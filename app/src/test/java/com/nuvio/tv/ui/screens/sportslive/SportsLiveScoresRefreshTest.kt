package com.nuvio.tv.ui.screens.sportslive

import androidx.lifecycle.SavedStateHandle
import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sportslive.SportsLiveEspnStarts
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.ChipScore
import com.nuvio.tv.core.sportslive.scores.EspnFixtures
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.LiveScoresRepository
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.core.sportslive.scores.ScoreChipStrings
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreDetails
import com.nuvio.tv.core.sportslive.scores.ScoreDetailsBuilder
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
import com.nuvio.tv.ui.screens.home.SportsLiveHomeRow
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * #81: on every surface, the second and third poll reach what the screen shows while it stays open (no
 * leaving and coming back). The live game is IND @ PHI an hour in; each poll 30 s apart brings a new home
 * score (88, 91, 94). The cards are those a TV shows: an undated game, whose today's feed answers before
 * yesterday's, or a timed one tracked onto a feed already waiting at the upcoming pace.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveScoresRefreshTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Before
    fun setUp() = SportsLiveZapSessions.clear()

    @After
    fun tearDown() = SportsLiveZapSessions.clear()

    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
    private val pacersEvent = nba.first { it.eventId == "401909985" } // IND @ PHI, 18:00Z
    private val lakersUpcoming = nba.first { it.eventId == "401909986" }.let { // LAL @ PHX, 21:00Z
        it.copy(state = ScoreState.PRE, isFinal = false, clockText = null, home = it.home.copy(score = null), away = it.away.copy(score = null))
    }

    // 19:00Z, 14:00 US Eastern: today's scoreboard is the 15th, yesterday's the 14th.
    private val now = pacersEvent.startMs!! + HOUR
    private val yesterday = LocalDate.of(2026, 11, 14)

    /** Today's NBA scoreboard has the live game at [homeScore]; yesterday's answers once [yesterdayGate] opens. */
    private inner class Feeds : ScoreProvider {
        var homeScore = 88
        val yesterdayGate = CompletableDeferred<Unit>()
        override suspend fun scoreboard(league: EspnLeague, date: LocalDate): ScoreboardResult {
            if (league != EspnLeagueMap.NBA) return ScoreboardResult(emptyList())
            if (date == yesterday) {
                yesterdayGate.await()
                return ScoreboardResult(emptyList())
            }
            val live = pacersEvent.copy(
                state = ScoreState.IN, isFinal = false, clockText = CLOCK,
                home = pacersEvent.home.copy(score = homeScore), away = pacersEvent.away.copy(score = AWAY)
            )
            return ScoreboardResult(listOf(live, lakersUpcoming))
        }
    }

    private fun item(name: String, startMs: Long?) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = "LIVE", imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = CATALOG, catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false,
        startMs = startMs, sport = "Basketball", startHasTimeOfDay = startMs != null,
        teams = SportsLiveTeams.parse(name)
    )

    private val undated = item("Indiana Pacers at Philadelphia 76ers", null)
    private val timed = item("Indiana Pacers at Philadelphia 76ers", pacersEvent.startMs)
    private val upcoming = item("Los Angeles Lakers at Phoenix Suns", lakersUpcoming.startMs)

    private fun TestScope.repository(feeds: Feeds) = LiveScoresRepository(
        provider = feeds, settings = MutableStateFlow(ScoreSettings()), scope = backgroundScope,
        now = { now + testScheduler.currentTime }, zone = { ZoneOffset.UTC }, jitter = { 0L },
        espnStarts = SportsLiveEspnStarts()
    )

    private fun TestScope.nowMs() = now + testScheduler.currentTime

    /**
     * What [visible] shows after each of three polls 30 s apart while [screenId] tracks [cards] and the
     * scores are collected the whole time. [alreadyTracked] (Home's upcoming game) is tracked a minute
     * before the screen opens, so the feed is waiting at the upcoming pace.
     */
    private fun <T> TestScope.threePolls(
        screenId: String,
        cards: List<SportsLiveItem>,
        alreadyTracked: List<SportsLiveItem> = emptyList(),
        visible: (Map<String, ScoreSnapshot>) -> T
    ): List<T> {
        val feeds = Feeds()
        val repo = repository(feeds)
        backgroundScope.launch { repo.scores.collect {} }
        if (alreadyTracked.isNotEmpty()) {
            repo.track(LiveScoresRepository.SCREEN_HOME, alreadyTracked)
            runCurrent()
            advanceTimeBy(60_000L); runCurrent()
        }
        repo.track(screenId, cards)
        runCurrent()
        feeds.yesterdayGate.complete(Unit)
        runCurrent()
        val shown = mutableListOf(visible(repo.scores.value))
        feeds.homeScore = 91
        advanceTimeBy(30_000L); runCurrent()
        shown += visible(repo.scores.value)
        feeds.homeScore = 94
        advanceTimeBy(30_000L); runCurrent()
        shown += visible(repo.scores.value)
        return shown
    }

    /** The chip of the three polls, in the card's order (Pacers first). */
    private val threeChips = listOf(88, 91, 94).map { ScoreChip.Live(CLOCK, ChipScore(AWAY, it)) }

    @Test
    fun `Live now cards show the second and third poll while the screen stays open`() = runTest(mainDispatcherRule.dispatcher) {
        val chips = threePolls(LiveScoresRepository.SCREEN_SECTION, listOf(undated, upcoming)) { scores ->
            ScoreChips.of(undated, scores[undated.streamKey], ScoresMode.SHOW, revealed = false)
        }
        assertEquals(threeChips, chips)
    }

    @Test
    fun `Scoreboard rows show the second and third poll while the screen stays open`() = runTest(mainDispatcherRule.dispatcher) {
        val chips = threePolls(LiveScoresRepository.SCREEN_SECTION, listOf(undated)) { scores ->
            scoreboardRows(listOf(undated), SportsLiveTab.LIVE_NOW, scores, ScoresMode.SHOW, emptySet(), nowMs()).single().chip
        }
        assertEquals(threeChips, chips)
    }

    @Test
    fun `Timeline blocks show the second and third poll while the screen stays open`() = runTest(mainDispatcherRule.dispatcher) {
        val chips = threePolls(LiveScoresRepository.SCREEN_SECTION, listOf(timed), alreadyTracked = listOf(upcoming)) { scores ->
            val rows = scoreboardRows(listOf(timed), SportsLiveTab.LIVE_NOW, scores, ScoresMode.SHOW, emptySet(), nowMs())
            SportsLiveTimeline.model(rows, SportsLiveTab.LIVE_NOW, nowMs(), ZoneOffset.UTC)
                .leagues.flatMap { it.blocks }.single { it.row.item == timed }.row.chip
        }
        assertEquals(threeChips, chips)
    }

    @Test
    fun `the details pane shows the second and third poll while the screen stays open`() = runTest(mainDispatcherRule.dispatcher) {
        val homeScores = threePolls(LiveScoresRepository.SCREEN_SECTION, listOf(undated)) { scores ->
            (ScoreDetailsBuilder.of(undated, scores[undated.streamKey], ScoresMode.SHOW, revealed = false) as? ScoreDetails.Shown)?.second?.score
        }
        assertEquals(listOf(88, 91, 94), homeScores)
    }

    @Test
    fun `Home's Live now row shows the second and third poll while Home stays open`() = runTest(mainDispatcherRule.dispatcher) {
        val strings = ScoreChipStrings("%1\$d–%2\$d", "%1\$s · %2\$s", "%1\$s %2\$s", "Final", "Postponed", "Score hidden")
        val labels = threePolls(LiveScoresRepository.SCREEN_HOME, listOf(undated)) { scores ->
            SportsLiveHomeRow.scoreLabels(listOf(undated), scores, ScoresMode.SHOW, strings, "● %1\$s")[undated.streamKey]
        }
        // "Q3 4:12" reads clock first, then the period, then the score between the abbreviations (V2 phase 10).
        assertEquals(listOf(88, 91, 94).map { "● 4:12 · Q3 · IND $AWAY–$it PHI" }, labels)
    }

    @Test
    fun `in-player panel rows show the second and third poll while the panel stays open`() = runTest(mainDispatcherRule.dispatcher) {
        val feeds = Feeds()
        val scores = repository(feeds)
        val other = item("Los Angeles Lakers at Phoenix Suns", null)
        SportsLiveZapSessions.start(
            SportsLiveZapSession.of(listOf(undated, other), undated.streamKey, SportsLiveTab.LIVE_NOW, null, returnToHomeOnBack = false)!!
        )
        val claimed = mockk<ClaimedCatalog>(relaxed = true)
        every { claimed.key } returns CATALOG
        val repo = mockk<SportsLiveRepository>(relaxed = true)
        every { repo.latest } returns MutableStateFlow(SportsLiveLoad(listOf(undated, other), emptyList(), 0L, setOf(CATALOG)))
        every { repo.claimedCatalogs } returns flowOf(listOf(claimed))
        every { repo.endedKeys } returns MutableStateFlow(emptySet())
        every { repo.followedTeams } returns flowOf(emptyList())
        val profiles = mockk<ProfileManager>()
        every { profiles.activeProfileId } returns MutableStateFlow(1)
        val viewModel = SportsLiveZapViewModel(
            SavedStateHandle(mapOf("contentId" to undated.id)), mockk(relaxed = true), mockk<DirectDebridResolver>(relaxed = true),
            profiles, repo, scores, mainDispatcherRule.dispatcher, logos = TeamLogoBook(active = true)
        )
        fun chip() = viewModel.panel.value?.rows?.firstOrNull { it.item == undated }?.chip

        viewModel.openPanel()
        runCurrent()
        feeds.yesterdayGate.complete(Unit)
        runCurrent()
        val chips = mutableListOf(chip())
        feeds.homeScore = 91
        advanceTimeBy(30_000L); runCurrent()
        chips += chip()
        feeds.homeScore = 94
        advanceTimeBy(30_000L); runCurrent()
        chips += chip()
        viewModel.closePanel()

        assertEquals(threeChips, chips)
    }

    private companion object {
        const val HOUR = 3_600_000L
        const val CATALOG = "a:sport:live"
        const val CLOCK = "Q3 4:12"
        const val AWAY = 80
    }
}
