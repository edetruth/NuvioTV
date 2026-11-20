package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.domain.model.FollowedTeam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The smart replay lifetime (live.20 1c): each rule on its own. */
class ReplayLifetimeTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour
    private val end = Instant.parse("2026-11-19T04:00:00Z").toEpochMilli()

    /** An NBA game's length, as the repository reads it (3 h plus the late-start allowance). */
    private val length = 200 * minute
    private val facts = ReplayFacts(endMs = end, lengthMs = length)

    // --- Rule 3: how long it stays on its own ---

    @Test
    fun `an ordinary replay stays 48 h past its end`() {
        assertTrue(ReplayLifetime.keeps(facts, end + 47 * hour))
        assertFalse(ReplayLifetime.keeps(facts, end + 48 * hour))
    }

    @Test
    fun `a big game stays up to 4 days`() {
        val big = facts.copy(big = true)
        assertTrue(ReplayLifetime.keeps(big, end + 3 * day))
        assertFalse(ReplayLifetime.keeps(big, end + 4 * day))
    }

    // --- Rule 1: the team's next game ---

    @Test
    fun `it leaves once the team's next game has started`() {
        val next = facts.copy(nextGameStartMs = end + 20 * hour)
        assertTrue(ReplayLifetime.keeps(next, end + 19 * hour))
        assertFalse(ReplayLifetime.keeps(next, end + 20 * hour))
        // A big game too: the next game makes it old news.
        assertFalse(ReplayLifetime.keeps(next.copy(big = true), end + 21 * hour))
    }

    // --- Rule 2: the result is known ---

    @Test
    fun `it leaves once the replay played past halfway, not after 2 min`() {
        assertTrue(ReplayLifetime.keeps(facts.copy(replayPlayedMs = 2 * minute), end + hour))
        assertTrue(ReplayLifetime.keeps(facts.copy(replayPlayedMs = length / 2 - minute), end + hour))
        assertFalse(ReplayLifetime.keeps(facts.copy(replayPlayedMs = length / 2), end + hour))
        assertFalse(ReplayLifetime.replayWatched(10 * minute, 0L))
    }

    @Test
    fun `it leaves once watched live for a meaningful stretch`() {
        assertTrue(ReplayLifetime.keeps(facts.copy(liveWatchedMs = 5 * minute), end + hour))
        assertFalse(ReplayLifetime.keeps(facts.copy(liveWatchedMs = ReplayLifetime.LIVE_WATCHED_MS), end + hour))
    }

    @Test
    fun `it leaves once the final score was seen`() {
        assertFalse(ReplayLifetime.keeps(facts.copy(resultSeen = true), end + hour))
    }

    // --- Rule 4: Watch later ---

    @Test
    fun `a game saved for later stays until watched or 7 days, whatever else happened`() {
        val saved = facts.copy(savedAtMs = end - hour, nextGameStartMs = end + 20 * hour, big = false)
        assertTrue(ReplayLifetime.keeps(saved, end + 6 * day))
        assertFalse(ReplayLifetime.keeps(saved, end - hour + ReplayLifetime.WATCH_LATER_MS))
        assertFalse(ReplayLifetime.keeps(saved.copy(replayPlayedMs = length), end + day))
    }

    // --- Inputs ---

    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
    private val pacersGame = nba.first { it.eventId == "401909985" }
    private val pacers = FollowedTeam("Indiana Pacers", "Basketball")

    @Test
    fun `the next game is the followed team's next start after the game`() {
        val start = pacersGame.startMs!!
        val next = pacersGame.copy(eventId = "next", startMs = start + 2 * day)
        val later = pacersGame.copy(eventId = "later", startMs = start + 4 * day)
        val before = pacersGame.copy(eventId = "before", startMs = start - 2 * day)
        val others = nba.filter { it.eventId != pacersGame.eventId }
        val events = others + listOf(later, pacersGame, before, next)
        assertEquals(start + 2 * day, ReplayLifetime.nextGameStartMs(pacersGame, events, listOf(pacers)))
        // Only a followed team's games count; none known, none.
        assertNull(ReplayLifetime.nextGameStartMs(pacersGame, events, listOf(FollowedTeam("New York Knicks", "Basketball"))))
        assertNull(ReplayLifetime.nextGameStartMs(pacersGame, others + pacersGame, listOf(pacers)))
    }

    @Test
    fun `a close finish counts as big, a clear one does not`() {
        val close = pacersGame.copy(state = ScoreState.POST, home = pacersGame.home.copy(score = 101), away = pacersGame.away.copy(score = 99))
        val clear = pacersGame.copy(state = ScoreState.POST, home = pacersGame.home.copy(score = 120), away = pacersGame.away.copy(score = 99))
        assertTrue(ReplayLifetime.closeFinish(close))
        assertFalse(ReplayLifetime.closeFinish(clear))
        assertTrue(ReplayLifetime.big(close))
    }
}
