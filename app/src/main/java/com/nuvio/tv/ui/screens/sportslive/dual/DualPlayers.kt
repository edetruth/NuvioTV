package com.nuvio.tv.ui.screens.sportslive.dual

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException
import com.nuvio.tv.core.sportslive.SportsLiveSourceReliability
import com.nuvio.tv.core.sportslive.SportsLiveSourceReliability.Outcome
import com.nuvio.tv.core.sportslive.SportsLiveLinkErrors
import com.nuvio.tv.core.sportslive.SportsLiveStreamHealth
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory
import com.nuvio.tv.ui.screens.sportslive.SportsLiveAwakeHolds
import com.nuvio.tv.ui.screens.sportslive.SportsLiveExtraSources
import com.nuvio.tv.ui.screens.sportslive.SportsLiveHealthTicker
import com.nuvio.tv.ui.screens.sportslive.buildSportsLiveLightPlayer
import com.nuvio.tv.ui.screens.sportslive.isDecoderInitFailure
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniPlayers
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniStop
import com.nuvio.tv.ui.screens.sportslive.playSportsLiveStream
import com.nuvio.tv.ui.screens.sportslive.retrySportsLiveLink
import com.nuvio.tv.ui.screens.sportslive.sportsLiveHealthSample
import com.nuvio.tv.core.sportslive.SportsLiveStartupTimeout

/**
 * The dual screen's two ExoPlayers. [sync] plays each pane of a [DualState] (a pane keeps its
 * player across a swap, so swapping does not restart either stream) and gives sound only to the
 * [DualState.audibleSide] pane: the other pane's audio track is disabled (volume 0 alone does not
 * mute AC3/EAC3 passthrough over HDMI, and a disabled track frees its audio decoder). Media sources come from the player's [PlayerMediaSourceFactory],
 * so a stream's proxy request headers are sent as in the full player.
 *
 * Only the audible pane's player handles audio focus ([setAudioAttributes] with focus handling on),
 * so the dual screen holds focus like the full player (another app's audio pauses it, and it pauses
 * other apps) while the muted pane never takes focus from the audible one. [sync] mutes first, so
 * the pane losing sound gives focus up before the other requests it.
 *
 * The FFmpeg audio renderer is always on as a fallback (EXTENSION_RENDERER_MODE_ON): the user's
 * decoder priority setting of the full player is not applied here.
 *
 * Each player has its own [SportsLiveStreamHealth] (#138), sampled every second: a pane whose stream
 * freezes, buffers too long or ends while live is reported ([onUnhealthy]) for that pane only, and
 * whenever a pane's stream moves, [onMoving] (a recovery is over).
 */
