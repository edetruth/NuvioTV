package com.nuvio.tv.ui.screens.sportslive.preview

import androidx.media3.common.TrackSelectionParameters
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.sportslive.SportsLiveSourceCooldown
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.ui.screens.sportslive.dual.dualItem
import com.nuvio.tv.ui.screens.sportslive.dual.dualStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** #83: the in-player panel's preview of the focused row. */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsLivePanelPreviewTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val playing = dualItem("playing")
    private val b = dualItem("b")
    private val c = dualItem("c")
    private val lookups = mutableListOf<String>()
    private val found: suspend (SportsLiveItem) -> SportsLivePreviewLookup = { item ->
        lookups += item.id
        SportsLivePreviewLookup.Found(dualStream(item.id))
    }

    @After
    fun tearDown() = SportsLiveDecoderLimit.reset()

    private fun TestScope.openPanel(resolve: suspend (SportsLiveItem) -> SportsLivePreviewLookup = found) =
        SportsLivePanelPreview(this, resolve).apply {
            onAllowed(true)
            onPanelOpen(true)
        }

    private fun TestScope.settle() {
        advanceTimeBy(SportsLivePanelPreview.SETTLE_MS + 1)
        runCurrent()
    }

    @Test
    fun `a row focused for its dwell plays its preview, not before`() = runTest {
        val preview = openPanel()
        preview.onFocus(b, playing.streamKey)
        advanceTimeBy(SportsLivePanelPreview.SETTLE_MS - 1)
        runCurrent()
        assertEquals(SportsLivePreviewState.Idle, preview.state.value)
        assertTrue(lookups.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(SportsLivePreviewState.Playing(b, dualStream("b")), preview.state.value)
    }

    @Test
    fun `the dwell is about 800 ms and a move within it cancels the lookup`() = runTest {
        assertEquals(800L, SportsLivePanelPreview.SETTLE_MS)
        val preview = openPanel()
        // Scrolling past rows faster than the dwell looks nothing up.
        listOf(b, c, b, c).forEach { row ->
            preview.onFocus(row, playing.streamKey)
            advanceTimeBy(SportsLivePanelPreview.SETTLE_MS - 100)
            runCurrent()
        }
        assertTrue(lookups.isEmpty())
        assertEquals(SportsLivePreviewState.Idle, preview.state.value)
        settle()
        assertEquals(listOf("c"), lookups)
    }

    @Test
    fun `no preview while a key repeats, and the dwell starts on release`() = runTest {
        val preview = openPanel()
        preview.onFocus(b, playing.streamKey)
        preview.onKeysRepeating(true)
        preview.onFocus(c, playing.streamKey)
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(lookups.isEmpty())
        assertEquals(SportsLivePreviewState.Idle, preview.state.value)
        // Held over a playing preview: it stops at once.
        preview.onKeysRepeating(false)
        settle()
        assertEquals(SportsLivePreviewState.Playing(c, dualStream("c")), preview.state.value)
        preview.onKeysRepeating(true)
        runCurrent()
        assertEquals(SportsLivePreviewState.Idle, preview.state.value)
        preview.onKeysRepeating(false)
        advanceTimeBy(SportsLivePanelPreview.SETTLE_MS - 1)
        runCurrent()
        assertEquals(SportsLivePreviewState.Idle, preview.state.value)
        settle()
        assertTrue(preview.state.value is SportsLivePreviewState.Playing)
    }

    @Test
    fun `only one preview is ever in flight, the last row focused`() = runTest {
        val started = mutableListOf<String>()
        var running = 0
        var maxRunning = 0
        val slow: suspend (SportsLiveItem) -> SportsLivePreviewLookup = { item ->
            started += item.id
            running++
            maxRunning = maxOf(maxRunning, running)
            try {
                kotlinx.coroutines.delay(3_000)
                SportsLivePreviewLookup.Found(dualStream(item.id))
            } finally {
                running--
            }
        }
        val preview = openPanel(slow)
        preview.onFocus(b, playing.streamKey)
        settle()
        preview.onFocus(c, playing.streamKey)
        settle()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf("b", "c"), started)
        assertEquals(1, maxRunning)
        assertEquals(SportsLivePreviewState.Playing(c, dualStream("c")), preview.state.value)
    }

    @Test
    fun `the game playing is never previewed`() = runTest {
        val preview = openPanel()
        preview.onFocus(playing, playing.streamKey)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(SportsLivePreviewState.Idle, preview.state.value)
        assertTrue(lookups.isEmpty())
    }

    @Test
    fun `moving focus stops the preview at once and settles again on the new row`() = runTest {
        val preview = openPanel()
        preview.onFocus(b, playing.streamKey)
        settle()
        assertTrue(preview.state.value is SportsLivePreviewState.Playing)
        preview.onFocus(c, playing.streamKey)
        assertEquals(SportsLivePreviewState.Idle, preview.state.value)
        settle()
        assertEquals(c, (preview.state.value as SportsLivePreviewState.Playing).item)
        // Focus on the chips (no row): nothing plays.
        preview.onFocus(null, playing.streamKey)
        assertEquals(SportsLivePreviewState.Idle, preview.state.value)
    }

    @Test
    fun `closing the panel or picking a game stops the preview`() = runTest {
        val preview = openPanel()
        preview.onFocus(b, playing.streamKey)
        settle()
        preview.onPanelOpen(false)
        assertEquals(SportsLivePreviewState.Idle, preview.state.value)
        // Reopened, it waits for a row to be focused again.
        preview.onPanelOpen(true)
        settle()
        assertEquals(SportsLivePreviewState.Idle, preview.state.value)

        preview.onFocus(c, playing.streamKey)
        settle()
        preview.onPick()
        assertEquals(SportsLivePreviewState.Idle, preview.state.value)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(SportsLivePreviewState.Idle, preview.state.value)
    }

    @Test
    fun `a row's preview stops 20 s after the row took focus, until focus moves`() = runTest {
        val preview = openPanel()
        preview.onFocus(b, playing.streamKey)
        settle()
        preview.onFirstFrame(b)
        advanceTimeBy(SportsLivePanelPreview.MAX_ROW_MS - SportsLivePanelPreview.SETTLE_MS - 2)
        runCurrent()
        assertTrue(preview.state.value is SportsLivePreviewState.Playing)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(SportsLivePreviewState.Idle, preview.state.value)
        // The same row (a refreshed copy) never starts again.
        preview.onFocus(b, playing.streamKey)
        settle()
        assertEquals(SportsLivePreviewState.Idle, preview.state.value)
        assertEquals(listOf("b"), lookups)
        // Another row gets its own 20 s.
        preview.onFocus(c, playing.streamKey)
        settle()
        assertEquals(c, (preview.state.value as SportsLivePreviewState.Playing).item)
    }

    @Test
    fun `the app going behind Home or the screensaver stops the preview`() = runTest {
        val preview = openPanel()
        preview.onFocus(b, playing.streamKey)
        settle()
        preview.onForeground(false)
        assertEquals(SportsLivePreviewState.Idle, preview.state.value)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(SportsLivePreviewState.Idle, preview.state.value)
        // Back in front with the panel still open: a fresh settle on the same row.
        preview.onForeground(true)
        settle()
        assertTrue(preview.state.value is SportsLivePreviewState.Playing)
    }

    @Test
    fun `the panel preview asks for the lowest bitrate at most 480p`() {
        val capped = TrackSelectionParameters.Builder().build().capPreviewVideo()
        assertEquals(PREVIEW_MAX_WIDTH, capped.maxVideoWidth)
        assertEquals(PREVIEW_MAX_HEIGHT, capped.maxVideoHeight)
        assertTrue(capped.forceLowestBitrate)
    }

    @Test
    fun `nothing is looked up while the preview is not allowed`() = runTest {
        val preview = openPanel()
        preview.onAllowed(false)
        preview.onFocus(b, playing.streamKey)
        settle()
        assertEquals(SportsLivePreviewState.Idle, preview.state.value)
        assertTrue(lookups.isEmpty())
    }

    @Test
    fun `an over-budget lookup leaves the art in place`() = runTest {
        // The resolver's budget (C1) answers NotPreviewable once spent: no player, no message.
        val preview = openPanel { SportsLivePreviewLookup.NotPreviewable }
        preview.onFocus(b, playing.streamKey)
        settle()
        assertEquals(SportsLivePreviewState.Idle, preview.state.value)
    }

    @Test
    fun `a source cooling down is not looked up`() = runTest {
        var now = 0L
        val cooldown = SportsLiveSourceCooldown(nowMs = { now })
        cooldown.note(b.addonBaseUrl, "addon", minutes = 5)
        assertEquals(SportsLivePreviewLookup.NotPreviewable, panelPreviewLookup(b, cooldown, found))
        assertTrue(lookups.isEmpty())
        now += 5 * 60_000L
        assertEquals(SportsLivePreviewLookup.Found(dualStream("b")), panelPreviewLookup(b, cooldown, found))
        assertEquals(listOf("b"), lookups)
    }

    @Test
    fun `data saver, the hover setting, low RAM or a decoder limit turn the preview off`() {
        assertTrue(panelPreviewAllowed(hoverPreview = true, dataSaver = false, lowRam = false, decoderLimit = false))
        assertFalse(panelPreviewAllowed(hoverPreview = true, dataSaver = true, lowRam = false, decoderLimit = false))
        assertFalse(panelPreviewAllowed(hoverPreview = false, dataSaver = false, lowRam = false, decoderLimit = false))
        assertFalse(panelPreviewAllowed(hoverPreview = true, dataSaver = false, lowRam = true, decoderLimit = false))
        assertFalse(panelPreviewAllowed(hoverPreview = true, dataSaver = false, lowRam = false, decoderLimit = true))
    }

    private fun viewModel(
        dataSaver: MutableStateFlow<Boolean> = MutableStateFlow(false),
        lowRam: Boolean = false
    ) = SportsLivePanelPreviewViewModel(
        resolve = found,
        hoverPreview = MutableStateFlow(true),
        dataSaver = dataSaver,
        lowRam = lowRam,
        onPreviewFailed = {}
    )

    @Test
    fun `the view model previews only off data saver and on a device with a decoder to spare`() = runTest(mainDispatcherRule.dispatcher) {
        val saver = MutableStateFlow(true)
        val vm = viewModel(dataSaver = saver)
        vm.onPanelOpen(true)
        vm.onFocus(b, playing.streamKey)
        settle()
        assertEquals(SportsLivePreviewState.Idle, vm.state.value)
        assertTrue(lookups.isEmpty())

        saver.value = false
        settle()
        assertTrue(vm.state.value is SportsLivePreviewState.Playing)

        // The preview found no decoder beside the game playing: it stops and never starts again.
        vm.onDecoderLimit()
        runCurrent()
        assertEquals(SportsLivePreviewState.Idle, vm.state.value)
        assertTrue(SportsLiveDecoderLimit.seen)
        vm.onFocus(c, playing.streamKey)
        settle()
        assertEquals(SportsLivePreviewState.Idle, vm.state.value)

        // A low-RAM device never previews.
        SportsLiveDecoderLimit.reset()
        lookups.clear()
        val lowRam = viewModel(lowRam = true)
        lowRam.onPanelOpen(true)
        lowRam.onFocus(b, playing.streamKey)
        settle()
        assertEquals(SportsLivePreviewState.Idle, lowRam.state.value)
        assertTrue(lookups.isEmpty())
    }

    @Test
    fun `a decoder limit seen in dual screen applies when the panel next opens`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        runCurrent()
        SportsLiveDecoderLimit.note()
        vm.onPanelOpen(true)
        vm.onFocus(b, playing.streamKey)
        settle()
        assertEquals(SportsLivePreviewState.Idle, vm.state.value)
        assertTrue(lookups.isEmpty())
    }
}
