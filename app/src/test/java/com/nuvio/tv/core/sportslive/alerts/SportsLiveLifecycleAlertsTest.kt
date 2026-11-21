package com.nuvio.tv.core.sportslive.alerts

import android.view.KeyEvent
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.MINUTE
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.START
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.item
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.live
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.pacersFinal
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.TeamFollows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #154 reminder-only teams, the games-on-screen rule, and ▲ Both's key. */
class SportsLiveLifecycleAlertsTest {

    private val game = item()
    private val event = live(10, 12)
    private val pacers = TeamFollows.teamToFollow(pacersFinal, pacersFinal.away)!!
    private val watchingNothing = SportsLiveWatching(streamStartedAtMs = START - 60 * MINUTE)

    // #154

    @Test
    fun `a reminder-only team's game is reminded, by ESPN id or by name`() {
        val state = RemindersState(remindedTeams = listOf(pacers))
        assertTrue(state.isReminded(game, event))
        assertTrue(state.isReminded(game))
        assertFalse(state.isReminded(item(name = "Knicks @ Celtics")))
        assertFalse(RemindersState().isReminded(game, event))
    }

    @Test
    fun `a per-game opt-out still applies to a reminder-only team's game`() {
        val state = RemindersState(remindedTeams = listOf(pacers), optOuts = listOf(GameReminders.of(game)!!))
        assertFalse(state.isReminded(game, event))
    }

    @Test
    fun `a followed and reminder-only team's game prompts once`() {
        val engine = SportsLiveAlertEngine()
        val state = RemindersState(remindedTeams = listOf(pacers))
        val followedCopy = item(followed = true)
        val otherCopy = item(id = "copy", addonId = "b")
        val games = listOf(followedCopy, otherCopy).map { AlertGame(it, null, state.isReminded(it), emptySet()) }
        val alerts = engine.update(START + MINUTE, games, SportsLiveAlertSettings(), ScoresMode.SHOW, watchingNothing)
        assertEquals(1, alerts.count { it is SportsLiveAlert.Kickoff })
    }

    @Test
    fun `More offers it per side, hidden for followed teams while Remind me for my teams is On`() {
        val followed = listOf(pacers)
        assertFalse(GameReminders.offersTeamReminder(RemindersState(), followed, pacersFinal, pacersFinal.away))
        assertTrue(GameReminders.offersTeamReminder(RemindersState(remindMyTeams = false), followed, pacersFinal, pacersFinal.away))
        assertTrue(GameReminders.offersTeamReminder(RemindersState(), followed, pacersFinal, pacersFinal.home))
        // On the list anyway: offered, so it can be taken off.
        assertTrue(GameReminders.offersTeamReminder(RemindersState(remindedTeams = listOf(pacers)), followed, pacersFinal, pacersFinal.away))
    }

    @Test
    fun `the list round-trips through More and Settings`() {
        val on = GameReminders.toggleTeam(RemindersState(), pacersFinal, pacersFinal.away)
        assertEquals(listOf(pacers), on.remindedTeams)
        assertTrue(GameReminders.isTeamReminded(on, pacersFinal, pacersFinal.away))
        assertFalse(GameReminders.isTeamReminded(on, pacersFinal, pacersFinal.home))
        val off = GameReminders.toggleTeam(on, pacersFinal, pacersFinal.away)
        assertEquals(emptyList<Any>(), off.remindedTeams)
        assertEquals(emptyList<Any>(), GameReminders.removeTeam(on, pacers).remindedTeams)
    }

    // Games on screen never alert

    @Test
    fun `a reminded game playing under another addon's id gives no prompt, now or later`() {
        val engine = SportsLiveAlertEngine()
        val listed = item(followed = true)
        val playingCopy = item(id = "other-addon-copy", addonId = "z")
        val (ids, keys, events) = SportsLiveAlertEngine.watchingKeys(listOf(playingCopy)) { null }
        val watching = SportsLiveWatching(playingIds = ids, playingGameKeys = keys, playingEventIds = events, inPlayer = true, sportsPlayback = true)
        assertEquals(emptyList<SportsLiveAlert>(), engine.update(START + MINUTE, listOf(AlertGame(listed, null, true)), SportsLiveAlertSettings(), ScoresMode.SHOW, watching))
        // The player left: the reminder was satisfied, it never fires.
        assertEquals(emptyList<SportsLiveAlert>(), engine.update(START + 2 * MINUTE, listOf(AlertGame(listed, null, true)), SportsLiveAlertSettings(), ScoresMode.SHOW, watchingNothing))
    }

