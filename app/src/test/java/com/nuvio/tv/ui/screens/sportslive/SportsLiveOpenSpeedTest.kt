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
import kotlinx.coroutines.flow.MutableSharedFlow
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
import java.time.Instant
import java.time.ZoneOffset

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
    fun `on a cold open Live now is published before Upcoming is sorted`() = runTest {
        // live.24: Live now (a few dozen games) never waits for the thousands of Upcoming, Channels and Replays rows.
        val gate = CompletableDeferred<Unit>()
        latestFlow.value = load(listOf(item("live", isLive = true), item("next", isLive = false, startMs = NOW + HOUR)))
        val viewModel = SportsLiveViewModel(
            repo,
            refreshClock = { testScheduler.currentTime },
            sharedBuckets = SportsLiveSharedBuckets(),
            computeDispatcher = compute
        )
        viewModel.nowMs = { NOW }
        viewModel.afterLiveFirst = { gate.await() }
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        val first = viewModel.uiState.value
        assertEquals(listOf("live"), first.visibleItems.map { it.id })
        assertTrue(first.tabs[SportsLiveTab.UPCOMING].orEmpty().isEmpty())

        // Upcoming sorted: it follows, and Live now stays as shown.
        gate.complete(Unit)
        runCurrent()
        val done = viewModel.uiState.value
        assertEquals(listOf("next"), done.tabs.getValue(SportsLiveTab.UPCOMING).map { it.id })
        assertEquals(listOf("live"), done.visibleItems.map { it.id })
    }

    @Test
    fun `Upcoming lists the next three days until a later day is asked`() = runTest {
        // live.24: the working set. A day five days ahead waits behind its tab until the viewer goes to it.
        val zone = ZoneOffset.UTC
        val today = Instant.ofEpochMilli(NOW).atZone(zone).toLocalDate()
        fun at(day: Long) = today.plusDays(day).atTime(23, 30).toInstant(zone).toEpochMilli()
        latestFlow.value = load(listOf(item("d0", isLive = false, startMs = at(0)), item("d2", isLive = false, startMs = at(2)), item("d5", isLive = false, startMs = at(5))))
        val viewModel = SportsLiveViewModel(
            repo,
            refreshClock = { testScheduler.currentTime },
            sharedBuckets = SportsLiveSharedBuckets(),
            computeDispatcher = compute,
            upcomingWindowDays = 3,
            zone = zone
        )
        viewModel.nowMs = { NOW }
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        val near = viewModel.uiState.value
        assertEquals(listOf("d0", "d2"), near.tabs.getValue(SportsLiveTab.UPCOMING).map { it.id })
        assertEquals(listOf(today.plusDays(5)), near.upcomingPendingDates)

        viewModel.showUpcomingDay(today.plusDays(5))
        runCurrent()
        val all = viewModel.uiState.value
        assertEquals(listOf("d0", "d2", "d5"), all.tabs.getValue(SportsLiveTab.UPCOMING).map { it.id })
        assertTrue(all.upcomingPendingDates.isEmpty())
    }

    @Test
    fun `a directory signal the last sorting already read sorts nothing again, Upcoming included`() = runTest {
        // live.25 (#221): Upcoming was sorted 3 times in one open on the TV, once for a change already sorted with.
        latestFlow.value = load(listOf(item("live", isLive = true), item("next", isLive = false, startMs = NOW + HOUR)))
        val shared = SportsLiveSharedBuckets()
        val directoryChanges = MutableSharedFlow<Unit>()
        var stamp = 0L
        val viewModel = SportsLiveViewModel(
            repo,
            refreshClock = { testScheduler.currentTime },
            directoryChanges = directoryChanges,
            directoryStamp = { stamp },
            sharedBuckets = shared,
            computeDispatcher = compute,
            upcomingWindowDays = 3,
            zone = ZoneOffset.UTC
        )
        viewModel.nowMs = { NOW + testScheduler.currentTime }
        backgroundScope.launch { viewModel.uiState.collect {} }
        // Past the open's hold, which builds the state once more by itself.
        advanceTimeBy(SportsLiveCalmOpen.HOLD_MS + 1)
        runCurrent()
        assertEquals(listOf("next"), viewModel.uiState.value.tabs.getValue(SportsLiveTab.UPCOMING).map { it.id })
        val sorted = shared.upcomingSorted
        val builds = viewModel.stateBuilds
        assertTrue(sorted > 0)

        // Home's row asked with a clock a moment behind (it waited for the lock), then the signal of the change came.
        shared.tab(latestFlow.value!!, setOf(CLAIMED.key), listOf(CLAIMED.addon.id), SportsLiveTab.LIVE_NOW, NOW - 5_000L, stamp = 0L)
        directoryChanges.emit(Unit)
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(sorted, shared.upcomingSorted)
        assertEquals(builds, viewModel.stateBuilds)

        // A change it has not read sorts again (live.26: Upcoming at once while it is the tab shown).
        viewModel.selectTab(SportsLiveTab.UPCOMING)
        runCurrent()
        assertEquals(sorted, shared.upcomingSorted)
        stamp = 1L
        directoryChanges.emit(Unit)
        advanceTimeBy(3_000)
        runCurrent()
        assertTrue(shared.upcomingSorted > sorted)
        assertEquals(listOf("next"), viewModel.uiState.value.tabs.getValue(SportsLiveTab.UPCOMING).map { it.id })
    }

    @Test
    fun `a directory change while Live now shows keeps Upcoming's sorting until Upcoming is opened`() = runTest {
        // live.26: on the TV each directory change after the open sorted Upcoming again (3-7 s, 3 times per open) while
        // nobody was on it; Live now and the other tabs are still sorted again for it.
        latestFlow.value = load(listOf(item("live", isLive = true), item("next", isLive = false, startMs = NOW + HOUR)))
        val shared = SportsLiveSharedBuckets()
        val directoryChanges = MutableSharedFlow<Unit>()
        var stamp = 0L
        val viewModel = SportsLiveViewModel(
            repo,
            refreshClock = { testScheduler.currentTime },
            directoryChanges = directoryChanges,
            directoryStamp = { stamp },
            sharedBuckets = shared,
            computeDispatcher = compute,
            upcomingWindowDays = 3,
            zone = ZoneOffset.UTC
        )
        viewModel.nowMs = { NOW + testScheduler.currentTime }
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceTimeBy(SportsLiveCalmOpen.HOLD_MS + 1)
        runCurrent()
        val sorted = shared.upcomingSorted
        assertTrue(sorted > 0)
        val builds = viewModel.stateBuilds

        // Two changes on Live now: the load is sorted again (the state follows), Upcoming is not; its rows stay.
        repeat(2) { index ->
            stamp = index + 1L
            directoryChanges.emit(Unit)
            advanceTimeBy(3_000)
            runCurrent()
        }
        assertEquals(sorted, shared.upcomingSorted)
        assertTrue(viewModel.stateBuilds > builds)
        assertEquals(listOf("next"), viewModel.uiState.value.tabs.getValue(SportsLiveTab.UPCOMING).map { it.id })
        assertEquals(listOf("live"), viewModel.uiState.value.visibleItems.map { it.id })

        // Opened: sorted again at once, with the directory as it is now, and only once.
        viewModel.selectTab(SportsLiveTab.UPCOMING)
        runCurrent()
        val resorted = shared.upcomingSorted
        assertTrue(resorted > sorted)
        assertEquals(listOf("next"), viewModel.uiState.value.tabs.getValue(SportsLiveTab.UPCOMING).map { it.id })
        viewModel.selectTab(SportsLiveTab.LIVE_NOW)
        runCurrent()
        viewModel.selectTab(SportsLiveTab.UPCOMING)
        runCurrent()
        assertEquals(resorted, shared.upcomingSorted)
    }

    @Test
    fun `a new load is sorted in full, Upcoming included, while Live now shows`() = runTest {
        // live.26: only a directory change keeps Upcoming's sorting; new games are never left out of it.
        latestFlow.value = load(listOf(item("live", isLive = true), item("next", isLive = false, startMs = NOW + HOUR)))
        val shared = SportsLiveSharedBuckets()
        val directoryChanges = MutableSharedFlow<Unit>()
        var stamp = 0L
        val viewModel = SportsLiveViewModel(
            repo,
            refreshClock = { testScheduler.currentTime },
            directoryChanges = directoryChanges,
            directoryStamp = { stamp },
            sharedBuckets = shared,
            computeDispatcher = compute,
            upcomingWindowDays = 3,
            zone = ZoneOffset.UTC
        )
        viewModel.nowMs = { NOW + testScheduler.currentTime }
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceTimeBy(SportsLiveCalmOpen.HOLD_MS + 1)
        runCurrent()
        stamp = 1L
        directoryChanges.emit(Unit)
        advanceTimeBy(3_000)
        runCurrent()

        latestFlow.value = load(
            listOf(item("live", isLive = true), item("next", isLive = false, startMs = NOW + HOUR), item("later", isLive = false, startMs = NOW + 2 * HOUR))
        )
        runCurrent()
        assertEquals(setOf("next", "later"), viewModel.uiState.value.tabs.getValue(SportsLiveTab.UPCOMING).map { it.id }.toSet())
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
    fun `back from the player a held Live now board is dropped once too old, other tabs' boards are kept`() = runTest {
        // live.24 RC review H2: back from a 30-minute game, the board StateFlow re-sent the Live now board from before it.
        var live = true
        val sharing = keptBoardSharing(5_000L, SportsLiveBoardCache.LIVE_MAX_AGE_MS) { live }
        val watchers = MutableStateFlow(1)
        val commands = mutableListOf<kotlinx.coroutines.flow.SharingCommand>()
        val job = launch { sharing.command(watchers).collect { commands += it } }
        try {
            runCurrent()
            assertEquals(listOf(kotlinx.coroutines.flow.SharingCommand.START), commands)
            // The player opens: sharing stops after 5 s, the Live now board is still held until 90 s.
            watchers.value = 0
            advanceTimeBy(5_001L); runCurrent()
            assertEquals(kotlinx.coroutines.flow.SharingCommand.STOP, commands.last())
            advanceTimeBy(SportsLiveBoardCache.LIVE_MAX_AGE_MS - 5_000L); runCurrent()
            assertEquals(kotlinx.coroutines.flow.SharingCommand.STOP_AND_RESET_REPLAY_CACHE, commands.last())
            // Back: sharing starts again with nothing held.
            watchers.value = 1
            runCurrent()
            assertEquals(kotlinx.coroutines.flow.SharingCommand.START, commands.last())
            // Upcoming's board (no live score) is kept however long the screen is away.
            live = false
            watchers.value = 0
            advanceTimeBy(30 * MINUTE); runCurrent()
            assertEquals(kotlinx.coroutines.flow.SharingCommand.STOP, commands.last())
            // Back within 5 s: sharing never stopped.
            watchers.value = 1
            runCurrent()
            watchers.value = 0
            advanceTimeBy(1_000L); runCurrent()
            watchers.value = 1
            runCurrent()
            assertEquals(kotlinx.coroutines.flow.SharingCommand.START, commands.last())
            assertEquals(1, commands.drop(4).count { it == kotlinx.coroutines.flow.SharingCommand.START })
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `Live now's kept board shows at once only while it is fresh, other tabs' boards always`() {
        // live.23 review H2: back on Live now after 20 minutes, its old board showed scores, clocks and LIVE as they were.
        var clock = NOW
        val cache = SportsLiveBoardCache(now = { clock })
        fun inputs(tab: SportsLiveTab) = SportsLiveBoardInputs(
            tab = tab, filter = null, items = listOf(item("a", isLive = tab == SportsLiveTab.LIVE_NOW, startMs = NOW)),
            scores = SportsLiveBoardScores(), ended = emptyMap(), excitement = emptyMap(), followed = emptyList(),
            clockMs = NOW, logoRevision = 0
        )
        val live = SportsLiveBoard(SportsLiveTab.LIVE_NOW, null, emptyList(), emptyList(), emptyMap())
        val upcoming = SportsLiveBoard(SportsLiveTab.UPCOMING, null, emptyList(), emptyList(), emptyMap())
        cache.put(inputs(SportsLiveTab.LIVE_NOW), live)
        cache.put(inputs(SportsLiveTab.UPCOMING), upcoming)
        clock += SportsLiveBoardCache.LIVE_MAX_AGE_MS
        assertSame(live, cache.instantFor(inputs(SportsLiveTab.LIVE_NOW)))
        clock += 20 * MINUTE
        // Too old to show its scores: the tab waits for its fresh board (its rows are still reused by it).
        assertNull(cache.instantFor(inputs(SportsLiveTab.LIVE_NOW)))
        assertSame(live, cache.shownFor(inputs(SportsLiveTab.LIVE_NOW)))
        assertSame(upcoming, cache.instantFor(inputs(SportsLiveTab.UPCOMING)))
        // Built again: shown at once again.
        cache.put(inputs(SportsLiveTab.LIVE_NOW), live)
        assertSame(live, cache.instantFor(inputs(SportsLiveTab.LIVE_NOW)))
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
    fun `a Watch later save or Hide my teams' scores drops every kept board`() {
        // Review S2: a kept board built before a game was protected drew its score on the next tab switch.
        val cache = SportsLiveBoardCache()
        val inputs = SportsLiveBoardInputs(
            tab = SportsLiveTab.LIVE_NOW, filter = null, items = listOf(item("a", isLive = true)),
            scores = SportsLiveBoardScores(), ended = emptyMap(), excitement = emptyMap(), followed = emptyList(),
            clockMs = NOW, logoRevision = 0
        )
        val board = SportsLiveBoard(SportsLiveTab.LIVE_NOW, null, emptyList(), emptyList(), emptyMap())
        val none = com.nuvio.tv.core.sportslive.scores.SpoilerProtection(nowMs = NOW)
        cache.scopeTo(SportsLiveBoardCache.ProtectionScope.of(none))
        cache.put(inputs, board)
        // Only the clock moved: the same scope, the board stays.
        cache.scopeTo(SportsLiveBoardCache.ProtectionScope.of(none.copy(nowMs = NOW + MINUTE)))
        assertSame(board, cache.shownFor(inputs))
        // A game saved for later: dropped.
        val saved = none.copy(games = listOf(com.nuvio.tv.core.sportslive.scores.ProtectedGame("e1", "A", "B", NOW, savedAtMs = NOW)))
        cache.scopeTo(SportsLiveBoardCache.ProtectionScope.of(saved))
        assertNull(cache.shownFor(inputs))
        // Hide my teams' scores turned on: dropped.
        cache.put(inputs, board)
        cache.scopeTo(SportsLiveBoardCache.ProtectionScope.of(saved.copy(myTeams = true)))
        assertNull(cache.shownFor(inputs))
    }

    @Test
    fun `two profiles with the same catalogs and settings never share a kept board (F3)`() {
        val cache = SportsLiveBoardCache()
        val inputs = SportsLiveBoardInputs(
            tab = SportsLiveTab.UPCOMING, filter = null, items = listOf(item("a", isLive = false, startMs = NOW + HOUR)),
            scores = SportsLiveBoardScores(), ended = emptyMap(), excitement = emptyMap(), followed = emptyList(),
            clockMs = NOW, logoRevision = 0
        )
        val board = SportsLiveBoard(SportsLiveTab.UPCOMING, null, emptyList(), emptyList(), emptyMap())
        fun scope(profile: Int) = SportsLiveBoardScope(
            profile, setOf("addon:tv:live"), com.nuvio.tv.core.sportslive.scores.MySports.DEFAULT, emptySet(), emptyList(),
            com.nuvio.tv.core.sportslive.scores.ScoresMode.SHOW
        )
        cache.scopeTo(scope(1))
        cache.put(inputs, board)
        cache.scopeTo(scope(1))
        assertSame(board, cache.shownFor(inputs))
        cache.scopeTo(scope(2))
        assertNull(cache.shownFor(inputs))
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
