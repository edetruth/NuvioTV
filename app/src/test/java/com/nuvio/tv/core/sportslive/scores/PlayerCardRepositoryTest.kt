package com.nuvio.tv.core.sportslive.scores

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerCardRepositoryTest {

    private class FakeSource(var athlete: String?, var log: String?) : PlayerCardSource {
        val requests = mutableListOf<String>()
        override suspend fun athlete(league: EspnLeague, athleteId: String): String? {
            requests += "${league.path}/athletes/$athleteId"
            return athlete
        }

        override suspend fun gameLog(league: EspnLeague, athleteId: String): String? {
            requests += "${league.path}/athletes/$athleteId/gamelog"
            return log
        }
    }

    private var nowMs = 1_000_000L
    private val athleteJson = EspnFixtures.json("espn_athlete_nba_3059318.json")
    private val logJson = EspnFixtures.json("espn_gamelog_nba_3059318.json")

    private fun repository(source: PlayerCardSource) = PlayerCardRepository(source, EspnFixtures.moshi, now = { nowMs })

    @Test
    fun `an open requests the athlete and the game log once, then reuses the card for 10 minutes`() = runTest {
        val source = FakeSource(athleteJson, logJson)
        val repository = repository(source)
        val card = repository.load(EspnLeagueMap.NBA, "3059318")!!
        assertEquals("Joel Embiid", card.bio.name)
        assertEquals(5, card.lastGames.size)
        assertEquals(listOf("basketball/nba/athletes/3059318", "basketball/nba/athletes/3059318/gamelog"), source.requests.sorted())

        nowMs += PlayerCardRepository.TTL_MS - 1
        assertEquals(card, repository.load(EspnLeagueMap.NBA, "3059318"))
        assertEquals(2, source.requests.size)

        // Ten minutes on: asked again.
        nowMs += 1
        repository.load(EspnLeagueMap.NBA, "3059318")
        assertEquals(4, source.requests.size)
    }

    @Test
    fun `cards are kept per league and athlete`() = runTest {
        val source = FakeSource(athleteJson, logJson)
        val repository = repository(source)
        repository.load(EspnLeagueMap.NBA, "3059318")
        repository.load(EspnLeagueMap.NBA, "1")
        assertEquals(4, source.requests.size)
    }

    @Test
    fun `a failed or unreadable bio is no card and is not kept`() = runTest {
        val source = FakeSource(null, logJson)
        val repository = repository(source)
        assertNull(repository.load(EspnLeagueMap.NBA, "3059318"))
        source.athlete = "<html>"
        assertNull(repository.load(EspnLeagueMap.NBA, "3059318"))
        source.athlete = athleteJson
        assertEquals("Joel Embiid", repository.load(EspnLeagueMap.NBA, "3059318")!!.bio.name)
        assertEquals(6, source.requests.size)
    }

    @Test
    fun `a failed game log leaves the card without last games`() = runTest {
        val card = repository(FakeSource(athleteJson, null)).load(EspnLeagueMap.NBA, "3059318")!!
        assertEquals(4, card.season.size)
        assertTrue(card.lastGames.isEmpty())
    }

    @Test
    fun `ESPN - the athlete and its game log on the athlete API, uncached, and an HTTP error is null`() = runTest {
        val requested = mutableListOf<Pair<String, String?>>()
        var code = 200
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            requested += chain.request().url.toString() to chain.request().header("Cache-Control")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fake")
                .body("{}".toResponseBody("application/json".toMediaType())).build()
        }).build()
        val espn = EspnScoreProvider(client, EspnFixtures.moshi, "https://example.invalid/site/", athleteBaseUrl = "https://example.invalid/common/")
        assertEquals("{}", espn.athlete(EspnLeagueMap.NHL, "3895074"))
        assertEquals("{}", espn.gameLog(EspnLeagueMap.NHL, "3895074"))
        assertEquals(
            listOf("https://example.invalid/common/hockey/nhl/athletes/3895074", "https://example.invalid/common/hockey/nhl/athletes/3895074/gamelog"),
            requested.map { it.first }
        )
        assertTrue(requested.all { it.second?.contains("no-store") == true })
        code = 404
        assertNull(espn.athlete(EspnLeagueMap.NHL, "1"))
        assertNull(espn.gameLog(EspnLeagueMap.NHL, "1"))
        assertEquals("https://site.web.api.espn.com/apis/common/v3/sports/", EspnScoreProvider.ATHLETE_BASE_URL)
    }
}
