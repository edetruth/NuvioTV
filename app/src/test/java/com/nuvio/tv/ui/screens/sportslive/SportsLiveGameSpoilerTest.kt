package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.sportslive.alerts.AlertFixtures
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.GameSummaryFixtures
import com.nuvio.tv.core.sportslive.scores.GameSummaryLoad
import com.nuvio.tv.core.sportslive.scores.ScoreSettings
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.SpoilerProtection
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Spoiler protection on the Game page (#151). */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveGameSpoilerTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // Crystal Palace (home) 1–3 Liverpool (away), FT, and its summary.
    private val game = EspnFixtures.events(EspnFixtures.ENG1_20261108, EspnFixtures.ENG1).first { it.eventId == "401879243" }
    private val summary = GameSummaryFixtures.summary(GameSummaryFixtures.SOCCER, EspnFixtures.ENG1, "401879243")
    private val settings = MutableStateFlow(ScoreSettings(mode = ScoresMode.SHOW))
    private val now = game.startMs!! + 4 * 60 * 60_000L
    private val protection = MutableStateFlow(SpoilerProtection(nowMs = now))
    private val ended = mutableListOf<ScoreSnapshot>()

    private fun TestScope.viewModel(request: SportsLiveGameRequest, settings: Flow<ScoreSettings> = this@SportsLiveGameSpoilerTest.settings) = SportsLiveGameViewModel(
        request = request,
        loadSummary = { _: EspnLeague, _: String -> GameSummaryLoad.Loaded(summary, final = true) },
        settings = settings,
        followedTeams = MutableStateFlow<List<FollowedTeam>>(emptyList()),
        follow = {},
        unfollow = {},
        now = { testScheduler.currentTime },
        failedHighlights = MutableStateFlow(emptySet()),
        protection = protection,
        endProtection = { event ->
            ended += event
            protection.value = SpoilerProtection(SpoilerProtection.ended(protection.value.games, event, now), nowMs = now)
        },
        saveForLater = { event ->
            protection.value = SpoilerProtection(SpoilerProtection.saved(protection.value.games, event, now), nowMs = now)
        }
    ).also { vm -> backgroundScope.launch { vm.uiState.collect {} } }

    @Test
    fun `the Game page opens spoiler-free from a replay, even under Show`() = runTest(mainDispatcherRule.dispatcher) {
        val replay = AlertFixtures.item(name = "Crystal Palace vs Liverpool", id = "replay").copy(kind = SportsLiveKind.REPLAY)
        val vm = viewModel(sportsLiveReplayGameRequest(game, replay))
        vm.setVisible(true)
        runCurrent()
        assertFalse(vm.uiState.value.showsScore)
        assertTrue(vm.uiState.value.sections.isEmpty())
        // Reveal result shows it.
        vm.reveal()
        runCurrent()
        assertTrue(vm.uiState.value.showsScore)
        assertTrue(vm.uiState.value.sections.isNotEmpty())
    }

    @Test
    fun `a protected game stays hidden when opened revealed, until Reveal result ends its protection`() = runTest(mainDispatcherRule.dispatcher) {
        protection.value = SpoilerProtection(SpoilerProtection.saved(emptyList(), game, now), nowMs = now)
        // Opened from a surface that showed scores (Show): it still opens spoiler-free.
        val vm = viewModel(SportsLiveGameRequest(protection.value.stamp(game), revealed = true))
        vm.setVisible(true)
        runCurrent()
        assertTrue(vm.uiState.value.spoilerProtected)
        assertFalse(vm.uiState.value.showsScore)
        assertTrue(vm.uiState.value.sections.isEmpty())

        vm.reveal()
        runCurrent()
        assertEquals(listOf(game.eventId), ended.map { it.eventId })
        assertFalse(vm.uiState.value.spoilerProtected)
        assertTrue(vm.uiState.value.showsScore)
    }

    @Test
    fun `the header shows no team records while the score is hidden`() = runTest(mainDispatcherRule.dispatcher) {
        // #184: a record after the game tells how it ended.
        val withRecords = summary.copy(away = summary.away.copy(record = "2-1-7"), home = summary.home.copy(record = "5-3-2"))
        protection.value = SpoilerProtection(SpoilerProtection.saved(emptyList(), game, now), nowMs = now)
        val vm = viewModel(SportsLiveGameRequest(protection.value.stamp(game), revealed = true))
        vm.setVisible(true)
        runCurrent()
        val (away, home) = headerSides(withRecords, game)
        assertFalse(vm.uiState.value.showsScore)
        assertEquals(listOf(null, null), listOf(away, home).map { it.shownRecord(vm.uiState.value.showsScore) })

        vm.reveal()
        runCurrent()
        assertTrue(vm.uiState.value.showsScore)
        assertEquals(listOf("2-1-7", "5-3-2"), listOf(away, home).map { it.shownRecord(vm.uiState.value.showsScore) })
    }

    @Test
    fun `saving a game while its page is open hides it at once`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(SportsLiveGameRequest(game, revealed = true))
        runCurrent()
        assertTrue(vm.uiState.value.showsScore)
        protection.value = SpoilerProtection(SpoilerProtection.saved(emptyList(), game, now), nowMs = now)
        runCurrent()
        assertFalse(vm.uiState.value.showsScore)
    }

    @Test
    fun `nothing shows until the Scores setting is read - no flash of the score under Hide until focused`() = runTest(mainDispatcherRule.dispatcher) {
        // live.19 F18: the page used to start as Show, so a hidden game drew its score and only More… for a moment.
        val pending = MutableSharedFlow<ScoreSettings>()
        val vm = viewModel(SportsLiveGameRequest(game, revealed = false), settings = pending)
        assertFalse(vm.uiState.value.settled)
        assertFalse(vm.uiState.value.showsScore)
        runCurrent()
        assertFalse(vm.uiState.value.settled)
        assertFalse(vm.uiState.value.showsScore)

        pending.emit(ScoreSettings(mode = ScoresMode.HIDE_UNTIL_FOCUSED))
        runCurrent()
        assertTrue(vm.uiState.value.settled)
        assertFalse(vm.uiState.value.showsScore)
    }

    @Test
    fun `a saved game opened from Results reads as protected, so More… offers Reveal result`() = runTest(mainDispatcherRule.dispatcher) {
        // Results does not stamp its events: the page reads the protection itself.
        protection.value = SpoilerProtection(SpoilerProtection.saved(emptyList(), game, now), nowMs = now)
        val vm = viewModel(SportsLiveGameRequest(game, revealed = false))
        runCurrent()
        assertTrue(vm.uiState.value.settled)
        assertTrue(vm.uiState.value.spoilerProtected)
        assertFalse(vm.uiState.value.showsScore)
    }

    @Test
    fun `Watch later from the page hides the game, and Reveal result undoes it`() = runTest(mainDispatcherRule.dispatcher) {
        // F22: the Game page's More… offers both, worded as on Results.
        val vm = viewModel(SportsLiveGameRequest(game, revealed = true))
        vm.setVisible(true)
        runCurrent()
        assertTrue(vm.uiState.value.showsScore)
        assertFalse(vm.uiState.value.spoilerProtected)

        vm.watchLater()
        runCurrent()
        assertTrue(vm.uiState.value.spoilerProtected)
        assertFalse(vm.uiState.value.showsScore)

        vm.reveal()
        runCurrent()
        assertEquals(listOf(game.eventId), ended.map { it.eventId })
        assertFalse(vm.uiState.value.spoilerProtected)
        assertTrue(vm.uiState.value.showsScore)
    }

    @Test
    fun `an upcoming game keeps its records in the header, as its Preview lists them`() {
        val side = HeaderSide(name = "Nuggets", logo = null, record = "8-5", score = null)
        assertEquals("8-5", side.shownRecord(showsScore = false, upcoming = true))
        assertEquals(null, side.shownRecord(showsScore = false))
    }
}
