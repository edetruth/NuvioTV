package com.nuvio.tv.core.sportslive.scores

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GameSummaryRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private class FakeSource(var body: String?) : GameSummarySource {
        val requests = mutableListOf<String>()
        override suspend fun summary(league: EspnLeague, eventId: String): String? {
            requests += "${league.path}/$eventId"
            return body
        }
    }

    private var nowMs = 1_000_000L

    private fun repository(source: GameSummarySource, disk: GameSummaryDiskCache? = GameSummaryDiskCache(folder.root)) =
        GameSummaryRepository(source, disk, EspnFixtures.moshi, now = { nowMs })

    private val finalJson = EspnFixtures.json(GameSummaryFixtures.NBA)

    // The same game while live: state "in", not completed.
    private val liveJson = finalJson
        .replace("\"state\": \"post\"", "\"state\": \"in\"")
        .replace("\"completed\": true", "\"completed\": false")

    @Test
    fun `a final is requested once, then served from memory and, in a new session, from disk`() = runTest {
        val source = FakeSource(finalJson)
        val first = repository(source)
        val loaded = first.load(EspnLeagueMap.NBA, "401909994") as GameSummaryLoad.Loaded
        assertTrue(loaded.final)
        assertEquals(115, loaded.summary.away.score)
        nowMs += 24 * 60 * 60_000L
        first.load(EspnLeagueMap.NBA, "401909994")
        assertEquals(listOf("basketball/nba/401909994"), source.requests)

        // A new session (new repository, same cache folder) reads the trimmed disk copy.
        val secondSource = FakeSource(null)
        val reopened = repository(secondSource).load(EspnLeagueMap.NBA, "401909994") as GameSummaryLoad.Loaded
        assertEquals(115, reopened.summary.away.score)
        assertTrue(secondSource.requests.isEmpty())
        // The copy on disk is trimmed to what the page reads.
        assertTrue(folder.root.listFiles()!!.single().length() < finalJson.length)
    }

    @Test
    fun `a live game is reused for at most 30 s and never written to disk`() = runTest {
        val source = FakeSource(liveJson)
        val repo = repository(source)
        val loaded = repo.load(EspnLeagueMap.NBA, "401909994") as GameSummaryLoad.Loaded
        assertEquals(false, loaded.final)
        assertTrue(loaded.summary.isLive)
        nowMs += GameSummaryRepository.LIVE_TTL_MS - 1
        repo.load(EspnLeagueMap.NBA, "401909994")
        assertEquals(1, source.requests.size)
        nowMs += 1
        repo.load(EspnLeagueMap.NBA, "401909994")
        assertEquals(2, source.requests.size)
        assertTrue(folder.root.listFiles().isNullOrEmpty())
    }

    @Test
    fun `a failure or an unreadable body is Failed, never a throw`() = runTest {
        assertEquals(GameSummaryLoad.Failed, repository(FakeSource(null)).load(EspnLeagueMap.NBA, "1"))
        assertEquals(GameSummaryLoad.Failed, repository(FakeSource("<html>")).load(EspnLeagueMap.NBA, "2"))
        assertEquals(GameSummaryLoad.Failed, repository(FakeSource("{}")).load(EspnLeagueMap.NBA, "3"))
    }

    @Test
    fun `the disk cache keeps at most its file count, dropping the oldest`() {
        val cache = GameSummaryDiskCache(folder.root, maxFiles = 2)
        cache.write("a", "1")
        folder.root.listFiles()!!.single().setLastModified(1_000)
        cache.write("b", "2")
        folder.root.listFiles()!!.first { it.name.startsWith("b") }.setLastModified(2_000)
        cache.write("c", "3")
        assertNull(cache.read("a"))
        assertEquals("2", cache.read("b"))
        assertEquals("3", cache.read("c"))
        // Unsafe characters never leave the folder.
        cache.write("../x/y", "4")
        assertEquals("4", cache.read("../x/y"))
        assertTrue(folder.root.listFiles()!!.all { it.parentFile == folder.root })
    }

    @Test
    fun `delayed values - each becomes current once it is the delay old`() {
        val values = DelayedValues<String>(30_000)
        values.push("a", 0)
        assertNull(values.current(29_999))
        assertEquals(30_000L, values.nextDueAt(29_999))
        assertEquals("a", values.current(30_000))
        values.push("b", 30_000)
        assertEquals("a", values.current(59_999))
        assertEquals(60_000L, values.nextDueAt(59_999))
        assertEquals("b", values.current(60_000))
        assertNull(values.nextDueAt(60_000))
        // No delay: current at once.
        val now = DelayedValues<String>(0)
        now.push("x", 5)
        assertEquals("x", now.current(5))
    }
}
