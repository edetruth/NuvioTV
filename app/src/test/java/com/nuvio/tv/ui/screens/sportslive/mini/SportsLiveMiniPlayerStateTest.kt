package com.nuvio.tv.ui.screens.sportslive.mini

import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapSession
import com.nuvio.tv.ui.screens.sportslive.dual.dualItem
import com.nuvio.tv.ui.screens.sportslive.dual.dualStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveMiniPlayerStateTest {

    private val a = dualItem("a")
    private val b = dualItem("b")
    private val stream = dualStream("a")
    private val session = SportsLiveZapSession(listOf(a, b), 0, SportsLiveTab.LIVE_NOW, null, false)

    private fun TestScope.controller(): SportsLiveMiniPlayerController =
        SportsLiveMiniPlayerController(backgroundScope)

    private fun SportsLiveMiniPlayerController.playing() = state.value as SportsLiveMiniState.Playing

    @Test
    fun `Back from a zap-session player starts the mini player on its stream`() = runTest {
        val mini = controller()

        mini.onBackFromPlayer("a", session, stream)

        val playing = mini.playing()
        assertEquals(a, playing.item)
        assertEquals(stream, playing.stream)
        assertEquals(session, playing.session)
        assertTrue(mini.isActive)
    }

    @Test
    fun `Back before the first frame starts no mini player and offers no Resume`() = runTest {
        val mini = controller()

        // Still starting, connecting or switching links: a slow link must never play later in the stage (#175).
        mini.onBackFromPlayer("a", session, stream, started = false)
        mini.onBackFromPlayer("a", session, stream, paused = true, started = false)

        assertEquals(SportsLiveMiniState.Idle, mini.state.value)
        assertNull(mini.resume.value)
    }

    @Test
    fun `what Back leaves follows the first frame, then the pause`() {
        assertEquals(SportsLiveBackAction.NOTHING, sportsLiveBackAction(paused = false, started = false))
        assertEquals(SportsLiveBackAction.NOTHING, sportsLiveBackAction(paused = true, started = false))
        assertEquals(SportsLiveBackAction.RESUME_OFFER, sportsLiveBackAction(paused = true, started = true))
        assertEquals(SportsLiveBackAction.MINI_PLAYER, sportsLiveBackAction(paused = false, started = true))
    }

    @Test
    fun `Back from a player without a session, or for other content, starts nothing`() = runTest {
        val mini = controller()

        mini.onBackFromPlayer("movie", null, stream)
        mini.onBackFromPlayer("b", session, stream)
        mini.onBackFromPlayer("a", session, null)

        assertEquals(SportsLiveMiniState.Idle, mini.state.value)
    }

    @Test
    fun `every stop event ends it and frees the player at once`() = runTest {
        for (reason in SportsLiveMiniStop.entries) {
            val mini = controller()
            var released = 0
            mini.onStopped = { released++ }
            mini.onBackFromPlayer("a", session, stream)
            released = 0

            mini.stop(reason)

            assertEquals(SportsLiveMiniState.Idle, mini.state.value)
            assertEquals(reason, mini.lastStop)
            assertEquals(1, released)
            // A second stop does nothing.
            mini.stop(SportsLiveMiniStop.USER_STOP)
            assertEquals(1, released)
            assertEquals(reason, mini.lastStop)
        }
    }

    @Test
    fun `a recovery's next link replaces the stream of the same mini player only`() = runTest {
        val mini = controller()
        var released = 0
        mini.onStopped = { released++ }
        mini.onBackFromPlayer("a", session, stream)
        val sessionId = mini.playing().sessionId
        released = 0
        val next = dualStream("a2")

        // A late replacement for an earlier mini player is ignored.
        mini.onStreamReplaced(sessionId - 1, dualStream("old"))
        assertEquals(stream, mini.playing().stream)

        mini.onStreamReplaced(sessionId, next)
        assertEquals(next, mini.playing().stream)
        // Same session: the player is not restarted, and an expand plays the new link.
        assertEquals(sessionId, mini.playing().sessionId)
        assertEquals(0, released)
    }

    @Test
    fun `2 h without interaction stops it`() = runTest {
        val mini = controller()
        mini.onBackFromPlayer("a", session, stream)

        advanceTimeBy(SportsLiveMiniPlayerController.IDLE_TIMEOUT_MS - 1)
        runCurrent()
        assertTrue(mini.isActive)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(SportsLiveMiniState.Idle, mini.state.value)
        assertEquals(SportsLiveMiniStop.IDLE_TIMEOUT, mini.lastStop)
    }

    @Test
    fun `an interaction starts the 2 h over`() = runTest {
        val mini = controller()
        mini.onBackFromPlayer("a", session, stream)

        advanceTimeBy(SportsLiveMiniPlayerController.IDLE_TIMEOUT_MS - 1_000)
        mini.onInteraction()
        advanceTimeBy(SportsLiveMiniPlayerController.IDLE_TIMEOUT_MS - 1_000)
        runCurrent()
        assertTrue(mini.isActive)

        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(SportsLiveMiniStop.IDLE_TIMEOUT, mini.lastStop)
    }

    @Test
    fun `a stopped mini player's timer does not stop the next one`() = runTest {
        val mini = controller()
        mini.onBackFromPlayer("a", session, stream)
        advanceTimeBy(SportsLiveMiniPlayerController.IDLE_TIMEOUT_MS - 10)
        mini.stop(SportsLiveMiniStop.USER_STOP)
        mini.onBackFromPlayer("a", session, stream)

        advanceTimeBy(20)
        runCurrent()
        assertTrue(mini.isActive)
    }

    @Test
    fun `each start has its own session id, and only the current one's stream error stops it`() = runTest {
        val mini = controller()
        mini.onBackFromPlayer("a", session, stream)
        val first = mini.playing()
        mini.stop(SportsLiveMiniStop.OTHER_PLAYER)
        mini.onBackFromPlayer("a", session, stream)
        val second = mini.playing()
        assertNotEquals(first.sessionId, second.sessionId)

        mini.onStreamError(first.sessionId)
        assertTrue(mini.isActive)

        mini.onStreamError(second.sessionId)
        assertEquals(SportsLiveMiniStop.STREAM_ERROR, mini.lastStop)
        assertFalse(mini.isActive)
    }

    @Test
    fun `expand is asked only while it plays`() = runTest {
        val mini = controller()
        assertFalse(mini.requestExpand())

        mini.onBackFromPlayer("a", session, stream)
        val playing = mini.playing()
        assertTrue(mini.requestExpand())
        assertTrue(mini.isCurrent(playing))

        mini.stop(SportsLiveMiniStop.EXPANDED)
        assertFalse(mini.isCurrent(playing))
        assertFalse(mini.requestExpand())
    }

    @Test
    fun `only a plain http stream can play in the mini player`() {
        assertNull(sportsLiveMiniStream("torrent://abc", emptyMap(), emptyMap(), null, null, null))
        assertNull(sportsLiveMiniStream(null, emptyMap(), emptyMap(), null, null, null))
        for (local in listOf(
            "http://127.0.0.1:11470/abc/0",
            "http://localhost:8080/stream",
            "http://[::1]:9000/live.m3u8",
            "HTTP://LOCALHOST/x"
        )) {
            assertNull(local, sportsLiveMiniStream(local, emptyMap(), emptyMap(), null, null, null))
        }

        val mini = sportsLiveMiniStream(
            url = "https://example.invalid/live.m3u8",
            headers = mapOf("Referer" to "https://example.invalid/"),
            responseHeaders = mapOf("Content-Type" to "application/vnd.apple.mpegurl"),
            filename = "live.m3u8",
            name = null,
            addonName = "addon"
        )
        assertEquals("https://example.invalid/live.m3u8", mini?.getStreamUrl())
        assertEquals(mapOf("Referer" to "https://example.invalid/"), mini?.behaviorHints?.proxyHeaders?.request)
        assertEquals("application/vnd.apple.mpegurl", mini?.behaviorHints?.proxyHeaders?.response?.get("Content-Type"))
        assertEquals("live.m3u8", mini?.behaviorHints?.filename)
    }

    @Test
    fun `the mini player plays on only inside Sports and Live and the player`() {
        assertTrue(sportsLiveMiniPlaysOn(null))
        assertTrue(sportsLiveMiniPlaysOn("sports_live"))
        assertTrue(sportsLiveMiniPlaysOn("sports_live_game"))
        assertTrue(sportsLiveMiniPlaysOn("sports_live_player_card"))
        assertTrue(sportsLiveMiniPlaysOn("player/{streamUrl}/{title}"))
        assertFalse(sportsLiveMiniPlaysOn("home"))
        assertFalse(sportsLiveMiniPlaysOn("search"))
        assertFalse(sportsLiveMiniPlaysOn("stream/{videoId}"))
        assertFalse(sportsLiveMiniPlaysOn("settings"))
    }

    @Test
    fun `leaving Sports and Live stops the mini player, moving inside it does not`() = runTest {
        val mini = controller()
        mini.onBackFromPlayer("a", session, stream)

        mini.onRoute("sports_live_game")
        assertTrue(mini.isActive)

        mini.onRoute("home")
        assertFalse(mini.isActive)
        assertEquals(SportsLiveMiniStop.LEFT_SECTION, mini.lastStop)
    }

    @Test
    fun `Back from a player opened on Home starts no mini player`() = runTest {
        val mini = controller()
        val fromHome = SportsLiveZapSession(listOf(a, b), 0, SportsLiveTab.LIVE_NOW, null, true)

        mini.onBackFromPlayer("a", fromHome, stream)

        assertEquals(SportsLiveMiniState.Idle, mini.state.value)
    }

    @Test
    fun `Back on a top-level screen stops a playing mini player once, then does the usual`() = runTest {
        val mini = controller()
        mini.onBackFromPlayer("a", session, stream)

        assertTrue(mini.stopOnBack())
        assertEquals(SportsLiveMiniStop.USER_STOP, mini.lastStop)
        assertFalse(mini.stopOnBack())
    }

    @Test
    fun `MEDIA_STOP and a held Back stop it, a short Back and other keys do not`() = runTest {
        val stop = android.view.KeyEvent.KEYCODE_MEDIA_STOP
        val back = android.view.KeyEvent.KEYCODE_BACK
        val mini = controller()
        assertFalse(mini.onStopKey(stop, down = true, longPress = false))

        mini.onBackFromPlayer("a", session, stream)
        assertFalse(mini.onStopKey(back, down = true, longPress = false))
        assertFalse(mini.onStopKey(android.view.KeyEvent.KEYCODE_DPAD_UP, down = true, longPress = true))
        assertFalse(mini.onStopKey(stop, down = false, longPress = false))
        assertTrue(mini.isActive)

        assertTrue(mini.onStopKey(back, down = true, longPress = true))
        assertFalse(mini.isActive)

        mini.onBackFromPlayer("a", session, stream)
        assertTrue(mini.onStopKey(stop, down = true, longPress = false))
        assertFalse(mini.isActive)
    }
}
