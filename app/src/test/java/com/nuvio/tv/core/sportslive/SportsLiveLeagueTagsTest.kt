package com.nuvio.tv.core.sportslive

import com.nuvio.tv.ui.screens.sportslive.scoreboardRowTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveLeagueTagsTest {

    @Test
    fun `raw ESPN abbreviations and names get readable short tags`() {
        assertEquals("U17 WC", SportsLiveLeagueTags.of("FU17W"))
        assertEquals("WORLD CUP", SportsLiveLeagueTags.of("FIFA World Cup"))
        assertEquals("UCL", SportsLiveLeagueTags.of("UEFA Champions League"))
        assertEquals("UEL", SportsLiveLeagueTags.of("UEFA Europa League"))
        assertEquals("UECL", SportsLiveLeagueTags.of("UEFA Conference League"))
        assertEquals("SOCCER", SportsLiveLeagueTags.of("CONMEBOL Libertadores"))
        assertEquals("FRIENDLY", SportsLiveLeagueTags.of("Men's International Friendly"))
        // Case, spacing and a curly apostrophe do not matter; slugs map too.
        assertEquals("FRIENDLY", SportsLiveLeagueTags.of("  men’s   international friendly "))
        assertEquals("U17 WC", SportsLiveLeagueTags.of("fifa.world.u17"))
    }

    @Test
    fun `every tag is upper case and at most 10 characters`() {
        val names = listOf("FU17W", "FU20W", "FIFA Club World Cup", "UEFA Nations League", "Africa Cup of Nations Qualifying",
            "English League Championship", "German Bundesliga", "Brazilian Serie A", "CONCACAF Nations League")
        names.forEach { name ->
            val tag = SportsLiveLeagueTags.of(name)!!
            assertTrue(tag, tag.length <= 10 && tag == tag.uppercase())
        }
    }

    @Test
    fun `ESPN abbreviations that read as bare initials get a readable tag`() {
        // "Argentine LPF" read "AL" on the Live now row (TV QA live.20).
        assertEquals("ARGENTINA", scoreboardRowTag("Argentine LPF", "Soccer", "Live"))
        assertEquals("AUSTRIA", scoreboardRowTag("Austrian Bundesliga", "Soccer", "Live"))
        assertEquals("A-LEAGUE", scoreboardRowTag("A-League Men", "Soccer", "Live"))
        listOf("Argentine LPF", "A-League Men", "Colombian Primera A", "Chilean Primera", "Liga AUF Uruguaya", "Japanese J1 League",
            "Belgian Pro League", "Austrian Bundesliga", "Swiss Super League", "Danish Superliga", "Greek Super League").forEach { name ->
            val tag = SportsLiveLeagueTags.of(name)!!
            // At most 8: the row's short vocabulary keeps it as is.
            assertTrue(tag, tag.length <= SportsLiveTags.MAX_CHARS && tag == tag.uppercase())
            assertEquals(tag, SportsLiveTags.short(tag, null))
        }
    }

    @Test
    fun `an unmapped league keeps the current tag`() {
        assertNull(SportsLiveLeagueTags.of("NBA"))
        assertEquals("NBA", scoreboardRowTag("NBA", "Basketball", "Live"))
        assertEquals("NCAAW", scoreboardRowTag("NCAAW", "COLLEGE", "Live"))
        assertEquals("U17 WC", scoreboardRowTag("FU17W", "Soccer", "Live"))
        assertEquals("UCL", scoreboardRowTag("UEFA Champions League", "Soccer", "Live"))
    }

    @Test
    fun `no tag drops letters to fit, live 23`() {
        // A competition's own short form or name, its country, else the sport's word; never "U21 FRNDLY" or "CHAMPNSHP".
        val expected = mapOf(
            "Men's U-21 Friendly" to "FRIENDLY", "fifa.friendly_u21" to "FRIENDLY",
            "English League Championship" to "EFL", "EFL League One" to "LEAGUE ONE", "English League Two" to "LEAGUE TWO",
            "Spanish LALIGA 2" to "LALIGA 2", "UEFA Nations League" to "SOCCER", "CONCACAF Nations League" to "SOCCER",
            "FIFA World Cup Qualifying" to "SOCCER", "Africa Cup of Nations Qualifying" to "SOCCER", "Copa del Rey" to "SOCCER",
            "Coppa Italia" to "SOCCER", "FIFA Women's World Cup" to "WORLD CUP", "AFC Champions League Elite" to "ACL",
            "Portuguese Primeira Liga" to "PORTUGAL", "Romanian Liga 1" to "ROMANIA", "Belgian Pro League" to "BELGIUM",
            "Greek Super League" to "GREECE", "Saudi Pro League" to "SOCCER", "Swiss Super League" to "SOCCER"
        )
        expected.forEach { (name, tag) -> assertEquals(name, tag, SportsLiveLeagueTags.of(name)) }
        val invented = setOf("FRNDLY", "CHAMPNSHP", "LG", "CNCF", "LIB", "SUD", "QUAL", "REY", "IT", "ROU", "SL", "PRO", "CL", "AM")
        expected.values.forEach { tag -> assertTrue(tag, tag.split(' ').none { it in invented }) }
    }

    @Test
    fun `a long competition no rule knows reads as its game's sport, not its first word`() {
        assertEquals("SOCCER", scoreboardRowTag("Mexican Liga de Expansion MX Apertura", "Soccer", "Live"))
        assertEquals("BASKETBALL", scoreboardRowTag("Lithuanian Basketball League Regular", null, "Live", SportsLiveSportKind.BASKETBALL))
        assertEquals("HOCKEY", scoreboardRowTag("Swedish Hockey League Regular Season", "Ice Hockey", "Live"))
        // A short ESPN tag of ten capitals or fewer stays as it is.
        assertEquals("NBA", scoreboardRowTag("NBA", "Basketball", "Live"))
    }
}
