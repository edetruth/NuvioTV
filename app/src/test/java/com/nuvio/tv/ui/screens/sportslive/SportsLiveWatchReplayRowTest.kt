package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.alerts.AlertFixtures
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.MINUTE
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.START
import com.nuvio.tv.core.sportslive.scores.FinalNote
import com.nuvio.tv.core.sportslive.scores.ResultRow
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.SpoilerProtection
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
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

    // #241: a replay card's links follow the Results row's own rule, reveals read by ESPN event id.
    private val replayNow = START + 3 * 60 * MINUTE
    private val final = AlertFixtures.final(101, 99)
    private val replay = AlertFixtures.item(name = "Pacers @ 76ers", id = "replay").copy(kind = SportsLiveKind.REPLAY)
    private val sixers = listOf(FollowedTeam(name = "Philadelphia 76ers", sport = "Basketball"))

    private fun hides(
        mode: ScoresMode?,
        match: ScoreSnapshot? = final,
        revealed: Set<String> = emptySet(),
        protection: SpoilerProtection = SpoilerProtection(nowMs = replayNow),
        item: SportsLiveItem = replay,
        isReplay: Boolean = true
    ): Boolean = sportsLiveHidesReplayLinks(item, isReplay, mode, match, revealed, protection)

    @Test
    fun `a replay's links are masked wherever its result is hidden for the user, never under Show with nothing hidden`() {
        assertFalse(hides(ScoresMode.SHOW))
        assertFalse("no ESPN game", hides(ScoresMode.SHOW, match = null))
        val saved = SpoilerProtection(games = SpoilerProtection.saved(emptyList(), final, replayNow), nowMs = replayNow)
        assertTrue("saved for later", hides(ScoresMode.SHOW, protection = saved))
        assertTrue("saved for later, no ESPN match: by its sides", hides(ScoresMode.SHOW, match = null, protection = saved))
        assertTrue(hides(ScoresMode.HIDE_UNTIL_FOCUSED))
        assertTrue("no ESPN game", hides(ScoresMode.HIDE_UNTIL_FOCUSED, match = null))
        assertTrue(hides(ScoresMode.OFF, revealed = setOf(final.eventId)))
        assertTrue("setting not read yet", hides(null))
        assertFalse("not a replay", hides(ScoresMode.HIDE_UNTIL_FOCUSED, protection = saved, isReplay = false))
    }

    @Test
    fun `a result revealed on Results unmasks its replay's links, read by the ESPN event id and not the card key`() {
        assertFalse(hides(ScoresMode.HIDE_UNTIL_FOCUSED, revealed = setOf(final.eventId)))
        // The card's own key is not an ESPN event: it reveals nothing (the old check compared the two).
        assertTrue(hides(ScoresMode.HIDE_UNTIL_FOCUSED, revealed = setOf(replay.streamKey)))
    }

    @Test
    fun `a replay card's links hide exactly when its Results row hides the score`() {
        val cases = listOf(
            SpoilerProtection(nowMs = replayNow),
            SpoilerProtection(myTeamsResults = true, followed = sixers, nowMs = replayNow),
            SpoilerProtection(myTeamsResults = true, followed = sixers, nowMs = replayNow, games = SpoilerProtection.ended(emptyList(), final, replayNow)),
            SpoilerProtection(games = SpoilerProtection.saved(emptyList(), final, replayNow), nowMs = replayNow)
        )
        for (mode in ScoresMode.entries) for (protection in cases) for (revealed in listOf(emptySet(), setOf(final.eventId))) {
            val row = ResultRow(final, followed = protection.followed.isNotEmpty(), note = FinalNote.FT)
            val rowHides = !SportsLiveResultsUiState(mode = mode, revealed = revealed, protection = protection).showsScore(row)
            assertEquals("$mode $protection $revealed", rowHides, hides(mode, revealed = revealed, protection = protection))
        }
    }

    // F1: a replay team folder ("76ers Replays") names no two sides and matches no one ESPN game, but holds many.
    private val folder = AlertFixtures.item(name = "76ers Replays", id = "folder").let {
        it.copy(kind = SportsLiveKind.REPLAY, teams = null, meta = it.meta.copy(type = ContentType.SERIES, rawType = "series"))
    }

    @Test
    fun `a replay team folder is masked whenever any game in it could be hidden`() {
        assertTrue(hides(ScoresMode.SHOW, match = null, item = folder, protection = SpoilerProtection(myTeamsResults = true, followed = sixers, nowMs = replayNow)))
        assertTrue(hides(ScoresMode.SHOW, match = null, item = folder, protection = SpoilerProtection(myTeams = true, followed = sixers, nowMs = replayNow)))
        val saved = SpoilerProtection(games = SpoilerProtection.saved(emptyList(), final, replayNow), nowMs = replayNow)
        assertTrue("a game saved for later", hides(ScoresMode.SHOW, match = null, item = folder, protection = saved))
        assertTrue(hides(ScoresMode.HIDE_UNTIL_FOCUSED, match = null, item = folder))
        // Even when one game in it matches ESPN and is revealed, the folder's other games are not.
        assertTrue(hides(ScoresMode.HIDE_UNTIL_FOCUSED, item = folder, revealed = setOf(final.eventId)))
        // Show with nothing protected and no team followed: as the addon names it.
        assertFalse(hides(ScoresMode.SHOW, match = null, item = folder))
        assertFalse(hides(ScoresMode.SHOW, match = null, item = folder, protection = SpoilerProtection(followed = sixers, nowMs = replayNow)))
    }

    @Test
    fun `a followed team's replay with no ESPN match is masked under my teams' protection`() {
        assertTrue(hides(ScoresMode.SHOW, match = null, protection = SpoilerProtection(myTeamsResults = true, followed = sixers, nowMs = replayNow)))
        assertTrue(hides(ScoresMode.SHOW, match = null, protection = SpoilerProtection(myTeams = true, followed = sixers, nowMs = replayNow)))
        // Another team's replay, or protection off, keeps its names under Show.
        val celtics = listOf(FollowedTeam(name = "Boston Celtics", sport = "Basketball"))
        assertFalse(hides(ScoresMode.SHOW, match = null, protection = SpoilerProtection(myTeamsResults = true, followed = celtics, nowMs = replayNow)))
        assertFalse(hides(ScoresMode.SHOW, match = null, protection = SpoilerProtection(followed = sixers, nowMs = replayNow)))
    }

    @Test
    fun `a shoot-out score and an extra-time tag next to a score are masked with it`() {
        assertEquals("Team A – Team B", spoilerSafeLinkText("Team A 1 (4) - (2) 1 Team B"))
        assertEquals("Team A – Team B", spoilerSafeLinkText("Team A 1(4)-(2)1 Team B"))
        assertEquals("Team A – Team B", spoilerSafeLinkText("Team A 1-1 (4-2 pens) Team B"))
        assertEquals("Team A – Team B", spoilerSafeLinkText("Team A 1-1 (4-2 p) Team B"))
        assertEquals("Team A – Team B", spoilerSafeLinkText("Team A 2-2 (5-4 on penalties) Team B"))
        // S2: a shoot-out in brackets after the team names goes whole, never leaving "(–)".
        assertEquals("Team A – Team B", spoilerSafeLinkText("Team A 1-1 Team B (4-2 pens)"))
        assertEquals("Arsenal – Chelsea", spoilerSafeLinkText("Arsenal 1-1 Chelsea (4-2 on penalties)"))
        assertEquals("Arsenal – Chelsea", spoilerSafeLinkText("Arsenal 1-1 Chelsea [4-2 pens]"))
        assertEquals("Team A – Team B", spoilerSafeLinkText("Team A 1-1 aet Team B"))
        assertEquals("Team A – Team B", spoilerSafeLinkText("Team A 1-1 a.e.t. Team B"))
        assertEquals("Team A – Team B", spoilerSafeLinkText("Team A 1-1 (a.e.t.) Team B"))
        assertEquals("Team A – Team B", spoilerSafeLinkText("Team A 2-1 (OT) Team B"))
        assertEquals("Team A – Team B", spoilerSafeLinkText("Team A 3-2 SO Team B"))
        assertEquals("Team A – Team B", spoilerSafeLinkText("Team A 3–2 (SO) Team B"))
        assertEquals("Team A – Team B", spoilerSafeLinkText("Team A 2 vs 1 aet Team B"))
    }

    @Test
    fun `more ways a link tells extra time or a shoot-out are masked with its score`() {
        // F6: shoot-out goals after each score, multi-overtime, square brackets, a bare pen, tags inside brackets first.
        assertEquals("Team A – Team B", spoilerSafeLinkText("Team A 1 (4) - 1 (2) Team B"))
        assertEquals("Team A – Team B", spoilerSafeLinkText("Team A 120-118 (2OT) Team B"))
        assertEquals("Lakers – Celtics", spoilerSafeLinkText("Lakers 120-118 2OT Celtics"))
        assertEquals("Team A – Team B", spoilerSafeLinkText("Team A 1-1 [AET] Team B"))
        assertEquals("Team A – Team B", spoilerSafeLinkText("Team A 1-1 pen Team B"))
        assertEquals("Team A –", spoilerSafeLinkText("Team A 1-1 (pens 4-2)"))
        assertEquals("Team A –", spoilerSafeLinkText("Team A 1-1 (AET, 4-2 pens)"))
    }

    @Test
    fun `an extra-time tag after the team names goes too once the score was masked`() {
        assertEquals("Arsenal – Chelsea", spoilerSafeLinkText("Arsenal 1-1 Chelsea (AET)"))
        assertEquals("Lakers – Celtics", spoilerSafeLinkText("Lakers 115-112 Celtics (OT)"))
        assertEquals("Leafs – Bruins", spoilerSafeLinkText("Leafs 3-2 Bruins SO"))
        assertEquals("Team A – Team B", spoilerSafeLinkText("Team A 1-1 Team B AET"))
        // No score masked: the words stay (nothing to give away beside a score that is not there).
        assertEquals("Arsenal vs Chelsea (AET)", spoilerSafeLinkText("Arsenal vs Chelsea (AET)"))
        assertEquals("Team A vs Team B SO", spoilerSafeLinkText("Team A vs Team B SO"))
        // Other brackets stay.
        assertEquals("Team A – Team B (Full Match)", spoilerSafeLinkText("Team A 2-1 Team B (Full Match)"))
        assertEquals("Team A – Team B [1080p]", spoilerSafeLinkText("Team A 2-1 Team B [1080p]"))
        assertEquals("Team A – Team B (2)", spoilerSafeLinkText("Team A 2-1 Team B (2)"))
    }

    @Test
    fun `a shoot-out bracket after the team names and a tag followed by words go too`() {
        // F6-1
        assertEquals("Arsenal – Chelsea", spoilerSafeLinkText("Arsenal 1-1 Chelsea (pens 4-2)"))
        assertEquals("Arsenal – Chelsea", spoilerSafeLinkText("Arsenal 1-1 Chelsea (AET, 4-2 pens)"))
        assertEquals("Team A –", spoilerSafeLinkText("Team A 1-1 (aet, pens 4-2)"))
        assertEquals("Lakers – Celtics Highlights", spoilerSafeLinkText("Lakers 120-118 Celtics OT Highlights"))
        assertEquals("Arsenal – Chelsea Full Match", spoilerSafeLinkText("Arsenal 1-1 Chelsea AET Full Match"))
        assertEquals("Leafs – Bruins 1080p", spoilerSafeLinkText("Leafs 3-2 Bruins SO 1080p"))
        assertEquals("Arsenal – Chelsea", spoilerSafeLinkText("Arsenal 1-1 Chelsea aet"))
        assertEquals("Arsenal – Chelsea", spoilerSafeLinkText("Arsenal 1-1 Chelsea on penalties"))
        assertEquals("Arsenal – Chelsea", spoilerSafeLinkText("Arsenal 1-1 Chelsea after extra time"))
        // A series score in brackets, masked, has no tag: it stays as "(–)".
        assertEquals("Lakers – Celtics (–)", spoilerSafeLinkText("Lakers 102 - 99 Celtics (3–2)"))
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
