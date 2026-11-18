package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.scores.SportsResults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #78: an empty Today after midnight points at last night's games under Yesterday. */
class SportsLiveResultsLastNightTest {

    @Test
    fun `until 6 AM an empty Today speaks of last night`() {
        assertEquals(ResultsTodayEmpty.LAST_NIGHT, resultsTodayEmpty(0))
        assertEquals(ResultsTodayEmpty.LAST_NIGHT, resultsTodayEmpty(2))
        assertEquals(ResultsTodayEmpty.LAST_NIGHT, resultsTodayEmpty(5))
        assertEquals(ResultsTodayEmpty.NO_FINALS_YET, resultsTodayEmpty(6))
        assertEquals(ResultsTodayEmpty.NO_FINALS_YET, resultsTodayEmpty(14))
        assertEquals(ResultsTodayEmpty.NO_FINALS_YET, resultsTodayEmpty(23))
    }

    @Test
    fun `only an empty Today offers yesterday's results`() {
        assertTrue(resultsOffersYesterday(SportsResults.TODAY, hasRows = false))
        assertFalse(resultsOffersYesterday(SportsResults.TODAY, hasRows = true))
        assertFalse(resultsOffersYesterday(SportsResults.YESTERDAY, hasRows = false))
        assertFalse(resultsOffersYesterday(null, hasRows = false))
    }
}
