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

    /**
     * A link still starting is never given up sooner than this (#138): live.18's whole start-up
     * limit, so the progress rule below is never stricter than it was.
     */
    const val NO_FIRST_FRAME_MS = 15_000L

    /** The same, once a manifest or segment request of the link has already failed. */
    const val NO_FIRST_FRAME_AFTER_FAILED_REQUEST_MS = 8_000L

    /**
     * Past [NO_FIRST_FRAME_MS], a link still starting is given up only once it has loaded nothing new
     * this long (#158); [NO_FIRST_FRAME_AFTER_FAILED_REQUEST_MS] once a request failed. Measured on
     * 2026-11-19: every link that played had its first byte within 7.8 s and its first frame within
     * 9.0 s; links that failed did so with an error, not by hanging.
     */
    const val STARTUP_NO_PROGRESS_MS = 10_000L

    /** A link still starting is given up after this long even while it keeps loading (#158). */
    const val STARTUP_HARD_CAP_MS = 30_000L

    /** At least this many more bytes count as start-up progress; a live playlist reload alone does not. */
    const val STARTUP_PROGRESS_MIN_BYTES = 32L * 1024

    /**
     * A live stream that falls behind its live window is moved back to the live edge in place, up to
     * [BEHIND_LIVE_WINDOW_COUNT] - 1 times within [BEHIND_LIVE_WINDOW_WINDOW_MS]; the next one is a stall.
     */
    const val BEHIND_LIVE_WINDOW_COUNT = 3
    const val BEHIND_LIVE_WINDOW_WINDOW_MS = 60_000L

    /**
     * A sports lookup plays what came back after this long (#158), without waiting for slower addons;
     * their answers still arrive in the background ([ADDON_LOOKUP_TIMEOUT_MS]) for the Sources list and
     * the recovery ladder. Measured on 2026-11-19, cold answers clustered at 6.1 s and reached 15 s
     * (60 s for one addon), so a lookup that has nothing yet keeps waiting, up to [LOOKUP_HARD_CAP_MS].
     */
    const val LOOKUP_SOFT_DEADLINE_MS = 6_000L

    /** A sports lookup with nothing to play this long gives up (its addons keep answering in the background). */
    const val LOOKUP_HARD_CAP_MS = 20_000L

    /** Each addon's stream answer for a sports lookup may take this long, in the background (#158). */
    const val ADDON_LOOKUP_TIMEOUT_MS = 45_000L

    /**
     * A CH± zap waits at most this long for its game's lookup before skipping on (#158 follow-up); an explicit
     * Watch or OK (a panel pick) keeps [LOOKUP_HARD_CAP_MS]. The addons keep answering in the background.
     */
    const val ZAP_SKIP_WAIT_MS = 8_000L

    /** A CH± zap's lookup still running this long shows "Looking for streams…" with Skip. */
    const val ZAP_LOOKING_CARD_AFTER_MS = 1_500L

    /** A zap that lands on games with no streams skips at most this many before giving up. */
    const val MAX_ZAP_SKIPS = 5

    /** The starting card shows its elapsed time from this long after a link starts loading. */
    const val STARTING_ELAPSED_AFTER_MS = 3_000L
}
