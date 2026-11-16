package com.nuvio.tv.ui.screens.sportslive.dual

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SportsLiveDualStateTest {

    private val a = dualItem("a")
    private val b = dualItem("b")
    private val c = dualItem("c")
    private val initial = DualState(DualPane(a), DualPane(b))

    @Test
    fun `OK hands over only a playing pane's stream`() {
        val stream = dualStream("a")
        val resolved = initial.reduce(DualAction.Resolved(a, stream))
        // Looking up, then starting: the normal play path.
        assertNull(initial.left.okStream)
        assertNull(resolved.left.okStream)
        val playing = resolved.reduce(DualAction.FirstFrame(a))
        assertSame(stream, playing.left.okStream)
        // Failed or out of decoders: the normal play path again.
        assertNull(playing.reduce(DualAction.Failed(a)).left.okStream)
        assertNull(playing.reduce(DualAction.DeviceLimit(a)).left.okStream)
        assertNull(initial.reduce(DualAction.Resolved(a, null)).left.okStream)
    }

    @Test
    fun `starts highlighted on the left with both panes loading`() {
        assertEquals(Side.LEFT, initial.highlighted)
        assertEquals(PaneStatus.Loading, initial.left.status)
        assertEquals(PaneStatus.Loading, initial.right.status)
        assertEquals(Side.LEFT, initial.audibleSide)
    }

    @Test
    fun `highlight moves the sound`() {
        val state = initial.reduce(DualAction.Highlight(Side.RIGHT))
        assertEquals(Side.RIGHT, state.highlighted)
        assertEquals(Side.RIGHT, state.audibleSide)
        assertEquals(Side.LEFT, state.reduce(DualAction.Highlight(Side.LEFT)).highlighted)
    }

    @Test
    fun `swap trades the panes and the highlight follows the same game`() {
        val playing = initial.reduce(DualAction.Resolved(a, dualStream("a"))).reduce(DualAction.FirstFrame(a))
        val swapped = playing.reduce(DualAction.Swap)
        assertEquals(playing.right, swapped.left)
        assertEquals(playing.left, swapped.right)
        assertEquals(Side.RIGHT, swapped.highlighted)
        assertEquals(a, swapped.pane(swapped.highlighted).item)
    }

    @Test
    fun `zap request puts the pane on the new item loading`() {
        val playing = initial.reduce(DualAction.Resolved(a, dualStream("a"))).reduce(DualAction.FirstFrame(a))
        val state = playing.reduce(DualAction.ZapRequested(Side.LEFT, c))
        assertEquals(DualPane(c, null, PaneStatus.Loading), state.left)
        assertEquals(playing.right, state.right)
    }

    @Test
    fun `a resolved stream keeps the pane loading with the stream`() {
        val stream = dualStream("b")
        val state = initial.reduce(DualAction.Resolved(b, stream))
        assertEquals(DualPane(b, stream, PaneStatus.Loading), state.right)
        assertEquals(initial.left, state.left)
    }

    @Test
    fun `no stream is an error`() {
        val state = initial.reduce(DualAction.Resolved(a, null))
        assertEquals(PaneStatus.Error, state.left.status)
        assertNull(state.left.stream)
    }

    @Test
    fun `first frame plays`() {
        val state = initial.reduce(DualAction.Resolved(a, dualStream("a"))).reduce(DualAction.FirstFrame(a))
        assertEquals(PaneStatus.Playing, state.left.status)
    }

    @Test
    fun `first frame before a stream is ignored`() {
        assertSame(initial, initial.reduce(DualAction.FirstFrame(a)))
    }

    @Test
    fun `a playback error is an error`() {
        val state = initial.reduce(DualAction.Resolved(a, dualStream("a")))
            .reduce(DualAction.FirstFrame(a))
            .reduce(DualAction.Failed(a))
        assertEquals(PaneStatus.Error, state.left.status)
    }

    @Test
    fun `a timeout while starting is an error, after the first frame it is ignored`() {
        val starting = initial.reduce(DualAction.Resolved(a, dualStream("a")))
        assertEquals(PaneStatus.Error, starting.reduce(DualAction.TimedOut(a)).left.status)
        val playing = starting.reduce(DualAction.FirstFrame(a))
        assertSame(playing, playing.reduce(DualAction.TimedOut(a)))
    }

    @Test
    fun `device limit keeps the other pane playing with sound`() {
        val state = initial.reduce(DualAction.Resolved(a, dualStream("a")))
            .reduce(DualAction.FirstFrame(a))
            .reduce(DualAction.Resolved(b, dualStream("b")))
            .reduce(DualAction.Highlight(Side.RIGHT))
            .reduce(DualAction.DeviceLimit(b))
        assertEquals(PaneStatus.DeviceLimit, state.right.status)
        assertEquals(PaneStatus.Playing, state.left.status)
        assertEquals(Side.RIGHT, state.highlighted)
        assertEquals(Side.LEFT, state.audibleSide)
    }

    @Test
    fun `sound moves to the playing pane when the highlighted one fails`() {
        val state = initial.reduce(DualAction.Resolved(a, dualStream("a")))
            .reduce(DualAction.FirstFrame(a))
            .reduce(DualAction.Resolved(b, dualStream("b")))
            .reduce(DualAction.FirstFrame(b))
            .reduce(DualAction.Highlight(Side.RIGHT))
        assertEquals(Side.RIGHT, state.audibleSide)
        val failed = state.reduce(DualAction.Failed(b))
        assertEquals(PaneStatus.Error, failed.right.status)
        assertEquals(Side.LEFT, failed.audibleSide)
    }

    @Test
    fun `stale results for an item no pane shows are ignored`() {
        val zapped = initial.reduce(DualAction.ZapRequested(Side.LEFT, c))
        assertSame(zapped, zapped.reduce(DualAction.Resolved(a, dualStream("a"))))
        assertSame(zapped, zapped.reduce(DualAction.FirstFrame(a)))
        assertSame(zapped, zapped.reduce(DualAction.Failed(a)))
        assertSame(zapped, zapped.reduce(DualAction.TimedOut(a)))
        assertSame(zapped, zapped.reduce(DualAction.DeviceLimit(a)))
    }

    @Test
    fun `a second result for a pane that already has a stream is ignored`() {
        val resolved = initial.reduce(DualAction.Resolved(a, dualStream("a")))
        assertSame(resolved, resolved.reduce(DualAction.Resolved(a, dualStream("other"))))
        assertSame(resolved, resolved.reduce(DualAction.Resolved(a, null)))
    }

    @Test
    fun `a result after a swap reaches the pane showing its game`() {
        val stream = dualStream("a")
        val state = initial.reduce(DualAction.Swap).reduce(DualAction.Resolved(a, stream))
        assertEquals(DualPane(a, stream, PaneStatus.Loading), state.right)
    }

    @Test
    fun `sound indicator moves only while the audible pane plays, the other is muted`() {
        val playing = initial.reduce(DualAction.Resolved(a, dualStream("a"))).reduce(DualAction.FirstFrame(a))
        assertEquals(PaneSound.AUDIBLE_PLAYING, playing.paneSound(Side.LEFT, isPlaying = true))
        // Buffering or stalled: the speaker stays, the bars stop.
        assertEquals(PaneSound.AUDIBLE, playing.paneSound(Side.LEFT, isPlaying = false))
        assertEquals(PaneSound.MUTED, playing.paneSound(Side.RIGHT, isPlaying = true))
        // Still loading: an isPlaying player does not count before the first frame.
        assertEquals(PaneSound.AUDIBLE, initial.paneSound(Side.LEFT, isPlaying = true))
    }

    @Test
    fun `sound indicator follows the sound, not a failed highlighted pane`() {
        val state = initial
            .reduce(DualAction.Resolved(a, dualStream("a"))).reduce(DualAction.FirstFrame(a))
            .reduce(DualAction.Highlight(Side.RIGHT))
            .reduce(DualAction.Resolved(b, null))
        assertEquals(Side.RIGHT, state.highlighted)
        assertEquals(PaneSound.MUTED, state.paneSound(Side.RIGHT, isPlaying = false))
        assertEquals(PaneSound.AUDIBLE_PLAYING, state.paneSound(Side.LEFT, isPlaying = true))
    }
}

internal fun dualItem(id: String) = SportsLiveItem(
    meta = MetaPreview(
        id = id,
        type = ContentType.UNKNOWN,
        rawType = "tv",
        name = id,
        poster = null,
        posterShape = PosterShape.LANDSCAPE,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    ),
    addonId = "addon",
    addonName = "addon",
    addonBaseUrl = "https://addon",
    catalogKey = "addon:tv:live",
    catalogName = "Live",
    kind = SportsLiveKind.EVENTS,
    isLive = true,
    isChannel = false,
    startMs = null,
    sport = null
)

internal fun dualStream(name: String) = Stream(
    name = name,
    title = null,
    description = null,
    url = "https://example.invalid/$name.m3u8",
    ytId = null,
    infoHash = null,
    fileIdx = null,
    externalUrl = null,
    behaviorHints = null,
    addonName = "addon",
    addonLogo = null
)
