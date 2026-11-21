package com.nuvio.tv.core.sportslive.scores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** live.23: a game first seen shows its score at once; only the game being watched keeps the first-seen hold. */
class ScoreDelayTest {

    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
    private val start = 1_000_000_000_000L
    private val base = nba.first { it.eventId == "401909985" }.copy(startMs = start)
    private val key = "card"
    private val delayMs = 60_000L

    private fun live(home: Int, away: Int, clock: String = "Q2 5:00") = base.copy(
        state = ScoreState.IN, isFinal = false, clockText = clock,
        home = base.home.copy(score = home), away = base.away.copy(score = away)
    )

    private fun final(home: Int, away: Int) = base.copy(
        state = ScoreState.POST, isFinal = true, clockText = "Final",
        home = base.home.copy(score = home), away = base.away.copy(score = away)
    )

    @Test
    fun `a game first seen live shows its score at once and later changes wait the delay`() {
        val delay = ScoreDelay()
        val t0 = start + 3_600_000L
        val first = delay.apply(mapOf(key to live(50, 48)), delayMs, t0).getValue(key)
        assertEquals(50, first.homeScore)
        assertEquals("Q2 5:00", first.clockText)
        assertNull(delay.nextChangeAt)
        // A basket at 10 s: the first score stays until the delay after it arrived.
        val basket = live(53, 48, "Q2 4:40")
        assertEquals(50, delay.apply(mapOf(key to basket), delayMs, t0 + 10_000).getValue(key).homeScore)
        assertEquals(t0 + 10_000 + delayMs, delay.nextChangeAt)
        assertEquals(50, delay.apply(mapOf(key to basket), delayMs, t0 + 10_000 + delayMs - 1).getValue(key).homeScore)
        assertEquals(53, delay.apply(mapOf(key to basket), delayMs, t0 + 10_000 + delayMs).getValue(key).homeScore)
    }

    @Test
    fun `a final first seen that may have just ended shows at once`() {
        val shown = ScoreDelay().apply(mapOf(key to final(110, 100)), delayMs, start + 3 * 3_600_000L).getValue(key)
        assertEquals(110, shown.homeScore)
        assertEquals(ScoreState.POST, shown.state)
    }

    @Test
    fun `the game being watched first seen live keeps its score withheld for the delay`() {
        val delay = ScoreDelay()
        val t0 = start + 3_600_000L
        val watched = setOf(base.eventId)
        val first = delay.apply(mapOf(key to live(50, 48)), delayMs, t0, holdFirstSeen = watched).getValue(key)
        assertEquals(ScoreState.IN, first.state)
        assertNull(first.homeScore)
        assertNull(first.clockText)
        assertEquals(t0 + delayMs, delay.nextChangeAt)
        assertNull(delay.apply(mapOf(key to live(50, 48)), delayMs, t0 + delayMs - 1, watched).getValue(key).homeScore)
        assertEquals(50, delay.apply(mapOf(key to live(50, 48)), delayMs, t0 + delayMs, watched).getValue(key).homeScore)
    }

    @Test
    fun `the game being watched first seen as a final that may have just ended is withheld too`() {
        val delay = ScoreDelay()
        val t0 = start + 3 * 3_600_000L
        val watched = setOf(base.eventId)
        assertNull(delay.apply(mapOf(key to final(110, 100)), delayMs, t0, watched).getValue(key).homeScore)
        assertEquals(110, delay.apply(mapOf(key to final(110, 100)), delayMs, t0 + delayMs, watched).getValue(key).homeScore)
        // Long over: as it is.
        val longOver = final(110, 100).copy(startMs = t0 - 20 * 3_600_000L)
        assertEquals(110, ScoreDelay().apply(mapOf(key to longOver), delayMs, t0, watched).getValue(key).homeScore)
    }

    @Test
    fun `a game shown at once and then played within the delay is withheld until a delayed version is known`() {
        val delay = ScoreDelay()
        val t0 = start + 3_600_000L
        assertEquals(50, delay.apply(mapOf(key to live(50, 48)), delayMs, t0).getValue(key).homeScore)
        // Played 20 s later: nothing of it is known from the delay ago, so its score waits (today's full hold).
        val watched = setOf(base.eventId)
        assertNull(delay.apply(mapOf(key to live(50, 48)), delayMs, t0 + 20_000, watched).getValue(key).homeScore)
        assertEquals(t0 + delayMs, delay.nextChangeAt)
        assertEquals(50, delay.apply(mapOf(key to live(50, 48)), delayMs, t0 + delayMs, watched).getValue(key).homeScore)
    }

    @Test
    fun `a game first seen before play is never withheld, watched or not`() {
        val pre = base.copy(state = ScoreState.PRE, isFinal = false, clockText = "7:00 PM", home = base.home.copy(score = null), away = base.away.copy(score = null))
        val shown = ScoreDelay().apply(mapOf(key to pre), delayMs, start - 60_000L, setOf(base.eventId)).getValue(key)
        assertEquals("7:00 PM", shown.clockText)
    }
}
