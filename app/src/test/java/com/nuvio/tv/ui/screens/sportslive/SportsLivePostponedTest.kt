package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.GameStatus
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.ScoreState
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
}
