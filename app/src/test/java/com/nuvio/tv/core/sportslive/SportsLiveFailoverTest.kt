package com.nuvio.tv.core.sportslive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveFailoverTest {

    @Test
    fun `auto-tries are allowed up to the cap, then stop so the overlay is shown`() {
        assertTrue(SportsLiveFailover.canAutoTry(attempts = 0))
        assertTrue(SportsLiveFailover.canAutoTry(attempts = 1))
        assertTrue(SportsLiveFailover.canAutoTry(attempts = 2))
        // Three distinct sources already tried: stop auto-trying and surface the manual overlay.
        assertFalse(SportsLiveFailover.canAutoTry(attempts = SportsLiveFailover.MAX_SOURCES))
        assertFalse(SportsLiveFailover.canAutoTry(attempts = SportsLiveFailover.MAX_SOURCES + 1))
    }

    @Test
    fun `a custom cap is honoured`() {
        assertTrue(SportsLiveFailover.canAutoTry(attempts = 0, cap = 1))
        assertFalse(SportsLiveFailover.canAutoTry(attempts = 1, cap = 1))
    }
}
