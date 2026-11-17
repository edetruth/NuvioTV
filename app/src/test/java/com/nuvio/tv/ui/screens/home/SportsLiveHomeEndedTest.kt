package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.sportslive.SportsLiveEndedGames
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home's Live now row drops games found over (#60); it has no Just ended strip. */
class SportsLiveHomeEndedTest {

    private fun item(name: String, startMs: Long?, isChannel: Boolean = false) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = listOf("Soccer")
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = if (isChannel) SportsLiveKind.CHANNELS else SportsLiveKind.EVENTS, isLive = true, isChannel = isChannel,
        startMs = startMs, sport = "Soccer", startHasTimeOfDay = startMs != null,
        teams = if (isChannel) null else SportsLiveTeams.parse(name)
    )

    @Test
    fun `Home drops ESPN-confirmed finals only, live games and channels stay`() {
        val arsenal = item("Arsenal vs Chelsea", NOW - 2 * HOUR)
        val running = item("Leeds vs Everton", NOW - HOUR)
        val channel = item("Sky Sports Main Event", NOW - 6 * HOUR, isChannel = true)
        val items = listOf(arsenal, running, channel)
        val final = EspnFixtures.events(EspnFixtures.ENG1_20261108, EspnFixtures.ENG1).first { it.eventId == "401879243" }
        // Crystal Palace 1-3 Liverpool, re-titled for the card.
        val card = item("Crystal Palace vs Liverpool", NOW - 2 * HOUR)
        val games = SportsLiveEndedGames()
        games.verdict(card, final.copy(state = ScoreState.IN, isFinal = false), NOW - 30 * MINUTE)
        games.verdict(card, final, NOW - 15 * MINUTE)

        val endedKeys = games.confirmedEndedKeys(items + card, mapOf(card.streamKey to final), NOW)
        assertEquals(setOf(card.streamKey), endedKeys)

        val state = HomeUiState(liveNowItems = items + card, liveNowEnded = endedKeys)
        assertEquals(items, state.liveNowShown)
        assertEquals(items + card, HomeUiState(liveNowItems = items + card).liveNowShown)
    }

    @Test
    fun `with no score data (Hide until focused or Off) an overdue game is never dropped`() {
        val overdue = item("Arsenal vs Chelsea", NOW - 8 * HOUR)
        val untimed = item("Ajax vs PSV", null)
        val items = listOf(overdue, untimed)
        // Hide until focused: Home tracks nothing, so it has no scores; Off: the feed is empty.
        val endedKeys = SportsLiveEndedGames().confirmedEndedKeys(items, emptyMap(), NOW)
        assertTrue(endedKeys.isEmpty())
        assertEquals(items, HomeUiState(liveNowItems = items, liveNowEnded = endedKeys).liveNowShown)
    }

    private companion object {
        const val NOW = 1_794_787_200_000L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
