package com.nuvio.tv.core.sportslive.scores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Results the user knows (live.20 1c rule 2): a final score shown, or a game watched live a while. */
class ResultSeenTest {

    private val second = 1_000L
    private val minute = 60 * second

    @Test
    fun `a final score shown is known at once`() {
        val seen = ResultSeen()
        seen.finalScoreShown("e1")
        seen.finalScoreShown("e1")
        assertEquals(setOf("e1"), seen.known.value)
    }

    @Test
    fun `a game watched live 20 min in ticks becomes known`() {
        val seen = ResultSeen()
        var now = 0L
        // Ticks every 5 s for 19 min: not yet.
        repeat((19 * minute / (5 * second)).toInt()) {
            seen.onLiveTick(listOf("e1"), now)
            now += 5 * second
        }
        assertTrue(seen.known.value.isEmpty())
        repeat((2 * minute / (5 * second)).toInt()) {
            seen.onLiveTick(listOf("e1"), now)
            now += 5 * second
        }
        assertEquals(setOf("e1"), seen.known.value)
    }

    @Test
    fun `time away from the game or long gaps between ticks do not count`() {
        val seen = ResultSeen()
        seen.onLiveTick(listOf("e1"), 0L)
        seen.onLiveTick(listOf("e1"), 5 * second)
        // Another game played for a while: e1's stretch stops.
        seen.onLiveTick(listOf("e2"), 10 * second)
        seen.onLiveTick(listOf("e1"), 30 * minute)
        // The app in the background: the next tick is far off.
        seen.onLiveTick(listOf("e1"), 60 * minute)
        assertEquals(5 * second, seen.liveWatchedMs("e1"))
        assertTrue(seen.known.value.isEmpty())
    }

    @Test
    fun `a profile switch forgets everything`() {
        val seen = ResultSeen()
        seen.finalScoreShown("e1")
        seen.onLiveTick(listOf("e2"), 0L)
        seen.onLiveTick(listOf("e2"), 5 * second)
        seen.clear()
        assertTrue(seen.known.value.isEmpty())
        assertEquals(0L, seen.liveWatchedMs("e2"))
    }
}
