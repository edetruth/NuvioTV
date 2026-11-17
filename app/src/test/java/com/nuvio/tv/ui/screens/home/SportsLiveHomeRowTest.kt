package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.ScoreChipStrings
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
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

    @Test
    fun `live now cards carry their score chip as the label line, spoiler setting applied`() {
        val event = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA).first { it.eventId == "401909985" }
            .let { it.copy(state = ScoreState.IN, isFinal = false, clockText = "Q3 4:12", home = it.home.copy(score = 88), away = it.away.copy(score = 80)) }
        val game = item("g").let { it.copy(meta = it.meta.copy(name = "Pacers at 76ers", releaseInfo = "2026"), teams = SportsLiveTeams.parse("Pacers at 76ers")) }
        val unmatched = item("u")
        val strings = ScoreChipStrings("%1\$d–%2\$d", "%1\$s · %2\$s", "%1\$s %2\$s", "Final", "Postponed", "Score hidden")
        val scores = mapOf(game.streamKey to event)
        fun labels(mode: ScoresMode) = SportsLiveHomeRow.scoreLabels(listOf(game, unmatched), scores, mode, strings, "● %1\$s")

        assertEquals(mapOf("g" to "● Q3 4:12 · 80–88"), labels(ScoresMode.SHOW))
        assertEquals(mapOf("g" to "● Score hidden"), labels(ScoresMode.HIDE_UNTIL_FOCUSED))
        assertEquals(emptyMap<String, String>(), labels(ScoresMode.OFF))

        val row = SportsLiveHomeRow.build(listOf(game, unmatched), "Live now", labels(ScoresMode.SHOW))
        assertEquals(listOf("● Q3 4:12 · 80–88", null), row.items.map { it.releaseInfo })
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