@OptIn(UnstableApi::class)
internal class DualPlayers(
    context: Context,
    /** [item]'s pane showed the first frame of [stream]. */
    private val onFirstFrame: (item: SportsLiveItem, stream: Stream) -> Unit,
    /**
     * [decoderInit]: the player could not get a decoder. [problem]: [SportsLiveStreamHealth.Problem.ERROR],
     * or [SportsLiveStreamHealth.Problem.ERROR_BURST] when errors come in a burst.
     */
    private val onError: (
        item: SportsLiveItem,
        decoderInit: Boolean,
        problem: SportsLiveStreamHealth.Problem,
        stream: Stream
    ) -> Unit,
    /**
     * Whether [item]'s pane is playing (advancing: not paused, buffering or stopped). A stop or
     * release reports false, so time away from the screen never counts as played (#65).
     */
    private val onIsPlaying: (item: SportsLiveItem, playing: Boolean) -> Unit = { _, _ -> },
    /** [item]'s stream went bad after its first frame (#138). */
    private val onUnhealthy: (item: SportsLiveItem, problem: SportsLiveStreamHealth.Problem) -> Unit = { _, _ -> },
    /** [item]'s stream moves (a new frame or position), checked every second (#138). */
    private val onMoving: (item: SportsLiveItem) -> Unit = {},
    /** A manifest or segment request of [item]'s link failed before its first frame (#138). */
    private val onRequestFailed: (item: SportsLiveItem) -> Unit = {},
    /** What [item]'s link has loaded so far, every second (#158): its start-up timeout spares a link still loading. */
    private val onLoadProgress: (item: SportsLiveItem, progress: SportsLiveStartupTimeout.Progress) -> Unit = { _, _ -> }
) {
    private val appContext = context.applicationContext

    init {
        // Dual screen and the mini player never play sound together.
        SportsLiveMiniPlayers.controller.stop(SportsLiveMiniStop.DUAL_SCREEN)
    }

    private inner class Slot {
        val player: ExoPlayer = buildSportsLiveLightPlayer(appContext)
        // Its own instance: parallel connections and the VOD cache stay at their defaults (off).
        val mediaSourceFactory = PlayerMediaSourceFactory(appContext)

        /** The game and stream this player plays; null when it is stopped. */
        var loaded: Pair<SportsLiveItem, Stream>? = null

        /** [DualPane.attempt] of [loaded]: a higher one loads the same stream again (#138). */
        private var loadedAttempt = 0

        /** This player's stream health (#138). */
        private val health = SportsLiveStreamHealth()

        /** [Player.isPlaying] of the current load, as Compose state; false between loads. */
        var isPlaying by mutableStateOf(false)
            private set

        /** Counts loads; a load's listener drops events once a newer load (or a stop) has started. */
        private var loads = 0

        /** The game this player last reported as playing, or null. */
        private var reported: SportsLiveItem? = null

        /** Sets [isPlaying] and reports a change of the game playing ([item] when [playing]). */
        private fun report(item: SportsLiveItem?, playing: Boolean) {
            isPlaying = playing
            SportsLiveAwakeHolds.shared.set(this, playing)
            val now = item.takeIf { playing }
            val before = reported
            if (before == now) return
            reported = now
            before?.let { onIsPlaying(it, false) }
            now?.let { onIsPlaying(it, true) }
        }
        private var listener: Player.Listener? = null
        private var analyticsListener: AnalyticsListener? = null

        /**
         * Replaces the listener with one that reports [item]'s events for this load only; the load's
         * first frame or error is remembered for [stream]'s source ([SportsLiveSourceReliability]).
         */
        private fun listenToLoad(item: SportsLiveItem, stream: Stream) {
            val load = ++loads
            report(null, false)
            val sourceKey = SportsLiveExtraSources.sourceKeyFor(item, stream)
            val startedAtMs = System.currentTimeMillis()
            var rendered = false
            listener?.let(player::removeListener)
            listener = object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    if (load != loads) return
                    health.onFirstFrame()
                    if (!rendered) {
                        rendered = true
                        SportsLiveSourceReliability.record(sourceKey, Outcome.SUCCESS, System.currentTimeMillis() - startedAtMs)
                    }
                    onFirstFrame(item, stream)
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (load != loads) return
                    // Fell behind the live window (#138): back to the live edge on the same link, not an
                    // error; only when it keeps happening is it a stall.
                    if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                        val problem = health.onBehindLiveWindow()
                        if (problem == null) {
                            player.retrySportsLiveLink()
                        } else {
                            SportsLiveSourceReliability.record(sourceKey, Outcome.STALL)
                            onUnhealthy(item, problem)
                        }
                        return
                    }
                    // A missing decoder is the device's fault, not the source's.
                    val decoderInit = error.isDecoderInitFailure()
                    if (!decoderInit) SportsLiveSourceReliability.record(sourceKey, Outcome.FAILURE)
                    // A link whose host does not exist is dead: the next link, no retry (#138).
                    val problem = if (!decoderInit && SportsLiveLinkErrors.isDeadHost(error)) {
                        SportsLiveStreamHealth.Problem.DEAD_LINK
                    } else {
                        health.onError()
                    }
                    onError(item, decoderInit, problem, stream)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (load == loads) report(item, isPlaying)
                }
            }.also(player::addListener)
            analyticsListener?.let(player::removeAnalyticsListener)
            analyticsListener = object : AnalyticsListener {
                override fun onLoadError(
                    eventTime: AnalyticsListener.EventTime,
                    loadEventInfo: LoadEventInfo,
                    mediaLoadData: MediaLoadData,
                    error: IOException,
                    wasCanceled: Boolean
                ) {
                    if (load == loads && !rendered && !wasCanceled) onRequestFailed(item)
                }
            }.also(player::addAnalyticsListener)
        }

        fun release() {
            loads++
            report(null, false)
            player.release()
        }

        /** Samples this player's health (#138): reports a problem, or that a recovering stream moves. */
        fun checkHealth() {
            val (item, stream) = loaded ?: return
            onLoadProgress(item, SportsLiveStartupTimeout.Progress(mediaSourceFactory.bytesLoaded, player.totalBufferedDuration))
            val problem = health.sample(player.sportsLiveHealthSample())
            if (problem != null) {
                if (problem.isStall) {
                    SportsLiveSourceReliability.record(SportsLiveExtraSources.sourceKeyFor(item, stream), Outcome.STALL)
                }
                onUnhealthy(item, problem)
            } else if (health.isProgressing) {
                onMoving(item)
            }
        }

        /** Whether this player holds audio focus; null before the first [setAudible]. */
        private var handlesFocus: Boolean? = null

        fun setAudible(audible: Boolean) {
            if (handlesFocus != audible) {
                handlesFocus = audible
                player.setAudioAttributes(MEDIA_AUDIO, /* handleAudioFocus = */ audible)
            }
            player.volume = if (audible) 1f else 0f
            val disabled = !audible
            val params = player.trackSelectionParameters
            if (C.TRACK_TYPE_AUDIO in params.disabledTrackTypes != disabled) {
                player.trackSelectionParameters = params.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, disabled)
                    .build()
            }
        }

        fun show(pane: DualPane) {
            val wanted = pane.playable()
            if (wanted == loaded) {
                // The same stream, retried as is (#138): loaded again from the live edge, last frame kept.
                if (wanted != null && pane.attempt != loadedAttempt) {
                    loadedAttempt = pane.attempt
                    health.rebaseline()
                    player.retrySportsLiveLink()
                }
                return
            }
            val url = wanted?.second?.getStreamUrl()
            if (wanted == null || url == null) {
                loaded = null
                loads++
                report(null, false)
                player.stop()
                player.clearMediaItems()
                return
            }
            loaded = wanted
            loadedAttempt = pane.attempt
            health.reset()
            listenToLoad(wanted.first, wanted.second)
            player.playSportsLiveStream(appContext, mediaSourceFactory, wanted.second, url)
        }
    }

    private val slots = listOf(Slot(), Slot())
    // Compose state, so [isPlaying] follows a swap.
    private var bySide: Map<Side, Slot> by mutableStateOf(mapOf(Side.LEFT to slots[0], Side.RIGHT to slots[1]))
    private var released = false

    private val ticker = SportsLiveHealthTicker { slots.forEach(Slot::checkHealth) }.also { it.start() }

    /** The player showing [side]'s pane, for its video surface; null once released. */
    fun player(side: Side): ExoPlayer? = if (released) null else bySide.getValue(side).player

    /**
     * Whether [side]'s player is playing ([Player.isPlaying], for its current load only), as Compose
     * state; kept apart from [DualState], which only knows whether a first frame came.
     */
    fun isPlaying(side: Side): Boolean = bySide.getValue(side).isPlaying

    fun sync(state: DualState) {
        if (released) return
        // A player already playing a pane's game and stream stays with that pane.
        val assigned = mutableMapOf<Side, Slot>()
        for (side in Side.entries) {
            val playable = state.pane(side).playable() ?: continue
            slots.firstOrNull { it.loaded == playable && it !in assigned.values }?.let { assigned[side] = it }
        }
        for (side in Side.entries) {
            if (side !in assigned) assigned[side] = slots.first { it !in assigned.values }
        }
        bySide = assigned
        for (side in Side.entries) assigned.getValue(side).show(state.pane(side))
        // Muted first, so its focus is abandoned before the audible player requests it.
        val audible = state.audibleSide
        assigned.getValue(audible.other).setAudible(false)
        assigned.getValue(audible).setAudible(true)
    }

    /** Releases both players; later calls do nothing. */
    fun release() {
        if (released) return
        released = true
        ticker.stop()
        slots.forEach {
            it.loaded = null
            it.release()
        }
    }
}

private val MEDIA_AUDIO: AudioAttributes = AudioAttributes.Builder()
    .setUsage(C.USAGE_MEDIA)
    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
    .build()

/** The game and stream a pane's player plays: one with a stream, still starting or playing. */
private fun DualPane.playable(): Pair<SportsLiveItem, Stream>? =
    stream?.takeIf { status == PaneStatus.Loading || status == PaneStatus.Playing }?.let { item to it }

