package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveFirstArrival.Answer.NO_PICK
import com.nuvio.tv.core.sportslive.SportsLiveFirstArrival.Answer.PENDING
import com.nuvio.tv.core.sportslive.SportsLiveFirstArrival.Answer.PICK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** live.20 B2: a start plays the first pick, giving better-ranked sources a short grace. */
class SportsLiveFirstArrivalTest {

    @Test
    fun `the best-ranked pick plays at once when every better-ranked source answered`() {
        assertEquals(0, SportsLiveFirstArrival.choose(listOf(PICK, PENDING, PENDING), graceOver = false))
        assertEquals(1, SportsLiveFirstArrival.choose(listOf(NO_PICK, PICK, PENDING), graceOver = false))
        assertEquals(2, SportsLiveFirstArrival.choose(listOf(NO_PICK, NO_PICK, PICK), graceOver = false))
    }

    @Test
    fun `a pick behind a better-ranked source still answering waits for the grace`() {
        assertNull(SportsLiveFirstArrival.choose(listOf(PENDING, PICK), graceOver = false))
        assertEquals(1, SportsLiveFirstArrival.choose(listOf(PENDING, PICK), graceOver = true))
        // Past the grace, still the best-ranked pick among those in.
        assertEquals(1, SportsLiveFirstArrival.choose(listOf(PENDING, PICK, PICK), graceOver = true))
    }

    @Test
    fun `nothing plays before any source has a pick`() {
        assertNull(SportsLiveFirstArrival.choose(listOf(PENDING, NO_PICK), graceOver = true))
        assertNull(SportsLiveFirstArrival.choose(listOf(NO_PICK, NO_PICK), graceOver = false))
        assertNull(SportsLiveFirstArrival.choose(emptyList(), graceOver = true))
    }

    @Test
    fun `the grace is well inside the five second start target`() {
        assertEquals(1_500L, SportsLiveFirstArrival.GRACE_MS)
        assertTrue(SportsLiveFirstArrival.GRACE_MS < SportsLiveStreamThresholds.LOOKUP_SOFT_DEADLINE_MS)
    }
}
