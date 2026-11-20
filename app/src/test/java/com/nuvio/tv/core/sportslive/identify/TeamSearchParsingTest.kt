package com.nuvio.tv.core.sportslive.identify

import com.nuvio.tv.core.sportslive.identify.TeamLookupFixtures.ESPN_BC_DUBAI
import com.nuvio.tv.core.sportslive.identify.TeamLookupFixtures.ESPN_DUBAI_BASKETBALL
import com.nuvio.tv.core.sportslive.identify.TeamLookupFixtures.ESPN_MACCABI_TEL_AVIV
import com.nuvio.tv.core.sportslive.identify.TeamLookupFixtures.ESPN_RAPID_BUCURESTI
import com.nuvio.tv.core.sportslive.identify.TeamLookupFixtures.SDB_DUBAI_BASKETBALL
import com.nuvio.tv.core.sportslive.identify.TeamLookupFixtures.SDB_MACCABI_TEL_AVIV
import com.nuvio.tv.core.sportslive.identify.TeamLookupFixtures.SDB_NONE
import com.nuvio.tv.core.sportslive.identify.TeamLookupFixtures.SDB_RAPID_BUCURESTI
import com.nuvio.tv.core.sportslive.identify.TeamLookupFixtures.moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #168: both sources' real answers read as team candidates. */
class TeamSearchParsingTest {

    @Test
    fun `ESPN's team result gives its sport, league and logo`() {
        val maccabi = EspnTeamSearch.parse(ESPN_MACCABI_TEL_AVIV, moshi)!!.single()
        assertEquals(
            TeamCandidate(
                name = "Maccabi Tel-Aviv",
                alternates = emptyList(),
                sport = "soccer",
                leagues = listOf("Israeli Premier League"),
                badge = "https://a.espncdn.com/i/teamlogos/soccer/500/524.png"
            ),
            maccabi
        )
        assertEquals("Romanian Liga 1", EspnTeamSearch.parse(ESPN_RAPID_BUCURESTI, moshi)!!.single().leagues.single())
    }

    @Test
    fun `ESPN with articles only is no team, and an unreadable body is a failure`() {
        assertEquals(emptyList<TeamCandidate>(), EspnTeamSearch.parse(ESPN_DUBAI_BASKETBALL, moshi))
        assertEquals(emptyList<TeamCandidate>(), EspnTeamSearch.parse(ESPN_BC_DUBAI, moshi))
        assertNull(EspnTeamSearch.parse("error code 1015", moshi))
    }

    @Test
    fun `TheSportsDB's team gives its sport, every league, alternates and badge`() {
        val dubai = SportsDbTeamSearch.parse(SDB_DUBAI_BASKETBALL, moshi)!!.single()
        assertEquals("Dubai Basketball", dubai.name)
        assertEquals(listOf("BC Dubai"), dubai.alternates)
        assertEquals("basketball", dubai.sport)
        assertEquals(listOf("Adriatic ABA League", "EuroLeague Basketball"), dubai.leagues)
        assertEquals("https://r2.thesportsdb.com/images/media/team/badge/fgtnti1758215967.png", dubai.badge)

        val maccabi = SportsDbTeamSearch.parse(SDB_MACCABI_TEL_AVIV, moshi)!!.single()
        assertEquals("soccer", maccabi.sport)
        assertEquals(listOf("Israeli Premier League", "UEFA Europa League", "UEFA Conference League"), maccabi.leagues)
        assertTrue("MTAFC" in maccabi.alternates && "FCMTA" in maccabi.alternates)

        val rapid = SportsDbTeamSearch.parse(SDB_RAPID_BUCURESTI, moshi)!!.single()
        assertEquals("rugby", rapid.sport)
        assertTrue("Rapid Bucharest XV" in rapid.alternates)
    }

    @Test
    fun `TheSportsDB with no team is empty, and an unreadable body is a failure`() {
        assertEquals(emptyList<TeamCandidate>(), SportsDbTeamSearch.parse(SDB_NONE, moshi))
        assertNull(SportsDbTeamSearch.parse("error code 1015", moshi))
    }
}
