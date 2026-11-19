package com.nuvio.tv.core.sportslive.scores

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

/** More… → Follow ‹team› (#134): only sides matched to a real team, named as ESPN names them. */
class FollowTargetsTest {

    private fun team(id: String, display: String, short: String, abbr: String) =
        DirectoryTeam(id, display, short, abbr, display.substringBeforeLast(' '), short, "https://a.espncdn.com/i/teamlogos/nhl/500/$id.png")

    private val nhlTeams = listOf(
        team("1", "Boston Bruins", "Bruins", "BOS"),
        team("26", "Florida Panthers", "Panthers", "FLA")
    )

    private fun item(name: String, sport: String?, isChannel: Boolean = false) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = isChannel, startMs = 1_800_000_000_000L, sport = sport,
        startHasTimeOfDay = true, teams = SportsLiveTeams.parse(name)
    )

    private fun scoreTeam(id: String?, display: String, short: String, abbr: String) = ScoreTeam(
        id = id, abbreviation = abbr, displayName = display, shortDisplayName = short, name = short,
        location = display.substringBeforeLast(' '), logo = null, score = 50, linescores = emptyList()
    )

    private fun game(away: ScoreTeam, home: ScoreTeam) = ScoreSnapshot(
        eventId = "401", league = EspnLeagueMap.COLLEGE_BASKETBALL, leagueName = "NCAAM", startMs = 1_800_000_000_000L,
        state = ScoreState.IN, clockText = "9:51 - 2nd Half", isFinal = false, home = home, away = away
    )

    private val uconn = scoreTeam("41", "UConn Huskies", "UConn", "CONN")
    private val arizona = scoreTeam("12", "Arizona Wildcats", "Arizona", "ARIZ")

    @Test
    fun `a matched game offers both teams by ESPN's short name in the card's order`() {
        val card = item("UConn vs Arizona", "College Basketball")
        val targets = FollowTargets.of(card, game(away = uconn, home = arizona), homeFirst = false, directory = TeamDirectory())
        assertEquals(listOf("UConn", "Arizona"), targets.map { it.label })
        // Home first when the card names the home team first.
        assertEquals(listOf("Arizona", "UConn"), FollowTargets.of(card, game(away = uconn, home = arizona), homeFirst = true, directory = TeamDirectory()).map { it.label })
    }

    @Test
    fun `following a target stores ESPN's team, never the raw title`() {
        val target = FollowTargets.of(item("UConn vs Arizona", "College Basketball"), game(uconn, arizona), homeFirst = false, directory = TeamDirectory()).first()
        val stored = TeamFollows.teamToFollow(target.event, target.side)!!
        assertEquals("UConn Huskies", stored.name)
        assertEquals("41", stored.espnId)
        assertTrue(target.isFollowed(listOf(stored)))
        assertFalse(target.isFollowed(listOf(FollowedTeam("Arizona Wildcats", "College Basketball", "12", "ARIZ", "basketball/mens-college-basketball"))))
    }

    @Test
    fun `a raw title with no matched team offers no Follow`() {
        // The case behind #134: "Follow LIVE: Bowls: New Zealand" was offered and saved as a team.
        assertTrue(FollowTargets.of(item("LIVE: Bowls: New Zealand vs India", "Bowls"), null, homeFirst = false, directory = TeamDirectory()).isEmpty())
        // A league whose list is known, with one side no team of it.
        val directory = TeamDirectory().apply { put(EspnLeagueMap.NHL, nhlTeams, 0L) }
        assertTrue(FollowTargets.of(item("Boston Bruins vs Springfield Isotopes", "NHL"), null, homeFirst = false, directory = directory).isEmpty())
        // A side ESPN gives no id for is left out.
        assertEquals(listOf("Arizona"), FollowTargets.of(item("UConn vs Arizona", "College Basketball"), game(scoreTeam(null, "UConn Huskies", "UConn", "CONN"), arizona), false, TeamDirectory()).map { it.label })
    }

    @Test
    fun `a game no scoreboard lists yet is followed from the team directory`() {
        val directory = TeamDirectory().apply { put(EspnLeagueMap.NHL, nhlTeams, 0L) }
        val targets = FollowTargets.of(item("Boston Bruins vs Florida Panthers", "NHL"), null, homeFirst = false, directory = directory)
        assertEquals(listOf("Bruins", "Panthers"), targets.map { it.label })
        assertEquals(listOf("1", "26"), targets.map { it.side.id })
    }

    @Test
    fun `a channel offers no Follow`() {
        val directory = TeamDirectory().apply { put(EspnLeagueMap.NHL, nhlTeams, 0L) }
        assertTrue(FollowTargets.of(item("NHL Network [Boston Bruins vs Florida Panthers]", "NHL", isChannel = true), null, false, directory).isEmpty())
    }
}
