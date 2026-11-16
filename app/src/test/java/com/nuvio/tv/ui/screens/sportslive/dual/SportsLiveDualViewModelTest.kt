package com.nuvio.tv.ui.screens.sportslive.dual

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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

@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveDualViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val a = dualItem("a")
    private val b = dualItem("b")
    private val c = dualItem("c")
    private val d = dualItem("d")
    private val items = listOf(a, b, c, d)

    private val pending = mutableMapOf<String, CompletableDeferred<Stream?>>()
    private val resolveCalls = mutableListOf<String>()
    private val cancelled = mutableListOf<String>()
    private var ended = emptySet<String>()

    @After
    fun tearDown() {
        SportsLiveDualSessions.clear()
    }

    private fun newViewModel(left: SportsLiveItem = a, right: SportsLiveItem = b) = SportsLiveDualViewModel(
        resolve = { item ->
            resolveCalls += item.id
            val deferred = pending.getOrPut(item.id) { CompletableDeferred() }
            try {
                deferred.await()
            } catch (e: CancellationException) {
                cancelled += item.id
                throw e
            }
        },
        endedKeys = { ended },
        session = SportsLiveDualSession(left, right, items)
    )

    private fun answer(id: String, stream: Stream?) {
        pending.getOrPut(id) { CompletableDeferred() }.complete(stream)
    }

    private fun SportsLiveDualViewModel.current(): DualState = state.value!!

    @Test
    fun `both panes are resolved at start`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        runCurrent()
        assertEquals(listOf("a", "b"), resolveCalls)

        val streamA = dualStream("a")
        answer("a", streamA)
        runCurrent()
        assertEquals(DualPane(a, streamA, PaneStatus.Loading), viewModel.current().left)
        assertEquals(DualPane(b), viewModel.current().right)

        viewModel.onFirstFrame(a)
        assertEquals(PaneStatus.Playing, viewModel.current().left.status)
    }

    @Test
    fun `no session gives no state`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = SportsLiveDualViewModel(resolve = { null }, endedKeys = { emptySet() }, session = null)
        assertNull(viewModel.state.value)
        assertFalse(viewModel.zap(forward = true))
    }

    @Test
    fun `a null resolve is an error`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        runCurrent()
        answer("b", null)
        runCurrent()
        assertEquals(PaneStatus.Error, viewModel.current().right.status)
        assertEquals(PaneStatus.Loading, viewModel.current().left.status)
    }

    @Test
    fun `zap cancels only its own pane's lookup`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        runCurrent()

        assertTrue(viewModel.zap(forward = true))
        runCurrent()

        assertEquals(listOf("a"), cancelled)
        assertEquals(listOf("a", "b", "c"), resolveCalls)
        assertEquals(c, viewModel.current().left.item)
        assertEquals(PaneStatus.Loading, viewModel.current().left.status)

        // The other pane's lookup still runs and lands.
        val streamB = dualStream("b")
        answer("b", streamB)
        runCurrent()
        assertEquals(streamB, viewModel.current().right.stream)
    }

    @Test
    fun `zap skips the other pane's game and ended games`() = runTest(mainDispatcherRule.dispatcher) {
        ended = setOf(c.streamKey)
        val viewModel = newViewModel()
        runCurrent()

        // From a: b is in the other pane, c has ended.
        assertTrue(viewModel.zap(forward = true))
        assertEquals(d, viewModel.current().left.item)

        // Backward from d: c has ended, b is in the other pane, so a.
        assertTrue(viewModel.zap(forward = false))
        assertEquals(a, viewModel.current().left.item)
    }

    @Test
    fun `zap moves the highlighted pane`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        runCurrent()
        viewModel.highlight(Side.RIGHT)
        assertTrue(viewModel.zap(forward = false))
        // Backward from b: a is in the other pane, so d.
        assertEquals(d, viewModel.current().right.item)
        assertEquals(a, viewModel.current().left.item)
    }

    @Test
    fun `zap with no other game left does nothing`() = runTest(mainDispatcherRule.dispatcher) {
        ended = setOf(c.streamKey, d.streamKey)
        val viewModel = newViewModel()
        runCurrent()
        assertFalse(viewModel.zap(forward = true))
        assertEquals(a, viewModel.current().left.item)
        assertEquals(emptyList<String>(), cancelled)
    }

    @Test
    fun `a stale result for a game zapped away is ignored`() = runTest(mainDispatcherRule.dispatcher) {
        // The player of the game zapped away from reports late; the pane now on c ignores it.
        val viewModel = newViewModel()
        runCurrent()
        answer("a", dualStream("a"))
        runCurrent()
        viewModel.zap(forward = true)
        runCurrent()
        val zapped = viewModel.current()

        viewModel.onFirstFrame(a)
        viewModel.onPlayerError(a, decoderInit = false)
        assertEquals(zapped, viewModel.current())
        assertEquals(c, zapped.left.item)
    }

    @Test
    fun `swap keeps each pane's lookup with its game`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        runCurrent()
        viewModel.swap()
        assertEquals(b, viewModel.current().left.item)
        assertEquals(Side.RIGHT, viewModel.current().highlighted)

        // The highlight followed a to the right; zapping it cancels a's lookup, not b's.
        assertTrue(viewModel.zap(forward = true))
        runCurrent()
        assertEquals(listOf("a"), cancelled)
        assertEquals(c, viewModel.current().right.item)
        assertEquals(b, viewModel.current().left.item)
    }

    @Test
    fun `no first frame in time is an error`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        runCurrent()
        answer("a", dualStream("a"))
        runCurrent()
        advanceTimeBy(SportsLiveDualViewModel.FIRST_FRAME_TIMEOUT_MS - 1)
        runCurrent()
        assertEquals(PaneStatus.Loading, viewModel.current().left.status)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(PaneStatus.Error, viewModel.current().left.status)
    }

    @Test
    fun `a decoder failure while the other pane plays is the device limit`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        runCurrent()
        answer("a", dualStream("a"))
        answer("b", dualStream("b"))
        runCurrent()
        viewModel.onFirstFrame(a)

        viewModel.onPlayerError(b, decoderInit = true)

        assertEquals(PaneStatus.DeviceLimit, viewModel.current().right.status)
        assertEquals(PaneStatus.Playing, viewModel.current().left.status)
        assertEquals(Side.LEFT, viewModel.current().audibleSide)
    }

    @Test
    fun `a decoder failure with nothing else playing is a stream error`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        runCurrent()
        answer("a", dualStream("a"))
        runCurrent()
        viewModel.onPlayerError(a, decoderInit = true)
        assertEquals(PaneStatus.Error, viewModel.current().left.status)
    }
}
