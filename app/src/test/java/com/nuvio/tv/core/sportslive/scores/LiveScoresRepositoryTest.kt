package com.nuvio.tv.core.sportslive.scores

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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class LiveScoresRepositoryTest {

    private class FakeProvider : ScoreProvider {
        val calls = mutableListOf<ScoreFeed>()
        var respond: (ScoreFeed) -> ScoreboardResult = { ScoreboardResult(emptyList()) }
        /** When set, a request waits for it: an in-flight request. */
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun scoreboard(league: EspnLeague, date: LocalDate): ScoreboardResult {
            val feed = ScoreFeed(league, date)
            calls += feed
            gate?.await()
            return respond(feed)
        }
    }

    private val provider = FakeProvider()
    private val settings = MutableStateFlow(ScoreSettings())

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

    private val mySports = MutableStateFlow(MySports.DEFAULT)

    private fun TestScope.repository(start: String, zone: ZoneId = ZoneOffset.UTC): LiveScoresRepository {
        val base = ms(start)
        return LiveScoresRepository(
            provider = provider,
            settings = settings,
            mySports = mySports,
            scope = backgroundScope,
            now = { base + testScheduler.currentTime },
            zone = { zone },
            jitter = { 0L }
        )
    }

    private fun TestScope.collect(repo: LiveScoresRepository) = backgroundScope.launch { repo.scores.collect {} }

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
        repo.track(SECTION, listOf(pacersCard))
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
        // First seen live: shown without a score until the delay has passed.
        repo.scores.value.getValue(key).let {
            assertEquals(ScoreState.IN, it.state)
            assertNull(it.homeScore)
            assertNull(it.clockText)
        }
        latest = game(ScoreState.IN, 53, 48, "Q2 4:40").let { it.copy(home = it.home.copy(displayName = "Sixers")) }
        advanceTimeBy(30_000); runCurrent() // fetched at 30 s; the first version is now due
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
    fun `turning the delay on mid-game shows no score until the delay has passed`() = runTest {
        provider.respond = { ScoreboardResult(listOf(game(ScoreState.IN, 50, 48, "Q2 5:00"))) }
        val repo = repository("2026-11-15T19:00:00Z")
        collect(repo)
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        val key = pacersCard.streamKey
        assertEquals(50, repo.scores.value.getValue(key).homeScore)
        settings.value = ScoreSettings(delaySeconds = 60)
        advanceTimeBy(1_000); runCurrent()
        assertNull(repo.scores.value.getValue(key).homeScore)
        advanceTimeBy(58_000); runCurrent()
        assertNull(repo.scores.value.getValue(key).homeScore)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(50, repo.scores.value.getValue(key).homeScore)
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
            repo.datesFor(ScoreCard.from(item(name, "Basketball", start, timed))!!, today).toSet()
        // 23:00 Eastern on the 15th (04:00 UTC on the 16th): the 15th and, within 3 h, the 16th.
        assertEquals(setOf(LocalDate.of(2026, 11, 15), today), dates("A vs B", "2026-11-16T04:00:00Z"))
        // 20:00 Eastern on the 15th is 01:00 UTC on the 16th, but ESPN files it on the 15th only.
        assertEquals(setOf(LocalDate.of(2026, 11, 15)), dates("A vs B", "2026-11-16T01:00:00Z"))
        assertEquals(setOf(LocalDate.of(2026, 11, 15)), dates("A vs B", "2026-11-15T18:00:00Z"))
        assertEquals(setOf(LocalDate.of(2026, 11, 14)), dates("A vs B", "2026-11-14T00:00:00Z", timed = false))
        assertEquals(setOf(today, LocalDate.of(2026, 11, 15)), dates("A vs B", null))
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
        val days = (1..LiveScoresRepository.MAX_FEEDS + 2).map { LocalDate.of(2026, 11, it) }
        val others = days.dropLast(1).map { item("Team$it A vs Team$it B", "Basketball", "${it}T18:00:00Z") }
        val followed = item("Indiana Pacers at Philadelphia 76ers", "Basketball", "${days.last()}T18:00:00Z", followed = true)
        repo.track(SECTION, others + followed)
        runCurrent()
        assertEquals(LiveScoresRepository.MAX_FEEDS, provider.calls.distinct().size)
        assertTrue(ScoreFeed(EspnLeagueMap.NBA, days.last()) in provider.calls)
        // The cards past the budget are not polled at all.
        assertTrue(ScoreFeed(EspnLeagueMap.NBA, days[days.size - 2]) !in provider.calls)
    }

    @Test
    fun `a league outside My sports is not polled unless a followed team plays`() = runTest {
        provider.respond = { feed -> if (feed == nba15) ScoreboardResult(listOf(game(ScoreState.IN, 50, 48))) else ScoreboardResult(emptyList()) }
        mySports.value = MySports(mapOf(MySport.HOCKEY to emptySet()))
        val repo = repository("2026-11-15T19:00:00Z")
        collect(repo)
        repo.track(SECTION, listOf(pacersCard))
        runCurrent()
        assertTrue(provider.calls.isEmpty())
        assertTrue(repo.scores.value.isEmpty())
        // My Teams first: a followed team's game is scored whatever My sports says.
        val followedCard = pacersCard.copy(followed = true)
        repo.track(SECTION, listOf(followedCard))
        runCurrent()
        assertEquals(listOf(nba15), provider.calls)
        assertEquals(setOf(followedCard.streamKey), repo.scores.value.keys)
    }

    @Test
    fun `a soccer competition not picked in My sports shows no score and stops polling`() = runTest {
        val eng1 = EspnFixtures.events(EspnFixtures.ENG1_20261108, EspnFixtures.ENG1)
        provider.respond = { feed -> if (feed.league == EspnFixtures.ENG1) ScoreboardResult(eng1) else ScoreboardResult(emptyList()) }
        // Soccer with only LaLiga named.
        mySports.value = MySports(mapOf(MySport.SOCCER to setOf(MyLeague.LA_LIGA)))
        val repo = repository("2026-11-08T15:00:00Z")
        collect(repo)
        val palace = item("Crystal Palace vs Liverpool", "Soccer", "2026-11-08T14:00:00Z", genres = listOf("Premier League"))
        repo.track(SECTION, listOf(palace))
        runCurrent()
        val first = provider.calls.size
        assertTrue(first > 0)
        assertTrue(repo.scores.value.isEmpty())
        advanceTimeBy(30 * 60_000L); runCurrent()
        assertEquals(first, provider.calls.size)
        // Picking the Premier League brings the score.
        mySports.value = MySports(mapOf(MySport.SOCCER to setOf(MyLeague.LA_LIGA, MyLeague.PREMIER_LEAGUE)))
        runCurrent()
        assertEquals(setOf(palace.streamKey), repo.scores.value.keys)
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

    @Test
    fun `a parked out-of-scope card is scored once its team is followed`() = runTest {
        val eng1 = EspnFixtures.events(EspnFixtures.ENG1_20261108, EspnFixtures.ENG1)
        provider.respond = { feed -> if (feed.league == EspnFixtures.ENG1) ScoreboardResult(eng1) else ScoreboardResult(emptyList()) }
        mySports.value = MySports(mapOf(MySport.SOCCER to setOf(MyLeague.LA_LIGA)))
        val repo = repository("2026-11-08T15:00:00Z")
        collect(repo)
        val palace = item("Crystal Palace vs Liverpool", "Soccer", "2026-11-08T14:00:00Z", genres = listOf("Premier League"))
        repo.track(SECTION, listOf(palace))
        runCurrent()
        assertTrue(repo.scores.value.isEmpty())
        repo.track(SECTION, listOf(palace.copy(followed = true)))
        runCurrent()
        assertEquals(setOf(palace.streamKey), repo.scores.value.keys)
    }
}
