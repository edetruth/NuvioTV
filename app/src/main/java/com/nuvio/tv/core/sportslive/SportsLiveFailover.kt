package com.nuvio.tv.core.sportslive

/**
 * Policy for Sports & Live automatic link failover (#28, #153). A failover run is the links tried one
 * after another while none shows a first frame: it goes on while untried links remain, for at most
 * [SportsLiveStreamThresholds.FAILOVER_RUN_MS] (each link start is itself bounded by
 * [SportsLiveStreamThresholds.NO_FIRST_FRAME_MS]); then the viewer chooses from the recovery overlay.
 * A game with 9 links keeps trying past the third. A first frame ends the run.
 */
object SportsLiveFailover {
    /** Whether another link may be auto-tried in a run that began at [runStartedAtMs] (null: none began yet). */
    fun canAutoTry(runStartedAtMs: Long?, nowMs: Long): Boolean =
        runStartedAtMs == null || nowMs - runStartedAtMs < SportsLiveStreamThresholds.FAILOVER_RUN_MS
}
