package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.sportslive.alerts.GameReminders
import com.nuvio.tv.core.sportslive.alerts.RemindersState
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.PlayerCardTarget
import com.nuvio.tv.core.sportslive.scores.ProtectedGame
import com.nuvio.tv.core.sportslive.scores.ScoreSettings
import com.nuvio.tv.core.sportslive.scores.ScoreSide
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.SpoilerProtection
import com.nuvio.tv.core.sportslive.scores.TeamFixtures
import com.nuvio.tv.core.sportslive.scores.TeamPageData
import com.nuvio.tv.core.sportslive.scores.TeamPageLoad
import com.nuvio.tv.core.sportslive.scores.TeamPages
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveTeamViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // Pacers @ 76ers (2026-11-15): the 76ers' page, opened from that game.
    private val game = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA).first { it.eventId == "401909985" }
    private val sixers = TeamPages.refOf(game, ScoreSide.HOME)!!

    private val schedule = TeamPages.snapshots(TeamFixtures.schedule(TeamFixtures.NBA_SCHEDULE).events!!, EspnLeagueMap.NBA)
    private val page = TeamPageData(
        header = TeamPages.header(TeamFixtures.team(TeamFixtures.NBA_TEAM), null)!!,
        recent = TeamPages.recent(schedule),
        upcoming = TeamPages.upcoming(schedule, TeamFixtures.NOW),
        leaders = emptyList()
    )

    private val settings = MutableStateFlow(ScoreSettings())
    private val followed = MutableStateFlow<List<FollowedTeam>>(emptyList())
    private val items = MutableStateFlow<List<SportsLiveItem>>(emptyList())
    private val requests = mutableListOf<String>()

    private fun TestScope.viewModel(request: SportsLiveTeamRequest? = SportsLiveTeamRequest(sixers)) = SportsLiveTeamViewModel(
        request = request,
        loadTeam = { league: EspnLeague, id: String ->
            requests += "${league.path}/$id"
            TeamPageLoad.Loaded(page)
        },
        settings = settings,
        followedTeams = followed,
        follow = { team -> followed.update { it + team } },
        unfollow = { team -> followed.update { list -> list.filterNot { it == team } } },
        items = items,
        now = { TeamFixtures.NOW }
    ).also { vm -> backgroundScope.launch { vm.uiState.collect {} } }

    @Test
    fun `Scores Off - nothing is requested until Reveal, then everything shows`() = runTest(mainDispatcherRule.dispatcher) {
        settings.value = ScoreSettings(mode = ScoresMode.OFF)
        val vm = viewModel()
        vm.setVisible(true)
        advanceTimeBy(60_000); runCurrent()
        assertTrue(requests.isEmpty())
        assertEquals(GameLoadStatus.WAITING_REVEAL, vm.uiState.value.status)
        assertFalse(vm.uiState.value.showsRecord)
        assertFalse(vm.uiState.value.showsResults)

        vm.reveal()
        runCurrent()
        assertEquals(listOf("basketball/nba/${sixers.teamId}"), requests)
        assertEquals(GameLoadStatus.READY, vm.uiState.value.status)
        assertTrue(vm.uiState.value.showsRecord)
        assertTrue(vm.uiState.value.showsResults)
    }

    @Test
    fun `Hide until focused - record and standing show, the last results wait for Reveal`() = runTest(mainDispatcherRule.dispatcher) {
        settings.value = ScoreSettings(mode = ScoresMode.HIDE_UNTIL_FOCUSED)
        val vm = viewModel()
        vm.setVisible(true)
        runCurrent()
        assertEquals(1, requests.size)
        assertTrue(vm.uiState.value.showsRecord)
        assertFalse(vm.uiState.value.showsResults)
        vm.reveal()
        runCurrent()
        assertTrue(vm.uiState.value.showsResults)
        // Reveal does not load again.
        assertEquals(1, requests.size)
    }

    @Test
    fun `it loads only while on screen, once per open`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        runCurrent()
        assertTrue(requests.isEmpty())
        vm.setVisible(true); runCurrent()
        vm.setVisible(false); runCurrent()
        vm.setVisible(true); runCurrent()
        assertEquals(1, requests.size)
        assertEquals(page, vm.uiState.value.data)
    }

    @Test
    fun `no request - no page`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(request = null)
        vm.setVisible(true); runCurrent()
        assertNull(vm.uiState.value.team)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `Follow stores the team with ESPN's id and league, and Following unfollows it`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        vm.toggleFollow(); runCurrent()
        val team = followed.value.single()
        assertEquals("Philadelphia 76ers", team.name)
        assertEquals(sixers.teamId, team.espnId)
        assertEquals("basketball/nba", team.league)
        assertTrue(vm.uiState.value.followed)

        vm.toggleFollow(); runCurrent()
        assertTrue(followed.value.isEmpty())
        assertFalse(vm.uiState.value.followed)
    }

    @Test
    fun `the Replay pill shows only on a strict replay match`() = runTest(mainDispatcherRule.dispatcher) {
        val strict = TeamFixtures.replay("Indiana Pacers vs Philadelphia 76ers", "2026-11-15T18:30:00Z")
        val loose = TeamFixtures.replay("Pacers vs Sixers highlights", "2026-11-13T00:30:00Z")
        items.value = listOf(strict, loose)
        val vm = viewModel()
        vm.setVisible(true); runCurrent()
        assertEquals(mapOf("401909985" to strict), vm.uiState.value.replays)
    }

    // Tonight's game at Orlando (2026-11-19 00:00Z), as an addon lists it.
    private val orlandoCard = TeamFixtures.replay("Philadelphia 76ers vs Orlando Magic", "2026-11-19T00:00:00Z", kind = SportsLiveKind.EVENTS)

    @Test
    fun `an upcoming game with a strictly matched card offers Remind me, and following the team reminds it automatically`() = runTest(mainDispatcherRule.dispatcher) {
        items.value = listOf(orlandoCard)
        val vm = viewModel()
        vm.setVisible(true); runCurrent()
        val card = vm.uiState.value.cards["401910010"]!!
        assertEquals(orlandoCard.streamKey, card.streamKey)
        assertEquals(UpcomingAction.REMIND, upcomingAction(page.upcoming.first(), card, TeamFixtures.NOW))
        assertTrue(GameReminders.canRemind(card, TeamFixtures.NOW))
        val reminders = RemindersState(remindMyTeams = true)
        assertFalse(reminders.isReminded(card))
        // A reminder set by hand is found again on the page's card.
        assertTrue(GameReminders.toggle(reminders, card, TeamFixtures.NOW).isReminded(card))

        vm.toggleFollow(); runCurrent()
        val followedCard = vm.uiState.value.cards.getValue("401910010")
        assertTrue(followedCard.followed)
        assertTrue(reminders.isReminded(followedCard))
        assertFalse(reminders.copy(remindMyTeams = false).isReminded(followedCard))
    }

    @Test
    fun `a live game with a card is watched - one without a card opens its Game page`() {
        val tonight = page.upcoming.first().copy(state = ScoreState.IN)
        val live = orlandoCard.copy(isLive = true)
        assertEquals(UpcomingAction.WATCH, upcomingAction(tonight, live, TeamFixtures.NOW))
        assertEquals(UpcomingAction.GAME_PAGE, upcomingAction(tonight, null, TeamFixtures.NOW))
    }

    @Test
    fun `a card naming only one of the teams is never the game's card`() {
        val wrong = TeamFixtures.replay("Philadelphia 76ers vs Boston Celtics", "2026-11-19T00:00:00Z", kind = SportsLiveKind.EVENTS)
        assertTrue(teamPageCards(page.upcoming, listOf(wrong), followed = false, nowMs = TeamFixtures.NOW).isEmpty())
    }

    // --- G5, G6: a protected last result is not given away by the header or a Player card ---

    private fun pageWithLatestSaved(mode: ScoresMode, revealed: Boolean = false): SportsLiveTeamUiState {
        val latest = page.recent.first()
        val saved = ProtectedGame(latest.eventId, home = null, away = null, startMs = latest.startMs, savedAtMs = TeamFixtures.NOW)
        return SportsLiveTeamUiState(
            team = sixers, data = page, status = GameLoadStatus.READY, mode = mode, revealed = revealed,
            protection = SpoilerProtection(games = listOf(saved), nowMs = TeamFixtures.NOW)
        )
    }

    @Test
    fun `the header record and streak hide while a last result is protected, whatever the mode or Reveal`() {
        assertTrue(page.header.streak != null || page.header.record != null)
        val open = SportsLiveTeamUiState(team = sixers, data = page, status = GameLoadStatus.READY, mode = ScoresMode.SHOW)
        assertFalse(open.hasProtectedResult)
        assertTrue(open.showsRecordLine)

        for (mode in listOf(ScoresMode.SHOW, ScoresMode.HIDE_UNTIL_FOCUSED, ScoresMode.OFF)) {
            for (revealed in listOf(false, true)) {
                val state = pageWithLatestSaved(mode, revealed)
                assertTrue(state.hasProtectedResult)
                assertFalse("$mode revealed=$revealed", state.showsRecordLine)
            }
        }
        // Upcoming games and leaders are not results: they still show.
        assertTrue(pageWithLatestSaved(ScoresMode.SHOW).showsRecord)
    }

    @Test
    fun `a Player card opened from a Team page with a protected result is spoiler-safe and not revealed`() {
        val target = PlayerCardTarget(EspnLeagueMap.NBA, "3059318", "J. Embiid", sixers.name)

        val protectedRequest = teamPlayerCardRequest(target, pageWithLatestSaved(ScoresMode.SHOW, revealed = true))
        assertTrue(protectedRequest.spoilerSafe)
        assertFalse(protectedRequest.revealed)
        // The card then keeps its last games behind Reveal even under Scores Show.
        val card = SportsLivePlayerCardUiState(target = target, mode = ScoresMode.SHOW, spoilerSafe = protectedRequest.spoilerSafe, revealed = protectedRequest.revealed)
        assertFalse(card.showsGame(focused = true))
        assertTrue(card.showsReveal)

        // Nothing protected: the card inherits the page's shown results (BUG19).
        val open = SportsLiveTeamUiState(team = sixers, data = page, status = GameLoadStatus.READY, mode = ScoresMode.SHOW)
        assertEquals(SportsLivePlayerCardRequest(target, spoilerSafe = false, revealed = true), teamPlayerCardRequest(target, open))
    }

    @Test
    fun `a Team page from a Replays card menu is spoiler-safe, from Live now as before`() {
        assertEquals(SportsLiveTeamRequest(sixers, spoilerSafe = true), sportsLiveCardTeamRequest(sixers, SportsLiveTab.REPLAYS))
        assertEquals(SportsLiveTeamRequest(sixers, spoilerSafe = false), sportsLiveCardTeamRequest(sixers, SportsLiveTab.LIVE_NOW))
    }
}
