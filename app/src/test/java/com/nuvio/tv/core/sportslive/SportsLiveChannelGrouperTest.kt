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
    fun `name prefix when genre has no code`() {
        assertEquals("US", group("US : ESPN HD", listOf("Sports")))
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
