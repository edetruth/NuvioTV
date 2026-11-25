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
import com.nuvio.tv.domain.model.StreamDebridCacheState
import com.nuvio.tv.domain.model.StreamDebridCacheStatus
import com.nuvio.tv.domain.repository.StreamRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    private val resolveCache = com.nuvio.tv.ui.screens.sportslive.SportsLiveResolveCache()

    private fun newProbe() = SportsLiveStreamProbe(streams, resolveCache).also {
        it.nowMs = { mainDispatcherRule.dispatcher.scheduler.currentTime }
        it.dispatcher = mainDispatcherRule.dispatcher
    }

    @Test
    fun `focusing an item whose addons have no streams marks it ended`() = runTest {
        streams.responses["gone"] = {
            flowOf(NetworkResult.Loading, NetworkResult.Error("none", NetworkResult.STREAMS_NOT_FOUND_CODE))
        }
        streams.responses["web"] = { result(stream(externalUrl = "https://site.example/watch")) }
        streams.responses["ok"] = { result(PLAYABLE) }
        val probe = newProbe()

        listOf("gone", "web", "ok").forEach { probe.reprobeIfStale(item(it)) }
        advanceTimeBy(1)

        assertEquals(setOf("sports|gone", "sports|web"), probe.ended.value)
        assertEquals(listOf("sport"), streams.calls.map { it.first }.distinct())
    }

    @Test
    fun `a fresh cached resolve skips the on-focus probe`() = runTest {
        streams.responses["ok"] = { result(PLAYABLE) }
        resolveCache.put(item("ok").streamKey, PLAYABLE)
        val probe = newProbe()

        probe.reprobeIfStale(item("ok"))
        advanceTimeBy(1)

        assertTrue(streams.calls.isEmpty())
    }

    @Test
    fun `a rate limit, an error or a timeout never marks an item ended`() = runTest {
        streams.responses["limited"] = { flowOf(NetworkResult.Loading, NetworkResult.Error("Too Many Requests", 429)) }
        streams.responses["notice"] = {
            result(stream(name = "Rate limit reached", title = "Too many stream requests in a short time."))
        }
        streams.responses["slow"] = { flow { emit(NetworkResult.Loading); awaitCancellation() } }
        val probe = newProbe()

        listOf("limited", "notice", "slow").forEach { probe.reprobeIfStale(item(it)) }
        advanceTimeBy(SportsLiveStreamProbe.PROBE_TIMEOUT_MS + 1)

        assertEquals(3, streams.calls.size)
        assertEquals(emptySet<String>(), probe.ended.value)
    }

    @Test
    fun `a stream with an info hash keeps the item alive even when not yet playable`() = runTest {
        streams.responses["torrent"] = {
            result(stream(infoHash = "0123456789abcdef0123456789abcdef01234567").copy(
                debridCacheStatus = StreamDebridCacheStatus(
                    providerId = "rd",
                    providerName = "Real-Debrid",
                    state = StreamDebridCacheState.NOT_CACHED
                )
            ))
        }
        val probe = newProbe()

        probe.reprobeIfStale(item("torrent"))
        advanceTimeBy(1)

        assertEquals(1, streams.calls.size)
        assertEquals(emptySet<String>(), probe.ended.value)
    }

    @Test
    fun `focus re-probes only after two minutes and an ended verdict is re-checked rather than expired`() = runTest {
        streams.responses["x"] = { flowOf(NetworkResult.Success(emptyList())) }
        val probe = newProbe()
        probe.reprobeIfStale(item("x"))
        advanceTimeBy(1)
        assertEquals(setOf("sports|x"), probe.ended.value)

        advanceTimeBy(SportsLiveStreamProbe.REPROBE_AFTER_MS - 2)
        probe.reprobeIfStale(item("x"))
        runCurrent()
        assertEquals(1, streams.calls.size)

        advanceTimeBy(1)
        probe.reprobeIfStale(item("x"))
        runCurrent()
        assertEquals(2, streams.calls.size)
        // The first probe shares the stream cache; the re-probe bypasses it.
        assertEquals(listOf(false, true), streams.forceRefreshes)
        assertEquals(setOf("sports|x"), probe.ended.value)

        // Three minutes after the latest verdict it is re-checked (no cache), and stays hidden
        // while still empty.
        advanceTimeBy(SportsLiveStreamProbe.ENDED_TTL_MS - 1)
        assertEquals(2, streams.calls.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, streams.calls.size)
        assertEquals(true, streams.forceRefreshes.last())
        assertEquals(setOf("sports|x"), probe.ended.value)
    }

    @Test
    fun `after its background re-checks run out an ended verdict lapses without a request`() = runTest {
        streams.responses["x"] = { flowOf(NetworkResult.Success(emptyList())) }
        val probe = newProbe()
        probe.reprobeIfStale(item("x"))
        runCurrent()

        // Two re-checks, three minutes apart, both still empty.
        advanceTimeBy(SportsLiveStreamProbe.MAX_ENDED_RECHECKS * SportsLiveStreamProbe.ENDED_TTL_MS)
        runCurrent()
        val lastVerdictAt = testScheduler.currentTime
        assertEquals(1 + SportsLiveStreamProbe.MAX_ENDED_RECHECKS, streams.calls.size)
        assertEquals(setOf("sports|x"), probe.ended.value)

        advanceTimeBy(SportsLiveStreamProbe.ENDED_FINAL_TTL_MS - 1)
        runCurrent()
        assertEquals(setOf("sports|x"), probe.ended.value)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(emptySet<String>(), probe.ended.value)
        assertEquals("no request for the lapse", 1 + SportsLiveStreamProbe.MAX_ENDED_RECHECKS, streams.calls.size)
        assertTrue(testScheduler.currentTime >= lastVerdictAt + SportsLiveStreamProbe.ENDED_FINAL_TTL_MS)

        // The next focus re-checks it without the stream cache.
        probe.reprobeIfStale(item("x"))
        runCurrent()
        assertEquals(true, streams.forceRefreshes.last())
    }

    @Test
    fun `a manual refresh clears every ended verdict and pending re-checks do nothing`() = runTest {
        streams.responses["x"] = { flowOf(NetworkResult.Success(emptyList())) }
        val probe = newProbe()
        probe.reprobeIfStale(item("x"))
        runCurrent()
        assertEquals(setOf("sports|x"), probe.ended.value)

        probe.clearEnded()
        assertEquals(emptySet<String>(), probe.ended.value)

        advanceTimeBy(SportsLiveStreamProbe.ENDED_TTL_MS + 1)
        runCurrent()
        assertEquals(1, streams.calls.size)
    }

    @Test
    fun `links found lately count as recent for fifteen minutes`() = runTest {
        streams.responses["ok"] = { result(PLAYABLE) }
        streams.responses["gone"] = { flowOf(NetworkResult.Success(emptyList())) }
        val probe = newProbe()
        probe.reprobeIfStale(item("ok"))
        probe.reprobeIfStale(item("gone"))
        runCurrent()

        assertTrue(probe.hasRecentLinks(item("ok")))
        assertEquals(false, probe.hasRecentLinks(item("gone")))
        assertEquals(false, probe.hasRecentLinks(item("never")))

        advanceTimeBy(SportsLiveStreamProbe.RECENT_LINKS_MS)
        assertEquals(false, probe.hasRecentLinks(item("ok")))
    }

    @Test
    fun `background re-checks of an ended game are bounded`() {
        assertEquals(true, SportsLiveStreamProbe.shouldRecheckEnded(0))
        assertEquals(true, SportsLiveStreamProbe.shouldRecheckEnded(SportsLiveStreamProbe.MAX_ENDED_RECHECKS - 1))
        assertEquals(false, SportsLiveStreamProbe.shouldRecheckEnded(SportsLiveStreamProbe.MAX_ENDED_RECHECKS))
    }

    @Test
    fun `an ended game comes back only when its re-check finds links`() = runTest {
        streams.responses["x"] = { flowOf(NetworkResult.Success(emptyList())) }
        val probe = newProbe()
        probe.reprobeIfStale(item("x"))
        runCurrent()
        assertEquals(setOf("sports|x"), probe.ended.value)

        // A quick tab round trip later the game is still gone: nothing expires on a timer.
        advanceTimeBy(SportsLiveStreamProbe.ENDED_TTL_MS - 1)
        assertEquals(setOf("sports|x"), probe.ended.value)

        // Links were posted late: the forced re-check finds them and the game shows again.
        streams.responses["x"] = { result(PLAYABLE) }
        advanceTimeBy(1)
        runCurrent()

        assertEquals(listOf(false, true), streams.forceRefreshes)
        assertEquals(emptySet<String>(), probe.ended.value)
    }

    @Test
    fun `a result reported by another stream fetch marks or clears an item`() = runTest {
        val probe = newProbe()

        probe.recordResult(item("x"), NetworkResult.Error("none", NetworkResult.STREAMS_NOT_FOUND_CODE))
        assertEquals(setOf("sports|x"), probe.ended.value)

        probe.recordResult(item("x"), NetworkResult.Error("HTTP 503", 503))
        assertEquals(setOf("sports|x"), probe.ended.value)

        probe.recordResult(item("x"), NetworkResult.Success(listOf(AddonStreams("Sports", null, listOf(PLAYABLE)))))
        assertEquals(emptySet<String>(), probe.ended.value)
        assertEquals(0, streams.calls.size)
    }

    @Test
    fun `a game that kicked off under 30 minutes ago is never marked ended`() = runTest {
        streams.responses["new"] = { flowOf(NetworkResult.Success(emptyList())) }
        streams.responses["old"] = { flowOf(NetworkResult.Success(emptyList())) }
        val probe = newProbe()
        advanceTimeBy(HOUR)

        probe.reprobeIfStale(item("new", startMs = HOUR - 10 * MINUTE))
        probe.reprobeIfStale(item("old", startMs = HOUR - 45 * MINUTE))
        runCurrent()

        assertEquals(setOf("sports|old"), probe.ended.value)
    }

    @Test
    fun `at most three probes run at once`() = runTest {
        val ids = (1..5).map { "g$it" }
        ids.forEach { id -> streams.responses[id] = { flow { delay(1_000); emitAll(result(PLAYABLE)) } } }
        val probe = newProbe()

        ids.forEach { probe.reprobeIfStale(item(it)) }
        advanceUntilIdle()

        assertEquals(3, streams.maxActive)
        assertEquals(5, streams.calls.size)
        assertEquals(2_000L, testScheduler.currentTime)
    }

    private class FakeStreamRepository : StreamRepository {
        val responses = mutableMapOf<String, () -> Flow<NetworkResult<List<AddonStreams>>>>()
        val calls = mutableListOf<Pair<String, String>>()
        val forceRefreshes = mutableListOf<Boolean>()
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
            continuePluginOnly: Boolean,
            contentId: String?
        ): Flow<NetworkResult<List<AddonStreams>>> = flow {
            calls += type to videoId
            forceRefreshes += forceRefresh
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

        fun stream(
            url: String? = null,
            externalUrl: String? = null,
            infoHash: String? = null,
            name: String = "Stream",
            title: String? = null
        ) = Stream(
            name = name,
            title = title,
            description = null,
            url = url,
            ytId = null,
            infoHash = infoHash,
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

        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE

        fun item(id: String, startMs: Long? = null) = SportsLiveItem(
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
            startMs = startMs,
            sport = null,
            startHasTimeOfDay = startMs != null
        )
    }
}
