package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.scores.DirectoryTeam
import com.nuvio.tv.core.sportslive.scores.MyLeague
import com.nuvio.tv.core.sportslive.scores.MySport
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.TeamFollows
import com.nuvio.tv.core.sportslive.scores.TeamHit
import com.nuvio.tv.core.sportslive.scores.TeamSearch
import com.nuvio.tv.domain.model.FollowedTeam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamSearchStateTest {

    private val celtics = DirectoryTeam("2", "Boston Celtics", "Celtics", "BOS", "Boston", "Celtics", "https://a.espncdn.com/i/teamlogos/nba/500/bos.png")
    private val basketballOnly = MySports(mapOf(MySport.BASKETBALL to emptySet()))

    @Test
    fun `the sport filter offers My sports, or every sport by default`() {
        assertEquals(listOf(MySport.BASKETBALL), TeamSearchState.filterSports(basketballOnly))
        assertEquals(TeamSearch.SPORTS, TeamSearchState.filterSports(MySports.DEFAULT))
        // Individual sports only: no team list to search, so every team sport.
        assertEquals(TeamSearch.SPORTS, TeamSearchState.filterSports(MySports(mapOf(MySport.TENNIS to emptySet()))))
    }

    @Test
    fun `My sports leagues are filled first`() {
        val order = TeamSearchState.fillOrder(null, basketballOnly)
        assertEquals(MyLeague.NBA, order.first())
        assertEquals(TeamSearch.LEAGUES.toSet(), order.toSet())
        assertEquals(listOf(MyLeague.NHL), TeamSearchState.fillOrder(MySport.HOCKEY, basketballOnly))
    }

    @Test
    fun `a result reads as followed in place, and a new follow is tagged New`() {
        val hit = TeamHit(celtics, MyLeague.NBA)
        val stored = TeamFollows.teamToFollow(TeamSearch.teamList(MyLeague.NBA), celtics)
        val before = TeamSearchUiState()
        assertFalse(before.isFollowed(hit))
        val after = before.copy(followed = listOf(stored), newKeys = setOf(TeamSearchState.followKey(stored)))
        assertTrue(after.isFollowed(hit))
        assertTrue(after.isNew(stored))
        assertFalse(after.isNew(FollowedTeam("Arsenal", "Soccer")))
    }

    @Test
    fun `a followed team stored without a logo takes it from the directory`() {
        val index = TeamSearch.index(mapOf(MyLeague.NBA to listOf(celtics)))
        val old = FollowedTeam("Boston Celtics", "Basketball", espnId = "2", league = "basketball/nba")
        val logos = TeamSearchState.logos(listOf(old), index)
        assertEquals(celtics.logo, TeamSearchUiState(followed = listOf(old), logos = logos).logoOf(old))
    }
}
