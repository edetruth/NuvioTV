package com.nuvio.tv.ui.screens.player

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #434 and #435: where a stream's login headers may go, and what the unchecked TLS retry sends. */
class PlaybackStreamHeadersTest {

    private fun request(url: String) = Request.Builder().url(url)
        .header("Authorization", "Bearer t")
        .header("Proxy-Authorization", "Basic p")
        .header("Cookie", "s=1")
        .header("X-Auth-Token", "a")
        .header("X-Api-Key", "k")
        .header("Referer", "https://ref.invalid/")
        .header("Origin", "https://ref.invalid")
        .header("User-Agent", "AddonAgent")
        .header("X-Custom", "v")
        .header("Range", "bytes=0-")
        .build()

    private val link = "https://cdn.invalid/live/master.m3u8"

    @Test
    fun `login and cookie headers are credentials, other headers are not`() {
        listOf("Authorization", "proxy-authorization", "Cookie", "Cookie2", "X-Auth-Token", "X-Access-Token", "Api-Key", "X-Api-Key", "X-Session-Id")
            .forEach { assertTrue(it, PlaybackStreamHeaders.isCredential(it)) }
        listOf("Referer", "Origin", "User-Agent", "X-Custom", "Range", "Accept-Language", "X-Requested-With")
            .forEach { assertFalse(it, PlaybackStreamHeaders.isCredential(it)) }
    }

    @Test
    fun `the link's own host gets every header`() {
        val scope = PlaybackStreamHeaders.HostScope()
        val first = scope.apply(request(link))
        assertEquals("Bearer t", first.header("Authorization"))
        val later = scope.apply(request("https://CDN.invalid/live/seg1.ts"))
        assertEquals("s=1", later.header("Cookie"))
    }

    @Test
    fun `another host gets no login or cookie headers but keeps the rest (#434)`() {
        val scope = PlaybackStreamHeaders.HostScope()
        scope.apply(request(link))
        val other = scope.apply(request("https://edge.invalid/live/seg1.ts"))
        listOf("Authorization", "Proxy-Authorization", "Cookie", "X-Auth-Token", "X-Api-Key").forEach { assertNull(it, other.header(it)) }
        assertEquals("https://ref.invalid/", other.header("Referer"))
        assertEquals("https://ref.invalid", other.header("Origin"))
        assertEquals("AddonAgent", other.header("User-Agent"))
        assertEquals("v", other.header("X-Custom"))
        assertEquals("bytes=0-", other.header("Range"))
        // Back on the link's host they go again.
        assertEquals("Bearer t", scope.apply(request("https://cdn.invalid/live/seg2.ts")).header("Authorization"))
    }

    @Test
    fun `the link's host down from https to http gets no login headers`() {
        val scope = PlaybackStreamHeaders.HostScope()
        scope.apply(request(link))
        assertNull(scope.apply(request("http://cdn.invalid/live/seg1.ts")).header("Authorization"))
    }

    @Test
    fun `the unchecked TLS retry sends no stream header at all (#435)`() {
        val retry = PlaybackStreamHeaders.forUncheckedRetry(request(link).newBuilder().header("Icy-MetaData", "1").build())
        assertEquals(link, retry.url.toString())
        assertEquals("bytes=0-", retry.header("Range"))
        assertEquals("1", retry.header("Icy-MetaData"))
        assertEquals(PlayerMediaSourceFactory.DEFAULT_USER_AGENT, retry.header("User-Agent"))
        assertEquals(setOf("range", "icy-metadata", "user-agent"), retry.headers.names().map { it.lowercase() }.toSet())
    }

    @Test
    fun `the playback client's TLS fallback retries through the header-free request`() {
        val source = java.io.File("src/main/java/com/nuvio/tv/ui/screens/player/PlayerPlaybackNetworking.kt").readText()
        assertTrue(source.contains("trustAllPlaybackHttpClient.newCall(PlaybackStreamHeaders.forUncheckedRetry(request))"))
    }
}
