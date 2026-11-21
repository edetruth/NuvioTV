package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ClaimedCatalog
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * live.24 Channels: IPTV and TV channel catalogs reach Channels (by name or by country-prefixed titles), never the games
 * tabs; a channel catalog's game entries are games (#12); a prefix-less US network is a US channel. Synthetic names only.
 */
class SportsLiveChannelCatalogsTest {
    private val now = Instant.parse("2026-11-21T16:00:00Z").toEpochMilli()

    // --- Claimed as channels by name ---

    @Test
    fun `a tv catalog named IPTV or TV is a channels catalog`() {
        for (name in listOf("Acme IPTV (tv)", "Acme Tv + (tv)", "Acme TV", "Acme Live TV", "IPTV")) {
            val catalog = catalog("tv", "c", name)
            assertEquals(name, SportsLiveKind.CHANNELS, SportsLiveCatalogPolicy.classify(addon(catalog), catalog, emptyMap())?.kind)
        }
    }

    @Test
    fun `a tv catalog naming events, games, a schedule or replays is no channels catalog`() {
        assertEquals(SportsLiveKind.EVENTS, kindOf("tv", "⚡Acme Live Events (tv)"))
        assertEquals(SportsLiveKind.EVENTS, kindOf("tv", "⚡AcmeTV Live Events (tv)"))
        assertEquals(SportsLiveKind.EVENTS, kindOf("tv", "TV Schedule"))
        assertEquals(SportsLiveKind.EVENTS, kindOf("tv", "TV Games"))
        assertEquals(SportsLiveKind.REPLAY, kindOf("tv", "🎬Acme Highlights (tv)"))
        // The "(tv)" type tag alone says nothing.
        assertEquals(SportsLiveKind.EVENTS, kindOf("tv", "Acme (tv)"))
        assertEquals(SportsLiveKind.EVENTS, kindOf("tv", "News"))
    }

    @Test
    fun `the name rule is for tv catalogs only`() {
        val movies = catalog("movie", "m", "Acme IPTV")
        assertNull(SportsLiveCatalogPolicy.classify(addon(movies), movies, emptyMap()))
        assertFalse(SportsLiveChannelCatalogs.namesChannels("Acme Live Events (tv)"))
        assertTrue(SportsLiveChannelCatalogs.namesChannels("Acme IPTV (tv)"))
    }

    // --- Claimed as channels by country prefixes ---

    @Test
    fun `country prefixes are read in their usual forms`() {
        for (title in listOf("US| ABC HD", "US : ESPN", "UK | Sky Sports Main Event", "(US) NFL Network", "[UK] BBC One", "ASIA-IN| STAR SPORTS 1", "LATIN-BR| BAND SPORTS", "USA: FOX")) {
            assertTrue(title, SportsLiveChannelCatalogs.hasCountryPrefix(title))
        }
        for (title in listOf("LIVE: A vs B", "PPV| EVENT 01", "NFL: Bears vs Lions", "ESPN", "EXYU| CITY TV", "📺 ABC", "24/7 | Seinfeld")) {
            assertFalse(title, SportsLiveChannelCatalogs.hasCountryPrefix(title))
        }
    }

    @Test
    fun `most of a first page prefixed makes a channel list, a short or mixed page does not`() {
        val prefixed = (1..6).map { "US| CHANNEL $it" }
        val plain = (1..4).map { "Channel $it" }
        assertTrue(SportsLiveChannelCatalogs.mostlyCountryPrefixed(prefixed + plain))
        assertFalse(SportsLiveChannelCatalogs.mostlyCountryPrefixed(prefixed.take(5) + plain + "Channel 5"))
        assertFalse(SportsLiveChannelCatalogs.mostlyCountryPrefixed(prefixed.take(5)))
    }

