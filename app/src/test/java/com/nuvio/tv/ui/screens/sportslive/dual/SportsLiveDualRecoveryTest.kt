package com.nuvio.tv.ui.screens.sportslive.dual

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.sportslive.SportsLiveRecoveryBudget
import com.nuvio.tv.core.sportslive.SportsLiveStreamHealth.Problem
import com.nuvio.tv.core.sportslive.SportsLiveWorkingLinks
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** #138: a dual pane whose stream goes bad mid-game recovers by itself, without touching the other pane. */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveDualRecoveryTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val a = dualItem("a")
    private val b = dualItem("b")
    private val c = dualItem("c")
    private val items = listOf(a, b, c)

    private var now = 0L
    private val links = SportsLiveWorkingLinks(nowMs = { now })
    private val budget = SportsLiveRecoveryBudget(nowMs = { now })

    private val streamA = dualStream("a")
    private val streamA2 = dualStream("a2")
    private val streamA3 = dualStream("a3")
    private val streamB = dualStream("b")

    /** What the recovery lookup answers for a game, in turn; and what it was asked to skip. */
    private val nextLinks = mutableMapOf<String, ArrayDeque<Stream>>()
    private val lookups = mutableListOf<Pair<String, Set<String>>>()

    @After
    fun tearDown() {
        SportsLiveDualSessions.clear()
    }

    private fun newViewModel() = SportsLiveDualViewModel(
        resolve = { item -> if (item.id == "a") streamA else streamB },
        endedKeys = { emptySet() },
        session = SportsLiveDualSession(a, b, items, null, SportsLiveTab.LIVE_NOW),
        workingLinks = links,
        recoveryLookup = { item, tried ->
            lookups += item.id to tried
            nextLinks[item.id]?.removeFirstOrNull()
        },
        recoveryBudget = budget
    )

    private fun SportsLiveDualViewModel.current(): DualState = state.value!!

    /** Both panes play. */
    private fun SportsLiveDualViewModel.playing(): SportsLiveDualViewModel {
        onFirstFrame(a)
        onFirstFrame(b)
        return this
    }

    @Test
    fun `a frozen pane is first loaded again as is, and the other pane is untouched`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        runCurrent()
        viewModel.playing()
        val right = viewModel.current().right

        viewModel.onUnhealthy(a, Problem.FROZEN)

        val left = viewModel.current().left
        assertEquals(streamA, left.stream)
        assertEquals(PaneStatus.Playing, left.status)
        assertTrue(left.reconnecting)
        assertEquals(1, left.attempt)
        assertEquals(right, viewModel.current().right)
        assertTrue(lookups.isEmpty())

        // It moves again: the chip goes.
        viewModel.onMoving(a)
        assertFalse(viewModel.current().left.reconnecting)
    }

    @Test
    fun `a second failure moves to the next link, skipping the failed one`() = runTest(mainDispatcherRule.dispatcher) {
        nextLinks["a"] = ArrayDeque(listOf(streamA2))
        val viewModel = newViewModel()
        runCurrent()
        viewModel.playing()

        viewModel.onUnhealthy(a, Problem.FROZEN)
        viewModel.onUnhealthy(a, Problem.FROZEN)
        runCurrent()

        assertEquals(listOf("a" to setOf(SportsLiveWorkingLinks.linkKey(streamA.url)!!)), lookups)
        val left = viewModel.current().left
        assertEquals(streamA2, left.stream)
        assertEquals(PaneStatus.Loading, left.status)
        assertTrue(left.reconnecting)
        assertEquals(PaneStatus.Playing, viewModel.current().right.status)

        viewModel.onFirstFrame(a)
        assertEquals(PaneStatus.Playing, viewModel.current().left.status)
        assertFalse(viewModel.current().left.reconnecting)
    }

    @Test
    fun `an error burst goes straight to the next link`() = runTest(mainDispatcherRule.dispatcher) {
        nextLinks["a"] = ArrayDeque(listOf(streamA2))
        val viewModel = newViewModel()
        runCurrent()
        viewModel.playing()

        viewModel.onPlayerError(a, decoderInit = false, problem = Problem.ERROR_BURST)
        runCurrent()
        assertEquals(streamA2, viewModel.current().left.stream)
    }

    @Test
    fun `the choices show only once every link failed`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        runCurrent()
        viewModel.playing()

        viewModel.onUnhealthy(a, Problem.FROZEN)
        assertTrue(viewModel.current().left.reconnecting)
        assertEquals(PaneStatus.Playing, viewModel.current().left.status)
        assertNull(viewModel.closeFailedSide())

        // Nothing else to switch to.
        viewModel.onUnhealthy(a, Problem.FROZEN)
        runCurrent()
        assertEquals(PaneStatus.Error, viewModel.current().left.status)
        assertFalse(viewModel.current().left.reconnecting)
        assertEquals(PaneStatus.Playing, viewModel.current().right.status)
        // Close this side: the other pane's game plays full screen.
        assertEquals(b to streamB, viewModel.closeFailedSide())
    }

    @Test
    fun `a link a recovery tried that does not start goes on to the next one`() = runTest(mainDispatcherRule.dispatcher) {
        nextLinks["a"] = ArrayDeque(listOf(streamA2, streamA3))
        val viewModel = newViewModel()
        runCurrent()
        viewModel.playing()

        viewModel.onUnhealthy(a, Problem.ERROR_BURST)
        runCurrent()
        assertEquals(streamA2, viewModel.current().left.stream)

        // Its start-up error is retried once as is, then the next link is tried.
        viewModel.onPlayerError(a, decoderInit = false)
        assertEquals(streamA2, viewModel.current().left.stream)
        assertEquals(1, viewModel.current().left.attempt)
        advanceTimeBy(SportsLiveDualViewModel.FIRST_FRAME_TIMEOUT_MS + 1)
        runCurrent()
        assertEquals(streamA3, viewModel.current().left.stream)
        assertTrue(viewModel.current().left.reconnecting)
        assertEquals(
            setOf(SportsLiveWorkingLinks.linkKey(streamA.url)!!, SportsLiveWorkingLinks.linkKey(streamA2.url)!!),
            lookups.last().second
        )

        // Three recoveries spent: the next failure offers the choices.
        advanceTimeBy(SportsLiveDualViewModel.FIRST_FRAME_TIMEOUT_MS + 1)
        runCurrent()
        assertEquals(PaneStatus.Error, viewModel.current().left.status)
        assertFalse(viewModel.current().left.reconnecting)
    }

    @Test
    fun `at most three recoveries per game in ten minutes`() = runTest(mainDispatcherRule.dispatcher) {
        nextLinks["a"] = ArrayDeque(listOf(streamA2, streamA3))
        val viewModel = newViewModel()
        runCurrent()
        viewModel.playing()

        viewModel.onUnhealthy(a, Problem.FROZEN) // 1: retry as is
        viewModel.onMoving(a)
        viewModel.onUnhealthy(a, Problem.FROZEN) // 2: next link
        runCurrent()
        viewModel.onFirstFrame(a)
        viewModel.onUnhealthy(a, Problem.FROZEN) // 3: retry the new link as is
        viewModel.onMoving(a)
        assertEquals(PaneStatus.Playing, viewModel.current().left.status)

        viewModel.onUnhealthy(a, Problem.FROZEN) // 4: the choices
        assertEquals(PaneStatus.Error, viewModel.current().left.status)
        assertEquals(listOf(streamA3), nextLinks["a"]!!.toList())
    }

    @Test
    fun `a frozen link is marked failed in the working-links memory`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        runCurrent()
        viewModel.playing()

        viewModel.onUnhealthy(a, Problem.FROZEN)
        assertTrue(links.isQuarantined(a.id, streamA.url))
        assertFalse(links.isQuarantined(b.id, streamB.url))
    }

    @Test
    fun `a known good link survives one freeze and is quarantined on the second`() = runTest(mainDispatcherRule.dispatcher) {
        links.markGood(a.id, streamA.url)
        val viewModel = newViewModel()
        runCurrent()
        viewModel.playing()

        viewModel.onUnhealthy(a, Problem.FROZEN)
        assertFalse(links.isQuarantined(a.id, streamA.url))
        viewModel.onMoving(a)
        now += 60_000
        viewModel.onUnhealthy(a, Problem.FROZEN)
        assertTrue(links.isQuarantined(a.id, streamA.url))
    }

    @Test
    fun `a start-up error moves to the next link, and with none left the pane offers the choices`() = runTest(mainDispatcherRule.dispatcher) {
        nextLinks["a"] = ArrayDeque(listOf(streamA2))
        val viewModel = newViewModel()
        runCurrent()
        viewModel.onPlayerError(a, decoderInit = false)
        runCurrent()
        assertEquals(streamA2, viewModel.current().left.stream)
        assertEquals(0, viewModel.current().left.attempt)

        // The new link's start error is retried once as is, then nothing is left: the choices.
        viewModel.onPlayerError(a, decoderInit = false)
        assertEquals(1, viewModel.current().left.attempt)
        viewModel.onPlayerError(a, decoderInit = false)
        runCurrent()
        assertEquals(PaneStatus.Error, viewModel.current().left.status)
        assertEquals(2, lookups.size)
    }

    @Test
    fun `next live game moves the failed pane to another game, never the other pane's`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        runCurrent()
        viewModel.playing()
        viewModel.onUnhealthy(a, Problem.FROZEN)
        viewModel.onUnhealthy(a, Problem.FROZEN)
        runCurrent()
        assertEquals(PaneStatus.Error, viewModel.current().left.status)

        assertTrue(viewModel.nextLiveGame())
        val left: SportsLiveItem = viewModel.current().left.item
        assertEquals(c, left)
        assertEquals(b, viewModel.current().right.item)
    }

    // --- Start-up (#138) ---

    @Test
    fun `a pane with no first frame in 15 s moves on to the next link`() = runTest(mainDispatcherRule.dispatcher) {
        nextLinks["a"] = ArrayDeque(listOf(streamA2))
        val viewModel = newViewModel()
        runCurrent()

        advanceTimeBy(SportsLiveDualViewModel.FIRST_FRAME_TIMEOUT_MS - 1)
        runCurrent()
        assertEquals(streamA, viewModel.current().left.stream)
        advanceTimeBy(2)
        runCurrent()

        val left = viewModel.current().left
        assertEquals(streamA2, left.stream)
        assertEquals(PaneStatus.Loading, left.status)
        assertEquals(2, left.linkNumber)
        assertTrue(links.isQuarantined(a.id, streamA.url))
    }

    @Test
    fun `a pane whose request failed moves on after 8 s`() = runTest(mainDispatcherRule.dispatcher) {
        nextLinks["a"] = ArrayDeque(listOf(streamA2))
        val viewModel = newViewModel()
        runCurrent()

        advanceTimeBy(2_000)
        viewModel.onRequestFailed(a)
        advanceTimeBy(5_999)
        runCurrent()
        assertEquals(streamA, viewModel.current().left.stream)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(streamA2, viewModel.current().left.stream)
        // The other pane keeps its full start-up time.
        assertEquals(streamB, viewModel.current().right.stream)
        assertEquals(PaneStatus.Loading, viewModel.current().right.status)
    }

    @Test
    fun `a start-up timeout is a ladder step and spends the game's budget`() = runTest(mainDispatcherRule.dispatcher) {
        nextLinks["a"] = ArrayDeque(listOf(streamA2, streamA3, dualStream("a4")))
        val viewModel = newViewModel()
        runCurrent()

        repeat(3) {
            advanceTimeBy(SportsLiveDualViewModel.FIRST_FRAME_TIMEOUT_MS + 1)
            runCurrent()
        }
        assertEquals(dualStream("a4"), viewModel.current().left.stream)
        assertEquals(PaneStatus.Loading, viewModel.current().left.status)
        // Three recoveries spent: the fourth timeout offers the choices.
        advanceTimeBy(SportsLiveDualViewModel.FIRST_FRAME_TIMEOUT_MS + 1)
        runCurrent()
        assertEquals(dualStream("a4"), viewModel.current().left.stream)
        assertEquals(PaneStatus.Error, viewModel.current().left.status)
    }

    @Test
    fun `try next link leaves the starting link behind without holding it against it`() = runTest(mainDispatcherRule.dispatcher) {
        nextLinks["a"] = ArrayDeque(listOf(streamA2))
        val viewModel = newViewModel()
        runCurrent()
        viewModel.highlight(Side.LEFT)

        assertTrue(viewModel.tryNextLink())
        runCurrent()

        assertEquals(streamA2, viewModel.current().left.stream)
        assertFalse(links.isQuarantined(a.id, streamA.url))
        assertEquals(setOf(SportsLiveWorkingLinks.linkKey(streamA.url)!!), lookups.last().second)
        // The other pane is untouched.
        assertEquals(streamB, viewModel.current().right.stream)
    }

    @Test
    fun `a late first frame of a cancelled start does not win`() = runTest(mainDispatcherRule.dispatcher) {
        nextLinks["a"] = ArrayDeque(listOf(streamA2))
        val viewModel = newViewModel()
        runCurrent()
        viewModel.highlight(Side.LEFT)
        viewModel.tryNextLink()
        runCurrent()

        // The old link's first frame and error arrive after the pane moved on: both are ignored.
        viewModel.onFirstFrame(a, streamA)
        viewModel.onPlayerError(a, decoderInit = false, stream = streamA)
        assertEquals(PaneStatus.Loading, viewModel.current().left.status)
        assertEquals(streamA2, viewModel.current().left.stream)

        viewModel.onFirstFrame(a, streamA2)
        assertEquals(PaneStatus.Playing, viewModel.current().left.status)
    }

    @Test
    fun `try next link does nothing on a playing pane`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        runCurrent()
        viewModel.playing()
        viewModel.highlight(Side.LEFT)

        assertFalse(viewModel.tryNextLink())
        assertTrue(lookups.isEmpty())
    }

    @Test
    fun `an unknown host at start goes straight to the next link with no retry`() = runTest(mainDispatcherRule.dispatcher) {
        nextLinks["a"] = ArrayDeque(listOf(streamA2))
        val viewModel = newViewModel()
        runCurrent()

        viewModel.onPlayerError(a, decoderInit = false, problem = Problem.DEAD_LINK)
        runCurrent()

        val left = viewModel.current().left
        assertEquals(streamA2, left.stream)
        assertEquals(0, left.attempt)
        assertTrue(links.isQuarantined(a.id, streamA.url))
    }
}
