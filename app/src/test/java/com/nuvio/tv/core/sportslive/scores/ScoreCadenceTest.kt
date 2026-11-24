package com.nuvio.tv.core.sportslive.scores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `a suspended game is checked at the upcoming pace, postponed and done games stop`() {
        val suspended = game.copy(state = ScoreState.OFF, mayResume = true)
        val postponed = game.copy(state = ScoreState.OFF, mayResume = false)
        assertEquals(300_000L, interval(EspnLeagueMap.NBA, listOf(suspended)))
        assertEquals(300_000L, interval(EspnLeagueMap.NBA, listOf(suspended, game)))
        assertEquals(30_000L, interval(EspnLeagueMap.NBA, listOf(suspended, live)))
        assertNull(interval(EspnLeagueMap.NBA, listOf(postponed)))
        assertNull(interval(EspnLeagueMap.NBA, listOf(postponed, game)))
        assertNull(interval(EspnLeagueMap.NBA, listOf(game)))
    }

    @Test
    fun `a feed with a followed team's live game polls every 15 s, others stay at 30 s`() {
        val other = live.copy(eventId = "other")
        fun withFollowed(matched: List<ScoreSnapshot>, followed: Set<String>, league: EspnLeague = EspnLeagueMap.NBA, failures: Int = 0) =
            ScoreCadence.nextIntervalMs(ScoreFeed(league, today), matched, matched, failures, null, today, now, followed)
        assertEquals(15_000L, withFollowed(listOf(live, other), setOf(live.eventId)))
        assertEquals(30_000L, withFollowed(listOf(other), setOf(live.eventId)))
        // A followed game not live yet: the usual pace.
        assertEquals(300_000L, withFollowed(listOf(game.copy(state = ScoreState.OFF, mayResume = true)), setOf(game.eventId)))
        // Backoff and the soccer/all floor still win.
        assertEquals(60_000L, withFollowed(listOf(live), setOf(live.eventId), failures = 2))
        assertEquals(60_000L, withFollowed(listOf(live), setOf(live.eventId), league = EspnLeagueMap.SOCCER_ALL))
    }

    @Test
    fun `after a failure a live feed's last answer is cut at about two minutes, whatever the backoff`() {
        // Tier 1 H1: the 5 min backoff (or a Retry-After) used to let a live score stand for 10 min and more.
        assertFalse(ScoreCadence.fresh(ageMs = 9 * 60_000L, intervalMs = 5 * 60_000L, failures = 1, live = true))
        assertTrue(ScoreCadence.fresh(ageMs = ScoreCadence.LIVE_STALE_MS, intervalMs = 15 * 60_000L, failures = 1, live = true))
        assertFalse(ScoreCadence.fresh(ageMs = ScoreCadence.LIVE_STALE_MS + 1, intervalMs = 15 * 60_000L, failures = 1, live = true))
        // Normal polling is untouched: soccer all's 60 s pace, and a failing feed with no game in play.
        assertTrue(ScoreCadence.fresh(ageMs = 123_000L, intervalMs = 60_000L, failures = 0, live = true))
        assertFalse(ScoreCadence.fresh(ageMs = 64_000L, intervalMs = 30_000L, failures = 0, live = true))
        assertTrue(ScoreCadence.fresh(ageMs = 9 * 60_000L, intervalMs = 5 * 60_000L, failures = 1, live = false))
        assertTrue(ScoreCadence.fresh(ageMs = 60 * 60_000L, intervalMs = null, failures = 0, live = false))
        // When the cut falls due.
        assertEquals(ScoreCadence.LIVE_STALE_MS - 30_000L + 1, ScoreCadence.liveStaleInMs(30_000L, failures = 1, live = true))
        assertNull(ScoreCadence.liveStaleInMs(30_000L, failures = 0, live = true))
        assertNull(ScoreCadence.liveStaleInMs(30_000L, failures = 1, live = false))
        assertNull(ScoreCadence.liveStaleInMs(ScoreCadence.LIVE_STALE_MS + 1, failures = 1, live = true))
    }
}
