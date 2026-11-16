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
}
