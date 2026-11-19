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
import com.nuvio.tv.core.sportslive.SportsLiveSourceReliability
import com.nuvio.tv.core.sportslive.SportsLiveSourceReliability.Outcome
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory
import com.nuvio.tv.ui.screens.sportslive.SportsLiveAwakeHolds
import com.nuvio.tv.ui.screens.sportslive.SportsLiveExtraSources
import com.nuvio.tv.ui.screens.sportslive.buildSportsLiveLightPlayer
import com.nuvio.tv.ui.screens.sportslive.isDecoderInitFailure
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniPlayers
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniStop
import com.nuvio.tv.ui.screens.sportslive.playSportsLiveStream

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
 */
@OptIn(UnstableApi::class)
internal class DualPlayers(
    context: Context,
    private val onFirstFrame: (SportsLiveItem) -> Unit,
    /** [decoderInit]: the player could not get a decoder. */
    private val onError: (item: SportsLiveItem, decoderInit: Boolean) -> Unit,
    /**
     * Whether [item]'s pane is playing (advancing: not paused, buffering or stopped). A stop or
     * release reports false, so time away from the screen never counts as played (#65).
     */
    private val onIsPlaying: (item: SportsLiveItem, playing: Boolean) -> Unit = { _, _ -> }
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
                    if (!rendered) {
                        rendered = true
                        SportsLiveSourceReliability.record(sourceKey, Outcome.SUCCESS, System.currentTimeMillis() - startedAtMs)
                    }
                    onFirstFrame(item)
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (load != loads) return
                    // A missing decoder is the device's fault, not the source's.
                    val decoderInit = error.isDecoderInitFailure()
                    if (!decoderInit) SportsLiveSourceReliability.record(sourceKey, Outcome.FAILURE)
                    onError(item, decoderInit)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (load == loads) report(item, isPlaying)
                }
            }.also(player::addListener)
        }

        fun release() {
            loads++
            report(null, false)
            player.release()
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
            if (wanted == loaded) return
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
            listenToLoad(wanted.first, wanted.second)
            player.playSportsLiveStream(appContext, mediaSourceFactory, wanted.second, url)
        }
    }

    private val slots = listOf(Slot(), Slot())
    // Compose state, so [isPlaying] follows a swap.
    private var bySide: Map<Side, Slot> by mutableStateOf(mapOf(Side.LEFT to slots[0], Side.RIGHT to slots[1]))
    private var released = false

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

