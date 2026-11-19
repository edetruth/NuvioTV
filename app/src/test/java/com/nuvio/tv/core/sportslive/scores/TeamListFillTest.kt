package com.nuvio.tv.core.sportslive.scores

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class TeamListFillTest {

    private val nba = EspnLeagueMap.NBA
    private val celtics = DirectoryTeam("2", "Boston Celtics", "Celtics", "BOS", "Boston", "Celtics", "https://a.espncdn.com/i/teamlogos/nba/500/bos.png")
    private val knicks = DirectoryTeam("18", "New York Knicks", "Knicks", "NY", "New York", "Knicks", "https://a.espncdn.com/i/teamlogos/nba/500/ny.png")

    private var clock = 1_000L
    private val asked = mutableListOf<EspnLeague>()
    private var answer: List<DirectoryTeam>? = listOf(celtics, knicks)

    private fun fill(directory: TeamDirectory = TeamDirectory(), store: TeamDirectoryStore? = null) =
        TeamListFill(directory, { league -> asked += league; answer }, store, { clock }, io = kotlin.coroutines.EmptyCoroutineContext)

    @Test
    fun `a league is fetched once and then served from the directory for a week`() = runTest {
        val directory = TeamDirectory()
        val fill = fill(directory)
        assertEquals(TeamListResult.Fetched(listOf(celtics, knicks)), fill.ensure(nba))
        assertEquals(listOf(celtics, knicks), directory.teams(nba))

        clock += TeamDirectory.REFRESH_MS - 1
        assertEquals(TeamListResult.Cached(listOf(celtics, knicks)), fill.ensure(nba))
        assertEquals(listOf(nba), asked)

        clock += 1
        answer = listOf(celtics)
        assertEquals(TeamListResult.Fetched(listOf(celtics)), fill.ensure(nba))
        assertEquals(listOf(nba, nba), asked)
    }

    @Test
    fun `offline with a kept list serves the old list, with none it fails and asks again next time`() = runTest {
        val directory = TeamDirectory().apply { put(nba, listOf(celtics), 0L) }
        clock = TeamDirectory.REFRESH_MS + 5
        answer = null
        assertEquals(TeamListResult.Stale(listOf(celtics)), fill(directory).ensure(nba))

        val empty = fill()
        assertEquals(TeamListResult.Failed, empty.ensure(EspnLeagueMap.NHL))
        assertEquals(TeamListResult.Failed, empty.ensure(EspnLeagueMap.NHL))
        assertEquals(2, asked.count { it == EspnLeagueMap.NHL })

        answer = emptyList()
        assertEquals(TeamListResult.Failed, empty.ensure(EspnLeagueMap.MLB))
    }

    @Test
    fun `a fetched list is kept on disk and read back after a restart without asking`() = runTest {
        val dir = Files.createTempDirectory("teamlists").toFile()
        try {
            fill(store = TeamDirectoryStore(dir)).ensure(nba)
            assertEquals(1, asked.size)

            answer = null
            val restarted = TeamDirectory()
            val result = fill(restarted, TeamDirectoryStore(dir)).ensure(nba)
            assertEquals(TeamListResult.Cached(listOf(celtics, knicks)), result)
            assertEquals(1, asked.size)
            assertTrue(restarted.fetchedAt(nba) != null)
        } finally {
            dir.deleteRecursively()
        }
    }
}
