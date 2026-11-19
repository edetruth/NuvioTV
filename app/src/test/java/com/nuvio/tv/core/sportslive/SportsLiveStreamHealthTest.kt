package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveStreamHealth.Problem
import com.nuvio.tv.core.sportslive.SportsLiveStreamHealth.Sample
import com.nuvio.tv.core.sportslive.SportsLiveStreamHealth.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #138: the shared stream-health monitor, on a fake clock. */
class SportsLiveStreamHealthTest {

    private var now = 1_000_000L
    private val health = SportsLiveStreamHealth(nowMs = { now })

    private var position = 0L
    private var frames = 0L

    private fun playing(
        state: State = State.READY,
        playWhenReady: Boolean = true,
        renderedFrames: Long? = frames,
        isLive: Boolean = true,
        isPlayingAd: Boolean = false
    ) = Sample(state, playWhenReady, position, renderedFrames, isLive, isPlayingAd)

    /** Samples once a second for [ms], advancing position and frames by [move]; the first problem, or null. */
    private fun run(ms: Long, move: Boolean, sample: () -> Sample = { playing() }): Problem? {
        var elapsed = 0L
        while (elapsed < ms) {
            now += SportsLiveStreamHealth.POLL_INTERVAL_MS
            elapsed += SportsLiveStreamHealth.POLL_INTERVAL_MS
            if (move) {
                position += 1_000
                frames += 25
            }
            health.sample(sample())?.let { return it }
        }
        return null
    }

    private fun started() {
        health.onFirstFrame()
        health.sample(playing())
    }

    @Test
    fun `nothing is watched before the first frame`() {
        assertNull(run(60_000, move = false) { playing(state = State.BUFFERING) })
        assertNull(run(60_000, move = false))
    }

    @Test
    fun `buffering for twelve seconds is reported once`() {
        started()
        // Timed from the first sample that buffers.
        assertNull(run(SportsLiveStreamHealth.BUFFERING_TOO_LONG_MS, move = false) { playing(state = State.BUFFERING) })
        assertEquals(Problem.BUFFERING_TOO_LONG, run(1_000, move = false) { playing(state = State.BUFFERING) })
        // Reported once until the owner recovers.
        assertNull(run(30_000, move = false) { playing(state = State.BUFFERING) })
        health.rebaseline()
        assertEquals(
            Problem.BUFFERING_TOO_LONG,
            run(SportsLiveStreamHealth.BUFFERING_TOO_LONG_MS + 1_000, move = false) { playing(state = State.BUFFERING) }
        )
    }

    @Test
    fun `short buffering that recovers is not a problem`() {
        started()
        assertNull(run(8_000, move = false) { playing(state = State.BUFFERING) })
        assertNull(run(5_000, move = true))
        assertNull(run(8_000, move = false) { playing(state = State.BUFFERING) })
        assertNull(run(1_000, move = true))
    }

    @Test
    fun `ready with no new frame and no position advance for eight seconds is frozen`() {
        started()
        assertNull(run(10_000, move = true))
        assertTrue(health.isProgressing)
        assertNull(run(SportsLiveStreamHealth.FROZEN_MS - 1_000, move = false))
        assertFalse(health.isProgressing)
        assertEquals(Problem.FROZEN, run(1_000, move = false))
    }

    @Test
    fun `frames alone moving is not frozen`() {
        started()
        assertNull(run(30_000, move = false) {
            frames += 25
            playing()
        })
        assertTrue(health.isProgressing)
    }

    @Test
    fun `position alone moving is not frozen, so audio-only streams are fine`() {
        started()
        assertNull(run(30_000, move = false) {
            position += 1_000
            playing()
        })
        // No video track: the position alone decides.
        assertNull(run(30_000, move = false) {
            position += 1_000
            playing(renderedFrames = null)
        })
        assertEquals(Problem.FROZEN, run(SportsLiveStreamHealth.FROZEN_MS, move = false) { playing(renderedFrames = null) })
    }

    @Test
    fun `a paused player is never frozen`() {
        started()
        assertNull(run(60_000, move = false) { playing(playWhenReady = false) })
        assertNull(run(60_000, move = false) { playing(state = State.BUFFERING, playWhenReady = false) })
        // Playing again: the stall timing starts over.
        assertNull(run(SportsLiveStreamHealth.FROZEN_MS - 1_000, move = false))
    }

    @Test
    fun `an ad is never frozen`() {
        started()
        assertNull(run(60_000, move = false) { playing(isPlayingAd = true) })
        assertNull(run(SportsLiveStreamHealth.FROZEN_MS - 1_000, move = false))
    }

    @Test
    fun `a discontinuity jumping the position back counts as moving`() {
        started()
        assertNull(run(SportsLiveStreamHealth.FROZEN_MS - 2_000, move = false))
        position -= 30_000
        assertNull(run(1_000, move = false))
        assertTrue(health.isProgressing)
        assertNull(run(SportsLiveStreamHealth.FROZEN_MS - 2_000, move = false))
    }

    @Test
    fun `a new decoder restarting the frame count counts as moving`() {
        started()
        frames = 5_000
        assertNull(run(SportsLiveStreamHealth.FROZEN_MS - 2_000, move = false))
        frames = 0
        assertNull(run(1_000, move = false))
        assertTrue(health.isProgressing)
    }

    @Test
    fun `an unexpected end of a live stream is reported`() {
        started()
        assertNull(run(5_000, move = true))
        // The playlist ended: the window is no longer live, but the stream was.
        assertEquals(Problem.UNEXPECTED_END, run(1_000, move = false) { playing(state = State.ENDED, isLive = false) })
    }

    @Test
    fun `a replay that ends is not a problem`() {
        health.onFirstFrame()
        assertNull(run(5_000, move = true) { playing(isLive = false) })
        assertNull(run(5_000, move = false) { playing(state = State.ENDED, isLive = false) })
    }

    @Test
    fun `two errors within thirty seconds are a burst`() {
        started()
        assertEquals(Problem.ERROR, health.onError())
        now += SportsLiveStreamHealth.ERROR_BURST_WINDOW_MS - 1
        assertEquals(Problem.ERROR_BURST, health.onError())
    }

    @Test
    fun `errors further apart are not a burst`() {
        started()
        assertEquals(Problem.ERROR, health.onError())
        now += SportsLiveStreamHealth.ERROR_BURST_WINDOW_MS
        assertEquals(Problem.ERROR, health.onError())
    }

    @Test
    fun `a first frame again on the same link keeps its errors, another link forgets them`() {
        health.onFirstFrame(link = "https://example.invalid/a.m3u8")
        assertEquals(Problem.ERROR, health.onError())
        health.onFirstFrame(link = "https://example.invalid/a.m3u8")
        assertEquals(Problem.ERROR_BURST, health.onError())
        health.onFirstFrame(link = "https://example.invalid/b.m3u8")
        assertEquals(Problem.ERROR, health.onError())
    }

    @Test
    fun `reset stops watching until the next first frame`() {
        started()
        health.reset()
        assertNull(run(60_000, move = false))
        health.onFirstFrame()
        assertEquals(Problem.FROZEN, run(SportsLiveStreamHealth.FROZEN_MS + 1_000, move = false))
    }
}
