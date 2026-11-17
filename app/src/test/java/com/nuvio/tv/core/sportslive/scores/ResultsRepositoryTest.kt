package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ResultsRepositoryTest {

    private class FakeProvider : ScoreProvider {
        val calls = mutableListOf<ScoreFeed>()
        var respond: (ScoreFeed) -> ScoreboardResult = { ScoreboardResult(emptyList()) }
        override suspend fun scoreboard(league: EspnLeague, date: LocalDate): ScoreboardResult {
            val feed = ScoreFeed(league, date)
            calls += feed
            return respond(feed)
        }
    }

    private val provider = FakeProvider()
    private val settings = MutableStateFlow(ScoreSettings())
    private val followed = MutableStateFlow<List<FollowedTeam>>(emptyList())
    private val recent = MutableStateFlow<Map<String, Long>>(emptyMap())
    private var clock = Instant.parse("2026-11-16T12:00:00Z").toEpochMilli()

    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
    private val nov15 = LocalDate.of(2026, 11, 15)

    private fun repository() = ResultsRepository(
        provider = provider,
        settings = settings,
        followedTeams = followed,
        recentLeagues = recent,
        saveRecentLeagues = { recent.value = it },
        now = { clock },
        zone = { ZoneOffset.UTC }
    )

    private fun card(name: String, genre: String) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = listOf(genre)
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false, startMs = clock, sport = genre,
        startHasTimeOfDay = true, teams = SportsLiveTeams.parse(name)
    )

    @Test
    fun `Scores Off shows nothing and requests nothing`() = runTest {
        settings.value = ScoreSettings(mode = ScoresMode.OFF)
        followed.value = listOf(FollowedTeam("Knicks", "Basketball"))
        recent.value = mapOf("basketball/nba" to clock)
        assertEquals(ResultsLoad.Off, repository().load(1))
        assertTrue(provider.calls.isEmpty())
    }

    @Test
    fun `no relevant league is its own state, with no request`() = runTest {
        assertEquals(ResultsLoad.NoLeagues, repository().load(1))
        assertTrue(provider.calls.isEmpty())
    }

    @Test
    fun `a day fetches each relevant league once and lists its finals, followed first`() = runTest {
        followed.value = listOf(FollowedTeam("Knicks", "Basketball"))
        recent.value = mapOf("hockey/nhl" to clock - 60_000)
        provider.respond = { feed -> if (feed.league == EspnLeagueMap.NBA) ScoreboardResult(nba) else ScoreboardResult(emptyList()) }
        val repo = repository()
        val load = repo.load(1) as ResultsLoad.Loaded
        assertEquals(nov15, load.date)
        assertEquals(setOf(ScoreFeed(EspnLeagueMap.NHL, nov15), ScoreFeed(EspnLeagueMap.NBA, nov15)), provider.calls.toSet())
        assertEquals(2, provider.calls.size)
        assertEquals("401909990", load.rows.first().event.eventId)
        assertTrue(load.rows.first().followed)
    }

    @Test
    fun `past days are not polled and a day shown again comes from memory`() = runTest {
        recent.value = mapOf("basketball/nba" to clock)
        provider.respond = { ScoreboardResult(nba) }
        val repo = repository()
        repo.load(3)
        repo.load(3)
        assertEquals(1, provider.calls.size)
        // Yesterday is re-fetched after a while (late games), older days are not.
        repo.load(1)
        clock += 16 * 60_000L
        repo.load(1)
        repo.load(3)
        assertEquals(3, provider.calls.size)
    }

    @Test
    fun `every league failing is Failed, one failing still lists the others`() = runTest {
        recent.value = mapOf("basketball/nba" to clock, "hockey/nhl" to clock)
        provider.respond = { ScoreboardResult.FAILED }
        val repo = repository()
        assertEquals(ResultsLoad.Failed, repo.load(1))
        provider.respond = { feed -> if (feed.league == EspnLeagueMap.NBA) ScoreboardResult(nba) else ScoreboardResult.FAILED }
        assertEquals(nba.size, (repo.load(2) as ResultsLoad.Loaded).rows.size)
    }

    @Test
    fun `cards seen are recorded as recent leagues`() = runTest {
        val repo = repository()
        repo.noteSeen(listOf(card("Lakers vs Celtics", "Basketball"), card("Rangers vs Bruins", "Hockey")))
        assertEquals(mapOf("basketball/nba" to clock, "hockey/nhl" to clock), recent.value)
    }
}
