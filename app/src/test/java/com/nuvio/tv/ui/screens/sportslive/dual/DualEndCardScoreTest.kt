package com.nuvio.tv.ui.screens.sportslive.dual

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.sportslive.GameEndReason
import com.nuvio.tv.core.sportslive.GameEndStrings
import com.nuvio.tv.core.sportslive.SportsLiveEndedGames
import com.nuvio.tv.core.sportslive.SportsLiveGameEnd
import com.nuvio.tv.core.sportslive.SportsLiveGameEndAction
import com.nuvio.tv.core.sportslive.SportsLiveGameWatch
import com.nuvio.tv.core.sportslive.SportsLiveRecoveryBudget
import com.nuvio.tv.core.sportslive.SportsLiveStreamHealth
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.SportsLiveTriedGames
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.ui.screens.sportslive.SportsLiveEndCard
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

/** live.23 TV test: a finished game's dual pane end card says the final score under Scores Show, never otherwise. */
@OptIn(ExperimentalCoroutinesApi::class)
class DualEndCardScoreTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val strings = GameEndStrings(gameOver = "Game over", finalScore = "Final · %1\$s %2\$d–%3\$d %4\$s")

    // Crystal Palace (home) 1–3 Liverpool (away), FT.
    private val palace: ScoreSnapshot = EspnFixtures.events(EspnFixtures.ENG1_20261108, EspnFixtures.ENG1).first { it.eventId == "401879243" }
    private val final = "Final · Crystal Palace 1–3 Liverpool"

    private val game: SportsLiveItem = dualItem("Crystal Palace vs Liverpool").copy(
        sport = "Soccer",
        teams = SportsLiveTeams.parse("Crystal Palace vs Liverpool")
    )
    private val other = dualItem("other")

    @After
    fun tearDown() {
        SportsLiveTriedGames.shared.clear()
    }

    private fun title(card: SportsLiveEndCard?) = SportsLiveGameEnd.title(card!!.item, card.snapshot, card.mode, strings)

    private fun endedPane(snapshot: ScoreSnapshot?, mode: ScoresMode?) = DualPane(
        item = game,
        status = PaneStatus.Ended,
        end = SportsLiveEndCard(
            item = game, reason = GameEndReason.STREAM_ENDED, eventId = null, snapshot = snapshot, mode = mode,
            next = null, upcoming = null, boxScore = false, streamStopped = true
        )
    )

    @Test
    fun `the second game's pane card reads its final from the live scores`() = runTest(mainDispatcherRule.dispatcher) {
        // The right pane's game was never played full screen: the game watch had no score for it.
        val viewModel = SportsLiveDualViewModel(
            resolve = { item -> dualStream(item.id) },
            endedKeys = { emptySet() },
            session = SportsLiveDualSession(other, game, listOf(other, game), null, SportsLiveTab.LIVE_NOW),
            recoveryBudget = SportsLiveRecoveryBudget(),
            gameWatch = SportsLiveGameWatch(SportsLiveEndedGames()),
            endActions = flowOf(SportsLiveGameEndAction.MOVE),
            scoresModes = flowOf(ScoresMode.SHOW),
            scoreOf = { item -> palace.takeIf { item == game } }
        )
        runCurrent()
        viewModel.onFirstFrame(other)
        viewModel.onFirstFrame(game)
        viewModel.onUnhealthy(game, SportsLiveStreamHealth.Problem.UNEXPECTED_END)
        val right = viewModel.state.value!!.right
        assertEquals(PaneStatus.Ended, right.status)
        assertEquals(final, title(right.end))
    }

    @Test
    fun `a final that comes in after the pane ended shows under Show`() {
        assertEquals(final, title(endedPane(null, ScoresMode.SHOW).withLatestEndScore(palace, ScoresMode.SHOW).end))
    }

    @Test
    fun `Hide until focused, Off and a protected game keep Game over`() {
        assertEquals("Game over", title(endedPane(null, ScoresMode.SHOW).withLatestEndScore(palace, ScoresMode.HIDE_UNTIL_FOCUSED).end))
        assertEquals("Game over", title(endedPane(null, ScoresMode.SHOW).withLatestEndScore(palace, ScoresMode.OFF).end))
        val protectedFinal = palace.copy(spoilerProtected = true)
        assertEquals("Game over", title(endedPane(null, ScoresMode.SHOW).withLatestEndScore(protectedFinal, ScoresMode.SHOW).end))
        // A protection that starts after the card read the final hides it again.
        assertEquals("Game over", title(endedPane(palace, ScoresMode.SHOW).withLatestEndScore(protectedFinal, ScoresMode.SHOW).end))
    }

    @Test
    fun `a pane with no card, or no new score, is left as it is`() {
        val playing = DualPane(item = game, status = PaneStatus.Playing)
        assertSame(playing, playing.withLatestEndScore(palace, ScoresMode.SHOW))
        val ended = endedPane(palace, ScoresMode.SHOW)
        assertSame(ended, ended.withLatestEndScore(null, null))
    }
}
