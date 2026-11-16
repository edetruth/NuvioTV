package com.nuvio.tv.core.sportslive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveTeamsTest {

    @Test
    fun `highfly vs names parse`() {
        assertEquals(TeamPair("Tenerife", "Ceuta"), SportsLiveTeams.parse("Tenerife vs Ceuta"))
        assertEquals(TeamPair("Cyprus", "Montenegro"), SportsLiveTeams.parse("Cyprus vs Montenegro"))
        assertEquals(
            TeamPair("Detroit Lions", "New England Patriots"),
            SportsLiveTeams.parse("Detroit Lions vs New England Patriots")
        )
    }

    @Test
    fun `nebula and cnc names parse`() {
        assertEquals(
            TeamPair("Las Vegas Raiders", "Seattle Seahawks"),
            SportsLiveTeams.parse("Las Vegas Raiders vs Seattle Seahawks")
        )
        assertEquals(
            TeamPair("Dallas Cowboys", "San Francisco 49ers"),
            SportsLiveTeams.parse("Dallas Cowboys vs San Francisco 49ers")
        )
        assertEquals(TeamPair("AFG", "ZIM"), SportsLiveTeams.parse("AFG vs ZIM"))
    }

    @Test
    fun `sports replay at names strip rankings and put home second`() {
        assertEquals(
            TeamPair(home = "Arizona Wildcats", away = "Utah Utes"),
            SportsLiveTeams.parse("2 Utah Utes at 23 Arizona Wildcats")
        )
        assertEquals(
            TeamPair(home = "Notre Dame Fighting Irish", away = "Boston College Eagles"),
            SportsLiveTeams.parse("Boston College Eagles at 5 Notre Dame Fighting Irish")
        )
    }

    @Test
    fun `other separators parse case-insensitively`() {
        assertEquals(TeamPair("Celtics", "76ers"), SportsLiveTeams.parse("Celtics vs. 76ers"))
        assertEquals(TeamPair("Arsenal", "Chelsea"), SportsLiveTeams.parse("Arsenal v Chelsea"))
        assertEquals(TeamPair("Arsenal", "Chelsea"), SportsLiveTeams.parse("Arsenal VS Chelsea"))
        assertEquals(TeamPair("Chiefs", "Bills"), SportsLiveTeams.parse("Bills @ Chiefs"))
        assertEquals(TeamPair("Real Madrid", "Barcelona"), SportsLiveTeams.parse("Real Madrid - Barcelona"))
        assertEquals(TeamPair(home = "Stoke", away = "Wolves"), SportsLiveTeams.parse("Wolves AT Stoke"))
    }

    @Test
    fun `hyphenated words and channel prefixes do not split`() {
        assertNull(SportsLiveTeams.parse("CA-TSN 4"))
        assertNull(SportsLiveTeams.parse("Saint-Etienne"))
        assertNull(SportsLiveTeams.parse("ASIA-IN| STAR SPORTS 1 HINDI FHD"))
        assertEquals(TeamPair("Saint-Etienne", "Paris-FC"), SportsLiveTeams.parse("Saint-Etienne - Paris-FC"))
    }

    @Test
    fun `channels and non-games return null`() {
        listOf(
            "CA : TSN 4", "UK| SKY SPORTS 1", "NFL RedZone", "NFL REDZONE", "ATP 250",
            "Formula 1", "MotoGP", "Today's Games", "beIN Sports 1 AU", "(HD) : TNT SPORT 1"
        ).forEach { assertNull(it, SportsLiveTeams.parse(it)) }
    }

    @Test
    fun `empty side or separator at the edge returns null`() {
        assertNull(SportsLiveTeams.parse(" vs Ceuta"))
        assertNull(SportsLiveTeams.parse("Tenerife vs "))
        assertNull(SportsLiveTeams.parse("Tenerife vs   "))
        assertNull(SportsLiveTeams.parse("vs"))
        assertNull(SportsLiveTeams.parse(""))
        assertNull(SportsLiveTeams.parse("Foo - "))
    }

    @Test
    fun `normalise lowercases strips rankings and club suffixes`() {
        assertEquals("utah utes", SportsLiveTeams.normalise("2 Utah Utes"))
        assertEquals("tenerife", SportsLiveTeams.normalise("  Tenerife  FC "))
        assertEquals("sevilla", SportsLiveTeams.normalise("Sevilla CF"))
        assertEquals("st pauli", SportsLiveTeams.normalise("St   Pauli SC"))
        assertEquals("san francisco 49ers", SportsLiveTeams.normalise("San Francisco 49ers"))
        assertEquals("76ers", SportsLiveTeams.normalise("76ers"))
    }

    @Test
    fun `matches equal or whole-word suffix both ways case-insensitively`() {
        assertTrue(SportsLiveTeams.matches("Patriots", "New England Patriots"))
        assertTrue(SportsLiveTeams.matches("NEW ENGLAND PATRIOTS", "patriots"))
        assertTrue(SportsLiveTeams.matches("2 Utah Utes", "Utah Utes"))
        assertTrue(SportsLiveTeams.matches("Tenerife FC", "tenerife"))
        assertFalse(SportsLiveTeams.matches("Patriots", "Patriots Academy"))
        assertFalse(SportsLiveTeams.matches("ots", "Patriots"))
        assertFalse(SportsLiveTeams.matches("Lions", "Detroit Lions Women"))
        assertFalse(SportsLiveTeams.matches("", "Patriots"))
        assertFalse(SportsLiveTeams.matches("FC", "Barcelona FC"))
    }

    @Test
    fun `fixture event names with a separator all parse`() {
        val names = fixtureNames("highfly_live.json") + fixtureNames("nebula_live.json") +
            fixtureNames("nebula_replays.json") + fixtureNames("replay_football.json") +
            fixtureNames("cnc_live_events.json").map { it.removePrefix("🔜 ").removePrefix("✅ ").removePrefix("🔴 ") }
        val games = names.filter { Regex("(?i) (vs\\.?|v|at|@|-) ").containsMatchIn(it) }
        assertTrue(games.size > 20)
        games.forEach { name ->
            val pair = SportsLiveTeams.parse(name)
            assertTrue(name, pair != null && pair.home.isNotBlank() && pair.away.isNotBlank())
        }
    }

    @Test
    fun `fixture channel and team-list names never parse`() {
        val names = fixtureNames("nebula_channels.json") + fixtureNames("ultra_tv_uk.json") +
            fixtureNames("replay_football_teams.json") +
            fixtureNames("highfly_live.json").filter { ":" in it }
        assertTrue(names.size > 20)
        names.forEach { assertNull(it, SportsLiveTeams.parse(it)) }
    }

    private fun fixtureNames(file: String): List<String> {
        val json = javaClass.classLoader!!.getResourceAsStream("sportslive/$file")!!
            .bufferedReader().use { it.readText() }
        return Regex("\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(json).map { it.groupValues[1] }.toList()
    }
}
