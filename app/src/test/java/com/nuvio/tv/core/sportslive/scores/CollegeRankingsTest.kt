package com.nuvio.tv.core.sportslive.scores

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/** live.20 BUG18: every ranked college team's AP rank for standings, one request per league per day. */
class CollegeRankingsTest {
    // As ESPN's rankings response, trimmed: the AP poll second, so it is picked by type, not by place.
    private val json = """
        {"rankings":[
          {"name":"Coaches Poll","type":"usa","ranks":[{"current":1,"team":{"id":"150"}}]},
          {"name":"AP Top 25","type":"ap","ranks":[
            {"current":1,"team":{"id":"57"}},
            {"current":3,"team":{"id":"248"}},
            {"current":26,"team":{"id":"2"}},
            {"current":4,"team":{}}
          ]}
        ]}
    """.trimIndent()

    private val calls = mutableListOf<String>()
    private var clock = ZonedDateTime.of(2026, 11, 20, 12, 0, 0, 0, SportsResults.EASTERN).toInstant().toEpochMilli()
    private val repo = CollegeRankingsRepository(
        source = object : RankingsSource {
            override suspend fun rankings(league: EspnLeague): String? {
                calls += league.path
                return json
            }
        },
        moshi = EspnFixtures.moshi,
        now = { clock }
    )

    @Test
    fun `the AP poll's ranks by team id, within the top 25`() = runTest {
        assertEquals(mapOf("57" to 1, "248" to 3), repo.ranks(EspnLeagueMap.COLLEGE_BASKETBALL))
    }

    @Test
    fun `one request per league per ESPN day`() = runTest {
        repo.ranks(EspnLeagueMap.COLLEGE_BASKETBALL)
        repo.ranks(EspnLeagueMap.COLLEGE_BASKETBALL)
        repo.ranks(EspnLeagueMap.COLLEGE_FOOTBALL)
        assertEquals(listOf("basketball/mens-college-basketball", "football/college-football"), calls)
        clock += 24 * 60 * 60_000L
        repo.ranks(EspnLeagueMap.COLLEGE_BASKETBALL)
        assertEquals(3, calls.size)
    }

    @Test
    fun `a pro league has no poll and asks for nothing`() = runTest {
        assertTrue(repo.ranks(EspnLeagueMap.NBA).isEmpty())
        assertTrue(calls.isEmpty())
        assertTrue(CollegeRankingsRepository.apRanks(EspnRankingsDto()).isEmpty())
    }
}
