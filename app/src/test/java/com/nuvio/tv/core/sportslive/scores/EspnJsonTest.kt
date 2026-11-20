package com.nuvio.tv.core.sportslive.scores

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ESPN's lists read entry by entry (live.20 TV report: `"odds": [null]` failed a whole score feed). Each parser reads a
 * real ESPN body with a null entry put first in every list exactly as it reads the body without them.
 */
class EspnJsonTest {

    private val guarded = EspnJson.guarded(EspnFixtures.moshi)

    /** [json] with a null entry first in every list of objects or strings. */
    private fun withNullEntries(json: String): String = NULL_FIRST.replace(json) { "[null, " + it.groupValues[1] }

    @Test
    fun `the fixture really gets null entries`() {
        val json = withNullEntries(EspnFixtures.json(EspnFixtures.GER1_20261120))
        assertTrue(json.contains("\"competitors\": [null, {"))
        assertTrue(json.contains("\"names\": [null, \""))
    }

    @Test
    fun `scoreboard - null competitors, broadcasts and notes drop only themselves`() {
        val adapter = guarded.adapter(EspnScoreboardDto::class.java)
        for ((file, league) in listOf(
            EspnFixtures.GER1_20261120 to EspnLeague("soccer", "ger.1"),
            EspnFixtures.SOCCER_ALL_20261120 to EspnLeagueMap.SOCCER_ALL,
            EspnFixtures.NBA_20261115 to EspnLeagueMap.NBA
        )) {
            val clean = EspnScoreProvider.toSnapshots(adapter.fromJson(EspnFixtures.json(file))!!, league)
            val nulled = EspnScoreProvider.toSnapshots(adapter.fromJson(withNullEntries(EspnFixtures.json(file)))!!, league)
            assertTrue(file, clean.isNotEmpty())
            assertEquals(file, clean, nulled)
        }
    }

    @Test
    fun `game summary - null entries drop only themselves`() = runTest {
        val clean = EspnFixtures.json(GameSummaryFixtures.NBA)
        fun load(body: String) = GameSummaryRepository(Source(body), disk = null, moshi = EspnFixtures.moshi, now = { 0L })
        val expected = load(clean).load(EspnLeagueMap.NBA, "401909994") as GameSummaryLoad.Loaded
        val actual = load(withNullEntries(clean)).load(EspnLeagueMap.NBA, "401909994") as GameSummaryLoad.Loaded
        assertEquals(expected, actual)
    }

    @Test
    fun `standings - null entries drop only themselves`() {
        val adapter = guarded.adapter(EspnStandingsNodeDto::class.java)
        val json = EspnFixtures.json("espn_standings_eng1_20261119.json")
        val clean = StandingsParser.parse(adapter.fromJson(json)!!)
        assertEquals(20, clean.size)
        assertEquals(clean, StandingsParser.parse(adapter.fromJson(withNullEntries(json))!!))
    }

    @Test
    fun `team list - null entries drop only themselves`() {
        val json = EspnFixtures.json("espn_teams_nba_20261118.json")
        val clean = TeamDirectory.parse(json, EspnFixtures.moshi)
        assertEquals(30, clean!!.size)
        assertEquals(clean, TeamDirectory.parse(withNullEntries(json), EspnFixtures.moshi))
    }

    @Test
    fun `an entry of another shape drops only itself and is counted`() {
        val adapter = guarded.adapter(EspnCompetitionDto::class.java)
        val json = """{"notes": [{"headline": {"nested": true}}, {"headline": "Final"}], "odds": [null, {"spread": -1.5}]}"""
        val (competition, skips) = EspnJson.counting { adapter.fromJson(json) }
        assertNotNull(competition)
        assertEquals(listOf("Final"), competition!!.notes!!.map { it.headline })
        assertEquals(listOf(-1.5), competition.odds!!.map { it?.spread })
        // The null odds entry is ESPN's habit, not counted; the unreadable note is.
        assertEquals(EspnJson.Skips(1, "$.notes[0]"), skips)
        // Outside a count nothing is kept.
        assertEquals(EspnJson.Skips.NONE, EspnJson.counting { adapter.fromJson("""{"notes": []}""") }.second)
    }

    private class Source(private val body: String) : GameSummarySource {
        override suspend fun summary(league: EspnLeague, eventId: String): String = body
        override suspend fun unrecordedSummary(league: EspnLeague, eventId: String): String = body
    }

    private companion object {
        val NULL_FIRST = Regex("""\[(\s*[{"])""")
    }
}
