package com.nuvio.tv.ui.screens.sportslive.dual

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.domain.model.SportsLiveTab
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * live.26 item 3 in dual screen: "Change the other game" only while another live game can go beside the two, the
 * picker's Same league from the pane that stays, and two decoders failing falls back to the game that plays, full screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DualPickerDualScreenTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val a = dualItem("a")
    private val b = dualItem("b")
    private val c = dualItem("c")
    private val channel = dualItem("channel").copy(isChannel = true)

    @After
    fun tearDown() {
        SportsLiveDualSessions.clear()
    }

    private val split = DualState(DualPane(a), DualPane(b))

    @Test
    fun `the Hold OK menu leaves out Change the other game when no other game is live`() {
        assertTrue(DualMenuEntry.CHANGE_OTHER_GAME in dualMenuEntries(split))
        assertTrue(DualMenuEntry.CHANGE_OTHER_GAME in dualMenuEntries(split, canChange = true))
        assertFalse(DualMenuEntry.CHANGE_OTHER_GAME in dualMenuEntries(split, canChange = false))
        assertEquals(4, dualMenuEntries(split, canChange = false).size)
    }

    @Test
    fun `the picker is offered only while another live game is listed, channels alone never`() = runTest(mainDispatcherRule.dispatcher) {
        val live = MutableStateFlow(listOf(a, b))
        val viewModel = SportsLiveDualViewModel(
            resolve = { null },
            endedKeys = { emptySet() },
            session = SportsLiveDualSession(a, b, listOf(a, b, channel), null, SportsLiveTab.CHANNELS),
            liveGames = live
        )
        runCurrent()
        assertFalse(viewModel.offersPairPicker())

        live.value = listOf(a, b, c)
        runCurrent()
        assertTrue(viewModel.offersPairPicker())
    }

    @Test
    fun `the picker's Same league is the pane that stays`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = SportsLiveDualViewModel(
            resolve = { null },
            endedKeys = { emptySet() },
            session = SportsLiveDualSession(a, b, listOf(a, b, c), null, SportsLiveTab.LIVE_NOW),
            liveGames = MutableStateFlow(listOf(a, b, c))
        )
        runCurrent()
        viewModel.openPairPicker(Side.RIGHT)
        assertEquals(a, viewModel.pairPickerAnchor())
        viewModel.closePairPicker()
        viewModel.openPairPicker(Side.LEFT)
        assertEquals(b, viewModel.pairPickerAnchor())
    }

    @Test
    fun `two decoders failing falls back to the game that plays, with its stream as is`() {
        val playing = DualPane(a, dualStream("a"), PaneStatus.Playing)
        val limit = DualPane(b, dualStream("b"), PaneStatus.DeviceLimit)
        assertEquals(playing, dualDecoderFallback(DualState(playing, limit)))
        assertEquals(playing, dualDecoderFallback(DualState(limit, playing)))
    }

    @Test
    fun `no fallback while the other game has not started, has failed too, or both play`() {
        val limit = DualPane(b, dualStream("b"), PaneStatus.DeviceLimit)
        assertNull(dualDecoderFallback(DualState(DualPane(a, dualStream("a"), PaneStatus.Loading), limit)))
        assertNull(dualDecoderFallback(DualState(DualPane(a, dualStream("a"), PaneStatus.Error), limit)))
        assertNull(
            dualDecoderFallback(DualState(DualPane(a, dualStream("a"), PaneStatus.Playing), DualPane(b, dualStream("b"), PaneStatus.Playing)))
        )
    }
}
