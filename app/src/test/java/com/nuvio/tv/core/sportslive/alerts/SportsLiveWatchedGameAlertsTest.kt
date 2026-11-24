package com.nuvio.tv.core.sportslive.alerts

import com.nuvio.tv.core.sportslive.SportsLiveGameWatch
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.MINUTE
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.START
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.final
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.item
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.live
import com.nuvio.tv.core.sportslive.scores.ProtectedGame
import com.nuvio.tv.core.sportslive.scores.ScoreSide
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreWatch
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.SpoilerProtection
import com.nuvio.tv.domain.model.FollowedTeam
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live.28 (Houston game report, BATCH 2026-11-23 22:20 and 22:30): no alert about the game on screen, however it is
 * known; and the hidden final's option 1: a game whose live score showed on a card shows its final score too.
 */
class SportsLiveWatchedGameAlertsTest {

    private val card = item(followed = true)
    private val watchingOther = SportsLiveWatching(playingIds = setOf("movie"), inPlayer = true, sportsPlayback = true, streamStartedAtMs = START - 60 * MINUTE)
    private val sixers = listOf(FollowedTeam("Philadelphia 76ers", "Basketball"))

    // The game on screen

    @Test
    fun `a game whose score is blanked as watched gets no score card and no full time card`() {
        val engine = SportsLiveAlertEngine()
        fun step(at: Long, snapshot: ScoreSnapshot) =
            engine.update(at, listOf(AlertGame(card, snapshot, false, setOf(ScoreSide.AWAY))), AlertFixtures.EVERY, ScoresMode.SHOW, watchingOther)
        step(START + 10 * MINUTE, live(10, 8))
        // The player learned nothing yet (no id, another addon's listing), but the scores blank the game it plays.
        assertEquals(emptyList<SportsLiveAlert>(), step(START + 12 * MINUTE, ScoreWatch.masked(live(12, 8))))
        assertEquals(emptyList<SportsLiveAlert>(), step(START + 130 * MINUTE, ScoreWatch.masked(final(101, 99))))
        assertTrue(SportsLiveAlertEngine.isPlaying(card, SportsLiveWatching(), ScoreWatch.masked(live(1, 1))))
    }

    @Test
    fun `a reminded game blanked as watched never prompts`() {
        val engine = SportsLiveAlertEngine()
        val alerts = engine.update(START + MINUTE, listOf(AlertGame(card, ScoreWatch.masked(live(0, 0)), true)), SportsLiveAlertSettings(), ScoresMode.SHOW, watchingOther)
        assertEquals(emptyList<SportsLiveAlert>(), alerts)
    }

    @Test
    fun `another addon's copy listed across midnight UTC is the game on screen, a rematch is not`() {
        val day = 24 * 60 * MINUTE
        val midnight = (START / day + 1) * day
        val playing = item(id = "copy-a", addonId = "a", startMs = midnight - 10 * MINUTE)
        val (ids, games, events) = SportsLiveAlertEngine.watchingKeys(listOf(playing)) { null }
        val watching = SportsLiveWatching(playingIds = ids, playingGameKeys = games, playingEventIds = events, inPlayer = true, sportsPlayback = true)
        // The old key was the UTC day: these two differ, yet they are one game.
        val listed = item(id = "copy-b", addonId = "b", startMs = midnight + 20 * MINUTE)
        assertTrue(SportsLiveAlertEngine.gameKey(playing) != SportsLiveAlertEngine.gameKey(listed))
        assertTrue(SportsLiveAlertEngine.isPlaying(listed, watching))
        assertTrue(SportsLiveAlertEngine.isPlaying(item(id = "copy-c", addonId = "c", startMs = midnight + 5 * 60 * MINUTE), watching))
        assertFalse(SportsLiveAlertEngine.isPlaying(item(id = "copy-d", addonId = "d", startMs = midnight + 7 * 60 * MINUTE), watching))
        assertFalse(SportsLiveAlertEngine.isPlaying(item(id = "rematch", addonId = "b", startMs = midnight + 2 * day), watching))
        // Another game at the same time is not it.
        assertFalse(SportsLiveAlertEngine.isPlaying(item(name = "Knicks @ Heat", id = "other", startMs = midnight), watching))
        // A listing with no start: the same two sides are the game.
        assertTrue(SportsLiveAlertEngine.isPlaying(item(id = "copy-e", addonId = "e", startMs = null), watching))
    }

    @Test
    fun `the game's ESPN id learned after the player opened tells the alerts to read the screen again`() {
        val watch = SportsLiveGameWatch()
        watch.watch(card)
        val before = watch.eventIds.value
        watch.note(card.id, live(10, 8))
        assertEquals(before + 1, watch.eventIds.value)
        // Score updates of the same game: nothing new to read.
        watch.note(card.id, live(12, 8))
        assertEquals(before + 1, watch.eventIds.value)
        watch.note(card.id, live(12, 8).copy(eventId = "another"))
        assertEquals(before + 2, watch.eventIds.value)
        // A game no player watches changes nothing.
        watch.note("not-watched", live(1, 1))
        assertEquals(before + 2, watch.eventIds.value)
        assertEquals("another", watch.snapshot(card.id)?.eventId)
    }

