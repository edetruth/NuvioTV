package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
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
        // #244: how it ended goes with the score ("AET" beside a hidden score still says it was a draw after 90 minutes).
        assertEquals("Final –", spoilerSafeLinkText("Final 1:0 AET"))
        assertEquals("NBA 2024-25 1080p H.265 5.1", spoilerSafeLinkText("NBA 2024-25 1080p H.265 5.1"))
        assertNull(spoilerSafeLinkText(null))
    }

    @Test
    fun `a replay's links are masked wherever its result is hidden for the user, never under Show with nothing hidden`() {
        val post = ScoreState.POST
        assertFalse(sportsLiveHidesReplayLinks(true, ScoresMode.SHOW, post, revealed = false, spoilerProtected = false))
        assertFalse("no ESPN game", sportsLiveHidesReplayLinks(true, ScoresMode.SHOW, null, revealed = false, spoilerProtected = false))
        assertTrue("saved for later", sportsLiveHidesReplayLinks(true, ScoresMode.SHOW, post, revealed = false, spoilerProtected = true))
        assertTrue(sportsLiveHidesReplayLinks(true, ScoresMode.HIDE_UNTIL_FOCUSED, post, revealed = false, spoilerProtected = false))
        assertTrue("no ESPN game", sportsLiveHidesReplayLinks(true, ScoresMode.HIDE_UNTIL_FOCUSED, null, revealed = false, spoilerProtected = false))
        assertFalse("revealed", sportsLiveHidesReplayLinks(true, ScoresMode.HIDE_UNTIL_FOCUSED, post, revealed = true, spoilerProtected = false))
        assertTrue(sportsLiveHidesReplayLinks(true, ScoresMode.OFF, post, revealed = true, spoilerProtected = false))
        assertTrue("setting not read yet", sportsLiveHidesReplayLinks(true, null, post, revealed = false, spoilerProtected = false))
        assertFalse("not a replay", sportsLiveHidesReplayLinks(false, ScoresMode.HIDE_UNTIL_FOCUSED, post, revealed = false, spoilerProtected = true))
    }

    @Test
    fun `a shoot-out score and an extra-time tag next to a score are masked with it`() {
        assertEquals("Team A – Team B", spoilerSafeLinkText("Team A 1 (4) - (2) 1 Team B"))
        assertEquals("Team A – Team B", spoilerSafeLinkText("Team A 1(4)-(2)1 Team B"))
        assertEquals("Team A – Team B", spoilerSafeLinkText("Team A 1-1 (4-2 pens) Team B"))
        assertEquals("Team A – Team B", spoilerSafeLinkText("Team A 1-1 (4-2 p) Team B"))
        assertEquals("Team A – Team B", spoilerSafeLinkText("Team A 2-2 (5-4 on penalties) Team B"))
        assertEquals("Team A – Team B (–)", spoilerSafeLinkText("Team A 1-1 Team B (4-2 pens)"))
        assertEquals("Team A – Team B", spoilerSafeLinkText("Team A 1-1 aet Team B"))
        assertEquals("Team A – Team B", spoilerSafeLinkText("Team A 1-1 a.e.t. Team B"))
        assertEquals("Team A – Team B", spoilerSafeLinkText("Team A 1-1 (a.e.t.) Team B"))
        assertEquals("Team A – Team B", spoilerSafeLinkText("Team A 2-1 (OT) Team B"))
        assertEquals("Team A – Team B", spoilerSafeLinkText("Team A 3-2 SO Team B"))
        assertEquals("Team A – Team B", spoilerSafeLinkText("Team A 3–2 (SO) Team B"))
        assertEquals("Team A – Team B", spoilerSafeLinkText("Team A 2 vs 1 aet Team B"))
    }

    @Test
    fun `words after a score that are not how it ended stay`() {
        assertEquals("Team A – Team B part 2", spoilerSafeLinkText("Team A 2-1 Team B part 2"))
        assertEquals("Team A – Team B (pt 1)", spoilerSafeLinkText("Team A 2-1 Team B (pt 1)"))
        assertEquals("Team A – p2p stream", spoilerSafeLinkText("Team A 2-1 p2p stream"))
        assertEquals("Team A – Overtime highlights", spoilerSafeLinkText("Team A 2-1 Overtime highlights"))
        assertEquals("Team A – so good", spoilerSafeLinkText("Team A 2-1 so good"))
        assertEquals("Team A – ot Team B", spoilerSafeLinkText("Team A 2-1 ot Team B"))
        assertEquals("Team A – () Team B", spoilerSafeLinkText("Team A 1-1 () Team B"))
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
