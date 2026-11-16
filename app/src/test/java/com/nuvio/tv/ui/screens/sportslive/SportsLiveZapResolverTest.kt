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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveZapResolverTest {

    private val streams = FakeStreamRepository()
    private val probe = mockk<SportsLiveStreamProbe>(relaxed = true)
    private var settings = PlayerSettings()

    private fun newResolver(): SportsLiveZapResolver {
        val dataStore = mockk<PlayerSettingsDataStore>()
        every { dataStore.playerSettings } answers { flowOf(settings) }
        val addons = mockk<AddonRepository>()
        every { addons.getInstalledAddons() } returns flowOf(listOf(addon("Sports"), addon("Off", enabled = false)))
        return SportsLiveZapResolver(streams, dataStore, addons, probe)
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

        val resolver = newResolver()
        assertNull(resolver.resolve(MERGED))
        assertEquals(emptyList<Stream>(), resolver.resolveAll(MERGED))
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
        val MERGED = ITEM.copy(alternates = listOf(ALT1, ALT2))
    }
}
