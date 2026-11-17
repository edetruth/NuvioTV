package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.EndedGame
import com.nuvio.tv.core.sportslive.EndedSignal
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Just ended strip (#60): OK routing, the section state and the player panel's group. */
class SportsLiveJustEndedTest {

    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
    private val pacersFinal = nba.first { it.eventId == "401909985" } // IND @ PHI, Final 131–124
    private val lakersEvent = nba.first { it.eventId == "401909986" } // LAL @ PHX

    private fun item(name: String, startMs: Long? = null) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = "LIVE", imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false,
        startMs = startMs, sport = "Basketball", startHasTimeOfDay = startMs != null,
        teams = SportsLiveTeams.parse(name)
    )

    private fun live(event: ScoreSnapshot) = event.copy(
        state = ScoreState.IN, isFinal = false, clockText = "Q2 5:00",
        home = event.home.copy(score = 50), away = event.away.copy(score = 48)
    )

    @Test
    fun `OK on an ESPN-ended card opens its Game page, on an estimated or live one plays`() {
        assertEquals(
            SportsLiveCardOk.OpenGame(pacersFinal),
            sportsLiveCardOk(EndedGame(EndedSignal.FINAL, 0L, pacersFinal))
        )
        assertEquals(SportsLiveCardOk.Play, sportsLiveCardOk(EndedGame(EndedSignal.ESTIMATE, 0L)))
        assertEquals(SportsLiveCardOk.Play, sportsLiveCardOk(null))
    }

    @Test
    fun `the section state knows which cards are in the strip, and only on Live now`() {
        val ended = item("Indiana Pacers at Philadelphia 76ers")
        val running = item("Los Angeles Lakers at Phoenix Suns")
        val verdict = EndedGame(EndedSignal.FINAL, 0L, pacersFinal)
        val state = SportsLiveUiState(
            tabs = mapOf(SportsLiveTab.LIVE_NOW to listOf(running, ended), SportsLiveTab.UPCOMING to listOf(item("A vs B"))),
            visibleItems = listOf(running, ended),
            justEnded = mapOf(ended.streamKey to verdict)
        )
        assertEquals(verdict, state.endedOf(ended))
        assertNull(state.endedOf(running))
        assertEquals(1, state.countOf(SportsLiveTab.LIVE_NOW))
        assertEquals(1, state.countOf(SportsLiveTab.UPCOMING))
        assertNull(state.copy(selectedTab = SportsLiveTab.UPCOMING).endedOf(ended))
    }

    @Test
    fun `the player panel lists ended games last, under Just ended, with their remembered final`() {
        val now = pacersFinal.startMs!! + 3 * HOUR
        val ended = item("Indiana Pacers at Philadelphia 76ers", now - 3 * HOUR)
        val running = item("Los Angeles Lakers at Phoenix Suns", now - HOUR)
        val verdict = EndedGame(EndedSignal.FINAL, now - 15 * MINUTE, pacersFinal)
        val rows = groupPanelRows(
            listOf(ended, running),
            mapOf(running.streamKey to live(lakersEvent)),
            ScoresMode.SHOW,
            emptySet(),
            now,
            ended = mapOf(ended.streamKey to verdict)
        )
        assertEquals(listOf(running, ended), rows.map { it.item })
        assertEquals(listOf(ZapPanelGroup.LIVE_NOW, ZapPanelGroup.JUST_ENDED), rows.map { it.group })
        // Untracked now: the chip comes from the final it was moved on.
        assertTrue(rows.last().chip is ScoreChip.Final)
        // Hide until focused: the uniform hidden chip.
        val hidden = groupPanelRows(listOf(ended), emptyMap(), ScoresMode.HIDE_UNTIL_FOCUSED, emptySet(), now, mapOf(ended.streamKey to verdict))
        assertEquals(ScoreChip.Hidden, hidden.single().chip)
        assertEquals(ZapPanelGroup.JUST_ENDED, hidden.single().group)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
