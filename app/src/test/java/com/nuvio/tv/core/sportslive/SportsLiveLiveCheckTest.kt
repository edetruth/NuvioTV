package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.ProxyHeaders
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveLiveCheckTest {

    private val now = 1_800_000_000_000L

    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720
        720/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=800000
        480/index.m3u8
    """.trimIndent()

    private fun media(sequence: Long, segments: Int, dated: Long? = null, ended: Boolean = false) = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-TARGETDURATION:6")
        appendLine("#EXT-X-MEDIA-SEQUENCE:$sequence")
        if (dated != null) appendLine("#EXT-X-PROGRAM-DATE-TIME:" + java.time.Instant.ofEpochMilli(dated))
        repeat(segments) { i ->
            appendLine("#EXTINF:6.0,")
            appendLine("seg${sequence + i}.ts")
        }
        if (ended) appendLine("#EXT-X-ENDLIST")
    }

    private fun stream(url: String, addon: String = "A") = Stream(
        name = addon, title = null, description = null, url = url, ytId = null, infoHash = null, fileIdx = null,
        externalUrl = null, behaviorHints = null, addonName = addon, addonLogo = null
    )

    /** A fetch serving [pages] by url, each read taking the next answer for that url (the last repeats). */
    private class FakeFetch(private val pages: Map<String, List<ManifestResponse?>>) {
        val reads = ArrayList<String>()
        private val served = HashMap<String, Int>()

        suspend fun fetch(url: String, headers: Map<String, String>): ManifestResponse? {
            reads += url
            val answers = pages[url] ?: return null
            val at = served.getOrDefault(url, 0)
            served[url] = at + 1
            return answers[minOf(at, answers.lastIndex)]
        }
    }

    private fun checker(fetch: FakeFetch) = SportsLiveLiveCheck(fetch::fetch, nowMs = { now }, wait = {})

    @Test
    fun `a master playlist alone never counts as live`() = runTest {
        val fetch = FakeFetch(
            mapOf(
                "https://h.invalid/master.m3u8" to listOf(ManifestResponse(200, master)),
                // The variant has nothing yet: the stream is not up.
                "https://h.invalid/720/index.m3u8" to listOf(ManifestResponse(200, media(0, 0)))
            )
        )
        assertEquals(LiveVerdict.NOT_LIVE, checker(fetch).check(stream("https://h.invalid/master.m3u8")))
        assertEquals(listOf("https://h.invalid/master.m3u8", "https://h.invalid/720/index.m3u8"), fetch.reads)
    }

    @Test
    fun `a master whose first variant has recent dated segments is live`() = runTest {
        val fetch = FakeFetch(
            mapOf(
                "https://h.invalid/master.m3u8" to listOf(ManifestResponse(200, master)),
                "https://h.invalid/720/index.m3u8" to listOf(ManifestResponse(200, media(10, 3, dated = now - 30_000L)))
            )
        )
        assertEquals(LiveVerdict.LIVE, checker(fetch).check(stream("https://h.invalid/master.m3u8")))
    }

    @Test
    fun `stale dated segments, an ended playlist or a 404 are not live`() = runTest {
        val fetch = FakeFetch(
            mapOf(
                "https://h.invalid/old.m3u8" to listOf(ManifestResponse(200, media(10, 3, dated = now - 10 * 60_000L))),
                "https://h.invalid/vod.m3u8" to listOf(ManifestResponse(200, media(0, 3, ended = true))),
                "https://h.invalid/gone.m3u8" to listOf(ManifestResponse(404, null))
            )
        )
        val check = checker(fetch)
        assertEquals(LiveVerdict.NOT_LIVE, check.check(stream("https://h.invalid/old.m3u8")))
        assertEquals(LiveVerdict.NOT_LIVE, check.check(stream("https://h.invalid/vod.m3u8")))
        assertEquals(LiveVerdict.NOT_LIVE, check.check(stream("https://h.invalid/gone.m3u8")))
    }

    @Test
    fun `an undated media playlist is live only when a second read moved on`() = runTest {
        val fetch = FakeFetch(
            mapOf(
                "https://h.invalid/moving.m3u8" to listOf(ManifestResponse(200, media(10, 3)), ManifestResponse(200, media(11, 3))),
                "https://h.invalid/frozen.m3u8" to listOf(ManifestResponse(200, media(10, 3)))
            )
        )
        val check = checker(fetch)
        assertEquals(LiveVerdict.LIVE, check.check(stream("https://h.invalid/moving.m3u8")))
        assertEquals(LiveVerdict.NOT_LIVE, check.check(stream("https://h.invalid/frozen.m3u8")))
    }

    @Test
    fun `no answer or something else than a playlist gives no verdict`() = runTest {
        val fetch = FakeFetch(mapOf("https://h.invalid/x.ts" to listOf(ManifestResponse(200, "binary"))))
        val check = checker(fetch)
        assertEquals(LiveVerdict.UNKNOWN, check.check(stream("https://h.invalid/x.ts")))
        assertEquals(LiveVerdict.UNKNOWN, check.check(stream("https://h.invalid/none.m3u8")))
        assertEquals(LiveVerdict.UNKNOWN, check.check(stream("magnet:?xt=urn:btih:abc")))
    }

    @Test
    fun `one probe per addon, the first live link wins`() = runTest {
        val fetch = FakeFetch(
            mapOf(
                "https://h.invalid/a1.m3u8" to listOf(ManifestResponse(200, media(0, 0))),
                "https://h.invalid/a2.m3u8" to listOf(ManifestResponse(200, media(5, 3, dated = now))),
                "https://h.invalid/b1.m3u8" to listOf(ManifestResponse(200, media(5, 3, dated = now)))
            )
        )
        val outcome = checker(fetch).firstLive(
            listOf(stream("https://h.invalid/a1.m3u8", "A"), stream("https://h.invalid/a2.m3u8", "A"), stream("https://h.invalid/b1.m3u8", "B"))
        )
        assertEquals("https://h.invalid/b1.m3u8", outcome.live?.url)
        assertTrue(outcome.checkable)
        // Addon A's second link is never read.
        assertFalse("https://h.invalid/a2.m3u8" in fetch.reads)
    }

    @Test
    fun `links that cannot be read leave the game unchecked`() = runTest {
        val outcome = checker(FakeFetch(emptyMap())).firstLive(listOf(stream("magnet:?xt=urn:btih:abc"), stream("https://h.invalid/x.m3u8", "B")))
        assertNull(outcome.live)
        assertFalse(outcome.checkable)
    }

    private val headers = mapOf("Authorization" to "Bearer t", "X-Custom" to "v")

    private fun stream(url: String, headers: Map<String, String>) = stream(url).copy(
        behaviorHints = StreamBehaviorHints(
            notWebReady = null, bingeGroup = null, countryWhitelist = null,
            proxyHeaders = ProxyHeaders(request = headers, response = null)
        )
    )

    /** Each read's url and the headers it carried. */
    private class RecordingFetch(private val pages: Map<String, ManifestResponse>) {
        val reads = ArrayList<Pair<String, Map<String, String>>>()

        suspend fun fetch(url: String, headers: Map<String, String>): ManifestResponse? {
            reads += url to headers
            return pages[url]
        }
    }

    @Test
    fun `a redirected master resolves its variant against where it was finally read (#279)`() = runTest {
        val fetch = RecordingFetch(
            mapOf(
                "https://h.invalid/master.m3u8" to ManifestResponse(200, master, finalUrl = "https://h.invalid/edge/live/master.m3u8"),
                "https://h.invalid/edge/live/720/index.m3u8" to ManifestResponse(200, media(10, 3, dated = now))
            )
        )
        val check = SportsLiveLiveCheck(fetch::fetch, nowMs = { now }, wait = {})
        assertEquals(LiveVerdict.LIVE, check.check(stream("https://h.invalid/master.m3u8", headers)))
        assertEquals(listOf("https://h.invalid/master.m3u8", "https://h.invalid/edge/live/720/index.m3u8"), fetch.reads.map { it.first })
        // Same host as the addon's link: the headers go with the variant read.
        assertEquals(headers, fetch.reads[1].second)
    }

    @Test
    fun `a variant on another host is read without the stream's login headers (#279, #463)`() = runTest {
        val crossMaster = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhttps://other.invalid/720/index.m3u8\n"
        val fetch = RecordingFetch(
            mapOf(
                "https://h.invalid/master.m3u8" to ManifestResponse(200, crossMaster),
                "https://other.invalid/720/index.m3u8" to ManifestResponse(200, media(10, 3))
            )
        )
        val check = SportsLiveLiveCheck(fetch::fetch, nowMs = { now }, wait = {})
        check.check(stream("https://h.invalid/master.m3u8", headers))
        assertEquals(headers, fetch.reads[0].second)
        // The variant read and the undated playlist's second read both go without the login header, with the rest.
        assertEquals(listOf("https://other.invalid/720/index.m3u8", "https://other.invalid/720/index.m3u8"), fetch.reads.drop(1).map { it.first })
        assertTrue(fetch.reads.drop(1).all { it.second == mapOf("X-Custom" to "v") })
    }

    @Test
    fun `a master redirected to another host sends no login headers to its variants there (#279, #463)`() = runTest {
        val fetch = RecordingFetch(
            mapOf(
                "https://h.invalid/master.m3u8" to ManifestResponse(200, master, finalUrl = "https://edge.invalid/live/master.m3u8"),
                "https://edge.invalid/live/720/index.m3u8" to ManifestResponse(200, media(10, 3, dated = now))
            )
        )
        val check = SportsLiveLiveCheck(fetch::fetch, nowMs = { now }, wait = {})
        assertEquals(LiveVerdict.LIVE, check.check(stream("https://h.invalid/master.m3u8", headers)))
        assertEquals("https://edge.invalid/live/720/index.m3u8", fetch.reads[1].first)
        assertEquals(mapOf("X-Custom" to "v"), fetch.reads[1].second)
    }
}
