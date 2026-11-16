package com.nuvio.tv.ui.screens.sportslive.mini

import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapSession
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapSessions
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapTarget
import com.nuvio.tv.ui.screens.sportslive.dual.dualItem
import com.nuvio.tv.ui.screens.sportslive.dual.dualStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveMiniExpanderTest {

    private val a = dualItem("a")
    private val b = dualItem("b")
    private val miniStream = dualStream("mini")
    private val freshStream = dualStream("fresh")
    private val session = SportsLiveZapSession(listOf(a, b), 0, SportsLiveTab.LIVE_NOW, null, false)
    private var lookups = 0
    private val picked = mutableListOf<Pair<Stream, Boolean>>()

    @After
    fun tearDown() {
        SportsLiveZapSessions.clear()
    }

    private class Setup(
        val mini: SportsLiveMiniPlayerController,
        val expander: SportsLiveMiniExpander,
        val targets: MutableList<SportsLiveZapTarget>
    )

    private fun TestScope.setup(lookup: CompletableDeferred<Stream?>): Setup {
        val mini = SportsLiveMiniPlayerController(backgroundScope)
        val expander = SportsLiveMiniExpander(
            scope = backgroundScope,
            controller = mini,
            resolve = { lookups++; lookup.await() },
            toTarget = { playing, stream, fresh ->
                picked += stream to fresh
                SportsLiveZapTarget(playing.item, null, playing.session.returnToHomeOnBack)
            }
        )
        val targets = mutableListOf<SportsLiveZapTarget>()
        backgroundScope.launch { expander.targets.collect { targets += it } }
        mini.onBackFromPlayer("a", session, miniStream)
        return Setup(mini, expander, targets)
    }

    private fun Setup.playing() = mini.state.value as SportsLiveMiniState.Playing

    @Test
    fun `a fresh stream opens the full player with the session restored, progress shown meanwhile`() = runTest {
        val lookup = CompletableDeferred<Stream?>()
        val s = setup(lookup)

        s.expander.expand(s.playing())
        runCurrent()
        assertEquals(a, s.expander.expanding.value)
        s.expander.expand(s.playing())
        runCurrent()
        assertEquals("a second press starts no second lookup", 1, lookups)

        lookup.complete(freshStream)
        runCurrent()
        assertEquals(listOf(freshStream to true), picked)
        assertEquals(1, s.targets.size)
        assertSame(session, SportsLiveZapSessions.current())
        assertNull(s.expander.expanding.value)
    }

    @Test
    fun `no fresh stream falls back to the mini player's`() = runTest {
        val lookup = CompletableDeferred<Stream?>()
        val s = setup(lookup)

        s.expander.expand(s.playing())
        lookup.complete(null)
        runCurrent()

        assertEquals(listOf(miniStream to false), picked)
        assertEquals(1, s.targets.size)
    }

    @Test
    fun `Back cancels the lookup and the mini player plays on`() = runTest {
        val lookup = CompletableDeferred<Stream?>()
        val s = setup(lookup)

        s.expander.expand(s.playing())
        runCurrent()
        s.expander.cancel()
        lookup.complete(freshStream)
        runCurrent()

        assertNull(s.expander.expanding.value)
        assertTrue(s.targets.isEmpty())
        assertTrue(s.mini.isActive)
        assertNull(SportsLiveZapSessions.current())
    }

    @Test
    fun `a mini player stopped during the lookup opens nothing`() = runTest {
        val lookup = CompletableDeferred<Stream?>()
        val s = setup(lookup)

        s.expander.expand(s.playing())
        runCurrent()
        s.mini.stop(SportsLiveMiniStop.USER_STOP)
        lookup.complete(freshStream)
        runCurrent()

        assertTrue(s.targets.isEmpty())
        assertNull(s.expander.expanding.value)
        assertNull(SportsLiveZapSessions.current())
    }
}
