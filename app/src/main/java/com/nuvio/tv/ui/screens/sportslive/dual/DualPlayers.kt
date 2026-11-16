package com.nuvio.tv.ui.screens.sportslive.dual

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory

/**
 * The dual screen's two ExoPlayers. [sync] plays each pane of a [DualState] (a pane keeps its
 * player across a swap, so swapping does not restart either stream) and gives sound only to the
 * [DualState.audibleSide] pane: the other pane's audio track is disabled (volume 0 alone does not
 * mute AC3/EAC3 passthrough over HDMI, and a disabled track frees its audio decoder). Media sources come from the player's [PlayerMediaSourceFactory],
 * so a stream's proxy request headers are sent as in the full player.
 *
 * Neither player handles audio focus (the builder default): two players taking focus from each
 * other would pause one another.
 */
@OptIn(UnstableApi::class)
internal class DualPlayers(
    context: Context,
    private val onFirstFrame: (SportsLiveItem) -> Unit,
    /** [decoderInit]: the player could not get a decoder. */
    private val onError: (item: SportsLiveItem, decoderInit: Boolean) -> Unit
) {
    private val appContext = context.applicationContext

    private inner class Slot {
        // Decoder fallback as in the full player, so a second pane can fall back to a software decoder.
        // The FFmpeg audio renderer backs up the platform ones, as in the full player: live channels often
        // carry E-AC-3, which devices without that decoder could not play at all.
        val player: ExoPlayer = ExoPlayer.Builder(
            appContext,
            DefaultRenderersFactory(appContext)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        )
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
            .build()
        // Its own instance: parallel connections and the VOD cache stay at their defaults (off).
        val mediaSourceFactory = PlayerMediaSourceFactory(appContext)

        /** The game and stream this player plays; null when it is stopped. */
        var loaded: Pair<SportsLiveItem, Stream>? = null

        /** Counts loads; a load's listener drops events once a newer load (or a stop) has started. */
        private var loads = 0
        private var listener: Player.Listener? = null

        /** Replaces the listener with one that reports [item]'s events for this load only. */
        private fun listenToLoad(item: SportsLiveItem) {
            val load = ++loads
            listener?.let(player::removeListener)
            listener = object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    if (load == loads) onFirstFrame(item)
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (load == loads) onError(item, error.isDecoderInitFailure())
                }
            }.also(player::addListener)
        }

        fun release() {
            loads++
            player.release()
        }

        fun setAudible(audible: Boolean) {
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
                player.stop()
                player.clearMediaItems()
                return
            }
            loaded = wanted
            listenToLoad(wanted.first)
            val stream = wanted.second
            val request = PlayerMediaSourceFactory.normalizePlaybackRequest(
                url = url,
                headers = stream.behaviorHints?.proxyHeaders?.request
            )
            player.setMediaSource(
                mediaSourceFactory.createMediaSource(
                    context = appContext,
                    url = request.url,
                    headers = request.headers,
                    filename = stream.behaviorHints?.filename,
                    responseHeaders = stream.behaviorHints?.proxyHeaders?.response.orEmpty()
                )
            )
            player.prepare()
            player.playWhenReady = true
        }
    }

    private val slots = listOf(Slot(), Slot())
    private var bySide: Map<Side, Slot> = mapOf(Side.LEFT to slots[0], Side.RIGHT to slots[1])
    private var released = false

    /** The player showing [side]'s pane, for its video surface; null once released. */
    fun player(side: Side): ExoPlayer? = if (released) null else bySide.getValue(side).player

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
        for (side in Side.entries) {
            val slot = assigned.getValue(side)
            slot.show(state.pane(side))
            slot.setAudible(side == state.audibleSide)
        }
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

/** The game and stream a pane's player plays: one with a stream, still starting or playing. */
private fun DualPane.playable(): Pair<SportsLiveItem, Stream>? =
    stream?.takeIf { status == PaneStatus.Loading || status == PaneStatus.Playing }?.let { item to it }

private fun PlaybackException.isDecoderInitFailure(): Boolean {
    if (errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED
    ) return true
    var cause: Throwable? = cause
    while (cause != null) {
        if (cause is MediaCodecRenderer.DecoderInitializationException) return true
        cause = cause.cause
    }
    return false
}
