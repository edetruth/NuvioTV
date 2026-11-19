package com.nuvio.tv.core.sportslive.scores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Bucks Team page listed T. Herro and K. Ware (seen 2026-11-19), after a last game played away at Miami.
 * ESPN's own pre-game summary of the Bucks' next game lists them under MIL (id 15): they are Bucks this season,
 * so the page was right. Leaders follow the page's team id alone, never the side it played last or its opponent.
 */
class TeamLeadersTest {
    private val summary = TeamFixtures.summary("espn_summary_nba_mil_next_leaders.json")
    private val bucks = "15"
    private val blazers = "22"

    @Test
    fun `leaders come from the page's own team when it was the away side of its last game`() {
        val miami = ScoreTeam("14", "MIA", "Miami Heat", "Heat", "Heat", "Miami", null, 137, emptyList())
        val milwaukee = ScoreTeam(bucks, "MIL", "Milwaukee Bucks", "Bucks", "Bucks", "Milwaukee", null, 130, emptyList())
        val last = ScoreSnapshot(
            eventId = "401910009", league = EspnLeagueMap.NBA, startMs = 0L, state = ScoreState.POST, clockText = null,
            isFinal = true, home = miami, away = milwaukee
        )
        assertEquals("14", TeamPages.opponentOf(last, bucks).id)

        val leaders = TeamPages.leaders(summary, bucks)
        assertEquals(listOf("Points", "Assists", "Rebounds"), leaders.map { it.category })
        assertEquals(listOf("T. Herro", "T. Herro", "K. Ware"), leaders.map { it.player })
        assertEquals(listOf("19.4", "4.2", "6.5"), leaders.map { it.value })
    }

    @Test
    fun `the next opponent's leaders never show on the page`() {
        val bucksPlayers = TeamPages.leaders(summary, bucks).map { it.player }.toSet()
        val blazersPlayers = TeamPages.leaders(summary, blazers).map { it.player }.toSet()
        assertTrue(blazersPlayers.isNotEmpty())
        assertTrue(bucksPlayers.none { it in blazersPlayers })
    }
}
