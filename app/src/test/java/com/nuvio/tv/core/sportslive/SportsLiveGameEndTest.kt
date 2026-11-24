package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.MINUTE
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.START
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.final
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.item
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.live
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.pre
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveGameEndTest {

    private val game = item()
    private val strings = GameEndStrings(gameOver = "Game over", finalScore = "Final · %1\$s %2\$d–%3\$d %4\$s")
    private val expectedEnd = SportsLiveGameEnd.expectedEndMs(game)!!

    @Test
    fun `the expected end is the timed start plus the sport's known length`() {
        assertEquals(START + SportsLiveGameLength.knownOf(game)!!, expectedEnd)
        assertNull(SportsLiveGameEnd.expectedEndMs(item(startMs = null)))
    }

    @Test
    fun `an ESPN final is the game ending, not a failure`() {
        assertEquals(GameEndReason.FINAL, SportsLiveGameEnd.failureIsEnd(game, final(99, 101), null, START + 30 * MINUTE))
        assertEquals(GameEndReason.FINAL, SportsLiveGameEnd.ended(game, final(99, 101), null))
    }

    @Test
    fun `the Just ended window is the game ending`() {
        val verdict = EndedGame(EndedSignal.ESTIMATE, expectedEnd)
        assertEquals(GameEndReason.JUST_ENDED, SportsLiveGameEnd.failureIsEnd(game, null, verdict, expectedEnd + 30 * MINUTE))
    }

    @Test
    fun `a stream stopping near the expected end with no ESPN game is the end, mid-game it is a real failure`() {
        assertNull(SportsLiveGameEnd.failureIsEnd(game, null, null, START + 60 * MINUTE))
        assertNull(SportsLiveGameEnd.failureIsEnd(game, null, null, expectedEnd - SportsLiveGameEnd.NEAR_END_MS - MINUTE))
        assertEquals(GameEndReason.STREAM_ENDED, SportsLiveGameEnd.failureIsEnd(game, null, null, expectedEnd - SportsLiveGameEnd.NEAR_END_MS))
        assertEquals(GameEndReason.STREAM_ENDED, SportsLiveGameEnd.failureIsEnd(game, null, null, expectedEnd + 15 * MINUTE))
    }

    @Test
    fun `a game ESPN shows in play is never over however late, so the ladder runs`() {
        assertNull(SportsLiveGameEnd.failureIsEnd(game, live(100, 100, "OT 1:00"), null, expectedEnd + 30 * MINUTE))
        assertNull(SportsLiveGameEnd.failureIsEnd(game, pre(), null, expectedEnd + 30 * MINUTE))
        // Even a stale Just ended verdict does not outrank ESPN showing it in play.
        assertNull(SportsLiveGameEnd.failureIsEnd(game, live(1, 0), EndedGame(EndedSignal.ESTIMATE, START), expectedEnd))
    }

    @Test
    fun `a channel never ends`() {
        val channel = item(isChannel = true)
        assertNull(SportsLiveGameEnd.failureIsEnd(channel, null, EndedGame(EndedSignal.ESTIMATE, START), START + 600 * MINUTE))
    }

    @Test
    fun `the title follows the score and spoiler settings`() {
        assertEquals("Final · Pacers 101–99 76ers", SportsLiveGameEnd.title(game, final(99, 101), ScoresMode.SHOW, strings))
        assertEquals("Game over", SportsLiveGameEnd.title(game, final(99, 101), ScoresMode.HIDE_UNTIL_FOCUSED, strings))
        assertEquals("Game over", SportsLiveGameEnd.title(game, final(99, 101), ScoresMode.OFF, strings))
        assertEquals("Game over", SportsLiveGameEnd.title(game, null, ScoresMode.SHOW, strings))
    }

    @Test
    fun `the countdown runs ten seconds and stops on a key`() {
        val countdown = SportsLiveEndCountdown()
        assertFalse(countdown.running)
        countdown.start(1_000L)
        assertEquals(10, countdown.secondsLeft(1_000L))
        assertEquals(3, countdown.secondsLeft(8_500L))
        assertFalse(countdown.isDue(10_999L))
        assertTrue(countdown.isDue(11_000L))
        assertTrue(countdown.cancel())
        assertFalse(countdown.isDue(20_000L))
        assertEquals(0, countdown.secondsLeft(5_000L))
        assertFalse(countdown.cancel())
    }

    @Test
    fun `the countdown runs only under Move, with a next game, on the pane with the sound`() {
        assertTrue(SportsLiveEndCountdown.runs(SportsLiveGameEndAction.MOVE, hasNext = true, hasSound = true))
        assertFalse(SportsLiveEndCountdown.runs(SportsLiveGameEndAction.MOVE, hasNext = true, hasSound = false))
        assertFalse(SportsLiveEndCountdown.runs(SportsLiveGameEndAction.MOVE, hasNext = false, hasSound = true))
        assertFalse(SportsLiveEndCountdown.runs(SportsLiveGameEndAction.ASK, hasNext = true, hasSound = true))
        assertFalse(SportsLiveEndCountdown.runs(SportsLiveGameEndAction.STAY, hasNext = true, hasSound = true))
    }

    @Test
    fun `the setting reads back, Move by default`() {
        assertEquals(SportsLiveGameEndAction.MOVE, SportsLiveGameEndAction.fromStored(null))
        assertEquals(SportsLiveGameEndAction.MOVE, SportsLiveGameEndAction.fromStored("junk"))
        assertEquals(SportsLiveGameEndAction.ASK, SportsLiveGameEndAction.fromStored("ASK"))
    }

    @Test
    fun `the watch decides from what players noted, and only for a watched game`() {
        var now = START + 30 * MINUTE
        val watch = SportsLiveGameWatch(SportsLiveEndedGames(), nowMs = { now })
        assertNull(watch.failureIsEnd(game.id))
        watch.watch(game)
        assertNull(watch.failureIsEnd(game.id))
        watch.note(game.id, final(99, 101))
        assertEquals(GameEndReason.FINAL, watch.failureIsEnd(game.id))
        watch.note(game.id, live(50, 50))
        assertNull(watch.failureIsEnd(game.id))
        now = expectedEnd + MINUTE
        watch.note(game.id, null)
        assertEquals(GameEndReason.STREAM_ENDED, watch.failureIsEnd(game.id))
    }

    @Test
    fun `#278 - another addon's game under the same raw id never inherits the watched game's ESPN snapshot`() {
        val watch = SportsLiveGameWatch(SportsLiveEndedGames(), nowMs = { START + 30 * MINUTE })
        val first = item(id = "event-1", addonId = "a")
        val other = item(name = "Lakers @ Suns", id = "event-1", addonId = "b")
        watch.watch(first)
        watch.note(first.id, final(99, 101))
        assertEquals(GameEndReason.FINAL, watch.failureIsEnd(first.id))
        watch.watch(other)
        assertNull(watch.snapshot(other.id))
        assertEquals(other.streamKey, watch.item(other.id)?.streamKey)
        // The same source watched again keeps what was noted for it.
        watch.note(other.id, live(50, 50))
        watch.watch(other)
        assertEquals(live(50, 50), watch.snapshot(other.id))
        // L1: re-watched with a newer listing, its item follows; replaced by another addon's game, the event count moves.
        val relisted = other.copy(startMs = START + MINUTE)
        watch.watch(relisted)
        assertEquals(relisted, watch.item(other.id))
        assertEquals(live(50, 50), watch.snapshot(other.id))
        val ids = watch.eventIds.value
        watch.watch(first)
        assertEquals(ids + 1, watch.eventIds.value)
    }

    // #162: "Waiting for the stream to start…"

    @Test
    fun `waiting applies within 30 minutes of the kickoff or while ESPN shows it not started`() {
        assertTrue(SportsLiveKickoffWait.applies(game, null, START - 10 * MINUTE))
        assertTrue(SportsLiveKickoffWait.applies(game, null, START + 25 * MINUTE))
        assertFalse(SportsLiveKickoffWait.applies(game, null, START + 40 * MINUTE))
        assertFalse(SportsLiveKickoffWait.applies(game, null, START - 45 * MINUTE))
        // ESPN not started yet: waits, however late the listed start.
        assertTrue(SportsLiveKickoffWait.applies(game, pre(), START + 50 * MINUTE))
    }

    @Test
    fun `waiting ends once ESPN shows the game in play or over, and never applies to channels`() {
        assertFalse(SportsLiveKickoffWait.applies(game, live(0, 0), START + 5 * MINUTE))
        assertFalse(SportsLiveKickoffWait.applies(game, final(1, 0), START + 5 * MINUTE))
        assertFalse(SportsLiveKickoffWait.applies(item(isChannel = true), null, START))
        assertFalse(SportsLiveKickoffWait.applies(item(startMs = null), null, START))
    }

    @Test
    fun `the watch says a game waits only while the rule holds`() {
        var now = START - 5 * MINUTE
        val watch = SportsLiveGameWatch(SportsLiveEndedGames(), nowMs = { now })
        assertFalse(watch.waits(game.id))
        watch.watch(game)
        assertTrue(watch.waits(game.id))
        // The links stayed dead past the window: the usual choices come back.
        now = START + 31 * MINUTE
        assertFalse(watch.waits(game.id))
    }
}
