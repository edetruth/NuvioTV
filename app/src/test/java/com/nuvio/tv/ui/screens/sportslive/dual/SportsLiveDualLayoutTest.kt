package com.nuvio.tv.ui.screens.sportslive.dual

import androidx.compose.ui.unit.dp
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.sportslive.alerts.AlertSpot
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.screens.sportslive.SportsLiveCorner
import com.nuvio.tv.ui.screens.sportslive.alertCardPlace
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
    fun `split tiles are 848 px wide, 32 px apart, inside the safe area and centred with their strips`() {
        val left = DualGeometry.tile(Side.LEFT, DualLayout.SPLIT, 960.dp, 540.dp)
        val right = DualGeometry.tile(Side.RIGHT, DualLayout.SPLIT, 960.dp, 540.dp)
        assertEquals(424f, left.width.value, 0.01f)
        assertEquals(424f, right.width.value, 0.01f)
        assertEquals(16f, (right.start - left.end).value, 0.01f)
        // Design audit A4: 96 px from each side at 1080p (the TV safe area).
        assertEquals(48f, left.start.value, 0.01f)
        assertEquals(960f, (right.end + 48.dp).value, 0.01f)
        assertTrue(left.top.value >= 27f)
        assertEquals(left.top, right.top)
        // F13: the tile and its strip sit in the middle, with no empty band below.
        assertEquals(540f - (left.bottom + 32.dp).value, left.top.value, 0.01f)
        // The strip and the game info overlay fit under the tiles.
        assertTrue((left.bottom + 32.dp + 84.dp).value <= 540f)
    }

    @Test
    fun `big and small tiles are three quarters and one quarter, tops aligned, never overlapping`() {
        val big = DualGeometry.tile(Side.LEFT, DualLayout.BIG_SMALL, 960.dp, 540.dp)
        val small = DualGeometry.tile(Side.RIGHT, DualLayout.BIG_SMALL, 960.dp, 540.dp)
        assertEquals(636f, big.width.value, 0.01f)
        assertEquals(212f, small.width.value, 0.01f)
        assertEquals(big.top, small.top)
        assertEquals(16f, (small.start - big.end).value, 0.01f)
        // Design audit A4: inside the safe area, 96 / 54 px at 1080p.
        assertEquals(48f, big.start.value, 0.01f)
        assertEquals(912f, small.end.value, 0.01f)
        assertTrue(big.top.value >= 27f)
        assertTrue((big.bottom + 32.dp).value <= 540f - 27f)
        // The free column under the small tile: its two-line caption first, then the hints and the game info.
        val column = DualGeometry.smallColumn(DualLayout.BIG_SMALL, 960.dp, 540.dp)!!
        assertEquals(small.start, column.start)
        assertEquals((small.bottom + 56.dp).value, column.top.value, 0.01f)
        assertTrue((column.top + 84.dp + 100.dp).value <= big.bottom.value)
        assertEquals(null, DualGeometry.smallColumn(DualLayout.SPLIT, 960.dp, 540.dp))
        // (live.24 design audit A4) the small tile's game info now lives in smallColumn, checked above.
    }

    @Test
    fun `alert cards share the toasts' bottom left corner, the left tile's over dual screen, the right pane's for the left game`() {
        // live.20: over dual screen the toasts' corner is 12 dp inside the left tile's bottom left.
        val left = DualGeometry.tile(Side.LEFT, DualLayout.SPLIT, 960.dp, 540.dp)
        val dualCorner = DualGeometry.corner(Side.LEFT, DualLayout.SPLIT, 960.dp, 540.dp)
        assertEquals((left.start + 12.dp).value, dualCorner.start.value, 0.01f)
        assertEquals((540.dp - left.bottom + 12.dp).value, dualCorner.bottom.value, 0.01f)
        // A card about the right game (it sits over the left pane), or about neither: the corner, holding it.
        listOf(AlertSpot.LEFT_PANE, AlertSpot.SCREEN).forEach { spot ->
            val place = alertCardPlace(spot, 960.dp, 540.dp, dualCorner)
            assertEquals(dualCorner.start, place.start)
            assertEquals(dualCorner.bottom, place.bottom)
            assertTrue(place.holdsCorner)
        }
        // A card about the left game: the right pane's bottom left, never over its own game, and not the toasts' corner.
        val right = DualGeometry.tile(Side.RIGHT, DualLayout.SPLIT, 960.dp, 540.dp)
        val onRight = alertCardPlace(AlertSpot.RIGHT_PANE, 960.dp, 540.dp, dualCorner)
        assertEquals((right.start + 12.dp).value, onRight.start.value, 0.01f)
        assertEquals((540.dp - right.bottom + 12.dp).value, onRight.bottom.value, 0.01f)
        assertFalse(onRight.holdsCorner)
        // The card fits inside the right tile.
        assertTrue((onRight.start + 340.dp).value <= right.end.value)
        // Big and small: the small tile is narrower than a card, so every card takes the big tile's corner.
        val bigCorner = DualGeometry.corner(Side.LEFT, DualLayout.BIG_SMALL, 960.dp, 540.dp)
        val big = DualGeometry.tile(Side.LEFT, DualLayout.BIG_SMALL, 960.dp, 540.dp)
        assertEquals((540.dp - big.bottom + 12.dp).value, bigCorner.bottom.value, 0.01f)
        listOf(AlertSpot.LEFT_PANE, AlertSpot.RIGHT_PANE).forEach { spot ->
            val place = alertCardPlace(spot, 960.dp, 540.dp, bigCorner, bigSmall = true)
            assertEquals(bigCorner, SportsLiveCorner(place.start, place.bottom))
            assertTrue(place.holdsCorner)
        }
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
    fun `MENU held into big and small puts the game with the sound large`() = runTest(mainDispatcherRule.dispatcher) {
        // F13: the sound was on the right pane; it must not stay on the small one.
        val viewModel = newViewModel(loadLayout = { null })
        runCurrent()
        viewModel.highlight(Side.RIGHT)
        viewModel.toggleLayout()
        val big = viewModel.state.value!!
        assertEquals(DualLayout.BIG_SMALL, big.layout)
        assertEquals(b, big.left.item)
        assertEquals(Side.LEFT, big.audibleSide)
        // Back to split: nothing moves.
        viewModel.toggleLayout()
        assertEquals(b, viewModel.state.value!!.left.item)
        assertEquals(DualLayout.SPLIT, viewModel.state.value!!.layout)
        // The sound on the left already: the panes stay where they are.
        val leftHeard = newViewModel(loadLayout = { null })
        runCurrent()
        leftHeard.toggleLayout()
        assertEquals(a, leftHeard.state.value!!.left.item)
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
