package com.nuvio.tv.core.sportslive.scores

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

class EspnScoreProviderTest {

    private val requested = mutableListOf<String>()
    private val cacheControl = mutableListOf<String?>()

    private fun provider(interceptor: Interceptor) = EspnScoreProvider(
        OkHttpClient.Builder().addInterceptor(interceptor).build(),
        EspnFixtures.moshi,
        "https://example.invalid/apis/site/v2/sports/",
        today = { LocalDate.of(2026, 11, 16) }
    )

    private fun respond(code: Int, body: String) = Interceptor { chain ->
        requested += chain.request().url.toString()
        cacheControl += chain.request().header("Cache-Control")
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("fake")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }

    private val day = LocalDate.of(2026, 11, 15)

    @Test
    fun `parses an NBA scoreboard and asks only for league and date`() = runTest {
        val result = provider(respond(200, EspnFixtures.json(EspnFixtures.NBA_20261115)))
            .scoreboard(EspnLeagueMap.NBA, day)

        assertEquals(listOf("https://example.invalid/apis/site/v2/sports/basketball/nba/scoreboard?dates=20261115&limit=1000"), requested)
        assertFalse(result.failed)
        assertEquals(5, result.events.size)
        val game = result.events.first { it.eventId == "401909985" }
        assertEquals("PHI", game.homeAbbr)
        assertEquals("IND", game.awayAbbr)
        assertEquals("Philadelphia 76ers", game.homeName)
        assertEquals(131, game.homeScore)
        assertEquals(124, game.awayScore)
        assertEquals(listOf(29, 29, 39, 34), game.home.linescores)
        assertEquals(ScoreState.POST, game.state)
        assertTrue(game.isFinal)
        assertEquals("Final", game.clockText)
        assertEquals(Instant.parse("2026-11-15T18:00:00Z").toEpochMilli(), game.startMs)
        assertTrue(game.homeLogo!!.startsWith("https://a.espncdn.com/"))
        assertEquals(EspnLeagueMap.NBA, game.league)

        val overtime = result.events.first { it.eventId == "401909990" }
        assertEquals("Final/OT", overtime.clockText)
        assertEquals(5, overtime.away.linescores.size)
    }

    @Test
    fun `live days skip the HTTP cache, past days use a copy up to 7 days stale`() = runTest {
        val p = provider(respond(200, "{}"))
        p.scoreboard(EspnLeagueMap.NBA, LocalDate.of(2026, 11, 17))
        p.scoreboard(EspnLeagueMap.NBA, LocalDate.of(2026, 11, 16))
        p.scoreboard(EspnLeagueMap.NBA, LocalDate.of(2026, 11, 15))
        p.scoreboard(EspnLeagueMap.NBA, LocalDate.of(2026, 11, 14))
        assertEquals(listOf("no-cache", "no-cache", "no-cache", "max-stale=604800"), cacheControl)
    }

