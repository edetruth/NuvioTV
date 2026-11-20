package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.SportsLiveEspnStarts
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.coroutines.EmptyCoroutineContext

/** #169: EuroLeague / EuroCup scores from their own feed, and the ESPN leagues routed since. */
@OptIn(ExperimentalCoroutinesApi::class)
class EuroLeagueScoresTest {

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun games(): List<EuroLeagueGameDto> =
        EspnFixtures.moshi.adapter(EuroLeagueGamesDto::class.java).fromJson(ROUND_13)!!.data!!

    // --- Feed parsing ---

    @Test
    fun `a live game reads its score, period, clock and the club names cards use`() {
        val live = EuroLeagueScoreSource.toSnapshots(games(), EspnLeagueMap.EUROLEAGUE).single { it.eventId == "euroleague:E2026_120" }
        assertEquals(ScoreState.IN, live.state)
        assertEquals(51, live.homeScore)
        assertEquals(46, live.awayScore)
        // 23 minutes played: the third quarter, 7 minutes left.
        assertEquals("Q3 7:00", live.clockText)
        assertEquals(listOf(20, 22, 9), live.home.linescores)
        assertEquals("Maccabi Rapyd Tel Aviv", live.homeName)
        assertTrue("Maccabi Tel Aviv" in live.home.alternateNames)
        assertEquals("https://media-cdn.incrowdsports.com/maccabi.png", live.homeLogo)
        assertEquals(ms("2026-11-19T17:15:00Z"), live.startMs)
        assertEquals("EuroLeague", live.leagueName)
    }

    @Test
    fun `a result is final and a confirmed game is scheduled without a score`() {
        val snapshots = EuroLeagueScoreSource.toSnapshots(games(), EspnLeagueMap.EUROLEAGUE).associateBy { it.eventId }
        val result = snapshots.getValue("euroleague:E2026_121")
        assertEquals(ScoreState.POST, result.state)
        assertTrue(result.isFinal)
        assertEquals("Final", result.clockText)
        assertEquals(92, result.homeScore)
        val scheduled = snapshots.getValue("euroleague:E2026_130")
        assertEquals(ScoreState.PRE, scheduled.state)
        assertNull(scheduled.homeScore)
        assertNull(scheduled.clockText)
    }

    @Test
    fun `a sponsor in the club name still matches the card's name`() {
        val live = EuroLeagueScoreSource.toSnapshots(games(), EspnLeagueMap.EUROLEAGUE)
        val matcher = ScoreMatcher(zone = { ZoneOffset.UTC })
        val card = ScoreCard("k1", SportsLiveTeams.parse("Maccabi Tel Aviv vs BC Dubai")!!, ms("2026-11-19T17:15:00Z"), true)
        assertEquals("euroleague:E2026_120", matcher.match(card, live, ms("2026-11-19T18:00:00Z"))?.eventId)
        val panathinaikos = EuroLeagueScoreSource.alternateNames("Panathinaikos AKTOR Athens", listOf("Panathinaikos"))
        assertTrue("Panathinaikos Athens" in panathinaikos)
    }

    @Test
    fun `the season and competition codes follow the day and the league`() {
        assertEquals("E2026", EuroLeagueScoreSource.seasonCode("E", LocalDate.of(2026, 11, 19)))
        assertEquals("E2026", EuroLeagueScoreSource.seasonCode("E", LocalDate.of(2027, 4, 16)))
        assertEquals("U", EuroLeagueScoreSource.competitionOf(EspnLeagueMap.EUROCUP))
        assertNull(EuroLeagueScoreSource.competitionOf(EspnLeagueMap.NBA))
    }

    // --- Fetching ---

    private val requests = mutableListOf<String>()

