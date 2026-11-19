package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.ui.screens.sportslive.SportsLiveHeaderStop.DAY_NEWER
import com.nuvio.tv.ui.screens.sportslive.SportsLiveHeaderStop.DAY_OLDER
import com.nuvio.tv.ui.screens.sportslive.SportsLiveHeaderStop.FAILURES
import com.nuvio.tv.ui.screens.sportslive.SportsLiveHeaderStop.FILTER
import com.nuvio.tv.ui.screens.sportslive.SportsLiveHeaderStop.REFRESH
import com.nuvio.tv.ui.screens.sportslive.SportsLiveHeaderStop.RESUME
import com.nuvio.tv.ui.screens.sportslive.SportsLiveHeaderStop.RESUME_CLOSE
import com.nuvio.tv.ui.screens.sportslive.SportsLiveHeaderStop.TAB
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #153: the Results day arrows never trap the D-pad; Left and Right walk the header. */
class SportsLiveHeaderFocusTest {

    private fun left(stop: SportsLiveHeaderStop, date: Boolean = true, filter: Boolean = true, failures: Boolean = false) =
        sportsLiveHeaderNeighbour(stop, right = false, hasDate = date, hasFilter = filter, hasFailures = failures)

    private fun right(stop: SportsLiveHeaderStop, date: Boolean = true, filter: Boolean = true, failures: Boolean = false) =
        sportsLiveHeaderNeighbour(stop, right = true, hasDate = date, hasFilter = filter, hasFailures = failures)

    @Test
    fun `left from the older day arrow goes back to the tabs`() {
        assertEquals(TAB, left(DAY_OLDER))
        assertEquals(DAY_OLDER, left(DAY_NEWER))
    }

    @Test
    fun `right from the newer day arrow goes on to the next header control`() {
        assertEquals(DAY_NEWER, right(DAY_OLDER))
        assertEquals(FILTER, right(DAY_NEWER))
        assertEquals(FAILURES, right(DAY_NEWER, filter = false, failures = true))
        assertEquals(REFRESH, right(DAY_NEWER, filter = false))
    }

    @Test
    fun `the filter steps back to the newer day arrow on Results and to the tab elsewhere`() {
        assertEquals(DAY_NEWER, left(FILTER))
        assertEquals(TAB, left(FILTER, date = false))
        assertEquals(REFRESH, right(FILTER))
        assertEquals(FAILURES, right(FILTER, failures = true))
    }

    @Test
    fun `the ends of the header have no neighbour and a hidden stop has none`() {
        assertNull(left(TAB))
        assertNull(right(REFRESH))
        assertNull(right(DAY_OLDER, date = false))
    }

    @Test
    fun `Live now's Resume chip and its close sit between the tabs and the filter`() {
        fun l(stop: SportsLiveHeaderStop, filter: Boolean = true) =
            sportsLiveHeaderNeighbour(stop, right = false, hasDate = false, hasFilter = filter, hasFailures = false, hasResume = true)
        fun r(stop: SportsLiveHeaderStop, filter: Boolean = true) =
            sportsLiveHeaderNeighbour(stop, right = true, hasDate = false, hasFilter = filter, hasFailures = false, hasResume = true)
        assertEquals(TAB, l(RESUME))
        assertEquals(RESUME_CLOSE, r(RESUME))
        assertEquals(RESUME, l(RESUME_CLOSE))
        assertEquals(FILTER, r(RESUME_CLOSE))
        assertEquals(REFRESH, r(RESUME_CLOSE, filter = false))
        assertEquals(RESUME_CLOSE, l(FILTER))
        // No chip: the filter steps back to the tab as before.
        assertEquals(TAB, left(FILTER, date = false))
    }
}
