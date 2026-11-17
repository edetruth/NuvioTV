package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.domain.model.FollowedTeam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** soccer/all per day (#53): every competition, labelled and grouped by ESPN's own data. */
class ResultsCompetitionsTest {

    private val all16 = EspnFixtures.events(EspnFixtures.SOCCER_ALL_FULL_20261116, EspnLeagueMap.SOCCER_ALL)
    private val women = EspnFixtures.events(EspnFixtures.SOCCER_ALL_WOMEN_20261108, EspnLeagueMap.SOCCER_ALL)
    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
    private val cbb = EspnFixtures.events(EspnFixtures.CBB_20261115, EspnLeagueMap.COLLEGE_BASKETBALL)

    @Test
    fun `each soccer all event is labelled with its competition and ESPN league id`() {
        val germany = all16.single { it.eventId == "401861185" }
        assertEquals("UEFA Nations League", germany.leagueName)
        assertEquals("2395", germany.competitionId)
        assertEquals("uefa.nations", germany.leagueSlug)
        assertEquals("Men's International Friendly", all16.single { it.eventId == "401914292" }.leagueName)
        assertEquals("LALIGA 2", all16.single { it.eventId == "401883305" }.leagueName)
        // A competition feed names its league from the feed itself.
        assertEquals("NBA", nba.first().leagueName)
        assertEquals("NCAAM", cbb.first().leagueName)
        assertEquals("Concacaf Nations League", EspnScoreProvider.competitionName("Concacaf Nations League, League B, Group C"))
        assertNull(EspnScoreProvider.competitionName(" "))
    }

    @Test
    fun `an event without a league link takes its slug from a sibling of the same league`() {
        // "Alavés at Tenerife" (Liga F) has no league link; its Liga F siblings say esp.w.1.
        val alaves = women.single { it.eventId == "401882466" }
        assertEquals("esp.w.1", alaves.leagueSlug)
        // So a followed men's Alavés does not star the women's game.
        assertFalse(SportsResults.follows(FollowedTeam("Alavés", "Soccer"), alaves))
        // A league with no linked event keeps soccer/all's slug.
        assertEquals("all", women.first { it.leagueName == "NCAAM Soccer" }.leagueSlug)
    }

    @Test
    fun `groups keep men's, women's and youth competitions apart, in league order`() {
        val groups = SportsResults.groups(SportsResults.rows(women + nba + cbb, emptyList()))
        val names = groups.map { it.name }
        // Sport order, then NBA before college; the Premier League before the rest of soccer.
        assertEquals(listOf("NBA", "NCAAM", "English Premier League"), names.take(3))
        assertTrue(listOf("Women's Super League", "Liga F", "NWSL", "NCAAM Soccer", "NCAAW Soccer").all { it in names })
        assertEquals(names.size, names.distinct().size)
        val ligaF = groups.single { it.name == "Liga F" }
        assertEquals(2, ligaF.rows.size) // the third (Alavés at Tenerife) was postponed

        val youth = SportsResults.groups(SportsResults.rows(all16, emptyList()))
        val u21 = youth.single { it.name == "Men's U-21 Friendly" }
        assertEquals(4, u21.rows.size) // the fifth is post but not completed
        assertFalse(youth.single { it.name == "Men's International Friendly" }.rows.any { it in u21.rows })
    }

    @Test
    fun `groups order internationals and named leagues before other competitions`() {
        val names = SportsResults.groups(SportsResults.rows(all16, emptyList())).map { it.name }
        val firstOther = names.indexOf("LALIGA 2")
        listOf("UEFA Nations League", "AFCON Qualifying", "Concacaf Nations League", "Men's International Friendly").forEach {
            assertTrue(it, names.indexOf(it) in 0 until firstOther)
        }
    }
}
