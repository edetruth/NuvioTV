package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.sportslive.SportsLiveItemBucketer
import com.nuvio.tv.core.sportslive.SportsLiveListCache
import com.nuvio.tv.core.sportslive.SportsLiveListSnapshot
import com.nuvio.tv.core.sportslive.SportsLiveSharedBuckets
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import com.nuvio.tv.core.sportslive.SportsLiveCalmOpen
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

    private fun TestScope.newViewModel(
        cache: SportsLiveListCache,
        buckets: IntArray = IntArray(1),
        shared: SportsLiveSharedBuckets? = null
    ): SportsLiveViewModel {
        val viewModel = SportsLiveViewModel(
            repo,
            refreshClock = { testScheduler.currentTime },
            listCache = cache,
            sharedBuckets = shared,
            computeDispatcher = compute
        )
        viewModel.nowMs = { NOW + testScheduler.currentTime }
        viewModel.bucket = { items, now, order ->
            buckets[0]++
            SportsLiveItemBucketer.bucket(items, now, order)
        }
        backgroundScope.launch { viewModel.uiState.collect {} }
        return viewModel
    }

    @Test
    fun `Live now waits for the first load up to the hold, then shows the last list, and later rows wait for N new`() = runTest {
        val savedAt = NOW - 20 * MINUTE
        // On the device: no addon URL (it comes from the claimed catalog).
        val saved = SportsLiveListSnapshot(savedAt, mapOf(SportsLiveTab.LIVE_NOW to listOf(item("a", isLive = true).copy(addonBaseUrl = ""))))
        loadGate = CompletableDeferred()
        val viewModel = newViewModel(MemoryCache(saved))
        runCurrent()

        // J1: the loading rows, not the saved list, while this visit's load runs.
        val holding = viewModel.uiState.value
        assertTrue(holding.visibleItems.isEmpty())
        assertTrue(holding.firstLoadPending)

        // The hold is over: the saved list at once, marked with its time.
        advanceTimeBy(SportsLiveCalmOpen.HOLD_MS + 1)
        runCurrent()
        val shown = viewModel.uiState.value
        assertFalse(shown.isLoading)
        assertEquals(listOf("a"), shown.visibleItems.map { it.id })
        assertEquals(CLAIMED.addon.baseUrl, shown.visibleItems.single().addonBaseUrl)
        assertEquals(savedAt, shown.staleListAtMs)

        // The fresh list refreshes the shown row in place; its new row waits behind "N new", and the mark goes.
        loadGate!!.complete(load(listOf(item("b", isLive = true), item("a", isLive = true))))
        runCurrent()
        val fresh = viewModel.uiState.value
        assertEquals(listOf("a"), fresh.visibleItems.map { it.id })
        assertEquals(1, fresh.newLiveRows)
        assertNull(fresh.staleListAtMs)

        // "N new": the whole list, in its order.
        viewModel.showNewRows()
        runCurrent()
        assertEquals(listOf("a", "b"), viewModel.uiState.value.visibleItems.map { it.id })
    }

    @Test
    fun `a first load that lands within the hold shows at once, whole`() = runTest {
        val saved = SportsLiveListSnapshot(NOW - 20 * MINUTE, mapOf(SportsLiveTab.LIVE_NOW to listOf(item("old", isLive = true).copy(addonBaseUrl = ""))))
        loadGate = CompletableDeferred()
        val viewModel = newViewModel(MemoryCache(saved))
        runCurrent()
        assertTrue(viewModel.uiState.value.visibleItems.isEmpty())
        advanceTimeBy(1_000L)
        loadGate!!.complete(load(listOf(item("a", isLive = true), item("b", isLive = true))))
        runCurrent()
        // Never the old list first: the new one, all of it.
        assertEquals(listOf("a", "b"), viewModel.uiState.value.visibleItems.map { it.id })
        assertEquals(0, viewModel.uiState.value.newLiveRows)
    }

    @Test
    fun `back on Live now from another tab its rows keep their place and new ones wait for N new`() = runTest {
        // J1 + #143 (l23-rc): the tab's kept board shows at once on return, so the fresh build must not reshuffle it.
        loadGate = CompletableDeferred()
        val viewModel = newViewModel(MemoryCache())
        runCurrent()
        loadGate!!.complete(load(listOf(item("a", isLive = true))))
        runCurrent()
        assertEquals(listOf("a"), viewModel.uiState.value.visibleItems.map { it.id })
        loadGate = null

        viewModel.selectTab(SportsLiveTab.UPCOMING)
        runCurrent()
        latestFlow.value = load(listOf(item("b", isLive = true), item("a", isLive = true)))
        runCurrent()
        viewModel.selectTab(SportsLiveTab.LIVE_NOW)
        runCurrent()

        val back = viewModel.uiState.value
        assertEquals(listOf("a"), back.visibleItems.map { it.id })
        assertEquals(1, back.newLiveRows)
        viewModel.showNewRows()
        runCurrent()
        assertEquals(setOf("a", "b"), viewModel.uiState.value.visibleItems.map { it.id }.toSet())
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

    @Test
    fun `the section uses the sorting Home's row and the player's panel already made of the load`() = runTest {
        val load = load(listOf(item("a", isLive = true), item("b", isLive = false, startMs = NOW + HOUR)))
        latestFlow.value = load
        val shared = SportsLiveSharedBuckets()
        val keys = setOf(CLAIMED.key)
        val order = listOf(CLAIMED.addon.id)
        // Home's row asked first.
        val homeLive = shared.tab(load, keys, order, SportsLiveTab.LIVE_NOW, NOW, stamp = 0L)
        val buckets = IntArray(1)
        val viewModel = newViewModel(SportsLiveListCache.NONE, buckets, shared)
        runCurrent()
        assertEquals(0, buckets[0])
        assertEquals(listOf("a"), viewModel.uiState.value.tabs.getValue(SportsLiveTab.LIVE_NOW).map { it.id })
        assertEquals(listOf("b"), viewModel.uiState.value.tabs.getValue(SportsLiveTab.UPCOMING).map { it.id })
        // The player's panel gets the same one, not a new merge.
        assertSame(homeLive, shared.tab(load, keys, order, SportsLiveTab.LIVE_NOW, NOW, stamp = 0L))
    }

    @Test
    fun `a focus move builds no new state unless it changes the rows Live now keeps`() = runTest {
        latestFlow.value = load(listOf(item("a", isLive = true), item("b", isLive = true), item("c", isLive = true)))
        val viewModel = newViewModel(SportsLiveListCache.NONE)
        runCurrent()
        val builds = viewModel.stateBuilds
        viewModel.selectItem("b")
        runCurrent()
        assertEquals("b", viewModel.uiState.value.selectedItemId)
        viewModel.selectItem("c")
        runCurrent()
        assertEquals("c", viewModel.uiState.value.selectedItemId)
        viewModel.selectItem("gone")
        runCurrent()
        assertEquals("a", viewModel.uiState.value.selectedItemId)
        assertEquals(builds, viewModel.stateBuilds)

        // A load that drops the focused card keeps it while focused; moving off it builds again and it leaves.
        viewModel.selectItem("b")
        runCurrent()
        latestFlow.value = load(listOf(item("a", isLive = true), item("c", isLive = true)))
        runCurrent()
        assertEquals(listOf("a", "b", "c"), viewModel.uiState.value.visibleItems.map { it.id })
        val afterLoad = viewModel.stateBuilds
        viewModel.selectItem("c")
        runCurrent()
        assertEquals(listOf("a", "c"), viewModel.uiState.value.visibleItems.map { it.id })
        assertEquals(afterLoad + 1, viewModel.stateBuilds)
    }

    @Test
    fun `a focus move alone gives the screen no new state, a tab change does`() = runTest {
        latestFlow.value = load(listOf(item("a", isLive = true), item("b", isLive = true), item("c", isLive = false, startMs = NOW + HOUR)))
        val viewModel = newViewModel(SportsLiveListCache.NONE)
        val drawn = mutableListOf<SportsLiveUiState>()
        backgroundScope.launch { viewModel.screenState.collect { drawn += it } }
        runCurrent()
        val shown = viewModel.screenState.value
        val emissions = drawn.size
        viewModel.selectItem("b")
        runCurrent()
        assertEquals("b", viewModel.uiState.value.selectedItemId)
        assertSame(shown, viewModel.screenState.value)
        assertEquals(emissions, drawn.size)

        viewModel.selectTab(SportsLiveTab.UPCOMING)
        runCurrent()
        assertEquals(SportsLiveTab.UPCOMING, viewModel.screenState.value.selectedTab)
        assertTrue(drawn.size > emissions)
    }

    @Test
    fun `a board built again keeps the rows that did not change, so only changed rows draw again`() {
        val a = item("a", isLive = true)
        val b = item("b", isLive = true)
        fun row(item: SportsLiveItem, followed: Boolean = false) = ScoreboardRow(
            item = item, group = ScoreboardGroup.LIVE, chip = null, art = null,
            firstFollowed = followed, secondFollowed = false, probablyEnded = false
        )
        fun board(filter: String?, vararg rows: ScoreboardRow) =
            SportsLiveBoard(SportsLiveTab.LIVE_NOW, filter, rows.map { it.item }, rows.toList(), emptyMap())
        val before = board(null, row(a), row(b))

        // One row changed (a follow): the other keeps its instance.
        val changed = board(null, row(a), row(b, followed = true)).reusingRows(before)
        assertSame(before.rows!![0], changed.rows!![0])
        assertTrue(changed.rows!![1].firstFollowed)
        // Nothing changed: the same list.
        assertSame(before.rows, board(null, row(a), row(b)).reusingRows(before).rows)
        // Another filter (or tab) reuses nothing.
        val other = board("Basketball", row(a))
        assertSame(other, other.reusingRows(before))
        assertSame(other, other.reusingRows(null))
    }

    @Test
    fun `each view keeps its last board, reused as up to date only for the inputs it was built from`() {
        val cache = SportsLiveBoardCache(maxViews = 2)
        fun inputs(tab: SportsLiveTab, clockMs: Long = NOW, filter: String? = null) = SportsLiveBoardInputs(
            tab = tab, filter = filter, items = listOf(item("a", isLive = false, startMs = NOW + HOUR)),
            scores = SportsLiveBoardScores(), ended = emptyMap(), excitement = emptyMap(), followed = emptyList(),
            clockMs = clockMs, logoRevision = 0
        )
        fun board(tab: SportsLiveTab) = SportsLiveBoard(tab, null, emptyList(), emptyList(), emptyMap())
        val upcoming = board(SportsLiveTab.UPCOMING)
        cache.put(inputs(SportsLiveTab.UPCOMING), upcoming)
        cache.put(inputs(SportsLiveTab.LIVE_NOW), board(SportsLiveTab.LIVE_NOW))

        // Back on Upcoming with nothing changed: nothing to build.
        assertSame(upcoming, cache.reusable(inputs(SportsLiveTab.UPCOMING)))
        // A minute on: shown at once, built again.
        assertNull(cache.reusable(inputs(SportsLiveTab.UPCOMING, clockMs = NOW + MINUTE)))
        assertSame(upcoming, cache.shownFor(inputs(SportsLiveTab.UPCOMING, clockMs = NOW + MINUTE)))
        // Another filter is its own view.
        assertNull(cache.shownFor(inputs(SportsLiveTab.UPCOMING, filter = "soccer")))
        // Bounded: the view used least recently goes.
        cache.put(inputs(SportsLiveTab.CHANNELS), board(SportsLiveTab.CHANNELS))
        assertTrue(cache.has(SportsLiveTab.UPCOMING, null))
        assertFalse(cache.has(SportsLiveTab.LIVE_NOW, null))
    }

    @Test
    fun `another profile or setting drops every kept board, and one built before never comes back`() {
        val cache = SportsLiveBoardCache()
        val inputs = SportsLiveBoardInputs(
            tab = SportsLiveTab.UPCOMING, filter = null, items = listOf(item("a", isLive = false, startMs = NOW + HOUR)),
            scores = SportsLiveBoardScores(), ended = emptyMap(), excitement = emptyMap(), followed = emptyList(),
            clockMs = NOW, logoRevision = 0
        )
        val board = SportsLiveBoard(SportsLiveTab.UPCOMING, null, emptyList(), emptyList(), emptyMap())
        cache.scopeTo("profile A")
        cache.put(inputs, board)
        cache.scopeTo("profile A")
        assertSame(board, cache.shownFor(inputs))

        val before = cache.generation
        cache.scopeTo("profile B")
        assertNull(cache.shownFor(inputs))
        // A board built ahead for profile A lands late: dropped.
        cache.putIfCurrent(before, inputs, board)
        assertNull(cache.shownFor(inputs))
        cache.putIfCurrent(cache.generation, inputs, board)
        assertSame(board, cache.shownFor(inputs))
    }

    @Test
    fun `back on the section with a list over 3 minutes old, it refreshes at once`() = runTest {
        latestFlow.value = load(listOf(item("a", isLive = true)))
        val viewModel = newViewModel(SportsLiveListCache.NONE)
        runCurrent()
        // Upcoming refreshes every 10 minutes on its own.
        viewModel.selectTab(SportsLiveTab.UPCOMING)
        viewModel.onScreenVisible(true)
        runCurrent()
        val opened = loads

        // Away (the player, another screen) for 2 minutes: nothing.
        viewModel.onScreenVisible(false)
        advanceTimeBy(2 * MINUTE)
        viewModel.onScreenVisible(true)
        runCurrent()
        assertEquals(opened, loads)

        // Away for 2 more: the list is 4 minutes old, so it refreshes on return, not at 10.
        viewModel.onScreenVisible(false)
        advanceTimeBy(2 * MINUTE)
        viewModel.onScreenVisible(true)
        runCurrent()
        assertEquals(opened + 1, loads)
        // A visible section refreshes forever; leave it so the test can end.
        viewModel.onScreenVisible(false)
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
        const val HOUR = 60 * MINUTE

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
