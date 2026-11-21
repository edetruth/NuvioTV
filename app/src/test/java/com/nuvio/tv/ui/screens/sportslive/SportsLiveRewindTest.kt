package com.nuvio.tv.ui.screens.sportslive

import android.view.KeyEvent
import com.nuvio.tv.data.local.InternalPlayerEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SportsLiveRewindTest {

    private fun delta(
        key: LiveRewindKey,
        hasSession: Boolean = true,
        engine: InternalPlayerEngine = InternalPlayerEngine.EXOPLAYER,
        isDynamic: Boolean = true,
        windowMs: Long = 120_000L,
        liveEdgeMs: Long = 100_000L,
        fromEdgeMs: Long = 0L
    ) = SportsLiveRewind.seekDeltaMs(hasSession, engine, isDynamic, windowMs, liveEdgeMs, fromEdgeMs, key)

    @Test
    fun `rewind steps back ten seconds inside a session window`() {
        assertEquals(-10_000L, delta(LiveRewindKey.REWIND))
        assertEquals(-10_000L, delta(LiveRewindKey.LEFT_HOLD, fromEdgeMs = 40_000L))
    }

    @Test
    fun `upstream blocking is kept without a session, on mpv, for static items and short windows`() {
        assertNull(delta(LiveRewindKey.REWIND, hasSession = false))
        assertNull(delta(LiveRewindKey.REWIND, engine = InternalPlayerEngine.MVP_PLAYER))
        assertNull(delta(LiveRewindKey.REWIND, engine = InternalPlayerEngine.AUTO))
        assertNull(delta(LiveRewindKey.REWIND, isDynamic = false))
        assertNull(delta(LiveRewindKey.REWIND, windowMs = 30_000L))
        assertEquals(-10_000L, delta(LiveRewindKey.REWIND, windowMs = 30_001L))
    }

    @Test
    fun `rewind stops short of the window start`() {
        assertEquals(-5_000L, delta(LiveRewindKey.REWIND, fromEdgeMs = 85_000L))
        assertNull(delta(LiveRewindKey.REWIND, fromEdgeMs = 90_000L))
    }

    @Test
    fun `a 48 s hls window with an 18 s live offset rewinds 20 s`() {
        // highfly: 8 x 6 s segments, Exo's default live offset is 3 target durations.
        assertEquals(-10_000L, delta(LiveRewindKey.REWIND, windowMs = 48_000L, liveEdgeMs = 30_000L))
        assertEquals(-10_000L, delta(LiveRewindKey.REWIND, windowMs = 48_000L, liveEdgeMs = 30_000L, fromEdgeMs = 10_000L))
        assertNull(delta(LiveRewindKey.REWIND, windowMs = 48_000L, liveEdgeMs = 30_000L, fromEdgeMs = 20_000L))
        assertEquals(10_000L, delta(LiveRewindKey.FAST_FORWARD, windowMs = 48_000L, liveEdgeMs = 30_000L, fromEdgeMs = 20_000L))
    }

    @Test
    fun `forward is clamped to the live edge`() {
        assertNull(delta(LiveRewindKey.FAST_FORWARD, fromEdgeMs = 0L))
        assertEquals(4_000L, delta(LiveRewindKey.FAST_FORWARD, fromEdgeMs = 4_000L))
        assertEquals(10_000L, delta(LiveRewindKey.RIGHT_HOLD, fromEdgeMs = 80_000L))
    }

    @Test
    fun `a short right keeps upstream behavior`() {
        assertNull(delta(LiveRewindKey.RIGHT_SHORT, fromEdgeMs = 80_000L))
    }

    @Test
    fun `keys map left only when held`() {
        assertNull(SportsLiveRewind.keyFor(KeyEvent.KEYCODE_DPAD_LEFT, 0))
        assertEquals(LiveRewindKey.LEFT_HOLD, SportsLiveRewind.keyFor(KeyEvent.KEYCODE_DPAD_LEFT, 3))
        assertEquals(LiveRewindKey.RIGHT_SHORT, SportsLiveRewind.keyFor(KeyEvent.KEYCODE_DPAD_RIGHT, 0))
        assertEquals(LiveRewindKey.RIGHT_HOLD, SportsLiveRewind.keyFor(KeyEvent.KEYCODE_DPAD_RIGHT, 1))
        assertEquals(LiveRewindKey.REWIND, SportsLiveRewind.keyFor(KeyEvent.KEYCODE_MEDIA_REWIND, 0))
        assertEquals(LiveRewindKey.FAST_FORWARD, SportsLiveRewind.keyFor(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, 0))
        assertNull(SportsLiveRewind.keyFor(KeyEvent.KEYCODE_DPAD_UP, 0))
    }

    @Test
    fun `indicator shows only when behind the edge of a seekable session window`() {
        val behind = LiveWindow(isDynamic = true, windowMs = 120_000L, liveEdgeMs = 100_000L, behindEdgeMs = 80_000L)
        val exo = InternalPlayerEngine.EXOPLAYER
        assertEquals(80_000L, SportsLiveRewind.indicatorBehindMs(true, exo, behind, covered = false))
        assertNull(SportsLiveRewind.indicatorBehindMs(true, exo, behind.copy(behindEdgeMs = 2_000L), covered = false))
        assertNull(SportsLiveRewind.indicatorBehindMs(false, exo, behind, covered = false))
        assertNull(SportsLiveRewind.indicatorBehindMs(true, InternalPlayerEngine.MVP_PLAYER, behind, covered = false))
        assertNull(SportsLiveRewind.indicatorBehindMs(true, exo, behind.copy(windowMs = 20_000L), covered = false))
        assertNull(SportsLiveRewind.indicatorBehindMs(true, exo, null, covered = false))
    }

    @Test
    fun `indicator hides while controls or a panel cover the player`() {
        val behind = LiveWindow(isDynamic = true, windowMs = 120_000L, liveEdgeMs = 100_000L, behindEdgeMs = 80_000L)
        assertNull(SportsLiveRewind.indicatorBehindMs(true, InternalPlayerEngine.EXOPLAYER, behind, covered = true))
    }

    @Test
    fun `a paused live game says how far behind live it is and offers Go live where the window can be sought (Q19)`() {
        val exo = InternalPlayerEngine.EXOPLAYER
        val behind = LiveWindow(isDynamic = true, windowMs = 120_000L, liveEdgeMs = 100_000L, behindEdgeMs = 23_000L)
        assertEquals(SportsLivePausedLive(behindMs = 23_000L, canGoLive = true), SportsLivePausedLive.of(true, exo, behind))
        // Just paused (a moment behind): Go live, no time yet.
        assertEquals(SportsLivePausedLive(behindMs = null, canGoLive = true), SportsLivePausedLive.of(true, exo, behind.copy(behindEdgeMs = 2_000L)))
        // A window that cannot be sought (short, static, another engine, unknown): only Paused and OK resume.
        assertEquals(SportsLivePausedLive(null, false), SportsLivePausedLive.of(true, exo, behind.copy(windowMs = 20_000L)))
        assertEquals(SportsLivePausedLive(null, false), SportsLivePausedLive.of(true, InternalPlayerEngine.MVP_PLAYER, behind))
        assertEquals(SportsLivePausedLive(null, false), SportsLivePausedLive.of(true, exo, null))
    }

    @Test
    fun `behind time formats as minutes and seconds`() {
        assertEquals("0:00", SportsLiveRewind.formatBehind(0L))
        assertEquals("1:20", SportsLiveRewind.formatBehind(80_000L))
        assertEquals("1:02:03", SportsLiveRewind.formatBehind(3_723_000L))
    }

    @Test
    fun `the score hold reads a live stream's distance behind its edge, nothing for a replay or no timeline (G7)`() {
        assertEquals(90_000L, behindLiveForScores(LiveWindow(isDynamic = true, windowMs = 300_000L, liveEdgeMs = 290_000L, behindEdgeMs = 90_000L)))
        assertNull(behindLiveForScores(LiveWindow(isDynamic = false, windowMs = 300_000L, liveEdgeMs = 300_000L, behindEdgeMs = 90_000L)))
        assertNull(behindLiveForScores(null))
    }
}
