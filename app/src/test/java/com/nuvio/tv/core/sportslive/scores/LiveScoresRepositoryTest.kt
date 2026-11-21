package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.SportsLiveEspnStarts
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.coroutines.EmptyCoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class LiveScoresRepositoryTest {

    private class FakeProvider : ScoreProvider {
        val calls = mutableListOf<ScoreFeed>()
        var respond: (ScoreFeed) -> ScoreboardResult = { ScoreboardResult(emptyList()) }
        /** When set, a request waits for it: an in-flight request. */
        var gate: CompletableDeferred<Unit>? = null
        /** Per-feed gates. */
        var gateFor: Map<ScoreFeed, CompletableDeferred<Unit>> = emptyMap()
        /** The warm-start copies. */
        var recent: (ScoreFeed) -> ScoreboardResult? = { null }
        override suspend fun scoreboard(league: EspnLeague, date: LocalDate): ScoreboardResult {
            val feed = ScoreFeed(league, date)
            calls += feed
            gate?.await()
            gateFor[feed]?.await()
            return respond(feed)
        }
        override suspend fun recentScoreboard(league: EspnLeague, date: LocalDate): ScoreboardResult? = recent(ScoreFeed(league, date))
        /** Scores-Off schedule reads (no disk copy, no health), also counted in [calls]. */
        val scheduleCalls = mutableListOf<ScoreFeed>()
        override suspend fun scheduleScoreboard(league: EspnLeague, date: LocalDate): ScoreboardResult {
            scheduleCalls += ScoreFeed(league, date)
            return scoreboard(league, date)
        }
        /** Team list requests (#82). */
        val teamCalls = mutableListOf<EspnLeague>()
        var teams: suspend (EspnLeague) -> List<DirectoryTeam>? = { null }
        override suspend fun teamList(league: EspnLeague): List<DirectoryTeam>? {
            teamCalls += league
            return teams(league)
        }
    }

    private val provider = FakeProvider()
    private val settings = MutableStateFlow(ScoreSettings())
    private val watching = WatchingNow()

    private val nbaFixture = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
    private val pacers = nbaFixture.first { it.eventId == "401909985" } // IND @ PHI, 18:00Z
    private val nba15 = ScoreFeed(EspnLeagueMap.NBA, LocalDate.of(2026, 11, 15))

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun game(state: ScoreState, home: Int? = null, away: Int? = null, clock: String? = null) = pacers.copy(
        state = state,
        isFinal = state == ScoreState.POST,
        clockText = clock,
        home = pacers.home.copy(score = home),
        away = pacers.away.copy(score = away)
    )

    private fun item(
        name: String,
        sport: String?,
        start: String?,
        timed: Boolean = true,
        addon: String = "a",
        genres: List<String> = emptyList(),
        alternates: List<SportsLiveItem> = emptyList(),
        followed: Boolean = false
    ) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = genres
        ),
        addonId = addon, addonName = addon, addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false, startMs = start?.let(::ms), sport = sport,
        startHasTimeOfDay = timed, teams = SportsLiveTeams.parse(name), alternates = alternates, followed = followed
    )

    private val SECTION = LiveScoresRepository.SCREEN_SECTION
    private val HOME = LiveScoresRepository.SCREEN_HOME

    private val pacersCard = item("Indiana Pacers at Philadelphia 76ers", "Basketball", "2026-11-15T18:00:00Z")
    /** Not listed live: Scores Off reads nothing for it (a live card's start is read, live.20). */
    private val pacersUpcoming = pacersCard.copy(isLive = false)

    private val mySports = MutableStateFlow(MySports.DEFAULT)

    private fun TestScope.repository(
        start: String,
        zone: ZoneId = ZoneOffset.UTC,
        logoFile: TeamLogoFile? = null,
        prefetch: (List<String>) -> Unit = {},
        health: EspnFeedHealth? = null,
        notifyOutage: () -> Unit = {},
        logos: TeamLogoBook = TeamLogoBook(),
        followedLeagues: kotlinx.coroutines.flow.Flow<List<EspnLeague>> = kotlinx.coroutines.flow.flowOf(emptyList()),
        espnStarts: SportsLiveEspnStarts = SportsLiveEspnStarts(),
        keptFile: TeamLogoFile? = null,
        prewarm: Boolean = false,
        protection: kotlinx.coroutines.flow.Flow<SpoilerProtection> = kotlinx.coroutines.flow.flowOf(SpoilerProtection()),
        profile: kotlinx.coroutines.flow.Flow<Int?> = kotlinx.coroutines.flow.flowOf(null)
    ): LiveScoresRepository {
        val base = ms(start)
        return LiveScoresRepository(
            provider = provider,
            settings = settings,
            mySports = mySports,
            scope = backgroundScope,
            now = { base + testScheduler.currentTime },
            zone = { zone },
            jitter = { 0L },
            logoFile = logoFile,
            prefetch = prefetch,
            io = EmptyCoroutineContext,
            health = health ?: EspnFeedHealth { base + testScheduler.currentTime },
            notifyOutage = notifyOutage,
            logos = logos,
            followedLeagues = followedLeagues,
            watching = watching,
            espnStarts = espnStarts,
            keptFile = keptFile,
            prewarm = prewarm,
            protectionSource = protection,
            profile = profile
        )
    }

    // --- Logo directory (#82) ---

    private val nbaTeams = TeamDirectory.parse(EspnFixtures.json("espn_teams_nba_20261118.json"), EspnFixtures.moshi)!!
    private val cbbTeams = TeamDirectory.parse(EspnFixtures.json("espn_teams_cbb_20261118.json"), EspnFixtures.moshi)!!

    @Test
    fun `Scores Off asks for no team list`() = runTest {
        settings.value = ScoreSettings(mode = ScoresMode.OFF)
        provider.teams = { nbaTeams }
        val repo = repository("2026-11-15T19:00:00Z")
        collect(repo)
        repo.track(SECTION, listOf(pacersUpcoming))
        advanceTimeBy(30 * 60_000L); runCurrent()
        assertTrue(provider.teamCalls.isEmpty())
        assertTrue(provider.calls.isEmpty())
        // Turned on: the card's league list follows.
        settings.value = ScoreSettings(mode = ScoresMode.SHOW)
        advanceTimeBy(LiveScoresRepository.DIRECTORY_START_DELAY_MS + 1); runCurrent()
        assertEquals(EspnLeagueMap.NBA, provider.teamCalls.first())
    }

    @Test
    fun `the team list waits behind the scoreboards and never holds them up`() = runTest {
        val never = CompletableDeferred<Unit>()
        provider.teams = { never.await(); nbaTeams }
        provider.respond = { ScoreboardResult(listOf(game(ScoreState.IN, 50, 48, "Q3 4:12"))) }
        val repo = repository("2026-11-15T19:00:00Z")
        collect(repo)
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        assertTrue(provider.teamCalls.isEmpty())
        assertEquals(50, repo.scores.value[pacersCard.streamKey]?.homeScore)
        advanceTimeBy(LiveScoresRepository.DIRECTORY_START_DELAY_MS + 1); runCurrent()
        // The card's league first; its request hangs, the scores keep polling.
        assertEquals(EspnLeagueMap.NBA, provider.teamCalls.first())
        val polled = provider.calls.size
        advanceTimeBy(5 * 60_000L); runCurrent()
        assertTrue(provider.calls.size > polled)
    }

    @Test
    fun `a fetched list draws the card's logos before any match`() = runTest {
        // A bare "Basketball" card: both its leagues' lists are needed (the college one has no Pacers).
        provider.teams = { league ->
            when (league) {
                EspnLeagueMap.NBA -> nbaTeams
                EspnLeagueMap.COLLEGE_BASKETBALL -> cbbTeams
                else -> null
            }
        }
        // Scoreboards fail: no match ever.
        provider.respond = { ScoreboardResult.FAILED }
        val book = TeamLogoBook()
        val repo = repository("2026-11-15T19:00:00Z", logos = book)
        collect(repo)
        repo.track(SECTION, listOf(pacersCard))
        advanceTimeBy(LiveScoresRepository.DIRECTORY_START_DELAY_MS + 1); runCurrent()
        assertTrue(repo.scores.value.isEmpty())
        val art = GameArt.of(pacersCard, snapshot = null, known = book)!!
        assertEquals("https://a.espncdn.com/i/teamlogos/nba/500/ind.png", art.first.logo)
        assertEquals("https://a.espncdn.com/i/teamlogos/nba/500/phi.png", art.second.logo)
    }

    @Test
    fun `My sports and followed teams' leagues get their lists too`() = runTest {
        val followed = MutableStateFlow(listOf(EspnLeague("soccer", "eng.1")))
        val repo = repository("2026-11-15T19:00:00Z", followedLeagues = followed)
        collect(repo)
        advanceTimeBy(LiveScoresRepository.DIRECTORY_START_DELAY_MS + 1); runCurrent()
        assertTrue(EspnLeague("soccer", "eng.1") in provider.teamCalls)
        assertTrue(MySports.DEFAULT.feeds().filter(TeamDirectory::covers).all { it in provider.teamCalls })
        assertTrue(EspnLeagueMap.SOCCER_ALL !in provider.teamCalls)
    }

    // --- Scores status (#70) ---

    private fun failure(health: EspnFeedHealth, feed: ScoreFeed) =
        health.recordFailure(EspnFeedKey.scoreboard(feed.league, feed.date), FeedFailureKind.NETWORK)

    @Test
    fun `retry now fetches today's and yesterday's scoreboards in use, never a past day`() = runTest {
        // Noon US Eastern on 2026-11-16.
        val base = ms("2026-11-16T17:00:00Z")
        val health = EspnFeedHealth { base + testScheduler.currentTime }
        val today = ScoreFeed(EspnLeagueMap.NBA, LocalDate.of(2026, 11, 16))
        val past = ScoreFeed(EspnLeagueMap.NBA, LocalDate.of(2026, 11, 10))
        listOf(today, nba15, past).forEach { failure(health, it) }
        val repo = repository("2026-11-16T17:00:00Z", health = health)
        advanceTimeBy(31_000L)
        repo.retryNow()
        runCurrent()
        assertEquals(setOf(today, nba15), provider.calls.toSet())
        assertEquals(2, provider.calls.size)
    }

    @Test
    fun `retry now runs at most once per 30 s`() = runTest {
        val base = ms("2026-11-16T12:00:00Z")
        val health = EspnFeedHealth { base + testScheduler.currentTime }
        failure(health, nba15)
        val repo = repository("2026-11-16T12:00:00Z", health = health)
        advanceTimeBy(31_000L)
        repo.retryNow(); runCurrent()
        assertEquals(1, provider.calls.size)
        advanceTimeBy(14_000L)
        repo.retryNow(); runCurrent()
        assertEquals(1, provider.calls.size)
        advanceTimeBy(17_000L)
        repo.retryNow(); runCurrent()
        assertEquals(2, provider.calls.size)
    }

    @Test
    fun `a failed retry keeps the feed's backoff, counts once, and Retry-After is respected`() = runTest {
        val base = ms("2026-11-15T19:00:00Z")
        val health = EspnFeedHealth { base + testScheduler.currentTime }
        provider.respond = { feed -> failure(health, feed); ScoreboardResult.FAILED }
        val repo = repository("2026-11-15T19:00:00Z", health = health)
        collect(repo)
        repo.track(SECTION, listOf(pacersCard))
        runCurrent() // 0 s: failure 1, next at 30 s
        // Asked for within 30 s: Retry now skips it.
        advanceTimeBy(10_000L); repo.retryNow(); runCurrent()
        assertEquals(1, provider.calls.size)
        advanceTimeBy(20_000L); runCurrent() // 30 s: failure 2, next at 90 s
        assertEquals(2, provider.calls.size)
        advanceTimeBy(30_000L); repo.retryNow(); runCurrent() // 60 s: the retry fails
        assertEquals(3, provider.calls.size)
        val key = EspnFeedKey.scoreboard(nba15.league, nba15.date)
        assertEquals(3, health.feeds.value.getValue(key).consecutiveFailures)
        // Not counted again by the poller: its next fetch stays at 90 s (not 60 s + 2 min).
        advanceTimeBy(29_000L); runCurrent()
        assertEquals(3, provider.calls.size)
        advanceTimeBy(1_000L); runCurrent()
        assertEquals(4, provider.calls.size)

        // ESPN asks to wait 10 minutes: Retry now leaves the feed alone meanwhile.
        provider.respond = { feed -> failure(health, feed); ScoreboardResult.FAILED.copy(retryAfterMs = 10 * 60_000L) }
        advanceTimeBy(2 * 60_000L); runCurrent() // 3:30: failure with Retry-After
        assertEquals(5, provider.calls.size)
        advanceTimeBy(60_000L); repo.retryNow(); runCurrent()
        assertEquals(5, provider.calls.size)
    }

    @Test
    fun `scores Off makes retry now fetch nothing`() = runTest {
        settings.value = ScoreSettings(ScoresMode.OFF)
        val base = ms("2026-11-16T12:00:00Z")
        val health = EspnFeedHealth { base + testScheduler.currentTime }
        failure(health, nba15)
        val repo = repository("2026-11-16T12:00:00Z", health = health)
        collect(repo)
        repo.track(SECTION, listOf(pacersUpcoming))
        repo.retryNow()
        advanceTimeBy(30 * 60_000L); runCurrent()
        assertTrue(provider.calls.isEmpty())
    }

    @Test
    fun `the outage toast shows once, after every feed has failed for 10 minutes in the section`() = runTest {
        val base = ms("2026-11-15T19:00:00Z")
        val health = EspnFeedHealth { base + testScheduler.currentTime }
        // The provider records each failure as the ESPN provider does.
        provider.respond = { feed ->
            failure(health, feed)
            ScoreboardResult.FAILED
        }
        var toasts = 0
        val repo = repository("2026-11-15T19:00:00Z", health = health, notifyOutage = { toasts++ })
        collect(repo)
        repo.track(SECTION, listOf(pacersCard))
        // Backoff 30 s, 60 s, 2 min, 5 min, 5 min...: failures at 0, 0:30, 1:30, 3:30, 8:30, 13:30.
        advanceTimeBy(9 * 60_000L); runCurrent()
        assertEquals(0, toasts)
        advanceTimeBy(5 * 60_000L); runCurrent()
        assertEquals(1, toasts)
        advanceTimeBy(30 * 60_000L); runCurrent()
        assertEquals(1, toasts)
    }

    @Test
    fun `no outage toast while only Home is tracked`() = runTest {
        val base = ms("2026-11-15T19:00:00Z")
        val health = EspnFeedHealth { base + testScheduler.currentTime }
        provider.respond = { feed ->
            failure(health, feed)
            ScoreboardResult.FAILED
        }
        var toasts = 0
        val repo = repository("2026-11-15T19:00:00Z", health = health, notifyOutage = { toasts++ })
        collect(repo)
        repo.track(HOME, listOf(pacersCard))
        advanceTimeBy(30 * 60_000L); runCurrent()
        assertEquals(0, toasts)
    }

    private fun TestScope.collect(repo: LiveScoresRepository) = backgroundScope.launch { repo.scores.collect {} }

    @Test
    fun `Upcoming's scoreboards asked for ahead, each feed at most once in 15 min, never under Scores Off`() = runTest {
        mySports.value = MySports(mapOf(MySport.BASKETBALL to setOf(MyLeague.NBA)))
        val repo = repository("2026-11-16T17:00:00Z")
        collect(repo)
        runCurrent()
        val asked = repo.prefetchUpcoming()
        // My sports' feeds for ESPN's today and tomorrow.
        assertTrue(asked > 0)
        assertEquals(0, asked % 2)
        assertTrue(provider.calls.size >= asked)
        advanceTimeBy(10 * 60_000L)
        assertEquals(0, repo.prefetchUpcoming())
        advanceTimeBy(6 * 60_000L)
        assertEquals(asked, repo.prefetchUpcoming())

        settings.value = ScoreSettings(ScoresMode.OFF)
        runCurrent()
        advanceTimeBy(16 * 60_000L)
        assertEquals(0, repo.prefetchUpcoming())
    }

    @Test
    fun `Upcoming's prefetch asks at most 6 feeds per burst, followed teams' leagues first (F4)`() = runTest {
        val leagues = MySports.DEFAULT.feeds().distinct()
        assertTrue(leagues.size > 3)
        val followed = leagues.last()
        val repo = repository("2026-11-16T17:00:00Z", followedLeagues = kotlinx.coroutines.flow.flowOf(listOf(followed)))
        collect(repo)
        runCurrent()
        assertEquals(LiveScoresRepository.PREWARM_FEEDS, repo.prefetchUpcoming())
        assertEquals(LiveScoresRepository.PREWARM_FEEDS, provider.calls.size)
        assertEquals(followed, provider.calls.first().league)
        // The next burst takes the feeds left, never the ones just asked for.
        val first = provider.calls.toSet()
        provider.calls.clear()
        assertTrue(repo.prefetchUpcoming() <= LiveScoresRepository.PREWARM_FEEDS)
        assertTrue(provider.calls.none { it in first })
    }

    @Test
    fun `cards on one feed share one request`() = runTest {
        provider.respond = { ScoreboardResult(nbaFixture) }
        val repo = repository("2026-11-16T12:00:00Z")
        collect(repo)
        repo.track(SECTION, listOf(pacersCard, item("Los Angeles Lakers at Phoenix Suns", "Basketball", "2026-11-15T20:30:00Z")))
        runCurrent()
        assertEquals(listOf(nba15), provider.calls)
        assertEquals(setOf("a|Indiana Pacers at Philadelphia 76ers", "a|Los Angeles Lakers at Phoenix Suns"), repo.scores.value.keys)
        assertEquals("401909986", repo.scores.value.getValue("a|Los Angeles Lakers at Phoenix Suns").eventId)
    }

    @Test
    fun `a spoiler settings read error falls back to the profile defaults and scores still show (L8)`() = runTest {
        provider.respond = { ScoreboardResult(nbaFixture) }
        val failing = kotlinx.coroutines.flow.flow<SpoilerProtection> { throw java.io.IOException("prefs") }
        val repo = repository("2026-11-16T12:00:00Z", protection = failing)
        collect(repo)
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        assertEquals(setOf(pacersCard.streamKey), repo.scores.value.keys)
    }

    @Test
    fun `a later settings read error keeps the last protection read`() = runTest {
        val saved = SpoilerProtection(myTeams = false, nowMs = 1L)
        val source = kotlinx.coroutines.flow.flow {
            emit(saved)
            throw java.io.IOException("prefs")
        }
        assertEquals(listOf(saved, saved), LiveScoresRepository.protectionOrDefaults(source).toList())
        val failing = kotlinx.coroutines.flow.flow<SpoilerProtection> { throw java.io.IOException("prefs") }
        assertEquals(
            listOf(SpoilerProtection(myTeamsResults = com.nuvio.tv.data.local.SportsLivePreferencesDataStore.MY_TEAMS_RESULTS_HIDDEN_DEFAULT)),
            LiveScoresRepository.protectionOrDefaults(failing).toList()
        )
    }

    @Test
    fun `a card is confirmed unmatched only once its feed has answered without its game (#60)`() = runTest {
        provider.respond = { ScoreboardResult(nbaFixture) }
        val gate = CompletableDeferred<Unit>()
        provider.gate = gate
        val repo = repository("2026-11-16T12:00:00Z")
        collect(repo)
        val heat = item("Miami Heat at Chicago Bulls", "Basketball", "2026-11-15T18:00:00Z")
        repo.track(SECTION, listOf(pacersCard, heat))
        runCurrent()
        // The feed has not answered yet: nothing may be estimated.
        assertTrue(repo.unmatched.value.isEmpty())
        gate.complete(Unit)
        runCurrent()
        assertEquals(setOf(heat.streamKey), repo.unmatched.value)
        // Still known once the card is no longer tracked.
        repo.untrack(SECTION)
        runCurrent()
        assertEquals(setOf(heat.streamKey), repo.unmatched.value)
    }

    @Test
    fun `polls that bring the same scores publish nothing new, a changed score publishes once (#207)`() = runTest {
        var home = 50
        // A new answer each poll, equal to the last until the score moves.
        provider.respond = { ScoreboardResult(listOf(game(ScoreState.IN, home, 48, "Q3 4:12"))) }
        val repo = repository("2026-11-15T19:00:00Z")
        val seen = ArrayList<Map<String, ScoreSnapshot>>()
        backgroundScope.launch { repo.scores.collect { seen += it } }
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        assertEquals(50, seen.last()[pacersCard.streamKey]?.homeScore)
        val published = seen.size
        advanceTimeBy(60_000); runCurrent()
        assertEquals(3, provider.calls.size)
        assertEquals(published, seen.size)
        home = 52
        advanceTimeBy(30_000); runCurrent()
        assertEquals(published + 1, seen.size)
        assertEquals(52, seen.last()[pacersCard.streamKey]?.homeScore)
    }

    @Test
    fun `a live game polls every 30 s`() = runTest {
        provider.respond = { ScoreboardResult(listOf(game(ScoreState.IN, 50, 48))) }
        val repo = repository("2026-11-15T19:00:00Z")
        collect(repo)
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        assertEquals(1, provider.calls.size)
        advanceTimeBy(29_000); runCurrent()
        assertEquals(1, provider.calls.size)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, provider.calls.size)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(3, provider.calls.size)
    }

    @Test
    fun `an upcoming game polls every 5 min, tightening to 30 s from 5 min before the start`() = runTest {
        provider.respond = { ScoreboardResult(listOf(game(ScoreState.PRE))) }
        val repo = repository("2026-11-15T17:40:00Z")
        collect(repo)
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        assertEquals(1, provider.calls.size)
        advanceTimeBy(5 * 60_000L); runCurrent() // 17:45
        assertEquals(2, provider.calls.size)
        advanceTimeBy(4 * 60_000L); runCurrent() // 17:49: next due at 17:50
        assertEquals(2, provider.calls.size)
        advanceTimeBy(60_000L); runCurrent() // 17:50; then 17:55 (5 min before the start), then every 30 s
        assertEquals(3, provider.calls.size)
        advanceTimeBy(5 * 60_000L); runCurrent() // 17:55
        assertEquals(4, provider.calls.size)
        advanceTimeBy(30_000L); runCurrent()
        assertEquals(5, provider.calls.size)
    }

    @Test
    fun `polling stops when every matched game is final`() = runTest {
        provider.respond = { ScoreboardResult(listOf(game(ScoreState.POST, 131, 124, "Final"))) }
        val repo = repository("2026-11-15T21:00:00Z")
        collect(repo)
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        advanceTimeBy(3 * 60 * 60_000L); runCurrent()
        assertEquals(1, provider.calls.size)
        assertEquals(131, repo.scores.value.values.single().homeScore)
    }

    @Test
    fun `a past day is fetched once`() = runTest {
        provider.respond = { ScoreboardResult(EspnFixtures.events(EspnFixtures.ENG1_20261108, EspnFixtures.ENG1)) }
        val repo = repository("2026-11-16T12:00:00Z")
        collect(repo)
        repo.track(SECTION, listOf(item("Man Utd vs Aston Villa", "Soccer", "2026-11-08T00:00:00Z", timed = false, genres = listOf("Premier League"))))
        runCurrent()
        advanceTimeBy(60 * 60_000L); runCurrent()
        assertEquals(listOf(ScoreFeed(EspnFixtures.ENG1, LocalDate.of(2026, 11, 8))), provider.calls)
        assertEquals("401879240", repo.scores.value.values.single().eventId)
    }

    @Test
    fun `a feed's last fetch is lent to Results only while younger than asked, never by a request (#67)`() = runTest {
        provider.respond = { ScoreboardResult(nbaFixture) }
        val repo = repository("2026-11-16T12:00:00Z")
        assertNull(repo.recentEvents(nba15, 120_000))
        collect(repo)
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        assertEquals(nbaFixture.map { it.eventId }, repo.recentEvents(nba15, 120_000)?.map { it.eventId })
        assertNull(repo.recentEvents(ScoreFeed(EspnLeagueMap.NHL, nba15.date), 120_000))
        advanceTimeBy(120_000); runCurrent()
        assertNull(repo.recentEvents(nba15, 120_000))
        assertEquals(1, provider.calls.size)
    }

    @Test
    fun `failures back off, pause after three and resume on success`() = runTest {
        var fail = false
        provider.respond = { if (fail) ScoreboardResult.FAILED else ScoreboardResult(listOf(game(ScoreState.IN, 50, 48))) }
        val repo = repository("2026-11-15T19:00:00Z")
        collect(repo)
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        assertEquals(1, repo.scores.value.size)
        fail = true
        advanceTimeBy(30_000); runCurrent() // failure 1 at 30 s
        assertEquals(2, provider.calls.size)
        assertEquals(1, repo.scores.value.size) // the last score stays until the feed pauses
        advanceTimeBy(29_999); runCurrent()
        assertEquals(2, provider.calls.size)
        advanceTimeBy(1); runCurrent() // failure 2 at 60 s (30 s backoff)
        assertEquals(3, provider.calls.size)
        advanceTimeBy(60_000); runCurrent() // failure 3 at 120 s (60 s backoff): paused
        assertEquals(4, provider.calls.size)
        assertTrue(repo.scores.value.isEmpty())
        fail = false
        advanceTimeBy(119_999); runCurrent()
        assertEquals(4, provider.calls.size)
        advanceTimeBy(1); runCurrent() // 2 min backoff, then a success brings the score back
        assertEquals(5, provider.calls.size)
        assertEquals(1, repo.scores.value.size)
    }

    @Test
    fun `Retry-After longer than the backoff is honoured`() = runTest {
        var calls = 0
        provider.respond = {
            if (calls++ == 0) ScoreboardResult(listOf(game(ScoreState.IN, 1, 0)))
            else ScoreboardResult.FAILED.copy(retryAfterMs = 120_000)
        }
        val repo = repository("2026-11-15T19:00:00Z")
        collect(repo)
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        advanceTimeBy(30_000); runCurrent() // failure with Retry-After 120 s
        advanceTimeBy(119_000); runCurrent()
        assertEquals(2, provider.calls.size)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(3, provider.calls.size)
    }

    @Test
    fun `nothing is fetched while no one collects`() = runTest {
        provider.respond = { ScoreboardResult(listOf(game(ScoreState.IN, 50, 48))) }
        val repo = repository("2026-11-15T19:00:00Z")
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        advanceTimeBy(10 * 60_000L); runCurrent()
        assertTrue(provider.calls.isEmpty())
        val collector = collect(repo)
        runCurrent()
        assertEquals(1, provider.calls.size)
        collector.cancel()
        advanceTimeBy(10 * 60_000L); runCurrent()
        assertEquals(1, provider.calls.size)
        // Back on screen after the interval has passed: fetched again at once.
        collect(repo)
        runCurrent()
        assertEquals(2, provider.calls.size)
    }

    @Test
    fun `Scores Off fetches nothing and shows nothing`() = runTest {
        settings.value = ScoreSettings(mode = ScoresMode.OFF)
        provider.respond = { ScoreboardResult(listOf(game(ScoreState.IN, 50, 48))) }
        val repo = repository("2026-11-15T19:00:00Z")
        collect(repo)
        repo.track(SECTION, listOf(pacersUpcoming))
        runCurrent()
        advanceTimeBy(10 * 60_000L); runCurrent()
        assertTrue(provider.calls.isEmpty())
        assertTrue(repo.scores.value.isEmpty())
        settings.value = ScoreSettings(mode = ScoresMode.HIDE_UNTIL_FOCUSED)
        runCurrent()
        assertEquals(1, provider.calls.size)
        assertEquals(1, repo.scores.value.size)
        settings.value = ScoreSettings(mode = ScoresMode.OFF)
        runCurrent()
        assertTrue(repo.scores.value.isEmpty())
        advanceTimeBy(10 * 60_000L); runCurrent()
        assertEquals(1, provider.calls.size)
    }

    @Test
    fun `score delay holds back score and clock, not names`() = runTest {
        settings.value = ScoreSettings(delaySeconds = 30)
        var latest = game(ScoreState.IN, 50, 48, "Q2 5:00")
        provider.respond = { ScoreboardResult(listOf(latest)) }
        val repo = repository("2026-11-15T19:00:00Z")
        collect(repo)
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        val key = pacersCard.streamKey
        // First seen live: its score shows at once (live.23).
        repo.scores.value.getValue(key).let {
            assertEquals(ScoreState.IN, it.state)
            assertEquals(50, it.homeScore)
            assertEquals("Q2 5:00", it.clockText)
        }
        latest = game(ScoreState.IN, 53, 48, "Q2 4:40").let { it.copy(home = it.home.copy(displayName = "Sixers")) }
        advanceTimeBy(30_000); runCurrent() // fetched at 30 s: the new name at once, the score still the first one
        repo.scores.value.getValue(key).let {
            assertEquals(50, it.homeScore)
            assertEquals("Q2 5:00", it.clockText)
            assertEquals("Sixers", it.homeName)
        }
        advanceTimeBy(29_000); runCurrent()
        assertEquals(50, repo.scores.value.getValue(key).homeScore)
        advanceTimeBy(1_000); runCurrent() // released 30 s after it arrived
        assertEquals(53, repo.scores.value.getValue(key).homeScore)
        assertEquals("Q2 4:40", repo.scores.value.getValue(key).clockText)
    }

    @Test
    fun `a game first seen already final shows at once even when it may have just ended`() = runTest {
        settings.value = ScoreSettings(delaySeconds = 30)
        provider.respond = { ScoreboardResult(listOf(game(ScoreState.POST, 110, 100, "Final"))) }
        // Three hours after the start: ESPN gives no end time, so it may have ended a moment ago.
        val repo = repository("2026-11-15T21:00:00Z")
        collect(repo)
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        assertEquals(110, repo.scores.value.getValue(pacersCard.streamKey).homeScore)
    }

    @Test
    fun `a final long over shows at once with the delay on`() = runTest {
        settings.value = ScoreSettings(delaySeconds = 30)
        provider.respond = { ScoreboardResult(listOf(game(ScoreState.POST, 110, 100, "Final"))) }
        val repo = repository("2026-11-16T12:00:00Z") // 18 h after the start
        collect(repo)
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        assertEquals(110, repo.scores.value.getValue(pacersCard.streamKey).homeScore)
    }

    @Test
    fun `a card with no start shows no final from yesterday while today's feed has not loaded`() = runTest {
        val easternToday = ScoreFeed(EspnLeagueMap.NBA, LocalDate.of(2026, 11, 16))
        // Yesterday's (Eastern) final; tonight's meeting of the same pair is on today's feed.
        val yesterdayFinal = game(ScoreState.POST, 110, 100, "Final").copy(eventId = "yesterday", startMs = ms("2026-11-16T00:10:00Z"))
        val tonight = game(ScoreState.PRE).copy(eventId = "tonight", startMs = ms("2026-11-17T00:10:00Z"))
        var todayFails = true
        provider.respond = { feed ->
            when {
                feed != easternToday -> ScoreboardResult(listOf(yesterdayFinal))
                todayFails -> ScoreboardResult.FAILED
                else -> ScoreboardResult(listOf(tonight))
            }
        }
        // 22:00 in Berlin, 16:00 Eastern.
        val repo = repository("2026-11-16T21:00:00Z", zone = ZoneId.of("Europe/Berlin"))
        collect(repo)
        val channel = item("[Pacers vs 76ers]", "Basketball", null).copy(isChannel = true)
        repo.track(SECTION, listOf(channel))
        runCurrent()
        assertTrue(repo.scores.value.isEmpty())
        todayFails = false
        advanceTimeBy(5 * 60_000L); runCurrent()
        assertEquals("tonight", repo.scores.value.getValue(channel.streamKey).eventId)
    }

    @Test
    fun `turning the delay on mid-game keeps the score shown and holds the next change`() = runTest {
        var home = 50
        provider.respond = { ScoreboardResult(listOf(game(ScoreState.IN, home, 48, "Q2 5:00"))) }
        val repo = repository("2026-11-15T19:00:00Z")
        collect(repo)
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        val key = pacersCard.streamKey
        assertEquals(50, repo.scores.value.getValue(key).homeScore)
        settings.value = ScoreSettings(delaySeconds = 60)
        advanceTimeBy(1_000); runCurrent()
        // live.23: first seen under the delay, so it stays; only changes from now on wait.
        assertEquals(50, repo.scores.value.getValue(key).homeScore)
        home = 53
        advanceTimeBy(30_000); runCurrent() // fetched
        assertEquals(50, repo.scores.value.getValue(key).homeScore)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(53, repo.scores.value.getValue(key).homeScore)
    }

    @Test
    fun `a game that leaves and comes back keeps its delay history`() = runTest {
        settings.value = ScoreSettings(delaySeconds = 30)
        var latest = game(ScoreState.IN, 50, 48)
        provider.respond = { ScoreboardResult(listOf(latest)) }
        val repo = repository("2026-11-15T19:00:00Z")
        collect(repo)
        val other = item("Los Angeles Lakers at Phoenix Suns", "Basketball", "2026-11-15T20:30:00Z")
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(50, repo.scores.value.getValue(pacersCard.streamKey).homeScore)
        latest = game(ScoreState.IN, 53, 48)
        repo.track(SECTION, listOf(other)) // the card leaves the screen...
        runCurrent()
        repo.track(SECTION, listOf(pacersCard, other)) // ...and comes back with a newer score fetched since
        advanceTimeBy(30_000); runCurrent()
        assertEquals(50, repo.scores.value.getValue(pacersCard.streamKey).homeScore)
    }

    @Test
    fun `score delay Off passes changes through at once`() = runTest {
        var latest = game(ScoreState.IN, 50, 48)
        provider.respond = { ScoreboardResult(listOf(latest)) }
        val repo = repository("2026-11-15T19:00:00Z")
        collect(repo)
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        latest = game(ScoreState.IN, 53, 48)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(53, repo.scores.value.getValue(pacersCard.streamKey).homeScore)
    }

    @Test
    fun `a merged card is keyed by its primary and routed by an alternate`() = runTest {
        provider.respond = { ScoreboardResult(nbaFixture) }
        val repo = repository("2026-11-16T12:00:00Z")
        collect(repo)
        val alternate = item("Pacers vs 76ers", "Basketball", "2026-11-15T18:00:00Z", addon = "b")
        val primary = item("Indiana Pacers at Philadelphia 76ers", null, "2026-11-15T18:00:00Z", alternates = listOf(alternate))
        repo.track(SECTION, listOf(primary))
        runCurrent()
        assertEquals(setOf(primary.streamKey), repo.scores.value.keys)
        assertNull(repo.scores.value[alternate.streamKey])
        assertEquals("401909985", repo.scores.value.getValue(primary.streamKey).eventId)
    }

    @Test
    fun `a soccer competition miss falls back to soccer all, whose league is then polled`() = runTest {
        val all = EspnFixtures.events(EspnFixtures.SOCCER_ALL_20261116, EspnLeagueMap.SOCCER_ALL)
        val nationsLeague = EspnLeague("soccer", "uefa.nations")
        provider.respond = { feed ->
            when (feed.league) {
                EspnLeagueMap.SOCCER_ALL -> ScoreboardResult(all)
                nationsLeague -> ScoreboardResult(all.filter { it.leagueSlug == "uefa.nations" }.map { it.copy(league = nationsLeague) })
                else -> ScoreboardResult(emptyList())
            }
        }
        val repo = repository("2026-11-16T22:00:00Z")
        collect(repo)
        val card = item("Germany vs Netherlands", "Soccer", "2026-11-16T19:45:00Z", genres = listOf("Premier League"))
        repo.track(SECTION, listOf(card))
        runCurrent()
        val day = LocalDate.of(2026, 11, 16)
        assertEquals(
            listOf(ScoreFeed(EspnFixtures.ENG1, day), ScoreFeed(EspnLeagueMap.SOCCER_ALL, day), ScoreFeed(nationsLeague, day)),
            provider.calls
        )
        assertEquals("401861185", repo.scores.value.getValue(card.streamKey).eventId)
        assertEquals(nationsLeague, repo.scores.value.getValue(card.streamKey).league)
    }

    @Test
    fun `dates are ESPN's US Eastern days within 3 h of the start`() = runTest {
        val repo = repository("2026-11-16T12:00:00Z", zone = ZoneId.of("Europe/London"))
        val today = LocalDate.of(2026, 11, 16)
        fun dates(name: String, start: String?, timed: Boolean = true) =
            repo.datesFor(ScoreCard.from(item(name, "Basketball", start, timed))!!).toSet()
        // 23:00 Eastern on the 15th (04:00 UTC on the 16th): the 15th and, within 3 h, the 16th.
        assertEquals(setOf(LocalDate.of(2026, 11, 15), today), dates("A vs B", "2026-11-16T04:00:00Z"))
        // 20:00 Eastern on the 15th is 01:00 UTC on the 16th, but ESPN files it on the 15th only.
        assertEquals(setOf(LocalDate.of(2026, 11, 15)), dates("A vs B", "2026-11-16T01:00:00Z"))
        assertEquals(setOf(LocalDate.of(2026, 11, 15)), dates("A vs B", "2026-11-15T18:00:00Z"))
        assertEquals(setOf(LocalDate.of(2026, 11, 14)), dates("A vs B", "2026-11-14T00:00:00Z", timed = false))
        assertEquals(setOf(today, LocalDate.of(2026, 11, 15)), dates("A vs B", null))
    }

    @Test
    fun `a card with no start asks for Eastern today and yesterday, not the device's days`() = runTest {
        // 08:00 in Tokyo on the 18th is 18:00 Eastern on the 17th: the device's 18th is no ESPN day yet.
        val repo = repository("2026-11-17T23:00:00Z", zone = ZoneId.of("Asia/Tokyo"))
        assertEquals(
            listOf(LocalDate.of(2026, 11, 17), LocalDate.of(2026, 11, 16)),
            repo.datesFor(ScoreCard.from(item("A vs B", "Basketball", null))!!)
        )
    }

    @Test
    fun `re-tracking within the interval does not refetch`() = runTest {
        provider.respond = { ScoreboardResult(listOf(game(ScoreState.IN, 50, 48))) }
        val repo = repository("2026-11-15T19:00:00Z")
        collect(repo)
        val hockey = item("Bruins vs Rangers", "Hockey", "2026-11-15T18:00:00Z")
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        advanceTimeBy(5_000); repo.track(SECTION, listOf(hockey)); runCurrent()
        advanceTimeBy(5_000); repo.track(SECTION, listOf(pacersCard)); runCurrent()
        assertEquals(1, provider.calls.count { it == nba15 })
        assertEquals(50, repo.scores.value.getValue(pacersCard.streamKey).homeScore)
        advanceTimeBy(20_000); runCurrent() // 30 s after the first fetch
        assertEquals(2, provider.calls.count { it == nba15 })
    }

    @Test
    fun `an in-flight request is shared across a poller restart`() = runTest {
        provider.respond = { ScoreboardResult(listOf(game(ScoreState.IN, 50, 48))) }
        val gate = CompletableDeferred<Unit>()
        provider.gate = gate
        val repo = repository("2026-11-15T19:00:00Z")
        collect(repo)
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        settings.value = ScoreSettings(delaySeconds = 15) // restarts the pollers
        runCurrent()
        assertEquals(1, provider.calls.size)
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, provider.calls.size)
        assertEquals(1, repo.scores.value.size)
    }

    @Test
    fun `Retry-After stretches the backoff and three failures still pause the feed`() = runTest {
        var calls = 0
        provider.respond = {
            if (calls++ == 0) ScoreboardResult(listOf(game(ScoreState.IN, 1, 0)))
            else ScoreboardResult.FAILED.copy(retryAfterMs = 120_000)
        }
        val repo = repository("2026-11-15T19:00:00Z")
        collect(repo)
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        advanceTimeBy(30_000); runCurrent() // failure 1
        advanceTimeBy(120_000); runCurrent() // failure 2 (Retry-After 120 s > 30 s)
        assertEquals(3, provider.calls.size)
        assertEquals(1, repo.scores.value.size)
        advanceTimeBy(119_000); runCurrent()
        assertEquals(3, provider.calls.size)
        advanceTimeBy(1_000); runCurrent() // failure 3: paused, scores hidden
        assertEquals(4, provider.calls.size)
        assertTrue(repo.scores.value.isEmpty())
    }

    @Test
    fun `soccer all is polled no more than every 60 s even while live`() = runTest {
        val all = EspnFixtures.events(EspnFixtures.SOCCER_ALL_20261116, EspnLeagueMap.SOCCER_ALL)
        // No league link of its own (slug "all"), so the card stays on soccer/all.
        val live = all.first { it.eventId == "401861185" }.copy(state = ScoreState.IN, isFinal = false, leagueSlug = "all")
        provider.respond = { ScoreboardResult(listOf(live)) }
        val repo = repository("2026-11-16T20:00:00Z")
        collect(repo)
        repo.track(SECTION, listOf(item("Germany vs Netherlands", "Soccer", "2026-11-16T19:45:00Z")))
        runCurrent()
        assertEquals(1, provider.calls.size)
        assertEquals(EspnLeagueMap.SOCCER_ALL, provider.calls.single().league)
        advanceTimeBy(59_000); runCurrent()
        assertEquals(1, provider.calls.size)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, provider.calls.size)
    }

    @Test
    fun `once matched, a late game stops polling the next Eastern day`() = runTest {
        val late = game(ScoreState.IN, 10, 8).copy(startMs = ms("2026-11-16T04:00:00Z")) // 23:00 Eastern
        val nextDay = ScoreFeed(EspnLeagueMap.NBA, LocalDate.of(2026, 11, 16))
        provider.respond = { feed -> if (feed == nba15) ScoreboardResult(listOf(late)) else ScoreboardResult(emptyList()) }
        val repo = repository("2026-11-16T04:30:00Z")
        collect(repo)
        repo.track(SECTION, listOf(item("Indiana Pacers at Philadelphia 76ers", "Basketball", "2026-11-16T04:00:00Z")))
        runCurrent()
        advanceTimeBy(10 * 60_000L); runCurrent()
        assertEquals(1, provider.calls.count { it == nextDay })
        assertEquals(21, provider.calls.count { it == nba15 })
    }

    @Test
    fun `each screen tracks its own cards and the repository polls their union`() = runTest {
        provider.respond = { ScoreboardResult(nbaFixture) }
        val repo = repository("2026-11-16T12:00:00Z")
        collect(repo)
        val lakers = item("Los Angeles Lakers at Phoenix Suns", "Basketball", "2026-11-15T20:30:00Z")
        repo.track(HOME, listOf(pacersCard))
        repo.track(SECTION, listOf(lakers))
        runCurrent()
        // Neither screen replaced the other's cards.
        assertEquals(setOf(pacersCard.streamKey, lakers.streamKey), repo.scores.value.keys)
        // A screen's new set replaces only its own.
        repo.track(SECTION, emptyList())
        runCurrent()
        assertEquals(setOf(pacersCard.streamKey), repo.scores.value.keys)
        repo.track(HOME, emptyList())
        runCurrent()
        assertTrue(repo.scores.value.isEmpty())
    }

    @Test
    fun `the same card on two screens is one card on one feed request`() = runTest {
        provider.respond = { ScoreboardResult(nbaFixture) }
        val repo = repository("2026-11-16T12:00:00Z")
        collect(repo)
        repo.track(HOME, listOf(pacersCard))
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        assertEquals(listOf(nba15), provider.calls)
        assertEquals(setOf(pacersCard.streamKey), repo.scores.value.keys)
        // Leaving one screen keeps the card scored for the other.
        repo.track(HOME, emptyList())
        runCurrent()
        assertEquals(setOf(pacersCard.streamKey), repo.scores.value.keys)
    }

    @Test
    fun `an untracked screen stops polling its feeds and leaves the other screen's`() = runTest {
        provider.respond = { ScoreboardResult(listOf(game(ScoreState.IN, 50, 48))) }
        val repo = repository("2026-11-15T19:00:00Z")
        collect(repo)
        repo.track(HOME, listOf(pacersCard))
        runCurrent()
        assertEquals(1, provider.calls.size)
        repo.untrack(HOME)
        runCurrent()
        assertTrue(repo.scores.value.isEmpty())
        advanceTimeBy(10 * 60_000L); runCurrent()
        assertEquals(1, provider.calls.size)
        // Untracking a screen that tracks nothing changes nothing for the others.
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        repo.untrack(HOME)
        runCurrent()
        assertEquals(setOf(pacersCard.streamKey), repo.scores.value.keys)
    }

    @Test
    fun `over the feed budget, followed teams' games poll first`() = runTest {
        provider.respond = { ScoreboardResult(emptyList()) }
        val repo = repository("2026-11-01T12:00:00Z")
        collect(repo)
        // One NBA game a day, each on its own feed (league + date); the followed one is listed last.
        val days = (1..LiveScoresRepository.MAX_FEEDS + 2).map { LocalDate.of(2026, 11, 1).plusDays(it - 1L) }
        val others = days.dropLast(1).map { item("Team$it A vs Team$it B", "Basketball", "${it}T18:00:00Z") }
        val followed = item("Indiana Pacers at Philadelphia 76ers", "Basketball", "${days.last()}T18:00:00Z", followed = true)
        repo.track(SECTION, others + followed)
        runCurrent()
        assertEquals(LiveScoresRepository.MAX_FEEDS, provider.calls.distinct().size)
        assertTrue(ScoreFeed(EspnLeagueMap.NBA, days.last()) in provider.calls)
        // The cards past the budget are not polled at all.
        assertTrue(ScoreFeed(EspnLeagueMap.NBA, days[days.size - 2]) !in provider.calls)
    }

    /** A game in play on [league]'s feed, named as its card names it ("Parma vs Roma": Parma at home). */
    private fun liveGame(id: String, league: EspnLeague, card: String, start: String, home: Int, away: Int): ScoreSnapshot {
        val (first, second) = card.split(" vs ")
        fun side(name: String, score: Int) = ScoreTeam(
            id = name, abbreviation = null, displayName = name, shortDisplayName = name, name = name, location = null,
            logo = null, score = score, linescores = emptyList()
        )
        return pacers.copy(
            eventId = id, league = league, leagueSlug = league.league, leagueName = null, competitionId = null,
            startMs = ms(start), state = ScoreState.IN, isFinal = false, clockText = "23'",
            home = side(first, home), away = side(second, away)
        )
    }

    @Test
    fun `Live now's games of every league are polled and scored, never crowded out by a week of followed games`() = runTest {
        // live.23 RC, TV 11:52 CT: Premier League and Ligue 1 scored, Serie A, Bundesliga, La Liga and college football
        // games in play did not. Live now also tracked every followed game on Upcoming, a week of them (each its own
        // feed), and followed teams' games took the feed budget first.
        val today = LocalDate.of(2026, 11, 21)
        val eng1 = EspnLeague("soccer", "eng.1")
        val live = listOf(
            Triple(eng1, "Premier League", "Newcastle vs Arsenal"),
            Triple(EspnLeague("soccer", "fra.1"), "Ligue 1", "Nice vs Lens"),
            Triple(EspnLeague("soccer", "ita.1"), "Serie A", "Parma vs Roma"),
            Triple(EspnLeague("soccer", "ger.1"), "Bundesliga", "Schalke vs Stuttgart"),
            Triple(EspnLeague("soccer", "esp.1"), "La Liga", "Sevilla vs Betis"),
            Triple(EspnLeagueMap.COLLEGE_FOOTBALL, "NCAAF", "Army vs Tulane")
        ).mapIndexed { index, (league, sport, name) ->
            val card = item(name, sport, "2026-11-21T17:30:00Z", followed = index == 0)
            Triple(league, card, liveGame("live$index", league, name, "2026-11-21T17:30:00Z", 1, index))
        }
        provider.respond = { feed ->
            ScoreboardResult(live.filter { (league, _, _) -> league == feed.league && feed.date == today }.map { it.third })
        }
        val repo = repository("2026-11-21T17:52:00Z")
        collect(repo)
        // A followed team's games on each of the next weeks' days: more feeds than the whole budget.
        val followedLater = (1..LiveScoresRepository.MAX_FEEDS + 4).map { day ->
            item("Lakers vs Team$day", "NBA", "${today.plusDays(day.toLong())}T23:00:00Z", followed = true).copy(isLive = false)
        }
        repo.track(SECTION, live.map { it.second } + followedLater)
        runCurrent()
        for ((league, card, game) in live) {
            assertTrue("${league.path} polled", ScoreFeed(league, today) in provider.calls)
            assertEquals("${card.meta.name} scored", game.eventId, repo.scores.value[card.streamKey]?.eventId)
        }
        // The budget still holds; the followed later games take what is left.
        assertTrue(provider.calls.distinct().size <= LiveScoresRepository.MAX_FEEDS)
        assertTrue(ScoreFeed(EspnLeagueMap.NBA, today.plusDays(1)) in provider.calls)
    }

    @Test
    fun `every live game's own feed is budgeted before any game's soccer-all fallback or extra days`() = runTest {
        // live.23 RC7, TV 12:28 CT: Serie A, NBA and NHL scored, Bundesliga, La Liga and Ligue 1 games in play did not.
        // The games listed before them (no start, ESPN without the game) took their own two days, then soccer/all's
        // fallback and ESPN's later days, and the budget ran out before the later games' own feeds.
        val today = LocalDate.of(2026, 11, 21)
        val elsewhere = listOf("Champions League", "Europa League", "Conference League", "Nations League", "Friendly")
            .mapIndexed { index, competition -> item("Club$index A vs Club$index B", "Soccer", null, genres = listOf(competition)) }
        val top = listOf(
            Triple(EspnLeague("soccer", "eng.1"), "Premier League", "Newcastle vs Arsenal"),
            Triple(EspnLeague("soccer", "ita.1"), "Serie A", "Parma vs Roma"),
            Triple(EspnLeague("soccer", "ger.1"), "Bundesliga", "Schalke 04 vs Stuttgart"),
            Triple(EspnLeague("soccer", "esp.1"), "La Liga", "Sevilla vs Betis"),
            Triple(EspnLeague("soccer", "fra.1"), "Ligue 1", "Nice vs Lens")
        ).mapIndexed { index, (league, sport, name) ->
            Triple(league, item(name, sport, "2026-11-21T17:30:00Z"), liveGame("top$index", league, name, "2026-11-21T17:30:00Z", 1, 0))
        }
        provider.respond = { feed ->
            ScoreboardResult(top.filter { (league, _, _) -> league == feed.league && feed.date == today }.map { it.third })
        }
        val repo = repository("2026-11-21T18:28:00Z")
        collect(repo)
        repo.track(SECTION, elsewhere + top.map { it.second })
        runCurrent()
        advanceTimeBy(60_000L); runCurrent()
        for ((league, card, game) in top) {
            assertTrue("${league.path} polled", ScoreFeed(league, today) in provider.calls)
            assertEquals("${card.meta.name} scored", game.eventId, repo.scores.value[card.streamKey]?.eventId)
        }
        // The games before them keep their own feeds; only their extras wait for a free slot.
        for (competition in listOf("uefa.champions", "uefa.europa", "uefa.europa.conf", "uefa.nations", "fifa.friendly")) {
            assertTrue(competition, ScoreFeed(EspnLeague("soccer", competition), today) in provider.calls)
        }
    }

    @Test
    fun `the hold the scores keep for the stream playing is told, and none while nothing plays`() = runTest {
        // TV report #14: the guide says "Scores held 1:15 to match your stream".
        provider.respond = { ScoreboardResult(listOf(game(ScoreState.IN, 50, 48, "Q2 5:00"))) }
        val repo = repository("2026-11-15T19:00:00Z")
        collect(repo)
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        assertNull(repo.scoreHoldMs.value)
        watching.watch("player", setOf("another game"), "addon")
        runCurrent()
        assertEquals(ScoreWatch.MIN_PLAYING_DELAY_MS, repo.scoreHoldMs.value)
        watching.behindLive("player", 70_000L)
        runCurrent()
        assertEquals(70_000L + ScoreWatch.MARGIN_MS, repo.scoreHoldMs.value)
        watching.stop("player")
        runCurrent()
        assertNull(repo.scoreHoldMs.value)
    }

    @Test
    fun `a card re-tracked on a stale feed is never confirmed unmatched before the feed answers again`() = runTest {
        // live.23 review H1: back on Live now after 5 minutes, every live card read "no score" until its feed refetched.
        provider.respond = { ScoreboardResult(listOf(game(ScoreState.IN, 50, 48, "Q2 5:00"))) }
        val repo = repository("2026-11-15T19:00:00Z")
        collect(repo)
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        assertEquals(50, repo.scores.value[pacersCard.streamKey]?.homeScore)
        repo.untrack(SECTION)
        runCurrent()
        advanceTimeBy(5 * 60_000L); runCurrent()
        val gate = CompletableDeferred<Unit>()
        provider.gate = gate
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        // The feed was fetched before but is stale now: it says nothing about the game.
        assertTrue(pacersCard.streamKey !in repo.unmatched.value)
        gate.complete(Unit)
        runCurrent()
        assertTrue(pacersCard.streamKey !in repo.unmatched.value)
        assertEquals(50, repo.scores.value[pacersCard.streamKey]?.homeScore)
    }

    @Test
    fun `a paused (failing) feed never confirms its cards unmatched`() = runTest {
        // live.23 review H1: an ESPN outage of one league read "no score" on all its live games, and opened the ended estimate.
        provider.respond = { ScoreboardResult.FAILED }
        val repo = repository("2026-11-15T19:00:00Z")
        collect(repo)
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        advanceTimeBy(30_001L); runCurrent()
        advanceTimeBy(60_001L); runCurrent()
        assertTrue(provider.calls.size >= ScoreCadence.PAUSE_AFTER_FAILURES)
        assertTrue(repo.unmatched.value.isEmpty())
    }

    @Test
    fun `over the budget a card with no start is never matched on yesterday's feed alone, nor confirmed unmatched`() = runTest {
        // live.23 review M4: (NBA, yesterday) polled for another card, (NBA, today) over the budget: last night's final of the
        // pair landed on today's live card, or the card read "no score" though its own feed was never asked.
        val yesterday = LocalDate.of(2026, 11, 15)
        provider.respond = { feed -> if (feed == nba15) ScoreboardResult(listOf(game(ScoreState.POST, 110, 100, "Final"))) else ScoreboardResult(emptyList()) }
        val repo = repository("2026-11-16T12:00:00Z")
        collect(repo)
        val lastNight = item("Miami Heat at Chicago Bulls", "NBA", "${yesterday}T18:00:00Z")
        val fillers = (1 until LiveScoresRepository.MAX_FEEDS).map { day ->
            item("Team$day A vs Team$day B", "NBA", "${LocalDate.of(2026, 11, 17).plusDays(day.toLong())}T18:00:00Z")
        }
        val undated = item("Indiana Pacers at Philadelphia 76ers", "NBA", null)
        repo.track(SECTION, listOf(lastNight) + fillers + undated)
        runCurrent()
        assertTrue(nba15 in provider.calls)
        assertTrue(ScoreFeed(EspnLeagueMap.NBA, LocalDate.of(2026, 11, 16)) !in provider.calls)
        assertNull(repo.scores.value[undated.streamKey])
        assertTrue(undated.streamKey !in repo.unmatched.value)
    }

    @Test
    fun `after a pause no old score shows until a fresh fetch`() = runTest {
        provider.respond = { ScoreboardResult(listOf(game(ScoreState.IN, 50, 48, "Q2 5:00"))) }
        val repo = repository("2026-11-15T19:00:00Z")
        val collector = collect(repo)
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        assertEquals(1, repo.scores.value.size)
        // The player opens: no one collects; the shown scores are dropped.
        collector.cancel()
        runCurrent()
        assertTrue(repo.scores.value.isEmpty())
        advanceTimeBy(10 * 60_000L); runCurrent()
        // Back: the refetch is held, and the 10-minute-old score must not come back meanwhile.
        val gate = CompletableDeferred<Unit>()
        provider.gate = gate
        provider.respond = { ScoreboardResult(listOf(game(ScoreState.IN, 70, 66, "Q3 1:00"))) }
        collect(repo)
        runCurrent()
        assertTrue(repo.scores.value.isEmpty())
        gate.complete(Unit)
        runCurrent()
        assertEquals(70, repo.scores.value.getValue(pacersCard.streamKey).homeScore)
    }

    // --- #66: every game on screen, college fallback, logos, warm start, cold path ---

    private val cbbFixture = EspnFixtures.events(EspnFixtures.CBB_20261115, EspnLeagueMap.COLLEGE_BASKETBALL)
    private val cbb15 = ScoreFeed(EspnLeagueMap.COLLEGE_BASKETBALL, LocalDate.of(2026, 11, 15))
    private val baylorCard = item("Baylor Bears at Texas Longhorns", "Basketball", "2026-11-15T20:00:00Z")

    private fun nbaAndCollege(feed: ScoreFeed) = when (feed.league) {
        EspnLeagueMap.NBA -> ScoreboardResult(nbaFixture)
        EspnLeagueMap.COLLEGE_BASKETBALL -> ScoreboardResult(cbbFixture)
        else -> ScoreboardResult(emptyList())
    }

    @Test
    fun `an on-screen college card is polled and matched when My sports leaves college out`() = runTest {
        provider.respond = ::nbaAndCollege
        mySports.value = MySports(mapOf(MySport.BASKETBALL to setOf(MyLeague.NBA)))
        val repo = repository("2026-11-15T21:00:00Z")
        collect(repo)
        val named = item("Baylor Bears at Texas Longhorns", "Basketball", "2026-11-15T20:00:00Z", genres = listOf("College Basketball"))
        repo.track(SECTION, listOf(named))
        runCurrent()
        assertEquals(listOf(cbb15), provider.calls)
        assertEquals("401906866", repo.scores.value.getValue(named.streamKey).eventId)
    }

    @Test
    fun `a league outside My sports is scored on screen, and unchosen leagues with no card are not polled`() = runTest {
        provider.respond = { feed -> if (feed == nba15) ScoreboardResult(listOf(game(ScoreState.IN, 50, 48))) else ScoreboardResult(emptyList()) }
        mySports.value = MySports(mapOf(MySport.HOCKEY to emptySet()))
        val repo = repository("2026-11-15T19:00:00Z")
        collect(repo)
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        // Only the card's own feed: no NHL (picked, but no card), no college (the card matched).
        assertEquals(listOf(nba15), provider.calls)
        assertEquals(setOf(pacersCard.streamKey), repo.scores.value.keys)
        advanceTimeBy(30 * 60_000L); runCurrent()
        assertTrue(provider.calls.all { it.league == EspnLeagueMap.NBA })
    }

    @Test
    fun `a soccer competition not picked in My sports still shows the score of its card`() = runTest {
        val eng1 = EspnFixtures.events(EspnFixtures.ENG1_20261108, EspnFixtures.ENG1)
        provider.respond = { feed -> if (feed.league == EspnFixtures.ENG1) ScoreboardResult(eng1) else ScoreboardResult(emptyList()) }
        mySports.value = MySports(mapOf(MySport.SOCCER to setOf(MyLeague.LA_LIGA)))
        val repo = repository("2026-11-08T15:00:00Z")
        collect(repo)
        val palace = item("Crystal Palace vs Liverpool", "Soccer", "2026-11-08T14:00:00Z", genres = listOf("Premier League"))
        repo.track(SECTION, listOf(palace))
        runCurrent()
        assertEquals(setOf(palace.streamKey), repo.scores.value.keys)
    }

    @Test
    fun `a bare Basketball card missing from the NBA feed is matched on the college feed`() = runTest {
        provider.respond = ::nbaAndCollege
        val repo = repository("2026-11-15T21:00:00Z")
        collect(repo)
        repo.track(SECTION, listOf(baylorCard))
        runCurrent()
        assertEquals(listOf(nba15, cbb15), provider.calls)
        assertEquals("401906866", repo.scores.value.getValue(baylorCard.streamKey).eventId)
        assertTrue(baylorCard.streamKey !in repo.unmatched.value)
        // From now on it is polled on its college feed alone.
        provider.calls.clear()
        advanceTimeBy(60 * 60_000L); runCurrent()
        assertTrue(provider.calls.none { it.league == EspnLeagueMap.NBA })
        assertEquals("401906866", repo.scores.value.getValue(baylorCard.streamKey).eventId)
    }

    @Test
    fun `the college feed is tried only for an unmatched card with a bare sport`() = runTest {
        provider.respond = ::nbaAndCollege
        val repo = repository("2026-11-15T21:00:00Z")
        collect(repo)
        // Matched on the NBA feed: no college request.
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        assertEquals(listOf(nba15), provider.calls)
        // Names the NBA: never the college feed, even unmatched.
        val namedNba = item("Baylor Bears at Texas Longhorns", "NBA", "2026-11-15T20:00:00Z")
        repo.track(SECTION, listOf(namedNba))
        runCurrent()
        assertTrue(cbb15 !in provider.calls)
        assertTrue(namedNba.streamKey in repo.unmatched.value)
        // A women's card goes to the women's feeds only (#163): never the NBA or the men's college feed.
        provider.calls.clear()
        repo.track(SECTION, listOf(item("Baylor Bears at Texas Longhorns", "Women's Basketball", "2026-11-15T20:00:00Z")))
        runCurrent()
        assertTrue(provider.calls.isNotEmpty())
        assertTrue(provider.calls.all { it.league in EspnLeagueMap.WOMEN_FEEDS })
    }

    @Test
    fun `an unmatched bare card is confirmed only once the college feed has answered too`() = runTest {
        provider.respond = { ScoreboardResult(if (it.league == EspnLeagueMap.NBA) nbaFixture else emptyList()) }
        val gate = CompletableDeferred<Unit>()
        val repo = repository("2026-11-15T21:00:00Z")
        collect(repo)
        val unknown = item("Nowhere Owls at Somewhere Hawks", "Basketball", "2026-11-15T20:00:00Z")
        repo.track(SECTION, listOf(unknown))
        provider.gateFor = mapOf(cbb15 to gate)
        runCurrent()
        assertEquals(listOf(nba15, cbb15), provider.calls)
        assertTrue(repo.unmatched.value.isEmpty())
        gate.complete(Unit)
        runCurrent()
        assertEquals(setOf(unknown.streamKey), repo.unmatched.value)
    }

    @Test
    fun `the first fetch fires at once and leagues are fetched in parallel`() = runTest {
        val gate = CompletableDeferred<Unit>()
        provider.gate = gate
        provider.respond = ::nbaAndCollege
        val repo = repository("2026-11-15T21:00:00Z")
        collect(repo)
        val named = item("Baylor Bears at Texas Longhorns", "Basketball", "2026-11-15T20:00:00Z", genres = listOf("College Basketball"))
        repo.track(SECTION, listOf(pacersCard, named))
        runCurrent()
        // No time has passed and neither request has answered: both are in flight together.
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(setOf(nba15, cbb15), provider.calls.toSet())
        gate.complete(Unit)
        runCurrent()
        assertEquals(setOf(pacersCard.streamKey, named.streamKey), repo.scores.value.keys)
    }

    @Test
    fun `a dated card is matched as soon as its own day lands, whatever another card waits for`() = runTest {
        provider.respond = { ScoreboardResult(listOf(game(ScoreState.IN, 50, 48))) }
        val yesterday = CompletableDeferred<Unit>()
        val repo = repository("2026-11-15T19:00:00Z")
        collect(repo)
        // An undated card asks for Eastern today and yesterday; yesterday's feed hangs.
        provider.gateFor = mapOf(ScoreFeed(EspnLeagueMap.NBA, LocalDate.of(2026, 11, 14)) to yesterday)
        val undated = item("Indiana Pacers vs Philadelphia 76ers", "Basketball", null, addon = "b")
        repo.track(SECTION, listOf(pacersCard, undated))
        runCurrent()
        assertTrue(pacersCard.streamKey in repo.scores.value)
        assertTrue(undated.streamKey !in repo.scores.value)
        yesterday.complete(Unit)
        runCurrent()
        assertTrue(undated.streamKey in repo.scores.value)
    }

    @Test
    fun `a warm copy shows at once and the network fetch follows immediately`() = runTest {
        val received = ms("2026-11-15T18:59:30Z")
        provider.recent = { feed -> if (feed == nba15) ScoreboardResult(listOf(game(ScoreState.IN, 40, 38)), receivedAtMs = received) else null }
        provider.respond = { ScoreboardResult(listOf(game(ScoreState.IN, 50, 48))) }
        val gate = CompletableDeferred<Unit>()
        provider.gate = gate
        val repo = repository("2026-11-15T19:00:00Z")
        collect(repo)
        val heat = item("Miami Heat at Chicago Bulls", "Basketball", "2026-11-15T18:00:00Z")
        repo.track(SECTION, listOf(pacersCard, heat))
        runCurrent()
        assertEquals(40, repo.scores.value.getValue(pacersCard.streamKey).homeScore)
        assertEquals(listOf(nba15), provider.calls)
        // A warm copy never confirms a card unmatched.
        assertTrue(repo.unmatched.value.isEmpty())
        gate.complete(Unit)
        runCurrent()
        assertEquals(50, repo.scores.value.getValue(pacersCard.streamKey).homeScore)
        assertEquals(setOf(heat.streamKey), repo.unmatched.value)
    }

    @Test
    fun `a warm copy shows at once like any first-seen live game`() = runTest {
        settings.value = ScoreSettings(delaySeconds = 30)
        provider.recent = { ScoreboardResult(listOf(game(ScoreState.IN, 40, 38)), receivedAtMs = ms("2026-11-15T18:59:30Z")) }
        provider.gate = CompletableDeferred()
        val repo = repository("2026-11-15T19:00:00Z")
        collect(repo)
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        assertEquals(40, repo.scores.value.getValue(pacersCard.streamKey).homeScore)
    }

    @Test
    fun `matched sides' logos are learned, saved and prefetched scaled`() = runTest {
        provider.respond = { ScoreboardResult(nbaFixture) }
        val dir = java.nio.file.Files.createTempDirectory("logos").toFile()
        val file = TeamLogoFile(java.io.File(dir, "team_logos.tsv"))
        val prefetched = mutableListOf<String>()
        val repo = repository("2026-11-16T12:00:00Z", logoFile = file, prefetch = { prefetched += it })
        collect(repo)
        val card = item("Pacers at 76ers", "Basketball", "2026-11-15T18:00:00Z")
        repo.track(SECTION, listOf(card))
        runCurrent()
        val known = repo.logos.resolve(EspnLeagueMap.NBA, "Pacers")!!
        assertEquals("IND", known.abbreviation)
        assertEquals(pacers.away.logo, known.logo)
        assertTrue(prefetched.isNotEmpty())
        assertTrue(prefetched.all { it.startsWith("https://a.espncdn.com/combiner/i?img=/i/") && it.endsWith("&w=96&h=96") })
        // Written a moment later, off the poll path; a new book reads it back.
        assertNull(file.read())
        advanceTimeBy(5_000L); runCurrent()
        val reloaded = TeamLogoBook().apply { load(file.read()!!) }
        assertEquals(known, reloaded.resolve(EspnLeagueMap.NBA, "Indiana Pacers"))
        dir.deleteRecursively()
    }

    @Test
    fun `USA vs Haiti is looked up on ESPN's Eastern day and matched on the live feed`() = runTest {
        val live = EspnFixtures.events(EspnFixtures.SOCCER_ALL_LIVE_20261117, EspnLeagueMap.SOCCER_ALL)
        val nationsLeague = EspnLeague("soccer", "concacaf.nations.league")
        provider.respond = { feed -> if (feed.league == nationsLeague) ScoreboardResult(live) else ScoreboardResult(emptyList()) }
        mySports.value = MySports(mapOf(MySport.SOCCER to setOf(MyLeague.PREMIER_LEAGUE)))
        val repo = repository("2026-11-18T00:50:00Z", zone = ZoneId.of("Europe/London"))
        collect(repo)
        val card = item("USA vs Haiti", "Soccer", "2026-11-18T00:00:00Z", genres = listOf("CONCACAF Nations League"))
        repo.track(SECTION, listOf(card))
        runCurrent()
        // Kicked off 19:00 ET on the 17th: ESPN's 17th, on its own league's feed (#169: ESPN's league index names it).
        assertEquals(ScoreFeed(nationsLeague, LocalDate.of(2026, 11, 17)), provider.calls.first())
        assertTrue(provider.calls.all { it.date == LocalDate.of(2026, 11, 17) })
        assertEquals("401929766", repo.scores.value.getValue(card.streamKey).eventId)
    }

    // --- #66 review ---

    @Test
    fun `a warm copy is aged from when ESPN sent it and never outlives a failing fetch`() = runTest {
        // Received 100 s before now: shown (under 2 min), but the network fetch fails.
        provider.recent = { ScoreboardResult(listOf(game(ScoreState.IN, 40, 38)), receivedAtMs = ms("2026-11-15T18:58:20Z")) }
        provider.respond = { ScoreboardResult.FAILED }
        val gate = CompletableDeferred<Unit>()
        provider.gate = gate
        val repo = repository("2026-11-15T19:00:00Z")
        collect(repo)
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        assertEquals(40, repo.scores.value.getValue(pacersCard.streamKey).homeScore)
        provider.gate = null
        gate.complete(Unit)
        runCurrent()
        // The backoff retry 30 s later fails too: the copy is now 130 s old and is gone.
        advanceTimeBy(30_000L); runCurrent()
        assertTrue(repo.scores.value.isEmpty())
    }

    @Test
    fun `a warm copy older than two minutes by ESPN's time is not shown`() = runTest {
        provider.recent = { ScoreboardResult(listOf(game(ScoreState.IN, 40, 38)), receivedAtMs = ms("2026-11-15T18:57:50Z")) }
        provider.gate = CompletableDeferred()
        val repo = repository("2026-11-15T19:00:00Z")
        collect(repo)
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        assertTrue(repo.scores.value.isEmpty())
    }

    @Test
    fun `the college fallback needs both sides strong`() = runTest {
        provider.respond = ::nbaAndCollege
        val repo = repository("2026-11-16T01:00:00Z")
        collect(repo)
        // "Storm" is St. John's Red Storm only by the suffix rule (weak): with "Syracuse Orange" strong it
        // would reach the usual total, but not both-strong.
        val weak = item("Syracuse Orange at Storm", "Basketball", "2026-11-16T00:00:00Z")
        repo.track(SECTION, listOf(weak))
        runCurrent()
        assertTrue(cbb15 in provider.calls)
        assertTrue(repo.scores.value.isEmpty())
        assertEquals(setOf(weak.streamKey), repo.unmatched.value)
    }

    @Test
    fun `a college feed that misses a card twice stops polling until the cards change`() = runTest {
        provider.respond = { feed ->
            if (feed.league == EspnLeagueMap.NBA) ScoreboardResult(emptyList())
            else ScoreboardResult(listOf(game(ScoreState.IN, 1, 1).copy(league = EspnLeagueMap.COLLEGE_BASKETBALL)))
        }
        val repo = repository("2026-11-15T19:00:00Z")
        collect(repo)
        val unknown = item("Nowhere Owls at Somewhere Hawks", "Basketball", "2026-11-15T20:00:00Z")
        repo.track(SECTION, listOf(unknown))
        runCurrent()
        advanceTimeBy(60 * 60_000L); runCurrent()
        // Two college answers without the game, then no more.
        assertEquals(2, provider.calls.count { it == cbb15 })
        // A new card list tries again.
        repo.track(SECTION, listOf(unknown, pacersCard))
        runCurrent()
        advanceTimeBy(10 * 60_000L); runCurrent()
        assertTrue(provider.calls.count { it == cbb15 } > 2)
    }

    // --- #81: live scores refresh while the screen stays open ---

    private val nba14 = ScoreFeed(EspnLeagueMap.NBA, LocalDate.of(2026, 11, 14))

    /** An undated card whose today's feed (the live game) answers before yesterday's, as on a TV often. */
    private fun TestScope.undatedLiveGame(homeScore: () -> Int, delaySeconds: Int = 0): Pair<LiveScoresRepository, String> {
        settings.value = ScoreSettings(delaySeconds = delaySeconds)
        provider.respond = { feed -> ScoreboardResult(if (feed == nba15) listOf(game(ScoreState.IN, homeScore(), 48)) else emptyList()) }
        val yesterday = CompletableDeferred<Unit>()
        provider.gateFor = mapOf(nba14 to yesterday)
        val repo = repository("2026-11-15T19:00:00Z")
        collect(repo)
        val undated = item("Indiana Pacers vs Philadelphia 76ers", "Basketball", null)
        repo.track(SECTION, listOf(undated))
        runCurrent()
        // Today's feed has answered; the card waits for yesterday's (a league counts once all its days are in).
        assertTrue(undated.streamKey !in repo.scores.value)
        yesterday.complete(Unit)
        provider.gateFor = emptyMap()
        runCurrent()
        return repo to undated.streamKey
    }

    @Test
    fun `an undated live game matched after its other day lands keeps polling every 30 s on screen (#81)`() = runTest {
        var score = 50
        val (repo, key) = undatedLiveGame({ score })
        assertEquals(50, repo.scores.value.getValue(key).homeScore)
        score = 53
        advanceTimeBy(30_000); runCurrent()
        assertEquals(53, repo.scores.value.getValue(key).homeScore)
        score = 55
        advanceTimeBy(30_000); runCurrent()
        assertEquals(55, repo.scores.value.getValue(key).homeScore)
        // Today's feed at the live pace, never more often.
        assertEquals(3, provider.calls.count { it == nba15 })
    }

    @Test
    fun `with a score delay each poll is released the delay after it arrived, without leaving the screen (#81)`() = runTest {
        var score = 50
        val (repo, key) = undatedLiveGame({ score }, delaySeconds = 30)
        // First seen live: at once (live.23).
        assertEquals(50, repo.scores.value.getValue(key).homeScore)
        score = 53
        advanceTimeBy(30_000); runCurrent()
        assertEquals(50, repo.scores.value.getValue(key).homeScore)
        score = 55
        advanceTimeBy(30_000); runCurrent()
        assertEquals(53, repo.scores.value.getValue(key).homeScore)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(55, repo.scores.value.getValue(key).homeScore)
    }

    @Test
    fun `a followed team's live game polls its feed every 15 s, an unfollowed one every 30 s`() = runTest {
        var score = 50
        provider.respond = { ScoreboardResult(listOf(game(ScoreState.IN, score, 48))) }
        val repo = repository("2026-11-15T19:00:00Z")
        collect(repo)
        val followedCard = pacersCard.copy(followed = true)
        repo.track(SECTION, listOf(followedCard))
        runCurrent()
        assertEquals(1, provider.calls.count { it == nba15 })
        score = 53
        advanceTimeBy(ScoreCadence.FOLLOWED_LIVE_MS); runCurrent()
        assertEquals(2, provider.calls.count { it == nba15 })
        assertEquals(53, repo.scores.value.getValue(followedCard.streamKey).homeScore)
        // Unfollowed: the fetch already due runs, then back to the live pace.
        repo.track(SECTION, listOf(pacersCard))
        advanceTimeBy(ScoreCadence.FOLLOWED_LIVE_MS); runCurrent()
        val before = provider.calls.count { it == nba15 }
        assertEquals(3, before)
        advanceTimeBy(ScoreCadence.FOLLOWED_LIVE_MS); runCurrent()
        assertEquals(before, provider.calls.count { it == nba15 })
        advanceTimeBy(ScoreCadence.LIVE_MS - ScoreCadence.FOLLOWED_LIVE_MS); runCurrent()
        assertEquals(before + 1, provider.calls.count { it == nba15 })
    }

    // --- live.23: the scores last shown kept for a reopen, and the warm start ---

    private fun keptFile() = TeamLogoFile(java.io.File(java.nio.file.Files.createTempDirectory("kept").toFile(), "last_scores.json"))

    @Test
    fun `the scores last shown are kept and drawn on a reopen until the feed answers`() = runTest {
        val file = keptFile()
        val key = pacersCard.streamKey
        provider.respond = { ScoreboardResult(listOf(game(ScoreState.IN, 50, 48, "Q2 5:00"))) }
        val first = repository("2026-11-15T19:00:00Z", keptFile = file)
        val looking = collect(first)
        first.track(SECTION, listOf(pacersCard))
        runCurrent()
        advanceTimeBy(LiveScoresRepository.KEPT_WRITE_DELAY_MS + 1); runCurrent()
        looking.cancel()
        runCurrent()
        val nowMs = ms("2026-11-15T19:00:00Z") + testScheduler.currentTime
        assertEquals(50, KeptScores.decode(file.read()!!, nowMs)!!.games.getValue(key).homeScore)

        // Reopened with the delay on: the kept score at once while the fetch is in flight...
        settings.value = ScoreSettings(delaySeconds = 60)
        val gate = CompletableDeferred<Unit>()
        provider.gate = gate
        provider.respond = { ScoreboardResult(listOf(game(ScoreState.IN, 55, 48, "Q3 9:00"))) }
        val second = repository("2026-11-15T19:00:00Z", keptFile = file)
        collect(second)
        second.track(SECTION, listOf(pacersCard))
        runCurrent()
        assertEquals(50, second.scores.value.getValue(key).homeScore)
        assertTrue(second.scores.value.getValue(key).kept)
        // Score only: the kept clock may be minutes old (review A4).
        assertNull(second.scores.value.getValue(key).clockText)
        // ...then the fresh one, first seen, at once.
        provider.gate = null
        gate.complete(Unit)
        runCurrent()
        assertEquals(55, second.scores.value.getValue(key).homeScore)
        assertEquals("Q3 9:00", second.scores.value.getValue(key).clockText)
    }

    @Test
    fun `the game being watched and a protected game are never written to the kept file`() = runTest {
        val lakers = item("Los Angeles Lakers at Phoenix Suns", "Basketball", "2026-11-15T20:30:00Z")
        val lakersGame = nbaFixture.first { it.eventId == "401909986" }.let {
            it.copy(state = ScoreState.IN, isFinal = false, clockText = "Q1 2:00", home = it.home.copy(score = 20), away = it.away.copy(score = 18))
        }
        provider.respond = { ScoreboardResult(listOf(game(ScoreState.IN, 50, 48, "Q2 5:00"), lakersGame)) }

        // The Pacers game is playing: only the Lakers game is written.
        val watchedFile = keptFile()
        val first = repository("2026-11-15T19:00:00Z", keptFile = watchedFile)
        val looking = collect(first)
        watching.watch("player", setOf(pacersCard.streamKey), "addon-a")
        first.track(SECTION, listOf(pacersCard, lakers))
        runCurrent()
        advanceTimeBy(LiveScoresRepository.KEPT_WRITE_DELAY_MS + 1); runCurrent()
        looking.cancel()
        runCurrent()
        val nowMs = ms("2026-11-15T19:00:00Z") + testScheduler.currentTime
        assertEquals(setOf(lakers.streamKey), KeptScores.decode(watchedFile.read()!!, nowMs)!!.games.keys)

        // Nothing playing, the 76ers followed with "hide my teams' scores": their game is not written either.
        watching.watch("player", setOf("another card"), "addon-a")
        val followed = listOf(com.nuvio.tv.domain.model.FollowedTeam(name = "Philadelphia 76ers", sport = "Basketball"))
        val protectedFile = keptFile()
        val second = repository(
            "2026-11-15T19:00:00Z", keptFile = protectedFile,
            protection = kotlinx.coroutines.flow.flowOf(SpoilerProtection(myTeams = true, followed = followed))
        )
        val again = collect(second)
        second.track(SECTION, listOf(pacersCard, lakers))
        runCurrent()
        advanceTimeBy(LiveScoresRepository.KEPT_WRITE_DELAY_MS + 1); runCurrent()
        again.cancel()
        runCurrent()
        val later = ms("2026-11-15T19:00:00Z") + testScheduler.currentTime
        assertEquals(setOf(lakers.streamKey), KeptScores.decode(protectedFile.read()!!, later)!!.games.keys)
    }

    @Test
    fun `a kept score is drawn for one poll at most when the feed never answers`() = runTest {
        val file = keptFile()
        val base = ms("2026-11-15T19:00:00Z")
        file.write(KeptScores.encode(mapOf(pacersCard.streamKey to game(ScoreState.IN, 50, 48, "Q2 5:00")), base - 60_000L))
        provider.gate = CompletableDeferred()
        val repo = repository("2026-11-15T19:00:00Z", keptFile = file)
        collect(repo)
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        assertEquals(50, repo.scores.value.getValue(pacersCard.streamKey).homeScore)
        advanceTimeBy(ScoreCadence.LIVE_MS); runCurrent()
        assertEquals(50, repo.scores.value.getValue(pacersCard.streamKey).homeScore)
        advanceTimeBy(2); runCurrent()
        assertTrue(pacersCard.streamKey !in repo.scores.value)
    }

    @Test
    fun `a kept score is not drawn when older than 2 minutes, for another game, or for the game being watched`() = runTest {
        val base = ms("2026-11-15T19:00:00Z")
        val lakers = item("Los Angeles Lakers at Phoenix Suns", "Basketball", "2026-11-15T20:30:00Z")
        val shown = mapOf(pacersCard.streamKey to game(ScoreState.IN, 50, 48), lakers.streamKey to game(ScoreState.IN, 50, 48))
        provider.gate = CompletableDeferred()

        val old = keptFile().apply { write(KeptScores.encode(shown, base - KeptScores.MAX_AGE_MS - 1)) }
        val oldRepo = repository("2026-11-15T19:00:00Z", keptFile = old)
        val looking = collect(oldRepo)
        oldRepo.track(SECTION, listOf(pacersCard))
        runCurrent()
        assertTrue(oldRepo.scores.value.isEmpty())
        looking.cancel()

        // The Lakers card was kept with the Pacers game: the matcher disagrees, so nothing. The Pacers game is the one
        // playing: its kept score is never drawn, not even for the guide's Now playing.
        val fresh = keptFile().apply { write(KeptScores.encode(shown, base - 60_000L)) }
        val repo = repository("2026-11-15T19:00:00Z", keptFile = fresh)
        collect(repo)
        watching.watch("player", setOf(pacersCard.streamKey), "addon-a")
        repo.track(SECTION, listOf(pacersCard, lakers))
        runCurrent()
        assertTrue(repo.scores.value.isEmpty())
        assertTrue(repo.watchedScores.value.isEmpty())
        // Another game playing: the Pacers' kept score is drawn.
        watching.watch("player", setOf("another card"), "addon-a")
        runCurrent()
        repo.track(SECTION, listOf(lakers, pacersCard))
        runCurrent()
        assertEquals(setOf(pacersCard.streamKey), repo.scores.value.keys)
        assertEquals(50, repo.scores.value.getValue(pacersCard.streamKey).homeScore)
    }

    @Test
    fun `no kept score shows before the profile's spoiler settings are read, and my teams' stays hidden after`() = runTest {
        val base = ms("2026-11-15T19:00:00Z")
        val lakers = item("Los Angeles Lakers at Phoenix Suns", "Basketball", "2026-11-15T20:30:00Z")
        val lakersGame = nbaFixture.first { it.eventId == "401909986" }.let {
            it.copy(state = ScoreState.IN, isFinal = false, clockText = "Q1 2:00", home = it.home.copy(score = 20), away = it.away.copy(score = 18))
        }
        val shown = mapOf(pacersCard.streamKey to game(ScoreState.IN, 50, 48), lakers.streamKey to lakersGame)
        val file = keptFile().apply { write(KeptScores.encode(shown, base - 60_000L, profile = 1)) }
        provider.gate = CompletableDeferred()
        val protection = kotlinx.coroutines.flow.MutableSharedFlow<SpoilerProtection>()
        val repo = repository("2026-11-15T19:00:00Z", keptFile = file, protection = protection, profile = MutableStateFlow(1))
        collect(repo)
        repo.track(SECTION, listOf(pacersCard, lakers))
        runCurrent()
        // Settings not read yet: nothing at all.
        assertTrue(repo.scores.value.isEmpty())
        // Read with "hide my teams' scores" and the 76ers followed: their game stays hidden, the other one shows.
        val followed = listOf(com.nuvio.tv.domain.model.FollowedTeam(name = "Philadelphia 76ers", sport = "Basketball"))
        protection.emit(SpoilerProtection(myTeams = true, followed = followed))
        runCurrent()
        assertTrue(repo.scores.value.getValue(pacersCard.streamKey).spoilerProtected)
        repo.scores.value.getValue(lakers.streamKey).let {
            assertFalse(it.spoilerProtected)
            assertEquals(20, it.homeScore)
        }
    }

    @Test
    fun `kept scores belong to their profile`() = runTest {
        val base = ms("2026-11-15T19:00:00Z")
        val file = keptFile().apply { write(KeptScores.encode(mapOf(pacersCard.streamKey to game(ScoreState.IN, 50, 48)), base - 60_000L, profile = 2)) }
        provider.gate = CompletableDeferred()
        val profile = MutableStateFlow<Int?>(1)
        val repo = repository("2026-11-15T19:00:00Z", keptFile = file, profile = profile)
        collect(repo)
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        // Another profile's copy: not shown.
        assertTrue(repo.scores.value.isEmpty())
        // Switched to the profile that saw it: shown once its settings are read.
        profile.value = 2
        runCurrent()
        repo.track(SECTION, listOf(pacersCard, item("Los Angeles Lakers at Phoenix Suns", "Basketball", "2026-11-15T20:30:00Z")))
        runCurrent()
        assertEquals(50, repo.scores.value.getValue(pacersCard.streamKey).homeScore)
    }

    @Test
    fun `the player's guide closed and reopened draws its games' last scores at once`() = runTest {
        // TV report: Score delay 0, a game playing; the guide's scores and clocks were gone on every reopen.
        val panel = LiveScoresRepository.SCREEN_PLAYER_PANEL
        val key = pacersCard.streamKey
        watching.watch("player", setOf("another card"), "addon-a")
        provider.respond = { ScoreboardResult(listOf(game(ScoreState.IN, 50, 48, "Q2 5:00"))) }
        val repo = repository("2026-11-15T19:00:00Z")
        collect(repo)
        repo.track(panel, listOf(pacersCard))
        runCurrent()
        // First seen while video plays: at once.
        assertEquals(50, repo.scores.value.getValue(key).homeScore)
        assertEquals("Q2 5:00", repo.scores.value.getValue(key).clockText)
        // Closed for 90 s: the feed is stale by then and fetched again on the reopen.
        repo.untrack(panel)
        runCurrent()
        assertTrue(key !in repo.scores.value)
        advanceTimeBy(90_000L); runCurrent()
        val gate = CompletableDeferred<Unit>()
        provider.gate = gate
        repo.track(panel, listOf(pacersCard))
        runCurrent()
        repo.scores.value.getValue(key).let {
            assertEquals(50, it.homeScore)
            // Score only until the fresh answer (review A4).
            assertNull(it.clockText)
            assertTrue(it.kept)
        }
        // The fetch lands: the delayed score takes over.
        provider.gate = null
        gate.complete(Unit)
        runCurrent()
        repo.scores.value.getValue(key).let {
            assertEquals(50, it.homeScore)
            assertFalse(it.kept)
        }
    }

    @Test
    fun `the warm start fetches followed teams' and My sports' feeds once scores are collected, shared with the cards`() = runTest {
        val eng = EspnLeague("soccer", "eng.1")
        val gate = CompletableDeferred<Unit>()
        provider.gate = gate
        provider.respond = { feed -> ScoreboardResult(if (feed == nba15) listOf(game(ScoreState.IN, 50, 48)) else emptyList()) }
        val repo = repository("2026-11-15T19:00:00Z", followedLeagues = kotlinx.coroutines.flow.flowOf(listOf(eng)), prewarm = true)
        collect(repo)
        runCurrent()
        assertEquals(ScoreFeed(eng, LocalDate.of(2026, 11, 15)), provider.calls.first())
        assertTrue(provider.calls.size <= LiveScoresRepository.PREWARM_FEEDS)
        assertTrue(provider.calls.none { it.league == EspnLeagueMap.SOCCER_ALL || EspnIndividualResults.covers(it.league) })
        assertTrue(nba15 in provider.calls)
        // A card tracked while its feed is in flight shares that request, and shows when it lands.
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        assertEquals(1, provider.calls.count { it == nba15 })
        provider.gate = null
        gate.complete(Unit)
        runCurrent()
        assertEquals(50, repo.scores.value.getValue(pacersCard.streamKey).homeScore)
    }

    @Test
    fun `a 429 on the warm start is waited out by the card's poller, not asked again at once (L7)`() = runTest {
        provider.respond = { feed ->
            if (feed == nba15) ScoreboardResult(emptyList(), failed = true, retryAfterMs = 120_000L) else ScoreboardResult(emptyList())
        }
        val repo = repository("2026-11-15T19:00:00Z", prewarm = true)
        collect(repo)
        runCurrent()
        assertEquals(1, provider.calls.count { it == nba15 })
        provider.respond = { feed -> ScoreboardResult(if (feed == nba15) listOf(game(ScoreState.IN, 50, 48)) else emptyList()) }
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        advanceTimeBy(60_000L)
        runCurrent()
        // Inside ESPN's Retry-After: still the one request.
        assertEquals(1, provider.calls.count { it == nba15 })
        advanceTimeBy(70_000L)
        runCurrent()
        assertEquals(2, provider.calls.count { it == nba15 })
        assertEquals(50, repo.scores.value.getValue(pacersCard.streamKey).homeScore)
    }

    @Test
    fun `an error in the warm start never stops score polling (L7)`() = runTest {
        provider.respond = { feed -> ScoreboardResult(if (feed == nba15) listOf(game(ScoreState.IN, 50, 48)) else emptyList()) }
        val failing = kotlinx.coroutines.flow.flow<List<EspnLeague>> { throw java.io.IOException("prefs") }
        val repo = repository("2026-11-15T19:00:00Z", followedLeagues = failing, prewarm = true)
        collect(repo)
        runCurrent()
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        assertEquals(50, repo.scores.value.getValue(pacersCard.streamKey).homeScore)
    }

    @Test
    fun `no warm start while Scores is Off`() = runTest {
        settings.value = ScoreSettings(mode = ScoresMode.OFF)
        val repo = repository("2026-11-15T19:00:00Z", prewarm = true)
        collect(repo)
        runCurrent()
        assertTrue(provider.calls.isEmpty())
    }

    // --- live.20 Q18 (#85): the game being watched, and the others while video plays ---

    @Test
    fun `the game being watched is served with no score and no clock, and gets them back once it stops`() = runTest {
        val (repo, key) = undatedLiveGame({ 50 })
        assertEquals(50, repo.scores.value.getValue(key).homeScore)
        watching.watch("player", setOf(key), "addon-a")
        runCurrent()
        val watched = repo.scores.value.getValue(key)
        assertTrue(watched.watched)
        assertNull(watched.homeScore)
        assertNull(watched.awayScore)
        assertNull(watched.clockText)
        // Its state stays: the end card still comes, delayed.
        assertEquals(ScoreState.IN, watched.state)
        watching.stop("player")
        runCurrent()
        assertEquals(50, repo.scores.value.getValue(key).homeScore)
        assertFalse(repo.scores.value.getValue(key).watched)
        assertTrue(repo.watchedScores.value.isEmpty())
    }

    @Test
    fun `the game being watched keeps its delayed score and clock for the guide's Now playing only`() = runTest {
        val (repo, key) = undatedLiveGame({ 50 })
        watching.watch("player", setOf(key), "addon-a")
        runCurrent()
        // #213: past the playing delay, Now playing reads the score and clock; every other surface stays masked.
        advanceTimeBy(ScoreWatch.MIN_PLAYING_DELAY_MS); runCurrent()
        val forNowPlaying = repo.watchedScores.value.getValue(key)
        assertEquals(50, forNowPlaying.homeScore)
        assertFalse(forNowPlaying.watched)
        assertEquals(repo.scores.value.getValue(key).eventId, forNowPlaying.eventId)
        assertNull(repo.scores.value.getValue(key).homeScore)
        assertTrue(repo.scores.value.getValue(key).watched)
        watching.stop("player")
        runCurrent()
        assertTrue(repo.watchedScores.value.isEmpty())
    }

    @Test
    fun `while video plays other games keep their score and changes wait at least 60 s`() = runTest {
        var score = 50
        val (repo, key) = undatedLiveGame({ score })
        assertEquals(50, repo.scores.value.getValue(key).homeScore)
        // Another game plays: this one keeps the score it shows (live.23); its next change waits 60 s.
        watching.watch("player", setOf("another card"), "addon-a")
        runCurrent()
        assertEquals(50, repo.scores.value.getValue(key).homeScore)
        assertFalse(repo.scores.value.getValue(key).watched)
        score = 53
        advanceTimeBy(30_000); runCurrent() // fetched at 30 s
        assertEquals(50, repo.scores.value.getValue(key).homeScore)
        advanceTimeBy(ScoreWatch.MIN_PLAYING_DELAY_MS - 1_000); runCurrent()
        assertEquals(50, repo.scores.value.getValue(key).homeScore)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(53, repo.scores.value.getValue(key).homeScore)
    }

    @Test
    fun `Real-time scores turns every delay off, also while video plays`() = runTest {
        val (repo, key) = undatedLiveGame({ 50 })
        settings.value = ScoreSettings(realTime = true)
        runCurrent()
        watching.watch("player", setOf("another card"), "addon-a")
        runCurrent()
        assertEquals(50, repo.scores.value.getValue(key).homeScore)
    }

    @Test
    fun `a live card tracked onto a feed waiting at the upcoming pace is polled at the live pace (#81)`() = runTest {
        var score = 50
        // LAL @ PHX at 21:00Z: upcoming at 19:00Z.
        val lakersEvent = nbaFixture.first { it.eventId == "401909986" }.let {
            it.copy(state = ScoreState.PRE, isFinal = false, clockText = null, home = it.home.copy(score = null), away = it.away.copy(score = null))
        }
        provider.respond = { ScoreboardResult(listOf(game(ScoreState.IN, score, 48), lakersEvent)) }
        val repo = repository("2026-11-15T19:00:00Z")
        collect(repo)
        // Home tracks only the upcoming game: the feed waits 5 minutes.
        repo.track(HOME, listOf(item("Los Angeles Lakers at Phoenix Suns", "Basketball", "2026-11-15T20:30:00Z")))
        runCurrent()
        assertEquals(1, provider.calls.size)
        // A minute on, the section shows the live game on the same feed.
        advanceTimeBy(60_000); runCurrent()
        score = 53
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        // Its last fetch is over 30 s old: fetched at once.
        assertEquals(2, provider.calls.size)
        assertEquals(53, repo.scores.value.getValue(pacersCard.streamKey).homeScore)
        score = 55
        advanceTimeBy(30_000); runCurrent()
        assertEquals(3, provider.calls.size)
        assertEquals(55, repo.scores.value.getValue(pacersCard.streamKey).homeScore)
    }

    // --- live.20: a game an addon lists live that ESPN schedules later (user TV report, Nice / PSG) ---

    private fun ligue1(state: ScoreState, start: String, id: String = "nice-psg") = ScoreSnapshot(
        eventId = id, league = EspnLeague("soccer", "fra.1"), startMs = ms(start), state = state, clockText = null,
        isFinal = state == ScoreState.POST,
        home = ScoreTeam(id = "nice", abbreviation = "NICE", displayName = "Nice", shortDisplayName = "Nice", name = "Nice",
            location = "Nice", logo = null, score = if (state == ScoreState.PRE) null else 1, linescores = emptyList()),
        away = ScoreTeam(id = "psg", abbreviation = "PSG", displayName = "Paris Saint-Germain", shortDisplayName = "Paris SG",
            name = "Paris Saint-Germain", location = "Paris Saint-Germain", logo = null, score = if (state == ScoreState.PRE) null else 0,
            linescores = emptyList())
    )

    @Test
    fun `a card listed live the day before ESPN's kick-off gets ESPN's start and never a score`() = runTest {
        val saturday = LocalDate.of(2026, 11, 21)
        provider.respond = { feed -> ScoreboardResult(if (feed.date == saturday) listOf(ligue1(ScoreState.PRE, "2026-11-21T16:15:00Z")) else emptyList()) }
        val starts = SportsLiveEspnStarts()
        val repo = repository("2026-11-20T15:40:00Z", espnStarts = starts)
        collect(repo)
        val card = item("Nice vs PSG", "Ligue 1", "2026-11-20T15:30:00Z")
        repo.track(SECTION, listOf(card))
        runCurrent()
        assertTrue(provider.calls.any { it.date == saturday })
        assertEquals(ms("2026-11-21T16:15:00Z"), starts.startOf(card.streamKey, ms("2026-11-20T15:40:00Z")))
        // The next day's game is never the card's score.
        assertFalse(card.streamKey in repo.scores.value)
    }

    @Test
    fun `a game ESPN shows in play keeps its card live, and an unmatched card stays as it is`() = runTest {
        val friday = LocalDate.of(2026, 11, 20)
        provider.respond = { feed -> ScoreboardResult(if (feed.date == friday) listOf(ligue1(ScoreState.IN, "2026-11-20T15:30:00Z")) else emptyList()) }
        val starts = SportsLiveEspnStarts()
        val repo = repository("2026-11-20T15:40:00Z", espnStarts = starts)
        collect(repo)
        val card = item("Nice vs PSG", "Ligue 1", "2026-11-20T15:30:00Z")
        val other = item("Lens vs Lyon", "Ligue 1", "2026-11-20T15:30:00Z")
        repo.track(SECTION, listOf(card, other))
        runCurrent()
        assertEquals("nice-psg", repo.scores.value.getValue(card.streamKey).eventId)
        assertNull(starts.startOf(card.streamKey, ms("2026-11-20T15:40:00Z")))
        assertNull(starts.startOf(other.streamKey, ms("2026-11-20T15:40:00Z")))
        assertFalse(other.streamKey in repo.scores.value)
    }

    @Test
    fun `ESPN's tomorrow costs a matched live card nothing and an unmatched one a request every 15 min, not every poll`() = runTest {
        val friday = LocalDate.of(2026, 11, 20)
        val saturdayFeed = ScoreFeed(EspnLeague("soccer", "fra.1"), LocalDate.of(2026, 11, 21))
        provider.respond = { feed -> ScoreboardResult(if (feed.date == friday) listOf(ligue1(ScoreState.IN, "2026-11-20T15:30:00Z")) else emptyList()) }
        val repo = repository("2026-11-20T15:40:00Z", espnStarts = SportsLiveEspnStarts())
        collect(repo)
        val card = item("Nice vs PSG", "Ligue 1", "2026-11-20T15:30:00Z")
        // Matched on its own day: its one feed, nothing more.
        repo.track(SECTION, listOf(card))
        runCurrent()
        assertEquals(listOf(ScoreFeed(EspnLeague("soccer", "fra.1"), friday)), provider.calls)
        // A live card ESPN does not list on its day: tomorrow is asked once its own feeds have answered.
        repo.track(SECTION, listOf(card, item("Lens vs Lyon", "Ligue 1", "2026-11-20T15:30:00Z")))
        runCurrent()
        assertEquals(1, provider.calls.count { it == saturdayFeed })
        // The live feed polls every 30 s; tomorrow is not asked again meanwhile.
        advanceTimeBy(LiveScoresRepository.SCHEDULE_CHECK_MS - 60_000L); runCurrent()
        assertTrue(provider.calls.count { it.date == friday } > 10)
        assertEquals(1, provider.calls.count { it == saturdayFeed })
        advanceTimeBy(60_001L); runCurrent()
        assertEquals(2, provider.calls.count { it == saturdayFeed })
    }

    @Test
    fun `a matched game ESPN has later the same day moves its live card too, an upcoming card is left alone`() = runTest {
        provider.respond = { ScoreboardResult(listOf(ligue1(ScoreState.PRE, "2026-11-20T17:30:00Z"))) }
        val starts = SportsLiveEspnStarts()
        val repo = repository("2026-11-20T15:40:00Z", espnStarts = starts)
        collect(repo)
        val live = item("Nice vs PSG", "Ligue 1", "2026-11-20T15:30:00Z")
        val upcoming = item("Nice vs PSG", "Ligue 1", "2026-11-20T16:00:00Z", addon = "b").copy(isLive = false)
        repo.track(SECTION, listOf(live, upcoming))
        runCurrent()
        assertEquals(ms("2026-11-20T17:30:00Z"), starts.startOf(live.streamKey, ms("2026-11-20T15:40:00Z")))
        assertNull(starts.startOf(upcoming.streamKey, ms("2026-11-20T15:40:00Z")))
    }

    // --- live.20: Scores Off still reads a live card's ESPN start, never a score ---

    @Test
    fun `with Scores Off a card listed live the day before gets ESPN's start and nothing is served`() = runTest {
        settings.value = ScoreSettings(mode = ScoresMode.OFF)
        val saturday = LocalDate.of(2026, 11, 21)
        provider.respond = { feed -> ScoreboardResult(if (feed.date == saturday) listOf(ligue1(ScoreState.PRE, "2026-11-21T16:15:00Z")) else emptyList()) }
        val starts = SportsLiveEspnStarts()
        val logos = TeamLogoBook()
        val repo = repository("2026-11-20T15:40:00Z", espnStarts = starts, logos = logos)
        collect(repo)
        val card = item("Nice vs PSG", "Ligue 1", "2026-11-20T15:30:00Z")
        repo.track(SECTION, listOf(card))
        runCurrent()
        assertTrue(provider.calls.any { it.date == saturday })
        assertEquals(ms("2026-11-21T16:15:00Z"), starts.startOf(card.streamKey, ms("2026-11-20T15:40:00Z")))
        assertTrue(repo.scores.value.isEmpty())
        assertTrue(repo.unmatched.value.isEmpty())
        assertEquals(0, logos.size)
    }

    @Test
    fun `with Scores Off a game in play leaves its card live and serves no score`() = runTest {
        settings.value = ScoreSettings(mode = ScoresMode.OFF)
        provider.respond = { ScoreboardResult(listOf(ligue1(ScoreState.IN, "2026-11-20T15:30:00Z"))) }
        val starts = SportsLiveEspnStarts()
        val repo = repository("2026-11-20T15:40:00Z", espnStarts = starts)
        collect(repo)
        val card = item("Nice vs PSG", "Ligue 1", "2026-11-20T15:30:00Z")
        repo.track(SECTION, listOf(card))
        runCurrent()
        assertTrue(provider.calls.isNotEmpty())
        assertNull(starts.startOf(card.streamKey, ms("2026-11-20T15:40:00Z")))
        assertTrue(repo.scores.value.isEmpty())
    }

    @Test
    fun `with Scores Off only live cards are read, each feed once per check interval`() = runTest {
        settings.value = ScoreSettings(mode = ScoresMode.OFF)
        val repo = repository("2026-11-20T15:40:00Z")
        collect(repo)
        val upcoming = item("Nice vs PSG", "Ligue 1", "2026-11-20T18:00:00Z").copy(isLive = false)
        repo.track(SECTION, listOf(upcoming))
        runCurrent()
        advanceTimeBy(60 * 60_000L); runCurrent()
        assertTrue(provider.calls.isEmpty())

        val live = item("Lens vs Lyon", "Ligue 1", "2026-11-20T15:30:00Z")
        repo.track(SECTION, listOf(upcoming, live))
        runCurrent()
        val first = provider.calls.size
        assertTrue(first > 0)
        // The cards change again within the interval: no feed is asked twice.
        repo.track(HOME, listOf(live))
        runCurrent()
        assertEquals(first, provider.calls.size)
        advanceTimeBy(LiveScoresRepository.SCHEDULE_CHECK_MS); runCurrent()
        assertEquals(2 * first, provider.calls.size)
        // Untracked: nothing more.
        repo.untrack(SECTION); repo.untrack(HOME)
        runCurrent()
        advanceTimeBy(60 * 60_000L); runCurrent()
        assertEquals(2 * first, provider.calls.size)
    }

    @Test
    fun `Scores Off reads only through the no-store schedule read, Scores On never does`() = runTest {
        settings.value = ScoreSettings(mode = ScoresMode.OFF)
        val repo = repository("2026-11-20T15:40:00Z")
        collect(repo)
        repo.track(SECTION, listOf(item("Nice vs PSG", "Ligue 1", "2026-11-20T15:30:00Z")))
        runCurrent()
        assertTrue(provider.calls.isNotEmpty())
        assertEquals(provider.calls, provider.scheduleCalls)

        val offReads = provider.scheduleCalls.size
        settings.value = ScoreSettings(mode = ScoresMode.SHOW)
        runCurrent()
        assertTrue(provider.calls.size > offReads)
        assertEquals(offReads, provider.scheduleCalls.size)
    }

    @Test
    fun `a schedule-only copy keeps the start and teams and drops every score`() {
        val played = ligue1(ScoreState.IN, "2026-11-20T15:30:00Z").copy(
            clockText = "67'", scorers = listOf(ScoreScorer(ScoreSide.HOME, "A Player", "12'", penalty = false, ownGoal = false)),
            home = ligue1(ScoreState.IN, "2026-11-20T15:30:00Z").home.copy(linescores = listOf(1, 0), record = "5-2-1", form = "WWDLW")
        )
        val stripped = LiveScoresRepository.scheduleOnly(played)
        assertEquals(played.startMs, stripped.startMs)
        assertEquals("Nice", stripped.home.displayName)
        assertEquals("Paris Saint-Germain", stripped.away.displayName)
        assertNull(stripped.homeScore)
        assertNull(stripped.awayScore)
        assertNull(stripped.clockText)
        assertTrue(stripped.scorers.isEmpty())
        assertTrue(stripped.home.linescores.isEmpty())
        assertNull(stripped.home.record)
        assertNull(stripped.home.form)
    }

    // --- TV QA live.20 (2026-11-20): finished soccer games left LIVE with no score ---

    private val ger1 = EspnLeague("soccer", "ger.1")
    private val esp2 = EspnLeague("soccer", "esp.2")

    private fun nov20Feeds(feed: ScoreFeed): ScoreboardResult = when (feed.league) {
        EspnLeagueMap.SOCCER_ALL -> ScoreboardResult(EspnFixtures.events(EspnFixtures.SOCCER_ALL_20261120, EspnLeagueMap.SOCCER_ALL))
        ger1 -> ScoreboardResult(EspnFixtures.events(EspnFixtures.GER1_20261120, ger1))
        esp2 -> ScoreboardResult(EspnFixtures.events(EspnFixtures.ESP2_20261120, esp2))
        else -> ScoreboardResult(emptyList())
    }

    @Test
    fun `Bayern vs Koln and Andorra vs Leganes listed live get ESPN's finals and leave Live`() = runTest {
        // TV report, live.20 (2026-11-20 ~21:50Z): both final on ESPN, both listed live by their addon, no score shown.
        provider.respond = ::nov20Feeds
        val repo = repository("2026-11-20T21:50:00Z")
        collect(repo)
        // As the section lists them: the team directory's league tag (Bundesliga), the identified sport word (Soccer).
        val bayern = item("Bayern vs Köln", "Bundesliga", "2026-11-20T19:30:00Z")
        val andorra = item("Andorra vs Leganés", "Soccer", "2026-11-20T19:30:00Z", addon = "b")
        repo.track(SECTION, listOf(bayern, andorra))
        runCurrent()

        val bayernFinal = repo.scores.value.getValue(bayern.streamKey)
        assertEquals("401884736", bayernFinal.eventId)
        assertEquals(ScoreState.POST, bayernFinal.state)
        assertEquals(ScoreChip.Final("FT", ChipScore(1, 1)), ScoreChips.of(bayern, bayernFinal, ScoresMode.SHOW, revealed = false))
        val andorraFinal = repo.scores.value.getValue(andorra.streamKey)
        assertEquals("401883112", andorraFinal.eventId)
        // "Andorra" is ESPN's home side "FC Andorra": 1-3, never the other way round.
        assertEquals(ScoreChip.Final("FT", ChipScore(1, 3)), ScoreChips.of(andorra, andorraFinal, ScoresMode.SHOW, revealed = false))

        // Seen final now, past the grace later: both leave Live now (Just ended / Results), as any final does.
        val ended = com.nuvio.tv.core.sportslive.SportsLiveEndedGames()
        val seen = ms("2026-11-20T21:50:00Z")
        ended.confirmedEndedKeys(listOf(bayern, andorra), repo.scores.value, seen)
        val later = seen + com.nuvio.tv.core.sportslive.SportsLiveEndedGames.GRACE_MS + 60_000L
        assertEquals(setOf(bayern.streamKey, andorra.streamKey), ended.confirmedEndedKeys(listOf(bayern, andorra), repo.scores.value, later))
    }

    // ---- live.23: a women's game an addon files under the men's league ----

    private val wslLeague = EspnLeague("soccer", "eng.w.1")
    private val saturday21 = LocalDate.of(2026, 11, 21)

    private fun club(id: String, name: String, abbr: String) = ScoreTeam(
        id = id, abbreviation = abbr, displayName = name, shortDisplayName = name, name = name, location = name,
        logo = null, score = 1, linescores = emptyList()
    )

    // ESPN's WSL game: Chelsea at Liverpool, in progress (TV report 2026-11-21).
    private val wslGame = ScoreSnapshot(
        eventId = "wsl-liv-che", league = wslLeague, leagueName = "English Women's Super League", startMs = ms("2026-11-21T12:45:00Z"),
        state = ScoreState.IN, clockText = "34'", isFinal = false,
        home = club("liv-w", "Liverpool", "LIV"), away = club("che-w", "Chelsea Women", "CHE")
    )

    @Test
    fun `a Premier League card ESPN's men's feed lacks is matched on the WSL, strictly, and marked a women's game`() = runTest {
        com.nuvio.tv.core.sportslive.SportsLiveWomenGames.shared.clear()
        provider.respond = { feed -> ScoreboardResult(if (feed.league == wslLeague) listOf(wslGame) else emptyList()) }
        val repo = repository("2026-11-21T13:30:00Z")
        collect(repo)
        val card = item("Liverpool vs Chelsea", "Premier League", "2026-11-21T12:45:00Z")
        repo.track(SECTION, listOf(card))
        runCurrent()
        assertTrue(ScoreFeed(wslLeague, saturday21) in provider.calls)
        assertEquals("wsl-liv-che", repo.scores.value.getValue(card.streamKey).eventId)
        assertTrue(com.nuvio.tv.core.sportslive.SportsLiveWomenGames.shared.has(card.streamKey))
        // Its sides sit on the game one way round, so the score shows (and the row's tag is the WSL's).
        assertEquals(true, ScoreMatcher.firstIsHome("Liverpool", "Chelsea", wslGame))
        assertEquals("WSL", com.nuvio.tv.core.sportslive.SportsLiveLeagueTags.of(wslGame.leagueName!!))
        com.nuvio.tv.core.sportslive.SportsLiveWomenGames.shared.clear()
    }

    @Test
    fun `the women's league gives no score for a card more than 3 hours from its game, or with no time`() = runTest {
        // Review B1: a men's game missing from ESPN took the WSL meeting up to 14 h away, or yesterday's for an undated card.
        com.nuvio.tv.core.sportslive.SportsLiveWomenGames.shared.clear()
        provider.respond = { feed -> ScoreboardResult(if (feed.league == wslLeague) listOf(wslGame) else emptyList()) }
        val repo = repository("2026-11-21T13:30:00Z")
        collect(repo)
        val evening = item("Liverpool vs Chelsea", "Premier League", "2026-11-21T19:30:00Z")
        val undated = item("Liverpool vs Chelsea", "Premier League", null, addon = "b")
        repo.track(SECTION, listOf(evening, undated))
        runCurrent()
        assertFalse(evening.streamKey in repo.scores.value)
        assertFalse(undated.streamKey in repo.scores.value)
        assertFalse(com.nuvio.tv.core.sportslive.SportsLiveWomenGames.shared.has(evening.streamKey))
        com.nuvio.tv.core.sportslive.SportsLiveWomenGames.shared.clear()
    }

    @Test
    fun `the women's league is never tried when the men's league has the pairing today`() = runTest {
        com.nuvio.tv.core.sportslive.SportsLiveWomenGames.shared.clear()
        // The men's game is late that evening, out of the card's reach, but on its ESPN day.
        val mens = wslGame.copy(
            eventId = "epl-liv-che", league = EspnLeague("soccer", "eng.1"), leagueSlug = "eng.1", leagueName = "Premier League",
            startMs = ms("2026-11-22T03:00:00Z"),
            state = ScoreState.PRE, home = club("liv", "Liverpool", "LIV"), away = club("che", "Chelsea", "CHE")
        )
        provider.respond = { feed ->
            ScoreboardResult(
                when (feed.league) {
                    wslLeague -> listOf(wslGame)
                    EspnLeague("soccer", "eng.1") -> listOf(mens)
                    else -> emptyList()
                }
            )
        }
        val repo = repository("2026-11-21T13:30:00Z")
        collect(repo)
        val card = item("Liverpool vs Chelsea", "Premier League", "2026-11-21T12:45:00Z")
        repo.track(SECTION, listOf(card))
        runCurrent()
        assertTrue(provider.calls.none { it.league == wslLeague })
        assertFalse(card.streamKey in repo.scores.value)
        assertFalse(com.nuvio.tv.core.sportslive.SportsLiveWomenGames.shared.has(card.streamKey))
    }

    // ---- live.24, TV report #18: college games an addon tags only "American Football" ----

    private val cfb21 = EspnFixtures.events(EspnFixtures.CFB_FBS_20261121, EspnLeagueMap.COLLEGE_FOOTBALL)

    private fun americanFootball(name: String, start: String) = item(name, "American Football", start, genres = listOf("American Football"))

    @Test
    fun `a card that says only American Football polls the NFL and college football as its own feeds and gets the college score`() = runTest {
        provider.respond = { feed -> ScoreboardResult(if (feed.league == EspnLeagueMap.COLLEGE_FOOTBALL) cfb21 else emptyList()) }
        val repo = repository("2026-11-21T21:00:00Z")
        collect(repo)
        val cards = mapOf(
            americanFootball("Illinois vs Iowa", "2026-11-21T19:30:00Z") to "401858527",
            americanFootball("Appalachian State vs Louisiana-Monroe", "2026-11-21T19:30:00Z") to "401869929",
            americanFootball("Louisiana vs Coastal Carolina", "2026-11-21T20:00:00Z") to "401869946",
            americanFootball("New Mexico State vs Delaware", "2026-11-21T20:00:00Z") to "401871077"
        )
        repo.track(SECTION, cards.keys.toList())
        runCurrent()
        val day = LocalDate.of(2026, 11, 21)
        assertTrue(ScoreFeed(EspnLeagueMap.NFL, day) in provider.calls)
        assertTrue(ScoreFeed(EspnLeagueMap.COLLEGE_FOOTBALL, day) in provider.calls)
        for ((card, id) in cards) assertEquals(card.meta.name, id, repo.scores.value[card.streamKey]?.eventId)
        // The row then reads ESPN's league, as a matched college game does.
        assertEquals("NCAAF", repo.scores.value.getValue(cards.keys.first().streamKey).leagueName)
    }

    @Test
    fun `a generic American Football card needs both sides strong, and a card naming the NFL never asks college football`() = runTest {
        provider.respond = { feed -> ScoreboardResult(if (feed.league == EspnLeagueMap.COLLEGE_FOOTBALL) cfb21 else emptyList()) }
        val repo = repository("2026-11-21T21:00:00Z")
        collect(repo)
        // "Iowa State" is not Iowa: one side alone never scores.
        val wrong = americanFootball("Illinois vs Iowa State", "2026-11-21T19:30:00Z")
        repo.track(SECTION, listOf(wrong))
        runCurrent()
        assertFalse(wrong.streamKey in repo.scores.value)
        provider.calls.clear()
        repo.track(SECTION, listOf(item("Illinois vs Iowa", "NFL", "2026-11-21T19:30:00Z")))
        runCurrent()
        assertTrue(provider.calls.none { it.league == EspnLeagueMap.COLLEGE_FOOTBALL })
    }

    private fun school(id: String, display: String, short: String, abbr: String, nickname: String) =
        DirectoryTeam(id, display, short, abbr, short, nickname, "https://a.espncdn.com/i/teamlogos/ncaa/500/$id.png")

    /** The NBA list and a college list holding Baylor and Texas, as the directory loads them. */
    private fun basketballLists() = TeamLogoBook(directory = TeamDirectory().apply {
        put(EspnLeagueMap.NBA, nbaTeams, 1L)
        put(
            EspnLeagueMap.COLLEGE_BASKETBALL,
            listOf(school("239", "Baylor Bears", "Baylor", "BAY", "Bears"), school("251", "Texas Longhorns", "Texas", "TEX", "Longhorns")),
            1L
        )
    })

    @Test
    fun `a Basketball card of two schools polls college basketball as its own feed, at once and with both sides strong`() = runTest {
        provider.respond = ::nbaAndCollege
        // The NBA feed hangs: as a fallback, college basketball would wait for it.
        val nbaHangs = CompletableDeferred<Unit>()
        provider.gateFor = mapOf(nba15 to nbaHangs)
        val repo = repository("2026-11-15T21:00:00Z", logos = basketballLists())
        collect(repo)
        repo.track(SECTION, listOf(baylorCard))
        runCurrent()
        assertTrue(cbb15 in provider.calls)
        assertEquals("401906866", repo.scores.value.getValue(baylorCard.streamKey).eventId)
        nbaHangs.complete(Unit)
    }

    @Test
    fun `a Basketball card of two NBA teams stays on the NBA feed alone`() = runTest {
        provider.respond = ::nbaAndCollege
        val repo = repository("2026-11-15T21:00:00Z", logos = basketballLists())
        collect(repo)
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        assertEquals(listOf(nba15), provider.calls)
        assertEquals("401909985", repo.scores.value.getValue(pacersCard.streamKey).eventId)
    }
}
