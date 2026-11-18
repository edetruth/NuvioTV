package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.EndedGame
import com.nuvio.tv.core.sportslive.EndedSignal
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.GameSituation
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #83: the in-player panel draws its rows from the Scoreboard's own row model. */
class ZapPanelBoardsTest {

    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
    private val pacersEvent = nba.first { it.eventId == "401909985" }
    private val lakersEvent = nba.first { it.eventId == "401909986" }

    private fun item(name: String, startMs: Long?, isLive: Boolean = true) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = isLive, isChannel = false,
        startMs = startMs, sport = "Basketball", startHasTimeOfDay = startMs != null,
        teams = SportsLiveTeams.parse(name)
    )

    private val redZone = GameSituation(redZone = true)

    private fun live(event: ScoreSnapshot) = event.copy(
        state = ScoreState.IN, isFinal = false, clockText = "Q3 4:12",
        home = event.home.copy(score = 80), away = event.away.copy(score = 88),
        situation = redZone
    )

    private val pacers = item("Pacers @ 76ers", NOW - HOUR)
    private val soon = item("Celtics @ Knicks", NOW + HOUR, isLive = false)
    private val later = item("Bulls @ Heat", NOW + 5 * HOUR, isLive = false)
    private val lakers = item("Lakers @ Suns", NOW - 3 * HOUR)
    private val estimated = item("Nets @ Magic", NOW - 4 * HOUR)
    private val items = listOf(later, lakers, estimated, soon, pacers)
    private val scores = mapOf(pacers.streamKey to live(pacersEvent), lakers.streamKey to lakersEvent)
    private val ended = mapOf(
        lakers.streamKey to EndedGame(EndedSignal.FINAL, NOW - HOUR, lakersEvent),
        estimated.streamKey to EndedGame(EndedSignal.ESTIMATE, NOW - HOUR)
    )
    private val followed = listOf(FollowedTeam(name = "76ers"))
    private val followsSide: (SportsLiveItem, String) -> Boolean = { item, side ->
        followed.any { SportsLiveTeams.followsSideOfAnySource(it, side, item) }
    }

    private fun boards(mode: ScoresMode? = ScoresMode.SHOW, revealed: Set<String> = emptySet()): List<ZapPanelBoard> =
        panelBoards(groupPanelRows(items, scores, mode, revealed, NOW, ended), scores, mode, revealed, NOW, ended, followed)

    @Test
    fun `every panel row is the same row the Scoreboard draws for that game`() {
        listOf(ScoresMode.SHOW, ScoresMode.HIDE_UNTIL_FOCUSED, ScoresMode.OFF).forEach { mode ->
            val revealed = setOf(pacers.streamKey)
            val scoreboard = scoreboardRows(items, SportsLiveTab.LIVE_NOW, scores, mode, revealed, NOW, ended, followsSide)
            val panel = boards(mode, revealed).map { it.board }
            assertEquals(scoreboard.associateBy { it.item.streamKey }, panel.associateBy { it.item.streamKey })
        }
    }

    @Test
    fun `panel boards keep the panel's own row order`() {
        val rows = groupPanelRows(items, scores, ScoresMode.SHOW, emptySet(), NOW, ended)
        assertEquals(rows.map { it.item }, boards().map { it.board.item })
    }

    @Test
    fun `the board carries logos, scores in card order, the clock and a starred followed side`() {
        val board = boards().first { it.board.item == pacers }.board
        assertEquals(ScoreboardGroup.LIVE, board.group)
        assertEquals(ScoreChip.Live("Q3 4:12", com.nuvio.tv.core.sportslive.scores.ChipScore(88, 80)), board.chip)
        assertEquals("IND", board.art?.first?.short)
        assertFalse(board.firstFollowed)
        assertTrue(board.secondFollowed)
        // A game starting soon counts down to its kickoff instead.
        assertEquals(1 to 0, boards().first { it.board.item == soon }.board.startsIn)
    }

    @Test
    fun `the situation line comes only with a shown live score`() {
        val shown = boards().first { it.board.item == pacers }
        assertEquals(redZone, shown.situation)
        assertNotNull(shown.event)
        // Hide until focused: nothing before the row is revealed (a key press, then a second on it).
        val hidden = boards(ScoresMode.HIDE_UNTIL_FOCUSED).first { it.board.item == pacers }
        assertTrue(hidden.board.chip is ScoreChip.Hidden)
        assertNull(hidden.situation)
        assertNull(hidden.event)
        assertEquals(redZone, boards(ScoresMode.HIDE_UNTIL_FOCUSED, setOf(pacers.streamKey)).first { it.board.item == pacers }.situation)
        // Scores off, a final or a game not started: none.
        assertNull(boards(ScoresMode.OFF).first { it.board.item == pacers }.situation)
        assertNull(boards().first { it.board.item == lakers }.situation)
        assertNull(boards().first { it.board.item == soon }.situation)
    }

    @Test
    fun `an estimated end shows Probably ended and no score in the panel too`() {
        val board = boards().first { it.board.item == estimated }.board
        assertTrue(board.probablyEnded)
        assertNull(board.chip)
        assertEquals(ScoreboardGroup.JUST_ENDED, board.group)
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val HOUR = 3_600_000L
    }
}
