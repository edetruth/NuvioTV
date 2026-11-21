package com.nuvio.tv.core.sportslive.alerts

import android.view.KeyEvent
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.MINUTE
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.START
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.final
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.item
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.live
import com.nuvio.tv.core.sportslive.scores.ProtectedGame
import com.nuvio.tv.core.sportslive.scores.ResultSeen
import com.nuvio.tv.core.sportslive.scores.ScoreSide
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.SpoilerProtection
import com.nuvio.tv.domain.model.FollowedTeam
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #136 and the live.24 redesign: one alert per card, its time, where it sits, who takes a key, the countdown; the peek kept. */
class SportsLiveAlertCardsTest {

    private val pacers = item("Pacers @ 76ers", followed = true)
    private val lakers = item("Lakers @ Celtics", followed = true)
    private val knicks = item("Knicks @ Heat", followed = true)

    private fun score(game: com.nuvio.tv.domain.model.SportsLiveItem = pacers, lead: Boolean = false) =
        SportsLiveAlert.ScoreChange(game, live(10, 11), ScoreSide.AWAY, leadChange = lead)

    // Per-alert time on screen (live.24)

    @Test
    fun `an actionable card stays ten seconds, a lead change twelve, Full time six, never shorter than accessibility asks`() {
        assertEquals(10_000L, SportsLiveAlertCards.durationMs(score()))
        assertEquals(12_000L, SportsLiveAlertCards.durationMs(score(lead = true)))
        assertEquals(6_000L, SportsLiveAlertCards.durationMs(SportsLiveAlert.FullTime(pacers, final(101, 99), namesOnly = false)))
        assertEquals(10_000L, SportsLiveAlertCards.durationMs(SportsLiveAlert.Kickoff(lakers, null)))
        // "Time to take action" set to 1 minute: the card waits that long; a recommendation below ours never shortens it.
        assertEquals(60_000L, SportsLiveAlertCards.durationMs(score()) { _, _ -> 60_000L })
        assertEquals(10_000L, SportsLiveAlertCards.durationMs(score()) { _, _ -> 2_000L })
        // Only actionable cards ask with controls on them.
        var asked: Boolean? = null
        SportsLiveAlertCards.durationMs(SportsLiveAlert.FullTime(pacers, final(101, 99), namesOnly = false)) { ms, actionable -> asked = actionable; ms }
        assertEquals(false, asked)
    }

    @Test
    fun `the engine marks a lead change, not a goal that only extends a lead or ties it`() {
        fun change(before: ScoreSnapshot, after: ScoreSnapshot): SportsLiveAlert.ScoreChange {
            val engine = SportsLiveAlertEngine()
            val watching = SportsLiveWatching(playingIds = setOf("movie"), inPlayer = true)
            val game = { s: ScoreSnapshot -> listOf(AlertGame(pacers, s, false, setOf(ScoreSide.AWAY))) }
            engine.update(START + 10 * MINUTE, game(before), SportsLiveAlertSettings(), ScoresMode.SHOW, watching)
            return engine.update(START + 11 * MINUTE, game(after), SportsLiveAlertSettings(), ScoresMode.SHOW, watching).single() as SportsLiveAlert.ScoreChange
        }
        assertTrue(change(live(10, 8), live(10, 11)).leadChange)
        assertFalse(change(live(10, 8), live(12, 8)).leadChange)
        // Level, then ahead (or ahead, then level) is no lead change.
        assertFalse(change(live(10, 10), live(10, 12)).leadChange)
        assertFalse(change(live(10, 8), live(10, 10)).leadChange)
        assertEquals(SportsLiveAlertCards.LEAD_CHANGE_MS, SportsLiveAlertCards.durationMs(change(live(10, 8), live(10, 11))))
    }

    // One alert per card, the highest priority (live.24)

