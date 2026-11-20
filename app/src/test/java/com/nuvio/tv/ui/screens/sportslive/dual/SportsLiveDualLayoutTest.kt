package com.nuvio.tv.ui.screens.sportslive.dual

import androidx.compose.ui.unit.dp
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.sportslive.alerts.AlertSpot
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.screens.sportslive.alertCardOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** #123: split and big + small, the one-key swap, MENU held, the profile's layout, and where the tiles sit. */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveDualLayoutTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val a = dualItem("a")
    private val b = dualItem("b")
    private val initial = DualState(DualPane(a), DualPane(b))

    @After
    fun tearDown() = SportsLiveDualSessions.clear()

    // State

    @Test
    fun `dual screen starts split and a layout change keeps both games, their streams and the sound`() {
        assertEquals(DualLayout.SPLIT, initial.layout)
        val playing = initial.reduce(DualAction.Resolved(a, dualStream("a"))).reduce(DualAction.FirstFrame(a))
            .reduce(DualAction.Highlight(Side.RIGHT))
        val big = playing.reduce(DualAction.SetLayout(DualLayout.BIG_SMALL))
        assertEquals(DualLayout.BIG_SMALL, big.layout)
        assertEquals(playing.left, big.left)
        assertEquals(playing.right, big.right)
        assertEquals(Side.RIGHT, big.highlighted)
        assertEquals(DualLayout.SPLIT, big.reduce(DualAction.SetLayout(DualLayout.SPLIT)).layout)
        assertEquals(DualLayout.SPLIT, DualLayout.BIG_SMALL.other)
        assertEquals(DualLayout.BIG_SMALL, DualLayout.SPLIT.other)
    }

    @Test
    fun `in big and small the swap puts the other game large and the sound follows the large pane`() {
        val big = initial.reduce(DualAction.SetLayout(DualLayout.BIG_SMALL))
        val swapped = big.reduce(DualAction.Swap)
        assertEquals(b, swapped.left.item)
        assertEquals(a, swapped.right.item)
        assertEquals(Side.LEFT, swapped.highlighted)
        assertEquals(b, swapped.pane(swapped.audibleSide).item)
        assertEquals(DualLayout.BIG_SMALL, swapped.layout)
        // Swapping back returns the first game to the large pane, with the sound.
        val back = swapped.reduce(DualAction.Swap)
        assertEquals(a, back.left.item)
        assertEquals(Side.LEFT, back.highlighted)
    }

    @Test
    fun `in big and small the viewer can still move the sound to the small pane`() {
        val big = initial.reduce(DualAction.SetLayout(DualLayout.BIG_SMALL)).reduce(DualAction.Highlight(Side.RIGHT))
        assertEquals(Side.RIGHT, big.audibleSide)
        assertEquals(b, big.pane(big.audibleSide).item)
        // Swapping that game in large keeps the sound on it.
        val swapped = big.reduce(DualAction.Swap)
        assertEquals(b, swapped.pane(swapped.audibleSide).item)
        assertEquals(Side.LEFT, swapped.audibleSide)
    }

    @Test
    fun `split swap still keeps the highlight on the same game`() {
        val swapped = initial.reduce(DualAction.Swap)
        assertEquals(Side.RIGHT, swapped.highlighted)
        assertEquals(a, swapped.pane(swapped.highlighted).item)
    }

    @Test
    fun `the game info side is the side with sound, also when the highlighted pane failed`() {
        assertEquals(Side.LEFT, initial.infoSide)
        assertEquals(Side.RIGHT, initial.reduce(DualAction.Highlight(Side.RIGHT)).infoSide)
        // Right highlighted but failed while the left plays: the left game is the one heard, and shown.
        val leftPlaying = initial.reduce(DualAction.Resolved(a, dualStream("a"))).reduce(DualAction.FirstFrame(a))
        val rightFailed = leftPlaying.reduce(DualAction.Resolved(b, null)).reduce(DualAction.Highlight(Side.RIGHT))
        assertEquals(PaneStatus.Error, rightFailed.right.status)
        assertEquals(Side.LEFT, rightFailed.infoSide)
        // Big and small: after a swap the large game is the one shown.
        val big = initial.reduce(DualAction.SetLayout(DualLayout.BIG_SMALL)).reduce(DualAction.Swap)
        assertEquals(b, big.pane(big.infoSide).item)
    }

    // MENU

    @Test
    fun `a short MENU press swaps on release`() {
        val key = DualMenuKey()
        assertNull(key.onDown(0))
        assertEquals(DualMenuAction.SWAP, key.onUp())
    }

    @Test
    fun `MENU held changes the layout once and its release does not swap`() {
        val key = DualMenuKey()
        assertNull(key.onDown(0))
        assertEquals(DualMenuAction.TOGGLE_LAYOUT, key.onDown(1))
        assertNull(key.onDown(2))
        assertNull(key.onDown(3))
        assertNull(key.onUp())
        // The next short press swaps again.
        assertNull(key.onDown(0))
        assertEquals(DualMenuAction.SWAP, key.onUp())
    }

    @Test
    fun `a MENU release or repeat whose press went elsewhere does nothing`() {
        val key = DualMenuKey()
        assertNull(key.onUp())
        assertNull(key.onDown(4))
        assertNull(key.onUp())
    }

    // Geometry (the 1080p TV: 960 x 540 dp)

    @Test
    fun `split tiles are 912 px wide, 32 px apart, and a little above the middle`() {
        val left = DualGeometry.tile(Side.LEFT, DualLayout.SPLIT, 960.dp, 540.dp)
        val right = DualGeometry.tile(Side.RIGHT, DualLayout.SPLIT, 960.dp, 540.dp)
        assertEquals(456f, left.width.value, 0.01f)
        assertEquals(456f, right.width.value, 0.01f)
        assertEquals(16f, (right.start - left.end).value, 0.01f)
        assertEquals(16f, left.start.value, 0.01f)
        assertEquals(960f, (right.end + 16.dp).value, 0.01f)
        assertEquals(left.top, right.top)
        assertTrue(left.top.value < (540f - left.height.value) / 2f)
        // The strip and the game info overlay fit under the tiles.
        assertTrue((left.bottom + 32.dp + 84.dp).value <= 540f)
    }

    @Test
    fun `big and small tiles are three quarters and one quarter, tops aligned, never overlapping`() {
        val big = DualGeometry.tile(Side.LEFT, DualLayout.BIG_SMALL, 960.dp, 540.dp)
        val small = DualGeometry.tile(Side.RIGHT, DualLayout.BIG_SMALL, 960.dp, 540.dp)
        assertEquals(672f, big.width.value, 0.01f)
        assertEquals(224f, small.width.value, 0.01f)
        assertEquals(big.top, small.top)
        assertEquals(16f, (small.start - big.end).value, 0.01f)
        assertEquals(936f, small.end.value, 0.01f)
        assertTrue((big.bottom + 32.dp).value <= 540f)
        // Room under the small tile's strip for the game info overlay.
        assertTrue((big.bottom - small.bottom - 32.dp).value > 150f)
    }

    @Test
    fun `alert cards sit in the other pane's corner in split, and under the small tile in big and small`() {
        // Split: as before, the top right corner of the pane the card is not about.
        val right = DualGeometry.tile(Side.RIGHT, DualLayout.SPLIT, 960.dp, 540.dp)
        val (endRight, topRight) = alertCardOffset(AlertSpot.RIGHT_PANE, 960.dp, 540.dp)
        assertEquals(28f, endRight.value, 0.01f)
        assertEquals((right.top + 12.dp).value, topRight.value, 0.01f)
        val (endLeft, _) = alertCardOffset(AlertSpot.LEFT_PANE, 960.dp, 540.dp)
        assertEquals((960.dp - 16.dp - 456.dp + 12.dp).value, endLeft.value, 0.01f)
        // Big and small: below the corner tile and its strip, whichever game the card is about.
        val small = DualGeometry.tile(Side.RIGHT, DualLayout.BIG_SMALL, 960.dp, 540.dp)
        listOf(AlertSpot.LEFT_PANE, AlertSpot.RIGHT_PANE).forEach { spot ->
            val (end, top) = alertCardOffset(spot, 960.dp, 540.dp, bigSmall = true)
            assertEquals(24f, end.value, 0.01f)
            assertEquals((small.bottom + 32.dp + 12.dp).value, top.value, 0.01f)
        }
        // The whole screen's spot does not move.
        assertEquals(alertCardOffset(AlertSpot.SCREEN, 960.dp, 540.dp), alertCardOffset(AlertSpot.SCREEN, 960.dp, 540.dp, bigSmall = true))
    }

    // The profile's layout

    private fun newViewModel(
        loadLayout: suspend () -> DualLayout?,
        saved: MutableList<DualLayout> = mutableListOf()
    ) = SportsLiveDualViewModel(
        resolve = { CompletableDeferred<Stream?>().await() },
        endedKeys = { emptySet() },
        session = SportsLiveDualSession(a, b, listOf(a, b), null, SportsLiveTab.LIVE_NOW),
        loadLayout = loadLayout,
        saveLayout = { saved += it }
    )

    @Test
    fun `dual screen opens in the profile's saved layout`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel(loadLayout = { DualLayout.BIG_SMALL })
        runCurrent()
        assertEquals(DualLayout.BIG_SMALL, viewModel.state.value!!.layout)
        val never = newViewModel(loadLayout = { null })
        runCurrent()
        assertEquals(DualLayout.SPLIT, never.state.value!!.layout)
    }

    @Test
    fun `the panes wait for the saved layout so a pane view is never laid out at the wrong tile first`() =
        runTest(mainDispatcherRule.dispatcher) {
            val read = CompletableDeferred<DualLayout?>()
            val viewModel = newViewModel(loadLayout = { read.await() })
            runCurrent()
            assertFalse(viewModel.layoutReady.value)
            read.complete(DualLayout.BIG_SMALL)
            runCurrent()
            assertTrue(viewModel.layoutReady.value)
            assertEquals(DualLayout.BIG_SMALL, viewModel.state.value!!.layout)
        }

    @Test
    fun `the panes show split when there is no saved layout or it cannot be read`() = runTest(mainDispatcherRule.dispatcher) {
        val never = newViewModel(loadLayout = { null })
        val failed = newViewModel(loadLayout = { error("no preferences") })
        runCurrent()
        listOf(never, failed).forEach { viewModel ->
            assertTrue(viewModel.layoutReady.value)
            assertEquals(DualLayout.SPLIT, viewModel.state.value!!.layout)
        }
    }

    @Test
    fun `MENU held before the saved layout is read shows the panes in the chosen layout`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel(loadLayout = { CompletableDeferred<DualLayout?>().await() })
        runCurrent()
        viewModel.toggleLayout()
        assertTrue(viewModel.layoutReady.value)
        assertEquals(DualLayout.BIG_SMALL, viewModel.state.value!!.layout)
    }

    @Test
    fun `MENU held changes the layout and saves it for the profile`() = runTest(mainDispatcherRule.dispatcher) {
        val saved = mutableListOf<DualLayout>()
        val viewModel = newViewModel(loadLayout = { null }, saved = saved)
        runCurrent()
        viewModel.toggleLayout()
        runCurrent()
        assertEquals(DualLayout.BIG_SMALL, viewModel.state.value!!.layout)
        viewModel.toggleLayout()
        runCurrent()
        assertEquals(DualLayout.SPLIT, viewModel.state.value!!.layout)
        assertEquals(listOf(DualLayout.BIG_SMALL, DualLayout.SPLIT), saved)
    }

    @Test
    fun `a layout chosen before the saved one is read is not undone by it`() = runTest(mainDispatcherRule.dispatcher) {
        val read = CompletableDeferred<DualLayout?>()
        val viewModel = newViewModel(loadLayout = { read.await() })
        runCurrent()
        viewModel.toggleLayout()
        read.complete(DualLayout.SPLIT)
        runCurrent()
        assertEquals(DualLayout.BIG_SMALL, viewModel.state.value!!.layout)
    }

    @Test
    fun `the view model's swap in big and small puts the other game large with the sound`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel(loadLayout = { DualLayout.BIG_SMALL })
        runCurrent()
        viewModel.swap()
        val state = viewModel.state.value!!
        assertEquals(b, state.left.item)
        assertEquals(a, state.right.item)
        assertEquals(Side.LEFT, state.highlighted)
        assertEquals(DualLayout.BIG_SMALL, state.layout)
    }
}
