package com.nuvio.tv.data.repository

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.repository.StreamRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveStreamProbeTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val streams = FakeStreamRepository()

    private fun newProbe() = SportsLiveStreamProbe(streams).also {
        it.nowMs = { mainDispatcherRule.dispatcher.scheduler.currentTime }
        it.dispatcher = mainDispatcherRule.dispatcher
    }

    @Test
    fun `a finished search with no playable stream marks the item ended`() = runTest {
        streams.responses["empty"] = { flowOf(NetworkResult.Loading, NetworkResult.Success(emptyList())) }
        streams.responses["external"] = { result(stream(externalUrl = "https://site.example/watch")) }
        streams.responses["junk"] = {
            flowOf(NetworkResult.Loading, NetworkResult.Error("none", NetworkResult.STREAMS_NOT_FOUND_CODE))
        }
        streams.responses["ok"] = { result(stream(externalUrl = "https://site.example/watch"), PLAYABLE) }
        val probe = newProbe()
        val items = listOf(item("empty"), item("external"), item("junk"), item("ok"))

        probe.probe(items)

        assertEquals(setOf("sports|empty", "sports|external", "sports|junk"), probe.ended.value)
        assertEquals(listOf("sport"), streams.calls.map { it.first }.distinct())
    }

    @Test
    fun `a timeout or a transport error does not mark the item ended`() = runTest {
        streams.responses["slow"] = { flow { emit(NetworkResult.Loading); awaitCancellation() } }
        streams.responses["down"] = { flowOf(NetworkResult.Loading, NetworkResult.Error("HTTP 500", 500)) }
        val probe = newProbe()
        val startedAt = testScheduler.currentTime

        probe.probe(listOf(item("slow"), item("down")))

        assertEquals(SportsLiveStreamProbe.PROBE_TIMEOUT_MS, testScheduler.currentTime - startedAt)
        assertEquals(emptySet<String>(), probe.ended.value)
    }

    @Test
    fun `at most three probes run at once`() = runTest {
        val ids = (1..7).map { "g$it" }
        ids.forEach { id -> streams.responses[id] = { flow { delay(1_000); emitAll(result(PLAYABLE)) } } }
        val probe = newProbe()
        val startedAt = testScheduler.currentTime

        probe.probe(ids.map(::item))

        assertEquals(3, streams.maxActive)
        assertEquals(7, streams.calls.size)
        assertEquals(3_000L, testScheduler.currentTime - startedAt)
    }

    @Test
    fun `results are cached for ten minutes and an ended item returns once streams come back`() = runTest {
        streams.responses["x"] = { flowOf(NetworkResult.Success(emptyList())) }
        val probe = newProbe()
        probe.probe(listOf(item("x")))
        assertEquals(setOf("sports|x"), probe.ended.value)

        advanceTimeBy(SportsLiveStreamProbe.CACHE_TTL_MS - 1)
        probe.probe(listOf(item("x")))
        assertEquals(1, streams.calls.size)

        streams.responses["x"] = { flow { delay(1_000); emitAll(result(PLAYABLE)) } }
        advanceTimeBy(1)
        val reprobe = async { probe.probe(listOf(item("x"))) }
        runCurrent()
        // Until the re-probe answers the item stays ended.
        assertEquals(setOf("sports|x"), probe.ended.value)
        reprobe.await()

        assertEquals(2, streams.calls.size)
        assertEquals(emptySet<String>(), probe.ended.value)
    }

    @Test
    fun `an expired ended entry of an item no longer listed is dropped`() = runTest {
        streams.responses["x"] = { flowOf(NetworkResult.Success(emptyList())) }
        val probe = newProbe()
        probe.probe(listOf(item("x")))
        assertEquals(setOf("sports|x"), probe.ended.value)

        advanceTimeBy(SportsLiveStreamProbe.CACHE_TTL_MS)
        probe.probe(emptyList())

        assertEquals(emptySet<String>(), probe.ended.value)
    }

    @Test
    fun `focus re-probes only an entry older than two minutes`() = runTest {
        val probe = newProbe()

        // No entry yet: probes at once.
        probe.reprobeIfStale(item("a"))
        advanceUntilIdle()
        assertEquals(1, streams.calls.size)

        advanceTimeBy(SportsLiveStreamProbe.REPROBE_AFTER_MS - 1)
        probe.reprobeIfStale(item("a"))
        advanceUntilIdle()
        assertEquals(1, streams.calls.size)

        advanceTimeBy(1)
        streams.responses["a"] = { flowOf(NetworkResult.Success(emptyList())) }
        probe.reprobeIfStale(item("a"))
        advanceUntilIdle()
        assertEquals(2, streams.calls.size)
        assertTrue("sports|a" in probe.ended.value)
    }

    private class FakeStreamRepository : StreamRepository {
        val responses = mutableMapOf<String, () -> Flow<NetworkResult<List<AddonStreams>>>>()
        val calls = mutableListOf<Pair<String, String>>()
        private var active = 0
        var maxActive = 0

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
            calls += type to videoId
            active++
            maxActive = maxOf(maxActive, active)
            try {
                emitAll(responses[videoId]?.invoke() ?: result(PLAYABLE))
            } finally {
                active--
            }
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
        val PLAYABLE = stream(url = "https://cdn.example/live.m3u8")

        fun stream(url: String? = null, externalUrl: String? = null) = Stream(
            name = "Stream",
            title = null,
            description = null,
            url = url,
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = externalUrl,
            behaviorHints = null,
            addonName = "Sports",
            addonLogo = null
        )

        fun result(vararg streams: Stream): Flow<NetworkResult<List<AddonStreams>>> = flowOf(
            NetworkResult.Loading,
            NetworkResult.Success(listOf(AddonStreams(addonName = "Sports", addonLogo = null, streams = streams.toList())))
        )

        fun item(id: String) = SportsLiveItem(
            meta = MetaPreview(
                id = id,
                type = ContentType.UNKNOWN,
                rawType = "sport",
                name = id,
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
    }
}
