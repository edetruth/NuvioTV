package com.nuvio.tv.core.sportslive.scores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ScoreCadenceTest {

    private val today = LocalDate.of(2026, 11, 15)
    private val now = Instant.parse("2026-11-15T19:00:00Z").toEpochMilli()
    private val game = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA).first()
    private val live = game.copy(state = ScoreState.IN)

    private fun interval(
        league: EspnLeague,
        matched: List<ScoreSnapshot>,
        date: LocalDate = today,
        failures: Int = 0,
        retryAfterMs: Long? = null
    ) = ScoreCadence.nextIntervalMs(ScoreFeed(league, date), matched, matched, failures, retryAfterMs, today, now)

    @Test
    fun `soccer all is never polled more often than every 60 s`() {
        assertEquals(30_000L, interval(EspnLeagueMap.NBA, listOf(live)))
        assertEquals(60_000L, interval(EspnLeagueMap.SOCCER_ALL, listOf(live)))
        assertEquals(60_000L, interval(EspnLeagueMap.SOCCER_ALL, listOf(live), failures = 1))
    }

    @Test
    fun `backoff grows to a 5 min cap`() {
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 300_000L, 300_000L),
            (1..5).map { interval(EspnLeagueMap.NBA, listOf(live), failures = it) })
    }

    @Test
    fun `past days stop after one fetch, unmatched feeds retry unless all final`() {
        assertNull(interval(EspnLeagueMap.NBA, listOf(live), date = today.minusDays(2)))
        assertEquals(30_000L, interval(EspnLeagueMap.NBA, listOf(live), date = today.minusDays(1)))
        assertEquals(300_000L, ScoreCadence.nextIntervalMs(ScoreFeed(EspnLeagueMap.NBA, today), emptyList(), listOf(live), 0, null, today, now))
        assertNull(ScoreCadence.nextIntervalMs(ScoreFeed(EspnLeagueMap.NBA, today), emptyList(), listOf(game), 0, null, today, now))
    }

    @Test
    fun `a game that is off is checked at the upcoming pace, and done games stop`() {
        val off = game.copy(state = ScoreState.OFF)
        assertEquals(300_000L, interval(EspnLeagueMap.NBA, listOf(off)))
        assertEquals(300_000L, interval(EspnLeagueMap.NBA, listOf(off, game)))
        assertEquals(30_000L, interval(EspnLeagueMap.NBA, listOf(off, live)))
        assertNull(interval(EspnLeagueMap.NBA, listOf(game)))
    }
}
