package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.alerts.AlertCardClock
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import org.junit.Assert.assertEquals
import org.junit.Test

class SportsLiveCardMotionTest {

    @Test
    fun `top right cards come in from the right and bottom left ones from the left`() {
        assertEquals(48, SportsLiveTokens.Motion.slideOffset(fromEnd = true, travelPx = 48))
        assertEquals(-48, SportsLiveTokens.Motion.slideOffset(fromEnd = false, travelPx = 48))
    }

    @Test
    fun `the motion numbers are the brief's`() {
        assertEquals(200, SportsLiveTokens.Motion.ENTER_MS)
        assertEquals(150, SportsLiveTokens.Motion.EXIT_MS)
        assertEquals(24f, SportsLiveTokens.Motion.TRAVEL.value, 0f)
    }

    @Test
    fun `the time left line shrinks linearly over the card's time`() {
        val clock = AlertCardClock(shownAtMs = 1_000L)
        assertEquals(1f, clock.timeLeftFraction(1_000L, 10_000L), 0.001f)
        assertEquals(0.5f, clock.timeLeftFraction(6_000L, 10_000L), 0.001f)
        assertEquals(0f, clock.timeLeftFraction(20_000L, 10_000L), 0.001f)
    }

    @Test
    fun `the time left line stops while the card waits under an overlay`() {
        // Showed 4 s, then the screen got busy (shownAtMs null): time passing changes nothing.
        val waiting = AlertCardClock(shownAtMs = null, shownMs = 4_000L)
        assertEquals(0.6f, waiting.timeLeftFraction(5_000L, 10_000L), 0.001f)
        assertEquals(0.6f, waiting.timeLeftFraction(99_000L, 10_000L), 0.001f)
    }

    @Test
    fun `a card that has not shown yet has all its time`() {
        assertEquals(1f, AlertCardClock().timeLeftFraction(5_000L, 10_000L), 0.001f)
    }
}
