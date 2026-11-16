package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.network.NetworkResult
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
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.core.debrid.DirectDebridResolver
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveZapResolverTest {

    private val streams = FakeStreamRepository()
    private val resolveCache = SportsLiveResolveCache()
    private val probe = mockk<SportsLiveStreamProbe>(relaxed = true)
    private var settings = PlayerSettings()
    private var preparationCheckThrows = false
    private val debrid = mockk<DirectDebridResolver>().also {
        coEvery { it.shouldResolveToPlayableStream(any()) } answers {
            if (preparationCheckThrows && firstArg<Stream>() == PREPARE_ALT) error("debrid check failed")
            firstArg<Stream>() in NEEDS_PREPARATION
        }
    }

    private fun TestScope.newResolver(probe: SportsLiveStreamProbe = this@SportsLiveZapResolverTest.probe): SportsLiveZapResolver {
        // Each fresh resolver starts with an empty cache so tests that re-resolve an item re-fetch it.
        resolveCache.clear()
        val dataStore = mockk<PlayerSettingsDataStore>()
        every { dataStore.playerSettings } answers { flowOf(settings) }
        val addons = mockk<AddonRepository>()
        every { addons.getInstalledAddons() } returns flowOf(listOf(addon("Sports"), addon("Off", enabled = false)))
        return SportsLiveZapResolver(streams, dataStore, addons, probe, debrid, resolveCache).also { it.fetchScope = backgroundScope }
    }

    @Test
    fun `picks the stream the user's autoplay settings select`() = runTest {
        settings = PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.REGEX_MATCH, streamAutoPlayRegex = "1080")
        streams.response = { result(SD, HD) }

        assertEquals(HD, newResolver().resolve(ITEM))
        assertEquals(listOf(Triple("sport", "game", false)), streams.calls)
        verify { probe.recordResult(ITEM, any()) }
    }

    @Test
    fun `manual mode plays the first playable stream`() = runTest {
        settings = PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.MANUAL)
        streams.response = { result(WEB, SD, HD) }

        assertEquals(SD, newResolver().resolve(ITEM))
    }

    @Test
    fun `a regex with no match falls back to the first playable stream`() = runTest {
        settings = PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.REGEX_MATCH, streamAutoPlayRegex = "4K")
        streams.response = { result(WEB, SD, HD) }

        assertEquals(SD, newResolver().resolve(ITEM))
    }

    @Test
    fun `only external links resolves to nothing and is reported to the probe`() = runTest {
        val final = success(WEB)
        streams.response = { flowOf(NetworkResult.Loading, final) }

        assertNull(newResolver().resolve(ITEM))
        verify { probe.recordResult(ITEM, final) }
    }

    @Test
    fun `a search that never finishes times out to nothing and reports nothing`() = runTest {
        streams.response = { flow { emit(NetworkResult.Loading); awaitCancellation() } }

        assertNull(newResolver().resolve(ITEM))
        assertEquals(SportsLiveZapResolver.TIMEOUT_MS, testScheduler.currentTime)
        verify(exactly = 0) { probe.recordResult(any(), any()) }
    }

    @Test
    fun `a search that ends while still loading resolves to nothing`() = runTest {
        streams.response = { flowOf(NetworkResult.Loading) }

        assertNull(newResolver().resolve(ITEM))
    }

    @Test
    fun `a partial result is not picked before the search finishes`() = runTest {
        settings = PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.REGEX_MATCH, streamAutoPlayRegex = "1080")
        streams.response = {
            flow {
                emit(success(SD))
                delay(2_000)
                emit(success(SD, HD))
            }
        }

        assertEquals(HD, newResolver().resolve(ITEM))
        assertEquals(2_000L, testScheduler.currentTime)
    }

    @Test
    fun `a selection already found plays once the early pick delay passes`() = runTest {
        settings = PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.FIRST_STREAM)
        streams.response = {
            flow {
                emit(success(SD))
                awaitCancellation()
            }
        }

        assertEquals(SD, newResolver().resolve(ITEM))
        assertEquals(SportsLiveZapResolver.EARLY_PICK_MS, testScheduler.currentTime)
    }

    @Test
    fun `resolveAll lists the primary's streams first, then each alternate's, fetched in parallel`() = runTest {
        streams.responses["game"] = { flow { delay(2_000); emit(success(SD)) } }
        streams.responses["alt1"] = { flowOf(success(HD, addon = "Other")) }
        streams.responses["alt2"] = { flow { delay(1_000); emit(success(OTHER_HD, JUNK, addon = "Third")) } }

        val all = newResolver().resolveAll(MERGED)

        assertEquals(listOf(SD, HD, OTHER_HD), all)
        assertEquals(2_000L, testScheduler.currentTime)
        verify { probe.recordResult(MERGED, any()) }
        verify { probe.recordResult(ALT1, any()) }
        verify { probe.recordResult(ALT2, any()) }
    }

    @Test
    fun `resolveAll keeps a timed out source's partial streams but does not report it`() = runTest {
        streams.responses["game"] = { flow { emit(success(SD)); awaitCancellation() } }
        streams.responses["alt1"] = { flowOf(success(HD, addon = "Other")) }
        streams.responses["alt2"] = { flowOf(success(addon = "Third")) }

        assertEquals(listOf(SD, HD), newResolver().resolveAll(MERGED))
        verify(exactly = 0) { probe.recordResult(MERGED, any()) }
        verify { probe.recordResult(ALT1, any()) }
        verify { probe.recordResult(ALT2, any()) }
    }

    @Test
    fun `a merged game plays the primary's best stream when it has one`() = runTest {
        settings = PlayerSettings(streamAutoPlayMode = StreamAutoPlayMode.REGEX_MATCH, streamAutoPlayRegex = "1080")
        streams.responses["game"] = { flowOf(success(SD)) }
        streams.responses["alt1"] = { flowOf(success(HD, addon = "Other")) }
        streams.responses["alt2"] = { flowOf(success(addon = "Third")) }

        assertEquals(SD, newResolver().resolve(MERGED))
    }

    @Test
    fun `a merged game falls back to the first alternate with a playable stream`() = runTest {
        streams.responses["game"] = { flowOf(success(WEB, JUNK)) }
        streams.responses["alt1"] = { flowOf(success(addon = "Other")) }
        streams.responses["alt2"] = { flowOf(success(OTHER_HD, addon = "Third")) }

        assertEquals(OTHER_HD, newResolver().resolve(MERGED))
        verify { probe.recordResult(MERGED, any()) }
        verify { probe.recordResult(ALT1, any()) }
        verify { probe.recordResult(ALT2, any()) }
    }

    @Test
    fun `a merged game with only empty or junk sources resolves to nothing`() = runTest {
        streams.responses["game"] = { flowOf(success(JUNK)) }
        streams.responses["alt1"] = { flowOf(NetworkResult.Error("none", NetworkResult.STREAMS_NOT_FOUND_CODE)) }
        streams.responses["alt2"] = { flow { throw IllegalStateException("boom") } }
        streams.responses["alt3"] = { flowOf(NetworkResult.Error("server", 500)) }
        val realProbe = SportsLiveStreamProbe(streams, resolveCache).apply { dispatcher = StandardTestDispatcher(testScheduler) }
        val merged = MERGED.copy(alternates = MERGED.alternates + ALT3)

        val resolver = newResolver(realProbe)
        assertNull(resolver.resolve(merged))
        assertEquals(emptyList<Stream>(), resolver.resolveAll(merged))
        // "Not found" is proof of an end; a throwing source records nothing and a server error is
        // inconclusive. (The primary's raw answer still holds a URL the probe counts as alive.)
        assertEquals(setOf(ALT1.streamKey), realProbe.ended.value)
    }

    @Test
    fun `a throwing source records nothing`() = runTest {
        streams.responses["game"] = { flowOf(success(SD)) }
        streams.responses["alt1"] = { flow { throw IllegalStateException("boom") } }
        streams.responses["alt2"] = { flowOf(success(HD, addon = "Third")) }

        newResolver().resolveAll(MERGED)

        verify(exactly = 0) { probe.recordResult(ALT1, any()) }
        verify { probe.recordResult(ALT2, any()) }
    }

    @Test
    fun `the primary's stream plays without waiting for the alternates, which are still recorded`() = runTest {
        streams.responses["game"] = { flowOf(success(SD)) }
        streams.responses["alt1"] = { flow { delay(5_000); emit(success(HD, addon = "Other")) } }
        streams.responses["alt2"] = { flow { delay(8_000); emit(success(OTHER_HD, addon = "Third")) } }

        assertEquals(SD, newResolver().resolve(MERGED))
        assertEquals(0L, testScheduler.currentTime)

        advanceTimeBy(8_001)
        verify { probe.recordResult(ALT1, any()) }
        verify { probe.recordResult(ALT2, any()) }
    }

    @Test
    fun `an alternate's stream that needs the Stream screen is skipped, a primary's is not`() = runTest {
        streams.responses["game"] = { flowOf(success(WEB)) }
        streams.responses["alt1"] = { flowOf(success(PREPARE_ALT, addon = "Other")) }
        streams.responses["alt2"] = { flowOf(success(OTHER_HD, addon = "Third")) }
        assertEquals(OTHER_HD, newResolver().resolve(MERGED))
        assertEquals(listOf(WEB, OTHER_HD), newResolver().resolveAll(MERGED))

        streams.responses["game"] = { flowOf(success(PREPARE_PRIMARY)) }
        assertEquals(PREPARE_PRIMARY, newResolver().resolve(MERGED))
    }

    @Test
    fun `resolveAlternates lists only the alternates' streams and keeps those that need preparation`() = runTest {
        streams.responses["game"] = { flowOf(success(WEB)) }
        streams.responses["alt1"] = { flowOf(success(PREPARE_ALT, addon = "Other")) }
        streams.responses["alt2"] = { flowOf(success(OTHER_HD, addon = "Third")) }

        val lookup = newResolver().resolveAlternates(MERGED)
        assertEquals(listOf(PREPARE_ALT, OTHER_HD), lookup.streams)
        assertTrue(lookup.complete)
        assertEquals(emptyList<Stream>(), newResolver().resolveAlternates(MERGED.copy(alternates = emptyList())).streams)
    }

    @Test
    fun `resolveAlternates is incomplete when a source times out`() = runTest {
        streams.responses["alt1"] = { flow { awaitCancellation() } }
        streams.responses["alt2"] = { flowOf(success(OTHER_HD, addon = "Third")) }

        val lookup = newResolver().resolveAlternates(MERGED)

        assertEquals(listOf(OTHER_HD), lookup.streams)
        assertFalse(lookup.complete)
    }

    @Test
    fun `a failing preparation check on one alternate does not lose the others`() = runTest {
        preparationCheckThrows = true
        streams.responses["game"] = { flowOf(success(WEB)) }
        streams.responses["alt1"] = { flowOf(success(PREPARE_ALT, addon = "Other")) }
        streams.responses["alt2"] = { flowOf(success(OTHER_HD, addon = "Third")) }

        assertEquals(OTHER_HD, newResolver().resolve(MERGED))
    }

    @Test
    fun `resolving another merged game cancels the previous game's running fetches`() = runTest {
        var cancelled = 0
        streams.responses["game"] = { flowOf(success(SD)) }
        streams.responses["game2"] = { flowOf(success(HD)) }
        streams.responses["alt1"] = { flow { try { awaitCancellation() } finally { cancelled++ } } }
        streams.responses["alt2"] = { flowOf(success(OTHER_HD, addon = "Third")) }
        val resolver = newResolver()

        assertEquals(SD, resolver.resolve(MERGED))
        runCurrent()
        // The same game again keeps its fetches.
        assertEquals(SD, resolver.resolve(MERGED))
        runCurrent()
        assertEquals(0, cancelled)

        assertEquals(HD, resolver.resolve(MERGED.copy(meta = MERGED.meta.copy(id = "game2"))))
        runCurrent()
        // Only the last fetch of the previous game was still running; the new game's alt1 runs on.
        assertEquals(1, cancelled)
    }

    @Test
    fun `a cached resolve is reused within the TTL and re-fetches after it expires`() = runTest {
        var now = 0L
        resolveCache.nowMs = { now }
        streams.response = { result(SD) }
        val resolver = newResolver()

        assertEquals(SD, resolver.resolve(ITEM))
        assertEquals(SD, resolver.resolve(ITEM))
        assertEquals(1, streams.calls.size) // second resolve served from cache

        now += SportsLiveResolveCache.TTL_MS
        assertEquals(SD, resolver.resolve(ITEM))
        assertEquals(2, streams.calls.size) // entry expired, re-fetched
    }

    @Test
    fun `forceRefresh ignores the cache and refreshes the entry`() = runTest {
        streams.response = { result(SD) }
        val resolver = newResolver()

        assertEquals(SD, resolver.resolve(ITEM))
        assertEquals(SD, resolver.resolve(ITEM, forceRefresh = true))
        assertEquals(2, streams.calls.size)
    }

    @Test
    fun `invalidate drops the cached resolve so the next resolve re-fetches`() = runTest {
        streams.response = { result(SD) }
        val resolver = newResolver()

        resolver.resolve(ITEM)
        resolver.invalidate(ITEM)
        resolver.resolve(ITEM)
        assertEquals(2, streams.calls.size)
    }

    @Test
    fun `an errored key invalidated by content id is re-resolved next time`() = runTest {
        streams.response = { result(SD) }
        val resolver = newResolver()

        resolver.resolve(ITEM)
        // The full player, which knows only the content id, evicts the stream that just failed.
        resolver.invalidateByContentId(ITEM.id)
        resolver.resolve(ITEM)
        assertEquals(2, streams.calls.size)
    }

    private class FakeStreamRepository : StreamRepository {
        var response: () -> Flow<NetworkResult<List<AddonStreams>>> = { flowOf() }

        /** Per video id; ids not listed get [response]. */
        val responses = mutableMapOf<String, () -> Flow<NetworkResult<List<AddonStreams>>>>()
        val calls = mutableListOf<Triple<String, String, Boolean>>()

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
            calls += Triple(type, videoId, forceRefresh)
            emitAll((responses[videoId] ?: response)())
        }

        override fun setLocalPluginSearchPaused(paused: Boolean) = Unit

        override suspend fun getCachedStreamsFromAllAddons(
            type: String,
            videoId: String,
            season: Int?,
            episode: Int?
        ): List<AddonStreams>? = null

        override suspend fun getStreamsFromAddon(addon: Addon, type: String, videoId: String) =
            NetworkResult.Success(emptyList<Stream>())
    }

    private companion object {
        val SD = stream(url = "https://cdn.example/sd.m3u8", name = "Sports 720p")
        val HD = stream(url = "https://cdn.example/hd.m3u8", name = "Sports 1080p")
        val WEB = stream(externalUrl = "https://site.example/watch", name = "Watch on web")
        val OTHER_HD = stream(url = "https://cdn3.example/hd.m3u8", name = "Third 1080p", addonName = "Third")
        val JUNK = stream(url = "https://site.example/", name = "Support us")
        val PREPARE_ALT = stream(url = "https://cdn2.example/debrid", name = "Other debrid", addonName = "Other")
        val PREPARE_PRIMARY = stream(url = "https://cdn.example/debrid", name = "Sports debrid")
        val NEEDS_PREPARATION = setOf(PREPARE_ALT, PREPARE_PRIMARY)

        fun stream(url: String? = null, externalUrl: String? = null, name: String, addonName: String = "Sports") = Stream(
            name = name,
            title = null,
            description = null,
            url = url,
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = externalUrl,
            behaviorHints = null,
            addonName = addonName,
            addonLogo = null
        )

        fun success(vararg streams: Stream, addon: String = "Sports"): NetworkResult<List<AddonStreams>> =
            NetworkResult.Success(listOf(AddonStreams(addonName = addon, addonLogo = null, streams = streams.toList())))

        fun result(vararg streams: Stream): Flow<NetworkResult<List<AddonStreams>>> =
            flowOf(NetworkResult.Loading, success(*streams))

        fun addon(name: String, enabled: Boolean = true) = Addon(
            id = name,
            name = name,
            version = "1",
            description = null,
            logo = null,
            baseUrl = "https://$name.example",
            catalogs = emptyList(),
            types = emptyList(),
            resources = emptyList(),
            enabled = enabled
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
            addonBaseUrl = "https://sports.example",
            catalogKey = "sports_sport_live",
            catalogName = "Live",
            kind = SportsLiveKind.EVENTS,
            isLive = true,
            isChannel = false,
            startMs = null,
            sport = null
        )

        val ALT1 = ITEM.copy(meta = ITEM.meta.copy(id = "alt1"), addonId = "other", addonName = "Other")
        val ALT2 = ITEM.copy(meta = ITEM.meta.copy(id = "alt2"), addonId = "third", addonName = "Third")
        val ALT3 = ITEM.copy(meta = ITEM.meta.copy(id = "alt3"), addonId = "fourth", addonName = "Fourth")
        val MERGED = ITEM.copy(alternates = listOf(ALT1, ALT2))
    }
}
