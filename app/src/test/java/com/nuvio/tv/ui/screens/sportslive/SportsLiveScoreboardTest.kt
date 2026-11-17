package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.sportslive.EndedGame
import com.nuvio.tv.core.sportslive.EndedSignal
import com.nuvio.tv.core.sportslive.SportsLiveLayout
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

    @Test
    fun `layouts are offered on Live now and Upcoming only, Cards by default, per tab`() {
        assertEquals(listOf(SportsLiveLayout.CARDS, SportsLiveLayout.SCOREBOARD), SportsLiveLayout.offeredFor(SportsLiveTab.LIVE_NOW))
        assertEquals(SportsLiveLayout.offeredFor(SportsLiveTab.LIVE_NOW), SportsLiveLayout.offeredFor(SportsLiveTab.UPCOMING))
        listOf(SportsLiveTab.CHANNELS, SportsLiveTab.REPLAYS, SportsLiveTab.RESULTS).forEach {
            assertTrue(SportsLiveLayout.offeredFor(it).isEmpty())
        }
        val choices = mapOf(SportsLiveTab.LIVE_NOW to SportsLiveLayout.SCOREBOARD, SportsLiveTab.CHANNELS to SportsLiveLayout.SCOREBOARD)
        assertEquals(SportsLiveLayout.SCOREBOARD, SportsLiveLayout.of(choices, SportsLiveTab.LIVE_NOW))
        assertEquals(SportsLiveLayout.CARDS, SportsLiveLayout.of(choices, SportsLiveTab.UPCOMING))
        // A tab without a switch always shows its cards.
        assertEquals(SportsLiveLayout.CARDS, SportsLiveLayout.of(choices, SportsLiveTab.CHANNELS))
    }

    @Test
    fun `stored layouts round-trip and tolerate unknown entries`() {
        val choices = mapOf(SportsLiveTab.UPCOMING to SportsLiveLayout.CARDS, SportsLiveTab.LIVE_NOW to SportsLiveLayout.SCOREBOARD)
        assertEquals("LIVE_NOW=SCOREBOARD,UPCOMING=CARDS", SportsLiveLayout.encode(choices))
        assertEquals(choices, SportsLiveLayout.decode(SportsLiveLayout.encode(choices)))
        assertEquals(
            mapOf(SportsLiveTab.LIVE_NOW to SportsLiveLayout.SCOREBOARD),
            SportsLiveLayout.decode("LIVE_NOW=SCOREBOARD,UPCOMING=TIMELINE,NOPE=CARDS,,=")
        )
        assertTrue(SportsLiveLayout.decode(null).isEmpty())
    }

    @Test
    fun `the view model remembers the layout per tab`() = runTest {
        val stored = MutableStateFlow<Map<SportsLiveTab, SportsLiveLayout>>(emptyMap())
        val repo = mockk<SportsLiveRepository>(relaxed = true) {
            every { claimedCatalogs } returns flowOf(emptyList())
            every { latest } returns MutableStateFlow<SportsLiveLoad?>(null)
            every { endedKeys } returns MutableStateFlow<Set<String>>(emptySet())
            every { followedTeams } returns flowOf(emptyList<FollowedTeam>())
            coEvery { load(any(), any()) } returns SportsLiveLoad(emptyList(), emptyList(), 0L, emptySet())
        }
        val viewModel = SportsLiveViewModel(repo, layouts = stored, saveLayout = { tab, layout -> stored.value = stored.value + (tab to layout) })
        backgroundScope.launch { viewModel.layout.collect {} }
        runCurrent()
        assertEquals(SportsLiveLayout.CARDS, viewModel.layout.value)

        viewModel.selectLayout(SportsLiveLayout.SCOREBOARD)
        runCurrent()
        assertEquals(SportsLiveLayout.SCOREBOARD, viewModel.layout.value)
        assertEquals(mapOf(SportsLiveTab.LIVE_NOW to SportsLiveLayout.SCOREBOARD), stored.value)

        // Upcoming keeps its own choice (Cards); coming back to Live now restores the Scoreboard.
        viewModel.selectTab(SportsLiveTab.UPCOMING)
        runCurrent()
        assertEquals(SportsLiveLayout.CARDS, viewModel.layout.value)
        viewModel.selectTab(SportsLiveTab.LIVE_NOW)
        runCurrent()
        assertEquals(SportsLiveLayout.SCOREBOARD, viewModel.layout.value)

        // Channels has no switch: a pick there is ignored.
        viewModel.selectTab(SportsLiveTab.CHANNELS)
        viewModel.selectLayout(SportsLiveLayout.SCOREBOARD)
        runCurrent()
        assertEquals(SportsLiveLayout.CARDS, viewModel.layout.value)
        assertFalse(SportsLiveTab.CHANNELS in stored.value)
    }

    @Test
    fun `rows are grouped live, starting soon, later, then final`() {
        val rows = rows()
        assertEquals(listOf(pacers, soon, later, lakers, estimated), rows.map { it.item })
        assertEquals(
            listOf(ScoreboardGroup.LIVE, ScoreboardGroup.STARTING_SOON, ScoreboardGroup.LATER, ScoreboardGroup.FINAL, ScoreboardGroup.FINAL),
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
        assertTrue(rows.none { it.group == ScoreboardGroup.FINAL })
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
    fun `watch with another game only on Live now and not for a final, game stats only on a strict match`() {
        val rows = rows()
        val liveRow = rows.first { it.item == pacers }
        val finalRow = rows.first { it.item == lakers }
        val pacersEvent = ScoreChips.matchedEvent(pacers, scores[pacers.streamKey])
        assertEquals(ScoreboardButtons(watchWith = true, gameStats = true), scoreboardButtons(SportsLiveTab.LIVE_NOW, liveRow, pacersEvent))
        assertEquals(ScoreboardButtons(watchWith = false, gameStats = true), scoreboardButtons(SportsLiveTab.LIVE_NOW, finalRow, lakersEvent))
        val soonRow = rows.first { it.item == soon }
        assertEquals(ScoreboardButtons(watchWith = true, gameStats = false), scoreboardButtons(SportsLiveTab.LIVE_NOW, soonRow, null))
        val upcoming = rows(items = listOf(soon), tab = SportsLiveTab.UPCOMING).single()
        assertEquals(ScoreboardButtons(watchWith = false, gameStats = false), scoreboardButtons(SportsLiveTab.UPCOMING, upcoming, null))
        // Another game's event on the card is no strict match: no Game stats.
        assertNull(ScoreChips.matchedEvent(item("Celtics @ Knicks", NOW), pacersEvent))
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
