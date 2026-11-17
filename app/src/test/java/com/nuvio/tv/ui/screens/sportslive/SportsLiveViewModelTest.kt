package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.sportslive.EndedInputs
import com.nuvio.tv.core.sportslive.EndedSignal
import com.nuvio.tv.core.sportslive.SportsLiveEndedGames
import com.nuvio.tv.core.sportslive.SportsLiveItemBucketer
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
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
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // The ViewModel buckets on Dispatchers.Default; the test dispatcher keeps that deterministic.
    @Before
    fun setUp() {
        mockkStatic(Dispatchers::class)
        every { Dispatchers.Default } returns mainDispatcherRule.dispatcher
    }

    @After
    fun tearDown() {
        unmockkStatic(Dispatchers::class)
    }

    private val claimedFlow = MutableStateFlow(listOf(CLAIMED))
    private val latestFlow = MutableStateFlow<SportsLiveLoad?>(null)
    private val endedFlow = MutableStateFlow<Set<String>>(emptySet())
    private val followedFlow = MutableStateFlow<List<FollowedTeam>>(emptyList())
    private var nextLoad = load(emptyList())
    private val forcedFlags = mutableListOf<Boolean>()
    private val recentlyAlive = mutableSetOf<String>()
    private var clearedEnded = 0
    private val includeChannelsFlags = mutableListOf<Boolean>()

    private val repo = mockk<SportsLiveRepository> {
        every { claimedCatalogs } returns claimedFlow
        every { latest } returns latestFlow
        every { endedKeys } returns endedFlow
        every { followedTeams } returns followedFlow
        every { reprobeIfStale(any()) } just runs
        every { hasRecentLinks(any()) } answers { firstArg<SportsLiveItem>().id in recentlyAlive }
        every { clearEnded() } answers { clearedEnded++; Unit }
        // Stubbed here, before setUp mocks Dispatchers statically (stubbing after that fails).
        coEvery { follow(any()) } coAnswers { followedFlow.value = followedFlow.value + firstArg<FollowedTeam>() }
        coEvery { unfollow(any()) } coAnswers { followedFlow.value = followedFlow.value - firstArg<FollowedTeam>() }
        coEvery { load(any(), any()) } coAnswers {
            forcedFlags += firstArg<Boolean>()
            includeChannelsFlags += secondArg<Boolean>()
            latestFlow.value = nextLoad
            nextLoad
        }
    }

    /** Collects [SportsLiveViewModel.uiState] in the background, as the screen would, so WhileSubscribed stays active. */
    private fun TestScope.newViewModel(
        scores: Map<String, ScoreSnapshot> = emptyMap(),
        canEstimate: (SportsLiveItem) -> Boolean = { true }
    ): SportsLiveViewModel {
        val viewModel = SportsLiveViewModel(repo, endedInputs = flowOf(EndedInputs(scores, canEstimate)), endedGames = SportsLiveEndedGames())
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
        assertEquals(listOf("Basketball", "Soccer"), state.filters)
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

        viewModel.selectFilter("Soccer")
        runCurrent()

        val filtered = viewModel.uiState.value
        assertEquals("Soccer", filtered.selectedFilter)
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
        viewModel.selectFilter("Golf")
        runCurrent()
        assertEquals("Golf", viewModel.uiState.value.selectedFilter)

        viewModel.selectTab(SportsLiveTab.UPCOMING)
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(SportsLiveTab.UPCOMING, state.selectedTab)
        assertNull(state.selectedFilter)
        assertEquals(listOf("soon", "mid", "late"), state.visibleItems.map { it.id })
        assertEquals(listOf("Soccer", "Tennis"), state.filters)
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
    fun `becoming visible loads at once then every 60 seconds only while visible`() = runTest {
        nextLoad = load(listOf(item("a", isLive = true, sport = "Soccer")))
        val viewModel = newViewModel()
        runCurrent()
        assertEquals(listOf(false), forcedFlags)

        viewModel.onScreenVisible(true)
        viewModel.onScreenVisible(true) // a second call must not start a second loop
        runCurrent()
        // The resume load is non-forced (served from the repository's cache when fresh).
        assertEquals(listOf(false, false), forcedFlags)
        advanceTimeBy(61_000)
        assertEquals(listOf(false, false, true), forcedFlags)
        // Only the first load includes channels catalogs; becoming visible and the 60 s reloads carry them over.
        assertEquals(listOf(true, false, false), includeChannelsFlags)

        viewModel.onScreenVisible(false)
        advanceTimeBy(120_000)
        assertEquals(3, forcedFlags.size)
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

        assertEquals(listOf("Soccer", "Tennis"), viewModel.uiState.value.filters)
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
    fun `a filter that leaves the list is cleared and does not come back`() = runTest {
        nextLoad = load(listOf(channel("uk1", "UK"), channel("ca1", "CA"), channel("us1", "US")))
        val viewModel = newViewModel()
        advanceUntilIdle()
        viewModel.selectTab(SportsLiveTab.CHANNELS)
        viewModel.selectFilter("UK")
        runCurrent()
        assertEquals("UK", viewModel.uiState.value.selectedFilter)

        latestFlow.value = load(listOf(channel("ca1", "CA"), channel("us1", "US")))
        runCurrent()
        assertNull(viewModel.uiState.value.selectedFilter)
        assertEquals(listOf("ca1", "us1"), viewModel.uiState.value.visibleItems.map { it.id })

        latestFlow.value = load(listOf(channel("uk1", "UK"), channel("ca1", "CA"), channel("us1", "US")))
        runCurrent()
        assertNull(viewModel.uiState.value.selectedFilter)
        assertEquals(3, viewModel.uiState.value.visibleItems.size)
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
        val viewModel = SportsLiveViewModel(repo)
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
        assertEquals(listOf(my, "Basketball", "Soccer"), viewModel.uiState.value.filters)

        viewModel.selectFilter(my)
        runCurrent()
        assertEquals(my, viewModel.uiState.value.selectedFilter)
        assertEquals(listOf("b"), viewModel.uiState.value.visibleItems.map { it.id })

        // No followed game on Upcoming, and never on Channels.
        viewModel.selectTab(SportsLiveTab.UPCOMING)
        runCurrent()
        assertEquals(listOf("Soccer"), viewModel.uiState.value.filters)
        viewModel.selectTab(SportsLiveTab.CHANNELS)
        runCurrent()
        assertEquals(listOf("UK", "US"), viewModel.uiState.value.filters)

        // Unfollowing while the filter is selected clears it.
        viewModel.selectTab(SportsLiveTab.LIVE_NOW)
        viewModel.selectFilter(my)
        runCurrent()
        followedFlow.value = emptyList()
        runCurrent()
        assertNull(viewModel.uiState.value.selectedFilter)
        assertEquals(listOf("Basketball", "Soccer"), viewModel.uiState.value.filters)
        assertEquals(listOf("s", "a", "b"), viewModel.uiState.value.visibleItems.map { it.id })
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
        val viewModel = SportsLiveViewModel(repo, previewResolve = { SportsLivePreviewLookup.Found(stream) }, hoverPreviewEnabled = enabled)
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

        viewModel.selectFilter("Soccer")
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
            miniPlayerActive = mini
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
