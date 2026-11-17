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
import java.time.ZoneId
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
    private val mySports = MutableStateFlow(MySports.DEFAULT)
    private val basketballOnly = MySports.decode(setOf("basketball"))
    private var clock = Instant.parse("2026-11-16T12:00:00Z").toEpochMilli()

    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
    private val nov15 = LocalDate.of(2026, 11, 15)

    private var saves = 0

    private fun repository(zone: ZoneId = ZoneOffset.UTC) = ResultsRepository(
        provider = provider,
        settings = settings,
        followedTeams = followed,
        mySports = mySports,
        recentLeagues = recent,
        saveRecentLeagues = { saves++; recent.value = it },
        now = { clock },
        zone = { zone }
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
    fun `by default a day fetches soccer all once plus NBA NFL NHL MLB, and nothing more`() = runTest {
        val repo = repository()
        repo.load(1)
        assertEquals(
            listOf(EspnLeagueMap.SOCCER_ALL, EspnLeagueMap.NBA, EspnLeagueMap.NFL, EspnLeagueMap.NHL, EspnLeagueMap.MLB).map { ScoreFeed(it, nov15) }.toSet(),
            provider.calls.toSet()
        )
        assertEquals(5, provider.calls.size)
        // Leagues seen on cards no longer add requests.
        recent.value = mapOf("soccer/eng.1" to clock, "basketball/mens-college-basketball" to clock)
        repo.load(2)
        assertEquals(10, provider.calls.size)
        assertTrue(provider.calls.none { it.league.league == "eng.1" || it.league == EspnLeagueMap.COLLEGE_BASKETBALL })
    }

    @Test
    fun `soccer all lists internationals under their competition`() = runTest {
        clock = Instant.parse("2026-11-17T12:00:00Z").toEpochMilli()
        mySports.value = MySports.decode(setOf("soccer"))
        val all = EspnFixtures.events(EspnFixtures.SOCCER_ALL_FULL_20261116, EspnLeagueMap.SOCCER_ALL)
        provider.respond = { ScoreboardResult(all) }
        val load = repository().load(1) as ResultsLoad.Loaded
        assertEquals(listOf(ScoreFeed(EspnLeagueMap.SOCCER_ALL, LocalDate.of(2026, 11, 16))), provider.calls)
        val germany = load.rows.single { it.event.eventId == "401861185" }
        assertEquals("UEFA Nations League", germany.event.leagueName)
        val nations = SportsResults.groups(load.rows).single { it.name == "UEFA Nations League" }
        assertTrue(germany in nations.rows)
        assertEquals(10, nations.rows.size)
    }

    @Test
    fun `a day fetches each relevant league once and lists its finals, followed first`() = runTest {
        followed.value = listOf(FollowedTeam("Knicks", "Basketball"))
        mySports.value = MySports.decode(setOf("hockey"))
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
        mySports.value = basketballOnly
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
        mySports.value = MySports.decode(setOf("basketball", "hockey"))
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

    @Test
    fun `a repeated noteSeen writes nothing`() = runTest {
        val repo = repository()
        val cards = listOf(card("Lakers vs Celtics", "Basketball"))
        repo.noteSeen(cards)
        repo.noteSeen(cards)
        clock += 60 * 60_000L
        repo.noteSeen(cards)
        assertEquals(1, saves)
    }

    @Test
    fun `days are whole ESPN days counted in US Eastern time, Yesterday only when it is the device's yesterday`() = runTest {
        // 20:00Z: 15:00 in New York (the 16th), 05:00 the 17th in Tokyo.
        clock = Instant.parse("2026-11-16T20:00:00Z").toEpochMilli()
        val tokyo = repository(ZoneId.of("Asia/Tokyo")).days()
        assertEquals((1..7).map { LocalDate.of(2026, 11, 16).minusDays(it.toLong()) }, tokyo.map { it.date })
        assertTrue(tokyo.none { it.isDeviceYesterday })
        mySports.value = basketballOnly
        repository(ZoneId.of("Asia/Tokyo")).load(1)
        assertEquals(ScoreFeed(EspnLeagueMap.NBA, LocalDate.of(2026, 11, 15)), provider.calls.single())
        // In New York (or UTC at that hour) the first chip is Yesterday.
        assertTrue(repository(ZoneId.of("America/New_York")).days().first().isDeviceYesterday)
        assertEquals(listOf(true) + List(6) { false }, repository(ZoneOffset.UTC).days().map { it.isDeviceYesterday })
    }
}
