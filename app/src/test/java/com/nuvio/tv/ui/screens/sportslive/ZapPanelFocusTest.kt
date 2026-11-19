package com.nuvio.tv.ui.screens.sportslive

import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.LiveScoresRepository
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

/** I3 and M11: the player panel keeps the focused game in place, and its dual picker leaves finals out. */
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

    @Test
    fun `a regroup keeps each group in the order shown, so the focused game keeps its key and place`() {
        val shown = listOf(row("a", ZapPanelGroup.LIVE_NOW), row("b", ZapPanelGroup.LIVE_NOW), row("c", ZapPanelGroup.LIVE_NOW))
        // A score arrives: fresh order puts c first, and a new game d comes in.
        val fresh = listOf(
            row("c", ZapPanelGroup.LIVE_NOW), row("d", ZapPanelGroup.LIVE_NOW),
            row("a", ZapPanelGroup.LIVE_NOW), row("b", ZapPanelGroup.LIVE_NOW)
        )
        val stable = stabilizePanelRows(fresh, shown.map { it.item.streamKey })

        assertEquals(listOf("a", "b", "c", "d"), stable.map { it.item.id })
        // The focused row (b) is found by key at the same list index (one header above the group).
        val focused = shown[1].item.streamKey
        assertEquals(panelGuide(shown, null).indexOf(focused), panelGuide(stable, null).indexOf(focused))
        assertEquals(2, panelGuide(stable, null).indexOf(focused))
        assertEquals(-1, panelGuide(stable, null).indexOf("missing"))
    }

    @Test
    fun `a game changing group still moves, under its new header, the rest keep their order`() {
        val shown = listOf(row("a", ZapPanelGroup.LIVE_NOW), row("b", ZapPanelGroup.LIVE_NOW), row("c", ZapPanelGroup.STARTING_SOON))
        val fresh = listOf(row("c", ZapPanelGroup.LIVE_NOW), row("b", ZapPanelGroup.LIVE_NOW), row("a", ZapPanelGroup.JUST_ENDED))
        val stable = stabilizePanelRows(fresh, shown.map { it.item.streamKey })

        assertEquals(listOf("b", "c", "a"), stable.map { it.item.id })
        assertEquals(listOf(ZapPanelGroup.LIVE_NOW, ZapPanelGroup.LIVE_NOW, ZapPanelGroup.JUST_ENDED), stable.map { it.group })
        // Two groups: headers at 0 and 3, a at index 4.
        assertEquals(4, panelGuide(stable, null).indexOf(stable[2].item.streamKey))
        assertEquals(fresh, stabilizePanelRows(fresh, null))
    }

    @Test
    fun `the order changes under focus only on a new minute, and at once with nothing focused`() {
        val minute = 1_000L
        val inMinute = minute * PANEL_REORDER_MS + 30_000L
        assertTrue(panelMayReorder(focusedKey = null, nowMs = inMinute, lastMinute = minute))
        assertFalse(panelMayReorder(focusedKey = "a|b", nowMs = inMinute, lastMinute = minute))
        assertTrue(panelMayReorder(focusedKey = "a|b", nowMs = (minute + 1) * PANEL_REORDER_MS, lastMinute = minute))
        // Nothing shown yet: the first list is taken as is.
        assertTrue(panelMayReorder(focusedKey = "a|b", nowMs = inMinute, lastMinute = null))
    }

    @Test
    fun `while a row is focused the panel keeps its order when the list changes, and takes the new one when nothing is`() =
        runTest(mainDispatcherRule.dispatcher) {
            // Live now is earliest start first: a, then b.
            val a = item("a", now - 2 * HOUR)
            val b = item("b", now - HOUR)
            val latest = MutableStateFlow(SportsLiveLoad(listOf(a, b), emptyList(), 0L, setOf(CATALOG)))
            val viewModel = panelViewModel(latest, playing = a)

            viewModel.openPanel()
            assertEquals(listOf("a", "b"), viewModel.panel.first { it?.items?.size == 2 }!!.items.map { it.id })
            viewModel.onPanelRowFocus(b.streamKey)

            // a's start moves after b's: the fresh order is b, a, but the focused panel keeps a, b.
            latest.value = SportsLiveLoad(listOf(a.copy(startMs = now - HOUR / 2), b), emptyList(), 1L, setOf(CATALOG))
            runCurrent()
            assertEquals(listOf("a", "b"), viewModel.panel.value!!.items.map { it.id })
            assertEquals(now - HOUR / 2, viewModel.panel.value!!.items.first().startMs)

            // With no row focused, the next change takes the fresh order.
            viewModel.onPanelRowFocus(null)
            latest.value = SportsLiveLoad(listOf(a.copy(startMs = now - HOUR / 3), b), emptyList(), 2L, setOf(CATALOG))
            runCurrent()
            assertEquals(listOf("b", "a"), viewModel.panel.value!!.items.map { it.id })

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
                logos = book, logoFile = TeamLogoFile(file), io = mainDispatcherRule.dispatcher
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
            now = { now + testScheduler.currentTime }, zone = { ZoneOffset.UTC }, jitter = { 0L }
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
