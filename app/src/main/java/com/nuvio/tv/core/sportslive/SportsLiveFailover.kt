package com.nuvio.tv.core.sportslive

/**
 * Policy for Sports & Live automatic link failover (#28, #153). A failover run is the links tried one
 * after another while none shows a first frame: it goes on while untried links remain, for at most
 * [SportsLiveStreamThresholds.MAX_FAILOVER_LINKS] links and [SportsLiveStreamThresholds.FAILOVER_RUN_MS]
 * (each link start is itself bounded by [SportsLiveStreamThresholds.NO_FIRST_FRAME_MS]); then the viewer
 * chooses from the recovery overlay. A game with 9 links keeps trying past the third. A first frame ends
 * the run; another game starts a new one.
 */
object SportsLiveFailover {
    /**
     * Whether another link may be auto-tried in a run that began at [runStartedAtMs] (null: none began yet)
     * and has auto-tried [attempts] links so far.
     */
    fun canAutoTry(runStartedAtMs: Long?, nowMs: Long, attempts: Int): Boolean =
        attempts < SportsLiveStreamThresholds.MAX_FAILOVER_LINKS &&
            (runStartedAtMs == null || nowMs - runStartedAtMs < SportsLiveStreamThresholds.FAILOVER_RUN_MS)

    /** Whether the next link may be tried: always when the viewer asked ([manual]: "Try next link"), else [canAutoTry]. */
    fun canTryNext(runStartedAtMs: Long?, nowMs: Long, attempts: Int, manual: Boolean): Boolean =
        manual || canAutoTry(runStartedAtMs, nowMs, attempts)
}
