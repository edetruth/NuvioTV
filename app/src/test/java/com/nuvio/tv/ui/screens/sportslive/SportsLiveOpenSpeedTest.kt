package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.sportslive.SportsLiveItemBucketer
import com.nuvio.tv.core.sportslive.SportsLiveListCache
import com.nuvio.tv.core.sportslive.SportsLiveListSnapshot
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ClaimedCatalog
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.repository.SportsLiveLoad
import com.nuvio.tv.domain.repository.SportsLiveRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * live.20 B1 and C1: the section opens on the profile's last list while the catalogs load, reuses the list it built
 * when the user comes back, and moves a game to Live now at tip-off without a refresh.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveOpenSpeedTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val compute get() = mainDispatcherRule.dispatcher

    private val claimedFlow = MutableStateFlow(listOf(CLAIMED))
    private val latestFlow = MutableStateFlow<SportsLiveLoad?>(null)
    private var loadGate: CompletableDeferred<SportsLiveLoad>? = null
    private var loads = 0

    private val repo = mockk<SportsLiveRepository> {
        every { claimedCatalogs } returns claimedFlow
        every { latest } returns latestFlow
        every { endedKeys } returns MutableStateFlow(emptySet())
        every { followedTeams } returns MutableStateFlow<List<FollowedTeam>>(emptyList())
        every { reprobeIfStale(any()) } just runs
        every { hasRecentLinks(any()) } returns false
        every { clearEnded() } just runs
        coEvery { load(any(), any(), any()) } coAnswers {
            loads++
            // No gate: the cached load (under a minute old) comes back as it is.
            val result = loadGate?.await() ?: latestFlow.value!!
            latestFlow.value = result
            result
        }
    }

    /** A cache in memory, as the store keeps it for the process. */
    private class MemoryCache(var stored: SportsLiveListSnapshot? = null) : SportsLiveListCache {
        override fun peek(): SportsLiveListSnapshot? = stored
        override suspend fun read(): SportsLiveListSnapshot? = stored
        override fun save(snapshot: SportsLiveListSnapshot) {
            stored = snapshot
        }
    }

    private fun TestScope.newViewModel(cache: SportsLiveListCache, buckets: IntArray = IntArray(1)): SportsLiveViewModel {
        val viewModel = SportsLiveViewModel(repo, listCache = cache, computeDispatcher = compute)
        viewModel.nowMs = { NOW + testScheduler.currentTime }
        viewModel.bucket = { items, now, order ->
            buckets[0]++
            SportsLiveItemBucketer.bucket(items, now, order)
        }
        backgroundScope.launch { viewModel.uiState.collect {} }
        return viewModel
    }

    @Test
    fun `the last list shows at once, marked with its time, while the first load runs`() = runTest {
        val savedAt = NOW - 20 * MINUTE
        // On the device: no addon URL (it comes from the claimed catalog).
        val saved = SportsLiveListSnapshot(savedAt, mapOf(SportsLiveTab.LIVE_NOW to listOf(item("a", isLive = true).copy(addonBaseUrl = ""))))
        loadGate = CompletableDeferred()
        val viewModel = newViewModel(MemoryCache(saved))
        runCurrent()

        val shown = viewModel.uiState.value
        assertFalse(shown.isLoading)
        assertEquals(listOf("a"), shown.visibleItems.map { it.id })
        assertEquals(CLAIMED.addon.baseUrl, shown.visibleItems.single().addonBaseUrl)
        assertEquals(savedAt, shown.staleListAtMs)

        // The fresh list replaces it and the mark goes.
        loadGate!!.complete(load(listOf(item("a", isLive = true), item("b", isLive = true))))
        runCurrent()
        val fresh = viewModel.uiState.value
        assertEquals(listOf("a", "b"), fresh.visibleItems.map { it.id })
        assertNull(fresh.staleListAtMs)
    }

    @Test
    fun `coming back to the section reuses the list built from the same load`() = runTest {
        latestFlow.value = load(listOf(item("a", isLive = true)))
        val cache = MemoryCache()
        val firstBuckets = IntArray(1)
        newViewModel(cache, firstBuckets)
        runCurrent()
        assertEquals(1, firstBuckets[0])

        val againBuckets = IntArray(1)
        val again = newViewModel(cache, againBuckets)
        runCurrent()
        assertEquals(0, againBuckets[0])
        assertEquals(listOf("a"), again.uiState.value.visibleItems.map { it.id })
        assertFalse(again.uiState.value.isLoading)
    }

    @Test
    fun `a game moves from Upcoming to Live now at tip-off without a refresh`() = runTest {
        val tipOff = NOW + 10 * MINUTE
        latestFlow.value = load(listOf(item("g", isLive = false, startMs = tipOff, liveByClock = true)))
        val viewModel = newViewModel(SportsLiveListCache.NONE)
        runCurrent()
        assertEquals(listOf("g"), viewModel.uiState.value.tabs.getValue(SportsLiveTab.UPCOMING).map { it.id })
        assertTrue(viewModel.uiState.value.tabs.getValue(SportsLiveTab.LIVE_NOW).isEmpty())
        val loadsBefore = loads

        advanceTimeBy(10 * MINUTE + 2_000L)
        runCurrent()
        val state = viewModel.uiState.value
        assertEquals(listOf("g"), state.tabs.getValue(SportsLiveTab.LIVE_NOW).map { it.id })
        assertTrue(state.tabs.getValue(SportsLiveTab.LIVE_NOW).single().isLive)
        assertTrue(state.tabs.getValue(SportsLiveTab.UPCOMING).isEmpty())
        assertEquals(loadsBefore, loads)
    }

    private fun load(items: List<SportsLiveItem>) =
        SportsLiveLoad(items = items, failures = emptyList(), loadedAtMs = NOW, claimedKeys = setOf(CLAIMED.key))

    private fun item(id: String, isLive: Boolean, startMs: Long? = null, liveByClock: Boolean = false) = SportsLiveItem(
        meta = MetaPreview(
            id = id,
            type = ContentType.UNKNOWN,
            rawType = "tv",
            name = "Team $id vs Other $id",
            poster = null,
            posterShape = PosterShape.LANDSCAPE,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList()
        ),
        addonId = CLAIMED.addon.id,
        addonName = CLAIMED.addon.name,
        addonBaseUrl = CLAIMED.addon.baseUrl,
        catalogKey = CLAIMED.key,
        catalogName = CLAIMED.catalog.name,
        kind = SportsLiveKind.EVENTS,
        isLive = isLive,
        isChannel = false,
        startMs = startMs,
        sport = "Basketball",
        startHasTimeOfDay = startMs != null,
        teams = SportsLiveTeams.parse("Team $id vs Other $id"),
        liveByClock = liveByClock
    )

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val MINUTE = 60_000L

        val CLAIMED: ClaimedCatalog = run {
            val catalog = CatalogDescriptor(type = ContentType.fromString("tv"), rawType = "tv", id = "events", name = "Events")
            val addon = Addon(
                id = "source.a",
                name = "Source A",
                version = "1.0.0",
                description = null,
                logo = null,
                baseUrl = "https://a.example.invalid",
                catalogs = listOf(catalog),
                types = listOf(catalog.type),
                resources = emptyList()
            )
            ClaimedCatalog(addon, catalog, "source.a_tv_events", SportsLiveKind.EVENTS)
        }
    }
}
