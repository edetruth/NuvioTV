package com.nuvio.tv.core.sportslive

/**
 * Debug builds only (live.20 B2): times a start from the viewer's key to its first frame, one [SportsLiveDebugTiming]
 * line per stage ("speed stage=… t=<ms since the key>"), so a TV session can read where the seconds go:
 * action (OK, CH±, a panel pick) → settled → resolved → navigate → playerStart → firstFrame.
 */
object SportsLiveSpeedClock {
    @Volatile
    private var actionAtMs = 0L

    @Volatile
    private var action = "none"

    /** The viewer asked for a start ([name]: ok, zap, pick); later stages are timed from here. */
    fun action(name: String) {
        actionAtMs = System.currentTimeMillis()
        action = name
        SportsLiveDebugTiming.log { "speed stage=action action=$name" }
    }

    /** A start reached [stage]; [detail] adds a few words (never a url or a host). */
    fun stage(stage: String, detail: String = "") {
        SportsLiveDebugTiming.log {
            "speed stage=$stage t=${System.currentTimeMillis() - actionAtMs} action=$action" + if (detail.isEmpty()) "" else " $detail"
        }
    }
}
