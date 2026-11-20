package com.nuvio.tv.ui.screens.sportslive

import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sportslive.SportsLiveEspnStarts
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.ChipScore
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.LiveScoresRepository
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.core.sportslive.scores.ScoreProvider
import com.nuvio.tv.core.sportslive.scores.ScoreSettings
import com.nuvio.tv.core.sportslive.scores.ScoreboardResult
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.TeamLogoBook
import com.nuvio.tv.core.sportslive.scores.TeamLogoFile
import com.nuvio.tv.domain.model.ClaimedCatalog
import com.nuvio.tv.domain.model.ContentType
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

/** #164 and M11: the open player panel keeps its order, focus by key, and its dual picker leaves finals out. */
@OptIn(ExperimentalCoroutinesApi::class)
class ZapPanelFocusTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Before
    fun setUp() = SportsLiveZapSessions.clear()

    @After
    fun tearDown() = SportsLiveZapSessions.clear()

    private val now = System.currentTimeMillis()

    private fun row(key: String, group: ZapPanelGroup) = ZapPanelRow(item(key, null), group, chip = null, art = null)

    private fun keys(rows: List<ZapPanelRow>) = rows.map { it.item.id }

    @Test
    fun `the frozen order keeps every row in place while scores and status update in place`() {
        val opened = listOf(row("a", ZapPanelGroup.LIVE_NOW), row("b", ZapPanelGroup.LIVE_NOW), row("c", ZapPanelGroup.STARTING_SOON))
        val (shown, frozen) = freezePanelLineup(opened, null)
        assertEquals(opened, shown)
        // A score arrives and c starts: the fresh order is c, b, a.
        val chip = ScoreChip.Live("Q1", ChipScore(1, 0))
        val fresh = listOf(
            row("c", ZapPanelGroup.LIVE_NOW), row("b", ZapPanelGroup.LIVE_NOW).copy(chip = chip), row("a", ZapPanelGroup.LIVE_NOW)
        )
        val (again, _) = freezePanelLineup(fresh, frozen)
        assertEquals(listOf("a", "b", "c"), keys(again))
        // In place: b's new score, c's new group, still listed under the header it opened under.
        assertEquals(chip, again[1].chip)
        assertEquals(ScoreboardGroup.LIVE, again[2].section)
        assertEquals(ScoreboardGroup.STARTING_SOON, again[2].placed)
        assertTrue(again.none { it.isNew || it.gone })
    }

    @Test
    fun `new games go to the end of their group, tagged New, never above`() {
        val (_, frozen) = freezePanelLineup(
            listOf(row("a", ZapPanelGroup.LIVE_NOW), row("b", ZapPanelGroup.LIVE_NOW), row("s", ZapPanelGroup.STARTING_SOON)), null
        )
        // d (live) and t (starting soon) come first in the fresh order; o is of a group the panel had none of.
        val fresh = listOf(
            row("d", ZapPanelGroup.LIVE_NOW), row("a", ZapPanelGroup.LIVE_NOW), row("b", ZapPanelGroup.LIVE_NOW),
            row("t", ZapPanelGroup.STARTING_SOON), row("s", ZapPanelGroup.STARTING_SOON), row("o", ZapPanelGroup.OTHER)
        )
        val (shown, next) = freezePanelLineup(fresh, frozen)
        assertEquals(listOf("a", "b", "d", "s", "t", "o"), keys(shown))
        assertEquals(listOf("d", "t", "o"), keys(shown.filter { it.isNew }))
        // The next update keeps them where they landed, still New.
        val (later, _) = freezePanelLineup(fresh.reversed(), next)
        assertEquals(keys(shown), keys(later))
        assertEquals(listOf("d", "t", "o"), keys(later.filter { it.isNew }))
    }

    @Test
    fun `ended and vanished games stay in place, dimmed and out of the zap order`() {
        val (_, frozen) = freezePanelLineup(
            listOf(row("a", ZapPanelGroup.LIVE_NOW), row("b", ZapPanelGroup.LIVE_NOW), row("c", ZapPanelGroup.LIVE_NOW)), null
        )
        // a ends (Just ended), b leaves the list altogether.
        val (shown, _) = freezePanelLineup(listOf(row("c", ZapPanelGroup.LIVE_NOW), row("a", ZapPanelGroup.JUST_ENDED)), frozen)
        assertEquals(listOf("a", "b", "c"), keys(shown))
        assertEquals(ScoreboardGroup.JUST_ENDED, shown[0].section)
        assertEquals(ScoreboardGroup.LIVE, shown[0].placed)
        assertTrue(shown[1].gone)
        assertEquals(listOf(false, false, true), shown.map { it.zappable })
        // One header over the run as it opened.
        val guide = panelGuide(shown, null)
        assertEquals(listOf(PanelGuideEntry.Header(ScoreboardGroup.LIVE, 3, run = 0)), guide.entries.filterIsInstance<PanelGuideEntry.Header>())
    }

    @Test
    fun `focus is kept by key across an update, and a removed line falls back to the nearest`() {
        val (opened, frozen) = freezePanelLineup(
            listOf(row("a", ZapPanelGroup.LIVE_NOW), row("b", ZapPanelGroup.LIVE_NOW), row("c", ZapPanelGroup.LIVE_NOW)), null
        )
        val focused = opened[1].item.streamKey
        val before = panelGuide(opened, null).indexOf(focused)
        val (updated, _) = freezePanelLineup(
            listOf(row("c", ZapPanelGroup.LIVE_NOW), row("b", ZapPanelGroup.LIVE_NOW), row("a", ZapPanelGroup.LIVE_NOW)), frozen
        )
        assertEquals(before, panelGuide(updated, null).indexOf(focused))
        // A list without b (a new chip): focus goes to the line nearest where b was.
        val narrowed = panelGuide(listOf(opened[0], opened[2]), null)
        assertEquals(-1, narrowed.indexOf(focused))
        assertEquals(opened[2].item.streamKey, narrowed.nearestFocusable(before))
    }

    @Test
    fun `while the panel is open its order stays as it opened, and closing takes the fresh one`() =
        runTest(mainDispatcherRule.dispatcher) {
            // Live now is earliest start first: a, then b.
            val a = item("a", now - 2 * HOUR)
            val b = item("b", now - HOUR)
            val latest = MutableStateFlow(SportsLiveLoad(listOf(a, b), emptyList(), 0L, setOf(CATALOG)))
            val viewModel = panelViewModel(latest, playing = a)

            viewModel.openPanel()
            assertEquals(listOf("a", "b"), viewModel.panel.first { it?.items?.size == 2 }!!.items.map { it.id })
            // a's start moves after b's: the fresh order is b, a, but the open panel keeps a, b, updated in place.
            latest.value = SportsLiveLoad(listOf(a.copy(startMs = now - HOUR / 2), b), emptyList(), 1L, setOf(CATALOG))
            runCurrent()
            assertEquals(listOf("a", "b"), viewModel.panel.value!!.items.map { it.id })
            assertEquals(now - HOUR / 2, viewModel.panel.value!!.items.first().startMs)
            // A new game arrives: last, tagged New.
            val c = item("c", now - 3 * HOUR)
            latest.value = SportsLiveLoad(listOf(a.copy(startMs = now - HOUR / 2), b, c), emptyList(), 2L, setOf(CATALOG))
            runCurrent()
            assertEquals(listOf("a", "b", "c"), viewModel.panel.value!!.items.map { it.id })
            assertTrue(viewModel.panel.value!!.rows.last().isNew)

            // Closed and opened again: the fresh order.
            viewModel.closePanel()
            runCurrent()
            viewModel.openPanel()
            runCurrent()
            assertEquals(listOf("c", "b", "a"), viewModel.panel.first { it?.items?.size == 3 }!!.items.map { it.id })
            viewModel.closePanel()
        }

    @Test
    fun `the dual picker from the panel leaves out games that are over`() = runTest(mainDispatcherRule.dispatcher) {
        val playing = item("a", now - HOUR)
        val other = item("b", now - HOUR / 2)
        // Started four hours ago: a basketball game is over (Just ended in the panel).
        val over = item("c", now - 4 * HOUR)
        val latest = MutableStateFlow(SportsLiveLoad(listOf(playing, other, over), emptyList(), 0L, setOf(CATALOG)))
        val viewModel = panelViewModel(latest, playing)

        viewModel.openPanel()
        val state = viewModel.panel.first { it?.items?.size == 3 }!!
        assertEquals(ZapPanelGroup.JUST_ENDED, state.rows.first { it.item.id == "c" }.group)

        val pick = viewModel.dualPick()!!
        assertEquals(listOf("b"), pick.candidates.map { it.id })
        assertTrue(pick.fromPlayer)
        assertEquals(playing, pick.pairWith(other)!!.backTo)
        // The picker draws each game from its panel row.
        assertEquals(state.rows.first { it.item.id == "b" }, pick.rows[other.streamKey])
        // A long press on the ended row pairs nothing either.
        assertNull(viewModel.dualSessionWith(over))

        viewModel.closePanel()
    }

    @Test
    fun `after a restart the kept logo map is read before the panel shows, and reading the setting redraws`() =
        runTest(mainDispatcherRule.dispatcher) {
            val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA).first()
            val earlier = TeamLogoBook(active = true).apply {
                learn(EspnLeagueMap.NBA, nba.home)
                learn(EspnLeagueMap.NBA, nba.away)
            }
            val file = File.createTempFile("team_logos", ".tsv").apply { deleteOnExit() }
            TeamLogoFile(file).write(earlier.encode())

            val book = TeamLogoBook()
            val scores = LiveScoresRepository(
                provider = object : ScoreProvider {
                    override suspend fun scoreboard(league: EspnLeague, date: LocalDate) = ScoreboardResult(emptyList())
                },
                settings = MutableStateFlow(ScoreSettings()), scope = backgroundScope,
                now = { now }, zone = { ZoneOffset.UTC }, jitter = { 0L },
                logos = book, logoFile = TeamLogoFile(file), io = mainDispatcherRule.dispatcher,
                espnStarts = SportsLiveEspnStarts()
            )
            assertFalse(scores.logosLoaded.value)
            runCurrent()
            assertTrue(scores.logosLoaded.value)
            assertEquals(2, book.size)

            // The setting read turns the map on: drawn rows are told to redraw, once.
            val revision = book.revision.value
            book.active = false
            book.active = true
            book.active = true
            assertEquals(revision + 2, book.revision.value)
        }

    private fun TestScope.panelViewModel(latest: MutableStateFlow<SportsLiveLoad>, playing: SportsLiveItem): SportsLiveZapViewModel {
        SportsLiveZapSessions.start(
            SportsLiveZapSession.of(latest.value.items, playing.streamKey, SportsLiveTab.LIVE_NOW, null, returnToHomeOnBack = false)!!
        )
        val scores = LiveScoresRepository(
            provider = object : ScoreProvider {
                override suspend fun scoreboard(league: EspnLeague, date: LocalDate) = ScoreboardResult(emptyList())
            },
            settings = MutableStateFlow(ScoreSettings(mode = ScoresMode.OFF)), scope = backgroundScope,
            now = { now + testScheduler.currentTime }, zone = { ZoneOffset.UTC }, jitter = { 0L },
            espnStarts = SportsLiveEspnStarts()
        )
        val claimed = mockk<ClaimedCatalog>(relaxed = true)
        every { claimed.key } returns CATALOG
        val repo = mockk<SportsLiveRepository>(relaxed = true)
        every { repo.latest } returns latest
        every { repo.claimedCatalogs } returns flowOf(listOf(claimed))
        every { repo.endedKeys } returns MutableStateFlow(emptySet())
        every { repo.followedTeams } returns flowOf(emptyList())
        val profiles = mockk<ProfileManager>()
        every { profiles.activeProfileId } returns MutableStateFlow(1)
        val resolver = mockk<SportsLiveZapResolver>()
        coEvery { resolver.resolve(any()) } returns null
        return SportsLiveZapViewModel(
            SavedStateHandle(mapOf("contentId" to playing.id)), resolver, mockk<DirectDebridResolver>(relaxed = true),
            profiles, repo, scores, mainDispatcherRule.dispatcher
        )
    }

    private fun item(id: String, startMs: Long?) = SportsLiveItem(
        meta = MetaPreview(
            id = id, type = ContentType.UNKNOWN, rawType = "tv", name = "Team $id vs Other $id", poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "addon", addonName = "Sports", addonBaseUrl = "https://example.invalid", catalogKey = CATALOG,
        catalogName = "Live", kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false,
        startMs = startMs, sport = "Basketball", startHasTimeOfDay = startMs != null,
        teams = SportsLiveTeams.parse("Team $id vs Other $id")
    )

    private companion object {
        const val HOUR = 3_600_000L
        const val CATALOG = "addon:tv:live"
    }
}
