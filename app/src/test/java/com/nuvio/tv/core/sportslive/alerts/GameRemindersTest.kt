package com.nuvio.tv.core.sportslive.alerts

import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.MINUTE
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.START
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.item
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameRemindersTest {

    private val now = START - 60 * MINUTE

    @Test
    fun `Remind me is offered on a timed game not started yet, never on live games, channels or replays`() {
        assertTrue(GameReminders.canRemind(item(), now))
        assertFalse(GameReminders.canRemind(item(), START + MINUTE))
        assertFalse(GameReminders.canRemind(item(isLive = true), now))
        assertFalse(GameReminders.canRemind(item(isChannel = true), now))
        assertFalse(GameReminders.canRemind(item(startMs = null), now))
        assertFalse(GameReminders.canRemind(item().copy(kind = SportsLiveKind.REPLAY), now))
        assertFalse(GameReminders.canRemind(item().copy(startHasTimeOfDay = false), now))
    }

    @Test
    fun `a reminder keeps every source's key, the sides and the start`() {
        val merged = item(alternates = listOf(item(id = "b1", addonId = "b")))
        val reminder = GameReminders.of(merged)!!
        assertEquals(setOf("a|Pacers @ 76ers", "b|b1"), reminder.keys)
        assertEquals("Pacers @ 76ers", reminder.title)
        assertEquals(START, reminder.startMs)
        assertNull(GameReminders.of(item(startMs = null)))
    }

    @Test
    fun `a reminder matches its card by any source key, or the same sides starting within 30 minutes`() {
        val reminder = GameReminders.of(item())!!
        assertTrue(GameReminders.matches(reminder, item()))
        // Merged later with another addon as primary: the old key is an alternate.
        assertTrue(GameReminders.matches(reminder, item(id = "x", addonId = "z", alternates = listOf(item()))))
        // Re-listed under a new id, sides either way round.
        assertTrue(GameReminders.matches(reminder, item(name = "Philadelphia 76ers vs Indiana Pacers", id = "new", startMs = START + 20 * MINUTE)))
        assertFalse(GameReminders.matches(reminder, item(id = "new", startMs = START + 2 * 60 * MINUTE)))
        assertFalse(GameReminders.matches(reminder, item(name = "Pacers @ Knicks", id = "new")))
    }

    @Test
    fun `followed teams' games are reminded automatically unless opted out or the setting is off`() {
        val followed = item(followed = true)
        assertTrue(RemindersState().isReminded(followed))
        assertFalse(RemindersState().isReminded(item()))
        assertFalse(RemindersState(remindMyTeams = false).isReminded(followed))
        assertFalse(RemindersState(optOuts = listOf(GameReminders.of(followed)!!)).isReminded(followed))
    }

    @Test
    fun `the bell turns a reminder on and off, and off opts a followed game out`() {
        val game = item()
        val on = GameReminders.toggle(RemindersState(), game, now)
        assertTrue(on.isReminded(game))
        val off = GameReminders.toggle(on, game, now)
        assertFalse(off.isReminded(game))
        assertEquals(emptyList<GameReminder>(), off.reminders)

        val followed = item(followed = true)
        val optedOut = GameReminders.toggle(RemindersState(), followed, now)
        assertFalse(optedOut.isReminded(followed))
        assertEquals(1, optedOut.optOuts.size)
        val back = GameReminders.toggle(optedOut, followed, now)
        assertTrue(back.isReminded(followed))
        assertEquals(emptyList<GameReminder>(), back.optOuts)
        // Back on by hand: it stays reminded even with "Remind me for my teams" turned off.
        assertTrue(back.copy(remindMyTeams = false).isReminded(followed))
    }

    @Test
    fun `old reminders are pruned on the next change`() {
        val old = GameReminders.of(item(id = "old", name = "Bulls vs Bucks", startMs = START - 13 * 60 * MINUTE))!!
        val state = GameReminders.toggle(RemindersState(reminders = listOf(old)), item(), START)
        assertEquals(listOf(GameReminders.of(item())!!), state.reminders)
    }

    @Test
    fun `an addon reusing an id for a later rematch is another game`() {
        val reminder = GameReminders.of(item())!!
        val rematch = item(startMs = START + 2 * 24 * 60 * MINUTE)
        assertEquals(reminder.keys, GameReminders.of(rematch)!!.keys)
        assertFalse(GameReminders.matches(reminder, rematch))
        assertFalse(RemindersState(reminders = listOf(reminder), remindMyTeams = false).isReminded(rematch))
        // Nor is the rematch opted out by the first game's opt-out.
        assertTrue(RemindersState(optOuts = listOf(reminder)).isReminded(rematch.copy(followed = true)))
        // A key match needs a start: an untimed card is never taken for the reminded game.
        assertFalse(GameReminders.matches(reminder, item(startMs = null)))
    }

    @Test
    fun `reminders and opt-outs of games started over six hours ago are dropped when read`() {
        val recent = GameReminders.of(item())!!
        val old = GameReminders.of(item(id = "old", name = "Bulls vs Bucks", startMs = START - 7 * 60 * MINUTE))!!
        assertEquals(listOf(recent), GameReminders.current(listOf(old, recent), START + 5 * 60 * MINUTE))
        assertEquals(emptyList<GameReminder>(), GameReminders.current(listOf(old, recent), START + 7 * 60 * MINUTE))
    }
}
