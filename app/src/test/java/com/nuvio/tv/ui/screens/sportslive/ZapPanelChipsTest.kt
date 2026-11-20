package com.nuvio.tv.ui.screens.sportslive

import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sportslive.SportsLiveEspnStarts
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.LiveScoresRepository
import com.nuvio.tv.core.sportslive.scores.ScoreProvider
import com.nuvio.tv.core.sportslive.scores.ScoreSettings
import com.nuvio.tv.core.sportslive.scores.ScoreboardResult
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.ClaimedCatalog
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** #68: the player's live games panel opens on All and its chips filter the rows and the zap order. */
@OptIn(ExperimentalCoroutinesApi::class)
class ZapPanelChipsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Before
    fun setUp() = SportsLiveZapSessions.clear()

    @After
    fun tearDown() = SportsLiveZapSessions.clear()

    private val knicks = item("k", "Knicks vs Celtics", "Basketball")
    private val bulls = item("b", "Bulls vs Heat", "Basketball")
    private val arsenal = item("a", "Arsenal vs Chelsea", "Soccer")
    private val my = SportsLiveUiState.MY_TEAMS_FILTER

    @Test
    fun `the panel opens on all with followed games first, a chip filters rows and zaps for its session, a pick keeps it`() = runTest(mainDispatcherRule.dispatcher) {
        val viewModel = panelViewModel()

        // A game started from My teams opens the panel on All, followed games first.
        viewModel.openPanel()
        val state = viewModel.panel.first { it != null }!!

        assertNull(state.chip)
        assertEquals(listOf(my, null, sportKey("Basketball"), sportKey("Soccer")), state.chips)
        // #79: each chip counts its games.
        assertEquals(mapOf(null to 3, my to 1, sportKey("Basketball") to 2, sportKey("Soccer") to 1), state.counts)
        assertEquals(setOf("k", "b", "a"), state.items.map { it.id }.toSet())
        assertEquals("k", state.rows.first().item.id)
        assertTrue(state.rows.first().item.followed)
        // Until a chip is picked, Up/Down start with the followed games.
        assertNull(SportsLiveZapSessions.current()!!.panelChip)

        // A chip filters the rows and the zap order, and is remembered for the session.
        viewModel.onPanelChip(sportKey("Soccer"))
        val soccer = viewModel.panel.first { it?.chip == sportKey("Soccer") }!!

        assertEquals(listOf("a"), soccer.items.map { it.id })
        assertEquals(setOf("k", "b", "a"), soccer.allItems.map { it.id }.toSet())
        val session = SportsLiveZapSessions.current()!!
        assertEquals(ZapPanelChip(sportKey("Soccer")), session.panelChip)
        assertEquals("a", session.step(forward = true, endedKeys = emptySet())!!.current.id)
        assertEquals("a", session.step(forward = false, endedKeys = emptySet())!!.current.id)

        // All: every game, in the list's order.
        viewModel.onPanelChip(null)
        assertEquals(3, viewModel.panel.first { it != null && it.chip == null && it.items.size == 3 }!!.items.size)
        assertEquals(ZapPanelChip(null), SportsLiveZapSessions.current()!!.panelChip)
        viewModel.onPanelChip(sportKey("Soccer"))

        // Reopened in the same playback: the chip picked.
        viewModel.closePanel()
        runCurrent()
        viewModel.openPanel()
        runCurrent()
        assertEquals(sportKey("Soccer"), viewModel.panel.first { it != null }!!.chip)
        viewModel.closePanel()
        runCurrent()

        // A new playback session opens on All again.
        SportsLiveZapSessions.start(session(on = knicks))
        viewModel.openPanel()
        runCurrent()
        assertNull(viewModel.panel.first { it != null }!!.chip)
        viewModel.closePanel()
        runCurrent()

        // A pick under a chip keeps zapping by that chip.
        viewModel.openPanel()
        viewModel.panel.first { it != null }
        viewModel.onPanelChip(sportKey("Basketball"))
        viewModel.panel.first { it?.chip == sportKey("Basketball") }

        viewModel.onPanelPick(bulls)

        val moved = SportsLiveZapSessions.current()!!
        assertEquals("b", moved.current.id)
        assertEquals(ZapPanelChip(sportKey("Basketball")), moved.panelChip)
        assertEquals("k", moved.step(forward = true, endedKeys = emptySet())!!.current.id)
    }

    private fun session(on: SportsLiveItem) = SportsLiveZapSession.of(
        // As the section hands it over: every Live now game, followed ones marked and first.
        items = listOf(knicks.copy(followed = true), bulls, arsenal),
        itemKey = on.streamKey,
        tab = SportsLiveTab.LIVE_NOW,
        filter = my,
        returnToHomeOnBack = false
    )!!

    private fun TestScope.panelViewModel(): SportsLiveZapViewModel {
        SportsLiveZapSessions.start(session(on = knicks))
        val scores = LiveScoresRepository(
            provider = object : ScoreProvider {
                override suspend fun scoreboard(league: EspnLeague, date: LocalDate) = ScoreboardResult(emptyList())
            },
            settings = MutableStateFlow(ScoreSettings(mode = ScoresMode.OFF)), scope = backgroundScope,
            now = { NOW + testScheduler.currentTime }, zone = { ZoneOffset.UTC }, jitter = { 0L },
            espnStarts = SportsLiveEspnStarts()
        )
        val claimed = mockk<ClaimedCatalog>(relaxed = true)
        every { claimed.key } returns CATALOG
        val repo = mockk<SportsLiveRepository>(relaxed = true)
        every { repo.latest } returns MutableStateFlow(SportsLiveLoad(listOf(knicks, bulls, arsenal), emptyList(), 0L, setOf(CATALOG)))
        every { repo.claimedCatalogs } returns flowOf(listOf(claimed))
        every { repo.endedKeys } returns MutableStateFlow(emptySet())
        every { repo.followedTeams } returns flowOf(listOf(FollowedTeam("Celtics", null)))
        val profiles = mockk<ProfileManager>()
        every { profiles.activeProfileId } returns MutableStateFlow(1)
        // No stream anywhere: a pick's lookup only reverts, after the assertions.
        val resolver = mockk<SportsLiveZapResolver>()
        coEvery { resolver.resolve(any()) } returns null
        return SportsLiveZapViewModel(
            SavedStateHandle(mapOf("contentId" to knicks.id)), resolver, mockk<DirectDebridResolver>(relaxed = true),
            profiles, repo, scores, mainDispatcherRule.dispatcher
        )
    }

    private fun item(id: String, name: String, sport: String) = SportsLiveItem(
        meta = MetaPreview(
            id = id,
            type = ContentType.UNKNOWN,
            rawType = "tv",
            name = name,
            poster = null,
            posterShape = PosterShape.LANDSCAPE,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList()
        ),
        addonId = "addon",
        addonName = "Sports",
        addonBaseUrl = "https://addon",
        catalogKey = CATALOG,
        catalogName = "Live",
        kind = SportsLiveKind.EVENTS,
        isLive = true,
        isChannel = false,
        startMs = null,
        sport = sport,
        teams = SportsLiveTeams.parse(name)
    )

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val CATALOG = "addon:tv:live"
    }
}