    @Test
    fun `Retry-After in seconds is kept on a failure`() {
        assertEquals(120_000L, EspnScoreProvider.retryAfterMs("120"))
        assertEquals(900_000L, EspnScoreProvider.retryAfterMs("99999"))
        assertEquals(null, EspnScoreProvider.retryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertEquals(null, EspnScoreProvider.retryAfterMs(null))
    }

    @Test
    fun `keeps each event's own league slug`() {
        val all = EspnFixtures.events(EspnFixtures.SOCCER_ALL_20261116, EspnLeagueMap.SOCCER_ALL)
        assertEquals("fifa.friendly_u21", all.first { it.eventId == "401930175" }.leagueSlug)
        assertEquals("uefa.nations", all.first { it.eventId == "401861185" }.leagueSlug)
        assertEquals("caf.nations_qual", all.first { it.eventId == "401927170" }.leagueSlug)
        // No league link: a sibling of the same ESPN league (l:20132) names it.
        assertEquals("fifa.friendly_u21", all.first { it.eventId == "401934216" }.leagueSlug)
        assertEquals("nba", EspnFixtures.events(EspnFixtures.NBA_20261115, EspnLeagueMap.NBA).first().leagueSlug)
    }

    @Test
    fun `parses an NFL scoreboard`() {
        val game = EspnFixtures.events(EspnFixtures.NFL_20261115, EspnLeagueMap.NFL).first { it.eventId == "401873062" }
        assertEquals("LV", game.homeAbbr)
        assertEquals("SEA", game.awayAbbr)
        assertEquals(13, game.homeScore)
        assertEquals(21, game.awayScore)
        assertEquals(4, game.home.linescores.size)
    }

    @Test
    fun `parses soccer goal scorers by side`() {
        val events = EspnFixtures.events(EspnFixtures.CAF_QUAL_20261116, EspnFixtures.CAF_QUAL)
        val game = events.first { it.eventId == "401927170" }
        assertEquals("NAM", game.homeAbbr)
        assertEquals("CMR", game.awayAbbr)
        assertEquals(0, game.homeScore)
        assertEquals(3, game.awayScore)
        assertEquals("FT", game.clockText)
        assertTrue(game.home.linescores.isEmpty())
        assertEquals(listOf("M. Nagida", "R. Fosso", "S. Alioum Moubarak"), game.scorers.map { it.name })
        assertTrue(game.scorers.all { it.side == ScoreSide.AWAY })
        assertEquals("12'", game.scorers.first().clock)

        val penalty = events.first { it.eventId == "401927177" }.scorers.single()
        assertTrue(penalty.penalty)
        assertEquals(ScoreSide.AWAY, penalty.side)

        val liverpool = EspnFixtures.events(EspnFixtures.ENG1_20261108, EspnFixtures.ENG1).first { it.eventId == "401879243" }
        assertEquals(listOf(ScoreSide.AWAY, ScoreSide.AWAY, ScoreSide.AWAY, ScoreSide.HOME), liverpool.scorers.map { it.side })
    }

    @Test
    fun `tolerates missing and malformed fields`() = runTest {
        val json = """
            {"events":[
              {"id":"1","competitions":[{"competitors":[
                {"homeAway":"home","team":{"abbreviation":"AAA"}},
                {"homeAway":"away","team":{"displayName":"Bbb"}}]}]},
              {"id":"2"},
              {},
              {"id":"3","date":"not a date","status":{"type":{"state":"in"}},"competitions":[{"competitors":[
                {"homeAway":"home","score":"x","team":{"name":"C"},"linescores":[{}]},
                {"homeAway":"away","team":{"name":"D"}}],"details":[{"scoringPlay":true}]}]},
              {"id":"4","competitions":[{"competitors":[{"homeAway":"home","team":{}},{"homeAway":"away","team":{"name":"E"}}]}]}
            ],"unknown":{"x":1}}
        """.trimIndent()
        val result = provider(respond(200, json)).scoreboard(EspnLeagueMap.NBA, day)

        assertFalse(result.failed)
        assertEquals(listOf("1", "3"), result.events.map { it.eventId })
        val first = result.events[0]
        assertNull(first.startMs)
        assertEquals(ScoreState.UNKNOWN, first.state)
        assertNull(first.homeScore)
        assertNull(first.clockText)
        assertFalse(first.isFinal)
        val third = result.events[1]
        assertEquals(ScoreState.IN, third.state)
        assertNull(third.homeScore)
        assertTrue(third.home.linescores.isEmpty())
        assertTrue(third.scorers.isEmpty())

        assertEquals(ScoreboardResult(emptyList()), provider(respond(200, "{}")).scoreboard(EspnLeagueMap.NBA, day))
    }

    @Test
    fun `any failure is an empty failed result, never a throw`() = runTest {
        val failures = listOf(
            respond(500, "oops"),
            respond(429, ""),
            respond(404, "{}"),
            respond(200, "not json"),
            respond(200, "[]"),
            respond(200, """{"events":"nope"}"""),
            Interceptor { throw IOException("offline") },
            Interceptor { throw IllegalStateException("bug") }
        )
        for (failure in failures) {
            assertEquals(ScoreboardResult.FAILED, provider(failure).scoreboard(EspnLeagueMap.NBA, day))
        }
    }

    @Test
    fun `college feeds ask for every Division I game`() = runTest {
        val p = provider(respond(200, "{}"))
        p.scoreboard(EspnLeagueMap.COLLEGE_FOOTBALL, day)
        p.scoreboard(EspnLeagueMap.COLLEGE_BASKETBALL, day)
        p.scoreboard(EspnLeague("soccer", "all"), day)
        assertEquals(
            listOf(
                "https://example.invalid/apis/site/v2/sports/football/college-football/scoreboard?dates=20261115&limit=1000&groups=80",
                "https://example.invalid/apis/site/v2/sports/basketball/mens-college-basketball/scoreboard?dates=20261115&limit=1000&groups=50",
                "https://example.invalid/apis/site/v2/sports/soccer/all/scoreboard?dates=20261115&limit=1000"
            ),
            requested
        )
    }

    private fun game(id: String, name: String, state: String, completed: Boolean, home: String, away: String) = """
        {"id":"$id","date":"2026-11-15T18:00Z",
         "status":{"type":{"name":"$name","state":"$state","completed":$completed,"shortDetail":"x"}},
         "competitions":[{"competitors":[
           {"homeAway":"home","score":"$home","team":{"id":"1","displayName":"Home Team"},"linescores":[{"value":1.0}]},
           {"homeAway":"away","score":"$away","team":{"id":"2","displayName":"Away Team"}}]}]}
    """

    @Test
    fun `postponed, cancelled, suspended and delayed games are OFF with no score, and pre-game shows none`() = runTest {
        val body = """{"events":[
            ${game("pp", "STATUS_POSTPONED", "post", false, "0", "0")},
            ${game("cx", "STATUS_CANCELED", "post", true, "0", "0")},
            ${game("su", "STATUS_SUSPENDED", "in", false, "2", "1")},
            ${game("dl", "STATUS_DELAYED", "in", false, "3", "3")},
            ${game("pre", "STATUS_SCHEDULED", "pre", false, "0", "0")},
            ${game("live", "STATUS_IN_PROGRESS", "in", false, "2", "1")},
            ${game("unk", "STATUS_SOMETHING", "odd", false, "4", "4")}
        ]}"""
        val events = provider(respond(200, body)).scoreboard(EspnLeagueMap.NBA, day).events.associateBy { it.eventId }
        for (id in listOf("pp", "cx", "su", "dl")) {
            val game = events.getValue(id)
            assertEquals(id, ScoreState.OFF, game.state)
            assertNull(id, game.homeScore)
            assertNull(id, game.awayScore)
            assertFalse(id, game.isFinal)
            assertTrue(id, game.home.linescores.isEmpty())
        }
        // Only a suspended or delayed game that is not over may resume.
        assertEquals(listOf("su", "dl"), listOf("pp", "cx", "su", "dl").filter { events.getValue(it).mayResume })
        events.getValue("pre").let {
            assertEquals(ScoreState.PRE, it.state)
            assertNull(it.homeScore)
            assertNull(it.awayScore)
        }
        events.getValue("unk").let {
            assertEquals(ScoreState.UNKNOWN, it.state)
            assertNull(it.homeScore)
        }
        events.getValue("live").let {
            assertEquals(ScoreState.IN, it.state)
            assertEquals(2, it.homeScore)
            assertEquals(listOf(1), it.home.linescores)
        }
    }
}
