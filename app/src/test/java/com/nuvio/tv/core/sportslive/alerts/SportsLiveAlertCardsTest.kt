package com.nuvio.tv.core.sportslive.alerts

import android.view.KeyEvent
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.MINUTE
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.START
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.final
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.item
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures.live
import com.nuvio.tv.core.sportslive.scores.ScoreSide
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #136: grouped cards, their timing, where they sit, who takes a key, and the peek. */
class SportsLiveAlertCardsTest {

    private val pacers = item("Pacers @ 76ers", followed = true)
    private val lakers = item("Lakers @ Celtics", followed = true)
    private val knicks = item("Knicks @ Heat", followed = true)

    private fun score(game: com.nuvio.tv.domain.model.SportsLiveItem = pacers, lead: Boolean = false) =
        SportsLiveAlert.ScoreChange(game, live(10, 11), ScoreSide.AWAY, leadChange = lead)

    // Per-alert duration

    @Test
    fun `a plain score shows about six seconds, a lead change or a final longer`() {
        assertEquals(6_000L, SportsLiveAlertCards.durationMs(score()))
        assertEquals(SportsLiveAlertCards.BIG_MS, SportsLiveAlertCards.durationMs(score(lead = true)))
        assertEquals(SportsLiveAlertCards.BIG_MS, SportsLiveAlertCards.durationMs(SportsLiveAlert.FullTime(pacers, final(101, 99), namesOnly = false)))
        assertEquals(SportsLiveAlertCards.BIG_MS, SportsLiveAlertCards.durationMs(SportsLiveAlert.FullTime(pacers, final(101, 99), namesOnly = true)))
        assertTrue(SportsLiveAlertCards.BIG_MS > SportsLiveAlertCards.NORMAL_MS)
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
        assertEquals(SportsLiveAlertCards.BIG_MS, SportsLiveAlertCards.durationMs(change(live(10, 8), live(10, 11))))
    }

    // Grouping and cycling

    @Test
    fun `alerts within eight seconds join one card, later ones wait for the next`() {
        val card = AlertCard(1, listOf(score(pacers)), shownAtMs = START)
        assertTrue(SportsLiveAlertCards.canJoin(card, score(lakers), START + 5_000))
        val joined = SportsLiveAlertCards.join(card, score(lakers), START + 5_000)
        assertEquals(listOf(pacers, lakers), joined.alerts.map { it.item })
        assertEquals(START + 5_000, joined.lastJoinedAtMs)
        // Eight seconds after the newest one still joins (the card is still up); past that, a card of its own.
        val big = AlertCard(2, listOf(score(pacers, lead = true)), shownAtMs = START)
        assertTrue(SportsLiveAlertCards.canJoin(big, score(knicks), START + 8_000))
        assertFalse(SportsLiveAlertCards.canJoin(big, score(knicks), START + 8_001))
    }

    @Test
    fun `a kickoff prompt never groups, and nothing joins it`() {
        val kickoff = SportsLiveAlert.Kickoff(lakers, null)
        val card = AlertCard(1, listOf(score()), shownAtMs = START)
        assertFalse(SportsLiveAlertCards.canJoin(card, kickoff, START + 1_000))
        assertFalse(SportsLiveAlertCards.canJoin(AlertCard(2, listOf(kickoff), START), score(), START + 1_000))
    }

    @Test
    fun `a card that has left takes no more alerts`() {
        val card = AlertCard(1, listOf(score()), shownAtMs = START)
        assertEquals(START + 6_000, SportsLiveAlertCards.endsAtMs(card))
        assertFalse(SportsLiveAlertCards.canJoin(card, score(lakers), START + 6_000))
    }

    @Test
    fun `a newer alert for a game on the card takes that one's place`() {
        val card = AlertCard(1, listOf(score(pacers), score(lakers)), shownAtMs = START)
        val ended = SportsLiveAlert.FullTime(pacers, final(101, 99), namesOnly = false)
        val joined = SportsLiveAlertCards.join(card, ended, START + 2_000)
        assertEquals(2, joined.alerts.size)
        assertEquals(ended, joined.alerts[0])
    }

