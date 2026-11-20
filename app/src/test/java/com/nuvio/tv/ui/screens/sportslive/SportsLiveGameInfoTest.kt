package com.nuvio.tv.ui.screens.sportslive

import android.view.KeyEvent
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.GameSummary
import com.nuvio.tv.core.sportslive.scores.GameSummaryLoad
import com.nuvio.tv.core.sportslive.scores.GameTeam
import com.nuvio.tv.core.sportslive.scores.PeriodLabel
import com.nuvio.tv.core.sportslive.scores.Performer
import com.nuvio.tv.core.sportslive.scores.PerformerStat
import com.nuvio.tv.core.sportslive.scores.ScoreDetails
import com.nuvio.tv.core.sportslive.scores.ScoreDetailsSide
import com.nuvio.tv.core.sportslive.scores.ScoreSide
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** #139: the game info overlay's keys, its view model and its lines. */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveGameInfoTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // Keys: full screen

    @Test
    fun `full screen INFO or MENU opens the overlay with the controls hidden on a matched game`() {
        listOf(KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MENU).forEach { key ->
            assertEquals(GameInfoKeyAction.SHOW, SportsLiveGameInfoKeys.fullScreen(key, shown = false, available = true, controlsShown = false))
        }
    }

    @Test
    fun `full screen INFO keeps opening the stream details when there is no game info`() {
        // No match, Scores Off: INFO does what it did.
        assertEquals(GameInfoKeyAction.PASS, SportsLiveGameInfoKeys.fullScreen(KeyEvent.KEYCODE_INFO, shown = false, available = false, controlsShown = false))
        // The controls are up: their own INFO.
        assertEquals(GameInfoKeyAction.PASS, SportsLiveGameInfoKeys.fullScreen(KeyEvent.KEYCODE_INFO, shown = false, available = true, controlsShown = true))
        assertEquals(GameInfoKeyAction.PASS, SportsLiveGameInfoKeys.fullScreen(KeyEvent.KEYCODE_MENU, shown = false, available = false, controlsShown = false))
    }

    @Test
    fun `full screen zap, panel, controls and OK keys never open the overlay`() {
        listOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK
        ).forEach { key ->
            assertEquals(GameInfoKeyAction.PASS, SportsLiveGameInfoKeys.fullScreen(key, shown = false, available = true, controlsShown = false))
        }
    }

    @Test
    fun `full screen with the overlay up, MENU closes it and every other key closes it and still does its job`() {
        assertEquals(GameInfoKeyAction.HIDE, SportsLiveGameInfoKeys.fullScreen(KeyEvent.KEYCODE_MENU, shown = true, available = true, controlsShown = false))
        // INFO again goes on to the stream details; a zap zaps, Left opens the panel.
        listOf(KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_CENTER).forEach { key ->
            assertEquals(GameInfoKeyAction.HIDE_AND_PASS, SportsLiveGameInfoKeys.fullScreen(key, shown = true, available = true, controlsShown = false))
        }
    }

    // Keys: dual

    @Test
    fun `a bare OK never pauses a live game, it opens the game info and then its Game page (TV QA C5)`() {
        fun ok(live: Boolean = true, controls: Boolean = false, shown: Boolean = false, available: Boolean = true, game: Boolean = true) =
            SportsLiveGameInfoKeys.fullScreenOk(live, controls, shown, available, game)
        assertEquals(SportsLiveOkAction.SHOW_INFO, ok())
        assertEquals(SportsLiveOkAction.OPEN_GAME, ok(shown = true))
        // No Game page to open (an individual sport): OK only closes the card, still no pause.
        assertEquals(SportsLiveOkAction.HIDE_INFO, ok(shown = true, game = false))
        // Nothing to show (no ESPN match, Scores Off): the controls, Play focused, so pausing takes a second OK.
        assertEquals(SportsLiveOkAction.SHOW_CONTROLS, ok(available = false))
        // The controls' own buttons and a replay keep the player's OK (a replay pauses as any video does).
        assertEquals(SportsLiveOkAction.PASS, ok(controls = true))
        assertEquals(SportsLiveOkAction.PASS, ok(live = false))
        assertEquals(SportsLiveOkAction.PASS, ok(live = false, shown = true))
    }

    @Test
    fun `the info card names OK Game page only while OK opens it, from the same routing`() {
        assertTrue(SportsLiveGameInfoKeys.infoCardOpensGame(live = true, canOpenGame = true))
        assertFalse(SportsLiveGameInfoKeys.infoCardOpensGame(live = true, canOpenGame = false))
        // A replay's OK closes the card and pauses as before: only "Back close".
        assertFalse(SportsLiveGameInfoKeys.infoCardOpensGame(live = false, canOpenGame = true))
    }

    @Test
    fun `dual INFO opens and closes the overlay, MENU stays the swap`() {
        assertEquals(GameInfoKeyAction.SHOW, SportsLiveGameInfoKeys.dual(KeyEvent.KEYCODE_INFO, shown = false, available = true))
        assertEquals(GameInfoKeyAction.HIDE, SportsLiveGameInfoKeys.dual(KeyEvent.KEYCODE_INFO, shown = true, available = true))
        assertEquals(GameInfoKeyAction.PASS, SportsLiveGameInfoKeys.dual(KeyEvent.KEYCODE_INFO, shown = false, available = false))
        assertEquals(GameInfoKeyAction.PASS, SportsLiveGameInfoKeys.dual(KeyEvent.KEYCODE_MENU, shown = false, available = true))
        assertEquals(GameInfoKeyAction.PASS, SportsLiveGameInfoKeys.dual(KeyEvent.KEYCODE_MENU, shown = true, available = true))
    }

    @Test
    fun `dual keys keep their jobs with the overlay up`() {
        listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER)
            .forEach { key -> assertEquals(GameInfoKeyAction.PASS, SportsLiveGameInfoKeys.dual(key, shown = true, available = true)) }
    }

    // Lines

    @Test
    fun `a performer's line is the team, the player and every category they lead`() {
        val performer = Performer(ScoreSide.AWAY, "BOS", "J. Tatum", listOf(PerformerStat("28", "PTS"), PerformerStat("9", "REB")))
        assertEquals("BOS · J. Tatum · 28 PTS · 9 REB", performerLine(performer, " · ", "%1\$s %2\$s"))
        // A football line is already its own text.
        val runner = Performer(ScoreSide.HOME, "KC", "I. Pacheco", listOf(PerformerStat("14 CAR, 88 YDS", null)))
        assertEquals("KC · I. Pacheco · 14 CAR, 88 YDS", performerLine(runner, " · ", "%1\$s %2\$s"))
    }

    @Test
    fun `a narrow linescore keeps the latest periods`() {
        val details = ScoreDetails.Shown(
            first = ScoreDetailsSide("BOS", 5, (1..9).map { it }, emptyList()),
            second = ScoreDetailsSide("NYY", 3, (11..19).map { it }, emptyList()),
            status = "Bot 9th", live = true, periods = (1..9).map { PeriodLabel.Number(it) }, soccer = false
        )
        val kept = latestPeriods(details, 4)
        assertEquals((6..9).map { PeriodLabel.Number(it) }, kept.periods)
        assertEquals(listOf(6, 7, 8, 9), kept.first.linescores)
        assertEquals(listOf(16, 17, 18, 19), kept.second.linescores)
        assertEquals(5, kept.first.score)
    }

    // View model

    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
    // IND @ PHI: home Philadelphia 76ers, away Indiana Pacers.
    private val pacers = nba.first { it.eventId == "401909985" }

    private fun item(name: String) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false,
        startMs = null, sport = "Basketball", teams = SportsLiveTeams.parse(name)
    )

    private val card = item("Pacers at 76ers")
    private val unmatched = item("Celtics vs Knicks")
    private val live = pacers.copy(
        state = ScoreState.IN, isFinal = false, clockText = "4:12 - 4th",
        home = pacers.home.copy(score = 88), away = pacers.away.copy(score = 91)
    )
    private val leader = Performer(ScoreSide.AWAY, "IND", "T. Haliburton", listOf(PerformerStat("28", "PTS")))
    private val summary = GameSummary(
        eventId = live.eventId, league = live.league, state = ScoreState.IN, isFinal = false, clockText = null, startMs = null,
        venue = null,
        away = GameTeam(null, "IND", null, null, null, 91, emptyList(), null),
        home = GameTeam(null, "PHI", null, null, null, 88, emptyList(), null),
        scorers = emptyList(), teamStats = emptyList(), performers = listOf(leader),
        boxScore = emptyList(), standings = emptyList(), highlights = emptyList()
    )

    private val loads = mutableListOf<Pair<EspnLeague, String>>()
    private val scores = MutableStateFlow<Map<String, ScoreSnapshot>>(mapOf(card.streamKey to live))
    private val mode = MutableStateFlow(ScoresMode.SHOW)

    private fun newViewModel() = SportsLiveGameInfoViewModel(
        scores = scores,
        mode = mode,
        loadSummary = { league, id -> loads += league to id; GameSummaryLoad.Loaded(summary, final = false) },
        hideAfterMs = 8_000L
    )

    @Test
    fun `show opens on a matched game, loads its summary once and closes by itself`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        viewModel.info.launchIn(backgroundScope)
        runCurrent()
        assertTrue(viewModel.show(card))
        runCurrent()
        assertTrue(viewModel.shown)
        val info = viewModel.info.value
        assertNotNull(info)
        assertEquals(listOf(leader), info!!.leaders)
        assertEquals(listOf(live.league to live.eventId), loads)
        advanceTimeBy(8_001L)
        runCurrent()
        assertFalse(viewModel.shown)
        assertNull(viewModel.info.value)
    }

    @Test
    fun `show does nothing for an unmatched game or under Scores Off, and makes no request`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        runCurrent()
        assertFalse(viewModel.show(unmatched))
        mode.value = ScoresMode.OFF
        runCurrent()
        assertFalse(viewModel.available(card))
        assertFalse(viewModel.show(card))
        assertFalse(viewModel.shown)
        assertTrue(loads.isEmpty())
    }

    @Test
    fun `the open card's Game page is its matched game, never under Scores Off nor while closed`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        runCurrent()
        assertNull(viewModel.gameRequest())
        viewModel.show(card)
        val request = viewModel.gameRequest()
        assertEquals(live.eventId, request?.event?.eventId)
        assertEquals(card, request?.card)
        assertTrue(request!!.revealed)
        mode.value = ScoresMode.HIDE_UNTIL_FOCUSED
        runCurrent()
        assertFalse(viewModel.gameRequest()!!.revealed)
        mode.value = ScoresMode.OFF
        runCurrent()
        assertNull(viewModel.gameRequest())
    }

    @Test
    fun `hide closes it once, for Back`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        runCurrent()
        assertFalse(viewModel.hide())
        viewModel.show(card)
        assertTrue(viewModel.hide())
        assertFalse(viewModel.hide())
    }

    @Test
    fun `in dual it follows the game with sound while open and closes on a game without info`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = newViewModel()
        viewModel.info.launchIn(backgroundScope)
        runCurrent()
        // Closed: following does not open it.
        viewModel.follow(card)
        assertFalse(viewModel.shown)
        val other = item("76ers vs Pacers")
        scores.value = scores.value + (other.streamKey to live)
        viewModel.show(card)
        runCurrent()
        viewModel.follow(other)
        runCurrent()
        assertTrue(viewModel.shown)
        assertEquals("PHI", (viewModel.info.value!!.details as ScoreDetails.Shown).first.short)
        // The sound moved to a game with no match: the overlay closes.
        viewModel.follow(unmatched)
        assertFalse(viewModel.shown)
    }
}
