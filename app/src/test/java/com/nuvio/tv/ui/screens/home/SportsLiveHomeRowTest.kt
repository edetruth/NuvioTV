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
    fun `a card with art gets it as its landscape poster, so Home draws no title over its logo`() {
        val channel = item("c").let { it.copy(meta = it.meta.copy(poster = "https://img.example/channel.png")) }
        val own = item("o").let { it.copy(meta = it.meta.copy(poster = "https://img.example/p.png", landscapePoster = "https://img.example/l.png")) }

        val row = SportsLiveHomeRow.build(listOf(channel, own, item("bare")), "Live now")

        assertEquals(
            listOf("https://img.example/channel.png", "https://img.example/l.png", null),
            row.items.map { it.landscapePoster }
        )
    }

    @Test
    fun `cards show the clean addon title with no taglines`() {
        val raw = item("r").let {
            it.copy(meta = it.meta.copy(name = "(FHD) : SPORTS CHANNEL 1 HQ", description = "Live IPTV Recording\nMatchday coverage"))
        }

        val card = SportsLiveHomeRow.build(listOf(raw), "Live now").items.single()

        assertEquals("Sports Channel 1 HQ", card.name)
        assertEquals("Matchday coverage", card.description)
        assertEquals(raw.id, card.id)
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
    fun `live now cards carry their status line as the label line, spoiler setting applied`() {
        val event = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA).first { it.eventId == "401909985" }
            .let { it.copy(state = ScoreState.IN, isFinal = false, clockText = "Q3 4:12", home = it.home.copy(score = 88), away = it.away.copy(score = 80)) }
        val game = item("g").let { it.copy(meta = it.meta.copy(name = "Pacers at 76ers", releaseInfo = "2026"), teams = SportsLiveTeams.parse("Pacers at 76ers")) }
        val unmatched = item("u")
        val strings = ScoreChipStrings("%1\$d–%2\$d", "%1\$s · %2\$s", "%1\$s %2\$s", "Final", "Postponed", "Score hidden")
        val scores = mapOf(game.streamKey to event)
        fun labels(mode: ScoresMode) = SportsLiveHomeRow.scoreLabels(listOf(game, unmatched), scores, mode, strings, "● %1\$s")

        assertEquals(mapOf(game.streamKey to "● 4:12 · Q3 · IND 80–88 PHI"), labels(ScoresMode.SHOW))
        // No focus reveal on Home: Hide until focused shows nothing there (and nothing is polled for it).
        assertEquals(emptyMap<String, String>(), labels(ScoresMode.HIDE_UNTIL_FOCUSED))
        assertEquals(emptyMap<String, String>(), labels(ScoresMode.OFF))

        val row = SportsLiveHomeRow.build(listOf(game, unmatched), "Live now", labels(ScoresMode.SHOW))
        assertEquals(listOf("● 4:12 · Q3 · IND 80–88 PHI", null), row.items.map { it.releaseInfo })
    }

    @Test
    fun `two addons sharing an item id keep their own score labels`() {
        val event = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA).first { it.eventId == "401909985" }
        fun game(addon: String) = item("same").let {
            it.copy(addonId = addon, meta = it.meta.copy(name = "Pacers at 76ers"), teams = SportsLiveTeams.parse("Pacers at 76ers"))
        }
        val first = game("addon.one")
        val second = game("addon.two")
        val strings = ScoreChipStrings("%1\$d–%2\$d", "%1\$s · %2\$s", "%1\$s %2\$s", "Final", "Postponed", "Score hidden")
        // Only the first addon's card is matched.
        val labels = SportsLiveHomeRow.scoreLabels(listOf(first, second), mapOf(first.streamKey to event), ScoresMode.SHOW, strings, "● %1\$s")
        assertEquals(mapOf(first.streamKey to "Final 124–131"), labels)
        val row = SportsLiveHomeRow.build(listOf(first, second), "Live now", labels)
        assertEquals(listOf("Final 124–131", null), row.items.map { it.releaseInfo })
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
