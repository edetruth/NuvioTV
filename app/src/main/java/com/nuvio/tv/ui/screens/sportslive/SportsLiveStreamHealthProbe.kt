package com.nuvio.tv.ui.screens.sportslive

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.SportsLiveStreamHealth
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * The ExoPlayer side of [SportsLiveStreamHealth] (#138): what the player shows now, as a sample.
 * The position is the one in the current period, so a live stream's position keeps growing while
 * its window slides (the window position alone stays about the same at the live edge). Frames are
 * the video decoder's rendered output buffers; null without a video track.
 */
@OptIn(UnstableApi::class)
internal fun ExoPlayer.sportsLiveHealthSample(playWhenReady: Boolean = this.playWhenReady): SportsLiveStreamHealth.Sample {
    val timeline = currentTimeline
    val position = if (timeline.isEmpty) {
        currentPosition
    } else {
        currentPosition - timeline.getPeriod(currentPeriodIndex, Timeline.Period()).positionInWindowMs
    }
    val frames = videoDecoderCounters?.takeIf { videoFormat != null }?.let {
        it.ensureUpdated()
        it.renderedOutputBufferCount.toLong()
    }
    return SportsLiveStreamHealth.Sample(
        state = when (playbackState) {
            Player.STATE_BUFFERING -> SportsLiveStreamHealth.State.BUFFERING
            Player.STATE_READY -> SportsLiveStreamHealth.State.READY
            Player.STATE_ENDED -> SportsLiveStreamHealth.State.ENDED
            else -> SportsLiveStreamHealth.State.IDLE
        },
        playWhenReady = playWhenReady,
        positionMs = position,
        renderedFrames = frames,
        isLive = !timeline.isEmpty && (isCurrentMediaItemLive || isCurrentMediaItemDynamic || duration == C.TIME_UNSET),
        isPlayingAd = isPlayingAd
    )
}

/**
 * Retries the link this player has loaded (#138): it loads again from the live edge, keeping the
 * last frame on screen meanwhile. Works after an error (the player is idle) and on a frozen stream.
 */
internal fun ExoPlayer.retrySportsLiveLink() {
    seekToDefaultPosition()
    prepare()
    playWhenReady = true
}

/** Runs [tick] every [SportsLiveStreamHealth.POLL_INTERVAL_MS] on the main thread while started. */
internal class SportsLiveHealthTicker(private val tick: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private val runnable = object : Runnable {
        override fun run() {
            if (!running) return
            tick()
            if (running) handler.postDelayed(this, SportsLiveStreamHealth.POLL_INTERVAL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.postDelayed(runnable, SportsLiveStreamHealth.POLL_INTERVAL_MS)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(runnable)
    }
}

/**
 * "Reconnecting…" (#138): a small chip the owner places at its video's top-left, over the last
 * frame, while a stream is recovered. Never placed over the bottom of the picture (the scorebug).
 */
@Composable
fun SportsLiveReconnectingChip(modifier: Modifier = Modifier, small: Boolean = false) {
    Text(
        text = stringResource(R.string.sports_live_reconnecting),
        style = if (small) SportsLiveTokens.style(SportsLiveTokens.Type.Tag) else MaterialTheme.typography.labelLarge,
        color = NuvioTheme.colors.TextPrimary,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(NuvioTheme.radii.sm))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = if (small) 8.dp else 12.dp, vertical = if (small) 2.dp else 6.dp)
    )
}
