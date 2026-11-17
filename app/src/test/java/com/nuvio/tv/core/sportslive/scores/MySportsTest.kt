package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.domain.model.FollowedTeam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MySportsTest {

    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
    private val soccerAll = EspnFixtures.events(EspnFixtures.SOCCER_ALL_FULL_20261116, EspnLeagueMap.SOCCER_ALL)
    private val women = EspnFixtures.events(EspnFixtures.SOCCER_ALL_WOMEN_20261108, EspnLeagueMap.SOCCER_ALL)

    @Test
    fun `nothing picked is every team sport with its default leagues`() {
        val default = MySports.decode(null)
        assertTrue(default.isDefault)
        assertEquals(MySport.entries.toList(), default.effectiveSports)
        assertEquals(setOf(MyLeague.NBA), default.leaguesOf(MySport.BASKETBALL))
        assertEquals(setOf(MyLeague.NFL), default.leaguesOf(MySport.FOOTBALL))
        assertTrue(default.namedLeagues(MySport.SOCCER).isEmpty())
        assertEquals(MySports.DEFAULT, MySports.decode(emptySet()))
        assertEquals(emptySet<String>(), MySports.DEFAULT.encode())
    }

    @Test
    fun `tokens round trip, a league brings its sport and unknown tokens are skipped`() {
        val sports = MySports.decode(setOf("basketball/college-basketball", "soccer", "soccer/eng.1", "tennis", "tennis/atp", "x/y"))
        assertEquals(listOf(MySport.BASKETBALL, MySport.SOCCER), sports.effectiveSports)
        assertEquals(setOf(MyLeague.COLLEGE_BASKETBALL), sports.leaguesOf(MySport.BASKETBALL))
        assertEquals(setOf(MyLeague.PREMIER_LEAGUE), sports.leaguesOf(MySport.SOCCER))
        assertEquals(sports, MySports.decode(sports.encode()))
        assertEquals(
            setOf("basketball", "basketball/college-basketball", "soccer", "soccer/eng.1"),
            sports.encode()
        )
    }

    @Test
    fun `the model has room for individual sports`() {
        assertTrue(MySport.entries.all { it.kind == SportKind.TEAM })
        // Defaults list team sports only, so an individual sport added later is opt-in.
        assertEquals(MySport.entries.filter { it.kind == SportKind.TEAM }, MySports.DEFAULT.effectiveSports)
    }

    @Test
    fun `switching sports on and off keeps the last one on`() {
        val noSoccer = MySports.DEFAULT.toggleSport(MySport.SOCCER)
        assertEquals(listOf(MySport.BASKETBALL, MySport.FOOTBALL, MySport.HOCKEY, MySport.BASEBALL), noSoccer.effectiveSports)
        assertEquals(listOf(EspnLeagueMap.NBA, EspnLeagueMap.NFL, EspnLeagueMap.NHL, EspnLeagueMap.MLB), noSoccer.feeds())
        val hockey = MySports.decode(setOf("hockey"))
        assertSame(hockey, hockey.toggleSport(MySport.HOCKEY))
        assertEquals(listOf(MySport.HOCKEY, MySport.SOCCER), hockey.toggleSport(MySport.SOCCER).effectiveSports)
    }

    @Test
    fun `switching leagues - college joins NBA, the last league stays, soccer falls back to every competition`() {
        val withCollege = MySports.DEFAULT.toggleLeague(MyLeague.COLLEGE_BASKETBALL)
        assertEquals(setOf(MyLeague.NBA, MyLeague.COLLEGE_BASKETBALL), withCollege.leaguesOf(MySport.BASKETBALL))
        val collegeOnly = withCollege.toggleLeague(MyLeague.NBA)
        assertEquals(setOf(MyLeague.COLLEGE_BASKETBALL), collegeOnly.leaguesOf(MySport.BASKETBALL))
        assertSame(collegeOnly, collegeOnly.toggleLeague(MyLeague.COLLEGE_BASKETBALL))

        val eplOnly = MySports.DEFAULT.toggleLeague(MyLeague.PREMIER_LEAGUE)
        assertEquals(setOf(MyLeague.PREMIER_LEAGUE), eplOnly.namedLeagues(MySport.SOCCER))
        assertTrue(eplOnly.toggleLeague(MyLeague.PREMIER_LEAGUE).namedLeagues(MySport.SOCCER).isEmpty())
        assertTrue(eplOnly.allSoccerCompetitions().namedLeagues(MySport.SOCCER).isEmpty())
        // A league of a sport that was off switches the sport on.
        val hockey = MySports.decode(setOf("hockey"))
        assertEquals(setOf(MyLeague.NFL), hockey.toggleLeague(MyLeague.NFL).leaguesOf(MySport.FOOTBALL))
    }

    @Test
    fun `my soccer competitions are matched by slug, internationals also by name, never the Club World Cup`() {
        val internationals = MySports.decode(setOf("soccer/internationals"))
        val included = soccerAll.filter(internationals::includes).map { it.leagueName }.toSet()
        assertTrue("UEFA Nations League" in included)
        assertTrue("AFCON Qualifying" in included)
        assertTrue("Concacaf Nations League" in included)
        // No league link, only its name: "Men's International Friendly".
        assertTrue("Men's International Friendly" in included)
        assertFalse("LALIGA 2" in included)
        assertFalse("Peru Liga 1" in included)
        val cwc = soccerAll.first().copy(leagueSlug = "fifa.cwc", leagueName = "FIFA Club World Cup")
        assertFalse(internationals.includes(cwc))

        val epl = MySports.decode(setOf("soccer/eng.1"))
        assertEquals(setOf("English Premier League"), women.filter(epl::includes).map { it.leagueName }.toSet())
        assertTrue(MySports.decode(setOf("soccer")).let { all -> women.all(all::includes) })
        assertFalse(epl.includes(nba.first()))
    }

    @Test
    fun `following a team adds its sport, and its league to a sport already picked`() {
        val soccerOnly = MySports.decode(setOf("soccer", "soccer/eng.1"))
        val withTeams = soccerOnly.withFollowed(listOf(FollowedTeam("Knicks", "Basketball"), FollowedTeam("Arsenal", "Soccer")))
        assertEquals(listOf(MySport.BASKETBALL, MySport.SOCCER), withTeams.effectiveSports)
        assertEquals(setOf(MyLeague.NBA), withTeams.leaguesOf(MySport.BASKETBALL))
        // A soccer team does not widen the soccer competitions picked: soccer/all lists its games anyway.
        assertEquals(setOf(MyLeague.PREMIER_LEAGUE), withTeams.namedLeagues(MySport.SOCCER))

        val nbaOnly = MySports.decode(setOf("basketball"))
        val withDuke = nbaOnly.withFollowed(listOf(FollowedTeam("Duke", "College Basketball")))
        assertEquals(setOf(MyLeague.NBA, MyLeague.COLLEGE_BASKETBALL), withDuke.leaguesOf(MySport.BASKETBALL))
        // Nothing new: the same selection.
        assertSame(nbaOnly, nbaOnly.withFollowed(listOf(FollowedTeam("Lakers", "Basketball"), FollowedTeam("Nobody"))))
    }
}
