package com.nuvio.tv.core.sportslive.scores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Results' time away (live.20): a Game page opened from Results does not count. */
class ResultsAwayTest {

    private val min = 60_000L

    @Test
    fun `never left is no return`() {
        assertNull(ResultsAway().back(5 * min))
    }

    @Test
    fun `time on another tab or the player counts in full`() {
        val away = ResultsAway()
        away.leave(0)
        assertEquals(ResultsAway.Return(0, 12 * min), away.back(12 * min))
        // Read once: a second return finds nothing.
        assertNull(away.back(13 * min))
    }

    @Test
    fun `Game page time is left out, the player opened from it counts`() {
        val away = ResultsAway()
        away.leave(0)
        away.gamePageShown(true, 0)
        // 30 min on the Game page, then 4 min in the player from it, then 2 more on the Game page.
        away.gamePageShown(false, 30 * min)
        away.gamePageShown(true, 34 * min)
        away.gamePageShown(false, 36 * min)
        assertEquals(ResultsAway.Return(0, 4 * min), away.back(36 * min))
    }

    @Test
    fun `a Game page that starts before Results stops counts from the leave`() {
        val away = ResultsAway()
        away.gamePageShown(true, 0)
        away.leave(1_000)
        // Back from the Game page before its stop arrives: closed at the return.
        assertEquals(ResultsAway.Return(1_000, 0), away.back(20 * min))
        // Its late stop changes nothing.
        away.gamePageShown(false, 20 * min + 500)
        away.leave(21 * min)
        assertEquals(ResultsAway.Return(21 * min, 3 * min), away.back(24 * min))
    }
}
