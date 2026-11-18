package com.nuvio.tv.ui.screens.sportslive.dual

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.sportslive.SportsLiveWorkingLinks
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.model.Stream
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

/** M11: Back from dual screen opened in a full player returns to that game's single player. */
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
    fun tearDown() = SportsLiveDualSessions.clear()

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
        assertNull(sportsLivePickModeSession(chosen = a, item = b, tab = SportsLiveTab.LIVE_NOW, items = items, filter = null)!!.backTo)
    }

    @Test
    fun `Back plays the game dual screen came from, on its pane's playing stream`() = runTest(mainDispatcherRule.dispatcher) {
        val session = SportsLiveDualPick(a, items, null, SportsLiveTab.LIVE_NOW, chosenStream = playing, fromPlayer = true).pairWith(b)!!
        val viewModel = newViewModel(session)
        runCurrent()

        // Still starting: the stream the player handed over.
        assertEquals(a to playing, viewModel.backToPlayer())

        // Swapped and playing: the same game, wherever its pane is, on the stream it plays.
        viewModel.onFirstFrame(a)
        viewModel.swap()
        val (item, stream) = viewModel.backToPlayer()!!
        assertEquals(a, item)
        assertSame(playing, stream)
    }

    @Test
    fun `with the game zapped away Back still returns to it, and dual from the section just leaves`() = runTest(mainDispatcherRule.dispatcher) {
        val session = SportsLiveDualPick(a, items, null, SportsLiveTab.LIVE_NOW, chosenStream = playing, fromPlayer = true).pairWith(b)!!
        val viewModel = newViewModel(session)
        runCurrent()
        viewModel.highlight(Side.LEFT)
        viewModel.zap(forward = true)
        runCurrent()
        assertEquals(a to playing, viewModel.backToPlayer())

        // Without a stream handed over, the play path looks one up (null stream).
        val noStream = newViewModel(session.copy(leftStream = null))
        runCurrent()
        assertEquals(a to null, noStream.backToPlayer())

        val fromSection = newViewModel(SportsLiveDualSession(a, b, items, null, SportsLiveTab.LIVE_NOW))
        runCurrent()
        assertNull(fromSection.backToPlayer())
    }
}
