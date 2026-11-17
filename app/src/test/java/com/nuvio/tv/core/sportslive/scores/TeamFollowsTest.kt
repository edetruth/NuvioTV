package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.SportsLiveItemBucketer
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Following a team from ESPN data (#54) and its effect on the My Teams list everywhere. */
class TeamFollowsTest {

    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
    // IND @ PHI: home Philadelphia 76ers, away Indiana Pacers.
    private val pacersGame = nba.first { it.eventId == "401909985" }
    // Crystal Palace (home) 1–3 Liverpool (away).
    private val palaceGame = EspnFixtures.events(EspnFixtures.ENG1_20261108, EspnFixtures.ENG1).first { it.eventId == "401879243" }

    private fun card(name: String, sport: String?) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = listOfNotNull(sport)
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false,
        startMs = null, sport = sport, teams = SportsLiveTeams.parse(name)
    )

    @Test
    fun `following an ESPN side stores the full name, a routable sport word and ESPN's identity`() {
        val sixers = TeamFollows.teamToFollow(pacersGame, pacersGame.home)!!
        assertEquals("Philadelphia 76ers", sixers.name)
        assertEquals("Basketball", sixers.sport)
        assertEquals(pacersGame.home.id, sixers.espnId)
        assertEquals("PHI", sixers.abbreviation)
        assertEquals("basketball/nba", sixers.league)
        // The sport word routes back to the feed, so My sports gains it (R1: a follow adds its sport).
        assertEquals(listOf(EspnLeagueMap.NBA), EspnLeagueMap.leaguesFor(sport = sixers.sport, hints = emptyList()))
        assertEquals("American Football", TeamFollows.sportWord(EspnLeagueMap.NFL))
        assertEquals("College Basketball", TeamFollows.sportWord(EspnLeagueMap.COLLEGE_BASKETBALL))
        assertEquals(listOf(EspnLeagueMap.COLLEGE_BASKETBALL), EspnLeagueMap.leaguesFor(sport = "College Basketball", hints = emptyList()))

        val liverpool = TeamFollows.teamToFollow(palaceGame, palaceGame.away)!!
        assertEquals("Soccer", liverpool.sport)
        assertEquals("soccer/eng.1", liverpool.league)
        assertTrue(MySports(mapOf(MySport.BASKETBALL to emptySet())).withFollowed(listOf(liverpool)).effectiveSports.contains(MySport.SOCCER))
    }

    @Test
    fun `an ESPN follow matches its side by id, a name-only follow by strong name in its sport`() {
        val sixers = TeamFollows.teamToFollow(pacersGame, pacersGame.home)!!
        assertTrue(TeamFollows.followsSide(sixers, pacersGame, pacersGame.home))
        assertFalse(TeamFollows.followsSide(sixers, pacersGame, pacersGame.away))
        // Same id, renamed: still the team.
        assertTrue(TeamFollows.followsSide(sixers.copy(name = "Sixers"), pacersGame, pacersGame.home))

        assertTrue(TeamFollows.followsSide(FollowedTeam("Philadelphia 76ers", "NBA"), pacersGame, pacersGame.home))
        assertTrue(TeamFollows.followsSide(FollowedTeam("Liverpool", "Football"), palaceGame, palaceGame.away))
        // A soccer team never matches in another sport.
        assertFalse(TeamFollows.followsSide(FollowedTeam("Philadelphia 76ers", "Soccer"), pacersGame, pacersGame.home))
        assertEquals(listOf(sixers), TeamFollows.followersOf(listOf(sixers, FollowedTeam("Arsenal", "Soccer")), pacersGame, pacersGame.home))
        // Results stars the row through the same rule.
        assertTrue(SportsResults.follows(sixers, pacersGame))
    }

    @Test
    fun `a team followed from Results stars and pins an addon's card in Live Now and Upcoming`() {
        val sixers = TeamFollows.teamToFollow(pacersGame, pacersGame.home)!!
        val other = card("Lakers vs Celtics", "NBA")
        // The addon names the team shortly and files the game under "NBA": the sports agree as ESPN sports.
        val sixersCard = card("Pacers at 76ers", "NBA")
        val pinned = SportsLiveItemBucketer.pinFollowed(listOf(other, sixersCard), listOf(sixers))
        assertEquals(listOf("Pacers at 76ers", "Lakers vs Celtics"), pinned.map { it.meta.name })
        assertTrue(pinned.first().followed)
        // A different sport still never matches: a soccer card named like the team is not starred.
        assertFalse(SportsLiveTeams.isFollowed(card("76ers vs Rangers", "Soccer"), listOf(sixers)))
        // Unfollowed: the order is the addon's again.
        assertEquals(listOf(other, sixersCard), SportsLiveItemBucketer.pinFollowed(listOf(other, sixersCard), emptyList()))
    }
}
