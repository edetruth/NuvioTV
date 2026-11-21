package com.nuvio.tv.core.sportslive.scores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveExcitementTest {

    private fun team(score: Int?) = ScoreTeam(null, null, null, null, null, null, null, score, emptyList())

    private fun game(
        sport: String,
        home: Int?,
        away: Int?,
        clock: String?,
        id: String = "$sport|$clock|$home-$away",
        state: ScoreState = ScoreState.IN,
        situation: GameSituation? = null
    ) = ScoreSnapshot(
        eventId = id,
        league = EspnLeague(sport, "x"),
        startMs = 0L,
        state = state,
        clockText = clock,
        isFinal = false,
        home = team(home),
        away = team(away),
        situation = situation
    )

    /** [games] most exciting first, by [LiveExcitement.rate]. */
    private fun ranked(vararg games: ScoreSnapshot): List<ScoreSnapshot> =
        games.sortedByDescending { LiveExcitement.rate(it)!!.score }

    private fun tag(game: ScoreSnapshot, recent: RecentChange = RecentChange.NONE) = LiveExcitement.rate(game, recent)?.tag

    @Test
    fun `basketball - overtime, then close in the final minutes, then an early tie, then a blowout`() {
        val blowout = game("basketball", 100, 80, "1:30 - 4th")
        val earlyTie = game("basketball", 20, 20, "8:00 - 1st")
        val closeLate = game("basketball", 100, 98, "1:30 - 4th")
        val overtime = game("basketball", 110, 108, "2:00 - OT")
        assertEquals(listOf(overtime, closeLate, earlyTie, blowout), ranked(blowout, earlyTie, closeLate, overtime))
        assertEquals(WhyTag(WhyKind.OVERTIME), tag(overtime))
        assertEquals(WhyTag(WhyKind.CLOSE_LATE, 2), tag(closeLate))
        assertNull(tag(earlyTie))
        assertNull(tag(blowout))
        // Six points is not close in basketball; five is.
        assertEquals(WhyTag(WhyKind.CLOSE_LATE, 1), tag(game("basketball", 90, 85, "Q4 0:40")))
        assertNull(tag(game("basketball", 90, 84, "Q4 0:40")))
        // Under a minute ESPN shows tenths.
        assertEquals(WhyTag(WhyKind.CLOSE_LATE, 1), tag(game("basketball", 100, 99, "45.2 - 4th")))
        assertEquals(WhyTag(WhyKind.CLOSE_LATE, 1), tag(game("hockey", 2, 2, "12.0 - 3rd")))
        // College halves.
        assertEquals(WhyTag(WhyKind.CLOSE_LATE, 3), tag(game("basketball", 60, 58, "2:30 - 2nd Half")))
    }

    @Test
    fun `football - a one-score game late, the red zone, then a blowout`() {
        val closeLate = game("football", 21, 14, "2:10 - 4th")
        val redZone = game("football", 14, 14, "8:00 - 2nd", situation = GameSituation(redZone = true))
        val blowout = game("football", 38, 10, "2:10 - 4th")
        assertEquals(listOf(closeLate, redZone, blowout), ranked(blowout, redZone, closeLate))
        assertEquals(WhyTag(WhyKind.CLOSE_LATE, 3), tag(closeLate))
        assertEquals(WhyTag(WhyKind.RED_ZONE), tag(redZone))
        assertNull(tag(blowout))
        // Nine points is two scores.
        assertNull(tag(game("football", 23, 14, "2:10 - 4th")))
    }

    @Test
    fun `soccer - penalties, extra time, a late one-goal game, then a rout`() {
        val pens = game("soccer", 1, 1, "Pens")
        val extraTime = game("soccer", 2, 2, "105'")
        val late = game("soccer", 1, 1, "88'")
        val stoppage = game("soccer", 2, 1, "90'+3'")
        val rout = game("soccer", 3, 0, "30'")
        assertEquals(listOf(pens, extraTime, late, stoppage, rout), ranked(rout, stoppage, late, extraTime, pens))
        assertEquals(WhyTag(WhyKind.PENALTIES), tag(pens))
        assertEquals(WhyTag(WhyKind.EXTRA_TIME), tag(extraTime))
        assertEquals(WhyTag(WhyKind.CLOSE_LATE, 2), tag(late))
        // Stoppage time has no whole minutes left to count.
        assertEquals(WhyTag(WhyKind.CLOSE_LATE, null), tag(stoppage))
        assertNull(tag(rout))
        // A level game at halftime is not news: "close" is only ever flagged late (CLOSE_LATE).
        assertNull(tag(game("soccer", 0, 0, "HT")))
        assertNull(tag(game("soccer", 1, 1, "59'")))
        assertNull(tag(game("soccer", 0, 2, "88'")))
    }

    @Test
    fun `hockey - shootout, overtime, a one-goal third, then a three-goal game`() {
        val shootout = game("hockey", 3, 3, "SO")
        val overtime = game("hockey", 2, 2, "2:00 - OT")
        val closeLate = game("hockey", 2, 1, "3:00 - 3rd")
        val wide = game("hockey", 4, 1, "4:00 - 2nd")
        assertEquals(listOf(shootout, overtime, closeLate, wide), ranked(wide, closeLate, overtime, shootout))
        assertEquals(WhyTag(WhyKind.SHOOTOUT), tag(shootout))
        assertEquals(WhyTag(WhyKind.OVERTIME), tag(overtime))
        assertEquals(WhyTag(WhyKind.CLOSE_LATE, 3), tag(closeLate))
        assertNull(tag(wide))
    }

    @Test
    fun `baseball - extra innings, close late, bases loaded late, then early innings`() {
        val extras = game("baseball", 4, 4, "Bot 10th")
        val closeLate = game("baseball", 3, 2, "Bot 9th")
        val loaded = game("baseball", 6, 2, "Top 8th", situation = GameSituation(onFirst = true, onSecond = true, onThird = true))
        val early = game("baseball", 1, 0, "Top 3rd")
        assertEquals(listOf(extras, closeLate, loaded, early), ranked(early, loaded, closeLate, extras))
        assertEquals(WhyTag(WhyKind.EXTRA_INNINGS), tag(extras))
        assertEquals(WhyTag(WhyKind.CLOSE_LATE, null), tag(closeLate))
        assertEquals(WhyTag(WhyKind.BASES_LOADED), tag(loaded))
        assertNull(tag(early))
        // Runners in scoring position count late in a close game only (the runners tag before "Close · late").
        val risp = GameSituation(onSecond = true)
        assertEquals(WhyTag(WhyKind.SCORING_POSITION), tag(game("baseball", 5, 3, "Mid 8th", situation = risp)))
        assertNull(tag(game("baseball", 5, 3, "Mid 4th", situation = risp)))
        assertTrue(
            LiveExcitement.rate(game("baseball", 3, 2, "Bot 9th", situation = risp))!!.score >
                LiveExcitement.rate(closeLate)!!.score
        )
    }

    @Test
    fun `games with no score, not in play or of an unrated sport are not rated`() {
        assertNull(LiveExcitement.rate(game("basketball", null, 80, "Q3 4:12")))
        assertNull(LiveExcitement.rate(game("basketball", 80, 80, "7:00 PM", state = ScoreState.PRE)))
        assertNull(LiveExcitement.rate(game("basketball", 80, 80, "Final", state = ScoreState.POST)))
        assertNull(LiveExcitement.rate(game("tennis", 1, 1, "3rd set")))
        assertNull(LiveExcitement.rate(game("mma", 0, 0, "R2")))
        // An unreadable clock still rates by closeness, with no time-based tag.
        val unreadable = LiveExcitement.rate(game("basketball", 80, 80, "Delayed"))!!
        assertNull(unreadable.tag)
        assertTrue(unreadable.score > 0)
    }

    @Test
    fun `a recent lead change or score lifts a game and tags it`() {
        val g = game("basketball", 50, 40, "6:00 - 2nd")
        val plain = LiveExcitement.rate(g)!!
        val scored = LiveExcitement.rate(g, RecentChange(scored = true))!!
        val flipped = LiveExcitement.rate(g, RecentChange(leadChange = true, scored = true))!!
        assertTrue(flipped.score > scored.score && scored.score > plain.score)
        assertEquals(WhyTag(WhyKind.LEAD_CHANGE), flipped.tag)
        assertEquals(WhyTag(WhyKind.JUST_SCORED), scored.tag)
        assertNull(plain.tag)
    }

    @Test
    fun `history sees a score and a lead change, through a tie, for five minutes`() {
        val history = ExcitementHistory()
        fun at(home: Int, away: Int) = game("soccer", home, away, "60'", id = "e1")
        val t0 = 1_000_000L
        history.observe(listOf(at(1, 0)), t0)
        // The first sight of a game records nothing.
        assertEquals(RecentChange.NONE, history.recent("e1", t0))

        history.observe(listOf(at(1, 1)), t0 + 60_000)
        assertEquals(RecentChange(leadChange = false, scored = true), history.recent("e1", t0 + 60_000))

        val flip = t0 + 120_000
        history.observe(listOf(at(1, 2)), flip)
        assertEquals(RecentChange(leadChange = true, scored = true), history.recent("e1", flip))
        // The same snapshot again changes nothing.
        history.observe(listOf(at(1, 2), at(1, 2)), flip + 30_000)
        assertTrue(history.recent("e1", flip + ExcitementHistory.RECENT_WINDOW_MS).leadChange)
        assertFalse(history.recent("e1", flip + ExcitementHistory.RECENT_WINDOW_MS + 1).leadChange)
        assertFalse(history.recent("e1", flip + ExcitementHistory.RECENT_WINDOW_MS + 1).scored)
    }

    @Test
    fun `just scored only where a score is news - goals and runs, football touchdowns, never basketball baskets`() {
        val history = ExcitementHistory()
        val t0 = 1_000_000L
        fun g(sport: String, id: String, home: Int, away: Int, clock: String) = game(sport, home, away, clock, id = id)
        history.observe(
            listOf(
                g("basketball", "nba", 50, 48, "6:00 - 2nd"),
                g("football", "fg", 7, 7, "8:00 - 2nd"),
                g("football", "td", 7, 7, "8:00 - 2nd"),
                g("hockey", "nhl", 1, 1, "5:00 - 2nd"),
                g("baseball", "mlb", 2, 1, "Top 5th")
            ),
            t0
        )
        val t1 = t0 + 30_000
        history.observe(
            listOf(
                // Both teams scored again: not news in basketball, and no lead change.
                g("basketball", "nba", 54, 51, "5:10 - 2nd"),
                g("football", "fg", 10, 7, "6:00 - 2nd"),
                g("football", "td", 7, 13, "6:00 - 2nd"),
                g("hockey", "nhl", 1, 2, "6:00 - 2nd"),
                g("baseball", "mlb", 3, 1, "Top 5th")
            ),
            t1
        )
        assertEquals(RecentChange.NONE, history.recent("nba", t1))
        assertEquals(RecentChange.NONE, history.recent("fg", t1))
        assertTrue(history.recent("td", t1).scored)
        assertTrue(history.recent("nhl", t1).scored)
        assertTrue(history.recent("mlb", t1).scored)
    }

    @Test
    fun `a basketball lead change still counts`() {
        val history = ExcitementHistory()
        val t0 = 1_000_000L
        history.observe(listOf(game("basketball", 50, 51, "6:00 - 2nd", id = "e1")), t0)
        history.observe(listOf(game("basketball", 53, 51, "5:40 - 2nd", id = "e1")), t0 + 30_000)
        assertEquals(RecentChange(leadChange = true, scored = false), history.recent("e1", t0 + 30_000))
    }

    @Test
    fun `a snapshot briefly without a score keeps the history`() {
        val history = ExcitementHistory()
        val t0 = 1_000_000L
        history.observe(listOf(game("soccer", 1, 0, "60'", id = "e1")), t0)
        history.observe(listOf(game("soccer", null, null, "61'", id = "e1")), t0 + 30_000)
        history.observe(listOf(game("soccer", null, null, "61'", id = "e1", state = ScoreState.UNKNOWN)), t0 + 45_000)
        history.observe(listOf(game("soccer", 1, 1, "62'", id = "e1")), t0 + 60_000)
        assertTrue(history.recent("e1", t0 + 60_000).scored)
    }

    @Test
    fun `history forgets games that leave play, and ignores finals and missing scores`() {
        val history = ExcitementHistory()
        val t0 = 1_000_000L
        history.observe(listOf(game("hockey", 1, 0, "5:00 - 2nd", id = "e1")), t0)
        history.observe(emptyList(), t0 + 1_000)
        // Seen again: a first sight, so no change is claimed.
        history.observe(listOf(game("hockey", 2, 3, "5:00 - 3rd", id = "e1")), t0 + 2_000)
        assertEquals(RecentChange.NONE, history.recent("e1", t0 + 2_000))

        history.observe(listOf(game("hockey", 2, 4, "Final", id = "e1", state = ScoreState.POST)), t0 + 3_000)
        assertEquals(RecentChange.NONE, history.recent("e1", t0 + 3_000))
        history.observe(listOf(game("hockey", null, 4, "5:00 - 3rd", id = "e2")), t0 + 4_000)
        assertEquals(RecentChange.NONE, history.recent("e2", t0 + 4_000))
    }

    @Test
    fun `clock text in both ESPN forms`() {
        val q4 = LiveExcitement.clockOf("basketball", "Q4 2:00")
        val dash = LiveExcitement.clockOf("basketball", "2:00 - 4th")
        assertEquals(q4, dash)
        assertTrue(q4.late)
        assertEquals(2, q4.minutesLeft)
        assertEquals(0.5, LiveExcitement.clockOf("basketball", "Halftime").progress!!, 1e-9)
        assertEquals(0.75, LiveExcitement.clockOf("football", "End of 3rd").progress!!, 1e-9)
        assertNull(LiveExcitement.clockOf("football", "8:42 - 3rd").minutesLeft)
        assertTrue(LiveExcitement.clockOf("hockey", "1:00 - 2OT").overtime)
        assertFalse(LiveExcitement.clockOf("soccer", "90'+4'").overtime)
        assertNull(LiveExcitement.clockOf("soccer", "90'+4'").minutesLeft)
        assertEquals(0, LiveExcitement.clockOf("basketball", "0.4 - 4th").minutesLeft)
        assertEquals(1, LiveExcitement.clockOf("football", "Q4 59.9").minutesLeft)
        assertNull(LiveExcitement.clockOf("basketball", "45.2 - 2nd").minutesLeft)
        assertTrue(LiveExcitement.clockOf("baseball", "Top 11th").overtime)
    }
}
