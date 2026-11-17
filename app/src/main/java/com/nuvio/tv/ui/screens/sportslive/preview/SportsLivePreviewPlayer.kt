package com.nuvio.tv.ui.screens.sportslive.preview

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
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
 * The hover preview's one player, built like a dual screen pane's. It exists only while a preview
 * plays: [play] releases any previous player before building the next, and [release] frees it, so
 * there is never more than one. It is muted for good: the audio track type is disabled before the
 * stream is prepared (volume 0 alone does not mute AC3/EAC3 passthrough, and a disabled track uses
 * no audio decoder), and it never asks for audio focus. While [suspended] (the screen is paused) [play]
 * does nothing, so a late call cannot build a player after the pause released it. A stream that
 * buffers for [STALL_TIMEOUT_MS] after its first frame counts as an error. Each load's first frame,
 * error or stall is remembered for its source ([SportsLiveSourceReliability]).
 */
@OptIn(UnstableApi::class)
internal class SportsLivePreviewPlayer(
    context: Context,
    private val onFirstFrame: (SportsLiveItem) -> Unit,
    private val onError: (SportsLiveItem) -> Unit
) {
    private val appContext = context.applicationContext
    private var player: ExoPlayer? = null
    private var loaded: Pair<String, Stream>? = null
    private var view: PlayerView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var stallCheck: Runnable? = null

    /** Set from ON_PAUSE to ON_RESUME: [play] is ignored meanwhile. */
    var suspended = false

    /** The pane's view: it shows the player while one plays. */
    fun attach(view: PlayerView?) {
        if (this.view !== view) this.view?.player = null
        this.view = view
        view?.player = player
    }

    fun play(item: SportsLiveItem, stream: Stream) {
        val wanted = item.previewKey to stream
        if (suspended || (wanted == loaded && player != null)) return
        release()
        val url = stream.getStreamUrl() ?: return onError(item)
        var rendered = false
        val sourceKey = SportsLiveExtraSources.sourceKeyFor(item, stream)
        val startedAtMs = System.currentTimeMillis()
        val built = buildSportsLiveLightPlayer(appContext).apply {
            volume = 0f
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    if (player !== this@apply) return
                    if (!rendered) {
                        SportsLiveSourceReliability.record(sourceKey, Outcome.SUCCESS, System.currentTimeMillis() - startedAtMs)
                    }
                    rendered = true
                    onFirstFrame(item)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (player !== this@apply) return
                    cancelStallCheck()
                    if (playbackState == Player.STATE_BUFFERING && rendered) {
                        val stalled = this@apply
                        stallCheck = Runnable {
                            if (player === stalled && stalled.playbackState == Player.STATE_BUFFERING) {
                                SportsLiveSourceReliability.record(sourceKey, Outcome.STALL)
                                onError(item)
                            }
                        }.also { handler.postDelayed(it, STALL_TIMEOUT_MS) }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (player !== this@apply) return
                    // A missing decoder is the device's fault, not the source's.
                    if (!error.isDecoderInitFailure()) SportsLiveSourceReliability.record(sourceKey, Outcome.FAILURE)
                    onError(item)
                }
            })
        }
        player = built
        loaded = wanted
        view?.player = built
        // Its own instance: parallel connections and the VOD cache stay at their defaults (off).
        built.playSportsLiveStream(appContext, PlayerMediaSourceFactory(appContext), stream, url)
    }

    /** Frees the player at once (the view lets go of it first); does nothing when none plays. */
    fun release() {
        cancelStallCheck()
        val current = player ?: return
        player = null
        loaded = null
        view?.player = null
        current.release()
    }

    private fun cancelStallCheck() {
        stallCheck?.let(handler::removeCallbacks)
        stallCheck = null
    }

    private companion object {
        const val STALL_TIMEOUT_MS = 10_000L
    }
}
