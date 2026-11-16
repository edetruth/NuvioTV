package com.nuvio.tv.ui.screens.sportslive

import android.view.KeyEvent
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.data.local.InternalPlayerEngine
import java.util.Locale

/** A key that may seek inside a zap session's live window. */
enum class LiveRewindKey { LEFT_HOLD, RIGHT_HOLD, RIGHT_SHORT, REWIND, FAST_FORWARD }

/**
 * The live window of the playing item, in Exo window positions: [windowMs] is the window's duration,
 * [liveEdgeMs] the live edge (Exo's default position, the window end minus the target live offset)
 * and [behindEdgeMs] how far playback is behind that edge.
 */
data class LiveWindow(val isDynamic: Boolean, val windowMs: Long, val liveEdgeMs: Long, val behindEdgeMs: Long)

object SportsLiveRewind {
    const val STEP_MS = 10_000L
    const val MIN_WINDOW_MS = 30_000L

    /** Rewinding stops this far from the window start, which keeps moving while we are behind. */
    const val START_MARGIN_MS = 10_000L

    /** The indicator shows from this far behind the edge, so live-offset jitter does not flash it. */
    const val INDICATOR_MIN_BEHIND_MS = 5_000L

    /** A held key steps once every this many repeats (about four steps a second). */
    const val HOLD_REPEAT_STRIDE = 5

    /** The key for a key-down; LEFT counts only once held (its short press opens the zap panel). */
    fun keyFor(keyCode: Int, repeatCount: Int): LiveRewindKey? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> if (repeatCount > 0) LiveRewindKey.LEFT_HOLD else null
        KeyEvent.KEYCODE_DPAD_RIGHT -> if (repeatCount > 0) LiveRewindKey.RIGHT_HOLD else LiveRewindKey.RIGHT_SHORT
        KeyEvent.KEYCODE_MEDIA_REWIND -> LiveRewindKey.REWIND
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> LiveRewindKey.FAST_FORWARD
        else -> null
    }

    /**
     * The seek delta [key] may make, or null to keep upstream's live blocking: only a zap session's
     * ExoPlayer playback of a dynamic item whose window is longer than [MIN_WINDOW_MS] seeks, back by
     * up to [STEP_MS] (never past the window start margin) or forward by up to [STEP_MS] (never past
     * the live edge at [liveEdgeMs]). A short Right keeps upstream behavior.
     */
    fun seekDeltaMs(
        hasSession: Boolean,
        engine: InternalPlayerEngine,
        isDynamic: Boolean,
        windowMs: Long,
        liveEdgeMs: Long,
        positionFromEdgeMs: Long,
        key: LiveRewindKey
    ): Long? {
        if (!hasSession || engine != InternalPlayerEngine.EXOPLAYER || !isDynamic) return null
        if (windowMs <= MIN_WINDOW_MS) return null
        val edge = liveEdgeMs.coerceIn(0L, windowMs)
        val behind = positionFromEdgeMs.coerceIn(0L, edge)
        return when (key) {
            LiveRewindKey.RIGHT_SHORT -> null
            LiveRewindKey.LEFT_HOLD, LiveRewindKey.REWIND -> {
                // The current position is edge - behind; keep START_MARGIN_MS from the window start.
                val room = edge - behind - START_MARGIN_MS
                if (room <= 0L) null else -minOf(STEP_MS, room)
            }
            LiveRewindKey.RIGHT_HOLD, LiveRewindKey.FAST_FORWARD ->
                if (behind <= 0L) null else minOf(STEP_MS, behind)
        }
    }

    /** "1:20" (or "1:02:03") for the behind-live indicator. */
    fun formatBehind(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L) + 500L) / 1000L
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }

    /**
     * How far behind the edge to show, or null when the indicator stays hidden. [covered]: the
     * player controls, a player panel or dialog, or the live games panel is on screen.
     */
    fun indicatorBehindMs(
        hasSession: Boolean,
        engine: InternalPlayerEngine,
        window: LiveWindow?,
        covered: Boolean
    ): Long? {
        if (covered) return null
        window ?: return null
        val canSeek = seekDeltaMs(
            hasSession, engine, window.isDynamic, window.windowMs, window.liveEdgeMs, window.behindEdgeMs,
            LiveRewindKey.FAST_FORWARD
        ) != null
        return window.behindEdgeMs.takeIf { canSeek && it >= INDICATOR_MIN_BEHIND_MS }
    }
}

/** The current item's live window, read from Exo's timeline; null without a timeline. */
fun ExoPlayer.liveWindow(): LiveWindow? {
    val timeline = currentTimeline
    if (timeline.isEmpty) return null
    val window = timeline.getWindow(currentMediaItemIndex, Timeline.Window())
    val durationMs = window.durationMs.takeIf { it != C.TIME_UNSET } ?: 0L
    val edgeMs = window.defaultPositionMs.takeIf { it != C.TIME_UNSET && it > 0L } ?: durationMs
    return LiveWindow(
        isDynamic = window.isDynamic,
        windowMs = durationMs,
        liveEdgeMs = edgeMs,
        behindEdgeMs = (edgeMs - currentPosition).coerceAtLeast(0L)
    )
}

/**
 * Seeks a zap session's live ExoPlayer stream for a key-down when [SportsLiveRewind.seekDeltaMs]
 * allows it. True when the key was used (held repeats between steps are used without seeking);
 * false leaves the key to upstream, which blocks seeking on live playback.
 */
fun seekWithinSportsLiveWindow(
    event: KeyEvent,
    hasSession: Boolean,
    engine: InternalPlayerEngine,
    player: ExoPlayer?
): Boolean {
    if (event.action != KeyEvent.ACTION_DOWN || !hasSession || player == null) return false
    val key = SportsLiveRewind.keyFor(event.keyCode, event.repeatCount) ?: return false
    val window = player.liveWindow() ?: return false
    val delta = SportsLiveRewind.seekDeltaMs(
        hasSession, engine, window.isDynamic, window.windowMs, window.liveEdgeMs, window.behindEdgeMs, key
    ) ?: return false
    if (event.repeatCount > 0 && (event.repeatCount - 1) % SportsLiveRewind.HOLD_REPEAT_STRIDE != 0) return true
    if (delta >= window.behindEdgeMs) {
        // Back at the edge: Exo's default position also resets its live offset target.
        player.seekToDefaultPosition()
    } else {
        player.seekTo((player.currentPosition + delta).coerceAtLeast(0L))
    }
    return true
}
