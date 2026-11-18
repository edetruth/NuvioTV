package com.nuvio.tv.data.repository

import okhttp3.HttpUrl.Companion.toHttpUrl
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
}
