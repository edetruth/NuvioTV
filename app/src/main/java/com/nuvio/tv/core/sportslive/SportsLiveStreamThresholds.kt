package com.nuvio.tv.core.sportslive

/**
 * Every limit of the sports stream failover (#138), in one place. Tuned with the stream session of
 * 2026-11-18: the longest stall that recovered was about 5 s; HLS manifests opened in 0.85 s
 * (median), 3.3 s (p90) and 6.8 s (max); the slowest sports addon answered in 6.1 s at p90.
 */
object SportsLiveStreamThresholds {
    /** How often a sports player's health is sampled. */
    const val POLL_INTERVAL_MS = 1_000L

    /** Buffering this long after the first frame, while meant to play, is a stall. */
    const val BUFFERING_TOO_LONG_MS = 12_000L

    /** No new frame and no position advance this long, while meant to play, is a frozen stream. */
    const val FROZEN_MS = 8_000L

    /** [ERROR_BURST_COUNT] playback errors within this window: the link is not retried as is. */
    const val ERROR_BURST_WINDOW_MS = 30_000L
    const val ERROR_BURST_COUNT = 2

    /** At most this many automatic recoveries per game within [RECOVERY_WINDOW_MS], then the viewer chooses. */
    const val MAX_RECOVERIES = 3
    const val RECOVERY_WINDOW_MS = 10L * 60 * 1000

    /**
     * Links that never show a first frame are failed over one after another for at most this long (#153),
     * whatever their number; then the viewer chooses. They do not spend [MAX_RECOVERIES], which is for a
     * game that was playing.
     */
    const val FAILOVER_RUN_MS = 3L * 60 * 1000

    /** No first frame this long after a link starts loading: the next link is tried. */
    const val NO_FIRST_FRAME_MS = 15_000L

    /** The same, once a manifest or segment request of the link has already failed. */
    const val NO_FIRST_FRAME_AFTER_FAILED_REQUEST_MS = 8_000L

    /**
     * A live stream that falls behind its live window is moved back to the live edge in place, up to
     * [BEHIND_LIVE_WINDOW_COUNT] - 1 times within [BEHIND_LIVE_WINDOW_WINDOW_MS]; the next one is a stall.
     */
    const val BEHIND_LIVE_WINDOW_COUNT = 3
    const val BEHIND_LIVE_WINDOW_WINDOW_MS = 60_000L

    /** Each addon's stream answer for a sports lookup may take this long. */
    const val ADDON_LOOKUP_TIMEOUT_MS = 6_000L

    /** A zap that lands on games with no streams skips at most this many before giving up. */
    const val MAX_ZAP_SKIPS = 5

    /** The starting card shows its elapsed time from this long after a link starts loading. */
    const val STARTING_ELAPSED_AFTER_MS = 3_000L
}
