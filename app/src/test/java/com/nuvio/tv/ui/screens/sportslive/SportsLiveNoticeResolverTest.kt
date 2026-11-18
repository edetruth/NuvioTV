package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.sportslive.SportsLiveSourceCooldown
import com.nuvio.tv.core.sportslive.SportsLiveSourceReliability
import com.nuvio.tv.core.sportslive.SportsLiveWorkingLinks
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.repository.SportsLiveStreamProbe
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.ui.screens.sportslive.preview.SportsLivePreviewLookup
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** QA C1: informational entries, rate-limit cool-downs and the hover preview's resolve budget. */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveNoticeResolverTest {

    private val streams = FakeStreamRepository()
    private val resolveCache = SportsLiveResolveCache()
    private var now = 1_000_000L
    private val cooldown = SportsLiveSourceCooldown(nowMs = { now })
    private val links = SportsLiveWorkingLinks(nowMs = { now })
    private var settings = PlayerSettings()

    private fun TestScope.newResolver(): SportsLiveZapResolver {
        val dataStore = mockk<PlayerSettingsDataStore>()
        every { dataStore.playerSettings } answers { flowOf(settings) }
        val addons = mockk<AddonRepository>()
        every { addons.getInstalledAddons() } returns flowOf(listOf(addon("Sports"), addon("Other")))
        val debrid = mockk<DirectDebridResolver>()
        coEvery { debrid.shouldResolveToPlayableStream(any()) } returns false
        return SportsLiveZapResolver(streams, dataStore, addons, mockk<SportsLiveStreamProbe>(relaxed = true), debrid, resolveCache).also {
            it.fetchScope = backgroundScope
            it.workingLinks = links
            it.cooldown = cooldown
            it.nowMs = { now }
        }
    }

    @After
    fun tearDown() {
        SportsLiveSourceReliability.reset()
        SportsLiveExtraSources.reset()
        SportsLiveZapSessions.clear()
    }

    @Test
    fun `a notice listed first is never auto-picked`() = runTest {
        streams.response = { flowOf(NetworkResult.Loading, success(RATE_LIMIT, SD)) }

        assertEquals(SD, newResolver().resolve(ITEM))
    }

    @Test
    fun `a notice is never auto-picked in manual mode or by a matching regex`() = runTest {
        streams.response = { flowOf(success(UPGRADE, SD)) }
        settings = PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.MANUAL)
        assertEquals(SD, newResolver().resolve(ITEM))

        resolveCache.clear()
        settings = PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.REGEX_MATCH, streamAutoPlayRegex = "Upgrade|720")
        assertEquals(SD, newResolver().resolve(ITEM))
    }

    @Test
    fun `only notices is nothing found, not a stream to play`() = runTest {
        streams.response = { flowOf(success(RATE_LIMIT, UPGRADE)) }

        assertEquals(SportsLiveLookup.NothingFound, newResolver().lookup(ITEM))
    }

    @Test
    fun `a merged game skips a source's notice for another source's stream`() = runTest {
        streams.responses["game"] = { flowOf(success(RATE_LIMIT)) }
        streams.responses["alt"] = { flowOf(success(HD, addon = "Other")) }

        assertEquals(HD, newResolver().resolve(MERGED))
        assertEquals(listOf(HD), newResolver().also { resolveCache.clear() }.resolveAll(MERGED))
    }

    @Test
    fun `a rate-limit notice starts a cool-down for its source`() = runTest {
        streams.response = { flowOf(success(RATE_LIMIT)) }

        newResolver().lookup(ITEM)

        assertTrue(cooldown.isCoolingDown("Sports"))
        assertEquals(7, cooldown.remainingMinutes("Sports"))
    }

    @Test
    fun `during a cool-down the source is not asked again, until it expires`() = runTest {
        cooldown.note("Sports", 7)
        streams.response = { flowOf(success(SD)) }
        val resolver = newResolver()

        assertEquals(SportsLiveLookup.NothingFound, resolver.lookup(ITEM))
        assertTrue(streams.calls.isEmpty())

        now += 7 * 60_000L
        assertEquals(SportsLiveLookup.Found(SD), resolver.lookup(ITEM))
        assertEquals(1, streams.calls.size)
    }

    @Test
    fun `during a cool-down a cached resolve or a known-good link still plays`() = runTest {
        val resolver = newResolver()
        resolveCache.put(ITEM.streamKey, SD)
        cooldown.note("Sports", 7)
        assertEquals(SD, resolver.resolve(ITEM))

        resolveCache.clear()
        links.noteLaunch(ITEM.id, HD)
        links.markGood(ITEM.id, HD.url)
        assertEquals(HD, resolver.resolve(ITEM))
        assertTrue(streams.calls.isEmpty())
    }

    @Test
    fun `a merged game skips a cooling source and asks the others`() = runTest {
        cooldown.note("Sports", 7)
        streams.responses["game"] = { flowOf(success(SD)) }
        streams.responses["alt"] = { flowOf(success(HD, addon = "Other")) }

        assertEquals(HD, newResolver().resolve(MERGED))
        assertEquals(listOf("alt"), streams.calls)
    }

    @Test
    fun `previews are suspended while the source cools down`() = runTest {
        cooldown.note("Sports", 7)
        streams.response = { flowOf(success(SD)) }

        assertEquals(SportsLivePreviewLookup.NotPreviewable, newResolver().previewLookup(ITEM))
        assertTrue(streams.calls.isEmpty())
    }

    @Test
    fun `a preview resolves a game at most once per window, a cached resolve costs nothing`() = runTest {
        streams.response = { flowOf(success(SD)) }
        val resolver = newResolver()

        assertEquals(SportsLivePreviewLookup.Found(SD), resolver.previewLookup(ITEM))
        // Cached: served again with no request.
        assertEquals(SportsLivePreviewLookup.Found(SD), resolver.previewLookup(ITEM))
        assertEquals(1, streams.calls.size)

        // The cache expired (or the link failed): no new resolve within the window.
        resolveCache.clear()
        now += SportsLiveZapResolver.PREVIEW_RESOLVE_WINDOW_MS - 1
        assertEquals(SportsLivePreviewLookup.NotPreviewable, resolver.previewLookup(ITEM))
        assertEquals(1, streams.calls.size)

        now += 1
        assertEquals(SportsLivePreviewLookup.Found(SD), resolver.previewLookup(ITEM))
        assertEquals(2, streams.calls.size)
    }

    @Test
    fun `a zap to a game with only a notice keeps the current stream`() = runTest {
        streams.responses["b"] = { flowOf(success(RATE_LIMIT)) }
        val games = listOf(ITEM.copy(meta = ITEM.meta.copy(id = "a")), ITEM.copy(meta = ITEM.meta.copy(id = "b")))
        SportsLiveZapSessions.start(SportsLiveZapSession.of(games, games[0].streamKey, SportsLiveTab.LIVE_NOW, null, false)!!)
        val resolver = newResolver()
        val navigated = mutableListOf<String>()
        val noStream = mutableListOf<String>()
        val controller = PlayerZapController(
            scope = backgroundScope,
            resolve = resolver::resolve,
            onNavigate = { item, _ -> navigated += item.id },
            onNoStream = { item -> noStream += item.id },
            settleMs = 0L
        )

        assertTrue(controller.zap("a", forward = true))
        runCurrent()

        // No navigation: the player keeps the working stream, and says the game has none right now.
        assertTrue(navigated.isEmpty())
        assertEquals(listOf("b"), noStream)
        assertEquals("a", SportsLiveZapSessions.current()?.current?.id)
    }

    @Test
    fun `the sources panel lists a notice as a notice row for sports only`() {
        SportsLiveExtraSources.markSportsPlayback("game")

        assertTrue(sportsLiveSourceNotice("game", RATE_LIMIT))
        assertEquals(false, sportsLiveSourceNotice("game", SD))
        assertEquals(false, sportsLiveSourceNotice("movie", RATE_LIMIT))
    }

    private class FakeStreamRepository : StreamRepository {
        var response: () -> Flow<NetworkResult<List<AddonStreams>>> = { flowOf() }
        val responses = mutableMapOf<String, () -> Flow<NetworkResult<List<AddonStreams>>>>()
        val calls = mutableListOf<String>()

        override fun getStreamsFromAllAddons(
            type: String,
            videoId: String,
            season: Int?,
            episode: Int?,
            forceRefresh: Boolean,
            priorityBingeGroup: String?,
            preferredPluginOnly: Boolean,
            continuePluginOnly: Boolean
        ): Flow<NetworkResult<List<AddonStreams>>> = flow {
            calls += videoId
            emitAll((responses[videoId] ?: response)())
        }

        override fun setLocalPluginSearchPaused(paused: Boolean) = Unit

        override suspend fun getCachedStreamsFromAllAddons(type: String, videoId: String, season: Int?, episode: Int?): List<AddonStreams>? = null

        override suspend fun getStreamsFromAddon(addon: Addon, type: String, videoId: String) =
            NetworkResult.Success(emptyList<Stream>())
    }

    private companion object {
        val SD = stream(url = "https://cdn.example.invalid/sd.m3u8", title = "Sports 720p")
        val HD = stream(url = "https://cdn2.example.invalid/hd.m3u8", title = "Other 1080p", addonName = "Other")
        val RATE_LIMIT = stream(
            url = "https://cdn.example.invalid/notice.mp4",
            title = "Rate limit reached",
            description = "Please wait 7 min. The stream you are already watching keeps working."
        )
        val UPGRADE = stream(url = "https://cdn.example.invalid/upgrade.mp4", title = "Upgrade for more links")

        fun stream(url: String?, title: String, description: String? = null, addonName: String = "Sports") = Stream(
            name = addonName,
            title = title,
            description = description,
            url = url,
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = null,
            addonName = addonName,
            addonLogo = null
        )

        fun success(vararg streams: Stream, addon: String = "Sports"): NetworkResult<List<AddonStreams>> =
            NetworkResult.Success(listOf(AddonStreams(addonName = addon, addonLogo = null, streams = streams.toList())))

        fun addon(name: String) = Addon(
            id = name,
            name = name,
            version = "1",
            description = null,
            logo = null,
            baseUrl = "https://$name.example.invalid",
            catalogs = emptyList(),
            types = emptyList(),
            resources = emptyList(),
            enabled = true
        )

        val ITEM = SportsLiveItem(
            meta = MetaPreview(
                id = "game",
                type = ContentType.UNKNOWN,
                rawType = "sport",
                name = "A vs B",
                poster = null,
                posterShape = PosterShape.LANDSCAPE,
                background = null,
                logo = null,
                description = null,
                releaseInfo = "LIVE",
                imdbRating = null,
                genres = emptyList()
            ),
            addonId = "sports",
            addonName = "Sports",
            addonBaseUrl = "https://sports.example.invalid",
            catalogKey = "sports_sport_live",
            catalogName = "Live",
            kind = SportsLiveKind.EVENTS,
            isLive = true,
            isChannel = false,
            startMs = null,
            sport = null
        )

        val ALT = ITEM.copy(meta = ITEM.meta.copy(id = "alt"), addonId = "other", addonName = "Other")
        val MERGED = ITEM.copy(alternates = listOf(ALT))
    }
}
