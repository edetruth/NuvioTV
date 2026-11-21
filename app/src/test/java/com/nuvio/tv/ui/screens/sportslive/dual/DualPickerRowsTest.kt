package com.nuvio.tv.ui.screens.sportslive.dual

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** live.23 TV test: "Pick another to watch" rows show each game's logos and score under the Scores setting. */
class DualPickerRowsTest {

    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
    // IND @ PHI: home Philadelphia 76ers, away Indiana Pacers.
    private val pacers = nba.first { it.eventId == "401909985" }
    private val live: ScoreSnapshot = pacers.copy(
        state = ScoreState.IN, isFinal = false, clockText = "4:12 - 4th",
        home = pacers.home.copy(score = 88), away = pacers.away.copy(score = 91)
    )
    private val game = item("76ers vs Pacers")
    private val channel = item("Sports Channel One").copy(isChannel = true)
    private val now = 1_800_000_000_000L

    private fun item(name: String) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false,
        startMs = null, sport = "Basketball", teams = SportsLiveTeams.parse(name)
    )

    private fun rows(mode: ScoresMode?, revealed: Set<String> = emptySet(), snapshot: ScoreSnapshot = live) =
        dualPickerRows(listOf(game, channel), mapOf(game.streamKey to snapshot), mode, revealed, now)

    @Test
    fun `every candidate has a row by its card key`() {
        val rows = rows(ScoresMode.SHOW)
        assertEquals(setOf(game.streamKey, channel.streamKey), rows.keys)
        assertNull(rows.getValue(channel.streamKey).chip)
    }

    @Test
    fun `under Show a scored game's row has both logos and the live score`() {
        val row = rows(ScoresMode.SHOW).getValue(game.streamKey)
        assertNotNull(row.teams)
        assertEquals(pacers.home.logo, row.teams!!.first.logo)
        assertEquals(pacers.away.logo, row.teams!!.second.logo)
        assertTrue(row.chip is ScoreChip.Live)
    }

    @Test
    fun `under Hide until focused the score shows only once the row was focused`() {
        assertEquals(ScoreChip.Hidden, rows(ScoresMode.HIDE_UNTIL_FOCUSED).getValue(game.streamKey).chip)
        assertTrue(rows(ScoresMode.HIDE_UNTIL_FOCUSED, revealed = setOf(game.streamKey)).getValue(game.streamKey).chip is ScoreChip.Live)
    }

    @Test
    fun `under Off or before the setting is read no score shows`() {
        assertNull(rows(ScoresMode.OFF).getValue(game.streamKey).chip)
        assertNull(rows(null).getValue(game.streamKey).chip)
    }

    @Test
    fun `a spoiler-protected game stays hidden under Show and once focused`() {
        val row = rows(ScoresMode.SHOW, revealed = setOf(game.streamKey), snapshot = live.copy(spoilerProtected = true)).getValue(game.streamKey)
        assertEquals(ScoreChip.Hidden, row.chip)
    }
}
