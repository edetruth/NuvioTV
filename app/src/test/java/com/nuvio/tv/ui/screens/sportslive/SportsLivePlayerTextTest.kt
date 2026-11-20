package com.nuvio.tv.ui.screens.sportslive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** live.19 QA F24 and F11: the player's cards name a game in words, and say a kickoff time only when it is real. */
class SportsLivePlayerTextTest {

    @Test
    fun `a leading emoji leaves no gap before the game (F24)`() {
        assertEquals("Team A @ Team B", sportsLivePlainTitle("⏱ Team A @ Team B"))
        assertEquals("Team A @ Team B", sportsLivePlainTitle("⏱️  Team A @ Team B"))
        assertEquals("Sports Channel 1", sportsLivePlainTitle("🏆 Sports Channel 1"))
    }

    @Test
    fun `pipes read as dots and spaces collapse`() {
        assertEquals("Moto3 · Round 21 · Free Practice", sportsLivePlainTitle("Moto3 | Round 21 | Free Practice"))
        assertEquals("Moto3 · Round 21", sportsLivePlainTitle("  Moto3  |Round 21 "))
    }

    @Test
    fun `emoji inside a title go too, words stay`() {
        assertEquals("Cricket · Team A vs Team B", sportsLivePlainTitle("🏏 Cricket | Team A vs Team B"))
        assertEquals("Team A vs Team B", sportsLivePlainTitle("Team A 🇺🇸 vs Team B"))
    }

    @Test
    fun `a plain title is left as it is`() {
        assertEquals("Team A vs Team B", sportsLivePlainTitle("Team A vs Team B"))
        assertEquals("St. Louis Blues @ L.A. Kings", sportsLivePlainTitle("St. Louis Blues @ L.A. Kings"))
    }

    @Test
    fun `a title of symbols only keeps its clean name`() {
        assertEquals("⚽", sportsLivePlainTitle("⚽"))
    }

    @Test
    fun `the kickoff prompt says a time only for a real kickoff near now (F11)`() {
        val now = 1_700_000_000_000L
        assertTrue(sportsLiveKickoffShowsTime(now, hasTimeOfDay = true, nowMs = now))
        assertTrue(sportsLiveKickoffShowsTime(now + 10 * 60_000L, hasTimeOfDay = true, nowMs = now))
        // A date-only start, or a placeholder midnight hours away, would read "12:00 AM" under "Kicking off now".
        assertFalse(sportsLiveKickoffShowsTime(now, hasTimeOfDay = false, nowMs = now))
        assertFalse(sportsLiveKickoffShowsTime(now - 9 * 3_600_000L, hasTimeOfDay = true, nowMs = now))
        assertFalse(sportsLiveKickoffShowsTime(null, hasTimeOfDay = true, nowMs = now))
    }
}
