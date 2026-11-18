package com.nuvio.tv.core.sportslive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveStallWatchdogTest {

    private val threshold = SportsLiveStallWatchdog.STALL_THRESHOLD_MS

    @Test
    fun `fires only after start and after the threshold`() {
        assertFalse(
            "not during startup, even past the threshold",
            SportsLiveStallWatchdog.isStalled(hasStartedPlaying = false, stalledForMs = threshold + 5_000)
        )
        assertFalse(
            "not before the threshold, even after start",
            SportsLiveStallWatchdog.isStalled(hasStartedPlaying = true, stalledForMs = threshold - 1)
        )
        assertTrue(SportsLiveStallWatchdog.isStalled(hasStartedPlaying = true, stalledForMs = threshold))
    }
}
