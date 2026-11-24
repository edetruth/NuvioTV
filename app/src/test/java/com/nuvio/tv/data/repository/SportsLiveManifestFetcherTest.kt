package com.nuvio.tv.data.repository

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #60 phase 2: the link check's redirects. */
class SportsLiveManifestFetcherTest {

    private val from = "https://cdn.invalid/live/index.m3u8".toHttpUrl()

    @Test
    fun `a redirect is never followed from https down to http`() {
        assertNull(SportsLiveManifestFetcher.nextHop(from, "http://cdn.invalid/live/index.m3u8"))
        assertNull(SportsLiveManifestFetcher.nextHop(from, "http://other.invalid/x.m3u8"))
        // Up from http to https is fine.
        assertTrue(SportsLiveManifestFetcher.nextHop("http://cdn.invalid/a.m3u8".toHttpUrl(), "https://cdn.invalid/a.m3u8") != null)
        // No location: not followed.
        assertNull(SportsLiveManifestFetcher.nextHop(from, null))
    }

    @Test
    fun `the stream's headers go on to the same host only`() {
        val same = SportsLiveManifestFetcher.nextHop(from, "/live/other.m3u8")!!
        assertEquals("https://cdn.invalid/live/other.m3u8", same.url.toString())
        assertTrue(same.keepHeaders)
        val elsewhere = SportsLiveManifestFetcher.nextHop(from, "https://edge.invalid/live/index.m3u8")!!
        assertFalse(elsewhere.keepHeaders)
    }

    @Test
    fun `a manifest read after a redirect says where it was finally read (#279)`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/edge/live/master.m3u8"))
            server.enqueue(MockResponse().setBody("#EXTM3U\n"))
            val answer = SportsLiveManifestFetcher().fetch(server.url("/master.m3u8").toString(), emptyMap())!!
            assertEquals(200, answer.code)
            assertEquals(server.url("/edge/live/master.m3u8").toString(), answer.finalUrl)
        }
    }

    @Test
    fun `a manifest read straight away says its own link (#279)`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("#EXTM3U\n"))
            val url = server.url("/master.m3u8").toString()
            assertEquals(url, SportsLiveManifestFetcher().fetch(url, emptyMap())!!.finalUrl)
        }
    }
}
