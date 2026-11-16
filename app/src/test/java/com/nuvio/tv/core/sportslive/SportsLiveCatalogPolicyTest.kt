package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogExtra
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SportsLiveCatalogPolicyTest {
    @Test
    fun `sport typed catalog is claimed as EVENTS`() {
        val catalog = catalog("sport", "sports_live", "Live Now")
        val claim = classify(addon("highfly", "Sports Streams", catalog), catalog)
        assertEquals(SportsLiveKind.EVENTS, claim?.kind)
        assertEquals("type:sport", claim?.reason)
    }

    @Test
    fun `sports typed catalog is claimed by the type rule`() {
        val live = catalog("sports", "streamed-events-live-now", "Nebula Sports: Live Now")
        val replays = catalog("sports", "streamed-replays", "Nebula Sports: Replays")
        val addon = addon("org.nebulastreams.sports", "Nebula Sports", live, replays)

        val liveClaim = classify(addon, live)
        assertEquals("type:sports", liveClaim?.reason)
        assertEquals(SportsLiveKind.EVENTS, liveClaim?.kind)
        assertEquals(SportsLiveKind.REPLAY, classify(addon, replays)?.kind)
        val plain = catalog("sports", "all", "Schedule")
        assertEquals("type:sports", classify(addon("plain", "Plain Addon", plain), plain)?.reason)
    }

    @Test
    fun `tv typed catalog from any addon is claimed`() {
        val catalog = catalog("tv", "news", "News")
        val claim = classify(addon("news.iptv", "News IPTV", catalog), catalog)
        assertNotNull(claim)
        assertEquals(SportsLiveKind.EVENTS, claim?.kind)
    }

    @Test
    fun `channel typed catalog named Channels is CHANNELS`() {
        val catalog = catalog("channel", "tv_channels", "Channels - TV")
        val claim = classify(addon("pengu", "PenguPlay", catalog), catalog)
        assertEquals(SportsLiveKind.CHANNELS, claim?.kind)
    }

    @Test
    fun `channel typed catalog is CHANNELS whatever its name`() {
        val plain = catalog("channel", "uk", "United Kingdom")
        assertEquals(SportsLiveKind.CHANNELS, classify(addon("iptv", "Some IPTV", plain), plain)?.kind)
        val replayNamed = catalog("channel", "replay_tv", "Replay TV")
        assertEquals(SportsLiveKind.CHANNELS, classify(addon("iptv", "Some IPTV", replayNamed), replayNamed)?.kind)
    }

    @Test
    fun `addon named Sports Replay claims movie catalogs as REPLAY`() {
        val catalog = catalog("movie", "football", "Football")
        val claim = classify(addon("sports.replay", "Sports Replay | Powered By X", catalog), catalog)
        assertEquals(SportsLiveKind.REPLAY, claim?.kind)
        assertEquals("addon:sports", claim?.reason)
    }

    @Test
    fun `addon description is never matched`() {
        val catalog = catalog("movie", "popular", "Popular")
        val addon = addon("torrentio", "Torrentio", catalog, description = "movies, sports, highlights")
        assertNull(classify(addon, catalog))
    }

    @Test
    fun `movie catalog named NFL Games is claimed by catalog rule`() {
        val catalog = catalog("movie", "nfl_games", "NFL Games")
        val claim = classify(addon("generic", "Generic Addon", catalog), catalog)
        assertEquals(SportsLiveKind.EVENTS, claim?.kind)
        assertEquals("catalog:nfl", claim?.reason)
    }

    @Test
    fun `cinemeta popular movies is not claimed`() {
        val catalog = catalog("movie", "top", "Popular")
        assertNull(classify(addon("com.linvo.cinemeta", "Cinemeta", catalog), catalog))
    }

    @Test
    fun `exclude override beats type rule`() {
        val catalog = catalog("sport", "sports_live", "Live Now")
        val addon = addon("highfly", "Sports Streams", catalog)
        val key = SportsLiveCatalogPolicy.keyFor(addon, catalog)
        assertNull(SportsLiveCatalogPolicy.classify(addon, catalog, mapOf(key to SportsLiveOverride.EXCLUDE)))
    }

    @Test
    fun `include override claims a plain movie catalog as EVENTS`() {
        val catalog = catalog("movie", "top", "Popular")
        val addon = addon("com.linvo.cinemeta", "Cinemeta", catalog)
        val key = SportsLiveCatalogPolicy.keyFor(addon, catalog)
        val claim = SportsLiveCatalogPolicy.classify(addon, catalog, mapOf(key to SportsLiveOverride.INCLUDE))
        assertEquals(SportsLiveKind.EVENTS, claim?.kind)
        assertEquals("override:include", claim?.reason)
    }

    @Test
    fun `search-only catalog is never claimed even with INCLUDE override`() {
        val catalog = catalog("sport", "sports_search", "Search")
            .copy(extra = listOf(CatalogExtra(name = "search", isRequired = true)))
        val addon = addon("highfly", "Sports Streams", catalog)
        val key = SportsLiveCatalogPolicy.keyFor(addon, catalog)
        assertNull(classify(addon, catalog))
        assertNull(SportsLiveCatalogPolicy.classify(addon, catalog, mapOf(key to SportsLiveOverride.INCLUDE)))
        val legacy = catalog("sport", "sports_search", "Search").copy(extraRequired = listOf("search"))
        assertNull(classify(addon("highfly", "Sports Streams", legacy), legacy))
    }

    @Test
    fun `catalog with an optional search extra is still claimed`() {
        val catalog = catalog("sport", "sports_live", "Live Now").copy(
            extra = listOf(CatalogExtra(name = "search"), CatalogExtra(name = "skip")),
            extraSupported = listOf("search", "skip")
        )
        val claim = classify(addon("highfly", "Sports Streams", catalog), catalog)
        assertEquals(SportsLiveKind.EVENTS, claim?.kind)
    }

    @Test
    fun `replay keyword in catalog id wins over events`() {
        val catalog = catalog("sport", "sports_recap", "Sports")
        val claim = classify(addon("highfly", "Sports Streams", catalog), catalog)
        assertEquals(SportsLiveKind.REPLAY, claim?.kind)
    }

    @Test
    fun `keyword match is word bounded`() {
        val catalog = catalog("movie", "transport_docs", "Transportation Docs")
        assertNull(classify(addon("docs", "Docs Addon", catalog), catalog))
    }

    @Test
    fun `tv catalog named Live TV is CHANNELS and Live Events stays EVENTS`() {
        val liveTv = catalog("tv", "cnc_EasyPlayLiveTV_tv", "EasyPlay Live TV (tv)")
        val events = catalog("tv", "cnc_SportzXLiveEvents_tv", "⚡SportzX Live Events (tv)")
        val addon = addon("com.cncverse.stremiobridge", "CNCVerse Bridge", liveTv, events)

        assertEquals(SportsLiveKind.CHANNELS, classify(addon, liveTv)?.kind)
        assertEquals(SportsLiveKind.EVENTS, classify(addon, events)?.kind)
        val liveTvEvents = catalog("tv", "x", "Live TV Events")
        assertEquals(SportsLiveKind.EVENTS, classify(addon("x", "X", liveTvEvents), liveTvEvents)?.kind)
        val idOnly = catalog("tv", "live tv", "Box")
        assertEquals(SportsLiveKind.CHANNELS, classify(addon("y", "Y", idOnly), idOnly)?.kind)
    }

    @Test
    fun `keyFor uses the legacy catalog row key`() {
        val catalog = catalog("sport", "sports_live", "Live Now")
        val addon = addon("highfly", "Sports Streams", catalog)
        assertEquals("highfly_sport_sports_live", SportsLiveCatalogPolicy.keyFor(addon, catalog))
    }

    private fun classify(addon: Addon, catalog: CatalogDescriptor) =
        SportsLiveCatalogPolicy.classify(addon, catalog, emptyMap())

    private fun addon(
        id: String,
        name: String,
        vararg catalogs: CatalogDescriptor,
        description: String? = null,
        baseUrl: String = "https://$id.example.com"
    ) = Addon(
        id = id,
        name = name,
        version = "1.0.0",
        description = description,
        logo = null,
        baseUrl = baseUrl,
        catalogs = catalogs.toList(),
        types = catalogs.map { it.type }.distinct(),
        resources = emptyList()
    )

    private fun catalog(type: String, id: String, name: String) = CatalogDescriptor(
        type = ContentType.fromString(type),
        rawType = type,
        id = id,
        name = name
    )
}
