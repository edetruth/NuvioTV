package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.scores.DirectoryTeam
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.TeamDirectory
import com.nuvio.tv.domain.model.FollowedTeam
import org.junit.Assert.assertEquals
import org.junit.Test

/** The "N new" line's followed team (live.25 M): its short name, "Mavericks game started · 13 new — OK to show". */
class SportsLiveNewRowsLineTest {

    private val directory = TeamDirectory().apply {
        put(
            EspnLeagueMap.NBA,
            listOf(DirectoryTeam("6", "Dallas Mavericks", "Mavericks", "DAL", "Dallas", "Mavericks", "https://example.invalid/dal.png")),
            1L
        )
    }

    @Test
    fun `a team followed from ESPN reads its short name, any other its own name`() {
        val espn = FollowedTeam(name = "Dallas Mavericks", sport = "Basketball", espnId = "6", league = "basketball/nba")
        assertEquals("Mavericks", sportsLiveTeamShortName(espn, directory))
        // Its list not loaded (another league), or a name-only follow: the name it was followed by.
        assertEquals("Dallas Mavericks", sportsLiveTeamShortName(espn.copy(league = "basketball/wnba"), directory))
        assertEquals("Dallas Mavericks", sportsLiveTeamShortName(FollowedTeam(name = "Dallas Mavericks"), directory))
    }
}
