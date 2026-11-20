package com.nuvio.tv.ui.screens.sportslive.dual

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.sportslive.SportsLiveEndCountdown
import com.nuvio.tv.core.sportslive.SportsLiveEndedGames
import com.nuvio.tv.core.sportslive.SportsLiveGameEndAction
import com.nuvio.tv.core.sportslive.SportsLiveGameWatch
import com.nuvio.tv.core.sportslive.SportsLiveRecoveryBudget
import com.nuvio.tv.core.sportslive.SportsLiveStreamHealth
import com.nuvio.tv.core.sportslive.SportsLiveTriedGames
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** #161 in dual screen: the ended pane shows the card; only that pane moves on, and only with the sound. */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveDualGameEndTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val now = 1_800_000_000_000L
    private val hour = 60 * 60_000L

    /** A basketball game that started [startedAgoMs] ago. */
    private fun game(id: String, startedAgoMs: Long): SportsLiveItem = dualItem(id).copy(
        meta = dualItem(id).meta.copy(genres = listOf("Basketball")),
        sport = "Basketball",
        startMs = now - startedAgoMs,
        startHasTimeOfDay = true
    )

    // a and b are long over (past their expected end); c and d are mid-game.
    private val a = game("a", 4 * hour)
    private val b = game("b", 4 * hour)
    private val c = game("c", hour)
    private val d = game("d", hour)

    @After
    fun tearDown() {
        SportsLiveTriedGames.shared.clear()
    }

    private fun newViewModel(left: SportsLiveItem, right: SportsLiveItem, action: SportsLiveGameEndAction = SportsLiveGameEndAction.MOVE) =
        SportsLiveDualViewModel(
            resolve = { item -> dualStream(item.id) },
            endedKeys = { emptySet() },
            session = SportsLiveDualSession(left, right, listOf(a, b, c, d), null, SportsLiveTab.LIVE_NOW),
            recoveryBudget = SportsLiveRecoveryBudget(),
            gameWatch = SportsLiveGameWatch(SportsLiveEndedGames(), nowMs = { now }),
            endActions = flowOf(action)
        )

    private fun SportsLiveDualViewModel.playing() {
        val state = state.value!!
        onFirstFrame(state.left.item)
        onFirstFrame(state.right.item)
    }

    @Test
    fun `a stream stopping at the end of the pane with the sound shows the card and moves only that pane after 10 s`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel(a, d)
            runCurrent()
            viewModel.playing()
            viewModel.onUnhealthy(a, SportsLiveStreamHealth.Problem.UNEXPECTED_END)
            val left = viewModel.state.value!!.left
            assertEquals(PaneStatus.Ended, left.status)
            assertEquals(b, left.end!!.next)
            assertNotNull(left.end!!.countdownEndsAtMs)
            assertEquals(PaneStatus.Playing, viewModel.state.value!!.right.status)

            advanceTimeBy(SportsLiveEndCountdown.DURATION_MS + 1)
            runCurrent()
            assertEquals(b, viewModel.state.value!!.left.item)
            // The other pane plays on, untouched.
            assertEquals(d, viewModel.state.value!!.right.item)
            assertEquals(PaneStatus.Playing, viewModel.state.value!!.right.status)
        }

    @Test
    fun `Up next skips a game Live now shows Probably ended`() = runTest(mainDispatcherRule.dispatcher) {
        // live.19 QA F2: b is past its expected end, so Live now shows it Probably ended.
        val liveNow = SportsLiveEndedGames()
        val viewModel = SportsLiveDualViewModel(
            resolve = { item -> dualStream(item.id) },
            endedKeys = { emptySet() },
            session = SportsLiveDualSession(a, d, listOf(a, b, c, d), null, SportsLiveTab.LIVE_NOW),
            recoveryBudget = SportsLiveRecoveryBudget(),
            gameWatch = SportsLiveGameWatch(SportsLiveEndedGames(), nowMs = { now }),
            endActions = flowOf(SportsLiveGameEndAction.MOVE),
            overKeys = { list, _ -> liveNow.overSourceKeys(list, emptyMap(), now) }
        )
        runCurrent()
        viewModel.playing()
        viewModel.onUnhealthy(a, SportsLiveStreamHealth.Problem.UNEXPECTED_END)
        assertEquals(PaneStatus.Ended, viewModel.state.value!!.left.status)
        assertEquals(c, viewModel.state.value!!.left.end!!.next)
    }

    @Test
    fun `a swap during the countdown still moves the ended game's pane, not the other one`() =
        runTest(mainDispatcherRule.dispatcher) {
            val viewModel = newViewModel(a, d)
            runCurrent()
            viewModel.playing()
            viewModel.onUnhealthy(a, SportsLiveStreamHealth.Problem.UNEXPECTED_END)
            viewModel.swap()
            runCurrent()
            assertEquals(a, viewModel.state.value!!.right.item)

            advanceTimeBy(SportsLiveEndCountdown.DURATION_MS + 1)
            runCurrent()
            assertEquals(b, viewModel.state.value!!.right.item)
            assertEquals(d, viewModel.state.value!!.left.item)
            assertEquals(PaneStatus.Playing, viewModel.state.value!!.left.status)
        }

    @Test
    fun `the muted pane's card waits for OK`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel(d, a)
        runCurrent()
        viewModel.playing()
        viewModel.onUnhealthy(a, SportsLiveStreamHealth.Problem.FROZEN)
        val right = viewModel.state.value!!.right
        assertEquals(PaneStatus.Ended, right.status)
        assertNull(right.end!!.countdownEndsAtMs)
        advanceTimeBy(SportsLiveEndCountdown.DURATION_MS * 3)
        runCurrent()
        assertEquals(a, viewModel.state.value!!.right.item)
        // OK on the highlighted ended pane: Watch next.
        viewModel.highlight(Side.RIGHT)
        assertTrue(viewModel.endWatchNext())
        runCurrent()
        assertNotEquals(a, viewModel.state.value!!.right.item)
        assertEquals(d, viewModel.state.value!!.left.item)
    }

    @Test
    fun `any key stops the countdown, and Stay closes the card`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel(a, d)
        runCurrent()
        viewModel.playing()
        viewModel.onUnhealthy(a, SportsLiveStreamHealth.Problem.UNEXPECTED_END)
        assertTrue(viewModel.cancelEndCountdowns())
        advanceTimeBy(SportsLiveEndCountdown.DURATION_MS * 2)
        runCurrent()
        val left = viewModel.state.value!!.left
        assertEquals(a, left.item)
        assertEquals(PaneStatus.Ended, left.status)
        assertNull(left.end!!.countdownEndsAtMs)
        assertTrue(viewModel.endStay())
        assertNull(viewModel.state.value!!.left.end)
        assertEquals(PaneStatus.Ended, viewModel.state.value!!.left.status)
    }

    @Test
    fun `Ask never counts down`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel(a, d, SportsLiveGameEndAction.ASK)
        runCurrent()
        viewModel.playing()
        viewModel.onUnhealthy(a, SportsLiveStreamHealth.Problem.UNEXPECTED_END)
        assertNull(viewModel.state.value!!.left.end!!.countdownEndsAtMs)
    }

    @Test
    fun `a mid-game failure is a real failure, the recovery runs and no card shows`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel(c, d)
        runCurrent()
        viewModel.playing()
        viewModel.onUnhealthy(c, SportsLiveStreamHealth.Problem.FROZEN)
        val left = viewModel.state.value!!.left
        assertNotEquals(PaneStatus.Ended, left.status)
        assertNull(left.end)
        assertTrue(left.reconnecting)
    }
}
