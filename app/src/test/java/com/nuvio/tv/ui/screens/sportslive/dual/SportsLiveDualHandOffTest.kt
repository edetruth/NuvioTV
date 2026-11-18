package com.nuvio.tv.ui.screens.sportslive.dual

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.sportslive.SportsLiveWorkingLinks
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** #65: dual screen entered from the full player keeps the stream playing; panes feed the link memory. */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveDualHandOffTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val a = dualItem("a")
    private val b = dualItem("b")
    private val c = dualItem("c")
    private val items = listOf(a, b, c)

    private var now = 0L
    private val links = SportsLiveWorkingLinks(nowMs = { now })
    private val pending = mutableMapOf<String, CompletableDeferred<Stream?>>()
    private val resolveCalls = mutableListOf<String>()

    /** The full player's stream for [a], with headers; not what a lookup would find. */
    private val playing = dualStream("a-playing")

    @After
    fun tearDown() {
        SportsLiveDualSessions.clear()
    }

    private fun newViewModel(leftStream: Stream? = playing, handOffPlayable: Boolean = true) = SportsLiveDualViewModel(
        resolve = { item ->
            resolveCalls += item.id
            pending.getOrPut(item.id) { CompletableDeferred() }.await()
        },
        endedKeys = { emptySet() },
        session = SportsLiveDualSession(a, b, items, null, SportsLiveTab.LIVE_NOW, leftStream = leftStream),
        handOffPlayable = { handOffPlayable },
        workingLinks = links
    )

    private fun SportsLiveDualViewModel.current(): DualState = state.value!!

    @Test
    fun `the playing game's pane plays the full player's stream with no lookup`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        runCurrent()

        assertEquals(listOf("b"), resolveCalls)
        assertSame(playing, viewModel.current().left.stream)
        assertEquals(PaneStatus.Loading, viewModel.current().left.status)
        viewModel.onFirstFrame(a)
        assertEquals(PaneStatus.Playing, viewModel.current().left.status)
    }

    @Test
    fun `the pick from the player carries the playing stream into the session`() {
        val pick = SportsLiveDualPick(chosen = a, items = items, filter = null, tab = SportsLiveTab.LIVE_NOW, chosenStream = playing)
        assertSame(playing, pick.pairWith(b)?.leftStream)
        // From the section nothing plays: both panes look their stream up.
        assertEquals(null, pick.copy(chosenStream = null).pairWith(b)?.leftStream)
    }

    @Test
    fun `a decoder retry of the handed over pane still does no lookup`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        runCurrent()
        pending.getValue("b").complete(dualStream("b"))
        runCurrent()
        viewModel.onFirstFrame(b)

        viewModel.onPlayerError(a, decoderInit = true)
        advanceTimeBy(SportsLiveDualViewModel.DEVICE_LIMIT_RETRY_MS + 1)
        runCurrent()

        assertEquals(listOf("b"), resolveCalls)
        assertSame(playing, viewModel.current().left.stream)
        assertFalse(links.isQuarantined(a.id, playing.url))
    }

    @Test
    fun `a stream a pane cannot play as is is looked up`() = runTest(mainDispatcherRule.dispatcher) {
        newViewModel(handOffPlayable = false)
        runCurrent()
        assertEquals(listOf("a", "b"), resolveCalls)
    }

    @Test
    fun `a handed over stream that fails is quarantined and the game is looked up next time`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel()
            runCurrent()
            viewModel.onPlayerError(a, decoderInit = false)

            assertEquals(PaneStatus.Error, viewModel.current().left.status)
            assertTrue(links.isQuarantined(a.id, playing.url))

            // Zap away and back: a is looked up now.
            assertTrue(viewModel.zap(forward = true))
            runCurrent()
            assertTrue(viewModel.zap(forward = false))
            runCurrent()
            assertEquals(listOf("b", "c", "a"), resolveCalls)
        }

    @Test
    fun `no first frame in time quarantines the pane's link`() = runTest(mainDispatcherRule.dispatcher) {
        newViewModel()
        runCurrent()
        advanceTimeBy(SportsLiveDualViewModel.FIRST_FRAME_TIMEOUT_MS + 1)
        runCurrent()
        assertTrue(links.isQuarantined(a.id, playing.url))
    }

    @Test
    fun `a pane that played thirty seconds marks its link good when it moves on`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        runCurrent()
        viewModel.onFirstFrame(a)

        now += SportsLiveWorkingLinks.GOOD_AFTER_MS
        assertTrue(viewModel.zap(forward = true))
        assertTrue(links.isGood(a.id, playing.url))
    }

    @Test
    fun `a pane that played less than thirty seconds is not marked good`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        runCurrent()
        viewModel.onFirstFrame(a)

        now += SportsLiveWorkingLinks.GOOD_AFTER_MS - 1
        assertTrue(viewModel.zap(forward = true))
        assertFalse(links.isGood(a.id, playing.url))
    }
}