    @Test
    fun `a grouped card cycles through its alerts every three seconds`() {
        val card = AlertCard(1, listOf(score(pacers), score(lakers), score(knicks)), shownAtMs = START)
        assertEquals(0, SportsLiveAlertCards.showingIndex(card, START))
        assertEquals(0, SportsLiveAlertCards.showingIndex(card, START + 2_999))
        assertEquals(1, SportsLiveAlertCards.showingIndex(card, START + 3_000))
        assertEquals(2, SportsLiveAlertCards.showingIndex(card, START + 6_500))
        assertEquals(0, SportsLiveAlertCards.showingIndex(card, START + 9_000))
        assertEquals(0, SportsLiveAlertCards.showingIndex(AlertCard(2, listOf(score()), START), START + 4_000))
    }

    @Test
    fun `a grouped card stays a turn for each, from its last join, and never past twenty seconds`() {
        val two = AlertCard(1, listOf(score(pacers), score(lakers)), shownAtMs = START, lastJoinedAtMs = START + 4_000)
        // Two turns are shorter than one alert's six seconds: six from the last join.
        assertEquals(START + 10_000, SportsLiveAlertCards.endsAtMs(two))
        val three = AlertCard(1, listOf(score(pacers), score(lakers, lead = true), score(knicks)), shownAtMs = START, lastJoinedAtMs = START + 2_000)
        assertEquals(START + 2_000 + 9_000, SportsLiveAlertCards.endsAtMs(three))
        val many = AlertCard(1, List(8) { score(item("Team$it @ Other$it", followed = true)) }, shownAtMs = START, lastJoinedAtMs = START + 7_000)
        assertEquals(START + SportsLiveAlertCards.MAX_CARD_MS, SportsLiveAlertCards.endsAtMs(many))
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

    // Who takes a key

    private val actionable = AlertKeyContext(cardActionable = true, canPeek = true, peeking = false)

    @Test
    fun `an actionable card takes OK to watch, Down to peek and Back to dismiss`() {
        assertEquals(AlertKeyAction.WATCH, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_DPAD_CENTER, actionable))
        assertEquals(AlertKeyAction.WATCH, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_ENTER, actionable))
        assertEquals(AlertKeyAction.PEEK, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_DPAD_DOWN, actionable))
        assertEquals(AlertKeyAction.DISMISS, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_BACK, actionable))
        // Everything else is the player's: Up still zaps, Left still opens the panel, CH- still zaps.
        assertEquals(AlertKeyAction.PASS, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_DPAD_UP, actionable))
        assertEquals(AlertKeyAction.PASS, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_DPAD_LEFT, actionable))
        assertEquals(AlertKeyAction.PASS, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_CHANNEL_DOWN, actionable))
    }

    @Test
    fun `with no card, or the player's controls up, OK stays the player's`() {
        val notActionable = AlertKeyContext(cardActionable = false, canPeek = false, peeking = false)
        listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_BACK).forEach { code ->
            assertEquals(AlertKeyAction.PASS, SportsLiveAlertKeys.route(code, notActionable))
        }
    }

    @Test
    fun `where no peek is possible Down is left to the screen`() {
        val dual = AlertKeyContext(cardActionable = true, canPeek = false, peeking = false)
        assertEquals(AlertKeyAction.PASS, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_DPAD_DOWN, dual))
        assertEquals(AlertKeyAction.WATCH, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_DPAD_CENTER, dual))
    }

    @Test
    fun `during a peek OK stays, Back returns, the volume works and nothing else reaches the player`() {
        val peeking = AlertKeyContext(cardActionable = false, canPeek = false, peeking = true)
        assertEquals(AlertKeyAction.PEEK_STAY, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_DPAD_CENTER, peeking))
        assertEquals(AlertKeyAction.PEEK_RETURN, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_BACK, peeking))
        assertEquals(AlertKeyAction.PASS, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_VOLUME_UP, peeking))
        assertEquals(AlertKeyAction.CONSUME, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_DPAD_UP, peeking))
        assertEquals(AlertKeyAction.CONSUME, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_CHANNEL_UP, peeking))
        // A card showing during a peek does not take OK from it.
        assertEquals(AlertKeyAction.PEEK_STAY, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_DPAD_CENTER, peeking.copy(cardActionable = true)))
    }

    // The kickoff prompt over the player or dual screen (#173)

    private val prompt = AlertKeyContext(cardActionable = false, canPeek = false, peeking = false, prompt = PromptChoice.WATCH)

    @Test
    fun `on the kickoff prompt OK watches at once, with Watch chosen first`() {
        assertEquals(AlertKeyAction.PROMPT_WATCH, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_DPAD_CENTER, prompt))
        assertEquals(AlertKeyAction.PROMPT_WATCH, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_ENTER, prompt))
        assertEquals(AlertKeyAction.PROMPT_WATCH, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_NUMPAD_ENTER, prompt))
    }

    @Test
    fun `left and right choose Watch or Later, and OK presses the one chosen`() {
        assertEquals(AlertKeyAction.PROMPT_CHOOSE_LATER, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_DPAD_RIGHT, prompt))
        assertEquals(AlertKeyAction.PROMPT_CHOOSE_WATCH, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_DPAD_LEFT, prompt))
        val later = prompt.copy(prompt = PromptChoice.LATER)
        assertEquals(AlertKeyAction.PROMPT_LATER, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_DPAD_CENTER, later))
    }

    @Test
    fun `Back on the kickoff prompt is Later`() {
        assertEquals(AlertKeyAction.PROMPT_LATER, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_BACK, prompt))
        assertEquals(AlertKeyAction.PROMPT_LATER, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_ESCAPE, prompt))
    }

    @Test
    fun `while the kickoff prompt is up no zap key reaches the player, the volume still does`() {
        listOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS
        ).forEach { code ->
            assertEquals("key $code", AlertKeyAction.CONSUME, SportsLiveAlertKeys.route(code, prompt))
        }
        assertEquals(AlertKeyAction.PASS, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_VOLUME_UP, prompt))
    }

    @Test
    fun `a kickoff prompt over the player takes the keys even with the player's own overlays up`() {
        val kickoff = AlertCard(1, listOf(SportsLiveAlert.Kickoff(lakers, null)), shownAtMs = START)
        listOf(false, true).forEach { busy ->
            val context = SportsLiveAlertKeys.context(kickoff, canActHere = true, surfaceBusy = busy, canPeek = true, peeking = false)
            assertEquals(PromptChoice.WATCH, context.prompt)
            assertFalse(context.cardActionable)
            assertFalse(context.canPeek)
        }
        // Over a movie or show, or anywhere it cannot play here, the prompt keeps its own focus: keys pass.
        val elsewhere = SportsLiveAlertKeys.context(kickoff, canActHere = false, surfaceBusy = false, canPeek = false, peeking = false)
        assertEquals(null, elsewhere.prompt)
        assertEquals(AlertKeyAction.PASS, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_CHANNEL_UP, elsewhere))
    }

    @Test
    fun `once the kickoff prompt times out the zap keys are the player's again`() {
        val kickoff = AlertCard(1, listOf(SportsLiveAlert.Kickoff(lakers, null)), shownAtMs = START)
        val up = SportsLiveAlertKeys.context(kickoff, canActHere = true, surfaceBusy = false, canPeek = true, peeking = false)
        assertEquals(AlertKeyAction.CONSUME, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_CHANNEL_UP, up))
        // The prompt closed (its 15 s ran out, or it was answered): no card, so nothing is taken.
        val closed = SportsLiveAlertKeys.context(null, canActHere = true, surfaceBusy = false, canPeek = true, peeking = false)
        listOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BACK
        ).forEach { code ->
            assertEquals("key $code", AlertKeyAction.PASS, SportsLiveAlertKeys.route(code, closed))
        }
    }

    @Test
    fun `up on the kickoff prompt opens both games where the player offers it, else it stays swallowed`() {
        val kickoff = AlertCard(1, listOf(SportsLiveAlert.Kickoff(lakers, null)), shownAtMs = START)
        val both = SportsLiveAlertKeys.context(kickoff, canActHere = true, surfaceBusy = true, canPeek = true, peeking = false, canBoth = true)
        assertEquals(AlertKeyAction.BOTH, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_DPAD_UP, both))
        // ▼ and CH± still never zap under it.
        assertEquals(AlertKeyAction.CONSUME, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_DPAD_DOWN, both))
        assertEquals(AlertKeyAction.CONSUME, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_CHANNEL_UP, both))
        val noBoth = SportsLiveAlertKeys.context(kickoff, canActHere = true, surfaceBusy = false, canPeek = true, peeking = false, canBoth = false)
        assertEquals(AlertKeyAction.CONSUME, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_DPAD_UP, noBoth))
    }

    @Test
    fun `up on a score card opens both games only while the card takes keys`() {
        val card = AlertCard(1, listOf(score()), shownAtMs = START)
        val open = SportsLiveAlertKeys.context(card, canActHere = true, surfaceBusy = false, canPeek = true, peeking = false, canBoth = true)
        assertEquals(AlertKeyAction.BOTH, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_DPAD_UP, open))
        val busy = SportsLiveAlertKeys.context(card, canActHere = true, surfaceBusy = true, canPeek = true, peeking = false, canBoth = true)
        assertFalse(busy.canBoth)
        assertEquals(AlertKeyAction.PASS, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_DPAD_UP, busy))
    }

    @Test
    fun `a score card's context is unchanged - actionable only with the screen's overlays closed`() {
        val card = AlertCard(1, listOf(score()), shownAtMs = START)
        val open = SportsLiveAlertKeys.context(card, canActHere = true, surfaceBusy = false, canPeek = true, peeking = false)
        assertEquals(AlertKeyContext(cardActionable = true, canPeek = true, peeking = false), open)
        val busy = SportsLiveAlertKeys.context(card, canActHere = true, surfaceBusy = true, canPeek = true, peeking = false)
        assertEquals(AlertKeyContext(cardActionable = false, canPeek = false, peeking = false), busy)
        val noSurface = SportsLiveAlertKeys.context(card, canActHere = true, surfaceBusy = null, canPeek = true, peeking = false)
        assertFalse(noSurface.cardActionable)
    }

    @Test
    fun `a peek keeps every key even with a kickoff prompt up`() {
        val peekingPrompt = prompt.copy(peeking = true)
        assertEquals(AlertKeyAction.PEEK_STAY, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_DPAD_CENTER, peekingPrompt))
        assertEquals(AlertKeyAction.PEEK_RETURN, SportsLiveAlertKeys.route(KeyEvent.KEYCODE_BACK, peekingPrompt))
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

    // The queue

    @Test
    fun `toasts raised together share one card, a later one joins it, and closing it shows the next`() {
        val alerts = SportsLiveAlerts(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        val now = System.currentTimeMillis()
        alerts.enqueue(listOf(score(pacers), score(lakers)), now)
        val card = alerts.current.value!!
        assertEquals(listOf(pacers, lakers), card.alerts.map { it.item })
        alerts.enqueue(listOf(score(knicks)), now + 1_000)
        val joined = alerts.current.value!!
        assertEquals(card.id, joined.id)
        assertEquals(3, joined.alerts.size)
        // A kickoff prompt waits for its own card.
        alerts.enqueue(listOf(SportsLiveAlert.Kickoff(item("Bulls @ Nets", followed = true), null)), now + 2_000)
        assertEquals(3, alerts.current.value!!.alerts.size)
        // Closed by the card it read before the join: the card goes by id all the same.
        alerts.close(card, AlertAnswer.TIMEOUT)
        val next = alerts.current.value!!
        assertTrue(next.first is SportsLiveAlert.Kickoff)
        assertEquals(1, next.alerts.size)
    }
}
