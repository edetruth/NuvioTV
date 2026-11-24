package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #474 (Gemini RC F7): an upcoming game's page under a hidden score shows Try again when its load failed. */
class SportsLiveGameFailureTest {

    private val pre = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA).first { it.eventId == "401909985" }
        .copy(state = ScoreState.PRE, isFinal = false)

    @Test
    fun `an upcoming game whose load failed under Hide until focused shows the failure card`() {
        val failed = SportsLiveGameUiState(
            event = pre, summary = null, status = GameLoadStatus.FAILED, mode = ScoresMode.HIDE_UNTIL_FOCUSED, revealed = false, settled = true
        )
        assertTrue(failed.showsPreview)
        assertTrue(failed.showsFailure)
    }

    @Test
    fun `a game in play or over keeps the hidden card, and Scores Off waiting for Reveal asks nothing`() {
        val live = SportsLiveGameUiState(
            event = pre.copy(state = ScoreState.IN), summary = null, status = GameLoadStatus.FAILED,
            mode = ScoresMode.HIDE_UNTIL_FOCUSED, revealed = false, settled = true
        )
        assertFalse(live.showsPreview)
        assertFalse(live.showsFailure)
        val off = SportsLiveGameUiState(event = pre, summary = null, status = GameLoadStatus.WAITING_REVEAL, mode = ScoresMode.OFF, revealed = false, settled = true)
        assertFalse(off.showsPreview)
        assertFalse(off.showsFailure)
    }
}
