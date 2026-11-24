package com.nuvio.tv.ui.screens.player

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
}
