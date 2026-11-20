package com.nuvio.tv.ui.screens.sportslive.dual

import android.view.KeyEvent
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.sportslive.SportsLiveGameReadiness
import com.nuvio.tv.core.sportslive.SportsLiveTriedGames
import com.nuvio.tv.core.sportslive.SportsLiveWorkingLinks
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab
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

/** live.19 TV report: a pane on its error card keeps zapping, through dead games, as a playing pane does. */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveDualErrorZapTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val a = dualItem("a")
    private val b = dualItem("b")
    private val c = dualItem("c")
    private val d = dualItem("d")
    private val e = dualItem("e")
    private val items = listOf(a, b, c, d, e)

    private val tried = SportsLiveTriedGames()
    private val links = SportsLiveWorkingLinks(nowMs = { 0L })

    @After
    fun tearDown() = SportsLiveDualSessions.clear()

    /** Left pane on a, right pane on e; the games in [dead] have no stream at all (the pane shows its error card). */
    private fun newViewModel(dead: Set<SportsLiveItem> = emptySet()) = SportsLiveDualViewModel(
        resolve = { item -> if (item in dead) null else dualStream(item.id) },
        endedKeys = { emptySet() },
        session = SportsLiveDualSession(a, e, items, null, SportsLiveTab.LIVE_NOW),
        workingLinks = links,
        readiness = SportsLiveGameReadiness(),
        triedGames = tried
    )

    private fun SportsLiveDualViewModel.left(): DualPane = state.value!!.left

    @Test
    fun `a zap from a pane on its error card moves it to the next game`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel(dead = setOf(b))
        runCurrent()
        assertTrue(viewModel.zap(forward = true))
        runCurrent()
        assertEquals(b, viewModel.left().item)
        assertEquals(PaneStatus.Error, viewModel.left().status)

        assertTrue(viewModel.zap(forward = true))
        runCurrent()
        assertEquals(c, viewModel.left().item)
        assertEquals(PaneStatus.Loading, viewModel.left().status)
    }

    @Test
    fun `repeated zaps across dead games keep moving, then wrap past the other pane's game`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel(dead = setOf(b, c, d))
        runCurrent()
        val visited = mutableListOf<SportsLiveItem>()
        repeat(4) {
            assertTrue(viewModel.zap(forward = true))
            runCurrent()
            visited += viewModel.left().item
        }
        // e is the other pane's: the list wraps to a, as a playing pane's zap does.
        assertEquals(listOf(b, c, d, a), visited)
        assertEquals(e, viewModel.state.value!!.right.item)
    }

    @Test
    fun `games left on the error card this session are skipped, and with none other left the zap still moves`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel(dead = setOf(b, c, d))
            runCurrent()
            repeat(4) {
                viewModel.zap(forward = true)
                runCurrent()
            }
            assertEquals(a, viewModel.left().item)
            // Back from a: d, c and b failed here and e is the other pane's. Only dead games are left: the zap
            // still moves (the user asked to keep flipping when every stream is exhausted), to d, looked up afresh.
            assertTrue(viewModel.zap(forward = false))
            runCurrent()
            assertEquals(d, viewModel.left().item)
        }

    @Test
    fun `a playing pane's zap skips a game whose every known link failed`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        runCurrent()
        viewModel.onFirstFrame(a)
        val only = dualStream("b-only")
        links.noteLinks(b.id, listOf(only))
        links.failed(b.id, only.url)
        assertTrue(viewModel.zap(forward = true))
        assertEquals(c, viewModel.left().item)
    }

    @Test
    fun `every zap key zaps a pane on its error card`() {
        val failed = DualPane(a, stream = dualStream("a"), status = PaneStatus.Error)
        assertEquals(true, dualZapKey(failed, KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(false, dualZapKey(failed, KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(true, dualZapKey(failed, KeyEvent.KEYCODE_CHANNEL_DOWN))
        assertEquals(false, dualZapKey(failed, KeyEvent.KEYCODE_CHANNEL_UP))
        assertEquals(true, dualZapKey(failed, KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(false, dualZapKey(failed, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        // Not a zap key.
        assertNull(dualZapKey(failed, KeyEvent.KEYCODE_DPAD_CENTER))
        // No stream found at all: the error card zaps too.
        assertEquals(true, dualZapKey(DualPane(a, status = PaneStatus.Error), KeyEvent.KEYCODE_DPAD_DOWN))
    }

    @Test
    fun `a starting card keeps Up and Down for its buttons, CH+ and CH- still zap`() {
        val starting = DualPane(a, stream = dualStream("a"), status = PaneStatus.Loading)
        assertNull(dualZapKey(starting, KeyEvent.KEYCODE_DPAD_DOWN))
        assertNull(dualZapKey(starting, KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(true, dualZapKey(starting, KeyEvent.KEYCODE_CHANNEL_DOWN))
        assertEquals(true, dualZapKey(DualPane(a, stream = dualStream("a"), status = PaneStatus.Playing), KeyEvent.KEYCODE_DPAD_DOWN))
    }
}
