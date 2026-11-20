package com.nuvio.tv.ui.screens.sportslive.dual

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapSessions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** Coordinator decisions 1 and 5: Back from full screen returns to dual; ▶ past the right edge changes the left pane. */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveDualBackAndPickerTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val a = dualItem("a")
    private val b = dualItem("b")
    private val c = dualItem("c")
    private val items = listOf(a, b, c)

    @After
    fun tearDown() {
        SportsLiveZapSessions.clear()
        SportsLiveDualSessions.clear()
    }

    private fun newViewModel() = SportsLiveDualViewModel(
        resolve = { item -> dualStream(item.id) },
        endedKeys = { emptySet() },
        session = SportsLiveDualSession(a, b, items, null, SportsLiveTab.LIVE_NOW)
    )

    @Test
    fun `the picker past the right edge changes the left pane, past the left edge the right one`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        runCurrent()
        viewModel.openPairPicker(Side.LEFT)
        assertEquals(listOf(c), viewModel.pairPicker.value)
        viewModel.pickPair(c)
        runCurrent()
        assertNull(viewModel.pairPicker.value)
        assertEquals(c, viewModel.state.value!!.left.item)
        assertEquals(b, viewModel.state.value!!.right.item)

        viewModel.openPairPicker(Side.RIGHT)
        viewModel.pickPair(a)
        runCurrent()
        assertEquals(c, viewModel.state.value!!.left.item)
        assertEquals(a, viewModel.state.value!!.right.item)
    }

    @Test
    fun `full screen from a pane remembers the other pane's game, so Back returns to dual with both`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        runCurrent()
        viewModel.startZapSession(b)
        val zap = SportsLiveZapSessions.current()!!
        assertEquals(b, zap.current)
        assertEquals(a, zap.dualReturn!!.other)
        val back = zap.dualSessionOnBack(null)!!
        assertEquals(b, back.left)
        assertEquals(a, back.right)
        assertEquals(items, back.items)
    }

    @Test
    fun `a full screen Back from dual plays carries no way back, so Back never ping-pongs`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        runCurrent()
        viewModel.startZapSession(a, returnToDual = false)
        assertNull(SportsLiveZapSessions.current()!!.dualReturn)
    }
}
