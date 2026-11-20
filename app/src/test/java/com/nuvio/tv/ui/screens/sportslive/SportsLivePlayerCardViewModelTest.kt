package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.sportslive.scores.EspnAthleteResponseDto
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnGameLogDto
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.GameSummaryFixtures
import com.nuvio.tv.core.sportslive.scores.PlayerCard
import com.nuvio.tv.core.sportslive.scores.PlayerCardTarget
import com.nuvio.tv.core.sportslive.scores.PlayerCards
import com.nuvio.tv.core.sportslive.scores.ScoreSettings
import com.nuvio.tv.core.sportslive.scores.ScoreSide
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.TeamLeader
import com.nuvio.tv.core.sportslive.scores.TeamRef
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
class SportsLivePlayerCardViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val embiid: PlayerCard = PlayerCards.parse(
        EspnFixtures.moshi.adapter(EspnAthleteResponseDto::class.java).fromJson(EspnFixtures.json("espn_athlete_nba_3059318.json"))!!,
        EspnFixtures.moshi.adapter(EspnGameLogDto::class.java).fromJson(EspnFixtures.json("espn_gamelog_nba_3059318.json")),
        "basketball"
    )!!
    private val target = PlayerCardTarget(EspnLeagueMap.NBA, "3059318", "J. Embiid", "Philadelphia 76ers")
    private val settings = MutableStateFlow(ScoreSettings())
    private val requests = mutableListOf<String>()
    private var nextCard: PlayerCard? = embiid

    private fun viewModel(request: SportsLivePlayerCardRequest? = SportsLivePlayerCardRequest(target)) =
        SportsLivePlayerCardViewModel(
            request = request,
            loadCard = { league: EspnLeague, id: String ->
                requests += "${league.path}/$id"
                nextCard
            },
            settings = settings
        )

    @Test
    fun `Scores Show - requested on open, everything shows, no Reveal`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        runCurrent()
        assertEquals(listOf("basketball/nba/3059318"), requests)
        val state = vm.uiState.value
        assertEquals(PlayerCardStatus.READY, state.status)
        assertEquals(embiid, state.card)
        assertTrue(state.showsLastGames)
        assertFalse(state.showsReveal)
    }

    @Test
    fun `Scores Off - nothing is requested until Reveal, then everything shows`() = runTest(mainDispatcherRule.dispatcher) {
        settings.value = ScoreSettings(mode = ScoresMode.OFF)
        val vm = viewModel()
        advanceTimeBy(120_000); runCurrent()
        assertTrue(requests.isEmpty())
        assertEquals(PlayerCardStatus.WAITING_REVEAL, vm.uiState.value.status)
        assertNull(vm.uiState.value.card)
        assertTrue(vm.uiState.value.showsReveal)
        // The header still has the name and team the card was opened with.
        assertEquals("J. Embiid", vm.uiState.value.target!!.name)

        vm.reveal()
        runCurrent()
        assertEquals(listOf("basketball/nba/3059318"), requests)
        assertEquals(PlayerCardStatus.READY, vm.uiState.value.status)
        assertTrue(vm.uiState.value.showsLastGames)
        assertFalse(vm.uiState.value.showsReveal)
    }

    @Test
    fun `Hide until focused - season stats show, each last game shows while its row has focus`() = runTest(mainDispatcherRule.dispatcher) {
        settings.value = ScoreSettings(mode = ScoresMode.HIDE_UNTIL_FOCUSED)
        val vm = viewModel()
        runCurrent()
        assertEquals(1, requests.size)
        assertEquals(PlayerCardStatus.READY, vm.uiState.value.status)
        assertEquals(embiid.season, vm.uiState.value.card!!.season)
        assertFalse(vm.uiState.value.showsLastGames)
        // No Reveal button: focus reveals one row at a time (#134).
        assertFalse(vm.uiState.value.showsReveal)
        assertTrue(vm.uiState.value.revealsOnFocus)
        assertFalse(vm.uiState.value.showsGame(focused = false))
        assertTrue(vm.uiState.value.showsGame(focused = true))

        vm.reveal()
        runCurrent()
        assertTrue(vm.uiState.value.showsLastGames)
        // Reveal requests nothing more.
        assertEquals(1, requests.size)
    }

    @Test
    fun `opened from a spoiler-safe Game page - the last games wait for Reveal even under Show`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(SportsLivePlayerCardRequest(target, spoilerSafe = true))
        runCurrent()
        assertFalse(vm.uiState.value.showsLastGames)
        assertTrue(vm.uiState.value.showsReveal)
        assertFalse(vm.uiState.value.showsGame(focused = true))
        vm.reveal()
        runCurrent()
        assertTrue(vm.uiState.value.showsLastGames)
    }

    @Test
    fun `a failure shows unavailable, without Reveal`() = runTest(mainDispatcherRule.dispatcher) {
        settings.value = ScoreSettings(mode = ScoresMode.HIDE_UNTIL_FOCUSED)
        nextCard = null
        val vm = viewModel()
        runCurrent()
        assertEquals(PlayerCardStatus.FAILED, vm.uiState.value.status)
        assertFalse(vm.uiState.value.showsReveal)
    }

    @Test
    fun `turning Scores Off after the card loaded requests nothing more`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel()
        runCurrent()
        settings.value = ScoreSettings(mode = ScoresMode.OFF)
        runCurrent()
        assertEquals(1, requests.size)
        assertFalse(vm.uiState.value.showsLastGames)
    }

    @Test
    fun `no request - nothing loads`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(request = null)
        runCurrent()
        assertNull(vm.uiState.value.target)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `Game page - a top performer opens a card on a pro league, never on a college one`() {
        val nba = GameSummaryFixtures.summary(GameSummaryFixtures.NBA, EspnLeagueMap.NBA, "401909994")
        val event = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA).first()
        val state = SportsLiveGameUiState(event = event, summary = nba)
        val targets = nba.performers.map(gamePlayerTarget(state))
        assertTrue(targets.all { it != null })
        val tatum = targets.first { it!!.name == "J. Tatum" }!!
        assertEquals("4065648", tatum.athleteId)
        val celtics = if (nba.home.short == "BOS") nba.home else nba.away
        assertEquals(celtics.fullName, tatum.team)

        val college = SportsLiveGameUiState(event = event, summary = nba.copy(league = EspnLeagueMap.COLLEGE_BASKETBALL))
        assertTrue(nba.performers.map(gamePlayerTarget(college)).all { it == null })
        // Not loaded yet: nothing to open.
        assertTrue(nba.performers.map(gamePlayerTarget(SportsLiveGameUiState(event = event))).all { it == null })
    }

    @Test
    fun `Game page - a Box score row opens its player's card, with the side's team and the league`() {
        val nba = GameSummaryFixtures.summary(GameSummaryFixtures.NBA, EspnLeagueMap.NBA, "401909994")
        val event = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA).first()
        val state = SportsLiveGameUiState(event = event, summary = nba)
        val boston = nba.boxScore.first { it.team == "BOS" }
        val tatumRow = boston.tables.single().rows.first { it.player == "J. Tatum" }
        val celtics = if (boston.side == ScoreSide.HOME) nba.home else nba.away
        assertEquals(
            PlayerCardTarget(EspnLeagueMap.NBA, "4065648", "J. Tatum", celtics.fullName),
            gameBoxScoreTarget(state)(boston.side, tatumRow)
        )
        // Every played row of this game opens a card.
        assertTrue(nba.boxScore.all { team -> team.tables.all { table -> table.rows.all { gameBoxScoreTarget(state)(team.side, it) != null } } })

        // No athlete id, a college league, or no summary yet: no card.
        assertNull(gameBoxScoreTarget(state)(boston.side, tatumRow.copy(athleteId = null)))
        assertNull(gameBoxScoreTarget(state)(boston.side, tatumRow.copy(athleteId = " ")))
        val college = SportsLiveGameUiState(event = event, summary = nba.copy(league = EspnLeagueMap.COLLEGE_BASKETBALL))
        assertNull(gameBoxScoreTarget(college)(boston.side, tatumRow))
        assertNull(gameBoxScoreTarget(SportsLiveGameUiState(event = event))(boston.side, tatumRow))
    }

    @Test
    fun `Player card request - a Box score row, a performer and a leader each carry the athlete and league`() {
        val nba = GameSummaryFixtures.summary(GameSummaryFixtures.NBA, EspnLeagueMap.NBA, "401909994")
        val event = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA).first()
        val state = SportsLiveGameUiState(event = event, summary = nba, spoilerSafe = true)
        val row = nba.boxScore.first().tables.single().rows.first()
        val fromRow = SportsLivePlayerCardRequest(gameBoxScoreTarget(state)(nba.boxScore.first().side, row)!!, spoilerSafe = state.spoilerSafe)
        assertEquals(row.athleteId, fromRow.target.athleteId)
        assertEquals(EspnLeagueMap.NBA, fromRow.target.league)
        // A Game page opened spoiler-safe keeps the card's last games hidden.
        assertTrue(fromRow.spoilerSafe)

        val performer = nba.performers.first()
        val fromPerformer = gamePlayerTarget(state)(performer)!!
        assertEquals(performer.athleteId, fromPerformer.athleteId)
        assertEquals(EspnLeagueMap.NBA, fromPerformer.league)

        val leader = TeamLeader("Points", "T. Maxey", "G", "26.5", athleteId = "4431678")
        val sixers = TeamRef(EspnLeagueMap.NBA, "20", event, ScoreSide.HOME)
        assertEquals(PlayerCardTarget(EspnLeagueMap.NBA, "4431678", "T. Maxey", sixers.name), PlayerCards.targetFor(leader, sixers))
        assertNull(PlayerCards.targetFor(leader.copy(athleteId = null), sixers))
    }
}
