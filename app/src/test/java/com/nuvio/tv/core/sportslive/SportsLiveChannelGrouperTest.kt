package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Test

class SportsLiveChannelGrouperTest {
    private val addon = "Sports Streams"
    private val addonGroup = "SPORTS STREAMS"

    @Test
    fun `country from second genre token`() {
        assertEquals("CA", group("CA : TSN 4", listOf("Sports", "CA-TSN-44343")))
    }

    @Test
    fun `provider code genre still yields country`() {
        assertEquals("UK", group("(HD) : TNT SPORT 1", listOf("Sports", "UK-TNT-SPORT-13434")))
    }

    @Test
    fun `genre code beats a valid name prefix`() {
        assertEquals("CA", group("US : X", listOf("Sports", "CA-TSN-44343")))
    }

    @Test
    fun `bare league tag in second genre is not a group`() {
        assertEquals("US", group("US : ESPN HD", listOf("Sports", "NFL")))
        assertEquals(addonGroup, group("Sky Sports Main Event", listOf("Sports", "NFL")))
    }

    @Test
    fun `name prefix when genre has no code`() {
        assertEquals("US", group("US : ESPN HD", listOf("Sports")))
    }

    @Test
    fun `pipe name prefixes from CNCVerse`() {
        assertEquals("UK", group("UK| SKY SPORTS 1", emptyList()))
        assertEquals("ASIA-IN", group("ASIA-IN| JIO EVENTS FHD", emptyList()))
        assertEquals("DSTV", group("DSTV| SUPERSPORT FOOTBALL", emptyList()))
        assertEquals("US", group("US| PEACOCK PPV 009 [Colgate vs. St. John's (2026-11-11 18:30:00)]", emptyList()))
    }

    @Test
    fun `prefixes that are too short, too long, spaced or digits only are not groups`() {
        assertEquals(addonGroup, group("L| DISNEY JR", emptyList()))
        assertEquals(addonGroup, group("CINE Y SERIE | AXN HD", emptyList()))
        assertEquals(addonGroup, group("VERYLONGPREFIX| X", emptyList()))
        assertEquals(addonGroup, group("24| NEWS", emptyList()))
    }

    @Test
    fun `addon name fallback`() {
        assertEquals(addonGroup, group("Sky Sports Main Event", emptyList()))
    }

    @Test
    fun `catalog name without emoji and type tag comes before the addon name`() {
        val flag = "🇬🇧"
        assertEquals("UNITED KINGDOM TV", group("Afrobeats", listOf("Live TV"), catalog = "$flag United Kingdom TV"))
        assertEquals("EASYPLAY LIVE TV", group("Some channel", emptyList(), catalog = "EasyPlay Live TV (tv)"))
        assertEquals("SPORTZX LIVE EVENTS", group("Some channel", emptyList(), catalog = "⚡SportzX Live Events (tv)"))
        assertEquals("US", group("US| ESPN", emptyList(), catalog = "$flag United Kingdom TV"))
        assertEquals(addonGroup, group("Some channel", emptyList(), catalog = " ⚡ (tv) "))
    }

    @Test
    fun `generic catalog names fall back to the addon name`() {
        assertEquals("NEBULA SPORTS", SportsLiveChannelGrouper.groupOf(meta("Sky Sports Main Event", emptyList()), "Channels", "Nebula Sports"))
        for (catalog in listOf("channels", "Channel List", "📺 Live TV (tv)", "TV", "Live Channels", "ALL CHANNELS")) {
            assertEquals(catalog, addonGroup, group("Some channel", emptyList(), catalog = catalog))
        }
        val one = SportsLiveChannelGrouper.groupOf(meta("ESPN", emptyList()), "Live TV", "Addon One")
        val two = SportsLiveChannelGrouper.groupOf(meta("ESPN", emptyList()), "Live TV", "Addon Two")
        assertEquals(listOf("ADDON ONE", "ADDON TWO"), listOf(one, two))
    }

    @Test
    fun `groups differing only in case are one upper-cased group`() {
        assertEquals("BIH", group("BiH| FACE TV", emptyList()))
        assertEquals("BIH", group("BIH| N1", emptyList()))
    }

    @Test
    fun `HD marker is not a group`() {
        assertEquals(addonGroup, group("(HD) : TNT SPORT 1", listOf("Sports")))
    }

    private fun group(name: String, genres: List<String>, catalog: String = "") =
        SportsLiveChannelGrouper.groupOf(meta(name, genres), catalog, addon)

    private fun meta(name: String, genres: List<String>) = MetaPreview(
        id = name,
        type = ContentType.UNKNOWN,
        rawType = "sport",
        name = name,
        poster = null,
        posterShape = PosterShape.LANDSCAPE,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = genres
    )
}
