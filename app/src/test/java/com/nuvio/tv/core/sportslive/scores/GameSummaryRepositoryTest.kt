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

        override suspend fun unrecordedSummary(league: EspnLeague, eventId: String): String? {
            requests += "off ${league.path}/$eventId"
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
    fun `a final saved before highlight links were kept is fetched once more, then served from disk`() = runTest {
        // NY @ TOR as a pre-#61 version saved it: the clips without their links, under the unversioned name.
        val adapter = EspnFixtures.moshi.adapter(EspnSummaryDto::class.java)
        val withLinks = GameSummaryFixtures.dto(GameSummaryFixtures.NBA_VIDEOS)
        val legacy = withLinks.copy(videos = withLinks.videos!!.map { it.copy(links = null) })
        val league = EspnLeagueMap.NBA
        GameSummaryDiskCache(folder.root).write("${league.sport}_${league.league}_401910002", adapter.toJson(legacy))

        val source = FakeSource(EspnFixtures.json(GameSummaryFixtures.NBA_VIDEOS))
        val loaded = repository(source).load(league, "401910002") as GameSummaryLoad.Loaded
        assertEquals(listOf("basketball/nba/401910002"), source.requests)
        assertEquals(4, loaded.summary.highlights.size)
        assertTrue(GameSection.HIGHLIGHTS in loaded.summary.sections)

        // Saved again in the current format: the next session reads it, links and all, without a request.
        val nextSource = FakeSource(null)
        val reopened = repository(nextSource).load(league, "401910002") as GameSummaryLoad.Loaded
        assertTrue(nextSource.requests.isEmpty())
        assertEquals(loaded.summary.highlights, reopened.summary.highlights)
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
    fun `under Scores Off a final is read without feed health and never written to disk`() = runTest {
        val health = EspnFeedHealth { nowMs }
        val source = FakeSource(finalJson)
        val repo = GameSummaryRepository(source, GameSummaryDiskCache(folder.root), EspnFixtures.moshi, now = { nowMs }, health = health)
        val loaded = repo.load(EspnLeagueMap.NBA, "401909994", scoresOff = true) as GameSummaryLoad.Loaded
        assertTrue(loaded.final)
        assertEquals(listOf("off basketball/nba/401909994"), source.requests)
        assertTrue(folder.root.listFiles().isNullOrEmpty())
        assertTrue(health.feeds.value.isEmpty())

        // A format change under Off is not recorded either.
        source.body = "{}"
        assertEquals(GameSummaryLoad.Failed, repo.load(EspnLeagueMap.NBA, "2", scoresOff = true))
        assertTrue(health.feeds.value.isEmpty())

        // The normal read (a new session: nothing in memory) still records and keeps its final.
        source.body = finalJson
        GameSummaryRepository(source, GameSummaryDiskCache(folder.root), EspnFixtures.moshi, now = { nowMs }, health = health)
            .load(EspnLeagueMap.NBA, "401909994")
        assertTrue(EspnFeedKey.SUMMARY in health.feeds.value)
        assertEquals(1, folder.root.listFiles()!!.size)
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
    fun `delayed values - each becomes current once its own hold has passed`() {
        val values = DelayedValues<String>()
        values.push("a", 0, 30_000)
        assertNull(values.current(29_999))
        assertEquals(30_000L, values.nextDueAt(29_999))
        assertEquals("a", values.current(30_000))
        values.push("b", 30_000, 30_000)
        assertEquals("a", values.current(59_999))
        assertEquals(60_000L, values.nextDueAt(59_999))
        assertEquals("b", values.current(60_000))
        assertNull(values.nextDueAt(60_000))
        // No hold: current at once.
        values.push("x", 70_000, 0)
        assertEquals("x", values.current(70_000))
    }
}
