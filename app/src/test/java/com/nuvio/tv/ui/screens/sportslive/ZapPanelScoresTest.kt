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
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.core.sportslive.scores.ScoreProvider
import com.nuvio.tv.core.sportslive.scores.ScoreSettings
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
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

@OptIn(ExperimentalCoroutinesApi::class)
class ZapPanelScoresTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Before
    fun setUp() = SportsLiveZapSessions.clear()

    @After
    fun tearDown() = SportsLiveZapSessions.clear()

    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
    private val pacersEvent = nba.first { it.eventId == "401909985" } // IND @ PHI
    private val lakersEvent = nba.first { it.eventId == "401909986" } // LAL @ PHX
    private val hornetsEvent = nba.first { it.eventId == "401909987" } // CHA @ LAC

    private fun item(name: String, startMs: Long?, isChannel: Boolean = false, followed: Boolean = false) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = "LIVE", imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = CATALOG, catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = isChannel,
        startMs = startMs, sport = "Basketball", startHasTimeOfDay = startMs != null,
        teams = if (isChannel) null else SportsLiveTeams.parse(name), followed = followed
    )

    private fun live(event: ScoreSnapshot, clock: String, home: Int, away: Int) = event.copy(
        state = ScoreState.IN, isFinal = false, clockText = clock,
        home = event.home.copy(score = home), away = event.away.copy(score = away)
    )

    private val now = pacersEvent.startMs!! + HOUR

    @Test
    fun `live games come first, furthest in on top, then starting soon soonest first, then the rest`() {
        val early = item("Indiana Pacers at Philadelphia 76ers", now - 2 * HOUR)
        val late = item("Los Angeles Lakers at Phoenix Suns", now - HOUR / 2)
        val noStart = item("Mystery Match", null)
        val soonLater = item("Team A vs Team B", now + 2 * HOUR)
        val soonFirst = item("Team C vs Team D", now + HOUR / 4)
        val channel = item("Sports Channel 1", null, isChannel = true)
        val tonight = item("Team E vs Team F", now + 5 * HOUR)
        val finished = item("Charlotte Hornets at LA Clippers", now - 3 * HOUR)
        val scores = mapOf(
            early.streamKey to live(pacersEvent, "Q4 2:00", 100, 98),
            late.streamKey to live(lakersEvent, "Q1 9:00", 4, 2),
            finished.streamKey to hornetsEvent
        )
        val rows = groupPanelRows(
            listOf(channel, soonLater, late, finished, tonight, noStart, soonFirst, early), scores, ScoresMode.SHOW, emptySet(), now
        )

        assertEquals(
            listOf(early, late, noStart, soonFirst, soonLater, channel, finished, tonight).map { it.id },
            rows.map { it.item.id }
        )
        assertEquals(
            listOf(ZapPanelGroup.LIVE_NOW, ZapPanelGroup.LIVE_NOW, ZapPanelGroup.LIVE_NOW, ZapPanelGroup.STARTING_SOON,
                ZapPanelGroup.STARTING_SOON, ZapPanelGroup.OTHER, ZapPanelGroup.OTHER, ZapPanelGroup.OTHER),
            rows.map { it.group }
        )
        // Chips on the matched rows only: the title's order (away team first in "at").
        assertEquals(ScoreChip.Live("Q4 2:00", com.nuvio.tv.core.sportslive.scores.ChipScore(98, 100)), rows[0].chip)
        assertTrue(rows.first { it.item == finished }.chip is ScoreChip.Final)
        assertNull(rows.first { it.item == noStart }.chip)
        // Starting soon rows carry their kickoff.
        assertEquals(now + HOUR / 4, rows.first { it.item == soonFirst }.art?.startMs)
    }

    @Test
    fun `under hide until focused a finished game keeps its place by start time`() {
        // Both games have begun by ESPN's starts (the Hornets tip off last).
        val at = hornetsEvent.startMs!! + HOUR
        val finished = item("Charlotte Hornets at LA Clippers", at - 3 * HOUR)
        val live = item("Indiana Pacers at Philadelphia 76ers", at - 2 * HOUR)
        val scores = mapOf(finished.streamKey to hornetsEvent, live.streamKey to live(pacersEvent, "Q4 2:00", 100, 98))
        val hidden = groupPanelRows(listOf(live, finished), scores, ScoresMode.HIDE_UNTIL_FOCUSED, emptySet(), at)
        // Both in Live now, ordered by ESPN's start (the Pacers tipped off first), not by the result.
        assertEquals(listOf(live, finished), hidden.map { it.item })
        assertEquals(listOf(ZapPanelGroup.LIVE_NOW, ZapPanelGroup.LIVE_NOW), hidden.map { it.group })
        assertEquals(ScoreChip.Hidden(live = false), hidden.first { it.item == finished }.chip)
        // Shown, the final drops to the rest.
        assertEquals(ZapPanelGroup.OTHER, groupPanelRows(listOf(finished), scores, ScoresMode.SHOW, emptySet(), at).single().group)
    }

    @Test
    fun `games more than three hours away are not starting soon`() {
        val later = item("Indiana Pacers at Philadelphia 76ers", now + 4 * HOUR)
        val pre = pacersEvent.copy(state = ScoreState.PRE, isFinal = false, startMs = now + 4 * HOUR)
        assertEquals(ZapPanelGroup.OTHER, groupPanelRows(listOf(later), mapOf(later.streamKey to pre), ScoresMode.SHOW, emptySet(), now).single().group)
        assertEquals(ZapPanelGroup.OTHER, groupPanelRows(listOf(later), emptyMap(), ScoresMode.SHOW, emptySet(), now).single().group)
        val edge = item("Team A vs Team B", now + 3 * HOUR)
        assertEquals(ZapPanelGroup.STARTING_SOON, groupPanelRows(listOf(edge), emptyMap(), ScoresMode.SHOW, emptySet(), now).single().group)
    }

    @Test
    fun `followed teams stay first inside their group`() {
        val a = item("Team A vs Team B", now - 2 * HOUR)
        val b = item("Team C vs Team D", now - HOUR, followed = true)
        val rows = groupPanelRows(listOf(a, b), emptyMap(), ScoresMode.SHOW, emptySet(), now)
        assertEquals(listOf(b, a), rows.map { it.item })
    }

    @Test
    fun `the score state beats the addon's start, and an upcoming event is starting soon`() {
        // The addon says it started an hour ago; ESPN says it has not begun.
        val delayed = item("Indiana Pacers at Philadelphia 76ers", now - HOUR)
        val pre = pacersEvent.copy(state = ScoreState.PRE, isFinal = false, clockText = "7:00 PM")
        val rows = groupPanelRows(listOf(delayed), mapOf(delayed.streamKey to pre), ScoresMode.SHOW, emptySet(), now)
        assertEquals(ZapPanelGroup.STARTING_SOON, rows.single().group)
        assertNull(rows.single().chip)
    }

    @Test
    fun `an event that does not fit the card's sides gives no chip and no state`() {
        val card = item("Boston Celtics vs New York Knicks", now - HOUR)
        val rows = groupPanelRows(listOf(card), mapOf(card.streamKey to hornetsEvent), ScoresMode.SHOW, emptySet(), now)
        assertNull(rows.single().chip)
        assertEquals(ZapPanelGroup.LIVE_NOW, rows.single().group)
    }

    @Test
    fun `spoiler setting applies to panel chips`() {
        val card = item("Indiana Pacers at Philadelphia 76ers", now - HOUR)
        val scores = mapOf(card.streamKey to live(pacersEvent, "Q3 4:12", 88, 80))
        assertEquals(ScoreChip.Hidden(live = true), groupPanelRows(listOf(card), scores, ScoresMode.HIDE_UNTIL_FOCUSED, emptySet(), now).single().chip)
        assertTrue(groupPanelRows(listOf(card), scores, ScoresMode.HIDE_UNTIL_FOCUSED, setOf(card.streamKey), now).single().chip is ScoreChip.Live)
        val off = groupPanelRows(listOf(card), scores, ScoresMode.OFF, setOf(card.streamKey), now).single()
        assertNull(off.chip)
        assertEquals(ZapPanelGroup.LIVE_NOW, off.group)
        assertNull(groupPanelRows(listOf(card), scores, null, emptySet(), now).single().chip)
    }

    @Test
    fun `the player panel polls scores only while it is open`() = runTest(mainDispatcherRule.dispatcher) {
        val requests = mutableListOf<LocalDate>()
        val provider = object : ScoreProvider {
            override suspend fun scoreboard(league: EspnLeague, date: LocalDate): ScoreboardResult {
                requests += date
                return ScoreboardResult(if (league == EspnLeagueMap.NBA) listOf(live(pacersEvent, "Q3 4:12", 88, 80)) else emptyList())
            }
        }
        val scores = LiveScoresRepository(
            provider = provider, settings = MutableStateFlow(ScoreSettings()), scope = backgroundScope,
            now = { now + testScheduler.currentTime }, zone = { ZoneOffset.UTC }, jitter = { 0L }
        )
        val (viewModel, playing) = panelViewModel(scores)

        // Closed: nothing is tracked or fetched.
        runCurrent()
        advanceTimeBy(30 * 60_000L); runCurrent()
        assertTrue(requests.isEmpty())

        // Open: the panel's games are tracked and the live score reaches the playing game's row.
        viewModel.openPanel()
        val state = viewModel.panel.first { panel -> panel?.rows?.any { it.chip != null } == true }!!
        assertTrue(requests.isNotEmpty())
        assertEquals(ScoreChip.Live("Q3 4:12", com.nuvio.tv.core.sportslive.scores.ChipScore(80, 88)), state.rows.first { it.item == playing }.chip)
        assertEquals(ZapPanelGroup.LIVE_NOW, state.rows.first().group)

        // Closed again: no more requests, however long it stays closed.
        viewModel.closePanel()
        runCurrent()
        val fetched = requests.size
        advanceTimeBy(30 * 60_000L); runCurrent()
        assertEquals(fetched, requests.size)
        assertNull(viewModel.panel.value)
    }

    private fun panelViewModel(scores: LiveScoresRepository): Pair<SportsLiveZapViewModel, SportsLiveItem> {
        val playing = item("Indiana Pacers at Philadelphia 76ers", pacersEvent.startMs)
        val other = item("Los Angeles Lakers at Phoenix Suns", lakersEvent.startMs)
        SportsLiveZapSessions.start(
            SportsLiveZapSession.of(listOf(playing, other), playing.streamKey, SportsLiveTab.LIVE_NOW, null, returnToHomeOnBack = false)!!
        )
        val claimed = mockk<ClaimedCatalog>(relaxed = true)
        every { claimed.key } returns CATALOG
        val repo = mockk<SportsLiveRepository>(relaxed = true)
        every { repo.latest } returns MutableStateFlow(SportsLiveLoad(listOf(playing, other), emptyList(), 0L, setOf(CATALOG)))
        every { repo.claimedCatalogs } returns flowOf(listOf(claimed))
        every { repo.endedKeys } returns MutableStateFlow(emptySet())
        every { repo.followedTeams } returns flowOf(emptyList())
        val profiles = mockk<ProfileManager>()
        every { profiles.activeProfileId } returns MutableStateFlow(1)
        val viewModel = SportsLiveZapViewModel(
            SavedStateHandle(mapOf("contentId" to playing.id)), mockk(relaxed = true), mockk<DirectDebridResolver>(relaxed = true),
            profiles, repo, scores
        )
        return viewModel to playing
    }

    @Test
    fun `hide until focused reveals no row until a key is pressed in the panel`() = runTest(mainDispatcherRule.dispatcher) {
        val provider = object : ScoreProvider {
            override suspend fun scoreboard(league: EspnLeague, date: LocalDate): ScoreboardResult =
                ScoreboardResult(if (league == EspnLeagueMap.NBA) listOf(live(pacersEvent, "Q3 4:12", 88, 80)) else emptyList())
        }
        val scores = LiveScoresRepository(
            provider = provider, settings = MutableStateFlow(ScoreSettings(mode = ScoresMode.HIDE_UNTIL_FOCUSED)), scope = backgroundScope,
            now = { now + testScheduler.currentTime }, zone = { ZoneOffset.UTC }, jitter = { 0L }
        )
        val (viewModel, playing) = panelViewModel(scores)
        viewModel.openPanel()
        val state = viewModel.panel.first { panel -> panel?.rows?.any { it.chip != null } == true }!!
        assertEquals(ScoreChip.Hidden(live = true), state.rows.first { it.item == playing }.chip)

        // Opening focuses the game playing: it stays hidden however long it sits there.
        viewModel.onPanelRowFocus(playing.streamKey)
        advanceTimeBy(5_000L); runCurrent()
        assertTrue(viewModel.revealedRows.isEmpty())

        // After a key press in the panel, a focused row reveals after a second.
        viewModel.onPanelKey()
        advanceTimeBy(1_100L); runCurrent()
        assertEquals(setOf(playing.streamKey), viewModel.revealedRows)
        val revealed = viewModel.panel.first { panel -> panel?.rows?.first { it.item == playing }?.chip is ScoreChip.Live }!!
        assertTrue(revealed.rows.first { it.item == playing }.chip is ScoreChip.Live)

        // Reopened: the gate is back until the next key.
        viewModel.closePanel()
        viewModel.openPanel()
        viewModel.onPanelRowFocus("a|other")
        advanceTimeBy(5_000L); runCurrent()
        assertEquals(setOf(playing.streamKey), viewModel.revealedRows)
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val CATALOG = "a:sport:live"
    }
}
