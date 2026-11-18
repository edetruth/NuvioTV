package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.TestPreferencesStore
import com.nuvio.tv.core.profile.ProfileManager
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

class CatchUpRepositoryTest {

    private class FakeProvider : ScoreProvider {
        val calls = mutableListOf<ScoreFeed>()
        var respond: (ScoreFeed) -> ScoreboardResult = { ScoreboardResult(emptyList()) }
        override suspend fun scoreboard(league: EspnLeague, date: LocalDate): ScoreboardResult {
            val feed = ScoreFeed(league, date)
            calls += feed
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
    private val store = TestPreferencesStore()
    private val prefs = SportsLivePreferencesDataStore(
        mockk<ProfileDataStoreFactory> { every { get(1, SportsLivePreferencesDataStore.FEATURE) } returns store },
        mockk<ProfileManager> { every { activeProfileId } returns MutableStateFlow(1) }
    )
    private val replays = MutableStateFlow(listOf(replay("Indiana Pacers vs Philadelphia 76ers", "2026-11-15T18:00:00Z")))

    private fun replay(name: String, start: String) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = listOf("Basketball")
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Replays",
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
        val repo = CatchUpRepository(results, prefs, replays, { clock }, backgroundScope)
        backgroundScope.launch { repo.items.collect { } }
        return repo
    }

    private suspend fun followPacers() = prefs.follow(FollowedTeam("Indiana Pacers", "Basketball"))

    @Test
    fun `an open loads each day of followed teams' feeds once and lists the replay`() = runTest {
        followPacers()
        val repo = repository()
        repo.onOpened()
        runCurrent()
        assertEquals(listOf(pacersId), repo.items.value.map { it.key })
        // Never opened before: 3 days back. Only the NBA (the followed team's feed), one request per day.
        val expected = (0..3).map { ScoreFeed(EspnLeagueMap.NBA, LocalDate.of(2026, 11, 16).minusDays(it.toLong())) }
        assertEquals(expected.toSet(), provider.calls.toSet())
        assertEquals(expected.size, provider.calls.size)
        // Listed once: remembered as surfaced.
        assertEquals(setOf(pacersId), prefs.catchUpMarks.first().surfaced.keys)
    }

    @Test
    fun `opening again reuses the cache, and nothing is requested between opens`() = runTest {
        followPacers()
        val repo = repository()
        repo.onOpened()
        runCurrent()
        val first = provider.calls.size
        advanceTimeBy(6 * 60 * minute)
        runCurrent()
        assertEquals("no polling", first, provider.calls.size)
        clock += minute
        repo.onOpened()
        runCurrent()
        assertEquals("past days and today come from the cache", first, provider.calls.size)
        // A new replay list only re-selects.
        replays.value = replays.value.toList()
        runCurrent()
        assertEquals(first, provider.calls.size)
    }

    @Test
    fun `no team followed or the setting off requests nothing and shows nothing`() = runTest {
        val repo = repository()
        repo.onOpened()
        runCurrent()
        assertTrue(repo.items.value.isEmpty())
        assertTrue(provider.calls.isEmpty())

        prefs.setCatchUpRow(false)
        followPacers()
        repo.onOpened()
        runCurrent()
        assertTrue(repo.items.value.isEmpty())
        assertTrue(provider.calls.isEmpty())

        prefs.setCatchUpRow(true)
        runCurrent()
        assertEquals(listOf(pacersId), repo.items.value.map { it.key })
        prefs.setCatchUpRow(false)
        runCurrent()
        assertTrue("the row hides", repo.items.value.isEmpty())
    }

    @Test
    fun `Scores Off hides the row and requests nothing`() = runTest {
        prefs.setScoresMode(ScoresMode.OFF)
        followPacers()
        val repo = repository()
        repo.onOpened()
        runCurrent()
        assertTrue(repo.items.value.isEmpty())
        assertTrue(provider.calls.isEmpty())

        // Back to Show: listed; Off again: hidden at once.
        prefs.setScoresMode(ScoresMode.SHOW)
        runCurrent()
        assertEquals(listOf(pacersId), repo.items.value.map { it.key })
        prefs.setScoresMode(ScoresMode.OFF)
        runCurrent()
        assertTrue(repo.items.value.isEmpty())
    }

    @Test
    fun `a replay played 2 min or more leaves the row, a shorter one stays`() = runTest {
        followPacers()
        val repo = repository()
        repo.onOpened()
        runCurrent()
        val item = repo.items.value.single()

        repo.onPlay(item)
        clock += CatchUp.WATCHED_AFTER_MS - 1
        repo.onOpened()
        runCurrent()
        assertEquals(listOf(pacersId), repo.items.value.map { it.key })

        repo.onPlay(item)
        clock += CatchUp.WATCHED_AFTER_MS
        repo.onOpened()
        runCurrent()
        assertTrue(repo.items.value.isEmpty())
        assertEquals(setOf(pacersId), prefs.catchUpMarks.first().seen.keys)
    }

    @Test
    fun `mark as watched removes the item`() = runTest {
        followPacers()
        val repo = repository()
        repo.onOpened()
        runCurrent()
        repo.markWatched(repo.items.value.single())
        runCurrent()
        assertTrue(repo.items.value.isEmpty())
    }

    @Test
    fun `a listed game stays after since moves on, until 3 days after its start`() = runTest {
        followPacers()
        val repo = repository()
        repo.onOpened()
        runCurrent()
        // Back after a long gap: since is now the last visit, after the game, yet it stays.
        clock += 10 * 60 * minute
        repo.onOpened()
        runCurrent()
        assertEquals(listOf(pacersId), repo.items.value.map { it.key })
        // 3 days after its start it is gone.
        clock = Instant.parse("2026-11-18T18:01:00Z").toEpochMilli()
        repo.onOpened()
        runCurrent()
        assertTrue(repo.items.value.isEmpty())
    }
}
