package com.nuvio.tv.ui.screens.sportslive

import android.view.KeyEvent
import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sportslive.SportsLiveEspnStarts
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.LiveScoresRepository
import com.nuvio.tv.core.sportslive.scores.MySport
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.ScoreProvider
import com.nuvio.tv.core.sportslive.scores.ScoreSettings
import com.nuvio.tv.core.sportslive.scores.ScoreboardResult
import com.nuvio.tv.core.sportslive.scores.ScoresMode
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** #142: the player's CH+/CH- walk the live games panel's own list, open or closed, and follow its order changes. */
@OptIn(ExperimentalCoroutinesApi::class)
class ZapPanelLineupTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Before
    fun setUp() = SportsLiveZapSessions.clear()

    @After
    fun tearDown() = SportsLiveZapSessions.clear()

    private val now = System.currentTimeMillis()

    private fun TestScope.zap(viewModel: SportsLiveZapViewModel, keyCode: Int): String {
        viewModel.onKey(keyCode, partyActive = false)
        runCurrent()
        val landed = SportsLiveZapSessions.current()!!.current.id
        // No stream: back to the game playing once the lookup ends.
        advanceTimeBy(PlayerZapController.SETTLE_MS + 1)
        runCurrent()
        return landed
    }

    @Test
    fun `channel keys walk the panel's list, and an order change moves both the panel and the zaps`() = runTest(mainDispatcherRule.dispatcher) {
        // Live now is earliest start first: a, b, c, d.
        val a = item("a", now - 90 * MINUTE)
        val b = item("b", now - 60 * MINUTE)
        val c = item("c", now - 45 * MINUTE)
        val d = item("d", now - 30 * MINUTE)
        val latest = MutableStateFlow(SportsLiveLoad(listOf(d, c, b, a), emptyList(), 0L, setOf(CATALOG)))
        val viewModel = panelViewModel(latest, playing = b)
        runCurrent()

        // Panel closed: next is the row below b, previous the row above.
        assertEquals("c", zap(viewModel, KeyEvent.KEYCODE_CHANNEL_DOWN))
        assertEquals("a", zap(viewModel, KeyEvent.KEYCODE_CHANNEL_UP))
        assertEquals("b", SportsLiveZapSessions.current()!!.current.id)

        // c started earliest after all: the list becomes c, a, b, d.
        latest.value = SportsLiveLoad(listOf(d, c.copy(startMs = now - 120 * MINUTE), b, a), emptyList(), 1L, setOf(CATALOG))
        runCurrent()
        viewModel.openPanel()
        val shown = viewModel.panel.first { it?.items?.size == 4 }!!.items.map { it.id }
        assertEquals(listOf("c", "a", "b", "d"), shown)
        // The zaps follow the same list at once.
        assertEquals("d", zap(viewModel, KeyEvent.KEYCODE_CHANNEL_DOWN))
        assertEquals("a", zap(viewModel, KeyEvent.KEYCODE_CHANNEL_UP))
        // The panel still shows that order.
        assertEquals(shown, viewModel.panel.value!!.items.map { it.id })

        viewModel.closePanel()
        runCurrent()
    }

    @Test
    fun `the lineup and the panel follow My sports, and More live in the panel lists the games it leaves out`() = runTest(mainDispatcherRule.dispatcher) {
        // Earliest start first: a (basketball), c (cricket), b (basketball).
        val a = item("a", now - 90 * MINUTE)
        val c = item("c", now - 60 * MINUTE, sport = "Cricket")
        val b = item("b", now - 45 * MINUTE)
        val latest = MutableStateFlow(SportsLiveLoad(listOf(a, c, b), emptyList(), 0L, setOf(CATALOG)))
        val viewModel = panelViewModel(latest, playing = a, mySports = MySports(mapOf(MySport.BASKETBALL to emptySet())))
        runCurrent()

        // CH- and CH+ never land on the cricket game.
        assertEquals("b", zap(viewModel, KeyEvent.KEYCODE_CHANNEL_DOWN))
        assertEquals("b", zap(viewModel, KeyEvent.KEYCODE_CHANNEL_UP))

        viewModel.openPanel()
        val panel = viewModel.panel.first { it != null }!!
        assertEquals(listOf("a", "b"), panel.items.map { it.id })
        // The game playing listed in its place too (#140), then More live's row with the game My sports leaves out.
        assertEquals(listOf("a", "b"), panel.guide.rows.map { it.item.id })
        assertEquals(1, panel.hiddenByMySports)
        assertEquals(PanelGuideEntry.HiddenByMySports(1, listOf("CRICKET" to 1)), panel.guide.entries.last())
        // No All sports chip any more (user 2026-11-21).
        assertFalse(SportsLiveUiState.ALL_SPORTS_FILTER in panel.chips)
        assertEquals(2, panel.counts[null])

        // OK on More live's row: the panel lists its games, and the zaps walk them.
        viewModel.onPanelChip(SportsLiveUiState.ALL_SPORTS_FILTER)
        val moreLive = viewModel.panel.first { it?.chip == SportsLiveUiState.ALL_SPORTS_FILTER && it.items.isNotEmpty() }!!
        assertEquals(listOf("c"), moreLive.items.map { it.id })
        assertEquals(0, moreLive.hiddenByMySports)
        viewModel.closePanel()
        runCurrent()
        assertEquals("c", zap(viewModel, KeyEvent.KEYCODE_CHANNEL_DOWN))

        // #216: All sports is for one look: the panel opens on My sports again (never a frame of the old scope), and the
        // zaps follow it. The panel is closed whatever happens: an open panel ticks on and the run never ends.
        viewModel.openPanel()
        try {
            val reopened = viewModel.panel.first { it != null }!!
            assertNull(reopened.chip)
            assertEquals(listOf("a", "b"), reopened.items.map { it.id })
        } finally {
            viewModel.closePanel()
            runCurrent()
        }
        assertNotEquals("c", zap(viewModel, KeyEvent.KEYCODE_CHANNEL_DOWN))
    }

    private fun TestScope.panelViewModel(
        latest: MutableStateFlow<SportsLiveLoad>,
        playing: SportsLiveItem,
        mySports: MySports = MySports.DEFAULT
    ): SportsLiveZapViewModel {
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
            profiles, repo, scores, mainDispatcherRule.dispatcher, mySports = flowOf(mySports)
        )
    }

    private fun item(id: String, startMs: Long?, sport: String = "Basketball") = SportsLiveItem(
        meta = MetaPreview(
            id = id, type = ContentType.UNKNOWN, rawType = "tv", name = "Team $id vs Other $id", poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "addon", addonName = "Sports", addonBaseUrl = "https://example.invalid", catalogKey = CATALOG,
        catalogName = "Live", kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false,
        startMs = startMs, sport = sport, startHasTimeOfDay = startMs != null,
        teams = SportsLiveTeams.parse("Team $id vs Other $id")
    )

    private companion object {
        const val MINUTE = 60_000L
        const val CATALOG = "addon:tv:live"
    }
}
