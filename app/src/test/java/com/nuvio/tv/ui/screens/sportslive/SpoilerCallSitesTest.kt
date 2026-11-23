package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.FinalNote
import com.nuvio.tv.core.sportslive.scores.GameSituation
import com.nuvio.tv.core.sportslive.scores.GameSituations
import com.nuvio.tv.core.sportslive.scores.GameSummaryLoad
import com.nuvio.tv.core.sportslive.scores.PlayerCard
import com.nuvio.tv.core.sportslive.scores.PlayerCardTarget
import com.nuvio.tv.core.sportslive.scores.ResultRow
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.core.sportslive.scores.ScoreChipStrings
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreSettings
import com.nuvio.tv.core.sportslive.scores.ScoreSide
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.SpoilerProtection
import com.nuvio.tv.core.sportslive.scores.TeamPageLoad
import com.nuvio.tv.core.sportslive.scores.TeamPageSpoilers
import com.nuvio.tv.core.sportslive.scores.TeamPages
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.ui.screens.home.SportsLiveHomeRow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * #117 step 1: the spoiler rule as every surface applies it today (l26-rc), pinned cell by cell before the surfaces
 * move to one policy. Each expected value below is written out per surface, so a refactor that changes any cell fails
 * here. Where two surfaces differ today the difference is pinned, not fixed (see the notes on each test).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpoilerCallSitesTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val modes = ScoresMode.entries
    private val bools = listOf(false, true)

    // IND @ PHI: home Philadelphia 76ers 131, away Indiana Pacers 124 (Final).
    private val pacers = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA).first { it.eventId == "401909985" }
    private val now = pacers.startMs!! + 4 * 60 * 60_000L

    private val strings = ScoreChipStrings(
        score = "%1\$d–%2\$d",
        live = "%1\$s · %2\$s",
        final = "%1\$s %2\$s",
        finalNote = "Final",
        postponed = "Postponed",
        hidden = "Score hidden"
    )

    private val card = SportsLiveItem(
        meta = MetaPreview(
            id = "76ers vs Pacers", type = ContentType.UNKNOWN, rawType = "sport", name = "76ers vs Pacers", poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false,
        startMs = null, sport = "Basketball", teams = SportsLiveTeams.parse("76ers vs Pacers")
    )

    /** The game in each state a chip reads: scheduled, live (with a situation), final, postponed, unknown. */
    private fun inState(state: ScoreState): ScoreSnapshot = when (state) {
        ScoreState.PRE -> pacers.copy(state = state, isFinal = false, clockText = "7:00 PM", home = pacers.home.copy(score = null), away = pacers.away.copy(score = null))
        ScoreState.IN -> pacers.copy(
            state = state, isFinal = false, clockText = "Q3 4:12", home = pacers.home.copy(score = 88), away = pacers.away.copy(score = 80),
            situation = GameSituation(possession = ScoreSide.HOME, outs = 1)
        )
        ScoreState.POST -> pacers
        ScoreState.OFF -> pacers.copy(state = state, isFinal = false, clockText = "Postponed")
        ScoreState.UNKNOWN -> pacers.copy(state = state, isFinal = false, clockText = null)
    }

    private fun kind(chip: ScoreChip?): String = when (chip) {
        null -> "none"
        is ScoreChip.Live -> "live"
        is ScoreChip.Final -> "final"
        is ScoreChip.Status -> "status"
        ScoreChip.Hidden -> "hidden"
    }

    // ---- Game page ----

    @Test
    fun `Game page - score, records and stats show under Show unless spoiler-safe, or once revealed, never protected or unsettled`() {
        for (mode in modes) for (revealed in bools) for (safe in bools) for (protected in bools) for (settled in bools) {
            val state = SportsLiveGameUiState(mode = mode, revealed = revealed, spoilerSafe = safe, spoilerProtected = protected, settled = settled)
            val byMode = when (mode) {
                ScoresMode.SHOW -> !safe || revealed
                ScoresMode.HIDE_UNTIL_FOCUSED -> revealed
                // Differs from Results and the chips: Reveal on the page shows it under Never show too.
                ScoresMode.OFF -> revealed
            }
            assertEquals("$mode revealed=$revealed safe=$safe protected=$protected settled=$settled", settled && !protected && byMode, state.showsScore)
        }
    }

    @Test
    fun `Game page - it asks for the game unless Never show is on and Reveal not pressed`() = runTest(mainDispatcherRule.dispatcher) {
        for (mode in modes) for (revealed in bools) {
            val loads = mutableListOf<String>()
            val vm = SportsLiveGameViewModel(
                request = SportsLiveGameRequest(event = pacers, revealed = revealed),
                loadSummary = { _: EspnLeague, _: String -> loads += "summary"; GameSummaryLoad.Failed },
                settings = MutableStateFlow(ScoreSettings(mode = mode)),
                followedTeams = MutableStateFlow<List<FollowedTeam>>(emptyList()),
                follow = {},
                unfollow = {},
                now = { testScheduler.currentTime },
                failedHighlights = MutableStateFlow(emptySet<String>()),
                loadSummaryScoresOff = { _: EspnLeague, _: String -> loads += "scores off"; GameSummaryLoad.Failed }
            )
            backgroundScope.launch { vm.uiState.collect {} }
            runCurrent()
            // Nothing before the page is on screen.
            assertTrue("$mode $revealed", loads.isEmpty())
            vm.setVisible(true)
            runCurrent()
            val mayLoad = mode != ScoresMode.OFF || revealed
            val expected = when {
                !mayLoad -> emptyList()
                mode == ScoresMode.OFF -> listOf("scores off")
                else -> listOf("summary")
            }
            assertEquals("$mode $revealed", expected, loads)
            assertEquals("$mode $revealed", !mayLoad, vm.uiState.value.status == GameLoadStatus.WAITING_REVEAL)
            vm.setVisible(false)
            runCurrent()
        }
    }

    // ---- Player card ----

    @Test
    fun `Player card - last games show under Show unless spoiler-safe, or once revealed, and on focus under Hide`() {
        for (mode in modes) for (revealed in bools) for (safe in bools) for (status in PlayerCardStatus.entries) {
            val state = SportsLivePlayerCardUiState(mode = mode, revealed = revealed, spoilerSafe = safe, status = status)
            val label = "$mode revealed=$revealed safe=$safe $status"
            val lastGames = when (mode) {
                ScoresMode.SHOW -> !safe || revealed
                ScoresMode.HIDE_UNTIL_FOCUSED -> revealed
                ScoresMode.OFF -> revealed
            }
            val onFocus = mode == ScoresMode.HIDE_UNTIL_FOCUSED && !safe && !revealed
            assertEquals(label, lastGames, state.showsLastGames)
            assertEquals(label, onFocus, state.revealsOnFocus)
            assertEquals(label, lastGames, state.showsGame(focused = false))
            assertEquals(label, lastGames || onFocus, state.showsGame(focused = true))
            assertEquals(label, !lastGames && !onFocus && status != PlayerCardStatus.FAILED, state.showsReveal)
        }
    }

    @Test
    fun `Player card - it asks for the player unless Never show is on and Reveal not pressed`() = runTest(mainDispatcherRule.dispatcher) {
        val target = PlayerCardTarget(EspnLeagueMap.NBA, "3059318", "J. Embiid", "Philadelphia 76ers")
        for (mode in modes) for (revealed in bools) {
            var loads = 0
            val vm = SportsLivePlayerCardViewModel(
                request = SportsLivePlayerCardRequest(target, revealed = revealed),
                loadCard = { _: EspnLeague, _: String -> loads++; null as PlayerCard? },
                settings = MutableStateFlow(ScoreSettings(mode = mode))
            )
            runCurrent()
            val mayLoad = mode != ScoresMode.OFF || revealed
            assertEquals("$mode $revealed", if (mayLoad) 1 else 0, loads)
            assertEquals("$mode $revealed", if (mayLoad) PlayerCardStatus.FAILED else PlayerCardStatus.WAITING_REVEAL, vm.uiState.value.status)
        }
    }

    // ---- Team page ----

    @Test
    fun `Team page - record shows unless Never show, results under Show or revealed, a row on focus under Hide, never protected`() {
        // A normal entry (not spoiler-safe): nothing changes.
        for (mode in modes) for (revealed in bools) for (focused in bools) for (protected in bools) {
            val label = "$mode revealed=$revealed focused=$focused protected=$protected"
            // Differs from the Game page: under Hide the record, standing and leaders show (decision 5).
            val record = mode != ScoresMode.OFF || revealed
            val results = mode == ScoresMode.SHOW || revealed
            val result = !protected && (results || (mode == ScoresMode.HIDE_UNTIL_FOCUSED && focused))
            assertEquals(label, record, TeamPageSpoilers.mayLoad(mode, revealed))
            assertEquals(label, record, TeamPageSpoilers.showsRecord(mode, revealed))
            assertEquals(label, results, TeamPageSpoilers.showsResults(mode, revealed))
            assertEquals(label, result, TeamPageSpoilers.showsResult(mode, revealed, focused, protected))
            val state = SportsLiveTeamUiState(mode = mode, revealed = revealed)
            assertEquals(label, record, state.showsRecord)
            assertEquals(label, results, state.showsResults)
        }
    }

    @Test
    fun `Team page opened from a spoiler-safe Game page hides results and record until revealed`() {
        for (mode in modes) for (revealed in bools) for (focused in bools) for (protected in bools) {
            val label = "$mode revealed=$revealed focused=$focused protected=$protected"
            val mayLoad = mode != ScoresMode.OFF || revealed
            // Whatever the setting, the last results and the record come back only with Reveal (never under Never show without it).
            assertEquals(label, mayLoad, TeamPageSpoilers.mayLoad(mode, revealed, spoilerSafe = true))
            assertEquals(label, revealed, TeamPageSpoilers.showsRecord(mode, revealed, spoilerSafe = true))
            assertEquals(label, revealed, TeamPageSpoilers.showsResults(mode, revealed, spoilerSafe = true))
            // No focus reveal on a spoiler-safe page.
            assertEquals(label, revealed && !protected, TeamPageSpoilers.showsResult(mode, revealed, focused, protected, spoilerSafe = true))
            val state = SportsLiveTeamUiState(mode = mode, revealed = revealed, spoilerSafe = true)
            assertEquals(label, revealed, state.showsRecord)
            assertEquals(label, revealed, state.showsResults)
        }
    }

    @Test
    fun `Team page - the Game page's spoiler-safe flag reaches the page and the Player card it opens`() = runTest(mainDispatcherRule.dispatcher) {
        val sixers = TeamPages.refOf(pacers, ScoreSide.HOME)!!
        for (safe in bools) {
            val vm = SportsLiveTeamViewModel(
                request = SportsLiveTeamRequest(sixers, spoilerSafe = safe),
                loadTeam = { _: EspnLeague, _: String -> TeamPageLoad.Failed },
                settings = MutableStateFlow(ScoreSettings(mode = ScoresMode.SHOW)),
                followedTeams = MutableStateFlow<List<FollowedTeam>>(emptyList()),
                follow = {},
                unfollow = {},
                items = MutableStateFlow<List<SportsLiveItem>>(emptyList()),
                now = { now }
            )
            backgroundScope.launch { vm.uiState.collect {} }
            runCurrent()
            assertEquals("safe=$safe", safe, vm.uiState.value.spoilerSafe)
            assertEquals("safe=$safe", !safe, vm.uiState.value.showsResults)
            val target = PlayerCardTarget(sixers.league, "1", "Player", null)
            assertEquals("safe=$safe", safe, teamPlayerCardRequest(target, vm.uiState.value).spoilerSafe)
        }
    }

    @Test
    fun `Team page - it asks for the team unless Never show is on and Reveal not pressed`() = runTest(mainDispatcherRule.dispatcher) {
        val sixers = TeamPages.refOf(pacers, ScoreSide.HOME)!!
        for (mode in modes) for (revealed in bools) {
            var loads = 0
            val vm = SportsLiveTeamViewModel(
                request = SportsLiveTeamRequest(sixers),
                loadTeam = { _: EspnLeague, _: String -> loads++; TeamPageLoad.Failed },
                settings = MutableStateFlow(ScoreSettings(mode = mode)),
                followedTeams = MutableStateFlow<List<FollowedTeam>>(emptyList()),
                follow = {},
                unfollow = {},
                items = MutableStateFlow<List<SportsLiveItem>>(emptyList()),
                now = { now }
            )
            backgroundScope.launch { vm.uiState.collect {} }
            if (revealed) vm.reveal()
            runCurrent()
            // Nothing before the page is on screen.
            assertEquals("$mode $revealed", 0, loads)
            vm.setVisible(true)
            runCurrent()
            val mayLoad = mode != ScoresMode.OFF || revealed
            assertEquals("$mode $revealed", if (mayLoad) 1 else 0, loads)
            assertEquals("$mode $revealed", !mayLoad, vm.uiState.value.status == GameLoadStatus.WAITING_REVEAL)
            vm.setVisible(false)
            runCurrent()
        }
    }

    // ---- Results ----

    @Test
    fun `Results - a row shows under Show, under Hide once revealed, never under Never show or protected`() {
        val row = ResultRow(pacers, followed = false, note = FinalNote.FT)
        val saved = SpoilerProtection(SpoilerProtection.saved(emptyList(), pacers, now), nowMs = now)
        for (mode in modes) for (revealed in bools) for (protected in bools) {
            val ids = if (revealed) setOf(pacers.eventId) else emptySet()
            val label = "$mode revealed=$revealed protected=$protected"
            // Differs from the pages: a revealed row stays hidden under Never show.
            val expected = !protected && when (mode) {
                ScoresMode.SHOW -> true
                ScoresMode.HIDE_UNTIL_FOCUSED -> revealed
                ScoresMode.OFF -> false
            }
            val state = SportsLiveResultsUiState(mode = mode, revealed = ids, protection = if (protected) saved else SpoilerProtection())
            assertEquals(label, expected, state.showsScore(row))
            assertEquals(label, expected, ResultSpoilerReveal.showsScore(mode, ids, pacers.eventId, spoilerProtected = protected))
        }
    }

    // ---- Score chips and the situation line ----

    @Test
    fun `Score chip - none without a mode or under Never show, hidden before the reveal or protected, else by game state`() {
        for (mode in modes + listOf<ScoresMode?>(null)) for (revealed in bools) for (protected in bools) for (state in ScoreState.entries) {
            val snapshot = inState(state).copy(spoilerProtected = protected)
            val label = "$mode revealed=$revealed protected=$protected $state"
            val spoils = state == ScoreState.PRE || state == ScoreState.IN || state == ScoreState.POST
            val expected = when {
                mode == null || mode == ScoresMode.OFF -> "none"
                spoils && (protected || (mode == ScoresMode.HIDE_UNTIL_FOCUSED && !revealed)) -> "hidden"
                state == ScoreState.IN -> "live"
                state == ScoreState.POST -> "final"
                state == ScoreState.OFF -> "status"
                else -> "none"
            }
            val chip = ScoreChips.of(card, snapshot, mode, revealed)
            assertEquals(label, expected, kind(chip))
            // The situation line follows the live chip exactly.
            assertEquals(label, expected == "live", GameSituations.of(card, snapshot, mode, revealed) != null)
        }
    }

    // ---- Home ----

    @Test
    fun `Home hero - a score label under Show only, whatever was revealed`() {
        for (mode in modes + listOf<ScoresMode?>(null)) for (protected in bools) {
            val live = inState(ScoreState.IN).copy(spoilerProtected = protected)
            val labels = SportsLiveHomeRow.scoreLabels(listOf(card), mapOf(card.streamKey to live), mode, strings, "● %1\$s")
            val label = "$mode protected=$protected"
            when {
                mode != ScoresMode.SHOW -> assertTrue(label, labels.isEmpty())
                // Under Show a protected live game reads its chip's words.
                protected -> assertEquals(label, mapOf(card.streamKey to "Score hidden"), labels)
                else -> {
                    val text = labels[card.streamKey].orEmpty()
                    assertTrue(label, text.startsWith("● ") && text.contains("88–80"))
                }
            }
        }
        // Home cards never reveal on focus: they read their chip as not revealed.
        assertEquals("hidden", kind(ScoreChips.of(card, inState(ScoreState.IN), ScoresMode.HIDE_UNTIL_FOCUSED, revealed = false)))
        assertFalse(SportsLiveHomeRow.scoreLabels(listOf(card), mapOf(card.streamKey to inState(ScoreState.POST)), ScoresMode.SHOW, strings, "● %1\$s").containsKey(card.streamKey))
    }
}
