package com.nuvio.tv.core.sportslive

/**
 * Decides when a Sports & Live source that already started playing has gone stale (endless
 * buffering, or the position stops advancing) and should be treated as failed so the player can
 * auto-fail over to another source.
 *
 * It never fires during initial startup — the first-frame/startup timeout covers that — so
 * [hasStartedPlaying] must be true. The threshold is generous so a slow-but-recovering stream is
 * not dropped.
 */
object SportsLiveStallWatchdog {
    const val STALL_THRESHOLD_MS = 18_000L

    /**
     * @param hasStartedPlaying the first frame has been seen (playback actually started).
     * @param stalledForMs how long the player has been buffering / not advancing without progress.
     * @param thresholdMs how long a stall must last before the source is treated as failed.
     */
    fun isStalled(
        hasStartedPlaying: Boolean,
        stalledForMs: Long,
        thresholdMs: Long = STALL_THRESHOLD_MS
    ): Boolean = hasStartedPlaying && stalledForMs >= thresholdMs
}
