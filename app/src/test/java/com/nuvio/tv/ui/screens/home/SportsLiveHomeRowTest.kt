package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.nuvio.tv.ui.components.catalogRowShowsSeeAllByDefault
import org.junit.Test

class SportsLiveHomeRowTest {

    @Test
    fun `build produces a row with the sentinel ids and the items metas`() {
        val items = listOf(item("a"), item("b"))

        val row = SportsLiveHomeRow.build(items, "Live now")

        assertEquals(SportsLiveHomeRow.ADDON_ID, row.addonId)
        assertEquals(SportsLiveHomeRow.BASE_URL, row.addonBaseUrl)
        assertEquals(SportsLiveHomeRow.CATALOG_ID, row.catalogId)
        assertEquals("Live now", row.catalogName)
        assertEquals("sport", row.apiType)
        assertEquals(items.map { it.meta }, row.items)
        assertFalse(row.hasMore)
        assertFalse(row.supportsSkip)
        assertFalse(row.isLoading)
    }

    @Test
    fun `isLiveNowRow matches only the sentinel base url`() {
        assertTrue(SportsLiveHomeRow.isLiveNowRow("nuvio://sports-live"))
        assertFalse(SportsLiveHomeRow.isLiveNowRow("https://addon.example"))
        assertFalse(SportsLiveHomeRow.isLiveNowRow(""))
    }

    @Test
    fun `the Live now row always shows see all even when short and without more pages`() {
        val row = SportsLiveHomeRow.build(listOf(item("a")), "Live now")

        assertTrue(SportsLiveHomeRow.alwaysShowsSeeAll(row))
        assertFalse(catalogRowShowsSeeAllByDefault(row))
    }

    @Test
    fun `ordinary catalog rows keep the default see all rule`() {
        val shortRow = SportsLiveHomeRow.build(List(3) { item("s$it") }, "Popular")
            .copy(addonId = "com.cinemeta", addonBaseUrl = "https://cinemeta.example")
        val longRow = shortRow.copy(items = List(15) { item("l$it").meta })

        assertFalse(SportsLiveHomeRow.alwaysShowsSeeAll(shortRow))
        assertFalse(catalogRowShowsSeeAllByDefault(shortRow))
        assertTrue(catalogRowShowsSeeAllByDefault(shortRow.copy(hasMore = true)))
        assertTrue(catalogRowShowsSeeAllByDefault(longRow))
    }

    companion object {
        fun item(id: String) = SportsLiveItem(
            meta = MetaPreview(
                id = id,
                type = ContentType.UNKNOWN,
                rawType = "tv",
                name = "Event $id",
                poster = null,
                posterShape = PosterShape.LANDSCAPE,
                background = null,
                logo = null,
                description = null,
                releaseInfo = null,
                imdbRating = null,
                genres = emptyList()
            ),
            addonId = "sports.addon",
            addonName = "Sports",
            addonBaseUrl = "https://sports.example",
            catalogKey = "sports.addon_tv_live",
            catalogName = "Live",
            kind = SportsLiveKind.EVENTS,
            isLive = true,
            isChannel = false,
            startMs = null,
            sport = null
        )
    }
}
