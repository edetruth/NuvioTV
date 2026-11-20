package com.nuvio.tv.core.sportslive

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SportsLiveReplayResumeTest {

    private val saved = mutableListOf<Triple<String, Long, Long>>()
    private val min = 60_000L
    private val hourMs = 60 * min

    @Before
    fun setUp() {
        SportsLiveReplayResume.reset()
        SportsLiveReplayResume.sink = { event, position, duration -> saved += Triple(event, position, duration) }
    }

    @After
    fun tearDown() = SportsLiveReplayResume.reset()

    @Test
    fun `a registered replay's progress is saved under its ESPN event, anything else stores nothing`() {
        SportsLiveReplayResume.register(listOf("replay-a", "replay-b"), "401", startMs = null)

        SportsLiveReplayResume.onProgress("replay-b", 20 * min, 3 * hourMs)
        // A live stream has no duration; an unregistered player is not a replay of a card.
        SportsLiveReplayResume.onProgress("replay-a", 5 * min, 0L)
        SportsLiveReplayResume.onProgress("movie", 5 * min, 2 * hourMs)

        assertEquals(listOf(Triple("401", 20 * min, 3 * hourMs)), saved)
    }

    @Test
    fun `the player starts where the card said, until it reports a place past it`() {
        SportsLiveReplayResume.register(listOf("replay-a"), "401", startMs = 72 * min)
        assertEquals(72 * min, SportsLiveReplayResume.startFor("replay-a"))

        // Before its seek the player reports 0: not saved, the start stands.
        SportsLiveReplayResume.onProgress("replay-a", 1_000L, 3 * hourMs)
        assertEquals(emptyList<Triple<String, Long, Long>>(), saved)
        assertEquals(72 * min, SportsLiveReplayResume.startFor("replay-a"))

        SportsLiveReplayResume.onProgress("replay-a", 73 * min, 3 * hourMs)
        assertEquals(1, saved.size)
        assertNull(SportsLiveReplayResume.startFor("replay-a"))
    }

    @Test
    fun `Start over registers no start`() {
        SportsLiveReplayResume.register(listOf("replay-a"), "401", startMs = 72 * min)
        SportsLiveReplayResume.register(listOf("replay-a"), "401", startMs = null)
        assertNull(SportsLiveReplayResume.startFor("replay-a"))
    }

    @Test
    fun `a card offers to resume only a place worth it`() {
        assertEquals(72 * min, SportsLiveReplayResume.resumeAt(ReplayPosition(72 * min, 3 * hourMs, 0L)))
        assertNull(SportsLiveReplayResume.resumeAt(ReplayPosition(30_000L, 3 * hourMs, 0L)))
        assertNull(SportsLiveReplayResume.resumeAt(ReplayPosition(176 * min, 3 * hourMs, 0L)))
        assertNull(SportsLiveReplayResume.resumeAt(null))
    }

    @Test
    fun `the place reads as a clock`() {
        assertEquals("1:12:30", SportsLiveReplayResume.clock(hourMs + 12 * min + 30_000L))
        assertEquals("12:05", SportsLiveReplayResume.clock(12 * min + 5_000L))
    }

    @Test
    fun `places are kept 14 days, newest first, and survive storage`() {
        val now = 100L * 24 * hourMs
        val old = ReplayPosition(10 * min, hourMs, now - SportsLiveReplayResume.KEEP_MS)
        val kept = SportsLiveReplayResume.updated(mapOf("old" to old), "new", ReplayPosition(20 * min, hourMs, now), now)
        assertEquals(setOf("new"), kept.keys)
        assertEquals(emptyMap<String, ReplayPosition>(), SportsLiveReplayResume.updated(kept, "new", null, now))
        assertEquals(kept, SportsLiveReplayResume.decode(SportsLiveReplayResume.encode(kept)))
    }
}
