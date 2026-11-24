package com.nuvio.tv.core.sportslive

import org.junit.Assert.assertEquals
import org.junit.Test

/** #279: a stream's request headers go on to a follow-up request (an HLS variant) only on the addon link's own host. */
class SportsLiveStreamHeadersTest {

    private val headers = mapOf("Authorization" to "Bearer t", "Referer" to "https://ref.invalid/")
    private val original = "https://cdn.invalid/live/master.m3u8"

    @Test
    fun `a variant on the link's own host gets the headers`() {
        assertEquals(headers, SportsLiveStreamHeaders.forFollowUp(original, "https://cdn.invalid/live/720/index.m3u8", headers))
        assertEquals(headers, SportsLiveStreamHeaders.forFollowUp(original, "https://CDN.invalid/other.m3u8", headers))
    }

    private val referer = mapOf("Referer" to "https://ref.invalid/")

    @Test
    fun `a variant on another host gets the headers but no login ones (#463)`() {
        assertEquals(referer, SportsLiveStreamHeaders.forFollowUp(original, "https://edge.invalid/live/720/index.m3u8", headers))
        assertEquals(referer, SportsLiveStreamHeaders.forFollowUp(original, "https://cdn.invalid.edge.invalid/x.m3u8", headers))
    }

    @Test
    fun `a variant on another port of the link's host gets no login headers (P4)`() {
        assertEquals(referer, SportsLiveStreamHeaders.forFollowUp(original, "https://cdn.invalid:9000/live/720/index.m3u8", headers))
    }

    @Test
    fun `a variant down from https to http gets neither login headers nor Referer, even on the same host`() {
        assertEquals(emptyMap<String, String>(), SportsLiveStreamHeaders.forFollowUp(original, "http://cdn.invalid/live/720/index.m3u8", headers))
        // Up from http to https on the same host keeps them.
        assertEquals(headers, SportsLiveStreamHeaders.forFollowUp("http://cdn.invalid/a.m3u8", "https://cdn.invalid/b.m3u8", headers))
    }

    @Test
    fun `an unreadable or non-web link gets none`() {
        assertEquals(emptyMap<String, String>(), SportsLiveStreamHeaders.forFollowUp(original, "not a link", headers))
        assertEquals(emptyMap<String, String>(), SportsLiveStreamHeaders.forFollowUp("::", "https://cdn.invalid/a.m3u8", headers))
        assertEquals(emptyMap<String, String>(), SportsLiveStreamHeaders.forFollowUp(original, "ftp://cdn.invalid/a.m3u8", headers))
    }
}
