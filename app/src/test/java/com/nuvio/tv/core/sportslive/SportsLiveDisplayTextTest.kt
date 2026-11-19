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
    fun `a LIVE prefix comes off, as the row's red dot already says it`() {
        assertEquals("Bowls: New Zealand vs India", of("LIVE: Bowls: New Zealand vs India").name)
        assertEquals("Late Night Talk Show", of("Live - Late Night Talk Show").name)
        // The Resume row (#148) reads the same clean title.
        assertEquals("PIF Saudi International", of("LIVE: PIF Saudi International").name)
        assertEquals("Celtics vs Knicks", of("● LIVE | Celtics vs Knicks").name)
        // "Live" as a word of the name stays.
        assertEquals("Live on Saturday", of("Live on Saturday").name)
        assertEquals("Live Aid", of("Live Aid").name)
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

    @Test
    fun `a round-the-clock channel marker goes, its language code kept as the region`() {
        val title = SportsLiveDisplayText.of("24/7-EN - Game of Thrones")
        assertEquals("Game of Thrones", title.name)
        assertEquals("EN", title.region)
        assertEquals("Classic Movies", SportsLiveDisplayText.name("24/7 | Classic Movies"))
        assertEquals("UK News", SportsLiveDisplayText.name("24/7 - UK News"))
        // A name that merely starts with digits stays.
        assertEquals("24 Hours of Le Mans", SportsLiveDisplayText.name("24 Hours of Le Mans"))
    }

    // P4 B5: show and promotion names keep their acronym caps

    @Test
    fun `an all caps word of two to four letters keeps its caps`() {
        assertEquals("AEW", of("AEW").name)
        assertEquals("ROH Wrestling", of("ROH WRESTLING").name)
        assertEquals("PFL Fight Night", of("PFL FIGHT NIGHT").name)
    }

    @Test
    fun `common short words in a caps title still read as words`() {
        assertEquals("Sports News Live", of("SPORTS NEWS LIVE").name)
        assertEquals("The Big Game", of("THE BIG GAME").name)
        assertEquals("Team A vs Team B", of("TEAM A VS TEAM B").name)
    }

    @Test
    fun `a long caps word is still title cased`() {
        assertEquals("Wrestling", of("WRESTLING").name)
    }

    // P4 #90: replay titles never carry a score

    @Test
    fun `spoiler free drops a scoreline between the teams`() {
        assertEquals("Team A vs Team B", SportsLiveDisplayText.spoilerFree("Team A 110-102 Team B"))
        assertEquals("Team A vs Team B", SportsLiveDisplayText.spoilerFree("Team A vs Team B (2-1)"))
        assertEquals("Team A vs Team B", SportsLiveDisplayText.spoilerFree("Team A vs Team B 3 – 0"))
    }

    @Test
    fun `spoiler free keeps dates seasons and times`() {
        assertEquals("Team A vs Team B 11-16-2026", SportsLiveDisplayText.spoilerFree("Team A vs Team B 11-16-2026"))
        assertEquals("League 2025-26 Team A vs Team B", SportsLiveDisplayText.spoilerFree("League 2025-26 Team A vs Team B"))
        assertEquals("Team A vs Team B 20:00", SportsLiveDisplayText.spoilerFree("Team A vs Team B 20:00"))
    }

    @Test
    fun `spoiler free also cleans the title`() {
        assertEquals("Team A vs Team B", SportsLiveDisplayText.spoilerFree("TEAM A VS TEAM B 1-0 Live IPTV Recording"))
    }
}
