package com.nuvio.tv.core.sportslive.report

import org.junit.Assert.assertEquals
import org.junit.Test

/** Report a problem (#172): the in-memory trails are bounded and keep the newest lines. */
class SportsLiveReportTrailTest {

    @Test
    fun `a ring keeps only the newest lines in order`() {
        val ring = SportsLiveReportTrail.Ring(3)
        for (n in 1..5) ring.add("line $n")
        assertEquals(listOf("line 3", "line 4", "line 5"), ring.lines())
    }

    @Test
    fun `the sign-in trail never grows past its bound`() {
        repeat(SportsLiveReportTrail.MAX_SIGN_IN + 10) { SportsLiveReportTrail.onSignInLine("line $it") }
        val lines = SportsLiveReportTrail.signInLines()
        assertEquals(SportsLiveReportTrail.MAX_SIGN_IN, lines.size)
        assertEquals("line ${SportsLiveReportTrail.MAX_SIGN_IN + 9}", lines.last())
    }

    @Test
    fun `the screen and focus are the latest given`() {
        SportsLiveReportTrail.onScreen("home")
        SportsLiveReportTrail.onScreen("player")
        SportsLiveReportTrail.onFocused("Celtics vs 76ers")
        assertEquals("player", SportsLiveReportTrail.screen)
        assertEquals("Celtics vs 76ers", SportsLiveReportTrail.focused)
    }
}
