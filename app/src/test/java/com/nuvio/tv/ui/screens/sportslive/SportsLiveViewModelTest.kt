package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.sportslive.EndedInputs
import com.nuvio.tv.core.sportslive.EndedSignal
import com.nuvio.tv.core.sportslive.LinkCheckRequest
import com.nuvio.tv.core.sportslive.ManifestResponse
import com.nuvio.tv.core.sportslive.SportsLiveLinkChecker
import com.nuvio.tv.core.sportslive.SportsLiveSourceCooldown
import com.nuvio.tv.core.sportslive.SportsLiveSourceTrust
import com.nuvio.tv.core.sportslive.SportsLiveEndedGames
import com.nuvio.tv.core.sportslive.SportsLiveItemBucketer
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.WhyKind
import com.nuvio.tv.core.sportslive.scores.WhyTag
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
import com.nuvio.tv.domain.repository.SportsLiveFailure
import com.nuvio.tv.domain.repository.SportsLiveLoad
import com.nuvio.tv.domain.repository.SportsLiveRepository
import com.nuvio.tv.ui.screens.sportslive.dual.dualStream
import com.nuvio.tv.ui.screens.sportslive.preview.SportsLivePreviewLookup
import com.nuvio.tv.ui.screens.sportslive.preview.SportsLivePreviewState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // The ViewModel buckets on its compute dispatcher; the test dispatcher keeps that deterministic.
    private val compute get() = mainDispatcherRule.dispatcher

    private val claimedFlow = MutableStateFlow(listOf(CLAIMED))
    private val latestFlow = MutableStateFlow<SportsLiveLoad?>(null)
    private val endedFlow = MutableStateFlow<Set<String>>(emptySet())
    private val followedFlow = MutableStateFlow<List<FollowedTeam>>(emptyList())
    private var nextLoad = load(emptyList())
    private val forcedFlags = mutableListOf<Boolean>()
    private val recentlyAlive = mutableSetOf<String>()
    private var clearedEnded = 0
    private val includeChannelsFlags = mutableListOf<Boolean>()
    private val kindsAsked = mutableListOf<Set<SportsLiveKind>?>()

    private val repo = mockk<SportsLiveRepository> {
        every { claimedCatalogs } returns claimedFlow
        every { latest } returns latestFlow
        every { endedKeys } returns endedFlow
        every { followedTeams } returns followedFlow
        every { reprobeIfStale(any()) } just runs
        every { hasRecentLinks(any()) } answers { firstArg<SportsLiveItem>().id in recentlyAlive }
        every { clearEnded() } answers { clearedEnded++; Unit }
        coEvery { follow(any()) } coAnswers { followedFlow.value = followedFlow.value + firstArg<FollowedTeam>() }
        coEvery { unfollow(any()) } coAnswers { followedFlow.value = followedFlow.value - firstArg<FollowedTeam>() }
        coEvery { load(any(), any(), any()) } coAnswers {
            forcedFlags += firstArg<Boolean>()
            includeChannelsFlags += secondArg<Boolean>()
            kindsAsked += thirdArg<Set<SportsLiveKind>?>()
            latestFlow.value = nextLoad
            nextLoad
        }
    }

    /** Collects [SportsLiveViewModel.uiState] in the background, as the screen would, so WhileSubscribed stays active. */
    private fun TestScope.newViewModel(
        scores: Map<String, ScoreSnapshot> = emptyMap(),
        canEstimate: (SportsLiveItem) -> Boolean = { true }
    ): SportsLiveViewModel {
        val viewModel = SportsLiveViewModel(repo, endedInputs = flowOf(EndedInputs(scores, canEstimate)), endedGames = SportsLiveEndedGames(), computeDispatcher = compute)
        viewModel.nowMs = { NOW }
        backgroundScope.launch { viewModel.uiState.collect {} }
        return viewModel
    }

    @Test
    fun `initial load populates tabs and picks LIVE_NOW with the first item selected`() = runTest {
        nextLoad = load(
            listOf(
                item("b", isLive = true, sport = "Soccer", name = "B"),
                item("a", isLive = true, sport = "Basketball", name = "A"),
                item("u", isLive = false, sport = "Tennis", startMs = NOW + HOUR)
            )
        )
        val viewModel = newViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.hasClaimedCatalogs)
        assertEquals(SportsLiveTab.entries.toSet(), state.tabs.keys)
        assertEquals(listOf("u"), state.tabs.getValue(SportsLiveTab.UPCOMING).map { it.id })
        assertEquals(SportsLiveTab.LIVE_NOW, state.selectedTab)
        assertEquals(listOf("a", "b"), state.visibleItems.map { it.id })
        assertEquals(listOf(sportKey("Basketball"), sportKey("Soccer")), state.filters)
        assertNull(state.selectedFilter)
        assertEquals("a", state.selectedItemId)
        assertEquals(NOW, state.lastRefreshedMs)
        assertEquals(listOf(false), forcedFlags)
    }

    @Test
    fun `ended games sit at the end of Live now as Just ended, and the tab counts only the live ones`() = runTest {
        nextLoad = load(
            listOf(
                // Soccer is estimated at 3h15 with the late start: ended 15 min ago (Just ended), and
                // more than six hours past its estimate (gone).
                item("ended", isLive = true, sport = "Soccer", name = "Ended", startMs = NOW - 3 * HOUR - 30 * MINUTE),
                item("gone", isLive = true, sport = "Soccer", name = "Gone", startMs = NOW - 10 * HOUR),
                item("on", isLive = true, sport = "Soccer", name = "On", startMs = NOW - HOUR),
                item("untimed", isLive = true, sport = "Soccer", name = "Untimed")
            )
        )
        val viewModel = newViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("on", "untimed", "ended"), state.visibleItems.map { it.id })
        assertEquals(setOf("ended"), state.justEnded.keys.map { it.substringAfter('|') }.toSet())
        assertEquals(EndedSignal.ESTIMATE, state.endedOf(state.visibleItems.last())?.signal)
        assertNull(state.endedOf(state.visibleItems.first()))
        assertEquals(2, state.countOf(SportsLiveTab.LIVE_NOW))
    }

    @Test
    fun `a game ESPN shows live stays in the main list past its estimate`() = runTest {
        val pacers = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA).first { it.eventId == "401909985" }
        val inPlay = pacers.copy(
            state = ScoreState.IN, isFinal = false, clockText = "OT 1:00",
            home = pacers.home.copy(score = 130), away = pacers.away.copy(score = 129)
        )
        val game = item("g", isLive = true, sport = "Basketball", name = "Indiana Pacers at Philadelphia 76ers", startMs = NOW - 3 * HOUR - 20 * MINUTE)
        nextLoad = load(listOf(game))
        val viewModel = newViewModel(scores = mapOf(game.streamKey to inPlay))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("g"), state.visibleItems.map { it.id })
        assertTrue(state.justEnded.isEmpty())
    }

    @Test
    fun `a coverable game whose feed has not answered is not estimated, so it stays in the main list`() = runTest {
        // NFL: estimated at 4h05 with the late start, overdue only after 4h30.
        val game = item("g", isLive = true, sport = "NFL", name = "New England Patriots at New York Jets", startMs = NOW - 4 * HOUR - 10 * MINUTE)
        nextLoad = load(listOf(game))
        val waiting = newViewModel(canEstimate = { false })
        advanceUntilIdle()
        assertTrue(waiting.uiState.value.justEnded.isEmpty())
        assertEquals(listOf("g"), waiting.uiState.value.visibleItems.map { it.id })

        val answered = newViewModel(canEstimate = { true })
        advanceUntilIdle()
        assertEquals(EndedSignal.ESTIMATE, answered.uiState.value.justEnded.values.single().signal)
    }

    @Test
    fun `a game live far longer than its sport runs moves to Just ended without a request, never hidden`() = runTest {
        nextLoad = load(
            listOf(
                item("over", isLive = true, sport = "Soccer", name = "Over", startMs = NOW - 4 * HOUR),
                item("on", isLive = true, sport = "Soccer", name = "On", startMs = NOW - HOUR),
                item("untimed", isLive = true, sport = "Soccer", name = "Untimed")
            )
        )
        val viewModel = newViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("on", "untimed", "over"), state.visibleItems.map { it.id })
        assertEquals(EndedSignal.ESTIMATE, state.endedOf(state.visibleItems.last())?.signal)
        assertEquals(2, state.countOf(SportsLiveTab.LIVE_NOW))
        verify(exactly = 0) { repo.reprobeIfStale(any()) }
    }

    @Test
    fun `a game past its limit stays while links were found for it lately`() = runTest {
        recentlyAlive += "over"
        nextLoad = load(listOf(item("over", isLive = true, sport = "Soccer", name = "Over", startMs = NOW - 5 * HOUR)))
        val viewModel = newViewModel()
        advanceUntilIdle()

        assertEquals(listOf("over"), viewModel.uiState.value.visibleItems.map { it.id })
    }

    @Test
    fun `only a manual refresh forgets ended verdicts`() = runTest {
        val viewModel = newViewModel()
        advanceUntilIdle()
        assertEquals("initial load", 0, clearedEnded)

        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(1, clearedEnded)
    }

    @Test
    fun `selecting a sport filters visible items and keeps selection valid`() = runTest {
        nextLoad = load(
            listOf(
                item("a", isLive = true, sport = "Basketball", name = "A"),
                item("b", isLive = true, sport = "Soccer", name = "B"),
                item("c", isLive = true, sport = "Soccer", name = "C")
            )
        )
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.selectItem("a")
        runCurrent()
        assertEquals("a", viewModel.uiState.value.selectedItemId)

        viewModel.selectFilter(sportKey("Soccer"))
        runCurrent()

        val filtered = viewModel.uiState.value
        assertEquals(sportKey("Soccer"), filtered.selectedFilter)
        assertEquals(listOf("b", "c"), filtered.visibleItems.map { it.id })
        assertEquals("b", filtered.selectedItemId)

        viewModel.selectItem("c")
        viewModel.selectFilter(null)
        runCurrent()

        val all = viewModel.uiState.value
        assertEquals(listOf("a", "b", "c"), all.visibleItems.map { it.id })
        assertEquals("c", all.selectedItemId)
    }

    @Test
    fun `selecting UPCOMING shows items sorted by start`() = runTest {
        nextLoad = load(
            listOf(
                item("live", isLive = true, sport = "Golf"),
                item("late", isLive = false, sport = "Tennis", startMs = NOW + 3 * HOUR),
                item("soon", isLive = false, sport = "Soccer", startMs = NOW + HOUR),
                item("mid", isLive = false, sport = "Soccer", startMs = NOW + 2 * HOUR)
            )
        )
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.selectFilter(sportKey("Golf"))
        runCurrent()
        assertEquals(sportKey("Golf"), viewModel.uiState.value.selectedFilter)

        viewModel.selectTab(SportsLiveTab.UPCOMING)
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(SportsLiveTab.UPCOMING, state.selectedTab)
        assertNull(state.selectedFilter)
        assertEquals(listOf("soon", "mid", "late"), state.visibleItems.map { it.id })
        assertEquals(listOf(sportKey("Soccer"), sportKey("Tennis")), state.filters)
        assertEquals("soon", state.selectedItemId)
    }

    @Test
    fun `failures are exposed but items still show`() = runTest {
        val failure = SportsLiveFailure(CLAIMED, "HTTP 500")
        nextLoad = load(listOf(item("a", isLive = true, sport = "Soccer")), failures = listOf(failure))
        val viewModel = newViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf(failure), state.failures)
        assertEquals(listOf("a"), state.visibleItems.map { it.id })
        assertFalse(state.isLoading)
    }

    @Test
    fun `each tab refreshes only its own catalogs on its own cadence, and only while visible`() = runTest {
        nextLoad = load(listOf(item("a", isLive = true, sport = "Soccer")))
        val viewModel = SportsLiveViewModel(repo, refreshClock = { testScheduler.currentTime }, computeDispatcher = compute)
        viewModel.nowMs = { NOW }
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        // Opening the section loads every catalog once.
        assertEquals(listOf<Set<SportsLiveKind>?>(null), kindsAsked)

        viewModel.onScreenVisible(true)
        viewModel.onScreenVisible(true) // a second call must not start a second loop
        runCurrent()
        assertEquals("fresher than Live now's 3 min: nothing", 1, kindsAsked.size)
        advanceTimeBy(3 * MINUTE - 1)
        runCurrent()
        assertEquals(1, kindsAsked.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(null, EVENTS), kindsAsked)
        assertEquals(listOf(false, true), forcedFlags)
        assertEquals("channels are carried over", listOf(true, false), includeChannelsFlags)
        advanceTimeBy(3 * MINUTE)
        runCurrent()
        assertEquals(3, kindsAsked.size)

        // Upcoming: every 10 min, counted from the events catalogs' last fetch.
        viewModel.selectTab(SportsLiveTab.UPCOMING)
        runCurrent()
        assertEquals(3, kindsAsked.size)
        advanceTimeBy(10 * MINUTE - 1)
        runCurrent()
        assertEquals(3, kindsAsked.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(EVENTS, kindsAsked.last())
        assertEquals(4, kindsAsked.size)

        // Replays: refreshed on opening (its last fetch is 16 min old), never on a timer.
        viewModel.selectTab(SportsLiveTab.REPLAYS)
        runCurrent()
        assertEquals(setOf(SportsLiveKind.REPLAY), kindsAsked.last())
        advanceTimeBy(60 * MINUTE)
        runCurrent()
        assertEquals(5, kindsAsked.size)

        // Results and Channels never refresh addon catalogs on their own.
        viewModel.selectTab(SportsLiveTab.RESULTS)
        viewModel.selectTab(SportsLiveTab.CHANNELS)
        advanceTimeBy(60 * MINUTE)
        runCurrent()
        assertEquals(5, kindsAsked.size)

        // Back on Live now an hour later: stale, so at once.
        viewModel.selectTab(SportsLiveTab.LIVE_NOW)
        runCurrent()
        assertEquals(6, kindsAsked.size)

        // The screensaver pauses it; coming back refreshes only when stale.
        viewModel.onAmbient(true)
        advanceTimeBy(30 * MINUTE)
        runCurrent()
        assertEquals(6, kindsAsked.size)
        viewModel.onAmbient(false)
        runCurrent()
        assertEquals(7, kindsAsked.size)

        // Hidden (the player is up): nothing.
        viewModel.onScreenVisible(false)
        advanceTimeBy(30 * MINUTE)
        runCurrent()
        assertEquals(7, kindsAsked.size)
    }

    @Test
    fun `boards are built on the compute dispatcher, and a focus move builds none`() = runTest {
        nextLoad = load(listOf(item("a", isLive = true, sport = "Soccer", name = "A"), item("b", isLive = true, sport = "Soccer", name = "B")))
        val boardDispatcher = StandardTestDispatcher(testScheduler, name = "compute")
        val viewModel = SportsLiveViewModel(repo, computeDispatcher = boardDispatcher)
        viewModel.nowMs = { NOW }
        val ranOn = mutableListOf<ContinuationInterceptor?>()
        viewModel.buildBoard = { inputs ->
            ranOn += currentCoroutineContext()[ContinuationInterceptor]
            SportsLiveBoard.build(inputs)
        }
        backgroundScope.launch { viewModel.board.collect {} }
        advanceUntilIdle()
        // V2 (#132): Live Now is always the Scoreboard.
        val board = viewModel.board.value!!
        assertEquals(listOf("a", "b"), board.items.map { it.id })
        assertEquals(listOf("a", "b"), board.rows!!.map { it.item.id })
        assertTrue(ranOn.isNotEmpty())
        assertTrue(ranOn.all { it === boardDispatcher })

        val built = ranOn.size
        viewModel.selectItem("b")
        advanceUntilIdle()
        assertEquals("a focus move builds nothing", built, ranOn.size)

        // Channels (V2 P4): channel rows by region, still built on the compute dispatcher.
        viewModel.selectTab(SportsLiveTab.CHANNELS)
        advanceUntilIdle()
        assertTrue(viewModel.board.value!!.rows!!.all { it.group == ScoreboardGroup.CHANNELS })
        assertTrue(ranOn.all { it === boardDispatcher })
    }

    @Test
    fun `items of catalogs that are no longer claimed are not shown`() = runTest {
        val other = CLAIMED.copy(key = "other.addon_tv_events")
        nextLoad = load(
            listOf(
                item("mine", isLive = true, sport = "Soccer"),
                item("theirs", isLive = true, sport = "Soccer").copy(catalogKey = other.key)
            )
        )
        val viewModel = newViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("mine"), state.visibleItems.map { it.id })
        assertEquals(listOf("mine"), state.tabs.getValue(SportsLiveTab.LIVE_NOW).map { it.id })

        // After a profile switch to one whose catalogs differ, the cached items vanish at once.
        claimedFlow.value = listOf(other)
        runCurrent()
        assertEquals(listOf("theirs"), viewModel.uiState.value.visibleItems.map { it.id })
    }

    @Test
    fun `no claimed catalogs sets hasClaimedCatalogs false and stops loading`() = runTest {
        claimedFlow.value = emptyList()
        val viewModel = newViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.hasClaimedCatalogs)
        assertFalse(state.isLoading)
        assertTrue(state.visibleItems.isEmpty())
        assertNull(state.selectedItemId)
    }

    @Test
    fun `ended items are hidden from Live now and stay in the other tabs`() = runTest {
        val live = item("a", isLive = true, sport = "Soccer")
        val gone = item("b", isLive = true, sport = "Soccer")
        val upcoming = item("u", isLive = false, sport = "Tennis", startMs = NOW + HOUR)
        nextLoad = load(listOf(live, gone, upcoming))
        endedFlow.value = setOf(gone.streamKey, upcoming.streamKey)
        val viewModel = newViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("a"), state.tabs.getValue(SportsLiveTab.LIVE_NOW).map { it.id })
        assertEquals(listOf("a"), state.visibleItems.map { it.id })
        assertEquals(listOf("u"), state.tabs.getValue(SportsLiveTab.UPCOMING).map { it.id })

        // A later probe that finds streams brings the item back.
        endedFlow.value = emptySet()
        runCurrent()
        assertEquals(listOf("a", "b"), viewModel.uiState.value.visibleItems.map { it.id })
    }

    @Test
    fun `only the Live now item the user settles on for 700 ms is re-probed`() = runTest {
        val items = (1..5).map { item("g$it", isLive = true, sport = "Soccer", name = "G$it") }
        nextLoad = load(items)
        val viewModel = newViewModel()
        advanceUntilIdle()

        // Moving through five items, each held under 700 ms, probes none of them.
        items.forEach { item ->
            viewModel.selectItem(item.id)
            advanceTimeBy(699)
        }
        verify(exactly = 0) { repo.reprobeIfStale(any()) }

        // Resting on the last one for 700 ms probes exactly that item, once.
        advanceTimeBy(1)
        runCurrent()
        verify(exactly = 1) { repo.reprobeIfStale(any()) }
        verify(exactly = 1) { repo.reprobeIfStale(items.last()) }
    }

    @Test
    fun `resting on an item in another tab never re-probes`() = runTest {
        val upcoming = item("u", isLive = false, sport = "Tennis", startMs = NOW + HOUR)
        nextLoad = load(listOf(item("a", isLive = true, sport = "Soccer"), upcoming))
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.selectTab(SportsLiveTab.UPCOMING)
        runCurrent()
        viewModel.selectItem("u")
        advanceTimeBy(5_000)

        verify(exactly = 0) { repo.reprobeIfStale(any()) }
    }

    @Test
    fun `channels tab exposes groups as filters`() = runTest {
        nextLoad = load(listOf(channel("uk1", "UK"), channel("ca1", "CA"), item("a", isLive = true, sport = "Soccer")))
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.selectTab(SportsLiveTab.CHANNELS)
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(listOf("CA", "UK"), state.filters)
        assertNull(state.selectedFilter)
    }

    @Test
    fun `live tab exposes sports as filters`() = runTest {
        nextLoad = load(
            listOf(
                channel("uk1", "UK", isLive = true, sport = "Soccer"),
                item("a", isLive = true, sport = "Tennis")
            )
        )
        val viewModel = newViewModel()
        advanceUntilIdle()

        assertEquals(listOf(sportKey("Soccer"), sportKey("Tennis")), viewModel.uiState.value.filters)
    }

    @Test
    fun `selecting a group filters channels`() = runTest {
        nextLoad = load(listOf(channel("uk1", "UK"), channel("ca1", "CA"), channel("uk2", "UK")))
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.selectTab(SportsLiveTab.CHANNELS)
        viewModel.selectFilter("UK")
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals("UK", state.selectedFilter)
        assertEquals(listOf("uk1", "uk2"), state.visibleItems.map { it.id })
    }

    @Test
    fun `switching tabs resets the filter`() = runTest {
        nextLoad = load(
            listOf(
                channel("uk1", "UK"),
                channel("ca1", "CA"),
                item("a", isLive = true, sport = "Soccer", name = "A"),
                item("b", isLive = true, sport = "Tennis", name = "B")
            )
        )
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.selectTab(SportsLiveTab.CHANNELS)
        viewModel.selectFilter("UK")
        runCurrent()
        assertEquals("UK", viewModel.uiState.value.selectedFilter)

        viewModel.selectTab(SportsLiveTab.LIVE_NOW)
        runCurrent()

        val state = viewModel.uiState.value
        assertNull(state.selectedFilter)
        assertEquals(listOf("a", "b"), state.visibleItems.map { it.id })
    }

    @Test
    fun `a selected filter that loses its games stays at 0 until the user moves, then falls back to All (#79)`() = runTest {
        nextLoad = load(listOf(channel("uk1", "UK"), channel("ca1", "CA"), channel("us1", "US")))
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.selectTab(SportsLiveTab.CHANNELS)
        viewModel.selectFilter("UK")
        runCurrent()
        assertEquals("UK", viewModel.uiState.value.selectedFilter)
        assertFalse(viewModel.onUserMoved())

        latestFlow.value = load(listOf(channel("ca1", "CA"), channel("us1", "US")))
        runCurrent()
        val emptied = viewModel.uiState.value
        assertEquals("UK", emptied.selectedFilter)
        assertEquals(listOf("CA", "UK", "US"), emptied.filters)
        assertEquals(0, emptied.filterCounts["UK"] ?: 0)
        assertTrue(emptied.selectedFilterEmpty)
        assertTrue(emptied.visibleItems.isEmpty())

        assertTrue(viewModel.onUserMoved())
        runCurrent()
        assertNull(viewModel.uiState.value.selectedFilter)
        assertEquals(listOf("CA", "US"), viewModel.uiState.value.filters)
        assertEquals(listOf("ca1", "us1"), viewModel.uiState.value.visibleItems.map { it.id })

        // Gone back to All, it does not come back with its games.
        latestFlow.value = load(listOf(channel("uk1", "UK"), channel("ca1", "CA"), channel("us1", "US")))
        runCurrent()
        assertNull(viewModel.uiState.value.selectedFilter)
        assertEquals(3, viewModel.uiState.value.visibleItems.size)
    }

    @Test
    fun `chips carry their game counts and chips with none hide (#79)`() = runTest {
        nextLoad = load(
            listOf(
                item("a", isLive = true, sport = "Soccer", name = "A"),
                item("b", isLive = true, sport = "Soccer", name = "B"),
                item("c", isLive = true, sport = "Basketball", name = "C"),
                item("u", isLive = false, sport = "Tennis", startMs = NOW + HOUR)
            )
        )
        val viewModel = newViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf(sportKey("Basketball"), sportKey("Soccer")), state.filters)
        assertEquals(3, state.filterCounts[null])
        assertEquals(2, state.filterCounts[sportKey("Soccer")])
        assertEquals(1, state.filterCounts[sportKey("Basketball")])
        assertNull(state.filterCounts[sportKey("Tennis")])
    }

    @Test
    fun `each tab reopens on its last chip while it still has games (#79)`() = runTest {
        nextLoad = load(
            listOf(
                channel("uk1", "UK"),
                channel("ca1", "CA"),
                item("a", isLive = true, sport = "Soccer", name = "A"),
                item("b", isLive = true, sport = "Tennis", name = "B")
            )
        )
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.selectFilter(sportKey("Tennis"))
        viewModel.selectTab(SportsLiveTab.CHANNELS)
        viewModel.selectFilter("UK")
        runCurrent()

        viewModel.selectTab(SportsLiveTab.LIVE_NOW)
        runCurrent()
        assertEquals(sportKey("Tennis"), viewModel.uiState.value.selectedFilter)
        viewModel.selectTab(SportsLiveTab.CHANNELS)
        runCurrent()
        assertEquals("UK", viewModel.uiState.value.selectedFilter)

        // All picked again: the tab reopens on All.
        viewModel.selectFilter(null)
        viewModel.selectTab(SportsLiveTab.LIVE_NOW)
        viewModel.selectTab(SportsLiveTab.CHANNELS)
        runCurrent()
        assertNull(viewModel.uiState.value.selectedFilter)

        // A remembered chip whose games have gone is not reopened.
        latestFlow.value = load(listOf(channel("ca1", "CA"), item("a", isLive = true, sport = "Soccer", name = "A")))
        runCurrent()
        viewModel.selectTab(SportsLiveTab.LIVE_NOW)
        runCurrent()
        assertNull(viewModel.uiState.value.selectedFilter)
    }

    @Test
    fun `manual refresh includes channels`() = runTest {
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(listOf(false, true), forcedFlags)
        assertEquals(listOf(true, true), includeChannelsFlags)
    }

    @Test
    fun `items are bucketed once per load, not on selection changes`() = runTest {
        nextLoad = load(listOf(channel("uk1", "UK"), channel("ca1", "CA"), item("a", isLive = true, sport = "Soccer")))
        var bucketCalls = 0
        val viewModel = SportsLiveViewModel(repo, computeDispatcher = compute)
        viewModel.nowMs = { NOW }
        viewModel.bucket = { items, now, order -> bucketCalls++; SportsLiveItemBucketer.bucket(items, now, order) }
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()
        val afterLoad = bucketCalls

        viewModel.selectTab(SportsLiveTab.CHANNELS)
        runCurrent()
        viewModel.selectFilter("UK")
        runCurrent()
        viewModel.selectItem("uk1")
        runCurrent()
        endedFlow.value = setOf("x|y")
        runCurrent()

        assertEquals(listOf("uk1"), viewModel.uiState.value.visibleItems.map { it.id })
        assertEquals(afterLoad, bucketCalls)

        latestFlow.value = load(listOf(channel("uk2", "UK")))
        runCurrent()
        assertEquals(afterLoad + 1, bucketCalls)
    }

    @Test
    fun `sport inference reaches Live Now, Upcoming and Replays but never Channels`() = runTest {
        nextLoad = load(
            listOf(
                item("a", isLive = true, sport = "Basketball", name = "Bulls vs Heat"),
                item("u", isLive = false, sport = "Basketball", startMs = NOW + HOUR, name = "Bulls vs Nets"),
                item("r", isLive = false, sport = "Basketball", startMs = NOW - HOUR, name = "Illinois vs Duke")
                    .copy(kind = SportsLiveKind.REPLAY),
                channel("ch", "US")
            )
        )
        val viewModel = SportsLiveViewModel(
            repo,
            inferSport = { it.copy(sport = "Inferred") },
            computeDispatcher = compute
        )
        viewModel.nowMs = { NOW }
        backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        val tabs = viewModel.uiState.value.tabs
        assertEquals(listOf("Inferred"), tabs.getValue(SportsLiveTab.LIVE_NOW).map { it.sport })
        assertEquals(listOf("Inferred"), tabs.getValue(SportsLiveTab.UPCOMING).map { it.sport })
        assertEquals(listOf("Inferred"), tabs.getValue(SportsLiveTab.REPLAYS).map { it.sport })
        assertFalse(tabs.getValue(SportsLiveTab.CHANNELS).any { it.sport == "Inferred" })
    }

    @Test
    fun `followed games come first in Live Now, Upcoming and Replays and are only marked on Channels`() = runTest {
        followedFlow.value = listOf(FollowedTeam("Celtics", "Basketball"))
        nextLoad = load(
            listOf(
                item("a", isLive = true, sport = "Basketball", name = "Bulls vs Heat"),
                item("b", isLive = true, sport = "Basketball", name = "Knicks vs Celtics"),
                item("u1", isLive = false, sport = "Basketball", startMs = NOW + HOUR, name = "Bulls vs Nets"),
                item("u2", isLive = false, sport = "Basketball", startMs = NOW + 2 * HOUR, name = "Celtics vs Nets"),
                item("r1", isLive = false, sport = "Basketball", startMs = NOW - HOUR, name = "Lakers vs Suns")
                    .copy(kind = SportsLiveKind.REPLAY),
                item("r2", isLive = false, sport = "Basketball", startMs = NOW - 2 * HOUR, name = "Celtics vs Suns")
                    .copy(kind = SportsLiveKind.REPLAY),
                channel("ch1", "US"),
                channel("ch2", "US").copy(teams = teamsOf("Celtics vs Heat")),
                channel("ch3", "US")
            )
        )
        val viewModel = newViewModel()
        advanceUntilIdle()

        val tabs = viewModel.uiState.value.tabs
        assertEquals(listOf("b", "a"), tabs.getValue(SportsLiveTab.LIVE_NOW).map { it.id })
        assertEquals(listOf("u2", "u1"), tabs.getValue(SportsLiveTab.UPCOMING).map { it.id })
        assertEquals(listOf("r2", "r1"), tabs.getValue(SportsLiveTab.REPLAYS).map { it.id })
        // Channels keep their order; a channel naming a followed game is only marked.
        assertEquals(listOf("ch1", "ch2", "ch3"), tabs.getValue(SportsLiveTab.CHANNELS).map { it.id })
        assertEquals(listOf(false, true, false), tabs.getValue(SportsLiveTab.CHANNELS).map { it.followed })

        // A follow in another sport does not count; unfollowing restores the order without a re-bucket.
        followedFlow.value = listOf(FollowedTeam("Celtics", "Soccer"))
        runCurrent()
        assertEquals(listOf("a", "b"), viewModel.uiState.value.tabs.getValue(SportsLiveTab.LIVE_NOW).map { it.id })
    }

    @Test
    fun `my teams filter leads the filters when a followed game is in the tab and shows only those`() = runTest {
        followedFlow.value = listOf(FollowedTeam("Celtics", null))
        nextLoad = load(
            listOf(
                item("a", isLive = true, sport = "Basketball", name = "Bulls vs Heat"),
                item("b", isLive = true, sport = "Basketball", name = "Knicks vs Celtics"),
                item("s", isLive = true, sport = "Soccer", name = "Arsenal vs Chelsea"),
                item("u", isLive = false, sport = "Soccer", startMs = NOW + HOUR, name = "Leeds vs Spurs"),
                channel("ch1", "US"),
                channel("ch2", "UK").copy(teams = teamsOf("Celtics vs Heat"))
            )
        )
        val viewModel = newViewModel()
        advanceUntilIdle()

        val my = SportsLiveUiState.MY_TEAMS_FILTER
        assertEquals(listOf(my, sportKey("Basketball"), sportKey("Soccer")), viewModel.uiState.value.filters)

        viewModel.selectFilter(my)
        runCurrent()
        assertEquals(my, viewModel.uiState.value.selectedFilter)
        assertEquals(listOf("b"), viewModel.uiState.value.visibleItems.map { it.id })

        // No followed game on Upcoming, and never on Channels.
        viewModel.selectTab(SportsLiveTab.UPCOMING)
        runCurrent()
        assertEquals(listOf(sportKey("Soccer")), viewModel.uiState.value.filters)
        viewModel.selectTab(SportsLiveTab.CHANNELS)
        runCurrent()
        assertEquals(listOf("UK", "US"), viewModel.uiState.value.filters)

        // Unfollowing while the filter is selected keeps it at 0 until the user moves (#79), then All.
        viewModel.selectTab(SportsLiveTab.LIVE_NOW)
        viewModel.selectFilter(my)
        runCurrent()
        followedFlow.value = emptyList()
        runCurrent()
        assertEquals(my, viewModel.uiState.value.selectedFilter)
        assertTrue(viewModel.uiState.value.selectedFilterEmpty)
        assertTrue(viewModel.onUserMoved())
        runCurrent()
        assertNull(viewModel.uiState.value.selectedFilter)
        assertEquals(listOf(sportKey("Basketball"), sportKey("Soccer")), viewModel.uiState.value.filters)
        assertEquals(listOf("s", "a", "b"), viewModel.uiState.value.visibleItems.map { it.id })
    }

    @Test
    fun `a game started from my teams carries every live now game, followed ones first (#68)`() = runTest {
        followedFlow.value = listOf(FollowedTeam("Celtics", null))
        nextLoad = load(
            listOf(
                item("a", isLive = true, sport = "Basketball", name = "Bulls vs Heat"),
                item("b", isLive = true, sport = "Basketball", name = "Knicks vs Celtics"),
                item("s", isLive = true, sport = "Soccer", name = "Arsenal vs Chelsea")
            )
        )
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.selectFilter(SportsLiveUiState.MY_TEAMS_FILTER)
        runCurrent()
        val followed = viewModel.uiState.value.visibleItems.single()

        try {
            viewModel.startZapSession(followed)
            val session = SportsLiveZapSessions.current()!!
            assertEquals(setOf("a", "b", "s"), session.items.map { it.id }.toSet())
            assertEquals("b", session.current.id)
            assertEquals(SportsLiveUiState.MY_TEAMS_FILTER, session.filter)
            assertNull(session.panelChip)
            // Up/Down: the followed games first, then on through the rest.
            assertEquals(3, session.zapOrder.size)
            assertEquals("b", session.items[session.zapOrder.first()].id)

            // Another tab keeps the list shown.
            viewModel.selectTab(SportsLiveTab.UPCOMING)
            runCurrent()
            assertEquals(viewModel.uiState.value.visibleItems, viewModel.uiState.value.zapItems)
        } finally {
            SportsLiveZapSessions.clear()
        }
    }

    @Test
    fun `toggleFollow follows a side with the item's sport, none for a channel, and unfollows every match`() = runTest {
        val game = item("g", isLive = true, sport = "American Football", name = "Jets vs New England Patriots")
            .copy(teams = teamsOf("Jets vs New England Patriots"))
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.toggleFollow(game, "New England Patriots")
        advanceUntilIdle()
        assertEquals(listOf(FollowedTeam("New England Patriots", "American Football")), followedFlow.value)

        // A channel's sport is only its catalog name, so none is stored.
        val ppv = channel("ch", "US", sport = "EasyPlay Live TV").copy(teams = teamsOf("Celtics vs 76ers"))
        viewModel.toggleFollow(ppv, "Celtics")
        advanceUntilIdle()
        assertEquals(FollowedTeam("Celtics", null), followedFlow.value.last())

        // A shorter follow ("Patriots") of the same side is removed too; Celtics stays.
        followedFlow.value = followedFlow.value + FollowedTeam("Patriots", null)
        viewModel.toggleFollow(game, "New England Patriots")
        advanceUntilIdle()
        assertEquals(listOf(FollowedTeam("Celtics", null)), followedFlow.value)
    }

    @Test
    fun `toggleFollow stores no catalog-name sport, so the follow matches the team in every addon`() = runTest {
        // This addon has no genres: the item's sport is its catalog's name.
        val game = item("g", isLive = true, sport = CLAIMED.catalog.name, name = "Celtics vs 76ers")
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.toggleFollow(game, "Celtics")
        advanceUntilIdle()

        assertEquals(listOf(FollowedTeam("Celtics", null)), followedFlow.value)
        val otherAddon = item("o", isLive = true, sport = "Basketball", name = "Boston Celtics vs Philadelphia 76ers")
        assertTrue(SportsLiveTeams.isFollowed(otherAddon, followedFlow.value))
    }

    @Test
    fun `toggleFollow on a merged card follows with an alternate's sport and unfollows every matching entry`() = runTest {
        val primary = item("p", isLive = true, sport = CLAIMED.catalog.name, name = "Celtics vs 76ers")
        val alternate = item("a", isLive = true, sport = "Basketball", name = "Boston Celtics vs Philadelphia 76ers")
            .let { it.copy(addonId = "other.addon", meta = it.meta.copy(genres = listOf("Basketball"))) }
        val merged = primary.copy(alternates = listOf(alternate))
        val viewModel = newViewModel()
        advanceUntilIdle()

        viewModel.toggleFollow(merged, "Celtics")
        advanceUntilIdle()
        assertEquals(listOf(FollowedTeam("Celtics", "Basketball")), followedFlow.value)

        // Duplicates from earlier follows (another addon's label, no sport) all go; other teams stay.
        followedFlow.value = listOf(
            FollowedTeam("Celtics", "Basketball"),
            FollowedTeam("Boston Celtics", null),
            FollowedTeam("Lakers", null)
        )
        viewModel.toggleFollow(merged, "Celtics")
        advanceUntilIdle()
        assertEquals(listOf(FollowedTeam("Lakers", null)), followedFlow.value)
    }

    @Test
    fun `tab, filter, visibility and the setting each reach the hover preview`() = runTest {
        nextLoad = load(listOf(item("a", isLive = true, sport = "Soccer"), item("b", isLive = true, sport = "Tennis")))
        val enabled = MutableStateFlow(false)
        val stream = dualStream("a")
        val viewModel = SportsLiveViewModel(repo, previewResolve = { SportsLivePreviewLookup.Found(stream) }, hoverPreviewEnabled = enabled, computeDispatcher = compute)
        viewModel.nowMs = { NOW }
        backgroundScope.launch { viewModel.uiState.collect {} }
        val a = item("a", isLive = true, sport = "Soccer")
        fun settle() { advanceTimeBy(2_001); runCurrent() }
        val playing = SportsLivePreviewState.Playing(a, stream)

        viewModel.onScreenVisible(true)
        viewModel.setPreviewFocus(a)
        settle()
        assertEquals("setting off", SportsLivePreviewState.Idle, viewModel.preview.value)

        enabled.value = true
        runCurrent()
        settle()
        assertEquals(playing, viewModel.preview.value)

        enabled.value = false
        runCurrent()
        assertEquals("setting turned off", SportsLivePreviewState.Idle, viewModel.preview.value)
        enabled.value = true
        runCurrent()
        settle()
        assertEquals(playing, viewModel.preview.value)

        viewModel.onScreenVisible(false)
        assertEquals("screen hidden", SportsLivePreviewState.Idle, viewModel.preview.value)
        viewModel.onScreenVisible(true)
        settle()
        assertEquals(playing, viewModel.preview.value)

        viewModel.selectFilter(sportKey("Soccer"))
        assertEquals("filter changed", SportsLivePreviewState.Idle, viewModel.preview.value)
        viewModel.setPreviewFocus(a)
        settle()
        assertEquals(playing, viewModel.preview.value)

        viewModel.selectTab(SportsLiveTab.UPCOMING)
        assertEquals("tab changed", SportsLivePreviewState.Idle, viewModel.preview.value)
        settle()
        assertEquals("no preview on Upcoming", SportsLivePreviewState.Idle, viewModel.preview.value)
        viewModel.selectTab(SportsLiveTab.CHANNELS)
        settle()
        assertEquals(playing, viewModel.preview.value)

        viewModel.onScreenVisible(false)
    }

    @Test
    fun `an active mini player holds the hover preview off, and a fresh settle follows its stop`() = runTest {
        nextLoad = load(listOf(item("a", isLive = true, sport = "Soccer")))
        val mini = MutableStateFlow(true)
        val stream = dualStream("a")
        val viewModel = SportsLiveViewModel(
            repo,
            previewResolve = { SportsLivePreviewLookup.Found(stream) },
            hoverPreviewEnabled = MutableStateFlow(true),
            miniPlayerActive = mini,
            computeDispatcher = compute
        )
        viewModel.nowMs = { NOW }
        backgroundScope.launch { viewModel.uiState.collect {} }
        val a = item("a", isLive = true, sport = "Soccer")

        viewModel.onScreenVisible(true)
        viewModel.setPreviewFocus(a)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals("mini player active", SportsLivePreviewState.Idle, viewModel.preview.value)

        mini.value = false
        runCurrent()
        advanceTimeBy(1_999)
        runCurrent()
        assertEquals("settling again", SportsLivePreviewState.Idle, viewModel.preview.value)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(SportsLivePreviewState.Playing(a, stream), viewModel.preview.value)

        mini.value = true
        runCurrent()
        assertEquals("a new mini player stops the preview", SportsLivePreviewState.Idle, viewModel.preview.value)

        viewModel.onScreenVisible(false)
    }

    private fun teamsOf(name: String) = SportsLiveTeams.parse(name)

    private fun channel(id: String, group: String, isLive: Boolean = false, sport: String? = null) =
        item(id, isLive = isLive, sport = sport, name = "Channel $id").copy(isChannel = true, group = group)

    // The excitement badge (#43, V2): basketball games from the ESPN fixture, put in play.
    private val nbaGames = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)

    private fun inPlay(eventId: String, home: Int, away: Int, clock: String): ScoreSnapshot =
        nbaGames.first { it.eventId == eventId }.let { event ->
            event.copy(
                state = ScoreState.IN, isFinal = false, clockText = clock,
                home = event.home.copy(score = home), away = event.away.copy(score = away)
            )
        }

    private val pacersGame = item("pacers", isLive = true, sport = "Basketball", name = "Indiana Pacers at Philadelphia 76ers", startMs = NOW - 2 * HOUR)
    private val hornetsGame = item("hornets", isLive = true, sport = "Basketball", name = "Charlotte Hornets at LA Clippers", startMs = NOW - 90 * MINUTE)
    private val lakersGame = item("lakers", isLive = true, sport = "Basketball", name = "Los Angeles Lakers at Phoenix Suns", startMs = NOW - HOUR)
    private val celticsGame = item("celtics", isLive = true, sport = "Basketball", name = "Dallas Mavericks at Boston Celtics", startMs = NOW - 30 * MINUTE)

    @Test
    fun `the excitement badge shows with scores shown only and never re-orders Live Now`() = runTest {
        followedFlow.value = listOf(FollowedTeam("Celtics", "Basketball"))
        nextLoad = load(listOf(pacersGame, hornetsGame, lakersGame, celticsGame))
        val inputs = flowOf(
            EndedInputs(
                mapOf(
                    pacersGame.streamKey to inPlay("401909985", 60, 40, "5:00 - 2nd"),
                    lakersGame.streamKey to inPlay("401909986", 100, 99, "1:00 - 4th"),
                    celticsGame.streamKey to inPlay("401909989", 10, 30, "8:00 - 1st")
                )
            )
        )
        fun TestScope.state(mode: ScoresMode?): SportsLiveUiState {
            val viewModel = SportsLiveViewModel(
                repo,
                endedInputs = inputs,
                endedGames = SportsLiveEndedGames(),
                scoresMode = flowOf(mode),
                computeDispatcher = compute
            )
            viewModel.nowMs = { NOW }
            backgroundScope.launch { viewModel.uiState.collect {} }
            advanceUntilIdle()
            return viewModel.uiState.value
        }

        val shown = state(ScoresMode.SHOW)
        // Start order, followed first: the close game is badged, not moved (F2: no sort, no why-tag circle).
        assertEquals(listOf("celtics", "pacers", "hornets", "lakers"), shown.visibleItems.map { it.id })
        assertEquals(mapOf(lakersGame.streamKey to WhyTag(WhyKind.CLOSE_LATE, 1)), shown.whyTags)
        // Hide until focused and Off: the same order, no badges.
        val hidden = state(ScoresMode.HIDE_UNTIL_FOCUSED)
        assertEquals(shown.visibleItems, hidden.visibleItems)
        assertTrue(hidden.whyTags.isEmpty())
        assertTrue(state(ScoresMode.OFF).whyTags.isEmpty())
    }

    @Test
    fun `the retired View and Sort settings are dropped when the section opens`() = runTest {
        var dropped = 0
        SportsLiveViewModel(repo, dropRetiredPrefs = { dropped++ }, computeDispatcher = compute)
        advanceUntilIdle()
        assertEquals(1, dropped)
    }

    // #60 phase 2: link checks confirm a game past its estimate is over

    private val linkFetches = mutableListOf<String>()
    private val linkTicks = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }

    // When the card's link was last resolved (the resolve cache's entry); null: none fresh.
    private var resolvedAt: Long? = 0L

    /** No game may be estimated (its feed has not answered): only a link check can move it. */
    private fun TestScope.linkCheckedViewModel(
        scores: Map<String, ScoreSnapshot> = emptyMap(),
        answer: () -> ManifestResponse? = { ManifestResponse(404, null) }
    ): SportsLiveViewModel {
        val checker = SportsLiveLinkChecker(
            fetch = { url, _ -> linkFetches += url; answer() },
            cooldown = SportsLiveSourceCooldown(nowMs = { NOW }),
            trust = SportsLiveSourceTrust(),
            nowMs = { NOW }
        )
        val viewModel = SportsLiveViewModel(
            repo,
            endedInputs = flowOf(EndedInputs(scores) { false }),
            endedGames = SportsLiveEndedGames(),
            clock = linkTicks,
            linkChecker = checker,
            linkCheckOf = { item ->
                resolvedAt?.let { at ->
                    LinkCheckRequest(item.streamKey, "https://cdn.invalid/${item.id}.m3u8", emptyMap(), listOf(item.addonBaseUrl), item.addonName, at)
                }
            },
            computeDispatcher = compute
        )
        viewModel.nowMs = { NOW }
        backgroundScope.launch { viewModel.uiState.collect {} }
        return viewModel
    }

    private fun TestScope.tick() {
        linkTicks.tryEmit(Unit)
        runCurrent()
    }

    // NFL: estimated at 4h05 with the late start, overdue only after 4h30.
    private val pastEstimate = item("g", isLive = true, sport = "NFL", name = "New England Patriots at New York Jets", startMs = NOW - 4 * HOUR - 10 * MINUTE)
    private val stillOn = item("on", isLive = true, sport = "NFL", name = "Buffalo Bills at Miami Dolphins", startMs = NOW - HOUR)

    @Test
    fun `a game past its estimate with a dead link moves to Just ended as Probably ended, never hidden`() = runTest {
        nextLoad = load(listOf(pastEstimate, stillOn))
        val viewModel = linkCheckedViewModel()
        runCurrent()
        // Live now not on screen: nothing is checked.
        tick()
        assertTrue(linkFetches.isEmpty())

        viewModel.onScreenVisible(true)
        tick()
        runCurrent()
        // Only the game past its estimate, once.
        assertEquals(listOf("https://cdn.invalid/g.m3u8"), linkFetches)
        val state = viewModel.uiState.value
        assertEquals(listOf("on", "g"), state.visibleItems.map { it.id })
        assertEquals(EndedSignal.ESTIMATE, state.endedOf(state.visibleItems.last())?.signal)
        assertEquals(1, state.countOf(SportsLiveTab.LIVE_NOW))
        // Checked once only; it stays listed (and playable) in the strip.
        tick()
        assertEquals(1, linkFetches.size)
        assertEquals(listOf("on", "g"), viewModel.uiState.value.visibleItems.map { it.id })
        viewModel.onScreenVisible(false)
    }

    @Test
    fun `a Probably ended card whose links come back returns to live, and leaves the strip only while its verdict stands`() = runTest {
        nextLoad = load(listOf(pastEstimate, stillOn))
        var reply: ManifestResponse? = ManifestResponse(404, null)
        val viewModel = linkCheckedViewModel(answer = { reply })
        viewModel.onScreenVisible(true)
        tick()
        runCurrent()
        assertEquals(setOf(pastEstimate.streamKey), viewModel.uiState.value.justEnded.keys)

        // No fresh resolve any more (the cache's entry expired): no check, the verdict stands.
        resolvedAt = null
        tick()
        assertEquals(setOf(pastEstimate.streamKey), viewModel.uiState.value.justEnded.keys)

        // Fresh links: a later resolve. The card is live again at once; its new link is checked, and
        // it answers.
        resolvedAt = 5_000L
        reply = ManifestResponse(200, "#EXTM3U\n#EXTINF:6,\na.ts\n")
        tick()
        runCurrent()
        assertTrue(viewModel.uiState.value.justEnded.isEmpty())
        assertEquals(2, viewModel.uiState.value.countOf(SportsLiveTab.LIVE_NOW))
        assertEquals(2, linkFetches.size)
        viewModel.onScreenVisible(false)
    }

    @Test
    fun `an alive or unclear link, another tab, or a game ESPN follows moves nothing`() = runTest {
        nextLoad = load(listOf(pastEstimate))
        val alive = linkCheckedViewModel(answer = { ManifestResponse(200, "#EXTM3U\n#EXTINF:6,\na.ts\n") })
        alive.onScreenVisible(true)
        tick()
        runCurrent()
        assertEquals(1, linkFetches.size)
        assertTrue(alive.uiState.value.justEnded.isEmpty())
        alive.onScreenVisible(false)

        // Upcoming selected: Live now is not on screen.
        linkFetches.clear()
        val elsewhere = linkCheckedViewModel()
        elsewhere.selectTab(SportsLiveTab.UPCOMING)
        elsewhere.onScreenVisible(true)
        tick()
        assertTrue(linkFetches.isEmpty())
        elsewhere.onScreenVisible(false)

        // A strict ESPN match decides alone, whatever its state: no check.
        val pacers = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA).first { it.eventId == "401909985" }
        val game = item("p", isLive = true, sport = "Basketball", name = "Indiana Pacers at Philadelphia 76ers", startMs = NOW - 4 * HOUR)
        nextLoad = load(listOf(game))
        val followed = linkCheckedViewModel(scores = mapOf(game.streamKey to pacers.copy(state = ScoreState.IN, isFinal = false)))
        followed.onScreenVisible(true)
        tick()
        runCurrent()
        assertTrue(linkFetches.isEmpty())
        assertEquals(listOf("p"), followed.uiState.value.visibleItems.map { it.id })
        followed.onScreenVisible(false)
    }

    private fun load(items: List<SportsLiveItem>, failures: List<SportsLiveFailure> = emptyList()) =
        SportsLiveLoad(items = items, failures = failures, loadedAtMs = NOW, claimedKeys = setOf(CLAIMED.key))

    private fun item(
        id: String,
        isLive: Boolean,
        sport: String?,
        startMs: Long? = null,
        name: String = "Event $id"
    ) = SportsLiveItem(
        meta = MetaPreview(
            id = id,
            type = ContentType.UNKNOWN,
            rawType = "tv",
            name = name,
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
        sport = sport,
        startHasTimeOfDay = startMs != null,
        teams = SportsLiveTeams.parse(name)
    )

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val HOUR = 60 * 60 * 1000L
        const val MINUTE = 60 * 1000L
        val EVENTS = setOf(SportsLiveKind.EVENTS)

        val CLAIMED: ClaimedCatalog = run {
            val catalog = CatalogDescriptor(type = ContentType.fromString("tv"), rawType = "tv", id = "events", name = "Events")
            val addon = Addon(
                id = "sports.addon",
                name = "Sports",
                version = "1.0.0",
                description = null,
                logo = null,
                baseUrl = "https://sports.example",
                catalogs = listOf(catalog),
                types = listOf(catalog.type),
                resources = emptyList()
            )
            ClaimedCatalog(addon, catalog, "sports.addon_tv_events", SportsLiveKind.EVENTS)
        }
    }
}