    @Test
    fun `the same game in either dual pane or the mini player gives no prompt`() {
        val other = item(name = "Knicks @ Celtics", id = "knicks")
        for (onScreen in listOf(listOf(other, game), listOf(game, other), listOf(game))) {
            val engine = SportsLiveAlertEngine()
            val (ids, keys, events) = SportsLiveAlertEngine.watchingKeys(onScreen) { null }
            val watching = SportsLiveWatching(playingIds = ids, playingGameKeys = keys, playingEventIds = events, dual = onScreen.size == 2, miniPlaying = onScreen.size == 1)
            val alerts = engine.update(START + MINUTE, listOf(AlertGame(item(id = "listed"), null, true)), SportsLiveAlertSettings(), ScoresMode.SHOW, watching)
            assertEquals(emptyList<SportsLiveAlert>(), alerts)
        }
    }

    @Test
    fun `no score or full-time card for the game playing, under another addon's copy or by its ESPN game`() {
        val listed = item(id = "addon-b-copy", addonId = "b")
        val playingCopy = item(id = "addon-a-copy", addonId = "a")
        val awaySide = setOf(com.nuvio.tv.core.sportslive.scores.ScoreSide.AWAY)
        val byCopy = SportsLiveAlertEngine.watchingKeys(listOf(playingCopy)) { null }
        val byEvent = SportsLiveAlertEngine.watchingKeys(listOf(item(name = "Indiana at Philadelphia", id = "espn-only"))) { event.eventId }
        for ((ids, keys, events) in listOf(byCopy, byEvent)) {
            val engine = SportsLiveAlertEngine()
            val watching = SportsLiveWatching(
                playingIds = ids, playingGameKeys = keys, playingEventIds = events,
                inPlayer = true, sportsPlayback = true, streamStartedAtMs = START - 60 * MINUTE
            )
            fun step(at: Long, snapshot: com.nuvio.tv.core.sportslive.scores.ScoreSnapshot) =
                engine.update(at, listOf(AlertGame(listed, snapshot, reminded = false, followedSides = awaySide)), SportsLiveAlertSettings(), ScoresMode.SHOW, watching)
            assertEquals(emptyList<SportsLiveAlert>(), step(START + 10 * MINUTE, live(10, 10)))
            assertEquals(emptyList<SportsLiveAlert>(), step(START + 20 * MINUTE, live(10, 13)))
            assertEquals(emptyList<SportsLiveAlert>(), step(START + 180 * MINUTE, AlertFixtures.final(99, 101)))
        }
        // The same game not on screen does alert: the rule is the screen, not the game.
        val engine = SportsLiveAlertEngine()
        engine.update(START + 10 * MINUTE, listOf(AlertGame(listed, live(10, 10), false, awaySide)), SportsLiveAlertSettings(), ScoresMode.SHOW, SportsLiveWatching(inPlayer = true, sportsPlayback = true, playingIds = setOf("other"), streamStartedAtMs = START - 60 * MINUTE))
        val alerts = engine.update(START + 20 * MINUTE, listOf(AlertGame(listed, live(10, 13), false, awaySide)), SportsLiveAlertSettings(), ScoresMode.SHOW, SportsLiveWatching(inPlayer = true, sportsPlayback = true, playingIds = setOf("other"), streamStartedAtMs = START - 60 * MINUTE))
        assertEquals(1, alerts.count { it is SportsLiveAlert.ScoreChange })
    }

    @Test
    fun `a card matched to the ESPN game on screen is that game`() {
        val watching = SportsLiveWatching(playingEventIds = setOf(event.eventId))
        assertTrue(SportsLiveAlertEngine.isPlaying(item(name = "Indiana at Philadelphia", id = "x"), watching, event))
        assertFalse(SportsLiveAlertEngine.isPlaying(item(name = "Indiana at Philadelphia", id = "x"), watching, null))
    }

    // Watch both (live.24: a choice of the countdown card, never a key of its own)

    @Test
    fun `Watch both is chosen on the countdown card in the full player, and is not offered in dual screen`() {
        val fullPlayer = AlertKeyContext(expanded = true, canBoth = true)
        assertEquals(AlertKeyAction.MOVE, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_DPAD_RIGHT, fullPlayer))
        assertEquals(AlertKeyAction.PRESS, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_DPAD_CENTER, fullPlayer))
        val dual = AlertKeyContext(expanded = true, canBoth = false)
        assertEquals(AlertKeyAction.CONSUME, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_DPAD_RIGHT, dual))
        // A collapsed card: Up, Down and Play are the screen's.
        val collapsed = AlertKeyContext(cardOk = true, cardBack = true)
        for (code in listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
            assertEquals(AlertKeyAction.PASS, SportsLiveAlertKeys.route(code, collapsed))
        }
    }
}
