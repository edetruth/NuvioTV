package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.domain.model.FollowedTeam
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
    private val mySports = MutableStateFlow(MySports.DEFAULT)
    private val basketballOnly = MySports.decode(setOf("basketball"))
    private var clock = Instant.parse("2026-11-16T12:00:00Z").toEpochMilli()

    private val nba = EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA)
    private val nov15 = LocalDate.of(2026, 11, 15)

    private fun repository(zone: ZoneId = ZoneOffset.UTC) = ResultsRepository(
        provider = provider,
        settings = settings,
        followedTeams = followed,
        mySports = mySports,
        now = { clock },
        zone = { zone }
    )

    @Test
    fun `Scores Off shows nothing and requests nothing`() = runTest {
        settings.value = ScoreSettings(mode = ScoresMode.OFF)
        followed.value = listOf(FollowedTeam("Knicks", "Basketball"))
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
        assertTrue(provider.calls.none { it.league == EspnLeagueMap.COLLEGE_BASKETBALL })
    }

    @Test
    fun `the score delay holds back a final that may have just ended, as on the cards`() = runTest {
        settings.value = ScoreSettings(delaySeconds = 30)
        mySports.value = basketballOnly
        val final = nba.first { it.state == ScoreState.POST && it.isFinal }
        // ESPN gives no end time: a final that started within 12 h may have ended a moment ago.
        val finals = listOf(
            final.copy(eventId = "long-over", startMs = clock - 20 * 60 * 60_000L),
            final.copy(eventId = "recent", startMs = clock - 3 * 60 * 60_000L)
        )
        val recent = setOf("recent")
        provider.respond = { feed -> ScoreboardResult(if (feed.league == EspnLeagueMap.NBA) finals else emptyList()) }
        val repo = repository()
        val first = repo.load(1) as ResultsLoad.Loaded
        assertEquals(listOf("long-over"), first.rows.map { it.event.eventId })
        assertEquals(clock + 30_000, first.heldUntil)
        clock += 29_000
        assertTrue((repo.load(1) as ResultsLoad.Loaded).rows.none { it.event.eventId in recent })
        clock += 1_000
        val due = repo.load(1) as ResultsLoad.Loaded
        assertEquals(finals.map { it.eventId }.toSet(), due.rows.map { it.event.eventId }.toSet())
        assertEquals(null, due.heldUntil)
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
