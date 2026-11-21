package com.nuvio.tv.ui.screens.sportslive.dual

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.sportslive.SportsLiveWorkingLinks
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapSessions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

/** Back from dual screen keeps the game with sound full screen and stops the other (user, 2026-11-21). */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveDualBackTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val a = dualItem("a")
    private val b = dualItem("b")
    private val c = dualItem("c")
    private val items = listOf(a, b, c)
    private val playing = dualStream("a-playing")
    private val pending = mutableMapOf<String, CompletableDeferred<Stream?>>()

    @After
    fun tearDown() {
        SportsLiveZapSessions.clear()
        SportsLiveDualSessions.clear()
    }

    private fun newViewModel(session: SportsLiveDualSession) = SportsLiveDualViewModel(
        resolve = { item -> pending.getOrPut(item.id) { CompletableDeferred() }.await() },
        endedKeys = { emptySet() },
        session = session,
        workingLinks = SportsLiveWorkingLinks(nowMs = { 0L })
    )

    @Test
    fun `a pick made in the full player remembers its game for Back, one made in the section does not`() {
        val fromPlayer = SportsLiveDualPick(chosen = a, items = items, filter = null, tab = SportsLiveTab.LIVE_NOW, chosenStream = playing, fromPlayer = true)
        assertEquals(a, fromPlayer.pairWith(b)!!.backTo)
        assertNull(fromPlayer.copy(fromPlayer = false).pairWith(b)!!.backTo)
        assertNull(sportsLiveWatchWithSession(a, items, items, null, emptyMap(), emptySet())!!.backTo)
    }

    @Test
    fun `Back keeps the game with sound, either pane, on the stream its pane plays`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel(SportsLiveDualSession(a, b, items, null, SportsLiveTab.LIVE_NOW))
        runCurrent()
        val streamA = dualStream("a-pane")
        val streamB = dualStream("b-pane")
        pending.getValue("a").complete(streamA)
        pending.getValue("b").complete(streamB)
        runCurrent()
        viewModel.onFirstFrame(a)
        viewModel.onFirstFrame(b)

        // The highlighted pane has the sound: A at first, B once the highlight moves.
        assertEquals(a to streamA, viewModel.keepOnBack())
        viewModel.highlight(Side.RIGHT)
        val (item, stream) = viewModel.keepOnBack()!!
        assertEquals(b, item)
        // The same stream object the pane plays: no new lookup, as OK hands it over.
        assertSame(streamB, stream)
    }

    @Test
    fun `a highlighted pane that failed leaves the sound and Back with the game still playing`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel(SportsLiveDualSession(a, b, items, null, SportsLiveTab.LIVE_NOW))
        runCurrent()
        val streamB = dualStream("b-pane")
        pending.getValue("a").complete(null)
        pending.getValue("b").complete(streamB)
        runCurrent()
        viewModel.onFirstFrame(b)
        viewModel.highlight(Side.LEFT)
        assertEquals(PaneStatus.Error, viewModel.state.value!!.left.status)

        assertEquals(b to streamB, viewModel.keepOnBack())
    }

    @Test
    fun `Back from dual reopened from a full screen game keeps that game, not the list (user bug 2026-11-21)`() = runTest(mainDispatcherRule.dispatcher) {
        // A and B in dual, OK put B full screen: its zap session remembers A for Back to dual.
        val first = newViewModel(SportsLiveDualSession(a, b, items, null, SportsLiveTab.LIVE_NOW))
        runCurrent()
        first.startZapSession(b)
        val streamB = dualStream("b-full")
        // Back from B's full player: dual again, B on the left with the sound, on the stream the player plays.
        val again = SportsLiveZapSessions.current()!!.dualSessionOnBack(streamB)!!
        assertEquals(b, again.left)
        assertEquals(a, again.right)
        val dual = newViewModel(again)
        runCurrent()

        // Back again: B goes on full screen on the stream handed over, A stops; never null (the list).
        assertEquals(b to streamB, dual.keepOnBack())
        dual.onFirstFrame(b)
        assertSame(streamB, dual.keepOnBack()!!.second)
    }

    @Test
    fun `the game kept by Back plays as a single game, so a further Back leaves`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel(SportsLiveDualSession(a, b, items, null, SportsLiveTab.LIVE_NOW))
        runCurrent()
        viewModel.highlight(Side.RIGHT)
        val (kept, _) = viewModel.keepOnBack()!!
        assertEquals(b, kept)

        // What the screen does when Back plays it: no way back to dual in its zap session.
        viewModel.startZapSession(kept, returnToDual = false)
        val zap = SportsLiveZapSessions.activeFor(b.id)!!
        assertEquals(b, zap.current)
        assertNull(zap.dualReturn)
        assertNull(zap.dualSessionOnBack(null))
    }

    @Test
    fun `dual opened in a full player keeps the game with sound, swapped or zapped`() = runTest(mainDispatcherRule.dispatcher) {
        val session = SportsLiveDualPick(a, items, null, SportsLiveTab.LIVE_NOW, chosenStream = playing, fromPlayer = true).pairWith(b)!!
        val viewModel = newViewModel(session)
        runCurrent()

        // Still starting: the stream the player handed over.
        assertEquals(a to playing, viewModel.keepOnBack())

        // Swapped and playing: the same game, wherever its pane is, on the stream it plays.
        viewModel.onFirstFrame(a)
        viewModel.swap()
        val (item, stream) = viewModel.keepOnBack()!!
        assertEquals(a, item)
        assertSame(playing, stream)

        // The pane with sound zapped to another game: that game is the one kept (no stream yet: the play path looks one up).
        val zapped = newViewModel(session)
        runCurrent()
        zapped.highlight(Side.LEFT)
        zapped.zap(forward = true)
        runCurrent()
        assertEquals(c to null, zapped.keepOnBack())
    }
}
