package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.MySport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Add ‹sport› to My sports?" after a More live game: Not now and two unanswered showings stop it, per sport. */
class SportsLiveAddSportPromptTest {

    @Test
    fun `not now stops that sport only`() {
        val memory = SportsLiveAddSportPrompt.Memory().decline(MySport.TENNIS)
        assertFalse(memory.asks(MySport.TENNIS))
        assertTrue(memory.asks(MySport.GOLF))
        assertEquals(setOf("tennis"), memory.notAsked)
    }

    @Test
    fun `left unanswered once it asks again, twice it stops`() {
        val once = SportsLiveAddSportPrompt.Memory().ignore(MySport.MMA)
        assertTrue(once.asks(MySport.MMA))
        val twice = once.ignore(MySport.MMA)
        assertFalse(twice.asks(MySport.MMA))
        assertEquals(SportsLiveAddSportPrompt.MAX_IGNORES, twice.ignored["mma"])
    }

    @Test
    fun `the counts survive being stored`() {
        val memory = SportsLiveAddSportPrompt.Memory().ignore(MySport.MMA).ignore(MySport.GOLF).ignore(MySport.GOLF)
        val stored = memory.encodeIgnored()
        assertEquals(memory.ignored, SportsLiveAddSportPrompt.decodeIgnored(stored))
        // Unreadable entries are skipped.
        assertEquals(mapOf("golf" to 1), SportsLiveAddSportPrompt.decodeIgnored(setOf("golf:1", "x", ":3", "mma:")))
        assertEquals(emptyMap<String, Int>(), SportsLiveAddSportPrompt.decodeIgnored(null))
    }
}
