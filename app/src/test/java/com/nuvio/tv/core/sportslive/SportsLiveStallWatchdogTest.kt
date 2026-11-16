package com.nuvio.tv.core.sportslive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveStallWatchdogTest {

    private val threshold = SportsLiveStallWatchdog.STALL_THRESHOLD_MS

    @Test
    fun `does not fire during startup even past the threshold`() {
        assertFalse(
            SportsLiveStallWatchdog.isStalled(
                hasStartedPlaying = false,
                stalledForMs = threshold + 5_000
            )
        )
    }

    @Test
    fun `does not fire before the threshold even after start`() {
        assertFalse(
            SportsLiveStallWatchdog.isStalled(
                hasStartedPlaying = true,
                stalledForMs = threshold - 1
            )
        )
    }

    @Test
    fun `fires only after start and after the threshold`() {
        assertTrue(
            SportsLiveStallWatchdog.isStalled(
                hasStartedPlaying = true,
                stalledForMs = threshold
            )
        )
    }
}
