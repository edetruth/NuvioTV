package com.nuvio.tv.ui.screens.sportslive

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.core.sportslive.SportsLiveDebugTiming
import kotlinx.coroutines.delay

/**
 * Debug builds only (#158): one [SportsLiveDebugTiming] line a second while a sports link starts,
 * with its state, the bytes loaded and the buffered position, then the first frame time. [link] is a
 * short id of the link, never its url. Returns when [firstFrame] or [stillLoading] says so, or after
 * [maxMs].
 */
internal suspend fun traceSportsLiveStart(
    who: String,
    link: String,
    player: () -> ExoPlayer?,
    bytes: () -> Long,
    firstFrame: () -> Boolean,
    stillLoading: () -> Boolean,
    maxMs: Long = 60_000L
) {
    val startedAt = System.currentTimeMillis()
    val startBytes = bytes()
    SportsLiveDebugTiming.log { "start who=$who link=$link" }
    while (true) {
        delay(1_000L)
        val elapsed = System.currentTimeMillis() - startedAt
        if (!stillLoading()) {
            SportsLiveDebugTiming.log { "left who=$who link=$link t=$elapsed" }
            return
        }
        val p = player()
        if (firstFrame()) {
            SportsLiveDebugTiming.log { "firstFrame who=$who link=$link t=$elapsed bytes=${bytes() - startBytes}" }
            return
        }
        SportsLiveDebugTiming.log {
            "tick who=$who link=$link t=$elapsed state=${p?.playbackState?.let(::stateName)} " +
                "bytes=${bytes() - startBytes} buffered=${p?.totalBufferedDuration ?: -1} error=${p?.playerError?.errorCodeName}"
        }
        if (elapsed >= maxMs) return
    }
}

/** A short id for a link in a debug line: never the url. */
internal fun sportsLiveLinkId(url: String?): String =
    url?.let { Integer.toHexString(it.hashCode()) } ?: "none"

private fun stateName(state: Int): String = when (state) {
    Player.STATE_IDLE -> "IDLE"
    Player.STATE_BUFFERING -> "BUFFERING"
    Player.STATE_READY -> "READY"
    else -> "ENDED"
}
