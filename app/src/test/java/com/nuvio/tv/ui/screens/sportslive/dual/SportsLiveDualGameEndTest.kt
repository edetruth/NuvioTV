package com.nuvio.tv.ui.screens.sportslive.dual

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.sportslive.SportsLiveEndCountdown
import com.nuvio.tv.core.sportslive.SportsLiveEndedGames
import com.nuvio.tv.core.sportslive.SportsLiveGameEndAction
import com.nuvio.tv.core.sportslive.SportsLiveGameReadiness
import com.nuvio.tv.core.sportslive.SportsLiveStillWatching
import com.nuvio.tv.core.sportslive.SportsLiveGameWatch
import com.nuvio.tv.core.sportslive.SportsLiveRecoveryBudget
import com.nuvio.tv.core.sportslive.SportsLiveStreamHealth
import com.nuvio.tv.core.sportslive.SportsLiveTriedGames
import com.nuvio.tv.core.sportslive.SportsLiveWorkingLinks
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
import org.junit.Assert.assertFalse
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

    // a and b are long over (past their expected end); c and d are mid-game. b is one of my games (a followed team).
    private val a = game("a", 4 * hour)
    private val b = game("b", 4 * hour).copy(followed = true)
    private val c = game("c", hour)
    private val d = game("d", hour)

    @After
    fun tearDown() {
        SportsLiveTriedGames.shared.clear()
    }

    /** "▶ Ready" marks: b holds a verified link unless a test says otherwise. */
    private fun readyB(): SportsLiveGameReadiness = SportsLiveGameReadiness(nowMs = { now }).also { it.ready(b.id, dualStream("b")) }

    private fun newViewModel(
        left: SportsLiveItem,
        right: SportsLiveItem,
        action: SportsLiveGameEndAction = SportsLiveGameEndAction.MOVE,
        readiness: SportsLiveGameReadiness = readyB(),
        lineup: List<SportsLiveItem> = listOf(a, b, c, d),
        stillWatching: SportsLiveStillWatching = SportsLiveStillWatching(nowMs = { now })
    ) = SportsLiveDualViewModel(
        resolve = { item -> dualStream(item.id) },
        endedKeys = { emptySet() },
        session = SportsLiveDualSession(left, right, lineup, null, SportsLiveTab.LIVE_NOW),
        recoveryBudget = SportsLiveRecoveryBudget(),
        gameWatch = SportsLiveGameWatch(SportsLiveEndedGames(), nowMs = { now }),
        endActions = flowOf(action),
        readiness = readiness,
        stillWatching = stillWatching
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
    fun `Up next in a pane never picks a game saved for later`() = runTest(mainDispatcherRule.dispatcher) {
        // #184: b was saved with Watch later; playing it would show it live.
        val viewModel = SportsLiveDualViewModel(
            resolve = { item -> dualStream(item.id) },
            endedKeys = { emptySet() },
            session = SportsLiveDualSession(a, d, listOf(a, b, c, d), null, SportsLiveTab.LIVE_NOW),
            recoveryBudget = SportsLiveRecoveryBudget(),
            gameWatch = SportsLiveGameWatch(SportsLiveEndedGames(), nowMs = { now }),
            endActions = flowOf(SportsLiveGameEndAction.MOVE),
            savedForLater = { { it == b } }
        )
        runCurrent()
        viewModel.playing()
        viewModel.onUnhealthy(a, SportsLiveStreamHealth.Problem.UNEXPECTED_END)
        assertEquals(c, viewModel.state.value!!.left.end!!.next)
    }

    @Test
    fun `Up next in a pane skips a game whose every known link failed`() = runTest(mainDispatcherRule.dispatcher) {
        val links = SportsLiveWorkingLinks(nowMs = { now })
        val dead = dualStream("b-only")
        links.noteLinks(b.id, listOf(dead))
        links.failed(b.id, dead.url)
        val viewModel = newViewModelWith(links = links)
        runCurrent()
        viewModel.playing()
        viewModel.onUnhealthy(a, SportsLiveStreamHealth.Problem.UNEXPECTED_END)
        assertEquals(c, viewModel.state.value!!.left.end!!.next)
    }

    private fun newViewModelWith(
        savedForLater: () -> (SportsLiveItem) -> Boolean = { { false } },
        links: SportsLiveWorkingLinks = SportsLiveWorkingLinks(nowMs = { now })
    ) = SportsLiveDualViewModel(
        resolve = { item -> dualStream(item.id) },
        endedKeys = { emptySet() },
        session = SportsLiveDualSession(a, d, listOf(a, b, c, d), null, SportsLiveTab.LIVE_NOW),
        recoveryBudget = SportsLiveRecoveryBudget(),
        gameWatch = SportsLiveGameWatch(SportsLiveEndedGames(), nowMs = { now }),
        endActions = flowOf(SportsLiveGameEndAction.MOVE),
        workingLinks = links,
        savedForLater = savedForLater
    )

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

    @Test
    fun `Up next that is not one of my games waits for OK, with no countdown`() = runTest(mainDispatcherRule.dispatcher) {
        // Research 6: no leading sports app switches to an unrelated game by itself. c is nobody's team.
        val viewModel = newViewModel(a, d, lineup = listOf(a, c, d))
        runCurrent()
        viewModel.playing()
        viewModel.onUnhealthy(a, SportsLiveStreamHealth.Problem.UNEXPECTED_END)
        val left = viewModel.state.value!!.left
        assertEquals(c, left.end!!.next)
        assertNull(left.end!!.countdownEndsAtMs)
        assertFalse(left.end!!.stillWatching)
        advanceTimeBy(SportsLiveEndCountdown.DURATION_MS * 3)
        runCurrent()
        assertEquals(a, viewModel.state.value!!.left.item)
    }

    @Test
    fun `my game with no Ready mark waits for OK too`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel(a, d, readiness = SportsLiveGameReadiness(nowMs = { now }))
        runCurrent()
        viewModel.playing()
        viewModel.onUnhealthy(a, SportsLiveStreamHealth.Problem.UNEXPECTED_END)
        val left = viewModel.state.value!!.left
        assertEquals(b, left.end!!.next)
        assertNull(left.end!!.countdownEndsAtMs)
    }

    @Test
    fun `Up next prefers my Ready game over the next one in the lineup`() = runTest(mainDispatcherRule.dispatcher) {
        // c comes first after a, but e is mine and Ready: it is the one the card names and moves to.
        val e = game("e", hour).copy(followed = true)
        val readiness = SportsLiveGameReadiness(nowMs = { now }).also { it.ready(e.id, dualStream("e")) }
        val viewModel = newViewModel(a, d, readiness = readiness, lineup = listOf(a, c, d, e))
        runCurrent()
        viewModel.playing()
        viewModel.onUnhealthy(a, SportsLiveStreamHealth.Problem.UNEXPECTED_END)
        assertEquals(e, viewModel.state.value!!.left.end!!.next)
        assertNotNull(viewModel.state.value!!.left.end!!.countdownEndsAtMs)
    }

    @Test
    fun `after hours with no key press the card asks Are you still watching instead of moving`() =
        runTest(mainDispatcherRule.dispatcher) {
            var clock = now
            val guard = SportsLiveStillWatching(nowMs = { clock })
            clock += SportsLiveStillWatching.IDLE_LIMIT_MS
            val viewModel = newViewModel(a, d, stillWatching = guard)
            runCurrent()
            viewModel.playing()
            viewModel.onUnhealthy(a, SportsLiveStreamHealth.Problem.UNEXPECTED_END)
            val left = viewModel.state.value!!.left
            assertEquals(b, left.end!!.next)
            assertTrue(left.end!!.stillWatching)
            assertNull(left.end!!.countdownEndsAtMs)
            advanceTimeBy(SportsLiveEndCountdown.DURATION_MS * 3)
            runCurrent()
            assertEquals(a, viewModel.state.value!!.left.item)
            // OK on it: Watch next.
            assertTrue(viewModel.endWatchNext())
            assertEquals(b, viewModel.state.value!!.left.item)
        }

    @Test
    fun `an automatic move counts toward the still-watching guard`() = runTest(mainDispatcherRule.dispatcher) {
        val guard = SportsLiveStillWatching(nowMs = { now })
        val viewModel = newViewModel(a, d, stillWatching = guard)
        runCurrent()
        viewModel.playing()
        viewModel.onUnhealthy(a, SportsLiveStreamHealth.Problem.UNEXPECTED_END)
        advanceTimeBy(SportsLiveEndCountdown.DURATION_MS + 1)
        runCurrent()
        assertEquals(b, viewModel.state.value!!.left.item)
        repeat(SportsLiveStillWatching.MAX_AUTO_MOVES - 2) { guard.onAutoMove() }
        assertTrue(guard.mayAutoMove())
        guard.onAutoMove()
        assertFalse(guard.mayAutoMove())
    }

    @Test
    fun `Back closes a pane's end card, its countdown with it, and the pane stays on Game over`() = runTest(mainDispatcherRule.dispatcher) {
        // Coordinator decision 4: a pane's card has no Stay; Back closes it.
        val viewModel = newViewModel(a, d)
        runCurrent()
        viewModel.playing()
        viewModel.onUnhealthy(a, SportsLiveStreamHealth.Problem.UNEXPECTED_END)
        assertNotNull(viewModel.state.value!!.left.end!!.countdownEndsAtMs)
        // The ring on the ended pane: its card closes.
        assertEquals(Side.LEFT, viewModel.state.value!!.highlighted)
        assertTrue(viewModel.closeEndCard())
        assertNull(viewModel.state.value!!.left.end)
        assertEquals(PaneStatus.Ended, viewModel.state.value!!.left.status)
        advanceTimeBy(SportsLiveEndCountdown.DURATION_MS * 2)
        runCurrent()
        assertEquals(a, viewModel.state.value!!.left.item)
        // Nothing left to close: Back goes on as before.
        assertFalse(viewModel.closeEndCard())
    }

    @Test
    fun `side by side Back with the ring on the healthy pane leaves the other pane's end card, big and small closes it`() = runTest(mainDispatcherRule.dispatcher) {
        // live.24 RC2 review R3: side by side, Back acts on the ringed pane as OK does (the other pane's card shows no keys),
        // so it plays the ringed game full screen; in big + small the other pane's card still takes Back.
        val viewModel = newViewModel(a, d)
        runCurrent()
        viewModel.playing()
        viewModel.onUnhealthy(a, SportsLiveStreamHealth.Problem.UNEXPECTED_END)
        viewModel.highlight(Side.RIGHT)
        assertEquals(DualLayout.SPLIT, viewModel.state.value!!.layout)
        assertFalse(viewModel.closeEndCard())
        assertNotNull(viewModel.state.value!!.left.end)
        // Back goes on: the countdown left on the other pane stops, its card stays.
        assertTrue(viewModel.cancelEndCountdowns())
        assertNotNull(viewModel.state.value!!.left.end)
        viewModel.toggleLayout()
        runCurrent()
        assertEquals(DualLayout.BIG_SMALL, viewModel.state.value!!.layout)
        val ended = viewModel.state.value!!.sideOf(a)!!
        viewModel.highlight(ended.other)
        assertTrue(viewModel.closeEndCard())
        assertNull(viewModel.state.value!!.pane(ended).end)
    }
}
