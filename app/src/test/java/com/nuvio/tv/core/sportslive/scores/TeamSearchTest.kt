package com.nuvio.tv.core.sportslive.scores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamSearchTest {

    private fun team(id: String, display: String, short: String?, abbr: String?, location: String?, nickname: String?) =
        DirectoryTeam(id, display, short, abbr, location, nickname, "https://a.espncdn.com/i/teamlogos/x/500/$id.png")

    // ESPN's own names for these teams.
    private val uconnHoops = team("41", "UConn Huskies", "UConn", "CONN", "UConn", "Huskies")
    private val michiganStateHoops = team("127", "Michigan State Spartans", "Michigan St", "MSU", "Michigan State", "Spartans")
    private val stJohns = team("2599", "St. John's Red Storm", "St John's", "SJU", "St. John's", "Red Storm")
    private val hawaii = team("62", "Hawai'i Rainbow Warriors", "Hawai'i", "HAW", "Hawai'i", "Rainbow Warriors")
    private val uconnFootball = team("41", "UConn Huskies", "UConn", "CONN", "UConn", "Huskies")
    private val washingtonFootball = team("264", "Washington Huskies", "Washington", "WASH", "Washington", "Huskies")
    private val lakers = team("13", "Los Angeles Lakers", "Lakers", "LAL", "Los Angeles", "Lakers")
    private val clippers = team("12", "LA Clippers", "Clippers", "LAC", "LA", "Clippers")
    private val kings = team("23", "Sacramento Kings", "Kings", "SAC", "Sacramento", "Kings")
    private val celtics = team("2", "Boston Celtics", "Celtics", "BOS", "Boston", "Celtics")
    private val vikings = team("16", "Minnesota Vikings", "Vikings", "MIN", "Minnesota", "Vikings")
    private val manUnited = team("360", "Manchester United", "Man United", "MAN", "Manchester United", "Manchester United")

    private val index = TeamSearch.index(
        mapOf(
            MyLeague.NBA to listOf(lakers, clippers, kings, celtics),
            MyLeague.COLLEGE_BASKETBALL to listOf(uconnHoops, michiganStateHoops, stJohns, hawaii),
            MyLeague.NFL to listOf(vikings),
            MyLeague.COLLEGE_FOOTBALL to listOf(uconnFootball, washingtonFootball),
            MyLeague.PREMIER_LEAGUE to listOf(manUnited)
        )
    )

    private fun names(query: String, sport: MySport? = null) =
        TeamSearch.search(index, query, sport).map { "${it.league.id} ${it.team.displayName}" }

    // --- Names and aliases ---

    @Test
    fun `uconn finds both UConn teams by their short name and says so`() {
        val hits = TeamSearch.search(index, "uconn")
        assertEquals(listOf(MyLeague.COLLEGE_BASKETBALL, MyLeague.COLLEGE_FOOTBALL), hits.map { it.league })
        hits.forEach {
            assertEquals(TeamMatchTier.EXACT, it.match!!.tier)
            assertEquals(TeamMatchField.SHORT_NAME, it.match!!.field)
            assertEquals("UConn", it.match!!.text)
        }
    }

    @Test
    fun `Connecticut finds UConn through the alias table, letter by letter too`() {
        val exact = TeamSearch.search(index, "Connecticut")
        assertEquals(listOf("UConn Huskies", "UConn Huskies"), exact.map { it.team.displayName })
        assertEquals(TeamMatchField.ALIAS, exact.first().match!!.field)
        assertEquals("Connecticut", exact.first().match!!.text)
        assertEquals(TeamMatchTier.EXACT, exact.first().match!!.tier)

        assertTrue(names("conn").all { it.endsWith("UConn Huskies") })
        assertEquals(2, names("connecticut huskies").size)
    }

    @Test
    fun `St and State are one word after the first word`() {
        val state = TeamSearch.search(index, "michigan state")
        assertEquals(listOf("Michigan State Spartans"), state.map { it.team.displayName })
        assertEquals(listOf("Michigan State Spartans"), names("michigan st").map { it.substringAfter(' ') })
        assertEquals(listOf("Michigan State Spartans"), names("Michigan St spartans").map { it.substringAfter(' ') })
        // Still typing the word: "sta" is the start of "State".
        assertEquals(listOf("Michigan State Spartans"), names("michigan sta").map { it.substringAfter(' ') })
    }

    @Test
    fun `apostrophes and dots join their word and a first Saint is St`() {
        listOf("St. John's", "st johns", "Saint Johns", "st john", "red storm").forEach { query ->
            assertEquals(query, listOf("St. John's Red Storm"), TeamSearch.search(index, query).map { it.team.displayName })
        }
        assertEquals(listOf("Hawai'i Rainbow Warriors"), TeamSearch.search(index, "hawaii").map { it.team.displayName })
    }

    @Test
    fun `soccer clubs answer to their common short names`() {
        val hits = TeamSearch.search(index, "man utd")
        assertEquals(listOf("Manchester United"), hits.map { it.team.displayName })
        assertEquals(TeamMatchField.ALIAS, hits.single().match!!.field)
    }

    // --- Ranking ---

    @Test
    fun `exact matches rank before prefix matches before contains matches`() {
        // Kings: the nickname exactly; Vikings: "kings" inside the name.
        assertEquals(listOf("nba Sacramento Kings", "nfl Minnesota Vikings"), names("kings"))
        assertEquals(TeamMatchTier.CONTAINS, TeamSearch.search(index, "kings").last().match!!.tier)
        // "la": LA Clippers and LAL start with it, Lakers has a word that does; never "Atlanta"-style contains on two letters.
        val la = TeamSearch.search(index, "la")
        assertEquals(listOf("LA Clippers", "Los Angeles Lakers"), la.map { it.team.displayName })
        assertEquals(TeamMatchTier.EXACT, la.first().match!!.tier)
        assertEquals(listOf("Los Angeles Lakers"), names("lakers").map { it.substringAfter(' ') })
    }

    @Test
    fun `short queries never match inside a word and abbreviations only from their start`() {
        assertTrue(names("ki").all { it.endsWith("Kings") })
        // "AC" is inside "SAC" (Kings) but an abbreviation matches only from its start.
        assertTrue(TeamSearch.search(index, "ac").none { it.team == kings })
        assertEquals(TeamMatchField.ABBREVIATION, TeamSearch.search(index, "sac").single().match!!.field)
    }

    @Test
    fun `a blank query or one with no match lists nothing`() {
        assertEquals(emptyList<TeamHit>(), TeamSearch.search(index, "   "))
        assertEquals(emptyList<TeamHit>(), TeamSearch.search(index, "xyz"))
    }

    @Test
    fun `results stop at the limit`() {
        assertEquals(1, TeamSearch.search(index, "huskies", limit = 1).size)
    }

    // --- Sport filter ---

    @Test
    fun `the sport filter keeps only that sport's leagues`() {
        assertEquals(listOf("college-football UConn Huskies", "college-football Washington Huskies"), names("huskies", MySport.FOOTBALL))
        assertEquals(listOf("college-basketball UConn Huskies"), names("huskies", MySport.BASKETBALL))
        assertEquals(emptyList<String>(), names("huskies", MySport.SOCCER))
        assertEquals(3, names("huskies").size)
    }

    // --- Browse ---

    @Test
    fun `an empty field browses every team A to Z under its league, leagues in picker order`() {
        val groups = TeamSearch.browse(index)
        assertEquals(
            listOf(MyLeague.NBA, MyLeague.COLLEGE_BASKETBALL, MyLeague.NFL, MyLeague.COLLEGE_FOOTBALL, MyLeague.PREMIER_LEAGUE),
            groups.map { it.league }
        )
        assertEquals(
            listOf("Boston Celtics", "LA Clippers", "Los Angeles Lakers", "Sacramento Kings"),
            groups.first().teams.map { it.team.displayName }
        )
        assertTrue(groups.flatMap { it.teams }.all { it.match == null })
    }

    @Test
    fun `browsing with a sport lists only its leagues, and leagues with no list are left out`() {
        assertEquals(listOf(MyLeague.NFL, MyLeague.COLLEGE_FOOTBALL), TeamSearch.browse(index, MySport.FOOTBALL).map { it.league })
        assertEquals(emptyList<TeamGroup>(), TeamSearch.browse(index, MySport.HOCKEY))
        assertTrue(TeamSearch.index(emptyMap()).isEmpty)
    }

    // --- Leagues ---

    @Test
    fun `each searchable league has a team list the directory covers`() {
        TeamSearch.LEAGUES.forEach { assertTrue(it.id, TeamDirectory.covers(TeamSearch.teamList(it))) }
        assertEquals(EspnLeague("soccer", "eng.1"), TeamSearch.teamList(MyLeague.PREMIER_LEAGUE))
        assertEquals(EspnLeagueMap.COLLEGE_BASKETBALL, TeamSearch.teamList(MyLeague.COLLEGE_BASKETBALL))
        assertNull(TeamSearch.LEAGUES.firstOrNull { it == MyLeague.CHAMPIONS_LEAGUE })
    }

    @Test
    fun `the real UConn of the college basketball list is found by uconn and Connecticut`() {
        val cbb = TeamDirectory.parse(EspnFixtures.json("espn_teams_cbb_20261118.json"), EspnFixtures.moshi)!!
        val real = TeamSearch.index(mapOf(MyLeague.COLLEGE_BASKETBALL to cbb))
        assertEquals("41", TeamSearch.search(real, "uconn").first().team.id)
        assertEquals("41", TeamSearch.search(real, "connecticut").single().team.id)
    }
}