    @Test
    fun `an events-claimed tv catalog of prefixed channels lists them on Channels only`() {
        val claimed = claimed("tv", "Acme", SportsLiveKind.EVENTS)
        val names = listOf("US| ABC HD", "US| NFL NETWORK", "US| ESPN 2", "UK| SKY SPORTS MAIN EVENT", "CA| TSN 1", "AU| FOX FOOTY",
            "FR| CANAL+ SPORT", "DE| SKY SPORT 1", "IT| RAI 1", "ES| LA 1", "PPV| EVENT 01", "VIP CHANNEL")
        val items = SportsLiveItemBucketer.describeCatalog(names.mapIndexed { i, name -> meta("c$i", name) }, claimed, now)
        assertTrue(items.all { it.isChannel && it.kind == SportsLiveKind.CHANNELS && !it.isLive })
        val tabs = SportsLiveItemBucketer.bucket(items, now)
        assertEquals(names.size, tabs.getValue(SportsLiveTab.CHANNELS).size)
        assertTrue(tabs.getValue(SportsLiveTab.LIVE_NOW).isEmpty())
        assertTrue(tabs.getValue(SportsLiveTab.UPCOMING).isEmpty())
    }

    @Test
    fun `the prefix rule is for tv catalogs only`() {
        val sports = claimed("sport", "Acme", SportsLiveKind.EVENTS)
        val names = (1..12).map { "US| CHANNEL $it" }
        assertEquals(sports, SportsLiveItemBucketer.channelListOf(names.map { meta(it, it) }, sports))
        val tv = claimed("tv", "Acme", SportsLiveKind.EVENTS)
        assertEquals(SportsLiveKind.CHANNELS, SportsLiveItemBucketer.channelListOf(names.map { meta(it, it) }, tv).kind)
    }

    // --- Game entries in a channel catalog (#12) ---

    @Test
    fun `a game in a channel catalog is a game, real channels stay channels`() {
        val game = entry("g", "Memphis Tigers @ Navy Midshipmen")
        assertFalse(game.isChannel)
        assertEquals(SportsLiveKind.EVENTS, game.kind)
        assertFalse(listed(game, SportsLiveTab.CHANNELS))
        assertTrue(listed(game, SportsLiveTab.UPCOMING))
        for (name in listOf("US| ESPN HD", "Sky Sports Tennis", "NBA TV", "📺 ABC", "US| PEACOCK PPV 004 [Celtics vs. 76ers]", "24/7 Lakers vs Celtics Classics", "News X World")) {
            val channel = entry(name, name)
            assertTrue(name, channel.isChannel)
            assertTrue(name, listed(channel, SportsLiveTab.CHANNELS))
        }
    }

    @Test
    fun `a live game in a channel catalog merges with another addon's copy on Live now`() {
        val fromChannels = entry("a1", "🔴 Memphis Tigers @ Navy Midshipmen", addonId = "a")
        val fromEvents = SportsLiveItemBucketer.describe(
            meta("b1", "Navy Midshipmen vs Memphis Tigers", releaseInfo = "LIVE"),
            claimed("sport", "Live", SportsLiveKind.EVENTS, addonId = "b"),
            now
        )
        val live = SportsLiveItemBucketer.bucketTab(listOf(fromChannels, fromEvents), SportsLiveTab.LIVE_NOW, now, addonOrder = listOf("b", "a"))
        assertEquals(listOf("b1"), live.map { it.id })
        assertEquals(listOf("a1"), live.single().alternates.map { it.id })
    }

    // --- US networks with no written country ---

    @Test
    fun `an unambiguous US network with no country is a US channel`() {
        for (name in listOf("ABC", "📺 CBS", "📺 ESPN 2", "ESPN2", "📺 Espnu", "ABC News Live", "📺 FOX Sports 1", "📺 FOX Deportes",
            "TNT", "TBS", "USA Network", "NFL Network", "📺 Big Ten Network", "Golf Channel HD", "CNN", "MSNBC", "📺 Cw", "PBS", "📺 Hgtv", "📺 Fx")) {
            assertEquals(name, "US", SportsLiveChannelRegions.regionOf(channel(name)))
            assertTrue(name, SportsLiveChannelRegions.inferred(channel(name)))
        }
    }

