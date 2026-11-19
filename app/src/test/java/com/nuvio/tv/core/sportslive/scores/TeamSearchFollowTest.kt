package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.domain.model.FollowedTeam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Following from team search (#149): what is stored, and how a result knows it is followed. */
class TeamSearchFollowTest {

    private val uconn = DirectoryTeam("41", "UConn Huskies", "UConn", "CONN", "UConn", "Huskies", "https://a.espncdn.com/i/teamlogos/ncaa/500/41.png")
    private val arsenal = DirectoryTeam("359", "Arsenal", "Arsenal", "ARS", "Arsenal", "Arsenal", "https://a.espncdn.com/i/teamlogos/soccer/500/359.png")
    private val cbb = TeamSearch.teamList(MyLeague.COLLEGE_BASKETBALL)
    private val cfb = TeamSearch.teamList(MyLeague.COLLEGE_FOOTBALL)

    @Test
    fun `a search result is stored like a follow from a game, with its logo`() {
        val followed = TeamFollows.teamToFollow(cbb, uconn)
        assertEquals(
            FollowedTeam(
                "UConn Huskies", "College Basketball", espnId = "41", abbreviation = "CONN",
                league = "basketball/mens-college-basketball", logo = uconn.logo
            ),
            followed
        )
        // The sport word routes back to the feed, so a game of the team matches by id there.
        assertEquals(listOf(EspnLeagueMap.COLLEGE_BASKETBALL), EspnLeagueMap.leaguesFor(sport = followed.sport, hints = emptyList()))
    }

    @Test
    fun `follow then unfollow round trip finds the entry by ESPN id in its own league only`() {
        var followed = listOf<FollowedTeam>()
        followed = followed + TeamFollows.teamToFollow(cbb, uconn)
        assertEquals(1, TeamFollows.followersOf(followed, cbb, uconn).size)
        // The football UConn has the same ESPN id but is another team to follow.
        assertTrue(TeamFollows.followersOf(followed, cfb, uconn).isEmpty())

        followed = followed - TeamFollows.followersOf(followed, cbb, uconn).toSet()
        assertTrue(followed.isEmpty())
    }

    @Test
    fun `a soccer club followed in one competition is followed in every one, and old name-only follows count`() {
        val followed = listOf(TeamFollows.teamToFollow(EspnLeague("soccer", "uefa.champions"), arsenal))
        assertEquals(1, TeamFollows.followersOf(followed, TeamSearch.teamList(MyLeague.PREMIER_LEAGUE), arsenal).size)

        val nameOnly = listOf(FollowedTeam("UConn Huskies", "College Basketball"), FollowedTeam("UConn Huskies", "Basketball"))
        assertEquals(listOf(nameOnly.first()), TeamFollows.followersOf(nameOnly, cbb, uconn))
    }
}