    @Test
    fun `of the alerts waiting, my team's kickoff wins, then a score, then full time, then a close finish`() {
        val crunch = SportsLiveAlert.Crunch(knicks, live(80, 79), mockk(relaxed = true), namesOnly = false)
        val fullTime = SportsLiveAlert.FullTime(pacers, final(101, 99), namesOnly = false)
        val kickoff = SportsLiveAlert.Kickoff(lakers, null)
        val waiting = listOf(crunch to START, fullTime to START + 1, score(pacers) to START + 2, kickoff to START + 3)
        assertEquals(3, SportsLiveAlertCards.next(waiting))
        assertEquals(2, SportsLiveAlertCards.next(waiting.dropLast(1)))
        assertEquals(1, SportsLiveAlertCards.next(waiting.take(2)))
        // A tie: the earliest raised.
        assertEquals(0, SportsLiveAlertCards.next(listOf(score(pacers) to START, score(lakers) to START + 1)))
        assertEquals(null, SportsLiveAlertCards.next(emptyList()))
    }

    @Test
    fun `alerts raised together show one card at a time, the highest priority first, the others wait their turn`() {
        val alerts = SportsLiveAlerts(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        val now = System.currentTimeMillis()
        val kickoff = SportsLiveAlert.Kickoff(item("Bulls @ Nets", followed = true), null)
        alerts.enqueue(listOf(score(pacers), score(lakers, lead = true), kickoff), now)
        val first = alerts.current.value!!
        assertEquals(kickoff, first.alert)
        // live.24 RC review M1: the scores raised with it wait, then show one at a time, the earlier one first.
        alerts.close(first, AlertAnswer.DISMISS)
        val second = alerts.current.value!!
        assertEquals(pacers, second.alert.item)
        alerts.close(second, AlertAnswer.TIMEOUT)
        val third = alerts.current.value!!
        assertEquals(lakers, third.alert.item)
        alerts.close(third, AlertAnswer.TIMEOUT)
        assertEquals(null, alerts.current.value)
    }

    @Test
    fun `while a card is up the next alerts wait, a kickoff first, and nothing cycles`() {
        val alerts = SportsLiveAlerts(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        val now = System.currentTimeMillis()
        alerts.enqueue(listOf(score(pacers)), now)
        val card = alerts.current.value!!
        alerts.enqueue(listOf(score(lakers)), now + 1_000)
        val kickoff = SportsLiveAlert.Kickoff(item("Bulls @ Nets", followed = true), null)
        alerts.enqueue(listOf(kickoff), now + 2_000)
        // The card up is untouched: one alert, the same.
        assertEquals(card, alerts.current.value)
        // A close for a card no longer up changes nothing.
        alerts.close(card.copy(id = card.id + 99), AlertAnswer.TIMEOUT)
        assertEquals(card, alerts.current.value)
        alerts.close(card, AlertAnswer.TIMEOUT)
        assertEquals(kickoff, alerts.current.value!!.alert)
        alerts.close(alerts.current.value!!, AlertAnswer.DISMISS)
        // The second score waited its turn (live.24 RC review M1), then nothing more.
        val waited = alerts.current.value!!
        assertEquals(lakers, waited.alert.item)
        alerts.close(waited, AlertAnswer.TIMEOUT)
        assertEquals(null, alerts.current.value)
    }

    @Test
    fun `a full time raised while a score card is up shows after it, the result hidden card too (live24 RC review M1)`() {
        val alerts = SportsLiveAlerts(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        val now = System.currentTimeMillis()
        alerts.enqueue(listOf(score(lakers)), now - 90_000)
        val card = alerts.current.value!!
        val hidden = SportsLiveAlert.FullTime(
            pacers, final(101, 99).copy(eventId = "m1-hidden-$now"), namesOnly = true, resultHidden = true, team = "Pacers"
        )
        // Raised a minute ago, while the score card was up: it used to be dropped for good.
        alerts.enqueue(listOf(hidden), now - 60_000)
        assertEquals(card, alerts.current.value)
        alerts.close(card, AlertAnswer.TIMEOUT)
        val shown = alerts.current.value!!
        assertEquals(hidden, shown.alert)
        // It gets its own time to show from now, not a wait that already ran out behind the other card.
        assertTrue(shown.raisedAtMs >= now)
        alerts.close(shown, AlertAnswer.TIMEOUT)
        assertEquals(null, alerts.current.value)
    }

    // live.24 RC2 review R2: spoiler protection is read again when a waiting card is about to show.

    private fun otherScore() = SportsLiveAlert.ScoreChange(lakers, live(10, 11).copy(eventId = "r2-other"), ScoreSide.AWAY)

    private val sixers = listOf(FollowedTeam("Philadelphia 76ers", "Basketball"))

    @Test
    fun `a waiting score is not shown once its game is saved for Watch later`() {
        val alerts = SportsLiveAlerts(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        val now = System.currentTimeMillis()
        alerts.enqueue(listOf(otherScore()), now)
        val card = alerts.current.value!!
        alerts.enqueue(listOf(score(pacers)), now + 1_000)
        // Saved for later while it waited behind the card up.
        val game = live(10, 11)
        alerts.onProtection(SpoilerProtection(games = listOf(ProtectedGame(game.eventId, "Philadelphia 76ers", "Indiana Pacers", START, savedAtMs = now))))
        alerts.close(card, AlertAnswer.TIMEOUT)
        assertEquals(null, alerts.current.value)
    }

    @Test
    fun `a waiting score is not shown once my teams' scores are hidden`() {
        val alerts = SportsLiveAlerts(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        val now = System.currentTimeMillis()
        alerts.enqueue(listOf(otherScore()), now)
        val card = alerts.current.value!!
        alerts.enqueue(listOf(score(pacers)), now + 1_000)
        alerts.onProtection(SpoilerProtection(myTeams = true, followed = sixers))
        alerts.close(card, AlertAnswer.TIMEOUT)
        assertEquals(null, alerts.current.value)
    }

    @Test
    fun `a waiting full time shows as result hidden once my teams' results are hidden`() {
        val alerts = SportsLiveAlerts(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        val now = System.currentTimeMillis()
        alerts.enqueue(listOf(otherScore()), now)
        val card = alerts.current.value!!
        val fullTime = SportsLiveAlert.FullTime(pacers, final(101, 99).copy(eventId = "r2-final-$now"), namesOnly = false)
        alerts.enqueue(listOf(fullTime), now + 1_000)
        alerts.onProtection(SpoilerProtection(myTeamsResults = true, followed = sixers))
        alerts.close(card, AlertAnswer.TIMEOUT)
        val shown = alerts.current.value!!.alert as SportsLiveAlert.FullTime
        assertTrue(shown.resultHidden)
        assertTrue(shown.namesOnly)
        assertEquals(fullTime.snapshot, shown.snapshot)
    }

    @Test
    fun `a full time's result counts as seen only when its card shows the score`() {
        val alerts = SportsLiveAlerts(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        val now = System.currentTimeMillis()
        alerts.enqueue(listOf(otherScore()), now)
        val card = alerts.current.value!!
        val hiddenLater = SportsLiveAlert.FullTime(pacers, final(101, 99).copy(eventId = "seen-hidden-$now"), namesOnly = false)
        alerts.enqueue(listOf(hiddenLater), now + 1_000)
        // Waiting behind the card up: not seen yet.
        assertFalse(hiddenLater.snapshot.eventId in ResultSeen.shared.known.value)
        alerts.onProtection(SpoilerProtection(myTeamsResults = true, followed = sixers))
        alerts.close(card, AlertAnswer.TIMEOUT)
        // It showed as "result hidden": the result is still not known, so its replay stays in Catch up.
        assertTrue((alerts.current.value!!.alert as SportsLiveAlert.FullTime).resultHidden)
        assertFalse(hiddenLater.snapshot.eventId in ResultSeen.shared.known.value)
        alerts.close(alerts.current.value!!, AlertAnswer.TIMEOUT)

        val shownNow = SportsLiveAlerts(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        val fullTime = SportsLiveAlert.FullTime(lakers, final(101, 99).copy(eventId = "seen-shown-$now"), namesOnly = false)
        shownNow.enqueue(listOf(fullTime), now)
        assertEquals(fullTime, shownNow.current.value!!.alert)
        assertTrue(fullTime.snapshot.eventId in ResultSeen.shared.known.value)
    }

    @Test
    fun `a profile switch empties the waiting alerts`() {
        val alerts = SportsLiveAlerts(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        val now = System.currentTimeMillis()
        alerts.onProfile(1)
        alerts.enqueue(listOf(otherScore()), now)
        val card = alerts.current.value!!
        val fullTime = SportsLiveAlert.FullTime(pacers, final(101, 99).copy(eventId = "r2-switch-$now"), namesOnly = false)
        alerts.enqueue(listOf(score(pacers), fullTime), now + 1_000)
        // The same profile read again keeps them.
        alerts.onProfile(1)
        alerts.onProfile(2)
        // The card up was the profile before's too: it goes at once (live.24 RC3 review L1).
        assertEquals(null, alerts.current.value)
        alerts.close(card, AlertAnswer.TIMEOUT)
        assertEquals(null, alerts.current.value)
    }

    @Test
    fun `waiting alerts keep one per game and kind, the newest score, and drop the oldest scores past five`() {
        val waiting = mutableListOf<Pair<SportsLiveAlert, Long>>()
        val fullTime = SportsLiveAlert.FullTime(knicks, final(101, 99), namesOnly = false)
        val kickoff = SportsLiveAlert.Kickoff(item("Bulls @ Nets", followed = true), null)
        SportsLiveAlertCards.addWaiting(waiting, fullTime, START)
        SportsLiveAlertCards.addWaiting(waiting, kickoff, START + 1)
        SportsLiveAlertCards.addWaiting(waiting, score(pacers), START + 2)
        val newer = SportsLiveAlert.ScoreChange(pacers, live(12, 11), ScoreSide.HOME)
        SportsLiveAlertCards.addWaiting(waiting, newer, START + 3)
        // The newer score of the game supersedes the older one.
        assertEquals(3, waiting.size)
        assertEquals(newer, waiting.single { it.first is SportsLiveAlert.ScoreChange }.first)
        // More games score than may wait: the oldest scores go, never the kickoff or the full time.
        val games = (1..5).map { item("Team$it A @ Team$it B", followed = true) }
        games.forEachIndexed { i, game -> SportsLiveAlertCards.addWaiting(waiting, score(game), START + 10 + i) }
        assertEquals(SportsLiveAlertCards.MAX_WAITING, waiting.size)
        assertTrue(waiting.any { it.first == fullTime })
        assertTrue(waiting.any { it.first == kickoff })
        assertFalse(waiting.any { it.first == newer })
        assertEquals(games.takeLast(3), waiting.mapNotNull { (it.first as? SportsLiveAlert.ScoreChange)?.item })
    }

    @Test
    fun `a waiting score goes stale after two minutes, a kickoff or full time never`() {
        val fullTime = SportsLiveAlert.FullTime(knicks, final(101, 99), namesOnly = false)
        val kickoff = SportsLiveAlert.Kickoff(lakers, null)
        val stale = START + SportsLiveAlertCards.WAITING_SCORE_STALE_MS + 1
        assertFalse(SportsLiveAlertCards.expired(score(), START, START + SportsLiveAlertCards.WAITING_SCORE_STALE_MS))
        assertTrue(SportsLiveAlertCards.expired(score(), START, stale))
        assertFalse(SportsLiveAlertCards.expired(fullTime, START, START + 20 * MINUTE))
        assertTrue(SportsLiveAlertCards.expired(fullTime, START, START + 31 * MINUTE))
        assertFalse(SportsLiveAlertCards.expired(kickoff, START, START + 20 * MINUTE))
        assertTrue(SportsLiveAlertCards.expired(kickoff, START, START + 31 * MINUTE))
        // A new alert clears the expired ones out of the queue.
        val waiting = mutableListOf<Pair<SportsLiveAlert, Long>>(score(pacers) to START, fullTime to START)
        SportsLiveAlertCards.addWaiting(waiting, kickoff, stale)
        assertEquals(listOf(fullTime, kickoff), waiting.map { it.first })
    }

    @Test
    fun `over a movie only a kickoff shows, and no close finish on a Sports and Live screen`() {
        val movie = SportsLiveWatching(inPlayer = true, sportsPlayback = false)
        val crunch = SportsLiveAlert.Crunch(knicks, live(80, 79), mockk(relaxed = true), namesOnly = false)
        assertTrue(SportsLiveAlerts.mayShowOver(SportsLiveAlert.Kickoff(lakers, null), movie))
        assertFalse(SportsLiveAlerts.mayShowOver(score(), movie))
        assertFalse(SportsLiveAlerts.mayShowOver(SportsLiveAlert.FullTime(pacers, final(101, 99), namesOnly = false), movie))
        assertFalse(SportsLiveAlerts.mayShowOver(crunch, movie))
        assertFalse(SportsLiveAlerts.mayShowOver(crunch, SportsLiveWatching(sportsLiveScreen = true)))
        assertTrue(SportsLiveAlerts.mayShowOver(score(), SportsLiveWatching(sportsLiveScreen = true)))
        assertTrue(SportsLiveAlerts.mayShowOver(crunch, SportsLiveWatching(inPlayer = true, sportsPlayback = true)))
    }


    // Placement

    @Test
    fun `in dual screen the card sits on the pane it is not about`() {
        val panes = DualPanes(left = pacers, right = lakers, leftHighlighted = true)
        assertEquals(AlertSpot.RIGHT_PANE, SportsLiveAlertPlacement.spot(score(pacers), panes))
        assertEquals(AlertSpot.LEFT_PANE, SportsLiveAlertPlacement.spot(score(lakers), panes))
    }

    @Test
    fun `a game neither pane shows, or no dual screen, uses the screen's top right`() {
        assertEquals(AlertSpot.SCREEN, SportsLiveAlertPlacement.spot(score(knicks), DualPanes(pacers, lakers, true)))
        assertEquals(AlertSpot.SCREEN, SportsLiveAlertPlacement.spot(score(pacers), null))
    }

    @Test
    fun `a pane showing the game from another addon still counts as that game`() {
        val otherAddon = item("Pacers @ 76ers", id = "elsewhere", addonId = "b")
        assertEquals(AlertSpot.LEFT_PANE, SportsLiveAlertPlacement.spot(score(pacers), DualPanes(lakers, otherAddon, false)))
        val merged = item("Knicks v Heat", id = "k2", alternates = listOf(knicks))
        assertTrue(SportsLiveAlertPlacement.sameGame(knicks, merged))
    }

    // Who takes a key (live.24): if a card shows "OK", OK is the card's

    private val scoreCard = AlertCard(1, score(), raisedAtMs = START)

    @Test
    fun `a plain OK acts on a card that shows OK, once it has been up 0_8 s`() {
        val early = SportsLiveAlertKeys.context(scoreCard, shownAtMs = START, nowMs = START + 799)
        assertEquals(AlertKeyAction.PASS, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_DPAD_CENTER, early))
        // Back closes it from the start.
        assertEquals(AlertKeyAction.DISMISS, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_BACK, early))
        val ready = SportsLiveAlertKeys.context(scoreCard, shownAtMs = START, nowMs = START + 800)
        assertEquals(AlertKeyAction.OK, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_DPAD_CENTER, ready))
        assertEquals(AlertKeyAction.OK, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_ENTER, ready))
        // ▶ is never an alert's, nor the arrows or the zap keys.
        for (code in listOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_CHANNEL_DOWN
        )) {
            assertEquals("key $code", AlertKeyAction.PASS, SportsLiveAlertKeys.route(code, ready))
        }
        // A kickoff and the no-stream note take OK too.
        val kickoff = AlertCard(2, SportsLiveAlert.Kickoff(lakers, null), START)
        assertEquals(AlertKeyAction.OK, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_DPAD_CENTER, SportsLiveAlertKeys.context(kickoff, START, START + 1_000)))
        val note = AlertCard(3, SportsLiveAlert.Kickoff(lakers, null, noStreamYet = true), START)
        assertEquals(AlertKeyAction.OK, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_DPAD_CENTER, SportsLiveAlertKeys.context(note, START, START + 1_000)))
    }

    @Test
    fun `Full time is information only, every key stays the screen's`() {
        val fullTime = AlertCard(1, SportsLiveAlert.FullTime(pacers, final(101, 99), namesOnly = false), START)
        assertFalse(SportsLiveAlertCards.actionable(fullTime.alert))
        val context = SportsLiveAlertKeys.context(fullTime, START, START + 5_000)
        for (code in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
            assertEquals("key $code", AlertKeyAction.PASS, SportsLiveAlertKeys.route(code, context))
        }
    }

    @Test
    fun `a card waiting under the guide or a menu takes no key, and its grace starts again when it shows`() {
        // Raised while the guide is open: it waits.
        var clock = AlertCardClock().step(START, busy = true)
        assertEquals(null, clock.shownAtMs)
        val waiting = SportsLiveAlertKeys.context(scoreCard, clock.shownAtMs, START + 3_000)
        assertEquals(AlertKeyAction.PASS, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_DPAD_CENTER, waiting))
        assertEquals(AlertKeyAction.PASS, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_BACK, waiting))
        // The guide closes at +5 s: it shows, and OK is the screen's for 0.8 s more.
        clock = clock.step(START + 5_000, busy = false)
        assertEquals(START + 5_000, clock.shownAtMs)
        assertEquals(AlertKeyAction.PASS, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_DPAD_CENTER, SportsLiveAlertKeys.context(scoreCard, clock.shownAtMs, START + 5_500)))
        assertEquals(AlertKeyAction.OK, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_DPAD_CENTER, SportsLiveAlertKeys.context(scoreCard, clock.shownAtMs, START + 5_800)))
        // A menu opens over it at +8 s: it hides again, its 3 s shown are kept; back at +9 s, a new grace.
        clock = clock.step(START + 8_000, busy = true)
        assertEquals(3_000L, clock.totalShownMs(START + 8_500))
        clock = clock.step(START + 9_000, busy = false)
        assertEquals(AlertKeyAction.PASS, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_DPAD_CENTER, SportsLiveAlertKeys.context(scoreCard, clock.shownAtMs, START + 9_100)))
    }

    @Test
    fun `a card's time counts only while it shows, and one that never shows goes stale after 30 s`() {
        var clock = AlertCardClock().step(START, busy = false)
        assertEquals(AlertCardClock.Fate.UP, clock.fate(scoreCard, START + 9_999, 10_000L))
        clock = clock.step(START + 4_000, busy = true)
        // 26 s under the guide: still 6 s to go once it shows again.
        clock = clock.step(START + 30_000, busy = false)
        assertEquals(AlertCardClock.Fate.UP, clock.fate(scoreCard, START + 35_999, 10_000L))
        assertEquals(AlertCardClock.Fate.TIMED_OUT, clock.fate(scoreCard, START + 36_000, 10_000L))
        // Never shown: stale at 30 s from being raised.
        val never = AlertCardClock().step(START + 1_000, busy = true)
        assertEquals(AlertCardClock.Fate.UP, never.fate(scoreCard, START + 29_999, 10_000L))
        assertEquals(AlertCardClock.Fate.STALE, never.fate(scoreCard, START + 30_000, 10_000L))
    }

    @Test
    fun `a held OK on a card goes back to the screens, a short one acts on its release`() {
        val press = AlertOkPress()
        assertEquals(AlertOkPress.Step.TAKE, press.onOk(KeyEvent.ACTION_DOWN, 0, downTime = 100L, cardTakes = true))
        assertEquals(AlertOkPress.Step.ACT, press.onOk(KeyEvent.ACTION_UP, 0, downTime = 100L, cardTakes = false))
        // Held until it repeats: handed back (the player menu, the Hold OK sheet); its release is not the card's.
        assertEquals(AlertOkPress.Step.TAKE, press.onOk(KeyEvent.ACTION_DOWN, 0, downTime = 200L, cardTakes = true))
        assertEquals(AlertOkPress.Step.HAND_BACK, press.onOk(KeyEvent.ACTION_DOWN, 1, downTime = 200L, cardTakes = false))
        assertEquals(AlertOkPress.Step.NONE, press.onOk(KeyEvent.ACTION_DOWN, 2, downTime = 200L, cardTakes = false))
        assertEquals(AlertOkPress.Step.NONE, press.onOk(KeyEvent.ACTION_UP, 0, downTime = 200L, cardTakes = false))
        // An OK the card does not take is never followed.
        assertEquals(AlertOkPress.Step.NONE, press.onOk(KeyEvent.ACTION_DOWN, 0, downTime = 300L, cardTakes = false))
        assertEquals(AlertOkPress.Step.NONE, press.onOk(KeyEvent.ACTION_UP, 0, downTime = 300L, cardTakes = false))
    }

    // The countdown card

    @Test
    fun `OK starts a 3 s countdown, Back cancels it, OK on Watch now switches at once`() {
        val countdown = AlertCountdown.start(scoreCard.id, START)
        assertEquals(START + 3_000, countdown.endsAtMs)
        assertEquals(3, countdown.secondsLeft(START))
        assertEquals(1, countdown.secondsLeft(START + 2_500))
        assertFalse(countdown.due(START + 2_999))
        assertTrue(countdown.due(START + 3_000))
        val expanded = SportsLiveAlertKeys.context(scoreCard, shownAtMs = START, nowMs = START + 1_000, expanded = true)
        assertEquals(AlertKeyAction.CANCEL, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_BACK, expanded))
        assertEquals(AlertKeyAction.PRESS, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_DPAD_CENTER, expanded))
        // It has the keys: nothing reaches the player underneath but the volume.
        assertEquals(AlertKeyAction.CONSUME, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_DPAD_UP, expanded))
        assertEquals(AlertKeyAction.CONSUME, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, expanded))
        assertEquals(AlertKeyAction.PASS, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_VOLUME_UP, expanded))
        // No Watch both (dual screen, browsing, a movie): Left and Right do nothing.
        assertEquals(AlertKeyAction.CONSUME, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_DPAD_RIGHT, expanded))
        assertEquals(10, AlertCountdown.start(1, START, SportsLiveAlertCards.TIP_OFF_COUNTDOWN_MS).secondsLeft(START))
    }

    @Test
    fun `over a sports stream Watch both is offered, and moving to it stops the countdown`() {
        val expanded = SportsLiveAlertKeys.context(scoreCard, shownAtMs = START, nowMs = START + 1_000, expanded = true, canBoth = true)
        assertEquals(AlertKeyAction.MOVE, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_DPAD_RIGHT, expanded))
        val moved = AlertCountdown.start(scoreCard.id, START).move()
        assertEquals(AlertChoice.WATCH_BOTH, moved.selected)
        assertEquals(null, moved.endsAtMs)
        assertFalse(moved.due(START + 60_000))
        assertEquals(null, moved.secondsLeft(START + 1_000))
        // Back to Watch now: the countdown stays stopped, OK switches.
        assertEquals(AlertChoice.WATCH_NOW, moved.move().selected)
        assertEquals(null, moved.move().endsAtMs)
        // Never offered outside the countdown card.
        assertFalse(SportsLiveAlertKeys.context(scoreCard, START, START + 1_000, expanded = false, canBoth = true).canBoth)
    }

    @Test
    fun `switching at tip-off is opt-in, needs a stream and never cuts into something playing`() {
        val switch = com.nuvio.tv.core.sportslive.SportsLiveTipOff.SWITCH
        assertTrue(SportsLiveTipOffSwitch.runs(switch, noStreamYet = false, inPlayer = false))
        assertFalse(SportsLiveTipOffSwitch.runs(com.nuvio.tv.core.sportslive.SportsLiveTipOff.REMIND, noStreamYet = false, inPlayer = false))
        assertFalse(SportsLiveTipOffSwitch.runs(switch, noStreamYet = true, inPlayer = false))
        assertFalse(SportsLiveTipOffSwitch.runs(switch, noStreamYet = false, inPlayer = true))
    }

    @Test
    fun `with no card every key stays the screen's`() {
        val none = SportsLiveAlertKeys.context(null, START, START + 5_000)
        for (code in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_DPAD_DOWN)) {
            assertEquals("key $code", AlertKeyAction.PASS, SportsLiveAlertKeys.route(code, none))
        }
    }


    // The peek

    @Test
    fun `a peek runs ten seconds from its first frame, then the game from before comes back`() {
        var state: PeekState? = SportsLivePeek.start(lakers, START)
        // The lookup is not counted.
        state = SportsLivePeek.step(state!!, PeekEvent.Tick(START + 9_000)).first
        assertEquals(PeekPhase.LOADING, state!!.phase)
        state = SportsLivePeek.step(state, PeekEvent.FirstFrame(START + 9_000)).first!!
        assertEquals(PeekPhase.PLAYING, state.phase)
        assertEquals(state to PeekCommand.NONE, SportsLivePeek.step(state, PeekEvent.Tick(START + 18_999)))
        assertEquals(null to PeekCommand.RETURN, SportsLivePeek.step(state, PeekEvent.Tick(START + 19_000)))
    }

    @Test
    fun `Back during a peek returns at once`() {
        val playing = SportsLivePeek.step(SportsLivePeek.start(lakers, START), PeekEvent.FirstFrame(START + 1_000)).first!!
        assertEquals(null to PeekCommand.RETURN, SportsLivePeek.step(playing, PeekEvent.Back))
        assertEquals(null to PeekCommand.RETURN, SportsLivePeek.step(SportsLivePeek.start(lakers, START), PeekEvent.Back))
    }

    @Test
    fun `OK during a peek zaps to the peeked game, which stays up until the new player replaces the old`() {
        val playing = SportsLivePeek.step(SportsLivePeek.start(lakers, START), PeekEvent.FirstFrame(START + 1_000)).first!!
        val (staying, command) = SportsLivePeek.step(playing, PeekEvent.Ok(START + 5_000))
        assertEquals(PeekCommand.ZAP, command)
        assertEquals(PeekPhase.STAYING, staying!!.phase)
        // The ten seconds no longer apply; a second OK or Back changes nothing while the zap runs.
        assertEquals(staying to PeekCommand.NONE, SportsLivePeek.step(staying, PeekEvent.Tick(START + 12_000)))
        assertEquals(staying to PeekCommand.NONE, SportsLivePeek.step(staying, PeekEvent.Ok(START + 6_000)))
        assertEquals(staying to PeekCommand.NONE, SportsLivePeek.step(staying, PeekEvent.Back))
        assertEquals(null to PeekCommand.END, SportsLivePeek.step(staying, PeekEvent.PlayerReplaced))
    }

    @Test
    fun `a zap that never arrives gives the game from before back`() {
        val staying = SportsLivePeek.step(SportsLivePeek.start(lakers, START), PeekEvent.Ok(START)).first!!
        assertEquals(null to PeekCommand.RETURN, SportsLivePeek.step(staying, PeekEvent.Tick(START + SportsLivePeek.STAY_TIMEOUT_MS)))
    }

    @Test
    fun `a peek with no stream, a failing stream or no first frame in time returns and says so`() {
        val loading = SportsLivePeek.start(lakers, START)
        assertEquals(null to PeekCommand.RETURN_FAILED, SportsLivePeek.step(loading, PeekEvent.Failed))
        assertEquals(null to PeekCommand.RETURN_FAILED, SportsLivePeek.step(loading, PeekEvent.Tick(START + SportsLivePeek.LOAD_TIMEOUT_MS)))
        val playing = SportsLivePeek.step(loading, PeekEvent.FirstFrame(START + 1_000)).first!!
        assertEquals(null to PeekCommand.RETURN_FAILED, SportsLivePeek.step(playing, PeekEvent.Failed))
        // Once staying, the zap looks its own stream up.
        val staying = SportsLivePeek.step(playing, PeekEvent.Ok(START + 2_000)).first!!
        assertEquals(staying to PeekCommand.NONE, SportsLivePeek.step(staying, PeekEvent.Failed))
    }

    // The engine in dual screen and under the mini player

    @Test
    fun `score toasts now show in dual screen and over the mini player`() {
        fun toasts(watching: SportsLiveWatching): Int {
            val engine = SportsLiveAlertEngine()
            val game = { s: ScoreSnapshot -> listOf(AlertGame(pacers, s, false, setOf(ScoreSide.AWAY))) }
            engine.update(START + 10 * MINUTE, game(live(10, 8)), SportsLiveAlertSettings(), ScoresMode.SHOW, watching)
            return engine.update(START + 11 * MINUTE, game(live(10, 11)), SportsLiveAlertSettings(), ScoresMode.SHOW, watching).size
        }
        assertEquals(1, toasts(SportsLiveWatching(dual = true, playingIds = setOf("other"))))
        assertEquals(1, toasts(SportsLiveWatching(miniPlaying = true, playingIds = setOf("other"))))
        // The highlighted pane's game is the one watched: no toast for it.
        assertEquals(0, toasts(SportsLiveWatching(dual = true, playingIds = setOf(pacers.id))))
        assertEquals(0, toasts(SportsLiveWatching()))
    }
}
