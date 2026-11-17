package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.sportslive.SportsLiveEndedGames
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
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
    fun `ended games leave the Home row, live games and channels stay`() {
        val ended = item("Arsenal vs Chelsea", NOW - 2 * HOUR - 20 * MINUTE)
        val running = item("Leeds vs Everton", NOW - HOUR)
        val channel = item("Sky Sports Main Event", NOW - 6 * HOUR, isChannel = true)
        val untimed = item("Ajax vs PSV", null)
        val items = listOf(ended, running, channel, untimed)

        val endedKeys = SportsLiveEndedGames().endedKeys(items, emptyMap(), NOW)
        assertEquals(setOf(ended.streamKey), endedKeys)

        val state = HomeUiState(liveNowItems = items, liveNowEnded = endedKeys)
        assertEquals(listOf(running, channel, untimed), state.liveNowShown)
        assertEquals(items, HomeUiState(liveNowItems = items).liveNowShown)
    }

    private companion object {
        const val NOW = 1_794_787_200_000L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
