package com.nuvio.tv.ui.screens.sportslive.preview

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.nuvio.tv.core.sportslive.SportsLiveSourceReliability
import com.nuvio.tv.core.sportslive.SportsLiveSourceReliability.Outcome
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.screens.player.PlayerMediaSourceFactory
import com.nuvio.tv.ui.screens.sportslive.SportsLiveExtraSources
import com.nuvio.tv.ui.screens.sportslive.buildSportsLiveLightPlayer
import com.nuvio.tv.ui.screens.sportslive.isDecoderInitFailure
import com.nuvio.tv.ui.screens.sportslive.playSportsLiveStream

/**
 * The hover preview's one player, built like a dual screen pane's. There is never more than one: it is built for
 * the first preview and kept, and each next preview loads into it ([play]); [stop] ends a preview without freeing
 * the player (#167: no player built or released on the main thread per focus move, only its codec let go, which
 * ExoPlayer does on its own thread), and [release] frees it. It is muted for good (unless [withSound], the alerts'
 * peek): the audio track type is disabled before the stream is prepared (volume 0 alone does not mute AC3/EAC3
 * passthrough, and a disabled track uses no audio decoder), and it never asks for audio focus. Video is capped at
 * about 480p ([capPreviewVideo]) unless [lowBitrate] is turned off. While [suspended] (the screen is paused)
 * [play] does nothing, so a late call cannot build a player after the pause released it. A stream that buffers
 * for [STALL_TIMEOUT_MS] after its first frame counts as an error. Each load's first frame, error or stall is
 * remembered for its source ([SportsLiveSourceReliability]).
 */
@OptIn(UnstableApi::class)
internal class SportsLivePreviewPlayer(
    context: Context,
    private val onFirstFrame: (SportsLiveItem) -> Unit,
    private val onError: (SportsLiveItem) -> Unit,
    /** The device gave this player no decoder (another video holds it), before [onError]. */
    private val onDecoderInitFailure: () -> Unit = {},
    /** Ask for the lowest bitrate, at most about 480p ([capPreviewVideo]): every preview by default (#71, #83). */
    private val lowBitrate: Boolean = true,
    /** The alerts' peek (#136): full screen with sound, so the audio track stays on. */
    private val withSound: Boolean = false
) {
    private val appContext = context.applicationContext
    private var player: ExoPlayer? = null
    private var loaded: Pair<String, Stream>? = null
    private var view: PlayerView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var stallCheck: Runnable? = null

    /** The load playing now: its game, source and start, and whether its first frame came. */
    private var loadItem: SportsLiveItem? = null
    private var loadSourceKey: String = ""
    private var loadStartedAtMs = 0L
    private var rendered = false

    /** Set from ON_PAUSE to ON_RESUME: [play] is ignored meanwhile. */
    var suspended = false

    /** How many players this one has built (tests: never more than one at a time). */
    internal var built = 0
        private set

    /** The pane's view: it shows the player while one plays. */
    fun attach(view: PlayerView?) {
        if (this.view !== view) this.view?.player = null
        this.view = view
        view?.player = player
    }

    fun play(item: SportsLiveItem, stream: Stream) {
        val wanted = item.previewKey to stream
        if (suspended || wanted == loaded) return
        val url = stream.getStreamUrl() ?: return onError(item)
        stop()
        val exo = player ?: build().also { player = it }
        loaded = wanted
        loadItem = item
        loadSourceKey = SportsLiveExtraSources.sourceKeyFor(item, stream)
        loadStartedAtMs = System.currentTimeMillis()
        rendered = false
        view?.player = exo
        // A second decoder beside whatever plays: it lets go at once if the full player loses its own (#106).
        SportsLiveSecondDecoders.hold(this) { yieldDecoder() }
        // Its own instance: parallel connections and the VOD cache stay at their defaults (off).
        exo.playSportsLiveStream(appContext, PlayerMediaSourceFactory(appContext), stream, url)
    }

    private fun build(): ExoPlayer = buildSportsLiveLightPlayer(appContext).apply {
        built++
        if (!withSound) {
            volume = 0f
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
        }
        if (lowBitrate) trackSelectionParameters = trackSelectionParameters.capPreviewVideo()
        addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                val item = loadItem ?: return
                if (!rendered) {
                    SportsLiveSourceReliability.record(loadSourceKey, Outcome.SUCCESS, System.currentTimeMillis() - loadStartedAtMs)
                }
                rendered = true
                onFirstFrame(item)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                cancelStallCheck()
                val item = loadItem ?: return
                if (playbackState == Player.STATE_BUFFERING && rendered) {
                    val sourceKey = loadSourceKey
                    stallCheck = Runnable {
                        if (loadItem === item && player?.playbackState == Player.STATE_BUFFERING) {
                            SportsLiveSourceReliability.record(sourceKey, Outcome.STALL)
                            onError(item)
                        }
                    }.also { handler.postDelayed(it, STALL_TIMEOUT_MS) }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val item = loadItem ?: return
                // A missing decoder is the device's fault, not the source's.
                if (error.isDecoderInitFailure()) {
                    onDecoderInitFailure()
                } else {
                    SportsLiveSourceReliability.record(loadSourceKey, Outcome.FAILURE)
                }
                onError(item)
            }
        })
    }

    /**
     * Ends the preview playing, keeping the player for the next one: ExoPlayer lets go of its codec and stream on
     * its own thread, so the main thread never waits (#167). Does nothing when none plays.
     */
    fun stop() {
        cancelStallCheck()
        SportsLiveSecondDecoders.drop(this)
        val current = player ?: return
        if (loadItem == null) return
        loadItem = null
        loaded = null
        rendered = false
        current.stop()
        current.clearMediaItems()
    }

    /** Frees the player at once (the view lets go of it first); does nothing when none was built. */
    fun release() {
        cancelStallCheck()
        SportsLiveSecondDecoders.drop(this)
        val current = player ?: return
        player = null
        loaded = null
        loadItem = null
        rendered = false
        view?.player = null
        current.release()
    }

    /** The full player lost its decoder (#106): this player is freed at once and its preview ends as an error. */
    private fun yieldDecoder() {
        val item = loadItem
        release()
        item?.let(onError)
    }

    private fun cancelStallCheck() {
        stallCheck?.let(handler::removeCallbacks)
        stallCheck = null
    }

    private companion object {
        const val STALL_TIMEOUT_MS = 10_000L
    }
}

/**
 * These parameters asking for the lowest bitrate at most 854 x 480 (#83): a small corner preview
 * needs no more. A stream with nothing that small still plays its smallest rendition.
 */
internal fun TrackSelectionParameters.capPreviewVideo(): TrackSelectionParameters =
    buildUpon()
        .setMaxVideoSize(PREVIEW_MAX_WIDTH, PREVIEW_MAX_HEIGHT)
        .setForceLowestBitrate(true)
        .build()

internal const val PREVIEW_MAX_WIDTH = 854
internal const val PREVIEW_MAX_HEIGHT = 480
