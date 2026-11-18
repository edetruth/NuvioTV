package com.nuvio.tv.core.sportslive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SportsLiveDisplayTextTest {

    private fun of(raw: String) = SportsLiveDisplayText.of(raw)

    @Test
    fun `a bracketed quality prefix becomes the quality badge and the caps name is title cased`() {
        val title = of("(FHD) : SPORTS CHANNEL 1 HQ")
        assertEquals("Sports Channel 1 HQ", title.name)
        assertEquals("FHD", title.quality)
        assertNull(title.region)
        assertEquals("(FHD) : SPORTS CHANNEL 1 HQ", title.raw)
    }

    @Test
    fun `an HD prefix in brackets and a region prefix both come off`() {
        val title = of("(HD) : UK : SPORTS NEWS")
        assertEquals("Sports News", title.name)
        assertEquals("HD", title.quality)
        assertEquals("UK", title.region)
    }

    @Test
    fun `a region prefix before the quality comes off in either order`() {
        val title = of("US : (FHD) SPORTS CHANNEL 2")
        assertEquals("Sports Channel 2", title.name)
        assertEquals("FHD", title.quality)
        assertEquals("US", title.region)
    }

    @Test
    fun `region prefixes with a pipe or no space are read`() {
        assertEquals("CA", of("CA| Sports Channel 3").region)
        assertEquals("UK", of("UK:Sports Channel 4").region)
        assertEquals("Sports Channel 4", of("UK:Sports Channel 4").name)
    }

    @Test
    fun `quality suffixes come off the end`() {
        assertEquals("Sports Channel 5" to "HD", of("Sports Channel 5 HD").let { it.name to it.quality })
        assertEquals("Sports Channel 6" to "FHD", of("Sports Channel 6 FHD").let { it.name to it.quality })
        assertEquals("Sports Channel 7" to "4K", of("Sports Channel 7 4K").let { it.name to it.quality })
        assertEquals("Sports Channel 8" to "UHD", of("Sports Channel 8 (UHD)").let { it.name to it.quality })
        assertEquals("Sports Channel 9" to "HD", of("Sports Channel 9 [hd]").let { it.name to it.quality })
    }

    @Test
    fun `taglines and ids are dropped`() {
        assertEquals("Team A vs Team B", of("Team A vs Team B - LIVE NOW").name)
        assertEquals("Team A vs Team B", of("Team A vs Team B Live IPTV Recording").name)
        assertEquals("Sports Channel 1", of("Sports Channel 1 · Live on ABC-123-XY434").name)
    }

    @Test
    fun `repeated whitespace collapses`() {
        assertEquals("Team A vs Team B", of("  Team A    vs\tTeam B  ").name)
    }

    @Test
    fun `known acronyms stay upper case in a title cased name`() {
        assertEquals("ESPN 2", of("ESPN 2").name)
        assertEquals("TNT Sports 1", of("TNT SPORTS 1").name)
        assertEquals("NBA TV", of("NBA TV").name)
        assertEquals("NFL Network", of("NFL NETWORK").name)
        assertEquals("Sports News HQ", of("UK: SPORTS NEWS HQ").name)
    }

    @Test
    fun `small words stay lower case except the first`() {
        assertEquals("Team A vs Team B", of("TEAM A VS TEAM B").name)
        assertEquals("The Match of the Day", of("THE MATCH OF THE DAY").name)
    }

    @Test
    fun `a mixed case name keeps its case`() {
        assertEquals("McLaren Racing iTV", of("McLaren Racing iTV").name)
        assertEquals("Lakers at Suns", of("Lakers at Suns").name)
    }

    @Test
    fun `quality words inside a name are left alone`() {
        assertEquals("HDTV Sports", of("HDTV Sports").name)
        assertNull(of("HDTV Sports").quality)
        assertEquals("HD Sports", of("HD Sports").name)
        assertNull(of("HD Sports").quality)
    }

    @Test
    fun `a league prefix is not a region`() {
        val title = of("NBA: Lakers vs Suns")
        assertNull(title.region)
        assertEquals("NBA: Lakers vs Suns", title.name)
    }

    @Test
    fun `a live on phrase without an id is part of the name`() {
        assertEquals("Live on Saturday", of("Live on Saturday").name)
    }

    @Test
    fun `a name that is only a quality or a tagline falls back to the raw text`() {
        assertEquals("HD", of("HD").name)
        assertEquals("LIVE NOW", of("LIVE NOW").name)
    }

    @Test
    fun `a codes token with digits keeps its case`() {
        assertEquals("F1 Live Coverage", of("F1 LIVE COVERAGE").name)
    }

    @Test
    fun `an empty title stays empty`() {
        assertEquals("", of("").name)
    }

    @Test
    fun `descriptions lose tagline lines and keep the rest`() {
        val raw = "Live IPTV Recording\nLive on ABC-123-XY434\nLIVE NOW\nMatchday coverage"
        assertEquals("Matchday coverage", SportsLiveDisplayText.description(raw))
    }

    @Test
    fun `a description of taglines only is null`() {
        assertNull(SportsLiveDisplayText.description("Live IPTV Recording\nLIVE NOW"))
        assertNull(SportsLiveDisplayText.description(null))
        assertNull(SportsLiveDisplayText.description("   "))
    }

    @Test
    fun `name is the clean name alone`() {
        assertEquals("Sports Channel 1 HQ", SportsLiveDisplayText.name("(FHD) : SPORTS CHANNEL 1 HQ"))
    }
}
