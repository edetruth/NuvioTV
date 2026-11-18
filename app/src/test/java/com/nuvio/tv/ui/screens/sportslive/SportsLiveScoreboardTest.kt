package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.sportslive.EndedGame
import com.nuvio.tv.core.sportslive.EndedSignal
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.repository.SportsLiveLoad
import com.nuvio.tv.domain.repository.SportsLiveRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveScoreboardTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
    private val pacersEvent = nba.first { it.eventId == "401909985" } // IND @ PHI, final 131–124
    private val lakersEvent = nba.first { it.eventId == "401909986" } // LAL @ PHX

    private fun item(name: String, startMs: Long?, isLive: Boolean = true) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = isLive, isChannel = false,
        startMs = startMs, sport = "Basketball", startHasTimeOfDay = startMs != null,
        teams = SportsLiveTeams.parse(name)
    )

    private fun live(event: ScoreSnapshot) = event.copy(
        state = ScoreState.IN, isFinal = false, clockText = "Q3 4:12",
        home = event.home.copy(score = 80), away = event.away.copy(score = 88)
    )

    // Live: Pacers @ 76ers in Q3. Starting soon: an unmatched game in an hour. Later: one in five hours.
    // Final: Lakers @ Suns (ESPN final). Probably ended: an unmatched game past its sport's length.
    private val pacers = item("Pacers @ 76ers", NOW - HOUR)
    private val soon = item("Celtics @ Knicks", NOW + HOUR, isLive = false)
    private val later = item("Bulls @ Heat", NOW + 5 * HOUR, isLive = false)
    private val lakers = item("Lakers @ Suns", NOW - 3 * HOUR)
    private val estimated = item("Nets @ Magic", NOW - 4 * HOUR)
    private val scores = mapOf(pacers.streamKey to live(pacersEvent), lakers.streamKey to lakersEvent)
    private val ended = mapOf(
        lakers.streamKey to EndedGame(EndedSignal.FINAL, NOW - HOUR, lakersEvent),
        estimated.streamKey to EndedGame(EndedSignal.ESTIMATE, NOW - HOUR)
    )

    private fun rows(
        items: List<SportsLiveItem> = listOf(later, lakers, estimated, soon, pacers),
        tab: SportsLiveTab = SportsLiveTab.LIVE_NOW,
        mode: ScoresMode? = ScoresMode.SHOW,
        revealed: Set<String> = emptySet(),
        followsSide: (SportsLiveItem, String) -> Boolean = { _, _ -> false }
    ) = scoreboardRows(items, tab, scores, mode, revealed, NOW, if (tab == SportsLiveTab.LIVE_NOW) ended else emptyMap(), followsSide)

    private fun repo() = mockk<SportsLiveRepository>(relaxed = true) {
        every { claimedCatalogs } returns flowOf(emptyList())
        every { latest } returns MutableStateFlow<SportsLiveLoad?>(null)
        every { endedKeys } returns MutableStateFlow<Set<String>>(emptySet())
        every { followedTeams } returns flowOf(emptyList<FollowedTeam>())
        every { hasRecentLinks(any()) } returns false
        coEvery { load(any(), any(), any()) } returns SportsLiveLoad(emptyList(), emptyList(), 0L, emptySet())
    }

    @Test
    fun `the state carries the view model clock, which ticks`() = runTest {
        val ticks = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }
        val viewModel = SportsLiveViewModel(repo(), clock = ticks, computeDispatcher = mainDispatcherRule.dispatcher)
        var now = NOW
        viewModel.nowMs = { now }
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        assertEquals(NOW, viewModel.uiState.value.nowMs)
        now = NOW + 30_000L
        ticks.emit(Unit)
        runCurrent()
        assertEquals(NOW + 30_000L, viewModel.uiState.value.nowMs)
    }

    @Test
    fun `rows are grouped live, starting soon, then just ended, with no more games group`() {
        val rows = rows()
        assertEquals(listOf(pacers, soon, later, lakers, estimated), rows.map { it.item })
        // Live Now has no MORE GAMES (V2): a game ESPN still has as later counts as starting soon.
        assertEquals(
            listOf(
                ScoreboardGroup.LIVE, ScoreboardGroup.STARTING_SOON, ScoreboardGroup.STARTING_SOON,
                ScoreboardGroup.JUST_ENDED, ScoreboardGroup.JUST_ENDED
            ),
            rows.map { it.group }
        )
        // Scores in the card's order (Pacers first), ESPN's clock.
        assertEquals(ScoreChip.Live("Q3 4:12", com.nuvio.tv.core.sportslive.scores.ChipScore(88, 80)), rows[0].chip)
        assertEquals("IND", rows[0].art?.first?.short)
        assertTrue(rows[3].chip is ScoreChip.Final)
        // An estimated end: "Probably ended", never a score.
        assertTrue(rows[4].probablyEnded)
        assertNull(rows[4].chip)
        assertFalse(rows[3].probablyEnded)
    }

    @Test
    fun `a followed side is starred on its own line`() {
        val row = rows(followsSide = { _, side -> side == "76ers" }).first { it.item == pacers }
        assertFalse(row.firstFollowed)
        assertTrue(row.secondFollowed)
    }

    @Test
    fun `upcoming lists starting soon and later games, live only when ESPN says so`() {
        val started = item("Hawks @ Spurs", NOW - 10 * 60_000L, isLive = false)
        val rows = rows(items = listOf(later, soon, started, pacers), tab = SportsLiveTab.UPCOMING)
        assertEquals(listOf(pacers, started, soon, later), rows.map { it.item })
        assertEquals(
            listOf(ScoreboardGroup.LIVE, ScoreboardGroup.STARTING_SOON, ScoreboardGroup.STARTING_SOON, ScoreboardGroup.LATER),
            rows.map { it.group }
        )
        assertTrue(rows.none { it.group == ScoreboardGroup.JUST_ENDED })
    }

    @Test
    fun `spoilers follow the card chip`() {
        // Hide until focused: "Score hidden" until revealed, then the score.
        val hidden = rows(mode = ScoresMode.HIDE_UNTIL_FOCUSED)
        assertEquals(ScoreChip.Hidden, hidden.first { it.item == pacers }.chip)
        assertEquals(ScoreChip.Hidden, hidden.first { it.item == lakers }.chip)
        val revealed = rows(mode = ScoresMode.HIDE_UNTIL_FOCUSED, revealed = setOf(pacers.streamKey))
        assertTrue(revealed.first { it.item == pacers }.chip is ScoreChip.Live)
        // Off: no score anywhere, no ESPN names either.
        val off = rows(mode = ScoresMode.OFF)
        assertTrue(off.all { it.chip == null })
        assertEquals("Pacers", off.first { it.item == pacers }.art?.first?.short)
    }

    @Test
    fun `game stats needs a strict match`() {
        val pacersEvent = ScoreChips.matchedEvent(pacers, scores[pacers.streamKey])
        assertEquals(pacersEvent?.eventId, "401909985")
        // Another game's event on the card is no strict match: no Game stats.
        assertNull(ScoreChips.matchedEvent(item("Celtics @ Knicks", NOW), pacersEvent))
    }

    @Test
    fun `a date with no time of day goes to later games, with no countdown`() {
        val dateOnly = item("Hawks @ Spurs", NOW + HOUR, isLive = false).copy(startHasTimeOfDay = false)
        val upcoming = rows(items = listOf(dateOnly, soon), tab = SportsLiveTab.UPCOMING)
        val row = upcoming.first { it.item == dateOnly }
        assertEquals(ScoreboardGroup.LATER, row.group)
        assertNull(row.startsIn)
        // A timed one an hour away counts down.
        assertEquals(1 to 0, upcoming.first { it.item == soon }.startsIn)
    }

    @Test
    fun `starting soon regroups and counts down as the clock moves`() {
        fun at(nowMs: Long) = scoreboardRows(listOf(later), SportsLiveTab.UPCOMING, emptyMap(), ScoresMode.SHOW, emptySet(), nowMs).single()
        // Five hours out: later, no countdown.
        assertEquals(ScoreboardGroup.LATER, at(NOW).group)
        assertNull(at(NOW).startsIn)
        // Two and a half hours later it starts within three hours.
        val soonAfter = at(NOW + 150 * 60_000L)
        assertEquals(ScoreboardGroup.STARTING_SOON, soonAfter.group)
        assertEquals(2 to 30, soonAfter.startsIn)
        assertEquals(0 to 10, at(NOW + 5 * HOUR - 10 * 60_000L).startsIn)
    }

    @Test
    fun `an ESPN final outside Just ended is a just ended row`() {
        // N5: after a restart the final is no Just ended game, yet ESPN says it is over.
        val row = scoreboardRows(listOf(lakers), SportsLiveTab.LIVE_NOW, scores, ScoresMode.SHOW, emptySet(), NOW).single()
        assertEquals(ScoreboardGroup.JUST_ENDED, row.group)
        // Hidden scores keep the grouping (only the chip is hidden).
        val hidden = scoreboardRows(listOf(lakers), SportsLiveTab.LIVE_NOW, scores, ScoresMode.HIDE_UNTIL_FOCUSED, emptySet(), NOW).single()
        assertEquals(ScoreboardGroup.JUST_ENDED, hidden.group)
        assertEquals(ScoreChip.Hidden, hidden.chip)
        // A Live Now game with no ESPN state and a start hours away waits under starting soon.
        val unmatched = scoreboardRows(listOf(later), SportsLiveTab.LIVE_NOW, emptyMap(), ScoresMode.SHOW, emptySet(), NOW).single()
        assertEquals(ScoreboardGroup.STARTING_SOON, unmatched.group)
    }

    @Test
    fun `a row names each team by the longest of full name, short name and abbreviation`() {
        // N1: ESPN's names for a matched game, longest first.
        val pacersRow = rows().first { it.item == pacers }
        assertEquals(listOf("Indiana Pacers", "Pacers", "IND"), scoreboardNameChoices(pacersRow.art!!.first))
        assertEquals(listOf("Philadelphia 76ers", "76ers", "PHI"), scoreboardNameChoices(pacersRow.art!!.second))
        // An unmatched card has its own name only once; blanks never show.
        assertEquals(listOf("Celtics"), scoreboardNameChoices(com.nuvio.tv.core.sportslive.scores.GameArtTeam("Celtics", "Celtics", null)))
        assertEquals(
            listOf("Auburn Tigers", "Auburn", "AUB"),
            scoreboardNameChoices(com.nuvio.tv.core.sportslive.scores.GameArtTeam("Auburn Tigers", " AUB ", null, shortName = "Auburn"))
        )
        assertEquals(listOf("Navy"), scoreboardNameChoices(com.nuvio.tv.core.sportslive.scores.GameArtTeam("Navy", "", null, shortName = " ")))
    }

    @Test
    fun `countdown to kickoff rounds up to the minute`() {
        assertEquals(1 to 44, scoreboardStartsIn(NOW + 104 * 60_000L - 30_000L, NOW))
        assertEquals(0 to 1, scoreboardStartsIn(NOW + 1_000L, NOW))
        assertNull(scoreboardStartsIn(NOW, NOW))
        assertNull(scoreboardStartsIn(null, NOW))
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val HOUR = 3_600_000L
    }
}
