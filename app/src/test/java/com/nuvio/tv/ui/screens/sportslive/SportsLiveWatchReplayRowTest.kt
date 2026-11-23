package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** live.26 shorter paths (#223): "▶ Watch replay" on a protected Results row, and no score in the replay's link names. */
class SportsLiveWatchReplayRowTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L

    @Test
    fun `a protected result of the last 36 h with a replay reads Watch replay, OK playing it`() {
        assertTrue(resultsRowWatchesReplay(ResultHiddenReason.YOUR_TEAM, hasReplay = true, individual = false, startMs = now - 3 * hour, nowMs = now))
        assertTrue(resultsRowWatchesReplay(ResultHiddenReason.SAVED, hasReplay = true, individual = false, startMs = now - 36 * hour, nowMs = now))
        // A West Coast game that ended after midnight still counts: 36 h, not the calendar day.
        assertTrue(resultsRowWatchesReplay(ResultHiddenReason.YOUR_TEAM, hasReplay = true, individual = false, startMs = now - 30 * hour, nowMs = now))
    }

    @Test
    fun `a Watch later game keeps Watch replay with no time limit while its replay is there, your teams keep 36 h`() {
        assertTrue(resultsRowWatchesReplay(ResultHiddenReason.SAVED, hasReplay = true, individual = false, startMs = now - 5 * 24 * hour, nowMs = now))
        assertTrue(resultsRowWatchesReplay(ResultHiddenReason.SAVED, hasReplay = true, individual = false, startMs = null, nowMs = now))
        assertFalse(resultsRowWatchesReplay(ResultHiddenReason.SAVED, hasReplay = false, individual = false, startMs = now - 5 * 24 * hour, nowMs = now))
        assertFalse(resultsRowWatchesReplay(ResultHiddenReason.SAVED, hasReplay = true, individual = true, startMs = now - hour, nowMs = now))
        assertFalse(resultsRowWatchesReplay(ResultHiddenReason.YOUR_TEAM, hasReplay = true, individual = false, startMs = now - 5 * 24 * hour, nowMs = now))
    }

    @Test
    fun `no replay, an older result of your team, an unknown start, an individual sport or a shown score keep the row as before`() {
        assertFalse(resultsRowWatchesReplay(ResultHiddenReason.YOUR_TEAM, hasReplay = false, individual = false, startMs = now - hour, nowMs = now))
        assertFalse(resultsRowWatchesReplay(ResultHiddenReason.YOUR_TEAM, hasReplay = true, individual = false, startMs = now - 37 * hour, nowMs = now))
        assertFalse(resultsRowWatchesReplay(ResultHiddenReason.YOUR_TEAM, hasReplay = true, individual = false, startMs = null, nowMs = now))
        assertFalse(resultsRowWatchesReplay(ResultHiddenReason.YOUR_TEAM, hasReplay = true, individual = true, startMs = now - hour, nowMs = now))
        assertFalse(resultsRowWatchesReplay(null, hasReplay = true, individual = false, startMs = now - hour, nowMs = now))
    }

    @Test
    fun `a row Hide until I reveal hides keeps Score hidden, as focus reveals it`() {
        assertFalse(resultsRowWatchesReplay(ResultHiddenReason.SETTING, hasReplay = true, individual = false, startMs = now - hour, nowMs = now))
    }

    @Test
    fun `a score in a replay's link words is masked, other numbers stay`() {
        assertEquals("Arsenal – Chelsea highlights", spoilerSafeLinkText("Arsenal 2-1 Chelsea highlights"))
        assertEquals("Lakers – Celtics (–)", spoilerSafeLinkText("Lakers 102 - 99 Celtics (3–2)"))
        assertEquals("Final – AET", spoilerSafeLinkText("Final 1:0 AET"))
        assertEquals("NBA 2024-25 1080p H.265 5.1", spoilerSafeLinkText("NBA 2024-25 1080p H.265 5.1"))
        assertNull(spoilerSafeLinkText(null))
    }

    @Test
    fun `a split score between team names is masked, a plain vs, dates and aspect ratios stay`() {
        assertEquals("Team A – Team B", spoilerSafeLinkText("Team A 2 vs 1 Team B"))
        assertEquals("Team A – Team B", spoilerSafeLinkText("Team A 2 v 1 Team B"))
        assertEquals("Team A – Team B", spoilerSafeLinkText("Team A 3 VS. 0 Team B"))
        assertEquals("Team A vs Team B", spoilerSafeLinkText("Team A vs Team B"))
        assertEquals("Team A vs Team B 2026-11-22", spoilerSafeLinkText("Team A vs Team B 2026-11-22"))
        assertEquals("Replay 22-11-2026 1080p 16:9", spoilerSafeLinkText("Replay 22-11-2026 1080p 16:9"))
        assertEquals("Round 2 vs 3rd seed", spoilerSafeLinkText("Round 2 vs 3rd seed"))
        // A score next to a date still goes.
        assertEquals("Team A – Team B 2026-11-22", spoilerSafeLinkText("Team A 2-1 Team B 2026-11-22"))
    }

    @Test
    fun `a replay's stream reaches the player with its name, title and description masked`() {
        val stream = Stream(
            name = "Replay 3-0", title = "Team A 3-0 Team B", description = "Full match, 3 - 0",
            url = "https://example.invalid/r.m3u8", ytId = null, infoHash = null, fileIdx = null, externalUrl = null,
            behaviorHints = null, addonName = "Sports", addonLogo = null
        )
        val safe = spoilerSafeReplayStream(stream)
        assertEquals("Replay –", safe.name)
        assertEquals("Team A – Team B", safe.title)
        assertEquals("Full match, –", safe.description)
        assertEquals(stream.url, safe.url)
        assertEquals(stream.addonName, safe.addonName)
    }
}
