package com.nuvio.tv.core.sportslive.alerts

import android.view.KeyEvent
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.MINUTE
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.START
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.final
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.item
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.live
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #91: a kickoff over a movie is passive while the movie plays, and asks once when the viewer pauses or leaves it. */
class SportsLiveMovieKickoffTest {

    private val game = item(followed = true)
    private val playing = SportsLiveWatching(playingIds = setOf("movie"), inPlayer = true, sportsPlayback = false, streamStartedAtMs = START - 60 * MINUTE)
    private val paused = playing.copy(moviePaused = true)
    private val browsing = SportsLiveWatching()
    private val sportsStream = playing.copy(sportsPlayback = true)

    private fun kickoff(deferred: Boolean = false, noStreamYet: Boolean = false) =
        SportsLiveAlert.Kickoff(game, live(0, 0), deferred = deferred, noStreamYet = noStreamYet)

    private fun newAlerts() = SportsLiveAlerts(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))

    private fun SportsLiveAlertEngine.look(
        at: Long,
        snapshot: ScoreSnapshot?,
        watching: SportsLiveWatching,
        gameItem: com.nuvio.tv.domain.model.SportsLiveItem = game,
        mode: ScoresMode = ScoresMode.SHOW
    ): List<SportsLiveAlert> = update(at, listOf(AlertGame(gameItem, snapshot, reminded = true)), SportsLiveAlertSettings(), mode, watching)

    // The rule

    @Test
    fun `over a movie that plays the card is passive, in every other place it is not`() {
        assertTrue(SportsLiveAlertEngine.kickoffPassive(kickoff(), playing))
        assertFalse(SportsLiveAlertEngine.kickoffPassive(kickoff(), paused))
        assertFalse(SportsLiveAlertEngine.kickoffPassive(kickoff(), sportsStream))
        assertFalse(SportsLiveAlertEngine.kickoffPassive(kickoff(), browsing))
        assertFalse(SportsLiveAlertEngine.kickoffPassive(kickoff(deferred = true), playing))
        assertFalse(SportsLiveAlertEngine.kickoffPassive(kickoff(noStreamYet = true), playing))
    }

    @Test
    fun `a passive card takes no key, OK and Back both go to the movie`() {
        val alerts = newAlerts()
        alerts.setWatching(playing)
        alerts.enqueue(listOf(kickoff()), System.currentTimeMillis())
        val card = alerts.current.value!!
        assertTrue((card.alert as SportsLiveAlert.Kickoff).passive)
        assertFalse(SportsLiveAlertCards.actionable(card.alert))
        // Well past the OK grace, so only the card's passivity keeps the keys.
        val context = SportsLiveAlertKeys.context(card, shownAtMs = 0L, nowMs = 60_000L)
        assertFalse(context.cardOk)
        assertFalse(context.cardBack)
        assertEquals(AlertKeyAction.PASS, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_DPAD_CENTER, context))
        assertEquals(AlertKeyAction.PASS, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_BACK, context))
    }

    @Test
    fun `the passive card shows 8 seconds, the asking one 10`() {
        assertEquals(8_000L, SportsLiveAlertCards.durationMs(kickoff().copy(passive = true)))
        assertEquals(10_000L, SportsLiveAlertCards.durationMs(kickoff()))
    }

    @Test
    fun `a kickoff that shows while the movie is paused asks at once and is not passive`() {
        val alerts = newAlerts()
        alerts.setWatching(paused)
        alerts.enqueue(listOf(kickoff()), System.currentTimeMillis())
        val alert = alerts.current.value!!.alert as SportsLiveAlert.Kickoff
        assertFalse(alert.passive)
        assertTrue(SportsLiveAlertCards.actionable(alert))
    }

    @Test
    fun `Hide and Off scores keep the passive card as it is - a kickoff has no score to hide`() {
        val settings = SportsLiveAlertSettings()
        val passive = kickoff().copy(passive = true)
        for (mode in listOf(ScoresMode.SHOW, ScoresMode.HIDE_UNTIL_FOCUSED, ScoresMode.OFF)) {
            assertEquals("$mode", passive, SportsLiveAlertEngine.stillShowable(passive, mode, settings))
        }
    }

    // The card over a playing movie, then the viewer pauses while it is up

    @Test
    fun `pausing while the passive card is up turns it into the asking card, as a new card`() {
        val alerts = newAlerts()
        alerts.setWatching(playing)
        // The engine raised it (the prompt is shown), then the host showed it.
        alerts.engine.look(START + MINUTE, live(0, 0), playing)
        alerts.enqueue(listOf(kickoff()), System.currentTimeMillis())
        val passive = alerts.current.value!!
        alerts.setWatching(paused)
        val asking = alerts.current.value!!
        assertNotEquals(passive.id, asking.id)
        assertFalse((asking.alert as SportsLiveAlert.Kickoff).passive)
        assertTrue(SportsLiveAlertCards.actionable(asking.alert))
        // It is the game's one question: answered, never asked again.
        alerts.close(asking, AlertAnswer.TIMEOUT)
        assertEquals(emptyList<SportsLiveAlert>(), alerts.engine.look(START + 30 * MINUTE, live(10, 8), browsing))
        assertEquals(emptyList<SportsLiveAlert>(), alerts.engine.look(START + 31 * MINUTE, live(10, 8), paused))
    }

    // The deferred offer

    @Test
    fun `a passive card that ran out keeps the offer - none while the movie plays, one when it is paused`() {
        val alerts = newAlerts()
        alerts.setWatching(playing)
        alerts.enqueue(listOf(kickoff()), System.currentTimeMillis())
        val engine = alerts.engine
        // The engine raised and showed it, as the host does (it marks the prompt shown).
        engine.look(START + MINUTE, live(0, 0), playing)
        alerts.close(alerts.current.value!!, AlertAnswer.TIMEOUT)

        assertEquals(emptyList<SportsLiveAlert>(), engine.look(START + 25 * MINUTE, live(20, 18), playing))
        val offer = engine.look(START + 40 * MINUTE, live(31, 28), paused)
        assertEquals(1, offer.size)
        val card = offer.single() as SportsLiveAlert.Kickoff
        assertTrue(card.deferred)
        assertFalse(SportsLiveAlertEngine.kickoffPassive(card, paused))
        // Exactly one per game, however often the viewer pauses.
        assertEquals(emptyList<SportsLiveAlert>(), engine.look(START + 41 * MINUTE, live(33, 30), paused))
        assertEquals(emptyList<SportsLiveAlert>(), engine.look(START + 42 * MINUTE, live(33, 30), browsing))
    }

    @Test
    fun `leaving the movie while the game is live offers it once`() {
        val engine = SportsLiveAlertEngine()
        engine.look(START + MINUTE, live(0, 0), playing)
        engine.deferOffer(game)
        assertEquals(emptyList<SportsLiveAlert>(), engine.look(START + 10 * MINUTE, live(10, 9), playing))
        val offer = engine.look(START + 11 * MINUTE, live(12, 9), browsing)
        assertEquals(1, offer.size)
        assertTrue((offer.single() as SportsLiveAlert.Kickoff).deferred)
        assertEquals(emptyList<SportsLiveAlert>(), engine.look(START + 12 * MINUTE, live(12, 9), browsing))
    }

    @Test
    fun `no offer once the game has ended, however the viewer is free`() {
        val engine = SportsLiveAlertEngine()
        engine.look(START + MINUTE, live(0, 0), playing)
        engine.deferOffer(game)
        assertEquals(emptyList<SportsLiveAlert>(), engine.look(START + 150 * MINUTE, final(110, 104), paused))
        // And it stays answered, even if a late look says live again.
        assertEquals(emptyList<SportsLiveAlert>(), engine.look(START + 151 * MINUTE, live(110, 104), browsing))
    }

    @Test
    fun `the offer waits while a Sports and Live stream is not the viewer's choice - dual screen holds it`() {
        val engine = SportsLiveAlertEngine()
        engine.look(START + MINUTE, live(0, 0), playing)
        engine.deferOffer(game)
        assertEquals(emptyList<SportsLiveAlert>(), engine.look(START + 5 * MINUTE, live(4, 2), browsing.copy(dual = true)))
        assertEquals(1, engine.look(START + 6 * MINUTE, live(4, 2), browsing).size)
    }

    @Test
    fun `under Off scores an untimed game is live as the listing says`() {
        val live = item(followed = true, isLive = true, startMs = null)
        val over = item(followed = true, isLive = false, startMs = null)
        val engine = SportsLiveAlertEngine()
        engine.deferOffer(live)
        assertEquals(1, engine.look(START + 30 * MINUTE, null, paused, live, ScoresMode.OFF).size)

        val other = SportsLiveAlertEngine()
        other.deferOffer(over)
        assertEquals(emptyList<SportsLiveAlert>(), other.look(START + 30 * MINUTE, null, paused, over, ScoresMode.OFF))
    }

    @Test
    fun `with no ESPN game a game listed Upcoming at load is live from its start - the offer comes on pause (review R3)`() {
        // Scores Off, or a league ESPN does not cover: the listing was loaded before kick-off and is not reloaded.
        val upcoming = item(followed = true, isLive = false).copy(liveByClock = true)
        for (mode in listOf(ScoresMode.OFF, ScoresMode.SHOW)) {
            val engine = SportsLiveAlertEngine()
            val first = engine.look(START + MINUTE, null, playing, upcoming, mode)
            assertEquals("$mode", 1, first.size)
            engine.deferOffer(upcoming)
            val offer = engine.look(START + 30 * MINUTE, null, paused, upcoming, mode)
            assertEquals("$mode", 1, offer.size)
            assertTrue((offer.single() as SportsLiveAlert.Kickoff).deferred)
        }
    }

    @Test
    fun `with no ESPN game the offer ends with the game's live window, however it was listed (review R3)`() {
        for (listed in listOf(item(followed = true, isLive = true), item(followed = true, isLive = false).copy(liveByClock = true))) {
            val engine = SportsLiveAlertEngine()
            engine.deferOffer(listed)
            assertEquals(emptyList<SportsLiveAlert>(), engine.look(START + 5 * 60 * MINUTE, null, paused, listed, ScoresMode.OFF))
        }
    }

    @Test
    fun `with no ESPN game nothing is offered before the listed start`() {
        val engine = SportsLiveAlertEngine()
        val game = item(followed = true, isLive = false).copy(liveByClock = true)
        engine.deferOffer(game)
        assertEquals(emptyList<SportsLiveAlert>(), engine.look(START - 10 * MINUTE, null, paused, game, ScoresMode.OFF))
        assertEquals(1, engine.look(START + MINUTE, null, paused, game, ScoresMode.OFF).size)
    }

    @Test
    fun `a game answered before is never deferred`() {
        val engine = SportsLiveAlertEngine()
        engine.look(START + MINUTE, live(0, 0), browsing)
        engine.answered(game)
        engine.deferOffer(game)
        assertEquals(emptyList<SportsLiveAlert>(), engine.look(START + 10 * MINUTE, live(4, 2), paused))
    }

    // R1 (review): the movie plays again while the asking card is up

    @Test
    fun `resuming the movie closes the asking card - it was the one offer, so it is answered`() {
        val alerts = newAlerts()
        alerts.setWatching(playing)
        alerts.engine.look(START + MINUTE, live(0, 0), playing)
        alerts.enqueue(listOf(kickoff()), System.currentTimeMillis())
        alerts.setWatching(paused)
        val asking = alerts.current.value!!.alert as SportsLiveAlert.Kickoff
        assertFalse(asking.passive)
        assertTrue(asking.deferred)

        alerts.setWatching(playing)
        assertNull(alerts.current.value)
        assertEquals(emptyList<SportsLiveAlert>(), alerts.engine.look(START + 30 * MINUTE, live(10, 8), paused))
    }

    @Test
    fun `a first kickoff asked over a paused movie that plays again closes and keeps its one offer`() {
        val alerts = newAlerts()
        alerts.setWatching(paused)
        alerts.engine.look(START + MINUTE, live(0, 0), paused)
        alerts.enqueue(listOf(kickoff()), System.currentTimeMillis())
        assertFalse((alerts.current.value!!.alert as SportsLiveAlert.Kickoff).passive)

        alerts.setWatching(playing)
        assertNull(alerts.current.value)
        assertEquals(emptyList<SportsLiveAlert>(), alerts.engine.look(START + 20 * MINUTE, live(10, 8), playing))
        val offer = alerts.engine.look(START + 30 * MINUTE, live(10, 8), paused)
        assertEquals(1, offer.size)
        assertTrue((offer.single() as SportsLiveAlert.Kickoff).deferred)
    }

    @Test
    fun `a deferred offer that comes up while the movie plays again waits for the next pause`() {
        val alerts = newAlerts()
        alerts.setWatching(playing)
        alerts.enqueue(listOf(kickoff(deferred = true)), System.currentTimeMillis())
        assertNull(alerts.current.value)
        assertEquals(emptyList<SportsLiveAlert>(), alerts.engine.look(START + 20 * MINUTE, live(10, 8), playing))
        assertEquals(1, alerts.engine.look(START + 30 * MINUTE, live(10, 8), paused).size)
    }

    // R6 (review): the viewer leaves the movie into the game itself, or into dual screen

    @Test
    fun `leaving the movie into the game itself answers the passive card - nothing asks over the game (review R6)`() {
        val alerts = newAlerts()
        alerts.setWatching(playing)
        alerts.engine.look(START + MINUTE, live(0, 0), playing)
        alerts.enqueue(listOf(kickoff()), System.currentTimeMillis())
        assertTrue((alerts.current.value!!.alert as SportsLiveAlert.Kickoff).passive)

        alerts.setWatching(SportsLiveWatching(playingIds = setOf(game.id), inPlayer = true, sportsPlayback = true, streamStartedAtMs = START))
        assertNull(alerts.current.value)
        assertEquals(emptyList<SportsLiveAlert>(), alerts.engine.look(START + 30 * MINUTE, live(10, 8), browsing))
    }

    @Test
    fun `opening dual screen from the movie keeps the offer for when it closes - dual holds kickoffs (review R6)`() {
        val alerts = newAlerts()
        alerts.setWatching(playing)
        alerts.engine.look(START + MINUTE, live(0, 0), playing)
        alerts.enqueue(listOf(kickoff()), System.currentTimeMillis())

        val dual = browsing.copy(dual = true)
        alerts.setWatching(dual)
        assertNull(alerts.current.value)
        assertEquals(emptyList<SportsLiveAlert>(), alerts.engine.look(START + 10 * MINUTE, live(4, 2), dual))
        assertEquals(1, alerts.engine.look(START + 11 * MINUTE, live(4, 2), browsing).size)
    }

    @Test
    fun `a deferred offer that never showed (a panel over the paused movie) waits for the next pause - never a second card over the movie (review R8)`() {
        val alerts = newAlerts()
        alerts.setWatching(paused)
        alerts.enqueue(listOf(kickoff(deferred = true)), System.currentTimeMillis())
        val offer = alerts.current.value!!
        // The host closes a card that went stale under an overlay with LATER.
        alerts.close(offer, AlertAnswer.LATER)
        assertEquals(emptyList<SportsLiveAlert>(), alerts.engine.look(START + 30 * MINUTE, live(10, 8), playing))
        val again = alerts.engine.look(START + 31 * MINUTE, live(10, 8), paused)
        assertEquals(1, again.size)
        assertTrue((again.single() as SportsLiveAlert.Kickoff).deferred)
    }

    @Test
    fun `a seek while paused is not a resume - the player's pause holds until it plays`() {
        // Paused, then buffering for a seek (neither paused nor playing): still paused.
        assertTrue(sportsLivePausedNow(paused = false, playing = false, wasPaused = true))
        assertFalse(sportsLivePausedNow(paused = false, playing = true, wasPaused = true))
        // Buffering while it plays is never a pause.
        assertFalse(sportsLivePausedNow(paused = false, playing = false, wasPaused = false))
    }

    @Test
    fun `the player's pause is a viewer's pause only while a movie plays - never over a game stream`() {
        assertTrue(SportsLiveAlertEngine.viewerFree(paused))
        assertTrue(SportsLiveAlertEngine.viewerFree(browsing))
        assertFalse(SportsLiveAlertEngine.viewerFree(playing))
        // A game stream is not a movie: the card there keeps its OK (switching games is what people do).
        assertTrue(SportsLiveAlertEngine.viewerFree(sportsStream))
    }
}
