package com.nuvio.tv.core.sportslive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #153: the full player's link failover is bounded by time and the links left, not by a count of 3; F1: and by a cap of links. */
class SportsLiveFailoverTest {

    private val start = 1_000_000L
    private val cap = SportsLiveStreamThresholds.MAX_FAILOVER_LINKS

    @Test
    fun `a first failure may always auto-try`() {
        assertTrue(SportsLiveFailover.canAutoTry(runStartedAtMs = null, nowMs = start, attempts = 0))
    }

    @Test
    fun `a run keeps trying links until its time is up`() {
        val run = SportsLiveStreamThresholds.FAILOVER_RUN_MS
        // Dead links, each failing at once, stay well inside the run.
        assertTrue(SportsLiveFailover.canAutoTry(runStartedAtMs = start, nowMs = start + 7 * 1_000L, attempts = 7))
        // The capped number of links that each time out at start-up fit in it too.
        assertTrue(cap * SportsLiveStreamThresholds.NO_FIRST_FRAME_MS <= run)
        assertTrue(SportsLiveFailover.canAutoTry(runStartedAtMs = start, nowMs = start + run - 1, attempts = 1))
        assertFalse(SportsLiveFailover.canAutoTry(runStartedAtMs = start, nowMs = start + run, attempts = 1))
    }

    @Test
    fun `a game with 9 links keeps trying past the third`() {
        assertTrue(cap > 3)
        assertTrue(SportsLiveFailover.canAutoTry(runStartedAtMs = start, nowMs = start + 12_000L, attempts = 3))
    }

    @Test
    fun `a run of dead links gives up after the cap, however many links are left and however fast they fail`() {
        // live.19 QA F1: 39 dead links, about 4 s each, were all cycled.
        assertTrue(SportsLiveFailover.canAutoTry(runStartedAtMs = start, nowMs = start + (cap - 1) * 4_000L, attempts = cap - 1))
        assertFalse(SportsLiveFailover.canAutoTry(runStartedAtMs = start, nowMs = start + cap * 4_000L, attempts = cap))
        assertFalse(SportsLiveFailover.canAutoTry(runStartedAtMs = start, nowMs = start + 39 * 4_000L, attempts = 38))
    }

    @Test
    fun `the cap is about 30 s of links that fail at once`() {
        assertTrue(cap in 5..10)
        assertTrue(cap * 4_000L <= 45_000L)
    }

    @Test
    fun `Try next link still plays the next link once the run gave up`() {
        val run = SportsLiveStreamThresholds.FAILOVER_RUN_MS
        assertTrue(SportsLiveFailover.canTryNext(runStartedAtMs = start, nowMs = start + 1_000L, attempts = cap, manual = true))
        assertTrue(SportsLiveFailover.canTryNext(runStartedAtMs = start, nowMs = start + run, attempts = 0, manual = true))
        assertFalse(SportsLiveFailover.canTryNext(runStartedAtMs = start, nowMs = start + 1_000L, attempts = cap, manual = false))
        assertTrue(SportsLiveFailover.canTryNext(runStartedAtMs = start, nowMs = start + 1_000L, attempts = 1, manual = false))
    }
}
