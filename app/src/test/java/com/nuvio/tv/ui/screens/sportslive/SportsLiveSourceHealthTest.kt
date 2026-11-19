package com.nuvio.tv.ui.screens.sportslive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #170: the dot on Refresh says nothing for a one-off miss and escalates only when it matters. */
class SportsLiveSourceHealthTest {

    private val minute = 60_000L
    private val a = "a" to "Source A"
    private val b = "b" to "Source B"
    private val both = mapOf(a, b)

    private fun SportsLiveSources.refresh(atMs: Long, failed: Map<String, String> = emptyMap(), fetched: Map<String, String> = both, claimed: Set<String> = both.keys) =
        after(fetched = fetched - failed.keys, failed = failed, claimed = claimed, nowMs = atMs)

    @Test
    fun `a one-off miss shows nothing`() {
        val sources = SportsLiveSources().refresh(0).refresh(minute, failed = mapOf(a))
        assertEquals(SportsLiveSourceBadge.NONE, sources.badge(minute))
        assertTrue(sources.flagged().isEmpty())
    }

    @Test
    fun `two failed refreshes in a row of the same source show the grey dot`() {
        val sources = SportsLiveSources().refresh(0).refresh(minute, failed = mapOf(a)).refresh(2 * minute, failed = mapOf(a))
        assertEquals(SportsLiveSourceBadge.GREY, sources.badge(2 * minute))
        assertEquals(listOf("Source A"), sources.flagged().map { it.name })
        assertEquals(0L, sources.flagged().single().silentSinceMs)
    }

    @Test
    fun `an answer in between starts the run again`() {
        val sources = SportsLiveSources().refresh(0, failed = mapOf(a)).refresh(minute).refresh(2 * minute, failed = mapOf(a))
        assertEquals(SportsLiveSourceBadge.NONE, sources.badge(2 * minute))
    }

    @Test
    fun `two different sources missing once each show nothing`() {
        val sources = SportsLiveSources().refresh(0).refresh(minute, failed = mapOf(a)).refresh(2 * minute, failed = mapOf(b))
        assertEquals(SportsLiveSourceBadge.NONE, sources.badge(2 * minute))
    }

    @Test
    fun `a source silent for over 10 minutes turns the dot amber`() {
        val sources = SportsLiveSources().refresh(0).refresh(5 * minute, failed = mapOf(a)).refresh(10 * minute, failed = mapOf(a))
        assertEquals(SportsLiveSourceBadge.GREY, sources.badge(10 * minute))
        assertEquals(SportsLiveSourceBadge.AMBER, sources.badge(10 * minute + 1))
    }

    @Test
    fun `every source failing turns the dot amber at once`() {
        val sources = SportsLiveSources().refresh(0).refresh(minute, failed = both)
        assertEquals(SportsLiveSourceBadge.AMBER, sources.badge(minute))
        assertEquals(setOf("Source A", "Source B"), sources.flagged().map { it.name }.toSet())
    }

    @Test
    fun `a refresh that does not ask a source keeps its run`() {
        val sources = SportsLiveSources().refresh(0, failed = mapOf(a))
            .refresh(minute, fetched = mapOf(b))
            .refresh(2 * minute, failed = mapOf(a))
        assertEquals(2, sources.health.getValue("a").failuresInRow)
        assertEquals(SportsLiveSourceBadge.GREY, sources.badge(2 * minute))
    }

    @Test
    fun `a refresh that asked nothing keeps whether all failed`() {
        val sources = SportsLiveSources().refresh(0, failed = both).refresh(minute, fetched = emptyMap())
        assertTrue(sources.allFailed)
    }

    @Test
    fun `a source no longer claimed is forgotten`() {
        val sources = SportsLiveSources().refresh(0, failed = mapOf(a)).refresh(minute, failed = mapOf(a))
            .refresh(2 * minute, fetched = mapOf(b), claimed = setOf("b"))
        assertEquals(setOf("b"), sources.health.keys)
        assertEquals(SportsLiveSourceBadge.NONE, sources.badge(2 * minute))
    }
}
