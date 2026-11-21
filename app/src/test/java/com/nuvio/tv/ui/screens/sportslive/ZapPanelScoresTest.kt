package com.nuvio.tv.ui.screens.sportslive

import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sportslive.SportsLiveEspnStarts
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
import com.nuvio.tv.core.sportslive.scores.TeamLogoBook
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
import org.junit.Assert.assertFalse
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

    @Test
    fun `the big-game lift orders the panel, guide and CH only under Scores Show (sorting model)`() {
        // Two scored games: the early one (ESPN 18:00) is not close; the later one (ESPN 21:00) is in crunch time
        // (Q4 2:00, 100-98). Neither is big by its stakes (no records in the fixture), so only the crunch can lift.
        val at = lakersEvent.startMs!! + HOUR
        val calm = item("Indiana Pacers at Philadelphia 76ers", pacersEvent.startMs!!)
        val crunch = item("Los Angeles Lakers at Phoenix Suns", lakersEvent.startMs!!)
        val scores = mapOf(
            calm.streamKey to live(pacersEvent, "Q3 9:00", 60, 40),
            crunch.streamKey to live(lakersEvent, "Q4 2:00", 100, 98)
        )
        fun order(mode: ScoresMode) = panelLineup(groupPanelRows(listOf(calm, crunch), scores, mode, emptySet(), at)).map { it.item.id }
        // Show: the close game moves up (it is on screen, with its score).
        assertEquals(listOf(crunch.id, calm.id), order(ScoresMode.SHOW))
        // Hide until focused: no lift, so the order never tells a hidden score is close; start order.
        assertEquals(listOf(calm.id, crunch.id), order(ScoresMode.HIDE_UNTIL_FOCUSED))
        // Off: no score at all; start order.
        assertEquals(listOf(calm.id, crunch.id), order(ScoresMode.OFF))
    }

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
    fun `inside a group followed games lead, then games with a live score, then the rest, each in its own order`() {
        // Earliest start first would put the unscored games on top.
        val unscoredEarly = item("Team A vs Team B", now - 3 * HOUR)
        val followedUnscored = item("Team C vs Team D", now - HOUR / 4, followed = true)
        val scoredLate = item("Los Angeles Lakers at Phoenix Suns", now - HOUR / 2)
        val scoredEarly = item("Indiana Pacers at Philadelphia 76ers", now - 2 * HOUR)
        val unscoredLate = item("Team E vs Team F", now - HOUR / 3)
        val scores = mapOf(
            scoredEarly.streamKey to live(pacersEvent, "Q4 2:00", 100, 98),
            scoredLate.streamKey to live(lakersEvent, "Q1 9:00", 4, 2)
        )
        val items = listOf(unscoredLate, scoredLate, unscoredEarly, followedUnscored, scoredEarly)
        val rows = groupPanelRows(items, scores, ScoresMode.SHOW, emptySet(), now)
        assertEquals(
            listOf(followedUnscored, scoredEarly, scoredLate, unscoredEarly, unscoredLate).map { it.id },
            rows.map { it.item.id }
        )
        // The shared lineup (the panel, CH and Live now) keeps it; the big-game rank (#137) sits between.
        assertEquals(rows.map { it.item.id }, panelLineup(rows).map { it.item.id })
        assertTrue(LINEUP_RANK_FOLLOWED < LINEUP_RANK_BIG_GAME && LINEUP_RANK_BIG_GAME < LINEUP_RANK_SCORED)
        assertTrue(LINEUP_RANK_SCORED < LINEUP_RANK_REST)
        // Under Scores Off no game has a live score: start order again, followed first.
        val off = groupPanelRows(items, scores, ScoresMode.OFF, emptySet(), now)
        assertEquals(
            listOf(followedUnscored, unscoredEarly, scoredEarly, scoredLate, unscoredLate).map { it.id },
            off.map { it.item.id }
        )
    }

    @Test
    fun `under hide until focused a finished game is grouped as a final, its score hidden`() {
        // Both games have begun by ESPN's starts (the Hornets tip off last).
        val at = hornetsEvent.startMs!! + HOUR
        val finished = item("Charlotte Hornets at LA Clippers", at - 3 * HOUR)
        val live = item("Indiana Pacers at Philadelphia 76ers", at - 2 * HOUR)
        val scores = mapOf(finished.streamKey to hornetsEvent, live.streamKey to live(pacersEvent, "Q4 2:00", 100, 98))
        val hidden = groupPanelRows(listOf(live, finished), scores, ScoresMode.HIDE_UNTIL_FOCUSED, emptySet(), at)
        // As the cards label it final (C3): the live game in Live now, the final with the rest.
        assertEquals(listOf(live, finished), hidden.map { it.item })
        assertEquals(listOf(ZapPanelGroup.LIVE_NOW, ZapPanelGroup.OTHER), hidden.map { it.group })
        assertEquals(ScoreChip.Hidden, hidden.first { it.item == finished }.chip)
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
        assertEquals(ScoreChip.Hidden, groupPanelRows(listOf(card), scores, ScoresMode.HIDE_UNTIL_FOCUSED, emptySet(), now).single().chip)
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
            now = { now + testScheduler.currentTime }, zone = { ZoneOffset.UTC }, jitter = { 0L },
            espnStarts = SportsLiveEspnStarts()
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

    @Test
    fun `the game playing is scored in the open panel even when its chip hides it`() = runTest(mainDispatcherRule.dispatcher) {
        val provider = object : ScoreProvider {
            override suspend fun scoreboard(league: EspnLeague, date: LocalDate): ScoreboardResult =
                ScoreboardResult(if (league == EspnLeagueMap.NBA) listOf(live(pacersEvent, "Q3 4:12", 88, 80)) else emptyList())
        }
        val scores = LiveScoresRepository(
            provider = provider, settings = MutableStateFlow(ScoreSettings()), scope = backgroundScope,
            now = { now + testScheduler.currentTime }, zone = { ZoneOffset.UTC }, jitter = { 0L },
            espnStarts = SportsLiveEspnStarts()
        )
        val hockey = item("Boston Bruins at Toronto Maple Leafs", pacersEvent.startMs).copy(sport = "Hockey")
        val (viewModel, playing) = panelViewModel(scores, extra = listOf(hockey))
        viewModel.openPanel()
        runCurrent()
        // The Hockey chip lists the hockey game only: the basketball game playing is pinned above it, still scored.
        viewModel.onPanelChip(com.nuvio.tv.core.sportslive.SportsLiveSportKind.filterKeyOf(hockey))
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        // Read the state, never wait on it: the open panel ticks forever, so a wait that is never met would hang the suite.
        val state = viewModel.panel.value!!
        viewModel.closePanel()
        assertTrue(state.rows.none { it.item.streamKey == playing.streamKey })
        assertEquals(playing.streamKey, state.nowPlayingRow!!.item.streamKey)
        assertEquals(ScoreChip.Live("Q3 4:12", com.nuvio.tv.core.sportslive.scores.ChipScore(80, 88)), state.nowPlayingRow!!.chip)
    }

    @Test
    fun `the panel tracks the game playing once, beside the games it lists`() {
        val playing = item("Indiana Pacers at Philadelphia 76ers", pacersEvent.startMs)
        val other = item("Los Angeles Lakers at Phoenix Suns", lakersEvent.startMs)
        assertEquals(listOf(other, playing), panelTrackedCards(listOf(other), playing))
        assertEquals(listOf(playing, other), panelTrackedCards(listOf(playing, other), playing))
        assertEquals(listOf(other), panelTrackedCards(listOf(other), null))
    }

    // The game playing is polled for its full-time toast (#60)

    /** Requests [scores]' provider makes while the player of [panelViewModel]'s game runs for 30 minutes. */
    private fun kotlinx.coroutines.test.TestScope.playerNowRequests(mode: ScoresMode, alsoTrackedElsewhere: Boolean): Pair<Int, SportsLiveZapViewModel> {
        val requests = mutableListOf<LocalDate>()
        val provider = object : ScoreProvider {
            override suspend fun scoreboard(league: EspnLeague, date: LocalDate): ScoreboardResult {
                requests += date
                return ScoreboardResult(if (league == EspnLeagueMap.NBA) listOf(live(pacersEvent, "Q3 4:12", 88, 80)) else emptyList())
            }
        }
        val scores = LiveScoresRepository(
            provider = provider, settings = MutableStateFlow(ScoreSettings(mode = mode)), scope = backgroundScope,
            now = { now + testScheduler.currentTime }, zone = { ZoneOffset.UTC }, jitter = { 0L },
            espnStarts = SportsLiveEspnStarts()
        )
        val (viewModel, playing) = panelViewModel(scores)
        // The section (or Home) tracks the same game: the feed is one poller, whoever asks.
        if (alsoTrackedElsewhere) scores.track(LiveScoresRepository.SCREEN_SECTION, listOf(playing))
        viewModel.onPlayerStarted(true)
        runCurrent()
        advanceTimeBy(30 * 60_000L); runCurrent()
        return requests.size to viewModel
    }

    @Test
    fun `the game playing is polled under Scores, never under Off, through the shared feeds`() = runTest(mainDispatcherRule.dispatcher) {
        // Off: no request at all, and no toast.
        val (offRequests, offViewModel) = playerNowRequests(ScoresMode.OFF, alsoTrackedElsewhere = false)
        assertEquals(0, offRequests)
        assertNull(offViewModel.endCard.value)
        offViewModel.onPlayerStarted(false)

        // Show: its league is polled while the player runs.
        val (alone, aloneViewModel) = playerNowRequests(ScoresMode.SHOW, alsoTrackedElsewhere = false)
        assertTrue(alone > 0)
        aloneViewModel.onPlayerStarted(false)

        // Tracked by the section too: still one poller per league and date, so no more requests.
        val (shared, sharedViewModel) = playerNowRequests(ScoresMode.SHOW, alsoTrackedElsewhere = true)
        assertEquals(alone, shared)
        sharedViewModel.onPlayerStarted(false)
    }

    @Test
    fun `the game playing stops being polled when the player stops`() = runTest(mainDispatcherRule.dispatcher) {
        val requests = mutableListOf<LocalDate>()
        val provider = object : ScoreProvider {
            override suspend fun scoreboard(league: EspnLeague, date: LocalDate): ScoreboardResult {
                requests += date
                return ScoreboardResult(emptyList())
            }
        }
        val scores = LiveScoresRepository(
            provider = provider, settings = MutableStateFlow(ScoreSettings()), scope = backgroundScope,
            now = { now + testScheduler.currentTime }, zone = { ZoneOffset.UTC }, jitter = { 0L },
            espnStarts = SportsLiveEspnStarts()
        )
        val (viewModel, _) = panelViewModel(scores)
        // Not started (or stopped): nothing.
        runCurrent()
        advanceTimeBy(10 * 60_000L); runCurrent()
        assertTrue(requests.isEmpty())

        viewModel.onPlayerStarted(true)
        runCurrent()
        advanceTimeBy(10 * 60_000L); runCurrent()
        assertTrue(requests.isNotEmpty())

        // The player closed (ON_STOP, or the overlay gone): no more requests, however long.
        viewModel.onPlayerStarted(false)
        runCurrent()
        val fetched = requests.size
        advanceTimeBy(60 * 60_000L); runCurrent()
        assertEquals(fetched, requests.size)
    }

    private fun panelViewModel(
        scores: LiveScoresRepository,
        logos: TeamLogoBook = TeamLogoBook.shared,
        extra: List<SportsLiveItem> = emptyList()
    ): Pair<SportsLiveZapViewModel, SportsLiveItem> {
        val playing = item("Indiana Pacers at Philadelphia 76ers", pacersEvent.startMs)
        val other = item("Los Angeles Lakers at Phoenix Suns", lakersEvent.startMs)
        SportsLiveZapSessions.start(
            SportsLiveZapSession.of(listOf(playing, other), playing.streamKey, SportsLiveTab.LIVE_NOW, null, returnToHomeOnBack = false)!!
        )
        val claimed = mockk<ClaimedCatalog>(relaxed = true)
        every { claimed.key } returns CATALOG
        val repo = mockk<SportsLiveRepository>(relaxed = true)
        every { repo.latest } returns MutableStateFlow(SportsLiveLoad(listOf(playing, other) + extra, emptyList(), 0L, setOf(CATALOG)))
        every { repo.claimedCatalogs } returns flowOf(listOf(claimed))
        every { repo.endedKeys } returns MutableStateFlow(emptySet())
        every { repo.followedTeams } returns flowOf(emptyList())
        val profiles = mockk<ProfileManager>()
        every { profiles.activeProfileId } returns MutableStateFlow(1)
        val viewModel = SportsLiveZapViewModel(
            SavedStateHandle(mapOf("contentId" to playing.id)), mockk(relaxed = true), mockk<DirectDebridResolver>(relaxed = true),
            profiles, repo, scores, mainDispatcherRule.dispatcher, logos = logos
        )
        return viewModel to playing
    }

    @Test
    fun `logos learned while the panel is open appear on its rows`() = runTest(mainDispatcherRule.dispatcher) {
        val provider = object : ScoreProvider {
            override suspend fun scoreboard(league: EspnLeague, date: LocalDate) = ScoreboardResult(emptyList())
        }
        val scores = LiveScoresRepository(
            provider = provider, settings = MutableStateFlow(ScoreSettings()), scope = backgroundScope,
            now = { now + testScheduler.currentTime }, zone = { ZoneOffset.UTC }, jitter = { 0L },
            espnStarts = SportsLiveEspnStarts()
        )
        val book = TeamLogoBook(active = true)
        val (viewModel, playing) = panelViewModel(scores, book)

        viewModel.openPanel()
        val before = viewModel.panel.first { it != null }!!
        assertNull(before.rows.first { it.item == playing }.teams!!.first.logo)

        // The map learns the teams (a feed answering elsewhere): the open panel redraws with them.
        book.learn(EspnLeagueMap.NBA, pacersEvent.away)
        book.learn(EspnLeagueMap.NBA, pacersEvent.home)
        val after = viewModel.panel.first { panel -> panel?.rows?.first { it.item == playing }?.teams?.first?.logo != null }!!
        assertEquals(pacersEvent.away.logo, after.rows.first { it.item == playing }.teams!!.first.logo)

        viewModel.closePanel()
    }

    @Test
    fun `hide until focused reveals no row until a key is pressed in the panel`() = runTest(mainDispatcherRule.dispatcher) {
        val provider = object : ScoreProvider {
            override suspend fun scoreboard(league: EspnLeague, date: LocalDate): ScoreboardResult =
                ScoreboardResult(if (league == EspnLeagueMap.NBA) listOf(live(pacersEvent, "Q3 4:12", 88, 80)) else emptyList())
        }
        val scores = LiveScoresRepository(
            provider = provider, settings = MutableStateFlow(ScoreSettings(mode = ScoresMode.HIDE_UNTIL_FOCUSED)), scope = backgroundScope,
            now = { now + testScheduler.currentTime }, zone = { ZoneOffset.UTC }, jitter = { 0L },
            espnStarts = SportsLiveEspnStarts()
        )
        val (viewModel, playing) = panelViewModel(scores)
        viewModel.openPanel()
        val state = viewModel.panel.first { panel -> panel?.rows?.any { it.chip != null } == true }!!
        assertEquals(ScoreChip.Hidden, state.rows.first { it.item == playing }.chip)

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

        // An open panel ticks forever on the test scheduler; closed, the test can finish.
        viewModel.closePanel()
    }

    @Test
    fun `panel rows carry both teams' logos with no score snapshot, channels none (#66)`() {
        val book = TeamLogoBook(active = true)
        book.learn(EspnLeagueMap.NBA, pacersEvent.away)
        book.learn(EspnLeagueMap.NBA, pacersEvent.home)
        val game = item("Indiana Pacers at Philadelphia 76ers", now - HOUR)
        val unknown = item("Team A vs Team B", now - HOUR)
        val channel = item("Sports Channel 1", null, isChannel = true)
        val rows = groupPanelRows(listOf(game, unknown, channel), emptyMap(), ScoresMode.SHOW, emptySet(), now, known = book)
        val teams = rows.first { it.item == game }.teams!!
        assertEquals(pacersEvent.away.logo, teams.first.logo)
        assertEquals("IND", teams.first.short)
        assertEquals(pacersEvent.home.logo, teams.second.logo)
        assertNull(rows.first { it.item == game }.chip)
        // Two teams but none known: no logos at all, only the names (#130).
        assertNull(rows.first { it.item == unknown }.teams!!.first.logo)
        assertFalse(rows.first { it.item == unknown }.teams!!.hasLogos)
        assertNull(rows.first { it.item == channel }.teams)
        // Scores Off: no learned logo either.
        book.active = false
        assertNull(groupPanelRows(listOf(game), emptyMap(), ScoresMode.OFF, emptySet(), now, known = book).single().teams!!.first.logo)
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val CATALOG = "a:sport:live"
    }
}
