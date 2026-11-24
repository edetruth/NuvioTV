package com.nuvio.tv.ui.screens.player

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `a stream client with no headers of its own still scopes the ones a request sets (P8)`() {
        redirectToOther()
        PlayerPlaybackNetworking.createHttpClient(emptyMap()).newCall(streamRequest()).execute().close()
        assertEquals("s=1", take(link).getHeader("Cookie"))
        assertNoLogin(take(other))
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
    fun `a redirect target with no cookie of its own gets none of the stream's (P7)`() {
        redirectToOther()
        parallelClient(CookieJar.NO_COOKIES).newCall(streamRequest()).execute().close()
        assertEquals("s=1", take(link).getHeader("Cookie"))
        assertNoLogin(take(other))
    }
}