    private fun source(code: Int = 200, now: () -> Long, health: EspnFeedHealth? = null) = EuroLeagueScoreSource(
        OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val url = chain.request().url.toString()
            requests += url
            val body = when {
                "limit=" in url -> SEASON
                "roundNumber=13" in url -> ROUND_13
                else -> """{"data":[]}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fake")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }).build(),
        EspnFixtures.moshi,
        baseUrl = "https://example.invalid/competitions/",
        now = now,
        health = health
    )

    @Test
    fun `a day reads the season calendar once, then only its rounds`() = runTest {
        var now = ms("2026-11-19T18:00:00Z")
        val source = source(now = { now })
        val day = source.scoreboard(EspnLeagueMap.EUROLEAGUE, LocalDate.of(2026, 11, 19))
        assertFalse(day.failed)
        assertEquals(setOf("euroleague:E2026_120", "euroleague:E2026_121"), day.events.map { it.eventId }.toSet())
        assertEquals(2, requests.size)
        assertTrue(requests[0].endsWith("E/seasons/E2026/games?limit=500"))
        assertTrue(requests[1].endsWith("E/seasons/E2026/games?roundNumber=13"))
        // The next day's feed polled at the same time shares the round; 30 s later the round is asked again.
        val next = source.scoreboard(EspnLeagueMap.EUROLEAGUE, LocalDate.of(2026, 11, 20))
        assertEquals(listOf("euroleague:E2026_130"), next.events.map { it.eventId })
        assertEquals(2, requests.size)
        now += 30_000L
        source.scoreboard(EspnLeagueMap.EUROLEAGUE, LocalDate.of(2026, 11, 19))
        assertEquals(3, requests.size)
        // A day with no round asks for nothing more.
        assertTrue(source.scoreboard(EspnLeagueMap.EUROLEAGUE, LocalDate.of(2026, 11, 21)).events.isEmpty())
        assertEquals(3, requests.size)
    }

    @Test
    fun `a failing feed is no score and shows in Scores status`() = runTest {
        val now = ms("2026-11-19T18:00:00Z")
        val health = EspnFeedHealth { now }
        val result = source(code = 503, now = { now }, health = health).scoreboard(EspnLeagueMap.EUROLEAGUE, LocalDate.of(2026, 11, 19))
        assertTrue(result.failed)
        val feed = health.feeds.value.getValue(EspnFeedKey.scoreboard(EspnLeagueMap.EUROLEAGUE, LocalDate.of(2026, 11, 19)))
        assertEquals(FeedFailureKind.HTTP, feed.lastFailure)
    }

    // --- Routing ---

    private class FakeEspn : ScoreProvider {
        val calls = mutableListOf<ScoreFeed>()
        override suspend fun scoreboard(league: EspnLeague, date: LocalDate): ScoreboardResult {
            calls += ScoreFeed(league, date)
            return ScoreboardResult(emptyList())
        }
        override suspend fun teamList(league: EspnLeague): List<DirectoryTeam> = emptyList()
    }

    private class FakeSource(var events: List<ScoreSnapshot> = emptyList()) : LeagueScoreSource {
        val calls = mutableListOf<ScoreFeed>()
        override fun handles(league: EspnLeague) = league == EspnLeagueMap.EUROLEAGUE || league == EspnLeagueMap.EUROCUP
        override suspend fun scoreboard(league: EspnLeague, date: LocalDate): ScoreboardResult {
            calls += ScoreFeed(league, date)
            return ScoreboardResult(events)
        }
    }

    @Test
    fun `EuroLeague and EuroCup go to their own feed and every other league to ESPN`() = runTest {
        val espn = FakeEspn()
        val euro = FakeSource()
        val routed = RoutedScoreProvider(espn, listOf(euro))
        val day = LocalDate.of(2026, 11, 19)
        routed.scoreboard(EspnLeagueMap.EUROLEAGUE, day)
        routed.scoreboard(EspnLeagueMap.EUROCUP, day)
        routed.scoreboard(EspnLeagueMap.NBA, day)
        assertEquals(listOf(EspnLeagueMap.EUROLEAGUE, EspnLeagueMap.EUROCUP), euro.calls.map { it.league })
        assertEquals(listOf(EspnLeagueMap.NBA), espn.calls.map { it.league })
        // ESPN has no list or warm copy of a league it does not cover.
        assertNull(routed.teamList(EspnLeagueMap.EUROLEAGUE))
        assertNotNull(routed.teamList(EspnLeagueMap.NBA))
        assertNull(routed.recentScoreboard(EspnLeagueMap.EUROLEAGUE, day))
    }

    private fun slugs(sport: String?, vararg hints: String) = EspnLeagueMap.leaguesFor(sport, hints.toList()).map { it.path }

    @Test
    fun `EuroLeague, EuroCup and the G League route to their own feeds`() {
        assertEquals(listOf("basketball/euroleague"), slugs("Basketball", "Maccabi Tel Aviv vs Dubai Basketball", "EuroLeague"))
        assertEquals(listOf("basketball/euroleague"), slugs(null, "Turkish Airlines EuroLeague: Real Madrid vs Olympiacos"))
        assertEquals(listOf("basketball/eurocup"), slugs("Basketball", "Trento vs Manresa", "EuroCup"))
        assertEquals(listOf("basketball/nba-development"), slugs("Basketball", "Memphis Hustle vs Texas Legends", "NBA G League"))
        assertEquals(listOf("basketball/nba-development"), slugs("G League", "Memphis Hustle vs Texas Legends"))
        // The UEFA Europa League is soccer's, never EuroLeague basketball.
        assertEquals(listOf("soccer/uefa.europa"), slugs("Football", "Roma vs Lyon", "Europa League"))
    }

    @Test
    fun `the new ESPN soccer slugs route by competition name`() {
        assertEquals(listOf("soccer/rou.1"), slugs("Football", "Rapid Bucuresti vs CFR Cluj", "Romanian Liga 1"))
        assertEquals(listOf("soccer/rou.1"), slugs("Soccer", "Rapid Bucuresti vs FCSB", "Romanian Liga I"))
        assertEquals(listOf("soccer/ksa.1"), slugs("Football", "Al Nassr vs Al Ahli", "Saudi Pro League"))
        assertEquals(listOf("soccer/ksa.1"), slugs("Soccer", "Al Hilal vs Al Ittihad", "Saudi-Arabian Pro League"))
        assertEquals(listOf("soccer/afc.champions"), slugs("Football", "Al Hilal vs Al Ain", "AFC Champions League Elite"))
        assertEquals(listOf("soccer/aut.1"), slugs("Soccer", "Rapid Wien vs Sturm Graz", "Austrian Bundesliga"))
        // A plain game, or a basketball card naming a soccer competition, keeps its sport's feed.
        assertEquals(listOf("soccer/all"), slugs("Football", "Beltinci vs Celje"))
        assertEquals(listOf("basketball/nba"), slugs("Basketball", "Kerala vs Goa", "Indian Super League"))
    }

    @Test
    fun `NCAA volleyball and named rugby union competitions route, other volleyball and rugby do not`() {
        assertEquals(
            listOf("volleyball/womens-college-volleyball", "volleyball/mens-college-volleyball"),
            slugs("Volleyball", "Texas vs Nebraska", "NCAA")
        )
        assertEquals(listOf("volleyball/womens-college-volleyball"), slugs("Women's College Volleyball", "Texas vs Nebraska"))
        assertEquals(emptyList<String>(), slugs("Volleyball", "Zenit Kazan vs Belogorie"))
        assertEquals(listOf("rugby/270557"), slugs("Rugby", "Leinster vs Munster", "United Rugby Championship"))
        assertEquals(listOf("rugby/180659"), slugs(null, "Six Nations: France vs England"))
        assertEquals(listOf("rugby/271937"), slugs("Rugby Union", "Toulouse vs Leinster", "Champions Cup"))
        // Without rugby said, a Champions Cup is no rugby game; rugby league and plain rugby stay unrouted.
        assertEquals(emptyList<String>(), slugs("Rugby League", "Wigan vs Leeds", "Super League"))
        assertEquals(emptyList<String>(), slugs("Rugby", "Leinster vs Munster"))
        assertEquals(emptyList<String>(), slugs(null, "Rugby World Cup Final: NZ vs SA"))
    }

    // --- Polling (precision rule) ---

    private fun card(name: String, start: String) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = listOf("EuroLeague")
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false, startMs = ms(start), sport = "Basketball",
        startHasTimeOfDay = true, teams = SportsLiveTeams.parse(name)
    )

    @Test
    fun `the EuroLeague feed is polled only while its game is on screen`() = runTest {
        val euro = FakeSource(EuroLeagueScoreSource.toSnapshots(games(), EspnLeagueMap.EUROLEAGUE))
        val espn = FakeEspn()
        val base = ms("2026-11-19T18:00:00Z")
        val repo = LiveScoresRepository(
            provider = RoutedScoreProvider(espn, listOf(euro)),
            settings = MutableStateFlow(ScoreSettings()),
            scope = backgroundScope,
            now = { base + testScheduler.currentTime },
            zone = { ZoneOffset.UTC },
            jitter = { 0L },
            io = EmptyCoroutineContext,
            espnStarts = SportsLiveEspnStarts()
        )
        backgroundScope.launch { repo.scores.collect {} }
        val game = card("Maccabi Tel Aviv vs Dubai Basketball", "2026-11-19T17:15:00Z")
        repo.track(LiveScoresRepository.SCREEN_SECTION, listOf(game))
        runCurrent()
        assertTrue(euro.calls.isNotEmpty())
        assertTrue(euro.calls.all { it.league == EspnLeagueMap.EUROLEAGUE })
        assertTrue(espn.calls.none { it.league == EspnLeagueMap.EUROLEAGUE })
        assertEquals("euroleague:E2026_120", repo.scores.value[game.streamKey]?.eventId)
        // Live: asked again within a minute.
        val first = euro.calls.size
        advanceTimeBy(31_000L); runCurrent()
        assertTrue(euro.calls.size > first)
        // Off screen: no more requests.
        repo.untrack(LiveScoresRepository.SCREEN_SECTION)
        runCurrent()
        val stopped = euro.calls.size
        advanceTimeBy(10 * 60_000L); runCurrent()
        assertEquals(stopped, euro.calls.size)
    }

    private companion object {
        fun game(
            id: String, date: String, round: Int, status: String, minute: String?,
            home: Triple<String, String, String>, away: Triple<String, String, String>,
            homeScore: Int, awayScore: Int, homeQuarters: String = "null,null,null,null", awayQuarters: String = "null,null,null,null"
        ): String {
            fun q(values: String) = values.split(',').let { (a, b, c, d) -> """{"q1":$a,"q2":$b,"q3":$c,"q4":$d,"ot1":null}""" }
            fun team(t: Triple<String, String, String>, score: Int, quarters: String) =
                """{"code":"${t.third}","name":"${t.first}","abbreviatedName":"${t.second}","editorialName":"${t.second}",""" +
                    """"tla":"${t.third}","score":$score,"quarters":${q(quarters)},""" +
                    """"imageUrls":{"crest":"https://media-cdn.incrowdsports.com/${t.second.lowercase()}.png"}}"""
            val min = minute?.let { "\"$it\"" } ?: "null"
            return """{"identifier":"$id","date":"$date","status":"$status","minute":$min,"remainingTime":null,"quarter":null,""" +
                """"confirmedTime":true,"round":{"round":$round},"home":${team(home, homeScore, homeQuarters)},"away":${team(away, awayScore, awayQuarters)}}"""
        }

        val MACCABI = Triple("Maccabi Rapyd Tel Aviv", "Maccabi", "MTA")
        val DUBAI = Triple("Dubai Basketball", "Dubai", "DUB")
        val VALENCIA = Triple("Valencia Basket", "Valencia", "VBC")
        val VIRTUS = Triple("Virtus Bologna", "Virtus", "VIR")
        val PARTIZAN = Triple("Partizan Mozzart Bet Belgrade", "Partizan", "PAR")
        val BESIKTAS = Triple("Besiktas Istanbul", "Besiktas", "BJK")

        val ROUND_13 = """{"status":"success","data":[""" + listOf(
            game("E2026_120", "2026-11-19T17:15:00.000Z", 13, "live", "23:00", MACCABI, DUBAI, 51, 46, "20,22,9,0", "18,20,8,0"),
            game("E2026_121", "2026-11-19T20:00:00.000Z", 13, "result", "40:00", VALENCIA, VIRTUS, 92, 87, "25,20,24,23", "22,21,22,22"),
            game("E2026_130", "2026-11-20T19:45:00.000Z", 13, "confirmed", null, PARTIZAN, BESIKTAS, 0, 0)
        ).joinToString(",") + "]}"

        // The season calendar: round 12 earlier in the week, round 13 on the 19th and 20th.
        val SEASON = """{"status":"success","data":[""" + listOf(
            game("E2026_110", "2026-11-17T19:30:00.000Z", 12, "result", "40:00", VALENCIA, PARTIZAN, 76, 64),
            game("E2026_120", "2026-11-19T17:15:00.000Z", 13, "confirmed", null, MACCABI, DUBAI, 0, 0),
            game("E2026_121", "2026-11-19T20:00:00.000Z", 13, "confirmed", null, VALENCIA, VIRTUS, 0, 0),
            game("E2026_130", "2026-11-20T19:45:00.000Z", 13, "confirmed", null, PARTIZAN, BESIKTAS, 0, 0)
        ).joinToString(",") + "]}"
    }
}
