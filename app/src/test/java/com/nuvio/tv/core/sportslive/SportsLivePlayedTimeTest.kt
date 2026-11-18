package com.nuvio.tv.core.sportslive

import org.junit.Assert.assertEquals
import org.junit.Test

class SportsLivePlayedTimeTest {
    private var clock = 0L
    private val played = SportsLivePlayedTime { clock }

    @Test
    fun `only playing time counts, the running stretch included`() {
        played.onPlaying("r", playing = true)
        clock += 60_000
        played.onPlaying("r", playing = false)
        clock += 10 * 60_000 // paused or away: not counted
        assertEquals(60_000L, played.playedMs("r"))
        played.onPlaying("r", playing = true)
        clock += 30_000
        assertEquals(90_000L, played.playedMs("r"))
        assertEquals(0L, played.playedMs("other"))
    }

    @Test
    fun `forget and clear drop what was played`() {
        played.onPlaying("r", playing = true)
        clock += 60_000
        played.forget("r")
        assertEquals(0L, played.playedMs("r"))
        played.onPlaying("s", playing = true)
        clock += 60_000
        played.clear()
        assertEquals(0L, played.playedMs("s"))
    }
}
