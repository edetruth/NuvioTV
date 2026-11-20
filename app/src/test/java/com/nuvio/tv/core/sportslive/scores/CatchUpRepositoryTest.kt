package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sportslive.SportsLivePlayedTime
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * The repository runs in the test's background scope, which `advanceUntilIdle` does not wait for:
 * every step settles with [runCurrent] (nothing here waits on virtual time but the provider's [FakeProvider.delayMs]).
 */
class CatchUpRepositoryTest {

    private class FakeProvider : ScoreProvider {
        val calls = mutableListOf<ScoreFeed>()
        var respond: (ScoreFeed) -> ScoreboardResult = { ScoreboardResult(emptyList()) }
        var delayMs = 0L
        var active = 0
        var maxActive = 0
        override suspend fun scoreboard(league: EspnLeague, date: LocalDate): ScoreboardResult {
            val feed = ScoreFeed(league, date)
            calls += feed
            active++
            maxActive = maxOf(maxActive, active)
            if (delayMs > 0) delay(delayMs)
            active--
            return respond(feed)
        }
    }

    private val minute = 60_000L
    private val nov15 = LocalDate.of(2026, 11, 15)
    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
    private val pacersId = "401909985"
    private var clock = Instant.parse("2026-11-16T12:00:00Z").toEpochMilli()

    private val provider = FakeProvider().apply {
        respond = { feed -> ScoreboardResult(if (feed.league == EspnLeagueMap.NBA && feed.date == nov15) nba else emptyList()) }
    }
    private val profile1 = TestPreferencesStore()
    private val profile2 = TestPreferencesStore()
    private val profileIds = MutableStateFlow(1)
    private val prefs = SportsLivePreferencesDataStore(
        mockk<ProfileDataStoreFactory> {
            every { get(1, SportsLivePreferencesDataStore.FEATURE) } returns profile1
            every { get(2, SportsLivePreferencesDataStore.FEATURE) } returns profile2
        },
        mockk<ProfileManager> { every { activeProfileId } returns profileIds }
    )
    private val pacersReplay = replay("Indiana Pacers vs Philadelphia 76ers", "2026-11-15T18:00:00Z")
    private val replays = MutableStateFlow(listOf(pacersReplay))
    private val sectionEnabled = MutableStateFlow(true)
    private var playerClock = 0L
    private val played = SportsLivePlayedTime { playerClock }
    private val resultSeen = ResultSeen()

    private fun replay(name: String, start: String, addonId: String = "a") = SportsLiveItem(
        meta = MetaPreview(
            id = "$addonId:$name", type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = listOf("Basketball")
        ),
        addonId = addonId, addonName = addonId, addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Replays",
        kind = SportsLiveKind.REPLAY, isLive = false, isChannel = false, startMs = Instant.parse(start).toEpochMilli(),
        sport = "Basketball", startHasTimeOfDay = true, teams = SportsLiveTeams.parse(name)
    )

    private fun TestScope.repository(): CatchUpRepository {
        val results = ResultsRepository(
            provider = provider,
            settings = prefs.scoreSettings,
            followedTeams = prefs.followedTeams,
            mySports = prefs.mySports,
            now = { clock },
            zone = { SportsResults.EASTERN }
        )
        val repo = CatchUpRepository(results, prefs, replays, sectionEnabled, played, { clock }, backgroundScope, resultSeen)
        backgroundScope.launch { repo.items.collect { } }
        return repo
    }

    private suspend fun followPacers() = prefs.follow(FollowedTeam("Indiana Pacers", "Basketball"))

    private fun TestScope.open(repo: CatchUpRepository) {
        repo.onOpened()
        runCurrent()
    }

    private fun keys(repo: CatchUpRepository) = repo.items.value.map { it.key }

    // --- Requests ---

    @Test
    fun `an open loads each day of followed teams' feeds once and lists the replay`() = runTest {
        followPacers()
        val repo = repository()
        open(repo)
        assertEquals(listOf(pacersId), keys(repo))
        // Never opened before: 4 days back. Only the NBA (the followed team's feed), one request per day.
        val expected = (0..4).map { ScoreFeed(EspnLeagueMap.NBA, LocalDate.of(2026, 11, 16).minusDays(it.toLong())) }
        assertEquals(expected.toSet(), provider.calls.toSet())
        assertEquals(expected.size, provider.calls.size)
        assertEquals(setOf(pacersId), prefs.catchUpMarks.first().surfaced.keys)
    }

    @Test
    fun `days load two at a time`() = runTest {
        followPacers()
        provider.delayMs = 1_000L
        val repo = repository()
        repo.onOpened()
        runCurrent()
        repeat(5) {
            advanceTimeBy(1_000L)
            runCurrent()
        }
        assertEquals(5, provider.calls.size)
        assertEquals(CatchUpRepository.MAX_PARALLEL_DAYS, provider.maxActive)
        assertEquals(listOf(pacersId), keys(repo))
    }

    @Test
    fun `resumes reload at most every 10 min, and nothing is requested between them`() = runTest {
        followPacers()
        val repo = repository()
        open(repo)
        val first = provider.calls.size
        advanceTimeBy(6 * 60 * minute)
        runCurrent()
        assertEquals("no polling", first, provider.calls.size)
        // 5 min later Today's copy is stale (2 min), yet the resume does not reload.
        clock += 5 * minute
        open(repo)
        assertEquals(first, provider.calls.size)
        // 10 min after the last load: reloaded, and only Today is asked again (past days are cached).
        clock += 5 * minute
        open(repo)
        assertEquals(first + 1, provider.calls.size)
        assertEquals(ScoreFeed(EspnLeagueMap.NBA, LocalDate.of(2026, 11, 16)), provider.calls.last())
        // A new replay list only re-selects.
        replays.value = replays.value.toList()
        runCurrent()
        assertEquals(first + 1, provider.calls.size)
    }

    @Test
    fun `no team, the row off, Sports & Live off or its Home row off, nothing requested and nothing shown`() = runTest {
        val repo = repository()
        open(repo)
        assertTrue(repo.items.value.isEmpty())
        assertTrue(provider.calls.isEmpty())

        prefs.setCatchUpRow(false)
        followPacers()
        open(repo)
        assertTrue(repo.items.value.isEmpty())
        prefs.setCatchUpRow(true)
        sectionEnabled.value = false
        runCurrent()
        assertTrue(repo.items.value.isEmpty())
        sectionEnabled.value = true
        prefs.setShowHomeRow(false)
        runCurrent()
        assertTrue(repo.items.value.isEmpty())
        assertTrue(provider.calls.isEmpty())

        prefs.setShowHomeRow(true)
        runCurrent()
        assertEquals(listOf(pacersId), keys(repo))
        prefs.setCatchUpRow(false)
        runCurrent()
        assertTrue("the row hides", repo.items.value.isEmpty())
    }

    @Test
    fun `Scores Off hides the row and requests nothing`() = runTest {
        prefs.setScoresMode(ScoresMode.OFF)
        followPacers()
        val repo = repository()
        open(repo)
        assertTrue(repo.items.value.isEmpty())
        assertTrue(provider.calls.isEmpty())

        prefs.setScoresMode(ScoresMode.SHOW)
        runCurrent()
        assertEquals(listOf(pacersId), keys(repo))
        prefs.setScoresMode(ScoresMode.OFF)
        runCurrent()
        assertTrue(repo.items.value.isEmpty())
    }

    // --- Watched ---

    @Test
    fun `playback past halfway clears the item, 2 min or time away does not`() = runTest {
        followPacers()
        val repo = repository()
        open(repo)

        // Away 30 min, the replay playing only 2 min of it: stays (live.20 1c: not 2 min any more).
        played.onPlaying(pacersReplay.id, playing = true)
        playerClock += 2 * minute
        played.onPlaying(pacersReplay.id, playing = false)
        clock += 30 * minute
        open(repo)
        assertEquals(listOf(pacersId), keys(repo))

        // Past half the game (an NBA game reads 200 min), wherever it was started (Results, the Game page): watched.
        played.onPlaying(pacersReplay.id, playing = true)
        playerClock += 98 * minute
        played.onPlaying(pacersReplay.id, playing = false)
        open(repo)
        assertTrue(repo.items.value.isEmpty())
        assertEquals(setOf(pacersId), prefs.catchUpMarks.first().seen.keys)
    }

    @Test
    fun `a replay played to its end clears the item, however short`() = runTest {
        followPacers()
        val repo = repository()
        open(repo)
        played.onFinished(pacersReplay.id)
        runCurrent()
        assertTrue(repo.items.value.isEmpty())
    }

    // --- The smart lifetime (live.20 1c) ---

    @Test
    fun `a result seen elsewhere clears the item and is remembered`() = runTest {
        followPacers()
        val repo = repository()
        open(repo)
        assertEquals(listOf(pacersId), keys(repo))
        resultSeen.finalScoreShown(pacersId)
        runCurrent()
        assertTrue(repo.items.value.isEmpty())
        assertEquals(setOf(pacersId), prefs.catchUpMarks.first().known.keys)
    }

    @Test
    fun `the team's next game starting clears the item`() = runTest {
        val pacersGame = nba.first { it.eventId == pacersId }
        val next = pacersGame.copy(
            eventId = "next",
            startMs = Instant.parse("2026-11-16T23:00:00Z").toEpochMilli(),
            state = ScoreState.PRE,
            isFinal = false
        )
        provider.respond = { feed ->
            ScoreboardResult(
                when {
                    feed.league != EspnLeagueMap.NBA -> emptyList()
                    feed.date == nov15 -> nba
                    feed.date == LocalDate.of(2026, 11, 16) -> listOf(next)
                    else -> emptyList()
                }
            )
        }
        followPacers()
        val repo = repository()
        open(repo)
        assertEquals(listOf(pacersId), keys(repo))
        clock = Instant.parse("2026-11-16T23:01:00Z").toEpochMilli()
        open(repo)
        assertTrue(repo.items.value.isEmpty())
    }

    @Test
    fun `a game saved for later stays past 48 h`() = runTest {
        followPacers()
        val repo = repository()
        open(repo)
        val pacersGame = nba.first { it.eventId == pacersId }
        prefs.updateProtectedGames { SpoilerProtection.saved(it, pacersGame, clock) }
        // 49 h after the game's estimated end (18:00Z + 200 min).
        clock = Instant.parse("2026-11-17T22:21:00Z").toEpochMilli()
        open(repo)
        assertEquals(listOf(pacersId), keys(repo))
    }

    @Test
    fun `mark as watched removes the item`() = runTest {
        followPacers()
        val repo = repository()
        open(repo)
        repo.markWatched(repo.items.value.single())
        runCurrent()
        assertTrue(repo.items.value.isEmpty())
    }

    @Test
    fun `a profile switch drops the visit and what was played`() = runTest {
        followPacers()
        val repo = repository()
        open(repo)
        played.onPlaying(pacersReplay.id, playing = true)
        playerClock += 3 * minute

        profileIds.value = 2
        runCurrent()
        assertTrue("profile 2 follows nobody and was not opened", repo.items.value.isEmpty())
        assertEquals(0L, played.playedMs(pacersReplay.id))

        // Back on profile 1: its row comes back once opened, not marked watched by the other profile.
        profileIds.value = 1
        runCurrent()
        open(repo)
        assertEquals(listOf(pacersId), keys(repo))
        assertTrue(prefs.catchUpMarks.first().seen.isEmpty())
    }

    // --- Pending: a replay that arrives late ---

    @Test
    fun `a replay that arrives after the visit still lists its game`() = runTest {
        followPacers()
        replays.value = emptyList()
        val repo = repository()
        open(repo)
        assertTrue(repo.items.value.isEmpty())
        assertEquals(setOf(pacersId), prefs.catchUpMarks.first().pending.keys)

        // Back the next evening: since is now the last visit, long after the game ended.
        clock += 10 * 60 * minute
        open(repo)
        assertTrue(repo.items.value.isEmpty())
        // The replay arrives: listed, and no longer pending.
        replays.value = listOf(pacersReplay)
        runCurrent()
        assertEquals(listOf(pacersId), keys(repo))
        assertTrue(prefs.catchUpMarks.first().pending.isEmpty())
        assertEquals(setOf(pacersId), prefs.catchUpMarks.first().surfaced.keys)
    }

    @Test
    fun `a listed game stays after since moves on, until 48 h after its end`() = runTest {
        followPacers()
        val repo = repository()
        open(repo)
        clock += 10 * 60 * minute
        open(repo)
        assertEquals(listOf(pacersId), keys(repo))
        // The game's estimated end: 18:00Z + 200 min = 21:20Z.
        clock = Instant.parse("2026-11-17T21:19:00Z").toEpochMilli()
        open(repo)
        assertEquals(listOf(pacersId), keys(repo))
        clock = Instant.parse("2026-11-17T21:21:00Z").toEpochMilli()
        open(repo)
        assertTrue(repo.items.value.isEmpty())
    }

    @Test
    fun `several sources give one item with each source's replay`() = runTest {
        followPacers()
        replays.value = listOf(pacersReplay, replay("Indiana Pacers vs Philadelphia 76ers", "2026-11-15T18:00:00Z", addonId = "b"))
        val repo = repository()
        open(repo)
        assertEquals(listOf("a", "b"), repo.items.value.single().replays.map { it.addonId })
    }
}
