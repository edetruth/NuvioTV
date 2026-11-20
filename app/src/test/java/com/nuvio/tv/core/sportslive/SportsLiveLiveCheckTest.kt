package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.Stream
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
}
