package com.nuvio.tv.ui.screens.sportslive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveAwakeHoldsTest {

    @Test
    fun `the screen stays on while anyone holds it`() {
        val holds = SportsLiveAwakeHolds()
        val dual = Any()
        val mini = Any()

        assertTrue(holds.set(dual, awake = true))
        assertTrue(holds.set(mini, awake = true))
        assertTrue("the mini player stopping leaves dual's hold", holds.set(mini, awake = false))
        assertFalse(holds.set(dual, awake = false))
    }

    @Test
    fun `releasing twice or without a hold is harmless`() {
        val holds = SportsLiveAwakeHolds()
        val dual = Any()
        assertFalse(holds.set(dual, awake = false))
        assertTrue(holds.set(dual, awake = true))
        assertTrue(holds.set(dual, awake = true))
        assertFalse(holds.set(dual, awake = false))
        assertFalse(holds.set(dual, awake = false))
    }
}
