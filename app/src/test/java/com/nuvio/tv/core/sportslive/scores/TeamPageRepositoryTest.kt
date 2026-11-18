package com.nuvio.tv.core.sportslive.scores

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TeamPageRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private class FakeSource(
        var team: String?,
        var schedule: String?,
        var fixtures: String? = null,
        var summary: String? = null
    ) : TeamPageSource, GameSummarySource {
        val requests = mutableListOf<String>()

        override suspend fun team(league: EspnLeague, teamId: String): String? {
            requests += "${league.path}/teams/$teamId"
            return team
        }

        override suspend fun teamSchedule(league: EspnLeague, teamId: String, fixtures: Boolean): String? {
            requests += "${league.path}/teams/$teamId/schedule" + if (fixtures) "?fixture=true" else ""
            return if (fixtures) this.fixtures else schedule
        }

        override suspend fun summary(league: EspnLeague, eventId: String): String? {
            requests += "${league.path}/summary/$eventId"
            return summary
        }
    }

    private var nowMs = TeamFixtures.NOW

    private fun repository(source: FakeSource) =
        TeamPageRepository(source, source, GameSummaryDiskCache(folder.root), EspnFixtures.moshi, now = { nowMs })

    private fun nbaSource() = FakeSource(
        team = EspnFixtures.json(TeamFixtures.NBA_TEAM),
        schedule = EspnFixtures.json(TeamFixtures.NBA_SCHEDULE),
        summary = EspnFixtures.json(TeamFixtures.NBA_NEXT_SUMMARY)
    )

    @Test
    fun `an open requests the team, its schedule and the next game's leaders, then reuses the page for 10 minutes`() = runTest {
        val source = nbaSource()
        val repo = repository(source)
        val loaded = repo.load(EspnLeagueMap.NBA, "20") as TeamPageLoad.Loaded
        assertFalse(loaded.stale)
        assertEquals(
            setOf("basketball/nba/teams/20", "basketball/nba/teams/20/schedule", "basketball/nba/summary/401910010"),
            source.requests.toSet()
        )
        assertEquals(3, source.requests.size)
        assertEquals("12-3", loaded.data.header.record)
        assertEquals(5, loaded.data.recent.size)
        assertEquals(4, loaded.data.upcoming.size)
        assertEquals("T. Maxey", loaded.data.leaders.first().player)

        nowMs += TeamPageRepository.TTL_MS - 1
        repo.load(EspnLeagueMap.NBA, "20")
        assertEquals(3, source.requests.size)

        nowMs += 1
        repo.load(EspnLeagueMap.NBA, "20")
        assertEquals(6, source.requests.size)
    }

    @Test
    fun `soccer asks for its fixtures too, and never for leaders`() = runTest {
        val source = FakeSource(
            team = EspnFixtures.json(TeamFixtures.ENG1_TEAM),
            schedule = EspnFixtures.json(TeamFixtures.ENG1_SCHEDULE),
            fixtures = EspnFixtures.json(TeamFixtures.ENG1_FIXTURES)
        )
        val eng1 = EspnLeague("soccer", "eng.1")
        val loaded = repository(source).load(eng1, "359") as TeamPageLoad.Loaded
        assertEquals(
            setOf("soccer/eng.1/teams/359", "soccer/eng.1/teams/359/schedule", "soccer/eng.1/teams/359/schedule?fixture=true"),
            source.requests.toSet()
        )
        assertEquals("401879232", loaded.data.upcoming.first().eventId)
        assertTrue(loaded.data.leaders.isEmpty())
    }

    @Test
    fun `a failed leaders request leaves the page without leaders`() = runTest {
        val source = nbaSource().apply { summary = null }
        val loaded = repository(source).load(EspnLeagueMap.NBA, "20") as TeamPageLoad.Loaded
        assertTrue(loaded.data.leaders.isEmpty())
        assertEquals(5, loaded.data.recent.size)
    }

    @Test
    fun `offline, the finals saved on disk show, marked stale, with nothing upcoming, and the next open tries again`() = runTest {
        repository(nbaSource()).load(EspnLeagueMap.NBA, "20")

        // A new session with ESPN unreachable.
        val offline = FakeSource(team = null, schedule = null)
        val repo = repository(offline)
        val stale = repo.load(EspnLeagueMap.NBA, "20") as TeamPageLoad.Loaded
        assertTrue(stale.stale)
        assertEquals("Philadelphia 76ers", stale.data.header.name)
        assertEquals(listOf("401909997", "401909985", "401909976", "401909961", "401909945"), stale.data.recent.map { it.eventId })
        assertTrue(stale.data.upcoming.isEmpty())
        // Not cached: the next open asks ESPN again.
        val before = offline.requests.size
        repo.load(EspnLeagueMap.NBA, "20")
        assertTrue(offline.requests.size > before)
        // The disk copy is trimmed to the finals kept.
        assertTrue(folder.root.listFiles()!!.single().length() < EspnFixtures.json(TeamFixtures.NBA_SCHEDULE).length)
    }

    @Test
    fun `nothing from ESPN and nothing saved is a failure`() = runTest {
        assertEquals(TeamPageLoad.Failed, repository(FakeSource(team = null, schedule = null)).load(EspnLeagueMap.NBA, "20"))
        assertEquals(TeamPageLoad.Failed, repository(FakeSource(team = "{}", schedule = "not json")).load(EspnLeagueMap.NBA, "20"))
    }
}
