package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.GameStatusStrings
import com.nuvio.tv.core.sportslive.SportsLiveEndedGames
import com.nuvio.tv.core.sportslive.SportsLiveScoreWait
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.ScoreChipStrings
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * live.28 (user decision): a Live now game of an ESPN-covered league reads its start time, not LIVE, until its feed has
 * answered; a game no feed covers and Scores Off show as before; ESPN not answering in time falls back to LIVE.
 */
class SportsLiveScoreWaitTest {

    private val pacersEvent = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA).first { it.eventId == "401909985" }

    private fun item(name: String, sport: String?, isChannel: Boolean = false) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = listOfNotNull(sport)
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = if (isChannel) SportsLiveKind.CHANNELS else SportsLiveKind.EVENTS, isLive = true, isChannel = isChannel,
        startMs = NOW - HOUR, sport = sport, startHasTimeOfDay = true,
        teams = if (isChannel) null else SportsLiveTeams.parse(name)
    )

    private val covered = item("Indiana Pacers at Philadelphia 76ers", "Basketball")
    private val uncovered = item("Leinster vs Munster", "Rugby")
    private val channel = item("Sports News 24/7", null, isChannel = true)
    private val waitingGate = SportsLiveEndedGames.estimateGate(scoresOff = false, unmatched = emptySet())

    private val strings = GameStatusStrings(
        liveClock = "● %1\$s", live = "LIVE", clockPeriod = "%1\$s · %2\$s", timeCountdown = "%1\$s · %2\$s",
        inHoursMinutes = "in %1\$d h %2\$d m", inHours = "in %1\$d h", inMinutes = "in %1\$d min",
        final = "Final", fullTime = "FT", probablyEnded = "Probably ended", postponed = "Postponed",
        chip = ScoreChipStrings("%1\$d–%2\$d", "%1\$s · %2\$s", "%1\$s %2\$s", "Final", "Postponed", "Score hidden"),
        listedLive = "Listed live"
    )

    @Test
    fun `a covered game waits for its feed, an uncovered game and a channel never wait`() {
        val wait = SportsLiveScoreWait()
        assertEquals(setOf(covered.streamKey), wait.awaiting(listOf(covered, uncovered, channel), emptyMap(), waitingGate, NOW))
    }

    @Test
    fun `an answer ends the wait - a snapshot whatever it says, or the feeds confirming no game`() {
        val wait = SportsLiveScoreWait()
        val inPlay = pacersEvent.copy(state = ScoreState.IN, isFinal = false)
        assertTrue(wait.awaiting(listOf(covered), mapOf(covered.streamKey to inPlay), waitingGate, NOW).isEmpty())
        val confirmed = SportsLiveEndedGames.estimateGate(scoresOff = false, unmatched = setOf(covered.streamKey))
        assertTrue(wait.awaiting(listOf(covered), emptyMap(), confirmed, NOW).isEmpty())
    }

    @Test
    fun `Scores Off waits for nothing`() {
        val off = SportsLiveEndedGames.estimateGate(scoresOff = true, unmatched = emptySet())
        assertTrue(SportsLiveScoreWait().awaiting(listOf(covered), emptyMap(), off, NOW).isEmpty())
    }

    @Test
    fun `ESPN not answering in time falls back to LIVE, and a lost answer waits again from then`() {
        val wait = SportsLiveScoreWait()
        assertEquals(setOf(covered.streamKey), wait.awaiting(listOf(covered), emptyMap(), waitingGate, NOW))
        assertEquals(setOf(covered.streamKey), wait.awaiting(listOf(covered), emptyMap(), waitingGate, NOW + SportsLiveScoreWait.MAX_WAIT_MS - 1))
        // #374: the caller is told when the wait runs out, to show the game as before right then.
        assertEquals(NOW + SportsLiveScoreWait.MAX_WAIT_MS, wait.deadline)
        assertTrue(wait.awaiting(listOf(covered), emptyMap(), waitingGate, NOW + SportsLiveScoreWait.MAX_WAIT_MS).isEmpty())
        assertEquals(null, wait.deadline)
        // Answered, then the snapshot goes (a feed paused while the player showed): a new wait.
        val later = NOW + HOUR
        assertTrue(wait.awaiting(listOf(covered), mapOf(covered.streamKey to pacersEvent), waitingGate, later).isEmpty())
        assertEquals(setOf(covered.streamKey), wait.awaiting(listOf(covered), emptyMap(), waitingGate, later + 1))
    }

    private fun board(awaiting: Set<String>): SportsLiveBoard = SportsLiveBoard.build(
        SportsLiveBoardInputs(
            tab = SportsLiveTab.LIVE_NOW,
            filter = null,
            items = listOf(covered, uncovered),
            scores = SportsLiveBoardScores(scores = emptyMap(), mode = ScoresMode.SHOW),
            ended = emptyMap(),
            awaiting = awaiting,
            excitement = emptyMap(),
            followed = emptyList(),
            clockMs = NOW,
            logoRevision = 0
        )
    )

    private fun SportsLiveBoard.rowOf(item: SportsLiveItem): ScoreboardRow = rows!!.first { it.item.streamKey == item.streamKey }

    @Test
    fun `a waiting row reads its start time in place, with no LIVE and no dot, and never moves`() {
        val waiting = board(setOf(covered.streamKey))
        val row = waiting.rowOf(covered)
        assertTrue(row.awaitingScore)
        // Still in the live group, where it will read LIVE once ESPN says so (settled screens: no move).
        assertEquals(ScoreboardGroup.LIVE, row.group)
        assertEquals(ScoreboardStatusLines("7:00 PM"), scoreboardStatusLines(row, strings, kickoff = "7:00 PM"))
        assertEquals(board(emptySet()).rows!!.map { it.item.streamKey }, waiting.rows!!.map { it.item.streamKey })
        // #373: the LIVE header counts only what reads LIVE.
        assertEquals(1, sportsLiveGroupCounts(waiting.rows!!)[ScoreboardGroup.LIVE])
        assertEquals(2, sportsLiveGroupCounts(board(emptySet()).rows!!)[ScoreboardGroup.LIVE])
        // The uncovered game shows exactly as before.
        val other = waiting.rowOf(uncovered)
        assertFalse(other.awaitingScore)
        assertEquals(ScoreboardStatusLines("LIVE", live = true), scoreboardStatusLines(other, strings, kickoff = "7:00 PM"))
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val HOUR = 3_600_000L
    }
}
