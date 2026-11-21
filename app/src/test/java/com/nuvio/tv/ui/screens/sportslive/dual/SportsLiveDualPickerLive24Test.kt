package com.nuvio.tv.ui.screens.sportslive.dual

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.domain.model.SportsLiveTab
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * live.24 (user TV report 2026-11-21 14:20): "Pick another to watch" listed only the 3 games of the filter dual screen
 * opened under. It lists every live game of My sports and followed teams now, in importance order; and the keys hint
 * shows on the first three entries only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveDualPickerLive24Test {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val a = dualItem("a")
    private val b = dualItem("b")
    private val c = dualItem("c")
    private val followed = dualItem("followed").copy(followed = true)
    private val premier = dualItem("premier")
    private val college = dualItem("college")
    private val over = dualItem("over")
    private val noStream = dualItem("nostream")

    @After
    fun tearDown() {
        SportsLiveDualSessions.clear()
    }

    @Test
    fun `candidates keep the ranked order, each game once, never one on screen or over`() {
        val ranked = listOf(followed, a, premier, college, over, noStream)
        val merged = c.copy(alternates = listOf(premier))
        val picked = dualPickerCandidates(
            pool = ranked + listOf(premier, merged),
            onScreen = a.sourceKeys() + b.sourceKeys(),
            over = setOf(over.streamKey),
            ended = setOf(noStream.streamKey)
        )
        assertEquals(listOf(followed, premier, college), picked)
    }

    @Test
    fun `the picker lists every ranked live game, not only the list dual screen opened from`() = runTest(mainDispatcherRule.dispatcher) {
        val live = MutableStateFlow(listOf(followed, a, premier, b, college))
        val viewModel = SportsLiveDualViewModel(
            resolve = { null },
            endedKeys = { emptySet() },
            // Opened under a filter with three games of one league.
            session = SportsLiveDualSession(a, b, listOf(a, b, c), filter = "NCAAM", tab = SportsLiveTab.LIVE_NOW),
            liveGames = live
        )
        runCurrent()
        viewModel.openPairPicker(Side.RIGHT)
        assertEquals(listOf(followed, premier, college), viewModel.pairPicker.value)

        // A game that ends leaves the next time the picker opens; a new one comes in where it ranks.
        viewModel.closePairPicker()
        live.value = listOf(followed, a, b, college, c)
        runCurrent()
        viewModel.openPairPicker(Side.RIGHT)
        assertEquals(listOf(followed, college, c), viewModel.pairPicker.value)
    }

    @Test
    fun `before the ranked list loads, and on Channels, the session's list is offered`() = runTest(mainDispatcherRule.dispatcher) {
        val empty = SportsLiveDualViewModel(
            resolve = { null },
            endedKeys = { emptySet() },
            session = SportsLiveDualSession(a, b, listOf(a, b, c), null, SportsLiveTab.LIVE_NOW)
        )
        runCurrent()
        empty.openPairPicker(Side.RIGHT)
        assertEquals(listOf(c), empty.pairPicker.value)

        val channels = SportsLiveDualViewModel(
            resolve = { null },
            endedKeys = { emptySet() },
            session = SportsLiveDualSession(a, b, listOf(a, b, c), null, SportsLiveTab.CHANNELS),
            liveGames = MutableStateFlow(listOf(premier, c))
        )
        runCurrent()
        channels.openPairPicker(Side.RIGHT)
        // The ranked games first, then the channels not already listed.
        assertEquals(listOf(premier, c), channels.pairPicker.value)
    }

    @Test
    fun `the keys hint shows on the profile's first three entries only`() = runTest(mainDispatcherRule.dispatcher) {
        var shown = 0
        val claim: suspend () -> Boolean = { shouldShowDualKeysHint(shown).also { if (it) shown++ } }
        val session = SportsLiveDualSession(a, b, listOf(a, b, c), null, SportsLiveTab.LIVE_NOW)
        val hints = (1..5).map {
            val viewModel = SportsLiveDualViewModel(resolve = { null }, endedKeys = { emptySet() }, session = session, claimKeysHint = claim)
            runCurrent()
            val visible = viewModel.keysHint.value
            advanceTimeBy(SportsLiveDualViewModel.KEYS_HINT_MS + 1)
            assertFalse(viewModel.keysHint.value)
            visible
        }
        assertEquals(listOf(true, true, true, false, false), hints)
        assertEquals(3, shown)
    }

    @Test
    fun `an alert for the small pane's game makes it big in big and small, so the sound follows it`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = SportsLiveDualViewModel(
            resolve = { item -> dualStream(item.id) },
            endedKeys = { emptySet() },
            session = SportsLiveDualSession(a, b, listOf(a, b, c), null, SportsLiveTab.LIVE_NOW),
            loadLayout = { DualLayout.BIG_SMALL }
        )
        runCurrent()
        assertTrue(viewModel.watchFromAlert(b))
        val state = viewModel.state.value!!
        assertEquals(b, state.left.item)
        assertEquals(Side.LEFT, state.audibleSide)
    }

    // ▲ ▼ in a pane (user TV report 2026-11-21 ~14:10: zapping cycled the 3 games of a basketball filter, BYU lost)

    @Test
    fun `a pane zaps through every ranked live game and can always get back to a game it played`() = runTest(mainDispatcherRule.dispatcher) {
        val byu = dualItem("byu")
        val d = dualItem("d")
        val viewModel = SportsLiveDualViewModel(
            resolve = { item -> dualStream(item.id) },
            endedKeys = { emptySet() },
            // Opened under a basketball-only filter, with BYU (football) in the left pane.
            session = SportsLiveDualSession(byu, a, listOf(a, b, c), filter = "Basketball", tab = SportsLiveTab.LIVE_NOW),
            liveGames = MutableStateFlow(listOf(a, b, c, d))
        )
        runCurrent()
        assertTrue(viewModel.zap(forward = true))
        runCurrent()
        // Past BYU (last) to the first ranked game not in the other pane.
        assertEquals(b, viewModel.state.value!!.left.item)
        assertTrue(viewModel.zap(forward = false))
        runCurrent()
        assertEquals(byu, viewModel.state.value!!.left.item)
        // Onwards it reaches every ranked game, not only the filter's three.
        val seen = (1..4).map {
            viewModel.zap(forward = true)
            runCurrent()
            viewModel.state.value!!.left.item
        }
        assertEquals(listOf(b, c, d, byu), seen)
    }

    @Test
    fun `the pane's zap order is frozen - up then down returns, new games join at the end, ended ones are passed in place`() =
        runTest(mainDispatcherRule.dispatcher) {
            val d = dualItem("d")
            val e = dualItem("e")
            var ended = emptySet<String>()
            val live = MutableStateFlow(listOf(a, b, c, d))
            val viewModel = SportsLiveDualViewModel(
                resolve = { item -> dualStream(item.id) },
                endedKeys = { ended },
                session = SportsLiveDualSession(c, a, listOf(a, b, c, d), null, SportsLiveTab.LIVE_NOW),
                liveGames = live
            )
            runCurrent()
            assertTrue(viewModel.zap(forward = false))
            runCurrent()
            assertEquals(b, viewModel.state.value!!.left.item)
            // The ranking reorders, and e goes live.
            live.value = listOf(d, c, e, b, a)
            runCurrent()
            assertTrue(viewModel.zap(forward = true))
            runCurrent()
            assertEquals(c, viewModel.state.value!!.left.item)
            // d ends: passed over in place; e, new, comes after it at the end.
            ended = setOf(d.streamKey)
            assertTrue(viewModel.zap(forward = true))
            runCurrent()
            assertEquals(e, viewModel.state.value!!.left.item)
            assertTrue(viewModel.zap(forward = false))
            runCurrent()
            assertEquals(c, viewModel.state.value!!.left.item)
        }
}
