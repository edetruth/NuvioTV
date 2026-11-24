package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.alerts.AlertCardClock
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `the time left line ticks only on an asking card that shows - never on a passive card, a countdown or one leaving (review R10)`() {
        val up = AlertCardClock(shownAtMs = 1_000L)
        assertTrue(timeLeftTicks(actionable = true, counting = false, leaving = false, clock = up))
        assertFalse(timeLeftTicks(actionable = false, counting = false, leaving = false, clock = up))
        assertFalse(timeLeftTicks(actionable = true, counting = true, leaving = false, clock = up))
        assertFalse(timeLeftTicks(actionable = true, counting = false, leaving = true, clock = up))
        // Waiting under an overlay: its clock stands still, so the line does too.
        assertFalse(timeLeftTicks(actionable = true, counting = false, leaving = false, clock = AlertCardClock(shownAtMs = null, shownMs = 4_000L)))
    }

    @Test
    fun `the time left line runs as one linear animation to empty over the card's remaining time (Gemini RC F6)`() {
        // Showed 4 s of 10: from 0.6 down to 0 over the 6 s left.
        val clock = AlertCardClock(shownAtMs = 1_000L)
        assertEquals(TimeLeftRun(from = 0.6f, remainingMs = 6_000L), timeLeftRun(clock, 10_000L, nowMs = 5_000L))
        // Out of time: nothing left to run.
        assertEquals(TimeLeftRun(from = 0f, remainingMs = 0L), timeLeftRun(clock, 10_000L, nowMs = 20_000L))
    }

    @Test
    fun `the time left line is read only when drawing - nothing recomposes the alert overlay while it runs (Gemini RC F6)`() {
        val host = java.io.File("src/main/java/com/nuvio/tv/ui/screens/sportslive/SportsLiveAlertsHost.kt").readText()
        val motion = java.io.File("src/main/java/com/nuvio/tv/ui/screens/sportslive/SportsLiveAlertMotion.kt").readText()
        // A delegated read (by) would read the animated value in composition, every frame.
        assertFalse(Regex("""by\s+rememberTimeLeft""").containsMatchIn(host))
        // No ticking clock state read in composition, no target recomputed per tick.
        val body = motion.substringAfter("fun rememberTimeLeft").substringBefore("fun Modifier.timeLeftLine")
        assertFalse(body.contains("mutableLongStateOf"))
        assertFalse(body.contains("animateFloatAsState"))
    }
}
