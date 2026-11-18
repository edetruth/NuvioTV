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

    /** The live scores' copies lent to Results, by feed, with when each was fetched. */
    private val live = HashMap<ScoreFeed, Pair<List<ScoreSnapshot>, Long>>()
    private val liveAsked = mutableListOf<ScoreFeed>()

    private fun repository(zone: ZoneId = ZoneOffset.UTC) = ResultsRepository(
        provider = provider,
        settings = settings,
        followedTeams = followed,
        mySports = mySports,
        now = { clock },
        zone = { zone },
        liveFeed = { feed, maxAge ->
            liveAsked += feed
            live[feed]?.takeIf { clock - it.second < maxAge }?.first
        }
    )

    private val nov16 = LocalDate.of(2026, 11, 16)
    private val nbaToday = ScoreFeed(EspnLeagueMap.NBA, nov16)
    private val aFinal = nba.first { it.state == ScoreState.POST && it.isFinal }

    private fun todayGame(id: String, state: ScoreState, startedAgoMs: Long = 20 * 60 * 60_000L) = aFinal.copy(
        eventId = id,
        state = state,
        isFinal = state == ScoreState.POST,
        startMs = clock - startedAgoMs
    )

    @Test
    fun `Scores Off shows nothing and requests nothing`() = runTest {
        settings.value = ScoreSettings(mode = ScoresMode.OFF)
        followed.value = listOf(FollowedTeam("Knicks", "Basketball"))
        assertEquals(ResultsLoad.Off, repository().load(1))
        assertTrue(provider.calls.isEmpty())
    }

    @Test
    fun `by default a day fetches soccer all once plus NBA NFL NHL MLB and the individual sports, and nothing more`() = runTest {
        val repo = repository()
        repo.load(1)
        assertEquals(
            listOf(
                EspnLeagueMap.SOCCER_ALL, EspnLeagueMap.NBA, EspnLeagueMap.NFL, EspnLeagueMap.NHL, EspnLeagueMap.MLB,
                EspnLeagueMap.ATP, EspnLeagueMap.WTA, EspnLeagueMap.UFC, EspnLeagueMap.F1, EspnLeagueMap.PGA, EspnLeagueMap.LPGA
            ).map { ScoreFeed(it, nov15) }.toSet(),
            provider.calls.toSet()
        )
        assertEquals(11, provider.calls.size)
        assertTrue(provider.calls.none { it.league == EspnLeagueMap.COLLEGE_BASKETBALL })
    }

    @Test
    fun `a set My sports requests no individual sport it did not pick`() = runTest {
        mySports.value = MySports.decode(setOf("basketball", "soccer"))
        repository().load(1)
        assertEquals(setOf(EspnLeagueMap.SOCCER_ALL, EspnLeagueMap.NBA), provider.calls.map { it.league }.toSet())
        assertTrue(provider.calls.none { EspnIndividualResults.covers(it.league) })
    }

    @Test
    fun `a picked individual sport requests only its chosen leagues, and lists its results`() = runTest {
        mySports.value = MySports.decode(setOf("golf/lpga", "mma"))
        val lpga = EspnIndividualResults.toSnapshots(
            EspnFixtures.moshi.adapter(EspnScoreboardDto::class.java).fromJson(EspnFixtures.json("espn_golf_lpga_20261115.json"))!!,
            EspnLeagueMap.LPGA,
            nov15
        )
        provider.respond = { feed -> ScoreboardResult(if (feed.league == EspnLeagueMap.LPGA) lpga else emptyList()) }
        val loaded = repository().load(1) as ResultsLoad.Loaded
        assertEquals(setOf(EspnLeagueMap.LPGA, EspnLeagueMap.UFC), provider.calls.map { it.league }.toSet())
        assertEquals(listOf("golf:401835176"), loaded.rows.map { it.event.eventId })
        assertTrue(loaded.rows.none { it.followed })
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
        assertEquals((0..6).map { LocalDate.of(2026, 11, 16).minusDays(it.toLong()) }, tokyo.map { it.date })
        // In Tokyo ESPN's today (the 16th) is the device's yesterday; ESPN's yesterday is not.
        assertEquals(listOf(true) + List(6) { false }, tokyo.map { it.isDeviceYesterday })
        mySports.value = basketballOnly
        repository(ZoneId.of("Asia/Tokyo")).load(1)
        assertEquals(ScoreFeed(EspnLeagueMap.NBA, LocalDate.of(2026, 11, 15)), provider.calls.single())
        // In New York (or UTC at that hour) the second chip is Yesterday.
        assertTrue(repository(ZoneId.of("America/New_York")).days()[1].isDeviceYesterday)
        assertEquals(listOf(false, true) + List(5) { false }, repository(ZoneOffset.UTC).days().map { it.isDeviceYesterday })
    }

    @Test
    fun `Today lists finals only and counts the games being played in My sports (#67)`() = runTest {
        mySports.value = basketballOnly
        val games = listOf(
            todayGame("final", ScoreState.POST),
            todayGame("live-1", ScoreState.IN),
            todayGame("live-2", ScoreState.IN),
            todayGame("later", ScoreState.PRE)
        )
        provider.respond = { feed -> ScoreboardResult(if (feed == nbaToday) games else emptyList()) }
        val today = repository().load(0) as ResultsLoad.Loaded
        assertEquals(nov16, today.date)
        assertEquals(listOf("final"), today.rows.map { it.event.eventId })
        assertEquals(2, today.liveNow)
        assertTrue(today.hasMyFinals)
        // Past days count nothing live.
        assertEquals(0, (repository().load(1) as ResultsLoad.Loaded).liveNow)
    }

    @Test
    fun `the live count leaves out games outside My sports and followed teams`() = runTest {
        mySports.value = MySports.decode(setOf("hockey"))
        provider.respond = { ScoreboardResult(listOf(todayGame("nba-live", ScoreState.IN))) }
        assertEquals(0, (repository().load(0) as ResultsLoad.Loaded).liveNow)
        followed.value = listOf(FollowedTeam(aFinal.home.displayName!!, "Basketball"))
        assertEquals(1, (repository().load(0) as ResultsLoad.Loaded).liveNow)
    }

    @Test
    fun `Today reuses the live scores' fresh copy and makes no request (#67)`() = runTest {
        mySports.value = basketballOnly
        live[nbaToday] = listOf(todayGame("final", ScoreState.POST)) to clock - 60_000L
        val repo = repository()
        val today = repo.load(0) as ResultsLoad.Loaded
        assertEquals(listOf("final"), today.rows.map { it.event.eventId })
        assertTrue(provider.calls.isEmpty())
        // Past days never ask the live scores.
        repo.load(1)
        assertEquals(listOf(nbaToday), liveAsked)
        // A copy 2 min old is not fresh: Today is fetched.
        clock += 60_000L
        repo.load(0)
        assertEquals(listOf(nbaToday), provider.calls.filter { it.date == nov16 })
    }

    @Test
    fun `Today is fetched again at most every 2 min`() = runTest {
        mySports.value = basketballOnly
        val repo = repository()
        repo.load(0)
        clock += 119_000L
        repo.load(0)
        assertEquals(1, provider.calls.size)
        clock += 1_000L
        repo.load(0)
        assertEquals(2, provider.calls.size)
    }

    @Test
    fun `Today's copy is never kept as a final day`() = runTest {
        mySports.value = basketballOnly
        provider.respond = { ScoreboardResult(listOf(todayGame("live", ScoreState.IN))) }
        val repo = repository()
        repo.load(0)
        // Two days on, the 16th is an older day: fetched again, not served from Today's copy.
        clock += 2 * 24 * 60 * 60_000L
        provider.respond = { ScoreboardResult(listOf(todayGame("live", ScoreState.POST))) }
        val later = repo.load(2) as ResultsLoad.Loaded
        assertEquals(nov16, later.date)
        assertEquals(listOf(nbaToday, nbaToday), provider.calls)
        assertEquals(listOf("live"), later.rows.map { it.event.eventId })
        // Fetched once its day was over: kept for good.
        clock += 24 * 60 * 60_000L
        repo.load(3)
        assertEquals(2, provider.calls.size)
    }

    @Test
    fun `Scores Off asks nothing for Today, not even the live scores`() = runTest {
        settings.value = ScoreSettings(mode = ScoresMode.OFF)
        live[nbaToday] = listOf(todayGame("final", ScoreState.POST)) to clock
        assertEquals(ResultsLoad.Off, repository().load(0))
        assertTrue(provider.calls.isEmpty())
        assertTrue(liveAsked.isEmpty())
    }

    @Test
    fun `the score delay holds a new final of Today and counts it as live meanwhile`() = runTest {
        settings.value = ScoreSettings(delaySeconds = 30)
        mySports.value = basketballOnly
        provider.respond = { ScoreboardResult(listOf(todayGame("just-ended", ScoreState.POST, startedAgoMs = 3 * 60 * 60_000L))) }
        val repo = repository()
        val first = repo.load(0) as ResultsLoad.Loaded
        assertTrue(first.rows.isEmpty())
        assertEquals(1, first.liveNow)
        assertEquals(clock + 30_000, first.heldUntil)
        assertTrue(!first.hasMyFinals)
        clock += 30_000
        val due = repo.load(0) as ResultsLoad.Loaded
        assertEquals(listOf("just-ended"), due.rows.map { it.event.eventId })
        assertEquals(0, due.liveNow)
    }
}
