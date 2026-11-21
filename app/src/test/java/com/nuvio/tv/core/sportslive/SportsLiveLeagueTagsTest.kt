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
        assertEquals("COPA LIB", SportsLiveLeagueTags.of("CONMEBOL Libertadores"))
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
        assertEquals("ARG LIGA", scoreboardRowTag("Argentine LPF", "Soccer", "Live"))
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
}
