package com.nuvio.tv.core.sportslive.scores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Crunch time per sport (#137) and the crunch alerts' limits. */
class CrunchTimeTest {

    private fun team(score: Int?) = ScoreTeam(null, null, null, null, null, null, null, score, emptyList())

    private fun game(
        sport: String,
        home: Int?,
        away: Int?,
        clock: String?,
        league: String = "x",
        state: ScoreState = ScoreState.IN,
        situation: GameSituation? = null,
        id: String = "$sport|$clock|$home-$away"
    ) = ScoreSnapshot(
        eventId = id,
        league = EspnLeague(sport, league),
        startMs = 0L,
        state = state,
        clockText = clock,
        isFinal = false,
        home = team(home),
        away = team(away),
        situation = situation
    )

    private fun crunch(game: ScoreSnapshot) = CrunchTime.isCrunch(game)

    @Test
    fun `basketball - within 5 in the last 2 minutes of regulation, or any overtime`() {
        // The user's game: Notre Dame 75-72 Auburn, 0:40 left in the 2nd half.
        assertTrue(crunch(game("basketball", 75, 72, "0:40 - 2nd Half", league = "mens-college-basketball")))
        assertTrue(crunch(game("basketball", 100, 95, "2:00 - 4th")))
        assertTrue(crunch(game("basketball", 100, 100, "Q4 1:12")))
        assertTrue(crunch(game("basketball", 99, 98, "45.2 - 4th")))
        assertFalse(crunch(game("basketball", 100, 94, "1:00 - 4th")))
        assertFalse(crunch(game("basketball", 100, 99, "2:01 - 4th")))
        assertFalse(crunch(game("basketball", 100, 99, "1:00 - 3rd")))
        assertFalse(crunch(game("basketball", 60, 59, "0:40 - 1st Half")))
        assertTrue(crunch(game("basketball", 110, 101, "3:12 - OT")))
        assertFalse(crunch(game("basketball", 50, 50, "Halftime")))
    }

    @Test
    fun `football - within 8 with under 5 minutes in the 4th, or overtime`() {
        assertTrue(crunch(game("football", 21, 14, "4:59 - 4th")))
        assertTrue(crunch(game("football", 24, 16, "0:30 - 4th")))
        assertFalse(crunch(game("football", 21, 14, "5:00 - 4th")))
        assertFalse(crunch(game("football", 24, 15, "1:00 - 4th")))
        assertTrue(crunch(game("football", 27, 27, "8:00 - OT")))
    }

    @Test
    fun `hockey - one goal in the last 5 minutes of the 3rd, or overtime or a shootout`() {
        assertTrue(crunch(game("hockey", 3, 2, "5:00 - 3rd")))
        assertTrue(crunch(game("hockey", 2, 2, "0:12 - 3rd")))
        assertFalse(crunch(game("hockey", 4, 2, "1:00 - 3rd")))
        assertFalse(crunch(game("hockey", 3, 2, "5:01 - 3rd")))
        assertFalse(crunch(game("hockey", 3, 2, "1:00 - 2nd")))
        assertTrue(crunch(game("hockey", 2, 2, "3:00 - OT")))
        assertTrue(crunch(game("hockey", 2, 2, "SO")))
    }

    @Test
    fun `soccer - level or one goal from the 80th minute, extra time and penalties too`() {
        assertTrue(crunch(game("soccer", 1, 0, "80'")))
        assertTrue(crunch(game("soccer", 2, 2, "90'+4'")))
        assertFalse(crunch(game("soccer", 1, 0, "79'")))
        assertFalse(crunch(game("soccer", 2, 0, "88'")))
        assertTrue(crunch(game("soccer", 1, 1, "105'")))
        assertTrue(crunch(game("soccer", 1, 1, "Pens")))
    }

    @Test
    fun `baseball - the tying run on base or at bat in the 9th or later`() {
        fun runners(first: Boolean = false, second: Boolean = false, third: Boolean = false) =
            GameSituation(onFirst = first, onSecond = second, onThird = third)
        // Bottom of the 9th, the home side down 1: the tying run is at bat.
        assertTrue(crunch(game("baseball", 3, 4, "Bot 9th")))
        // Down 3 with two on: the tying run is at bat.
        assertTrue(crunch(game("baseball", 2, 5, "Bot 9th", situation = runners(first = true, second = true))))
        // Down 3 with one on: not yet.
        assertFalse(crunch(game("baseball", 2, 5, "Bot 9th", situation = runners(first = true))))
        // Top of the 10th, the away side down 2 with one on.
        assertTrue(crunch(game("baseball", 6, 4, "Top 10th", situation = runners(third = true))))
        // Level in extra innings.
        assertTrue(crunch(game("baseball", 4, 4, "Top 11th")))
        // The batting side leading is no crunch; the 8th is too early; between halves no one bats.
        assertFalse(crunch(game("baseball", 2, 5, "Top 9th")))
        assertFalse(crunch(game("baseball", 3, 4, "Bot 8th")))
        assertFalse(crunch(game("baseball", 3, 4, "Mid 9th")))
    }

    @Test
    fun `never for a game not in play, without scores, or an individual sport`() {
        assertFalse(crunch(game("basketball", 75, 72, "0:40 - 2nd Half", state = ScoreState.POST)))
        assertFalse(crunch(game("basketball", null, 72, "0:40 - 2nd Half")))
        assertFalse(crunch(game("golf", 1, 1, "Round 4")))
    }

    @Test
    fun `the moment says overtime or the minutes left`() {
        assertEquals(CrunchMoment(false, 1, "0:40 - 2nd Half"), CrunchTime.of(game("basketball", 75, 72, "0:40 - 2nd Half")))
        assertEquals(2, CrunchTime.of(game("basketball", 75, 72, "1:30 - 4th"))?.minutesLeft)
        assertTrue(CrunchTime.of(game("hockey", 2, 2, "3:00 - OT"))!!.overtime)
        assertEquals(CrunchMoment(false, 2, "88'"), CrunchTime.of(game("soccer", 1, 1, "88'")))
        assertNull(CrunchTime.of(game("baseball", 3, 4, "Bot 9th"))?.minutesLeft)
    }

    // ---- The alerts' limits ----

    private fun candidate(id: String, big: Double = 0.0, watching: Boolean = false, crunch: Boolean = true) = CrunchCandidate(
        key = "card-$id",
        event = game("basketball", 75, if (crunch) 72 else 50, "0:40 - 2nd Half", id = id),
        bigScore = big,
        watching = watching
    )

    @Test
    fun `at most one alert per game, ever`() {
        val alerts = CrunchAlerts()
        assertEquals("a", alerts.next(0L, listOf(candidate("a")))?.event?.eventId)
        assertNull(alerts.next(CrunchAlerts.GAP_MS, listOf(candidate("a"))))
        assertNull(alerts.next(10 * CrunchAlerts.GAP_MS, listOf(candidate("a"))))
    }

    @Test
    fun `at most one alert every 3 minutes overall`() {
        val alerts = CrunchAlerts()
        assertEquals("a", alerts.next(0L, listOf(candidate("a")))?.event?.eventId)
        assertNull(alerts.next(CrunchAlerts.GAP_MS - 1, listOf(candidate("b"))))
        assertEquals("b", alerts.next(CrunchAlerts.GAP_MS, listOf(candidate("b")))?.event?.eventId)
    }

    @Test
    fun `never for the game being watched, and the biggest game goes first`() {
        val alerts = CrunchAlerts()
        assertNull(alerts.next(0L, listOf(candidate("a", watching = true))))
        val pick = alerts.next(0L, listOf(candidate("small", big = 1.0), candidate("ranked", big = 6.0), candidate("watched", big = 9.0, watching = true)))
        assertEquals("ranked", pick?.event?.eventId)
        // A watched game is never remembered as alerted: once the user leaves it, it may still alert.
        assertEquals("watched", alerts.next(CrunchAlerts.GAP_MS, listOf(candidate("watched", big = 9.0)))?.event?.eventId)
    }

    @Test
    fun `a game not in crunch time never alerts`() {
        assertNull(CrunchAlerts().next(0L, listOf(candidate("a", crunch = false))))
    }
}
