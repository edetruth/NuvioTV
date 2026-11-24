package com.nuvio.tv.ui.screens.player

import okhttp3.HttpUrl.Companion.toHttpUrl
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
        // P6: more credential shapes addons and media servers use.
        listOf("X-Key", "X-Access-Key", "X-Stream-Key", "X-Signature", "X-Sig", "X-JWT", "X-Pass", "X-User", "X-Username", "X-Ticket", "X-Login", "X-Account", "X-Credential")
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
    fun `a scope given the link never takes a redirect target as its link (P2)`() {
        val scope = PlaybackStreamHeaders.HostScope(link.toHttpUrl())
        // A warm session's first request goes straight to the CDN an earlier redirect led to.
        assertNull(scope.apply(request("https://edge.invalid/movie.mkv")).header("Authorization"))
        assertEquals("Bearer t", scope.apply(request(link)).header("Authorization"))
    }

    @Test
    fun `another host keeps its own cookie, only the stream's goes (P7)`() {
        val scope = PlaybackStreamHeaders.HostScope()
        val stream = request(link).headers
        scope.apply(request(link), stream)
        val jarCookie = request("https://edge.invalid/seg1.ts").newBuilder().header("Cookie", "edge=1").build()
        assertEquals("edge=1", scope.apply(jarCookie, stream).header("Cookie"))
        assertNull(scope.apply(request("https://edge.invalid/seg1.ts"), stream).header("Cookie"))
    }

    @Test
    fun `another port on the link's host gets no login headers (P4)`() {
        val scope = PlaybackStreamHeaders.HostScope()
        scope.apply(request("https://cdn.invalid:8443/live/master.m3u8"))
        assertNull(scope.apply(request("https://cdn.invalid:9000/live/seg1.ts")).header("Authorization"))
        assertEquals("Bearer t", scope.apply(request("https://cdn.invalid:8443/live/seg1.ts")).header("Authorization"))
    }

    @Test
    fun `the link's host down from https to http gets no login headers`() {
        val scope = PlaybackStreamHeaders.HostScope()
        scope.apply(request(link))
        val down = scope.apply(request("http://cdn.invalid/live/seg1.ts"))
        assertNull(down.header("Authorization"))
        // P6: Referer and Origin can carry a private link; not in clear text either.
        assertNull(down.header("Referer"))
        assertNull(down.header("Origin"))
        assertEquals("v", down.header("X-Custom"))
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

    @Test
    fun `the unchecked retry to a home server keeps the stream's headers (#435)`() {
        listOf(
            "https://10.0.0.5:8443/movie.mp4", "https://172.16.1.2/a.mkv", "https://172.31.255.1/a.mkv",
            "https://192.168.1.20:8920/a.mkv", "https://127.0.0.1:8096/a.mkv", "https://[::1]:8096/a.mkv",
            "https://[fd12:3456::1]/a.mkv", "https://[fe80::1]/a.mkv", "https://nas.local/a.mkv", "https://jellyfin:8920/a.mkv"
        ).forEach { url ->
            val retry = PlaybackStreamHeaders.forUncheckedRetry(request(url))
            assertEquals(url, "Bearer t", retry.header("Authorization"))
            assertEquals(url, "AddonAgent", retry.header("User-Agent"))
        }
    }

    @Test
    fun `public and look-alike hosts are not home servers`() {
        listOf(
            "8.8.8.8", "172.32.0.1", "172.15.0.1", "192.169.1.1", "11.0.0.1", "cdn.invalid", "local.example.invalid",
            "10.0.0.5.example.invalid", "2001:db8::1"
        ).forEach { assertFalse(it, PlaybackStreamHeaders.isLocalHost(it)) }
        listOf("10.1.2.3", "127.0.0.1", "::1", "fc00::1", "fe80::abcd", "nas.local", "NAS.LOCAL", "jellyfin")
            .forEach { assertTrue(it, PlaybackStreamHeaders.isLocalHost(it)) }
    }

    @Test
    fun `the opt-in parallel connections client gets the same host scope (#434)`() {
        val source = java.io.File("src/main/java/com/nuvio/tv/ui/screens/player/PlayerMediaSourceFactory.kt").readText()
        assertTrue(source.contains("OkHttpDataSource.Factory(PlaybackStreamHeaders.scoped(playbackHttpClient, url))"))
    }

    @Test
    fun `a scoped client drops login headers on another host`() {
        val base = okhttp3.OkHttpClient()
        val scoped = PlaybackStreamHeaders.scoped(base)
        assertEquals(base.networkInterceptors.size + 1, scoped.networkInterceptors.size)
        // Each call gets its own scope: two clients never share a link host.
        assertTrue(PlaybackStreamHeaders.scoped(base).networkInterceptors.last() !== scoped.networkInterceptors.last())
    }
}
