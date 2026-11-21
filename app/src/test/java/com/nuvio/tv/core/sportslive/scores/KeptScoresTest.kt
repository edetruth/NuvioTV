package com.nuvio.tv.core.sportslive.scores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** live.23: the scores last shown, kept on disk for a reopen. */
class KeptScoresTest {

    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
    private val pacers = nba.first { it.eventId == "401909985" }
    private val savedAt = 1_800_000_000_000L

    private val live = pacers.copy(
        state = ScoreState.IN, isFinal = false, clockText = "Q3 4:12",
        home = pacers.home.copy(score = 88, linescores = listOf(30, 28, 30)),
        away = pacers.away.copy(score = 80, linescores = listOf(25, 30, 25))
    )

    @Test
    fun `a kept game reads back as a card draws it`() {
        val kept = KeptScores.decode(KeptScores.encode(mapOf("a|game" to live), savedAt), savedAt + 60_000L)!!
        assertEquals(savedAt, kept.savedAt)
        val back = kept.games.getValue("a|game")
        assertEquals(live.eventId, back.eventId)
        assertEquals(live.league, back.league)
        assertEquals(live.leagueSlug, back.leagueSlug)
        assertEquals(live.startMs, back.startMs)
        assertEquals(ScoreState.IN, back.state)
        assertEquals("Q3 4:12", back.clockText)
        assertEquals(live.home, back.home.copy(form = live.home.form))
        assertEquals(live.away, back.away.copy(form = live.away.form))
    }

    @Test
    fun `a copy older than 10 minutes, from the future or unreadable is not read back`() {
        val text = KeptScores.encode(mapOf("a|game" to live), savedAt)
        assertEquals(1, KeptScores.decode(text, savedAt + KeptScores.MAX_AGE_MS)!!.games.size)
        assertNull(KeptScores.decode(text, savedAt + KeptScores.MAX_AGE_MS + 1))
        assertNull(KeptScores.decode(text, savedAt - 1))
        assertNull(KeptScores.decode("not json", savedAt))
        assertNull(KeptScores.decode("", savedAt))
    }

    @Test
    fun `individual results are not kept and the file is bounded`() {
        val golf = live.copy(eventId = "golf", individual = GolfResult(null, emptyList()))
        val many = (0 until KeptScores.MAX_GAMES + 5).associate { "a|$it" to live.copy(eventId = "e$it") }
        val text = KeptScores.encode(many + ("a|golf" to golf), savedAt)
        val games = KeptScores.decode(text, savedAt)!!.games
        assertEquals(KeptScores.MAX_GAMES, games.size)
        assertNull(games["a|golf"])
    }
}
