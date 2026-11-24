package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.TeamPair
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** live.28 item e: an untagged soccer row ("Arsenal vs Chelsea" under "Soccer") checked against every loaded soccer list. */
class NoEspnGameTest {

    private val eng1Teams = TeamDirectory.parse(EspnFixtures.json("espn_teams_eng1_20261118.json"), EspnFixtures.moshi)!!
    private val nflTeams = TeamDirectory.parse(EspnFixtures.json("espn_teams_nfl_20261118.json"), EspnFixtures.moshi)!!

    private val ENG1 = EspnLeague("soccer", "eng.1")
    private val ALL = EspnLeagueMap.SOCCER_ALL
    private val matcher = ScoreMatcher()

    private fun team(id: String, display: String, short: String) =
        DirectoryTeam(id, display, short, null, null, null, "https://a.espncdn.com/i/teamlogos/x/500/$id.png")

    private fun directory(vararg lists: Pair<EspnLeague, List<DirectoryTeam>>) =
        TeamDirectory().apply { lists.forEach { (league, teams) -> put(league, teams, 0L) } }

    /** The Premier League's and the NFL's lists, as inference keeps them. */
    private fun usual() = directory(ENG1 to eng1Teams, EspnLeagueMap.NFL to nflTeams)

    private fun card(home: String, away: String) = ScoreCard("card", TeamPair(home, away), null, false)

    private fun side(id: String, name: String) = ScoreTeam(
        id = id, abbreviation = null, displayName = name, shortDisplayName = name, name = name, location = null,
        logo = null, score = 0, linescores = emptyList()
    )

    private fun event(home: ScoreTeam, away: ScoreTeam) = ScoreSnapshot(
        eventId = "${home.id}-${away.id}", league = ALL, startMs = null, state = ScoreState.PRE, clockText = null,
        isFinal = false, home = home, away = away
    )

    private fun hasNone(
        card: ScoreCard,
        directory: TeamDirectory = usual(),
        events: List<ScoreSnapshot> = emptyList(),
        leagues: List<EspnLeague> = listOf(ALL),
        today: Boolean = true
    ) = NoEspnGame.hasNone(card, leagues, { today }, directory, events, matcher)

    @Test
    fun `an untagged soccer row whose two clubs ESPN has no game for today is no game`() {
        assertTrue(hasNone(card("Arsenal", "Chelsea")))
        // A bare "Football" card is on the college football fallback too; its clubs are still told by the soccer lists.
        assertTrue(hasNone(card("Arsenal", "Chelsea"), leagues = listOf(ALL, EspnLeagueMap.COLLEGE_FOOTBALL)))
    }

    @Test
    fun `a club that plays today on any soccer board keeps the row live, by id or by name`() {
        assertFalse(hasNone(card("Arsenal", "Chelsea"), events = listOf(event(side("359", "Arsenal"), side("364", "Liverpool")))))
        // An id no list holds, a name the matcher reads.
        assertFalse(hasNone(card("Arsenal", "Chelsea"), events = listOf(event(side("9001", "Chelsea"), side("9002", "Somewhere")))))
    }

    @Test
    fun `no fresh soccer-all board for ESPN's today moves nothing`() {
        assertFalse(hasNone(card("Arsenal", "Chelsea"), today = false))
    }

    @Test
    fun `a side no loaded list knows, or both sides one club, moves nothing`() {
        assertFalse(hasNone(card("Arsenal", "Inter")))
        assertFalse(hasNone(card("Arsenal", "Arsenal")))
        assertFalse(hasNone(card("Arsenal", "Chelsea"), directory = directory(EspnLeagueMap.NFL to nflTeams)))
    }

    @Test
    fun `a name two soccer lists give different clubs moves nothing`() {
        // A women's list (or any other) holding another Arsenal: the row may be that club's game.
        val women = EspnLeague("soccer", "eng.w.1")
        val dir = directory(ENG1 to eng1Teams, EspnLeagueMap.NFL to nflTeams, women to listOf(team("9999", "Arsenal", "Arsenal")))
        assertFalse(hasNone(card("Arsenal", "Chelsea"), directory = dir))
        assertNull(NoEspnGame.untaggedSoccerIds(card("Arsenal", "Chelsea"), dir))
    }

    @Test
    fun `a side another sport's loaded list names moves nothing, nor any row while the NFL list is not loaded`() {
        val hockey = directory(ENG1 to eng1Teams, EspnLeagueMap.NFL to nflTeams, EspnLeagueMap.NHL to listOf(team("77", "Chelsea Blues", "Chelsea")))
        assertFalse(hasNone(card("Arsenal", "Chelsea"), directory = hockey))
        assertFalse(hasNone(card("Arsenal", "Chelsea"), directory = directory(ENG1 to eng1Teams)))
    }

    @Test
    fun `a row its competition names is told by that league alone, never by every soccer list`() {
        // The Bundesliga's list is not loaded: the Premier League's never speaks for a Bundesliga card.
        val ger1 = EspnLeague("soccer", "ger.1")
        assertFalse(hasNone(card("Arsenal", "Chelsea"), leagues = listOf(ger1, ALL)))
        assertFalse(NoEspnGame.isUntaggedSoccer(listOf(ger1, ALL)))
        assertTrue(NoEspnGame.isUntaggedSoccer(listOf(ALL)))
        assertFalse(NoEspnGame.isUntaggedSoccer(listOf(EspnLeagueMap.NBA)))
    }
}
