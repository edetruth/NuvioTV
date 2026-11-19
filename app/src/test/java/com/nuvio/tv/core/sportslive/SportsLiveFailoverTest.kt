package com.nuvio.tv.core.sportslive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #153: the full player's link failover is bounded by time and the links left, not by a count of 3. */
class SportsLiveFailoverTest {

    private val start = 1_000_000L

    @Test
    fun `a first failure may always auto-try`() {
        assertTrue(SportsLiveFailover.canAutoTry(runStartedAtMs = null, nowMs = start))
    }

    @Test
    fun `a run keeps trying links until its time is up`() {
        val run = SportsLiveStreamThresholds.FAILOVER_RUN_MS
        // Nine dead links, each failing at once, stay well inside the run.
        assertTrue(SportsLiveFailover.canAutoTry(runStartedAtMs = start, nowMs = start + 9 * 1_000L))
        // Nine links that each time out at start-up are still inside it too.
        assertTrue(9 * SportsLiveStreamThresholds.NO_FIRST_FRAME_MS < run)
        assertTrue(SportsLiveFailover.canAutoTry(runStartedAtMs = start, nowMs = start + run - 1))
        assertFalse(SportsLiveFailover.canAutoTry(runStartedAtMs = start, nowMs = start + run))
    }
}
