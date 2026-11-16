package com.nuvio.tv.ui.screens.sportslive.preview

import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.screens.sportslive.dual.dualItem
import com.nuvio.tv.ui.screens.sportslive.dual.dualStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SportsLivePreviewControllerTest {

    private val a = dualItem("a")
    private val b = dualItem("b")
    private val streamA = dualStream("a")
    private val streamB = dualStream("b")
    private val lookups = mutableListOf<String>()

    private fun TestScope.controller(
        tab: SportsLiveTab = SportsLiveTab.LIVE_NOW,
        resolve: suspend (SportsLiveItem) -> Stream? = { item -> if (item.id == "a") streamA else streamB }
    ) = SportsLivePreviewController(this, { item -> lookups += item.id; resolve(item) }).apply {
        onTab(tab)
        onEnabled(true)
        onVisible(true)
    }

    @Test
    fun `resting 2 s on a card plays its stream, and the first frame shows it`() = runTest {
        val preview = controller()
        preview.onFocus(a)
        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(SportsLivePreviewState.Idle, preview.state.value)
        assertEquals(emptyList<String>(), lookups)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(SportsLivePreviewState.Playing(a, streamA), preview.state.value)

        preview.onFirstFrame(a)
        assertEquals(SportsLivePreviewState.Playing(a, streamA, rendering = true), preview.state.value)
        advanceUntilIdle()
        assertEquals(SportsLivePreviewState.Playing(a, streamA, rendering = true), preview.state.value)
    }

    @Test
    fun `moving away stops the preview at once`() = runTest {
        val preview = controller()
        preview.onFocus(a)
        advanceTimeBy(2_001)
        preview.onFirstFrame(a)

        preview.onFocus(null)
        assertEquals(SportsLivePreviewState.Idle, preview.state.value)
        advanceUntilIdle()
        assertEquals(SportsLivePreviewState.Idle, preview.state.value)
    }

    @Test
    fun `scrolling past cards looks nothing up`() = runTest {
        val preview = controller()
        repeat(20) { i ->
            preview.onFocus(dualItem("card$i"))
            advanceTimeBy(300)
        }
        preview.onFocus(null)
        advanceUntilIdle()
        assertEquals(emptyList<String>(), lookups)
        assertEquals(SportsLivePreviewState.Idle, preview.state.value)
    }

    @Test
    fun `moving to another card settles again before it plays`() = runTest {
        val preview = controller()
        preview.onFocus(a)
        advanceTimeBy(2_001)
        preview.onFocus(b)
        assertEquals(SportsLivePreviewState.Idle, preview.state.value)
        advanceTimeBy(2_001)
        assertEquals(SportsLivePreviewState.Playing(b, streamB), preview.state.value)
    }

    @Test
    fun `a refreshed copy of the focused card keeps its preview`() = runTest {
        val preview = controller()
        preview.onFocus(a)
        advanceTimeBy(2_001)
        preview.onFirstFrame(a)
        preview.onFocus(a.copy(isLive = true, sport = "Soccer"))
        assertEquals(SportsLivePreviewState.Playing(a, streamA, rendering = true), preview.state.value)
    }

    @Test
    fun `only Live now and Channels preview`() = runTest {
        for (tab in SportsLiveTab.entries) {
            lookups.clear()
            val preview = controller(tab)
            preview.onFocus(a)
            advanceTimeBy(2_001)
            val expected = if (tab == SportsLiveTab.LIVE_NOW || tab == SportsLiveTab.CHANNELS) listOf("a") else emptyList()
            assertEquals("$tab", expected, lookups)
            preview.onFocus(null)
        }
    }

    @Test
    fun `a tab change stops the preview`() = runTest {
        val preview = controller()
        preview.onFocus(a)
        advanceTimeBy(2_001)
        preview.onTab(SportsLiveTab.UPCOMING)
        assertEquals(SportsLivePreviewState.Idle, preview.state.value)
        advanceUntilIdle()
        assertEquals(listOf("a"), lookups)
    }

    @Test
    fun `setting off never previews and turning it off stops a preview`() = runTest {
        val preview = controller()
        preview.onEnabled(false)
        preview.onFocus(a)
        advanceUntilIdle()
        assertEquals(emptyList<String>(), lookups)

        preview.onEnabled(true)
        advanceTimeBy(2_001)
        assertEquals(SportsLivePreviewState.Playing(a, streamA), preview.state.value)
        preview.onEnabled(false)
        assertEquals(SportsLivePreviewState.Idle, preview.state.value)
    }

    @Test
    fun `hidden screen stops the preview and coming back settles afresh`() = runTest {
        val preview = controller()
        preview.onFocus(a)
        advanceTimeBy(2_001)
        preview.onFirstFrame(a)
        preview.onVisible(false)
        assertEquals(SportsLivePreviewState.Idle, preview.state.value)

        preview.onVisible(true)
        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(SportsLivePreviewState.Idle, preview.state.value)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(SportsLivePreviewState.Playing(a, streamA), preview.state.value)
    }

    @Test
    fun `no stream keeps the art`() = runTest {
        val preview = controller(resolve = { null })
        preview.onFocus(a)
        advanceUntilIdle()
        assertEquals(SportsLivePreviewState.Idle, preview.state.value)
        assertEquals(listOf("a"), lookups)
    }

    @Test
    fun `the card is shown as resolving while its stream is looked up`() = runTest {
        val found = CompletableDeferred<Stream?>()
        val preview = controller(resolve = { found.await() })
        preview.onFocus(a)
        advanceTimeBy(2_001)
        assertEquals(SportsLivePreviewState.Resolving(a), preview.state.value)
        found.complete(streamA)
        runCurrent()
        assertEquals(SportsLivePreviewState.Playing(a, streamA), preview.state.value)
    }

    @Test
    fun `a stale lookup result is ignored`() = runTest {
        val found = CompletableDeferred<Stream?>()
        // A lookup that does not stop when cancelled still returns its result.
        val preview = controller(resolve = { item ->
            if (item.id == "a") withContext(NonCancellable) { found.await() } else streamB
        })
        preview.onFocus(a)
        advanceTimeBy(2_001)
        preview.onFocus(b)
        found.complete(streamA)
        runCurrent()
        assertEquals(SportsLivePreviewState.Idle, preview.state.value)
        advanceTimeBy(2_001)
        assertEquals(SportsLivePreviewState.Playing(b, streamB), preview.state.value)
    }

    @Test
    fun `no first frame within 10 s goes back to the art`() = runTest {
        val preview = controller()
        preview.onFocus(a)
        advanceTimeBy(2_001)
        advanceTimeBy(9_998)
        runCurrent()
        assertEquals(SportsLivePreviewState.Playing(a, streamA), preview.state.value)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(SportsLivePreviewState.Idle, preview.state.value)
        // Not retried until the focus moves.
        advanceUntilIdle()
        assertEquals(listOf("a"), lookups)
    }

    @Test
    fun `a player error goes back to the art, and another card's events are ignored`() = runTest {
        val preview = controller()
        preview.onFocus(a)
        advanceTimeBy(2_001)
        preview.onFirstFrame(b)
        preview.onError(b)
        assertEquals(SportsLivePreviewState.Playing(a, streamA), preview.state.value)
        preview.onError(a)
        assertEquals(SportsLivePreviewState.Idle, preview.state.value)
        advanceUntilIdle()
        assertEquals(listOf("a"), lookups)
    }

    @Test
    fun `take over hands a rendering preview over and does not settle again`() = runTest {
        val preview = controller()
        preview.onFocus(a)
        advanceTimeBy(2_001)
        assertNull("not on screen yet", preview.takeOver(a))
        preview.onFirstFrame(a)
        assertNull("another card", preview.takeOver(b))

        assertEquals(SportsLivePreviewState.Playing(a, streamA, rendering = true), preview.takeOver(a))
        assertEquals(SportsLivePreviewState.Idle, preview.state.value)
        advanceUntilIdle()
        assertEquals(SportsLivePreviewState.Idle, preview.state.value)
        assertEquals(listOf("a"), lookups)
    }
}
