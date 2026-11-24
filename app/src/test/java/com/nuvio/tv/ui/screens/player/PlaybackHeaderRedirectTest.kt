package com.nuvio.tv.ui.screens.player

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Review of #434 (P4, P9): the player's clients over real HTTP redirects. */
class PlaybackHeaderRedirectTest {

    private val link = MockWebServer().apply { start() }
    private val other = MockWebServer().apply { start() }

    @After
    fun close() {
        link.shutdown()
        other.shutdown()
    }

    private val streamHeaders = mapOf(
        "Authorization" to "Bearer t",
        "Cookie" to "s=1",
        "X-Api-Key" to "k",
        "Referer" to "https://ref.invalid/",
        "X-Custom" to "v"
    )

    /** [link] answers with a redirect to [other] (another port of the same host: another origin). */
    private fun redirectToOther(path: String = "/seg.ts") {
        link.enqueue(MockResponse().setResponseCode(302).addHeader("Location", other.url(path).toString()))
        other.enqueue(MockResponse().setBody("ok"))
    }

    private fun take(server: MockWebServer): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS)!!

    private fun assertNoLogin(request: RecordedRequest) {
        listOf("Authorization", "Cookie", "X-Api-Key").forEach { assertNull(it, request.getHeader(it)) }
    }

    @Test
    fun `a stream client sends no login headers to another port after a redirect (P4)`() {
        redirectToOther()
        val client = PlayerPlaybackNetworking.createHttpClient(streamHeaders)
        client.newCall(Request.Builder().url(link.url("/master")).build()).execute().use { assertEquals(200, it.code) }
        assertEquals("Bearer t", take(link).getHeader("Authorization"))
        val hop = take(other)
        assertNoLogin(hop)
        assertEquals("https://ref.invalid/", hop.getHeader("Referer"))
        assertEquals("v", hop.getHeader("X-Custom"))
    }

    @Test
    fun `a stream client given the link sends no login headers when its first request goes elsewhere (#464)`() {
        other.enqueue(MockResponse().setBody("ok"))
        val client = PlayerPlaybackNetworking.createHttpClient(streamHeaders, linkUrl = link.url("/master").toString())
        client.newCall(Request.Builder().url(other.url("/subtitle.srt")).build()).execute().close()
        val first = take(other)
        assertNoLogin(first)
        assertEquals("https://ref.invalid/", first.getHeader("Referer"))
    }

    @Test
    fun `the main player's data sources are scoped to the addon's link, the DRM licence factory to its licence url (#464)`() {
        val source = java.io.File("src/main/java/com/nuvio/tv/ui/screens/player/PlayerMediaSourceFactory.kt").readText()
        assertEquals(4, Regex("""linkUrl = url""").findAll(source).count())
        assertTrue(source.contains("setDrmHttpDataSourceFactory(PlayerPlaybackNetworking.createHttpDataSourceFactory(sanitizedHeaders, linkUrl = it.licenseUrl))"))
    }

    @Test
    fun `the licence server the addon named gets the stream's headers, a host it redirects to gets no login ones (#464)`() {
        MockWebServer().use { beyond ->
            // The licence server is another origin than the stream's link: the addon named it, so it gets them.
            other.enqueue(MockResponse().setResponseCode(307).addHeader("Location", beyond.url("/licence").toString()))
            beyond.enqueue(MockResponse().setBody("licence"))
            val licenceUrl = other.url("/licence").toString()
            val drm = PlayerPlaybackNetworking.createHttpClient(streamHeaders, linkUrl = licenceUrl)
            drm.newCall(Request.Builder().url(licenceUrl).build()).execute().close()
            assertEquals("Bearer t", take(other).getHeader("Authorization"))
            val hop = beyond.takeRequest(5, TimeUnit.SECONDS)!!
            assertNoLogin(hop)
            assertEquals("https://ref.invalid/", hop.getHeader("Referer"))
        }
    }

    @Test
    fun `a stream client with no headers of its own still scopes the ones a request sets (P8)`() {
        redirectToOther()
        PlayerPlaybackNetworking.createHttpClient(emptyMap()).newCall(streamRequest()).execute().close()
        assertEquals("s=1", take(link).getHeader("Cookie"))
        assertNoLogin(take(other))
    }

    @Test
    fun `a helper on the shared playback client sends no login headers to a redirect target (P5)`() {
        redirectToOther()
        // As the frame-rate probe and subtitle indexing build theirs: the shared client, headers on the request.
        val helper = PlayerPlaybackNetworking.playbackHttpClient.newBuilder().build()
        helper.newCall(streamRequest()).execute().close()
        assertEquals("k", take(link).getHeader("X-Api-Key"))
        val hop = take(other)
        assertNoLogin(hop)
        assertEquals("v", hop.getHeader("X-Custom"))
    }

    /** [server] by its IPv4 address, a home-server address. */
    private fun local(server: MockWebServer, path: String): HttpUrl =
        HttpUrl.Builder().scheme("http").host("127.0.0.1").port(server.port).encodedPath(path).build()

    @Test
    fun `the unchecked retry keeps headers on the home server but sends none past its redirect (P1)`() {
        link.enqueue(MockResponse().setResponseCode(302).addHeader("Location", local(other, "/seg.ts").toString()))
        other.enqueue(MockResponse().setBody("ok"))
        val request = Request.Builder().url(local(link, "/play")).apply {
            streamHeaders.forEach { (name, value) -> header(name, value) }
        }.build()
        PlaybackStreamHeaders.uncheckedRetry(OkHttpClient(), request).execute().close()
        assertEquals("Bearer t", take(link).getHeader("Authorization"))
        val hop = take(other)
        assertNoLogin(hop)
        assertNull(hop.getHeader("Referer"))
        assertNull(hop.getHeader("X-Custom"))
        assertEquals(PlayerMediaSourceFactory.DEFAULT_USER_AGENT, hop.getHeader("User-Agent"))
        // #457: OkHttp's own headers stay; a request without Host is answered 400 by real servers.
        assertEquals("127.0.0.1:${other.port}", hop.getHeader("Host"))
        assertEquals("Keep-Alive", hop.getHeader("Connection"))
    }

    /** A client the way the parallel connections path builds it: headers set on the request, a cookie jar. */
    private fun parallelClient(jar: CookieJar) = PlaybackStreamHeaders.scoped(
        PlayerPlaybackNetworking.playbackHttpClient.newBuilder().cookieJar(jar).build()
    )

    private fun streamRequest() = Request.Builder().url(link.url("/movie.mkv")).apply {
        streamHeaders.forEach { (name, value) -> header(name, value) }
    }.build()

    @Test
    fun `a redirect target keeps the cookie the jar holds for it (P7)`() {
        redirectToOther()
        val jar = object : CookieJar {
            override fun loadForRequest(url: HttpUrl): List<Cookie> =
                if (url.port == other.port) listOf(Cookie.Builder().name("edge").value("1").hostOnlyDomain(url.host).build()) else emptyList()

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
        }
        parallelClient(jar).newCall(streamRequest()).execute().close()
        take(link)
        val hop = take(other)
        assertEquals("edge=1", hop.getHeader("Cookie"))
        assertNull(hop.getHeader("Authorization"))
        assertNull(hop.getHeader("X-Api-Key"))
    }

    @Test
    fun `a warm parallel session going straight to the redirect target sends no login headers (P2)`() {
        other.enqueue(MockResponse().setBody("ok"))
        val client = PlaybackStreamHeaders.scoped(PlayerPlaybackNetworking.playbackHttpClient, link.url("/movie.mkv").toString())
        val straight = streamRequest().newBuilder().url(other.url("/movie.mkv")).build()
        client.newCall(straight).execute().close()
        assertNoLogin(take(other))
    }

    @Test
    fun `a redirect target with no cookie of its own gets none of the stream's (P7)`() {
        redirectToOther()
        parallelClient(CookieJar.NO_COOKIES).newCall(streamRequest()).execute().close()
        assertEquals("s=1", take(link).getHeader("Cookie"))
        assertNoLogin(take(other))
    }
}
