package com.nuvio.tv.data.repository

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Review of #279 (P9): the pre-start probe over real HTTP redirects. */
class SportsLiveLinkProberRedirectTest {

    private val link = MockWebServer().apply { start() }
    private val other = MockWebServer().apply { start() }

    @After
    fun close() {
        link.shutdown()
        other.shutdown()
    }

    private val headers = mapOf("Authorization" to "Bearer t", "Referer" to "https://ref.invalid/", "X-Custom" to "v")
    private val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv/index.m3u8\n"
    private val media = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg1.ts\n"

    @Test
    fun `a master redirected to another origin has its variant read there without the stream's login headers (P9, #463)`() = runBlocking {
        link.enqueue(MockResponse().setResponseCode(302).addHeader("Location", other.url("/live/master.m3u8").toString()))
        other.enqueue(MockResponse().setBody(master))
        other.enqueue(MockResponse().setBody(media))
        SportsLiveLinkProber().probe(link.url("/master.m3u8").toString(), headers)
        assertEquals("v", link.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("X-Custom"))
        val hop = other.takeRequest(5, TimeUnit.SECONDS)!!
        val variant = other.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/live/v/index.m3u8", variant.path)
        listOf(hop, variant).forEach { request ->
            // #463: the login header stays behind; Referer and the rest go along, as the player sends them.
            assertNull(request.getHeader("Authorization"))
            assertEquals("v", request.getHeader("X-Custom"))
            assertEquals("https://ref.invalid/", request.getHeader("Referer"))
        }
    }

    @Test
    fun `a master on the link's own origin has its variant read with the stream's headers`() = runBlocking {
        link.enqueue(MockResponse().setBody(master))
        link.enqueue(MockResponse().setBody(media))
        SportsLiveLinkProber().probe(link.url("/live/master.m3u8").toString(), headers)
        link.takeRequest(5, TimeUnit.SECONDS)
        val variant = link.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/live/v/index.m3u8", variant.path)
        assertEquals("Bearer t", variant.getHeader("Authorization"))
    }
}
