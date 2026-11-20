package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #147: a start's probe race and the media playlist rule it judges by (#110). */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveLinkRaceTest {

    private val a1 = link("A", "a1")
    private val a2 = link("A", "a2")
    private val b1 = link("B", "b1")
    private val c1 = link("C", "c1")
    private val d1 = link("D", "d1")
    private val e1 = link("E", "e1")

    @Test
    fun `each addon races with its best link only`() {
        assertEquals(listOf(a1, b1, c1), SportsLiveLinkRace.entrants(listOf(a1, a2, b1, c1), SportsLiveLinkRace::probeable))
    }

    @Test
    fun `at most four addons race`() {
        assertEquals(listOf(a1, b1, c1, d1), SportsLiveLinkRace.entrants(listOf(a1, b1, c1, d1, e1), SportsLiveLinkRace::probeable))
    }

    @Test
    fun `no race when only one addon has links`() {
        assertTrue(SportsLiveLinkRace.entrants(listOf(a1, a2), SportsLiveLinkRace::probeable).isEmpty())
        // A check of a single game probes its one addon.
        assertEquals(listOf(a1), SportsLiveLinkRace.entrants(listOf(a1, a2), SportsLiveLinkRace::probeable, minAddons = 1))
    }

    @Test
    fun `links that are not plain http are never probed`() {
        val magnet = link("B", "m").copy(url = "magnet:?xt=urn:btih:abc")
        assertTrue(SportsLiveLinkRace.entrants(listOf(a1, magnet), SportsLiveLinkRace::probeable).isEmpty())
    }

    @Test
    fun `the first link that proves live wins and the others are cancelled`() = runTest {
        val cancelled = mutableListOf<String>()
        val (winner, results) = SportsLiveLinkRace.race(listOf(a1, b1, c1), timeoutMs = 3_000, nowMs = { testScheduler.currentTime }) { link ->
            try {
                when (link) {
                    a1 -> { delay(1_500); ProbeVerdict.OK }
                    b1 -> { delay(400); ProbeVerdict.OK }
                    else -> awaitCancellation()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled += link.name.orEmpty()
                throw e
            }
        }

        assertEquals(b1, winner)
        assertEquals(listOf("a1", "c1"), cancelled.sorted())
        assertEquals(listOf(b1), results.map { it.stream })
        assertEquals(400L, testScheduler.currentTime)
    }

    @Test
    fun `losers that answered before the winner are reported for the learning`() = runTest {
        val (winner, results) = SportsLiveLinkRace.race(listOf(a1, b1), timeoutMs = 3_000, nowMs = { testScheduler.currentTime }) { link ->
            if (link == a1) { delay(100); ProbeVerdict.DEAD } else { delay(800); ProbeVerdict.OK }
        }

        assertEquals(b1, winner)
        assertEquals(listOf(a1 to ProbeVerdict.DEAD, b1 to ProbeVerdict.OK), results.map { it.stream to it.verdict })
    }

    @Test
    fun `a probe that does not answer in time has no verdict`() = runTest {
        val (winner, results) = SportsLiveLinkRace.race(listOf(a1, b1), timeoutMs = 3_000, nowMs = { testScheduler.currentTime }) { link ->
            if (link == a1) awaitCancellation() else ProbeVerdict.DEAD
        }

        assertNull(winner)
        assertEquals(ProbeVerdict.UNKNOWN, results.single { it.stream == a1 }.verdict)
        assertEquals(3_000L, testScheduler.currentTime)
        // With no winner the start plays the best link not found dead.
        assertEquals(a1, SportsLiveLinkRace.afterNoWinner(listOf(a1, b1), results, a1))
    }

    @Test
    fun `with no winner a dead first link is passed over`() {
        val results = listOf(SportsLiveLinkRace.Result(a1, ProbeVerdict.DEAD, 10), SportsLiveLinkRace.Result(b1, ProbeVerdict.UNKNOWN, 3_000))
        assertEquals(b1, SportsLiveLinkRace.afterNoWinner(listOf(a1, b1), results, a1))
        val allDead = listOf(SportsLiveLinkRace.Result(a1, ProbeVerdict.DEAD, 10), SportsLiveLinkRace.Result(b1, ProbeVerdict.DEAD, 20))
        assertEquals(a1, SportsLiveLinkRace.afterNoWinner(listOf(a1, b1), allDead, a1))
    }

    @Test
    fun `only a media playlist with recent segments is live`() {
        val now = 1_763_500_000_000L
        val recent = java.time.Instant.ofEpochMilli(now - 20_000).toString()
        val old = java.time.Instant.ofEpochMilli(now - 30 * 60_000).toString()
        val media = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:100\n#EXTINF:6.0,\nseg100.ts\n"

        assertEquals(ProbeVerdict.OK, SportsLiveMediaPlaylist.verdict(media, now))
        assertEquals(ProbeVerdict.OK, SportsLiveMediaPlaylist.verdict(media.replace("#EXTINF", "#EXT-X-PROGRAM-DATE-TIME:$recent\n#EXTINF"), now))
        assertEquals(ProbeVerdict.DEAD, SportsLiveMediaPlaylist.verdict(media.replace("#EXTINF", "#EXT-X-PROGRAM-DATE-TIME:$old\n#EXTINF"), now))
        assertEquals(ProbeVerdict.DEAD, SportsLiveMediaPlaylist.verdict(media + "#EXT-X-ENDLIST\n", now))
        assertEquals(ProbeVerdict.DEAD, SportsLiveMediaPlaylist.verdict("#EXTM3U\n#EXT-X-TARGETDURATION:6\n", now))
        assertEquals(ProbeVerdict.DEAD, SportsLiveMediaPlaylist.verdict("", now))
        assertEquals(ProbeVerdict.UNKNOWN, SportsLiveMediaPlaylist.verdict("<html>blocked</html>", now))
    }

    @Test
    fun `a master playlist alone proves nothing and names its first variant`() {
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=3000000\nhigh/index.m3u8\n"

        assertEquals(ProbeVerdict.UNKNOWN, SportsLiveMediaPlaylist.verdict(master, 0L))
        assertTrue(SportsLiveMediaPlaylist.isMaster(master))
        assertEquals("low/index.m3u8", SportsLiveMediaPlaylist.firstVariant(master))
        assertTrue(SportsLiveMediaPlaylist.isHls("https://cdn.example.invalid/live/index.m3u8?token=1"))
    }

    private fun link(addon: String, id: String) = Stream(
        name = id, title = null, description = null, url = "https://cdn.example.invalid/$id.m3u8", ytId = null,
        infoHash = null, fileIdx = null, externalUrl = null, behaviorHints = null, addonName = addon, addonLogo = null
    )
}