    @Test
    fun `a network naming another country or a non-US brand is not inferred`() {
        for (name in listOf("ABC Australia", "📺 FOX Sports 501 (cricket)", "📺 FOX Sports 503", "Fox League", "Fox Footy", "TNT Sports 1",
            "Sky Sports Main Event", "TSN 1", "DAZN 1", "CNN News18", "CNN International", "ESPN Brasil", "Random Local Channel")) {
            assertEquals(name, SportsLiveChannelRegions.OTHER, SportsLiveChannelRegions.regionOf(channel(name)))
            assertFalse(name, SportsLiveChannelRegions.inferred(channel(name)))
        }
        // A long group naming another country keeps its own region.
        assertEquals("UNITED KINGDOM TV", SportsLiveChannelRegions.regionOf(channel("CNN", group = "UNITED KINGDOM TV")))
    }

    @Test
    fun `a written country always beats the inferred one`() {
        assertEquals("MX", SportsLiveChannelRegions.regionOf(channel("LATIN-MX| ESPN 2 HD", group = "LATIN-MX")))
        assertEquals("UK", SportsLiveChannelRegions.regionOf(channel("UK: ESPN")))
        assertFalse(SportsLiveChannelRegions.inferred(channel("US| ABC HD", group = "US")))
    }

    @Test
    fun `a prefix-less US network merges with the US-prefixed copy, which leads`() {
        val bare = channel("📺 ABC", addonId = "b")
        val us = channel("US| ABC HD", group = "US", addonId = "a")
        val merged = SportsLiveChannelList.merge(listOf(bare, us))
        assertEquals(1, merged.size)
        val popular = SportsLiveChannelList.popular(listOf(bare, us), setOf("US"), emptyMap(), now, dead = { false })
        assertEquals(us.id, popular.single().id)
    }

    private fun kindOf(type: String, name: String): SportsLiveKind? {
        val catalog = catalog(type, "c", name)
        return SportsLiveCatalogPolicy.classify(addon(catalog), catalog, emptyMap())?.kind
    }

    private fun listed(item: SportsLiveItem, tab: SportsLiveTab) =
        SportsLiveItemBucketer.bucketTab(listOf(item), tab, now, addonOrder = listOf(item.addonId)).any { it.id == item.id }

    private fun entry(id: String, name: String, addonId: String = "a"): SportsLiveItem =
        SportsLiveItemBucketer.describe(meta(id, name), claimed("tv", "TV", SportsLiveKind.CHANNELS, addonId = addonId), now)

    private fun channel(name: String, group: String? = null, addonId: String = "a"): SportsLiveItem =
        SportsLiveItemBucketer.describe(meta(name, name), claimed("tv", "Channels", SportsLiveKind.CHANNELS, addonId = addonId), now)
            .copy(group = group)

    private fun meta(id: String, name: String, releaseInfo: String? = null) = MetaPreview(
        id = id, type = ContentType.UNKNOWN, rawType = "tv", name = name, poster = null,
        posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
        releaseInfo = releaseInfo, imdbRating = null, genres = emptyList()
    )

    private fun catalog(type: String, id: String, name: String) =
        CatalogDescriptor(type = ContentType.fromString(type), rawType = type, id = id, name = name)

    private fun addon(vararg catalogs: CatalogDescriptor, id: String = "a") = Addon(
        id = id, name = "Addon $id", version = "1.0.0", description = null, logo = null,
        baseUrl = "https://example.invalid", catalogs = catalogs.toList(), types = catalogs.map { it.type }, resources = emptyList()
    )

    private fun claimed(type: String, name: String, kind: SportsLiveKind, addonId: String = "a"): ClaimedCatalog {
        val catalog = catalog(type, name.lowercase(), name)
        return ClaimedCatalog(addon(catalog, id = addonId), catalog, "${addonId}_${type}_${catalog.id}", kind, false)
    }
}
