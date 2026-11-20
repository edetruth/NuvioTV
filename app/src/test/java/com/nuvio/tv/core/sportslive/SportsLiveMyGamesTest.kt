package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveReadySchedule.Action
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.MINUTE
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.START
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.item
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.pacersFinal
import com.nuvio.tv.core.sportslive.alerts.GameReminders
import com.nuvio.tv.core.sportslive.alerts.RemindersState
import com.nuvio.tv.core.sportslive.scores.TeamFollows
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** live.20 1b: which games are mine (the only ones checked ahead), when they are checked, and Home's line. */
class SportsLiveMyGamesTest {

    private val notSaved: (com.nuvio.tv.domain.model.SportsLiveItem) -> Boolean = { false }
    private val random = item(name = "Knicks @ Celtics")

    // ---- Which games ----

    @Test
    fun `a followed team's game is mine, a random game never is`() {
        assertTrue(SportsLiveMyGames.isMine(item(followed = true), RemindersState(), notSaved))
        assertFalse(SportsLiveMyGames.isMine(random, RemindersState(), notSaved))
    }

    @Test
    fun `a game with Remind me is mine`() {
        val reminded = RemindersState(reminders = listOf(GameReminders.of(random)!!))
        assertTrue(SportsLiveMyGames.isMine(random, reminded, notSaved))
    }

    @Test
    fun `an every-game reminder team's game is mine`() {
        val pacers = TeamFollows.teamToFollow(pacersFinal, pacersFinal.away)!!
        val state = RemindersState(remindedTeams = listOf(pacers))
        assertTrue(SportsLiveMyGames.isMine(item(), state, notSaved))
        assertFalse(SportsLiveMyGames.isMine(random, state, notSaved))
    }

    @Test
    fun `a followed team's game opted out of its prompt is still mine`() {
        val game = item(followed = true)
        val state = RemindersState(optOuts = listOf(GameReminders.of(game)!!))
        assertTrue(SportsLiveMyGames.isMine(game, state, notSaved))
    }

    @Test
    fun `a game saved for later is never mine, nor a channel or a replay`() {
        val game = item(followed = true)
        assertFalse(SportsLiveMyGames.isMine(game, RemindersState(), savedForLater = { it == game }))
        assertFalse(SportsLiveMyGames.isMine(item(followed = true, isChannel = true), RemindersState(), notSaved))
        assertFalse(SportsLiveMyGames.isMine(game.copy(kind = SportsLiveKind.REPLAY), RemindersState(), notSaved))
    }

    @Test
    fun `my games of a list are its followed and reminded games, once each`() {
        val mine = item(followed = true)
        val reminded = item(name = "Lakers @ Suns", id = "ls")
        val cards = listOf(random, mine, reminded, mine)
        val state = RemindersState(reminders = listOf(GameReminders.of(reminded)!!))
        assertEquals(listOf(mine, reminded), SportsLiveMyGames.of(cards, state, notSaved))
    }

    // ---- When ----

    @Test
    fun `checks start ten minutes before kickoff`() {
        val game = item(followed = true)
        assertEquals(Action.NONE, SportsLiveReadySchedule.next(game, START - 11 * MINUTE, over = false, heldAtMs = null, lastCheckAtMs = null))
        assertEquals(Action.CHECK, SportsLiveReadySchedule.next(game, START - 10 * MINUTE, over = false, heldAtMs = null, lastCheckAtMs = null))
    }

    @Test
    fun `a game with no held link is checked again every five minutes`() {
        val game = item(followed = true, isLive = true)
        val last = START + 2 * MINUTE
        assertEquals(Action.NONE, SportsLiveReadySchedule.next(game, last + 5 * MINUTE - 1, over = false, heldAtMs = null, lastCheckAtMs = last))
        assertEquals(Action.CHECK, SportsLiveReadySchedule.next(game, last + 5 * MINUTE, over = false, heldAtMs = null, lastCheckAtMs = last))
    }

    @Test
    fun `a held link is verified again before its hold lapses`() {
        val game = item(followed = true, isLive = true)
        val held = START + MINUTE
        assertTrue(SportsLiveStreamThresholds.READY_REFRESH_MS < SportsLiveStreamThresholds.READY_HOLD_MS)
        assertEquals(Action.NONE, SportsLiveReadySchedule.next(game, held + SportsLiveStreamThresholds.READY_REFRESH_MS - 1, false, held, held))
        assertEquals(Action.REFRESH, SportsLiveReadySchedule.next(game, held + SportsLiveStreamThresholds.READY_REFRESH_MS, false, held, held))
    }

    @Test
    fun `checks stop when the game is over`() {
        val game = item(followed = true, isLive = true)
        assertEquals(Action.NONE, SportsLiveReadySchedule.next(game, START + 30 * MINUTE, over = true, heldAtMs = null, lastCheckAtMs = null))
        // Nothing says it ended: hours after the start it is left alone.
        val late = START + SportsLiveStreamThresholds.READY_MAX_GAME_MS + 1
        assertEquals(Action.NONE, SportsLiveReadySchedule.next(game, late, over = false, heldAtMs = null, lastCheckAtMs = null))
    }

    @Test
    fun `a game with no timed start is checked only while live`() {
        val untimed = item(followed = true, startMs = null)
        assertFalse(SportsLiveReadySchedule.inWindow(untimed, START, over = false))
        assertTrue(SportsLiveReadySchedule.inWindow(untimed.copy(isLive = true), START, over = false))
    }

    // ---- Home's line ----

    @Test
    fun `Home counts my games today not over, and how many are ready`() {
        val zone = ZoneOffset.UTC
        val now = START - 60 * MINUTE
        val tonight = item(followed = true, id = "t1")
        val live = item(followed = true, id = "t2", isLive = true, startMs = START - 3 * 60 * MINUTE)
        val over = item(followed = true, id = "t3")
        val tomorrow = item(followed = true, id = "t4", startMs = START + 24 * 60 * MINUTE)
        val today = SportsLiveMyGames.today(
            mine = listOf(tonight, live, over, tomorrow),
            nowMs = now,
            isOver = { it == over },
            isReady = { it == live },
            zone = zone
        )
        assertEquals(SportsLiveMyGamesToday(games = 2, ready = 1), today)
        assertNull(SportsLiveMyGames.today(listOf(tomorrow), now, { false }, { false }, zone))
    }
}
