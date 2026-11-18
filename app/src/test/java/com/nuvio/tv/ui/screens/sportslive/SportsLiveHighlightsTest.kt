package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.scores.GameHighlight
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Game page highlights (#61): playlist order, auto-advance, failed links and clips, transient playback. */
class SportsLiveHighlightsTest {

    private fun clip(id: String, vararg links: String) = GameHighlight(id, "Clip $id", null, 30, links.toList())

    private val a = clip("a", "https://x/a.m3u8", "https://x/a.mp4")
    private val b = clip("b", "https://x/b.mp4")
    private val c = clip("c", "https://x/c.m3u8")

    @After
    fun tearDown() = SportsLiveHighlights.reset()

    @Test
    fun `Watch all - clips play in order, each starting on its best link, then back to the Game page`() {
        val playlist = HighlightPlaylist(listOf(a, b, c))
        assertEquals(HighlightClipPlay("a", "https://x/a.m3u8", "Clip a"), playlist.current)
        assertEquals(HighlightStep.Play(HighlightClipPlay("b", "https://x/b.mp4", "Clip b")), playlist.ended())
        assertEquals(HighlightStep.Play(HighlightClipPlay("c", "https://x/c.m3u8", "Clip c")), playlist.ended())
        assertEquals(HighlightStep.Done(), playlist.ended())
        assertNull(playlist.current)
        assertEquals(HighlightStep.Done(), playlist.ended())
    }

    @Test
    fun `a failed link tries the clip's next link, then the clip is given up and the next one plays`() {
        val playlist = HighlightPlaylist(listOf(a, b))
        assertEquals(HighlightStep.Play(HighlightClipPlay("a", "https://x/a.mp4", "Clip a")), playlist.failed())
        assertEquals(HighlightStep.Play(HighlightClipPlay("b", "https://x/b.mp4", "Clip b"), failedClipId = "a"), playlist.failed())
        assertEquals(HighlightStep.Done(failedClipId = "b"), playlist.failed())
    }

    @Test
    fun `one clip - it plays alone and its end returns to the Game page`() {
        val playlist = HighlightPlaylist(listOf(b))
        assertEquals("b", playlist.current!!.clipId)
        assertEquals(HighlightStep.Done(), playlist.ended())
    }

    @Test
    fun `failed clips are hidden for the session and left out of the next Watch all`() {
        assertEquals("https://x/a.m3u8", SportsLiveHighlights.start(listOf(a, b))!!.url)
        assertTrue(SportsLiveHighlights.isClip("https://x/a.mp4"))
        assertTrue(SportsLiveHighlights.isClip("https://x/b.mp4"))
        assertFalse(SportsLiveHighlights.isClip("https://x/c.m3u8"))
        assertFalse(SportsLiveHighlights.isClip(null))

        SportsLiveHighlights.failed()
        val step = SportsLiveHighlights.failed()
        assertEquals("a", step.failedClipId)
        assertEquals(setOf("a"), SportsLiveHighlights.failedClipIds.value)

        // The next playlist skips it.
        assertEquals("b", SportsLiveHighlights.start(listOf(a, b, c))!!.clipId)
        assertEquals("c", (SportsLiveHighlights.ended() as HighlightStep.Play).clip.clipId)
        assertEquals(HighlightStep.Done(), SportsLiveHighlights.ended())
        // Done: nothing plays any more.
        assertFalse(SportsLiveHighlights.isClip("https://x/c.m3u8"))
    }

    @Test
    fun `Back stops the playlist - the player no longer treats its links as clips`() {
        SportsLiveHighlights.start(listOf(a, b))
        SportsLiveHighlights.stop()
        assertFalse(SportsLiveHighlights.isClip("https://x/a.m3u8"))
        assertEquals(HighlightStep.Done(), SportsLiveHighlights.ended())
        assertTrue(SportsLiveHighlights.failedClipIds.value.isEmpty())
    }

    @Test
    fun `a player left any other way ends the playlist, but not one already replaced by the next clip`() {
        SportsLiveHighlights.start(listOf(a, b))
        // Auto-advance: the next player plays b, then the old player (a) is disposed.
        SportsLiveHighlights.ended()
        SportsLiveHighlights.playerLeft("https://x/a.m3u8")
        assertTrue(SportsLiveHighlights.isClip("https://x/b.mp4"))
        // b's player is left by a zap or party: the playlist ends.
        SportsLiveHighlights.playerLeft("https://x/b.mp4")
        assertFalse(SportsLiveHighlights.isClip("https://x/b.mp4"))
        assertEquals(HighlightStep.Done(), SportsLiveHighlights.ended())
        SportsLiveHighlights.playerLeft(null)
    }

    @Test
    fun `nothing playable - nothing starts`() {
        assertNull(SportsLiveHighlights.start(emptyList()))
        assertNull(SportsLiveHighlights.start(listOf(clip("x"))))
        assertFalse(SportsLiveHighlights.isClip("https://x/a.m3u8"))
    }

    @Test
    fun `a clip plays as a transient playback - no content id, type or video id, no autoplay fallback`() {
        val route = highlightPlayerRoute(HighlightClipPlay("a", "https://x/a.m3u8?t=1&v=2", "Knicks vs. Raptors"), launchStartedAtMs = 42L)
        assertTrue(route.startsWith("player/https%3A%2F%2Fx%2Fa.m3u8%3Ft%3D1%26v%3D2/Knicks%20vs.%20Raptors?"))
        val query = route.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        // No identity: no watch progress, Continue watching, Trakt scrobble, addon events, link cache or sports failover.
        assertEquals("", query.getValue("contentId"))
        assertEquals("", query.getValue("contentType"))
        assertEquals("", query.getValue("videoId"))
        assertEquals("", query.getValue("headers"))
        assertEquals("", query.getValue("addonName"))
        assertEquals("false", query.getValue("autoPlayNav"))
        assertEquals("false", query.getValue("fromLinkCache"))
        assertEquals("42", query.getValue("launchStartedAtMs"))
    }
}
