package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Channels phase 2: brand families, one row per channel, Popular now, countries and the browse letters. */
class SportsLiveChannelListTest {

    private fun channel(id: String, name: String, addon: String = "a", group: String? = null) = SportsLiveItem(
        meta = MetaPreview(
            id = id, type = ContentType.UNKNOWN, rawType = "tv", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = addon, addonName = addon, addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Channels",
        kind = SportsLiveKind.CHANNELS, isLive = true, isChannel = true, startMs = null, sport = null, group = group
    )

    private val l = SportsLiveChannelList
    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    // --- Brand families ---

    @Test
    fun `variants of one brand collapse to one family and feed`() {
        val keys = listOf("ESPN HD", "US: ESPN", "ESPN (East)", "ESPN FHD").map { SportsLiveChannelBrands.familyOf(it)?.rowKey }
        assertEquals(listOf("espn", "espn", "espn", "espn"), keys)
    }

    @Test
    fun `a numbered or named feed is its own row, written apart or run together`() {
        assertEquals("espn 2", SportsLiveChannelBrands.familyOf("ESPN 2")?.rowKey)
        assertEquals("espn 2", SportsLiveChannelBrands.familyOf("ESPN2")?.rowKey)
        assertEquals("sky sports main event", SportsLiveChannelBrands.familyOf("Sky Sports Main Event HD")?.rowKey)
        assertEquals("bbc one", SportsLiveChannelBrands.familyOf("BBC 1")?.rowKey)
        assertEquals("fox sports 502", SportsLiveChannelBrands.familyOf("Fox Sports 502")?.rowKey)
    }

    @Test
    fun `the longest brand wins and a name of no listed brand has no family`() {
        assertEquals("fox news", SportsLiveChannelBrands.familyOf("FOX News")?.family?.key)
        assertEquals(1, SportsLiveChannelBrands.familyOf("NFL Network")?.family?.tier)
        assertEquals(2, SportsLiveChannelBrands.familyOf("CNN")?.family?.tier)
        assertNull(SportsLiveChannelBrands.familyOf("Random Local Channel"))
    }

    @Test
    fun `the main feed comes first, then numbers in order, then names`() {
        val feeds = listOf("main event", "10", "", "2").sortedWith(
            compareBy({ SportsLiveChannelBrands.feedOrder(it).first }, { SportsLiveChannelBrands.feedOrder(it).second })
        )
        assertEquals(listOf("", "2", "10", "main event"), feeds)
    }

    // --- One row per channel ---

    @Test
    fun `the same channel across addons and qualities is one row keeping every link`() {
        val a = channel("a1", "US: ESPN", addon = "a")
        val b = channel("b1", "ESPN", addon = "b", group = "US")
        val c = channel("a2", "US: ESPN HD", addon = "a")
        val uk = channel("b2", "UK: ESPN", addon = "b")
        val merged = l.merge(listOf(a, uk, b, c))
        assertEquals(listOf("a1", "b2"), merged.map { it.id })
        assertEquals(listOf("b1", "a2"), merged.first().alternates.map { it.id })
        assertTrue(merged[1].alternates.isEmpty())
    }

    // --- Popular now ---

    private val catalog = listOf(
        channel("cnn", "US: CNN"),
        channel("espn", "US: ESPN"),
        channel("espn2", "US: ESPN 2"),
        channel("nfl", "US: NFL Network"),
        channel("skyuk", "UK: Sky Sports Main Event"),
        channel("skynews", "Sky Sports News"),
        channel("bein", "beIN Sports 1"),
        channel("x", "Random")
    )
    private val usAndNone = setOf("US", SportsLiveChannelRegions.OTHER)

    @Test
    fun `popular lists listed brands of the viewer's countries by tier and the list's order`() {
        val rows = l.popular(catalog, usAndNone, emptyMap(), now, dead = { false })
        // Sky Sports UK: another country; Sky Sports News: no country and made for the UK only; Random: no brand.
        assertEquals(listOf("espn", "espn2", "nfl", "bein", "cnn"), rows.map { it.id })
    }

    @Test
    fun `channels played twice in 30 days rise to the top, older plays do not count`() {
        val cnn = l.mergeKey(catalog.first())
        val nfl = l.mergeKey(catalog.first { it.id == "nfl" })
        val plays = mapOf(cnn to listOf(now - day, now - 2 * day), nfl to listOf(now - 40 * day, now - 41 * day, now - day))
        val rows = l.popular(catalog, usAndNone, plays, now, dead = { false })
        assertEquals("cnn", rows.first().id)
        assertEquals(listOf("cnn", "espn", "espn2", "nfl", "bein"), rows.map { it.id })
    }

    @Test
    fun `a brand whose links all failed leaves popular`() {
        val rows = l.popular(catalog, usAndNone, emptyMap(), now, dead = { it.id == "nfl" })
        assertFalse(rows.any { it.id == "nfl" })
    }

    @Test
    fun `every country lists other countries' brands too`() {
        val rows = l.popular(catalog, null, emptyMap(), now, dead = { false })
        assertTrue(rows.any { it.id == "skyuk" })
        assertTrue(rows.any { it.id == "skynews" })
    }

    @Test
    fun `one family fills at most three popular rows unless asked for all`() {
        val fox = (listOf("5", "9", "11", "13")).map { channel("fox$it", "US: FOX $it") }
        assertEquals(listOf("fox5", "fox9", "fox11"), l.popular(fox, usAndNone, emptyMap(), now, dead = { false }).map { it.id })
        assertEquals(4, l.popular(fox, usAndNone, emptyMap(), now, dead = { false }, perFamily = null).size)
    }

    @Test
    fun `a popular row is the variant with a country, the others its links`() {
        val bare = channel("bare", "ESPN HD")
        val us = channel("us", "US: ESPN")
        val rows = l.popular(listOf(bare, us), usAndNone, emptyMap(), now, dead = { false })
        assertEquals(listOf("us"), rows.map { it.id })
        assertEquals(listOf("bare"), rows.single().alternates.map { it.id })
    }

    // --- Countries ---

    @Test
    fun `your countries are the TV's region, your teams' league countries and no country listed`() {
        val followed = listOf(
            FollowedTeam("Arsenal", league = "soccer/eng.1"),
            FollowedTeam("Celtics", league = "basketball/nba"),
            FollowedTeam("Real Madrid", league = "soccer/uefa.champions")
        )
        val yours = SportsLiveChannelCountries.yours("US", followed)
        assertEquals(listOf("US", "UK", SportsLiveChannelRegions.OTHER), yours.map { it.country })
        assertEquals(SportsLiveCountryReason.TvRegion, yours[0].reason)
        assertEquals(SportsLiveCountryReason.Teams(listOf("Arsenal")), yours[1].reason)
        assertEquals(SportsLiveCountryReason.NoCountry, yours[2].reason)
    }

    @Test
    fun `the countries listed are yours by default, every one for All, and every one when a choice lists nothing`() {
        val channels = listOf(channel("1", "US: One"), channel("2", "UK: Two"), channel("3", "FR: Trois"))
        val yours = SportsLiveChannelCountries.yours("US", emptyList())
        assertEquals(setOf("US", SportsLiveChannelRegions.OTHER), SportsLiveChannelCountries.selected(null, yours, channels))
        assertNull(SportsLiveChannelCountries.selected(setOf(SportsLiveChannelCountries.ALL), yours, channels))
        assertEquals(setOf("FR"), SportsLiveChannelCountries.selected(setOf("FR"), yours, channels))
        assertNull(SportsLiveChannelCountries.selected(setOf("JP"), yours, channels))
    }

    @Test
    fun `league paths and the TV's locale read as channel countries`() {
        assertEquals("ES", SportsLiveChannelCountries.leagueCountry("soccer/esp.1"))
        assertEquals("US", SportsLiveChannelCountries.leagueCountry("football/nfl"))
        assertNull(SportsLiveChannelCountries.leagueCountry("soccer/uefa.champions"))
        assertNull(SportsLiveChannelCountries.leagueCountry(null))
        assertEquals("UK", SportsLiveChannelCountries.tvRegion(Locale.UK))
        assertNull(SportsLiveChannelCountries.tvRegion(Locale("en")))
    }

    @Test
    fun `most channels lists the biggest countries not already yours`() {
        val counts = listOf("UK" to 40, SportsLiveChannelRegions.OTHER to 30, "US" to 20, "FR" to 10)
        assertEquals(listOf("UK", "FR"), SportsLiveChannelCountries.most(counts, exclude = setOf("US")).map { it.first })
    }

    // --- Letters ---

    @Test
    fun `right jumps to the next letter and left to the start of the letter before`() {
        val letters = listOf("A", "A", "B", "C", "C")
        assertEquals(2, l.jump(letters, 0, forward = true))
        assertEquals(3, l.jump(letters, 2, forward = true))
        assertNull(l.jump(letters, 4, forward = true))
        assertEquals(2, l.jump(letters, 4, forward = false))
        assertEquals(0, l.jump(letters, 2, forward = false))
        assertNull(l.jump(letters, 1, forward = false))
    }

    @Test
    fun `the rail shows the letter before, the row's own and the next`() {
        val letters = listOf("A", "B", "B", "D")
        assertEquals(Triple("A", "B", "D"), l.around(letters, 2))
        assertEquals(Triple(null, "A", "B"), l.around(letters, 0))
        assertNull(l.around(letters, 9))
    }

    @Test
    fun `the rail shows plain letters when given the rows' sections`() {
        val sections = listOf("A", "B", "B", "D").map { l.LETTER + it }
        assertEquals(Triple("A", "B", "D"), l.around(sections, 1))
        assertEquals(Triple(null, "A", "B"), l.around(sections, 0))
    }

    @Test
    fun `channels sort A to Z with digits and symbols first under hash`() {
        val items = listOf(channel("1", "Zee"), channel("2", "1 Sports"), channel("3", "Élan"), channel("4", "alpha"))
        assertEquals(listOf("1 Sports", "alpha", "Élan", "Zee"), l.sortedAZ(items).map { it.meta.name })
        assertEquals(listOf("#", "A", "E", "Z"), l.sortedAZ(items).map(l::letterOf))
    }

    @Test
    fun `find a channel matches every word of the query in the name or brand`() {
        val sky = channel("1", "Sky Sports Main Event")
        assertTrue(l.matches(sky, "sky main"))
        assertTrue(l.matches(sky, ""))
        assertFalse(l.matches(sky, "sky news"))
    }

    @Test
    fun `a play is counted for 30 days and the oldest plays drop`() {
        val espn = channel("e", "US: ESPN")
        var picks = SportsLiveChannelPicks()
        picks = SportsLiveChannelFavorites.played(picks, espn, now - 40 * day)
        picks = SportsLiveChannelFavorites.played(picks, espn, now - day)
        picks = SportsLiveChannelFavorites.played(picks, espn, now)
        assertEquals(listOf(now - day, now), picks.plays[l.mergeKey(espn)])
        assertEquals(2, l.playCount(picks.plays, listOf(l.mergeKey(espn)), now))
    }
}
