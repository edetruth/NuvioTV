package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import org.junit.Assert.assertEquals
import org.junit.Test

class SportsLiveChannelGrouperTest {
    private val addon = "Sports Streams"

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
        assertEquals(addon, group("Sky Sports Main Event", listOf("Sports", "NFL")))
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
        assertEquals(addon, group("L| DISNEY JR", emptyList()))
        assertEquals(addon, group("CINE Y SERIE | AXN HD", emptyList()))
        assertEquals(addon, group("VERYLONGPREFIX| X", emptyList()))
        assertEquals(addon, group("24| NEWS", emptyList()))
    }

    @Test
    fun `addon name fallback`() {
        assertEquals(addon, group("Sky Sports Main Event", emptyList()))
    }

    @Test
    fun `HD marker is not a group`() {
        assertEquals(addon, group("(HD) : TNT SPORT 1", listOf("Sports")))
    }

    private fun group(name: String, genres: List<String>) =
        SportsLiveChannelGrouper.groupOf(meta(name, genres), addon)

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
