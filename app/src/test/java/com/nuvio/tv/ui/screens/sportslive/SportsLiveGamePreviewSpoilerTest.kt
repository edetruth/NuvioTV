package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.GameSection
import com.nuvio.tv.core.sportslive.scores.GameSummaryFixtures
import com.nuvio.tv.core.sportslive.scores.PreviewBlock
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Game page's Preview hides other games' results (form, head-to-head) until revealed (live.28 H5). */
class SportsLiveGamePreviewSpoilerTest {

    private val nba = GameSummaryFixtures.summary("espn_summary_nba_preview_401910019.json", EspnLeagueMap.NBA, "401910019")

    private fun state(mode: ScoresMode, revealed: Boolean = false, spoilerProtected: Boolean = false) =
        SportsLiveGameUiState(summary = nba, mode = mode, revealed = revealed, spoilerProtected = spoilerProtected)

    private fun blocks(state: SportsLiveGameUiState) = state.shownSummary!!.preview!!.blocks

    @Test
    fun `under Show the Preview keeps its form and head-to-head`() {
        val shown = state(ScoresMode.SHOW)
        assertTrue(blocks(shown).containsAll(listOf(PreviewBlock.FORM, PreviewBlock.HEAD_TO_HEAD)))
        assertEquals(GameSection.PREVIEW, shown.section)
    }

    @Test
    fun `under Hide until I reveal the Preview shows no form or head-to-head`() {
        val hidden = state(ScoresMode.HIDE_UNTIL_FOCUSED)
        assertFalse(blocks(hidden).contains(PreviewBlock.FORM))
        assertFalse(blocks(hidden).contains(PreviewBlock.HEAD_TO_HEAD))
        assertTrue(blocks(hidden).contains(PreviewBlock.LEADERS))
        assertEquals(GameSection.PREVIEW, hidden.section)
    }

    @Test
    fun `revealing brings the form and head-to-head back`() {
        assertTrue(blocks(state(ScoresMode.HIDE_UNTIL_FOCUSED, revealed = true)).contains(PreviewBlock.FORM))
    }

    @Test
    fun `a protected game hides the form and head-to-head under Show`() {
        assertFalse(blocks(state(ScoresMode.SHOW, spoilerProtected = true)).contains(PreviewBlock.FORM))
    }
}
