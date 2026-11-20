package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.START
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.item
import com.nuvio.tv.core.sportslive.alerts.GameReminders
import com.nuvio.tv.core.sportslive.alerts.RemindersState
import com.nuvio.tv.domain.model.SportsLiveItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #161 after research 6: the end card moves by itself only into my Ready games, and asks after a long idle stretch. */
class SportsLiveEndMoveTest {

    private val next = item()
    private val mine: (SportsLiveItem) -> Boolean = { true }
    private val notMine: (SportsLiveItem) -> Boolean = { false }
    private val ready: (SportsLiveItem) -> Boolean = { true }
    private val notReady: (SportsLiveItem) -> Boolean = { false }

    private fun move(
        action: SportsLiveGameEndAction = SportsLiveGameEndAction.MOVE,
        next: SportsLiveItem? = this.next,
        hasSound: Boolean = true,
        isMine: (SportsLiveItem) -> Boolean = mine,
        isReady: (SportsLiveItem) -> Boolean = ready,
        mayAutoMove: Boolean = true
    ) = SportsLiveEndCountdown.move(action, next, hasSound = hasSound, isMine = isMine, isReady = isReady, mayAutoMove = mayAutoMove)

    @Test
    fun `Move counts down only into one of my games showing Ready`() {
        assertEquals(SportsLiveEndMove.COUNTDOWN, move())
        assertEquals(SportsLiveEndMove.WAIT, move(isMine = notMine))
        assertEquals(SportsLiveEndMove.WAIT, move(isReady = notReady))
        assertEquals(SportsLiveEndMove.WAIT, move(isMine = notMine, isReady = notReady))
    }

    @Test
    fun `Ask and Stay never count down, nor a muted pane or a card with nothing next`() {
        assertEquals(SportsLiveEndMove.WAIT, move(action = SportsLiveGameEndAction.ASK))
        assertEquals(SportsLiveEndMove.WAIT, move(action = SportsLiveGameEndAction.STAY))
        assertEquals(SportsLiveEndMove.WAIT, move(hasSound = false))
        assertEquals(SportsLiveEndMove.WAIT, move(next = null))
    }

    @Test
    fun `the still-watching guard turns a move into the question, never a wait into it`() {
        assertEquals(SportsLiveEndMove.STILL_WATCHING, move(mayAutoMove = false))
        // Nothing would have moved: no question either.
        assertEquals(SportsLiveEndMove.WAIT, move(mayAutoMove = false, isMine = notMine))
        assertEquals(SportsLiveEndMove.WAIT, move(mayAutoMove = false, action = SportsLiveGameEndAction.ASK))
    }

    @Test
    fun `my games are followed or reminded ones, never one saved for later`() {
        val followed = item().copy(followed = true)
        assertTrue(SportsLiveMyGames.isMine(followed, RemindersState(), savedForLater = { false }))
        assertFalse(SportsLiveMyGames.isMine(followed, RemindersState(), savedForLater = { true }))
        assertFalse(SportsLiveMyGames.isMine(item(), RemindersState(), savedForLater = { false }))
        val reminded = GameReminders.toggle(RemindersState(), item(), nowMs = START)
        assertTrue(SportsLiveMyGames.isMine(item(), reminded, savedForLater = { false }))
    }

    @Test
    fun `the guard allows a few automatic moves in a row, then asks`() {
        val guard = SportsLiveStillWatching(nowMs = { 0L })
        repeat(SportsLiveStillWatching.MAX_AUTO_MOVES) {
            assertTrue(guard.mayAutoMove())
            guard.onAutoMove()
        }
        assertFalse(guard.mayAutoMove())
        // A key press: the viewer is here, it starts afresh.
        guard.onInteraction()
        assertTrue(guard.mayAutoMove())
    }

    @Test
    fun `the guard asks once the last key press is three hours old`() {
        var now = 1_000L
        val guard = SportsLiveStillWatching(nowMs = { now })
        now += SportsLiveStillWatching.IDLE_LIMIT_MS - 1
        assertTrue(guard.mayAutoMove())
        now += 1
        assertFalse(guard.mayAutoMove())
        guard.onInteraction()
        assertTrue(guard.mayAutoMove())
        assertEquals(3 * 60 * 60_000L, SportsLiveStillWatching.IDLE_LIMIT_MS)
    }
}
