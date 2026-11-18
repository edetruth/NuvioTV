package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.ProxyHeaders
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #60 phase 2: the ended-game link check and its limits. */
@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveLinkCheckTest {

    private var now = 0L
    private val fetched = mutableListOf<String>()
    private var answer: (String) -> ManifestResponse? = { ManifestResponse(404, null) }
    private val cooldown = SportsLiveSourceCooldown(nowMs = { now })
    private val trust = SportsLiveSourceTrust()
    private val checker = SportsLiveLinkChecker(
        fetch = { url, _ -> fetched += url; answer(url) },
        cooldown = cooldown,
        trust = trust,
        nowMs = { now }
    )

    private fun request(key: String, base: String = "https://addon.invalid", source: String = "Addon A") =
        LinkCheckRequest(key = key, url = "https://cdn.invalid/$key.m3u8", headers = emptyMap(), sourceBaseUrls = listOf(base), source = source)

    // Classification

    @Test
    fun `403, 404, an empty manifest or an ended playlist are dead, a running playlist is alive`() {
        fun classify(code: Int, body: String?) = SportsLiveManifestVerdict.classify(ManifestResponse(code, body))
        assertEquals(LinkCheckResult.DEAD, classify(404, null))
        assertEquals(LinkCheckResult.DEAD, classify(403, "forbidden"))
        assertEquals(LinkCheckResult.DEAD, classify(200, ""))
        assertEquals(LinkCheckResult.DEAD, classify(200, "  \n"))
        assertEquals(LinkCheckResult.DEAD, classify(200, "#EXTM3U\n#EXTINF:6,\nseg1.ts\n#EXT-X-ENDLIST\n"))
        assertEquals(LinkCheckResult.ALIVE, classify(200, "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:120\n#EXTINF:6,\nseg120.ts\n"))
        // No verdict: server errors, rate limits, other answers, no answer at all.
        assertEquals(LinkCheckResult.UNKNOWN, classify(500, null))
        assertEquals(LinkCheckResult.UNKNOWN, classify(429, null))
        assertEquals(LinkCheckResult.UNKNOWN, classify(200, "<html>"))
        assertEquals(LinkCheckResult.UNKNOWN, SportsLiveManifestVerdict.classify(null))
    }

    @Test
    fun `only an already-resolved plain HLS link is checked, with its request headers`() {
        val item = game("g")
        assertNull("nothing resolved: never resolve just to test", SportsLiveManifestVerdict.requestFor(item, null))
        assertNull(SportsLiveManifestVerdict.requestFor(item, stream("https://cdn.invalid/file.mp4")))
        assertNull(SportsLiveManifestVerdict.requestFor(item, stream("magnet:?xt=urn:btih:abc")))
        val headers = mapOf("Referer" to "https://site.invalid/")
        val request = SportsLiveManifestVerdict.requestFor(item, stream("https://cdn.invalid/live/index.m3u8?token=1", headers))!!
        assertEquals(item.streamKey, request.key)
        assertEquals(headers, request.headers)
        assertEquals(listOf(item.addonBaseUrl), request.sourceBaseUrls)
    }

    // Rate limits

    @Test
    fun `each card is checked once, at most four a minute`() = runTest {
        val started = checker.offer((1..6).map { request("k$it") }, this)
        runCurrent()
        assertEquals(listOf("k1", "k2", "k3", "k4"), started)
        assertEquals(4, fetched.size)
        // The same minute: nothing more, and a card checked once is never checked again.
        assertEquals(emptyList<String>(), checker.offer(listOf(request("k1"), request("k5")), this))
        now += 60_000L
        assertEquals(listOf("k5", "k6"), checker.offer((1..6).map { request("k$it") }, this))
        runCurrent()
        assertEquals(6, fetched.size)
    }

    @Test
    fun `a card whose source cools down waits, and is checked once the cool-down ends`() = runTest {
        cooldown.note("https://addon.invalid", "Addon A", minutes = 2)
        assertEquals(emptyList<String>(), checker.offer(listOf(request("k1")), this))
        runCurrent()
        assertTrue(fetched.isEmpty())
        // Other sources are not held back.
        assertEquals(listOf("k2"), checker.offer(listOf(request("k2", base = "https://other.invalid")), this))
        now += 2 * 60_000L + 1
        assertEquals(listOf("k1"), checker.offer(listOf(request("k1")), this))
    }

    // Verdicts

    @Test
    fun `only a dead link is reported, and each source's verdicts are kept`() = runTest {
        answer = { url ->
            when {
                "dead" in url -> ManifestResponse(404, null)
                "alive" in url -> ManifestResponse(200, "#EXTM3U\n#EXTINF:6,\na.ts\n")
                else -> null
            }
        }
        now = 1_000L
        checker.offer(listOf(request("dead"), request("alive", source = "Addon B"), request("silent", source = "Addon B")), this)
        runCurrent()
        assertEquals(mapOf("dead" to 1_000L), checker.dead.value)
        assertEquals(1.0, trust.deadShare("Addon A")!!, 0.0)
        // An unclear answer counts for nothing.
        assertEquals(0.0, trust.deadShare("Addon B")!!, 0.0)
        assertNull(trust.deadShare("Addon C"))
    }

    @Test
    fun `a fetch that throws is no verdict`() = runTest {
        val throwing = SportsLiveLinkChecker(fetch = { _, _ -> error("boom") }, cooldown = cooldown, trust = trust, nowMs = { now })
        throwing.offer(listOf(request("k")), this)
        runCurrent()
        assertTrue(throwing.dead.value.isEmpty())
    }

    private fun game(id: String) = SportsLiveItem(
        meta = MetaPreview(
            id = id, type = ContentType.UNKNOWN, rawType = "tv", name = id, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "addon", addonName = "Addon A", addonBaseUrl = "https://addon.invalid", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false, startMs = null, sport = "Soccer"
    )

    private fun stream(url: String, headers: Map<String, String>? = null) = Stream(
        name = "s", title = null, description = null, url = url, ytId = null, infoHash = null, fileIdx = null,
        externalUrl = null,
        behaviorHints = headers?.let {
            StreamBehaviorHints(notWebReady = null, bingeGroup = null, countryWhitelist = null, proxyHeaders = ProxyHeaders(it, null))
        },
        addonName = "Addon A", addonLogo = null
    )
}
