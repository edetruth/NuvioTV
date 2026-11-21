package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.GameStatus
import com.nuvio.tv.core.sportslive.GameStatusStrings
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.ScoreChipStrings
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.ui.screens.home.SportsLiveHomeMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #215 part 4 (conservative version): an addon-listed live game the score feeds confirmed they do not have reads
 * "Listed live" (muted, no red dot), never LIVE. A game still being looked up, Scores Off, and any ESPN match keep LIVE.
 */
class SportsLiveListedLiveTest {

    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
    private val pacersEvent = nba.first { it.eventId == "401909985" }

    private fun item(name: String, startMs: Long? = NOW - HOUR, isChannel: Boolean = false) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = if (isChannel) SportsLiveKind.CHANNELS else SportsLiveKind.EVENTS, isLive = true, isChannel = isChannel,
        startMs = startMs, sport = if (isChannel) null else "Basketball", startHasTimeOfDay = startMs != null,
        teams = SportsLiveTeams.parse(name)
    )

    private fun inPlay(event: ScoreSnapshot) = event.copy(
        state = ScoreState.IN, isFinal = false, clockText = "4:12 - 4th",
        home = event.home.copy(score = 80), away = event.away.copy(score = 88)
    )

    private val pacers = item("Pacers @ 76ers")
    private val unverified = item("Virginia vs North Carolina")
    private val scores = mapOf(pacers.streamKey to inPlay(pacersEvent))

    private val strings = GameStatusStrings(
        liveClock = "● %1\$s", live = "LIVE", clockPeriod = "%1\$s · %2\$s", timeCountdown = "%1\$s · %2\$s",
        inHoursMinutes = "in %1\$d h %2\$d m", inHours = "in %1\$d h", inMinutes = "in %1\$d min",
        final = "Final", fullTime = "FT", probablyEnded = "Probably ended", postponed = "Postponed",
        chip = ScoreChipStrings("%1\$d–%2\$d", "%1\$s · %2\$s", "%1\$s %2\$s", "Final", "Postponed", "Score hidden"),
        listedLive = "Listed live"
    )

    private fun board(mode: ScoresMode?, unmatched: Set<String>): SportsLiveBoard = SportsLiveBoard.build(
        SportsLiveBoardInputs(
            tab = SportsLiveTab.LIVE_NOW,
            filter = null,
            items = listOf(pacers, unverified),
            scores = SportsLiveBoardScores(scores = if (mode == ScoresMode.OFF) emptyMap() else scores, mode = mode),
            ended = emptyMap(),
            unmatched = unmatched,
            excitement = emptyMap(),
            followed = emptyList(),
            clockMs = NOW,
            logoRevision = 0
        )
    )

    private fun SportsLiveBoard.rowOf(item: SportsLiveItem): ScoreboardRow = rows!!.first { it.item.streamKey == item.streamKey }

    @Test
    fun `a live game the feeds confirmed they do not have reads Listed live, muted and with no dot`() {
        val row = board(ScoresMode.SHOW, setOf(unverified.streamKey)).rowOf(unverified)
        assertTrue(row.listedOnly)
        assertEquals(ScoreboardGroup.LIVE, row.group)
        assertEquals(ScoreboardStatusLines("Listed live", listed = true), scoreboardStatusLines(row, strings, kickoff = null))
    }

    @Test
    fun `a game still being looked up keeps LIVE, so nothing flips while the first scores load`() {
        val row = board(ScoresMode.SHOW, emptySet()).rowOf(unverified)
        assertFalse(row.listedOnly)
        assertEquals(ScoreboardStatusLines("LIVE", live = true), scoreboardStatusLines(row, strings, kickoff = null))
    }

    @Test
    fun `Scores Off checks nothing, so every listed game keeps LIVE`() {
        val row = board(ScoresMode.OFF, setOf(unverified.streamKey)).rowOf(unverified)
        assertFalse(row.listedOnly)
        assertEquals(ScoreboardStatusLines("LIVE", live = true), scoreboardStatusLines(row, strings, kickoff = null))
    }

    @Test
    fun `Hide until focused doubts an unplaced game too, and an ESPN match is never doubted`() {
        assertTrue(board(ScoresMode.HIDE_UNTIL_FOCUSED, setOf(unverified.streamKey)).rowOf(unverified).listedOnly)
        // A stale unmatched mark on a game ESPN now has: its match wins.
        val matched = board(ScoresMode.SHOW, setOf(pacers.streamKey, unverified.streamKey)).rowOf(pacers)
        assertFalse(matched.listedOnly)
        assertEquals(ScoreboardStatusLines("4:12", "4th", live = true), scoreboardStatusLines(matched, strings, kickoff = null))
    }

    @Test
    fun `the rule - only an unmatched Live status with Scores on and the feeds' answer in`() {
        assertTrue(listedLiveOnly(GameStatus.Live(null), scoresOn = true, confirmedUnmatched = true))
        assertFalse(listedLiveOnly(GameStatus.Live(null), scoresOn = false, confirmedUnmatched = true))
        assertFalse(listedLiveOnly(GameStatus.Live(null), scoresOn = true, confirmedUnmatched = false))
        assertFalse(listedLiveOnly(GameStatus.Live(inPlay(pacersEvent)), scoresOn = true, confirmedUnmatched = true))
        assertFalse(listedLiveOnly(GameStatus.Channel, scoresOn = true, confirmedUnmatched = true))
        assertFalse(listedLiveOnly(GameStatus.Upcoming(null, hasTimeOfDay = false), scoresOn = true, confirmedUnmatched = true))
    }

    @Test
    fun `the player's panel and guide read it the same way`() {
        val rows = groupPanelRows(listOf(pacers, unverified), scores, ScoresMode.SHOW, emptySet(), NOW)
        val boards = panelBoards(rows, scores, ScoresMode.SHOW, emptySet(), NOW, unmatched = setOf(unverified.streamKey))
            .associateBy { it.board.item.streamKey }
        assertTrue(boards.getValue(unverified.streamKey).board.listedOnly)
        assertFalse(boards.getValue(pacers.streamKey).board.listedOnly)
        val off = panelBoards(groupPanelRows(listOf(unverified), emptyMap(), ScoresMode.OFF, emptySet(), NOW), emptyMap(), ScoresMode.OFF, emptySet(), NOW, unmatched = setOf(unverified.streamKey))
        assertFalse(off.single().board.listedOnly)
    }

    @Test
    fun `Home's Live now card reads Listed live with no dot and no Score hidden note`() {
        assertTrue(SportsLiveHomeMeta.listedLiveOnly(ScoresMode.SHOW, confirmedUnmatched = true, matched = false))
        assertFalse(SportsLiveHomeMeta.listedLiveOnly(ScoresMode.OFF, confirmedUnmatched = true, matched = false))
        assertFalse(SportsLiveHomeMeta.listedLiveOnly(null, confirmedUnmatched = true, matched = false))
        assertFalse(SportsLiveHomeMeta.listedLiveOnly(ScoresMode.SHOW, confirmedUnmatched = false, matched = false))
        assertFalse(SportsLiveHomeMeta.listedLiveOnly(ScoresMode.SHOW, confirmedUnmatched = true, matched = true))
        val footer = SportsLiveHomeMeta.cardFooter(null, ScoresMode.HIDE_UNTIL_FOCUSED, strings.chip, "Live", listedLive = "Listed live")
        assertEquals(DrawnCardFooter(lead = "Listed live"), footer)
        val unchanged = SportsLiveHomeMeta.cardFooter(null, ScoresMode.SHOW, strings.chip, "Live")
        assertEquals(DrawnCardFooter(lead = "Live", live = true), unchanged)
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val HOUR = 3_600_000L
    }
}
