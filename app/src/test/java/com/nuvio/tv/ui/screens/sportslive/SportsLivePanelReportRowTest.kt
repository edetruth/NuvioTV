package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.report.SportsLiveReport
import com.nuvio.tv.core.sportslive.scores.ChipScore
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A report's panel rows (#218, live.25 TV report #21): tag and score, and the game's title for a row with no score. */
class SportsLivePanelReportRowTest {

    private val game = SportsLiveItem(
        meta = MetaPreview(
            id = "g", type = ContentType.UNKNOWN, rawType = "sport", name = "Illinois vs Iowa", poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = listOf("Football")
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false, startMs = null, sport = "Football",
        teams = SportsLiveTeams.parse("Illinois vs Iowa")
    )

    private fun row(chip: ScoreChip?) = ScoreboardRow(
        item = game, group = ScoreboardGroup.LIVE, chip = chip, art = null,
        firstFollowed = false, secondFollowed = false, probablyEnded = false
    )

    @Test
    fun `a row with no score names its game, a scored or hidden row never does`() {
        val none = panelReportRow(row(null))
        assertTrue(none, none.contains(" | no score | "))
        assertTrue(none, none.contains("Illinois"))
        assertTrue(none, none.endsWith("Iowa"))
        val noScoreYet = panelReportRow(row(ScoreChip.Live(clock = null, score = null)))
        assertEquals(none, noScoreYet)
        val scored = panelReportRow(row(ScoreChip.Final(note = null, score = ChipScore(24, 17))))
        assertTrue(scored, scored.endsWith("| ${SportsLiveReport.SCORE_SHOWN}"))
        assertFalse(scored, scored.contains("Illinois"))
        val hidden = panelReportRow(row(ScoreChip.Hidden))
        assertTrue(hidden, hidden.endsWith("| score hidden"))
        assertFalse(hidden, hidden.contains("Illinois"))
    }
}
