package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.GameStatus
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import org.junit.Assert.assertEquals
import org.junit.Test

/** #357: a game ESPN has postponed or cancelled leaves LIVE at once; a suspended or delayed one (may resume) stays. */
class SportsLivePostponedTest {

    private val pacers = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA).first { it.eventId == "401909985" }
    private val card = SportsLiveItem(
        meta = MetaPreview(
            id = "p", type = ContentType.UNKNOWN, rawType = "sport", name = "Indiana Pacers at Philadelphia 76ers", poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = listOf("Basketball")
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false,
        startMs = null, sport = "Basketball", startHasTimeOfDay = false,
        teams = SportsLiveTeams.parse("Indiana Pacers at Philadelphia 76ers")
    )

    @Test
    fun `postponed leaves the live group for Just ended, suspended stays live, both later on Upcoming`() {
        val postponed = pacers.copy(state = ScoreState.OFF, isFinal = false, clockText = "Postponed", mayResume = false)
        val suspended = postponed.copy(clockText = "Suspended", mayResume = true)
        val off = GameStatus.of(card, postponed, null, 0L)
        val paused = GameStatus.of(card, suspended, null, 0L)
        assertEquals(GameStatus.Postponed("Postponed", mayResume = false), off)
        assertEquals(GameStatus.Postponed("Suspended", mayResume = true), paused)
        assertEquals(ScoreboardGroup.JUST_ENDED, scoreboardGroupOf(off, card))
        assertEquals(ScoreboardGroup.LIVE, scoreboardGroupOf(paused, card))
        assertEquals(ScoreboardGroup.LATER, scoreboardGroupOf(off, card, SportsLiveTab.UPCOMING))
    }

    @Test
    fun `L3 - a postponed game in the panel's Just ended plays on OK, no box score or postgame`() {
        val postponed = pacers.copy(state = ScoreState.OFF, isFinal = false, clockText = "Postponed", mayResume = false)
        val row = groupPanelRows(listOf(card), mapOf(card.streamKey to postponed), ScoresMode.SHOW, emptySet(), 0L).single()
        assertEquals(ScoreboardGroup.JUST_ENDED, row.section)
        assertEquals(PanelRowOk.PLAY, panelRowOk(row, postponed))
    }

    @Test
    fun `L4 - the LIVE header counts a suspended game living there, never a postponed one`() {
        val suspended = pacers.copy(state = ScoreState.OFF, isFinal = false, clockText = "Suspended", mayResume = true)
        val row = scoreboardRows(listOf(card), SportsLiveTab.LIVE_NOW, mapOf(card.streamKey to suspended), ScoresMode.SHOW, emptySet(), 0L).single()
        assertEquals(ScoreboardGroup.LIVE, row.group)
        assertEquals(1, sportsLiveGroupCounts(listOf(row))[ScoreboardGroup.LIVE])
        // Settled in its LIVE place after ESPN called it postponed: not counted.
        val postponed = row.copy(status = GameStatus.Postponed("Postponed", mayResume = false))
        assertEquals(null, sportsLiveGroupCounts(listOf(postponed))[ScoreboardGroup.LIVE])
    }

    @Test
    fun `L9 - a postponed row in the Just ended strip reads Postponed, never Final, with no chip too`() {
        val postponed = pacers.copy(state = ScoreState.OFF, isFinal = false, clockText = "Postponed", mayResume = false)
        val row = scoreboardRows(listOf(card), SportsLiveTab.LIVE_NOW, mapOf(card.streamKey to postponed), ScoresMode.SHOW, emptySet(), 0L)
            .single().copy(chip = null)
        assertEquals(ScoreboardGroup.JUST_ENDED, row.group)
        val strings = com.nuvio.tv.core.sportslive.GameStatusStrings(
            liveClock = "● %1\$s", live = "LIVE", clockPeriod = "%1\$s · %2\$s", timeCountdown = "%1\$s · %2\$s",
            inHoursMinutes = "in %1\$d h %2\$d m", inHours = "in %1\$d h", inMinutes = "in %1\$d min",
            final = "Final", fullTime = "FT", probablyEnded = "Probably ended", postponed = "Postponed",
            chip = com.nuvio.tv.core.sportslive.scores.ScoreChipStrings("%1\$d–%2\$d", "%1\$s · %2\$s", "%1\$s %2\$s", "Final", "Postponed", "Score hidden")
        )
        assertEquals(ScoreboardStatusLines("Postponed"), scoreboardStatusLines(row, strings, kickoff = null))
    }
}