    // Hidden final, option 1

    private fun alerts() = SportsLiveAlerts(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))

    private fun hiddenFinal(eventId: String) = SportsLiveAlert.FullTime(
        card, final(101, 99).copy(eventId = eventId, spoilerProtected = true), namesOnly = true, resultHidden = true, team = "76ers"
    )

    @Test
    fun `a hidden final shows its score once a live score card of that game showed`() {
        val alerts = alerts()
        val now = System.currentTimeMillis()
        val id = "opt1-shown-$now"
        alerts.onProtection(SpoilerProtection(myTeamsResults = true, followed = sixers))
        alerts.enqueue(listOf(SportsLiveAlert.ScoreChange(card, live(10, 11).copy(eventId = id), ScoreSide.AWAY)), now)
        alerts.close(alerts.current.value!!, AlertAnswer.TIMEOUT)
        val hidden = hiddenFinal(id)
        alerts.enqueue(listOf(hidden), now + 1_000)
        val shown = alerts.current.value!!.alert as SportsLiveAlert.FullTime
        assertFalse(shown.resultHidden)
        assertFalse(shown.namesOnly)
        assertEquals(hidden.snapshot, shown.snapshot)
        // The Full time card then carries the score; it is information only (no OK).
        assertTrue(SportsLiveAlertContent.of(shown) is AlertContent.FullTime)
        assertFalse(SportsLiveAlertCards.actionable(shown))
    }

    @Test
    fun `a hidden final with no live score card shown stays result hidden`() {
        val alerts = alerts()
        val now = System.currentTimeMillis()
        alerts.onProtection(SpoilerProtection(myTeamsResults = true, followed = sixers))
        // Another game's score card showed, not this one's.
        alerts.enqueue(listOf(SportsLiveAlert.ScoreChange(card, live(10, 11).copy(eventId = "opt1-other-$now"), ScoreSide.AWAY)), now)
        alerts.close(alerts.current.value!!, AlertAnswer.TIMEOUT)
        val hidden = hiddenFinal("opt1-none-$now")
        alerts.enqueue(listOf(hidden), now + 1_000)
        assertEquals(hidden, alerts.current.value!!.alert)
    }

    @Test
    fun `a game saved for later after its score card showed keeps its result hidden`() {
        val alerts = alerts()
        val now = System.currentTimeMillis()
        val id = "opt1-saved-$now"
        alerts.onProtection(SpoilerProtection(myTeamsResults = true, followed = sixers))
        alerts.enqueue(listOf(SportsLiveAlert.ScoreChange(card, live(10, 11).copy(eventId = id), ScoreSide.AWAY)), now)
        alerts.close(alerts.current.value!!, AlertAnswer.TIMEOUT)
        // Watch later: its live score is hidden now too, so its result stays hidden.
        alerts.onProtection(
            SpoilerProtection(games = listOf(ProtectedGame(id, "Philadelphia 76ers", "Indiana Pacers", START, savedAtMs = now)), myTeamsResults = true, followed = sixers)
        )
        val hidden = hiddenFinal(id)
        alerts.enqueue(listOf(hidden), now + 1_000)
        assertEquals(hidden, alerts.current.value!!.alert)
    }

    @Test
    fun `a revealed final keeps to the Scores setting, names only under Hide until focused`() {
        val alerts = alerts()
        val now = System.currentTimeMillis()
        val id = "opt1-hide-$now"
        alerts.onProtection(SpoilerProtection(myTeamsResults = true, followed = sixers))
        alerts.enqueue(listOf(SportsLiveAlert.ScoreChange(card, live(10, 11).copy(eventId = id), ScoreSide.AWAY)), now)
        alerts.close(alerts.current.value!!, AlertAnswer.TIMEOUT)
        alerts.onRules(ScoresMode.HIDE_UNTIL_FOCUSED, SportsLiveAlertSettings())
        alerts.enqueue(listOf(hiddenFinal(id)), now + 1_000)
        val shown = alerts.current.value!!.alert as SportsLiveAlert.FullTime
        assertFalse(shown.resultHidden)
        assertTrue(shown.namesOnly)
    }

    @Test
    fun `a full time not yet hidden stays with its score when its live score showed`() {
        val fullTime = SportsLiveAlert.FullTime(card, final(101, 99).copy(eventId = "opt1-pure"), namesOnly = false)
        val protection = SpoilerProtection(myTeamsResults = true, followed = sixers)
        assertTrue((SportsLiveAlerts.underProtection(fullTime, protection) as SportsLiveAlert.FullTime).resultHidden)
        assertEquals(fullTime, SportsLiveAlerts.underProtection(fullTime, protection) { it == "opt1-pure" })
        assertTrue(SportsLiveAlerts.showsLiveScore(SportsLiveAlert.ScoreChange(card, live(1, 0), ScoreSide.HOME)))
        assertFalse(SportsLiveAlerts.showsLiveScore(fullTime))
    }
}
