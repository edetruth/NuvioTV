package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.FinalNote
import com.nuvio.tv.core.sportslive.scores.GameSection
import com.nuvio.tv.core.sportslive.scores.GameSummary
import com.nuvio.tv.core.sportslive.scores.GameSummaryFixtures
import com.nuvio.tv.core.sportslive.scores.GameSummaryLoad
import com.nuvio.tv.core.sportslive.scores.ResultRow
import com.nuvio.tv.core.sportslive.scores.ScoreSettings
import com.nuvio.tv.core.sportslive.scores.ScoreSide
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveGameViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // Crystal Palace (home) 1–3 Liverpool (away), FT: the scoreboard event and its summary.
    private val palaceGame = EspnFixtures.events(EspnFixtures.ENG1_20261108, EspnFixtures.ENG1).first { it.eventId == "401879243" }
    private val palaceSummary = GameSummaryFixtures.summary(GameSummaryFixtures.SOCCER, EspnFixtures.ENG1, "401879243")
    private val nbaSummary = GameSummaryFixtures.summary(GameSummaryFixtures.NBA, EspnLeagueMap.NBA, "401909994")

    private val settings = MutableStateFlow(ScoreSettings())
    private val followed = MutableStateFlow<List<FollowedTeam>>(emptyList())
    private val failedHighlights = MutableStateFlow<Set<String>>(emptySet())
    // NY @ TOR with four playable ESPN clips.
    private val highlightsSummary = GameSummaryFixtures.summary(GameSummaryFixtures.NBA_VIDEOS, EspnLeagueMap.NBA, "401910002")
    private val requests = mutableListOf<String>()
    private var nextLoad: () -> GameSummaryLoad = { GameSummaryLoad.Loaded(palaceSummary, final = true) }

    private val reports = mutableListOf<String>()

    private fun TestScope.viewModel(request: SportsLiveGameRequest?) = SportsLiveGameViewModel(
        request = request,
        loadSummary = { league: EspnLeague, id: String ->
            requests += "${league.path}/$id"
            nextLoad()
        },
        settings = settings,
        followedTeams = followed,
        follow = { team -> followed.update { it + team } },
        unfollow = { team -> followed.update { list -> list.filterNot { it == team } } },
        now = { testScheduler.currentTime },
        failedHighlights = failedHighlights,
        // Scores Off's read (no feed health, no disk copy) is told apart from the normal one.
        loadSummaryScoresOff = { league: EspnLeague, id: String ->
            requests += "off ${league.path}/$id"
            nextLoad()
        },
        report = { reports += it }
    ).also { vm -> backgroundScope.launch { vm.uiState.collect {} } }

    @Test
    fun `Report a problem in the Game page's More saves one report through the reporter`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = viewModel(null)
        viewModel.reportProblem()
        runCurrent()
        assertEquals(listOf("Hold OK › More… › Report a problem (game page)"), reports)
    }

    @Test
    fun `Watch with another game shows for a live game and pairs only when pressed`() = runTest(mainDispatcherRule.dispatcher) {
        val first = com.nuvio.tv.ui.screens.sportslive.dual.dualItem("a")
        val second = com.nuvio.tv.ui.screens.sportslive.dual.dualItem("b")
        var paired = 0
        val pair = {
            paired++
            com.nuvio.tv.ui.screens.sportslive.dual.SportsLiveDualSession(first, second, listOf(first, second), null, com.nuvio.tv.domain.model.SportsLiveTab.LIVE_NOW)
        }
        val live = palaceGame.copy(state = com.nuvio.tv.core.sportslive.scores.ScoreState.IN, isFinal = false)
        val vm = viewModel(SportsLiveGameRequest(live, watchWith = pair))
        runCurrent()
        assertTrue(vm.uiState.value.offersWatchWith)
        // Nothing is picked until the button is pressed.
        assertEquals(0, paired)
        assertEquals(second, vm.watchWithSession()!!.right)
        assertEquals(1, paired)

        // A final, or a page opened elsewhere (no pairing), offers nothing.
        assertFalse(viewModel(SportsLiveGameRequest(palaceGame, watchWith = pair)).uiState.value.offersWatchWith)
        assertFalse(viewModel(SportsLiveGameRequest(live)).uiState.value.offersWatchWith)
    }

    @Test
    fun `the page tells Results when it comes on screen and leaves it (live20)`() = runTest(mainDispatcherRule.dispatcher) {
        settings.value = ScoreSettings(mode = ScoresMode.OFF)
        val shown = mutableListOf<Boolean>()
        val vm = viewModel(SportsLiveGameRequest(palaceGame, onShown = { shown += it }))
        vm.setVisible(true)
        vm.setVisible(false)
        assertEquals(listOf(true, false), shown)
        // A page opened elsewhere tells no one.
        viewModel(SportsLiveGameRequest(palaceGame)).apply { setVisible(true); setVisible(false) }
        assertEquals(listOf(true, false), shown)
    }

    @Test
    fun `Scores Off - nothing is requested until Reveal, and nothing shows`() = runTest(mainDispatcherRule.dispatcher) {
        settings.value = ScoreSettings(mode = ScoresMode.OFF)
        val vm = viewModel(SportsLiveGameRequest(palaceGame))
        vm.setVisible(true)
        advanceTimeBy(120_000); runCurrent()
        assertTrue(requests.isEmpty())
        assertEquals(GameLoadStatus.WAITING_REVEAL, vm.uiState.value.status)
        assertTrue(vm.uiState.value.sections.isEmpty())

        vm.reveal()
        runCurrent()
        // live.20: Reveal under Off reads the summary without feed health or a disk copy.
        assertEquals(listOf("off soccer/eng.1/401879243"), requests)
        assertEquals(GameLoadStatus.READY, vm.uiState.value.status)
        assertTrue(vm.uiState.value.showsScore)
        assertEquals(listOf(GameSection.GAME, GameSection.STANDINGS), vm.uiState.value.sections)
    }

    @Test
    fun `Reveal under Hide until focused reads the summary the normal way`() = runTest(mainDispatcherRule.dispatcher) {
        settings.value = ScoreSettings(mode = ScoresMode.HIDE_UNTIL_FOCUSED)
        val vm = viewModel(SportsLiveGameRequest(palaceGame, revealed = false))
        vm.setVisible(true)
        vm.reveal()
        runCurrent()
        assertTrue(requests.isNotEmpty())
        assertTrue("$requests", requests.all { it == "soccer/eng.1/401879243" })
        vm.setVisible(false)
    }

    @Test
    fun `hide until focused - a row already revealed opens revealed, otherwise score and sections wait for Reveal`() = runTest(mainDispatcherRule.dispatcher) {
        settings.value = ScoreSettings(mode = ScoresMode.HIDE_UNTIL_FOCUSED)
        val hidden = viewModel(SportsLiveGameRequest(palaceGame, revealed = false))
        hidden.setVisible(true)
        runCurrent()
        assertTrue(!hidden.uiState.value.showsScore)
        // Empty and hidden: no section shows before Reveal.
        assertTrue(hidden.uiState.value.sections.isEmpty())
        hidden.reveal()
        runCurrent()
        assertEquals(GameSection.GAME, hidden.uiState.value.section)

        val revealed = viewModel(SportsLiveGameRequest(palaceGame, revealed = true))
        revealed.setVisible(true)
        runCurrent()
        assertTrue(revealed.uiState.value.showsScore)
    }

    @Test
    fun `nothing loads while the page is not visible`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(SportsLiveGameRequest(palaceGame))
        runCurrent()
        assertTrue(requests.isEmpty())
        vm.setVisible(true)
        runCurrent()
        assertEquals(1, requests.size)
        // A final is never refreshed.
        advanceTimeBy(10 * 60_000L); runCurrent()
        assertEquals(1, requests.size)
    }

    @Test
    fun `a live game refreshes every 30 s while visible, and stops when the page is not`() = runTest(mainDispatcherRule.dispatcher) {
        val live = liveNba()
        nextLoad = { GameSummaryLoad.Loaded(live, final = false) }
        val vm = viewModel(SportsLiveGameRequest(liveEvent()))
        vm.setVisible(true)
        runCurrent()
        assertEquals(1, requests.size)
        advanceTimeBy(SportsLiveGameViewModel.REFRESH_MS); runCurrent()
        assertEquals(2, requests.size)
        vm.setVisible(false)
        advanceTimeBy(5 * SportsLiveGameViewModel.REFRESH_MS); runCurrent()
        assertEquals(2, requests.size)
    }

    @Test
    fun `score delay - a live game shows each summary only once it is the delay old`() = runTest(mainDispatcherRule.dispatcher) {
        settings.value = ScoreSettings(delaySeconds = 30)
        val first = liveNba()
        val second = first.copy(clockText = "Q4 1:00")
        var calls = 0
        nextLoad = { GameSummaryLoad.Loaded(if (calls++ == 0) first else second, final = false) }
        val vm = viewModel(SportsLiveGameRequest(liveEvent()))
        vm.setVisible(true)
        runCurrent()
        assertNull(vm.uiState.value.summary)
        advanceTimeBy(29_999); runCurrent()
        assertNull(vm.uiState.value.summary)
        advanceTimeBy(1); runCurrent()
        // Due now: the first summary; the second (fetched now) waits its own 30 s.
        assertSame(first, vm.uiState.value.summary)
        advanceTimeBy(30_000); runCurrent()
        assertSame(second, vm.uiState.value.summary)
        // Leaving stops the 30 s refresh (the test then ends with nothing scheduled).
        vm.setVisible(false)
        runCurrent()
    }

    @Test
    fun `opened right after kick-off - the card still says scheduled but the live summary waits out the delay`() = runTest(mainDispatcherRule.dispatcher) {
        settings.value = ScoreSettings(delaySeconds = 60)
        nextLoad = { GameSummaryLoad.Loaded(liveNba(), final = false) }
        // The delayed scoreboard still shows the game as scheduled.
        val vm = viewModel(SportsLiveGameRequest(liveEvent().copy(state = ScoreState.PRE)))
        vm.setVisible(true)
        runCurrent()
        assertNull(vm.uiState.value.summary)
        advanceTimeBy(59_999); runCurrent()
        assertNull(vm.uiState.value.summary)
        advanceTimeBy(1); runCurrent()
        assertEquals(ScoreState.IN, vm.uiState.value.summary?.state)
        vm.setVisible(false)
        runCurrent()
    }

    @Test
    fun `a game that ends while the page is open holds its final for the delay too`() = runTest(mainDispatcherRule.dispatcher) {
        settings.value = ScoreSettings(delaySeconds = 30)
        var calls = 0
        nextLoad = {
            if (calls++ == 0) GameSummaryLoad.Loaded(liveNba(), final = false) else GameSummaryLoad.Loaded(nbaSummary, final = true)
        }
        val vm = viewModel(SportsLiveGameRequest(liveEvent()))
        vm.setVisible(true)
        runCurrent()
        // At 30 s the live summary is due and the final is fetched; the final waits its own 30 s.
        advanceTimeBy(30_000); runCurrent()
        assertEquals(ScoreState.IN, vm.uiState.value.summary?.state)
        advanceTimeBy(29_999); runCurrent()
        assertEquals(ScoreState.IN, vm.uiState.value.summary?.state)
        advanceTimeBy(1); runCurrent()
        assertSame(nbaSummary, vm.uiState.value.summary)
    }

    @Test
    fun `a final opened with a delay set shows at once - the delay is for live games`() = runTest(mainDispatcherRule.dispatcher) {
        settings.value = ScoreSettings(delaySeconds = 90)
        val vm = viewModel(SportsLiveGameRequest(palaceGame))
        vm.setVisible(true)
        runCurrent()
        assertSame(palaceSummary, vm.uiState.value.summary)
    }

    @Test
    fun `a failed load says so`() = runTest(mainDispatcherRule.dispatcher) {
        nextLoad = { GameSummaryLoad.Failed }
        val vm = viewModel(SportsLiveGameRequest(palaceGame))
        vm.setVisible(true)
        runCurrent()
        assertEquals(GameLoadStatus.FAILED, vm.uiState.value.status)
    }

    @Test
    fun `Follow and Following - writes the My Teams list, OK again unfollows`() = runTest(mainDispatcherRule.dispatcher) {
        followed.value = listOf(FollowedTeam("Arsenal", "Soccer"))
        val vm = viewModel(SportsLiveGameRequest(palaceGame))
        runCurrent()
        vm.toggleFollow(ScoreSide.AWAY)
        runCurrent()
        val liverpool = followed.value.last()
        assertEquals("Liverpool", liverpool.name)
        assertEquals("Soccer", liverpool.sport)
        assertEquals(palaceGame.away.id, liverpool.espnId)
        assertTrue(vm.uiState.value.awayFollowed)
        assertTrue(!vm.uiState.value.homeFollowed)

        vm.toggleFollow(ScoreSide.AWAY)
        runCurrent()
        assertEquals(listOf(FollowedTeam("Arsenal", "Soccer")), followed.value)
        assertTrue(!vm.uiState.value.awayFollowed)

        // A name-only follow of the same team shows as Following, and OK removes it.
        followed.value = listOf(FollowedTeam("Crystal Palace", "Football"))
        runCurrent()
        assertTrue(vm.uiState.value.homeFollowed)
        vm.toggleFollow(ScoreSide.HOME)
        runCurrent()
        assertTrue(followed.value.isEmpty())
    }

    @Test
    fun `sections - the chosen one sticks, an empty one is never offered`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(SportsLiveGameRequest(palaceGame))
        vm.setVisible(true)
        runCurrent()
        vm.selectSection(GameSection.STANDINGS)
        runCurrent()
        assertEquals(GameSection.STANDINGS, vm.uiState.value.section)
        // Soccer has no box score here: choosing it falls back to the first section shown.
        vm.selectSection(GameSection.BOX_SCORE)
        runCurrent()
        assertEquals(GameSection.GAME, vm.uiState.value.section)
    }

    @Test
    fun `a page opened for its box score opens on the Box Score tab when it has one`() = runTest(mainDispatcherRule.dispatcher) {
        nextLoad = { GameSummaryLoad.Loaded(nbaSummary, final = true) }
        val vm = viewModel(SportsLiveGameRequest(palaceGame, section = GameSection.BOX_SCORE))
        vm.setVisible(true)
        runCurrent()
        assertTrue(GameSection.BOX_SCORE in vm.uiState.value.sections)
        assertEquals(GameSection.BOX_SCORE, vm.uiState.value.section)

        // A summary with no box score opens on its first section.
        nextLoad = { GameSummaryLoad.Loaded(palaceSummary, final = true) }
        val soccer = viewModel(SportsLiveGameRequest(palaceGame, section = GameSection.BOX_SCORE))
        soccer.setVisible(true)
        runCurrent()
        assertEquals(GameSection.GAME, soccer.uiState.value.section)
    }

    @Test
    fun `Results OK opens the Game page with the row's strict replay and its reveal state`() {
        val row = ResultRow(palaceGame, followed = false, note = FinalNote.FT)
        val replay = replayItem()
        val state = SportsLiveResultsUiState(mode = ScoresMode.HIDE_UNTIL_FOCUSED, revealed = setOf(row.key), replays = mapOf(row.key to replay))
        assertEquals(SportsLiveGameRequest(palaceGame, replay = replay, revealed = true), state.gameRequestFor(row))
        // No replay match and not revealed: the page opens without Watch replay, score hidden.
        val plain = SportsLiveResultsUiState(mode = ScoresMode.HIDE_UNTIL_FOCUSED)
        assertEquals(SportsLiveGameRequest(palaceGame, replay = null, revealed = false), plain.gameRequestFor(row))
        assertEquals(true, SportsLiveResultsUiState().gameRequestFor(row).revealed)
    }

    @Test
    fun `highlights - a clip that failed is hidden, and with none left the tab goes`() = runTest(mainDispatcherRule.dispatcher) {
        nextLoad = { GameSummaryLoad.Loaded(highlightsSummary, final = true) }
        val vm = viewModel(SportsLiveGameRequest(palaceGame))
        vm.setVisible(true)
        runCurrent()
        vm.selectSection(GameSection.HIGHLIGHTS)
        runCurrent()
        assertEquals(GameSection.HIGHLIGHTS, vm.uiState.value.section)
        assertEquals(4, vm.uiState.value.summary!!.highlights.size)

        failedHighlights.value = setOf("50516726")
        runCurrent()
        assertEquals(listOf("50515367", "50515447", "50515574"), vm.uiState.value.summary!!.highlights.map { it.id })
        assertEquals(GameSection.HIGHLIGHTS, vm.uiState.value.section)

        failedHighlights.value = highlightsSummary.highlights.map { it.id }.toSet()
        runCurrent()
        assertTrue(vm.uiState.value.summary!!.highlights.isEmpty())
        // This trimmed summary has nothing else: no section is left.
        assertTrue(vm.uiState.value.sections.isEmpty())
        assertNull(vm.uiState.value.section)
    }

    @Test
    fun `highlights wait for Reveal under Hide until focused and Off - clip titles give results away`() = runTest(mainDispatcherRule.dispatcher) {
        nextLoad = { GameSummaryLoad.Loaded(highlightsSummary, final = true) }
        for (mode in listOf(ScoresMode.HIDE_UNTIL_FOCUSED, ScoresMode.OFF)) {
            settings.value = ScoreSettings(mode = mode)
            val vm = viewModel(SportsLiveGameRequest(palaceGame))
            vm.setVisible(true)
            vm.selectSection(GameSection.HIGHLIGHTS)
            runCurrent()
            assertTrue(vm.uiState.value.sections.isEmpty())
            assertNull(vm.uiState.value.section)
            vm.reveal()
            runCurrent()
            assertEquals(GameSection.HIGHLIGHTS, vm.uiState.value.section)
        }
        // Show: there at once.
        settings.value = ScoreSettings(mode = ScoresMode.SHOW)
        val shown = viewModel(SportsLiveGameRequest(palaceGame))
        shown.setVisible(true)
        runCurrent()
        assertTrue(GameSection.HIGHLIGHTS in shown.uiState.value.sections)
    }

    @Test
    fun `no request at all, the page closes`() = runTest(mainDispatcherRule.dispatcher) {
        val vm = viewModel(null)
        vm.setVisible(true)
        runCurrent()
        assertNull(vm.uiState.value.event)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `stat bars - shares follow the values, never vanish, even when missing`() {
        assertEquals(41f to 47f, statBarWeights(41.0, 47.0))
        assertEquals(1f to 1f, statBarWeights(null, 3.0))
        assertEquals(1f to 1f, statBarWeights(0.0, 0.0))
        val (zero, three) = statBarWeights(0.0, 3.0)
        assertTrue(zero > 0f && three == 3f)
    }

    private fun liveNba(): GameSummary = nbaSummary.copy(state = ScoreState.IN, isFinal = false, clockText = "Q3 8:42")

    private fun liveEvent(): ScoreSnapshot = palaceGame.copy(
        eventId = "401909994",
        league = EspnLeagueMap.NBA,
        leagueSlug = "nba",
        state = ScoreState.IN,
        isFinal = false
    )

    private fun replayItem() = SportsLiveItem(
        meta = MetaPreview(
            id = "r", type = ContentType.UNKNOWN, rawType = "sport", name = "Crystal Palace vs Liverpool", poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Replays",
        kind = SportsLiveKind.REPLAY, isLive = false, isChannel = false, startMs = null, sport = "Soccer", teams = null
    )
}
