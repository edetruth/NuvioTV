package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ClaimedCatalog
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** H (user report #6, live.23): listings that are no game and no event of their own fold under "Other events". */
class SportsLiveOtherEventsTest {
    private val now = Instant.parse("2026-11-21T12:00:00Z").toEpochMilli()

    @Test
    fun `the report's titles not placed are other events`() {
        for (title in listOf(
            "ATP 250 vs Stockholm Open", "ATP", "WTA", "TENNIS", "BKFC", "ISL", "NPL", "Raw", "Dynamite", "Friday Fights",
            "One Friday Fights", "Gromda", "Italia T10", "🔴 BKFC", "LIVE: Gromda", "WTA 1000 vs Wuhan Open"
        )) {
            assertTrue(title, SportsLiveOtherEvents.isOtherEvent(event("a", title, title)))
        }
    }

    @Test
    fun `a tour written beside a tournament is folded, from the title or the description`() {
        assertTrue(SportsLiveOtherEvents.isOtherEvent(event("a", "1", "ATP 250 vs Stockholm Open", listOf("Tennis"))))
        val described = event("a", "2", "ATP 250", listOf("Tennis"), description = "Tennis · Live now\nATP 250 v Stockholm Open")
        assertEquals("ATP 250 · Stockholm Open", described.displayName)
        assertTrue(SportsLiveOtherEvents.isOtherEvent(described))
    }

    @Test
    fun `games, fights and titled events stay rows`() {
        for (title in listOf(
            "Sinner vs Alcaraz", "Henry Cejudo vs Song Yadong", "Jack Paul vs Cedric Doumbe", "Khor Fakkan vs Al-Jazira",
            "Burnley vs Blackburn Rovers", "MotoGP", "Formula 1 | Brazil", "UFC 310", "ATP Finals", "ATP Finals 2026",
            "Wimbledon | Day 6", "Moto3 | Round 22 | Portugal", "Premier League Darts", "Masters"
        )) {
            assertFalse(title, SportsLiveOtherEvents.isOtherEvent(event("a", title, title)))
        }
    }

    @Test
    fun `real channels stay channels`() {
        for (title in listOf("Sky Sports Tennis", "Tennis Channel")) {
            val item = event("a", title, title, listOf("Tennis"))
            assertTrue(title, item.isChannel)
            assertFalse(title, SportsLiveOtherEvents.isOtherEvent(item))
        }
    }

    @Test
    fun `copies of one entry across catalogs collapse to one with every source`() {
        val first = event("a", "1", "ATP 250 vs Stockholm Open", catalog = "Live Events A")
        val second = event("a", "2", "ATP 250 vs Stockholm Open", catalog = "Live Events B")
        val bkfc = event("a", "3", "BKFC")
        val game = event("a", "4", "Burnley vs Blackburn Rovers")
        val (games, others) = SportsLiveOtherEvents.split(listOf(first, game, second, bkfc))
        assertEquals(listOf("4"), games.map { it.id })
        assertEquals(listOf("1", "3"), others.map { it.id })
        assertEquals(listOf("2"), others.first().alternates.map { it.id })
    }

    private fun event(
        addonId: String,
        id: String,
        name: String,
        genres: List<String> = emptyList(),
        description: String? = null,
        catalog: String = "Live Events"
    ): SportsLiveItem =
        SportsLiveItemBucketer.describe(
            MetaPreview(
                id = id, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
                posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = description,
                releaseInfo = "LIVE", imdbRating = null, genres = genres
            ),
            claimed(addonId, catalog),
            now
        )

    private fun claimed(addonId: String, name: String): ClaimedCatalog {
        val catalog = CatalogDescriptor(type = ContentType.fromString("sport"), rawType = "sport", id = name.lowercase().replace(' ', '_'), name = name)
        val addon = Addon(
            id = addonId, name = "Sports $addonId", version = "1.0.0", description = null, logo = null,
            baseUrl = "https://example.invalid", catalogs = listOf(catalog), types = listOf(catalog.type), resources = emptyList()
        )
        return ClaimedCatalog(addon, catalog, "${addonId}_sport_${catalog.id}", SportsLiveKind.EVENTS, false)
    }
}
