package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * #208: the card "UCF vs Iowa State" tagged NCAAF, 23:00Z on 2026-11-20, against ESPN's real FBS scoreboard
 * (groups=80) of that day, where the game is "Iowa State Cyclones at UCF Knights", in progress.
 */
class CollegeFootballScoresTest {

    private val start = Instant.parse("2026-11-20T23:00:00Z").toEpochMilli()
    private val eastern = ZoneId.of("America/New_York")
    private val events = EspnFixtures.events(EspnFixtures.CFB_FBS_20261120, EspnLeagueMap.COLLEGE_FOOTBALL)

    private fun card(key: String) = ScoreCard(key, SportsLiveTeams.parse("UCF vs Iowa State")!!, start, hasTimeOfDay = true)

    @Test
    fun `an NCAAF or College Football card is routed to the college football feed`() {
        assertEquals(listOf(EspnLeagueMap.COLLEGE_FOOTBALL), EspnLeagueMap.leaguesFor("NCAAF", listOf("UCF vs Iowa State")))
        assertEquals(listOf(EspnLeagueMap.COLLEGE_FOOTBALL), EspnLeagueMap.leaguesFor(null, listOf("UCF vs Iowa State", "NCAAF")))
        assertEquals(listOf(EspnLeagueMap.COLLEGE_FOOTBALL), EspnLeagueMap.leaguesFor("College Football", listOf("UCF vs Iowa State")))
    }

    @Test
    fun `UCF vs Iowa State matches the live Iowa State at UCF game`() {
        val game = events.single { it.home.abbreviation == "UCF" }
        assertEquals(ScoreState.IN, game.state)
        assertEquals(game.eventId, ScoreMatcher(zone = { eastern }).match(card("cfb"), events, start)?.eventId)
        // Both sides strong too: the college fallback's precision rule holds.
        val strict = ScoreMatcher(zone = { eastern }, minScore = ScoreMatcher.BOTH_STRONG)
        assertEquals(game.eventId, strict.match(card("cfb-strict"), events, start)?.eventId)
        assertEquals(true, ScoreMatcher.firstIsHome("UCF", "Iowa State", game))
    }

    @Test
    fun `the 23-00Z start is on ESPN's Eastern day of the scoreboard`() {
        assertEquals("2026-11-20", Instant.ofEpochMilli(start).atZone(eastern).toLocalDate().toString())
        assertTrue(events.isNotEmpty())
    }
}
