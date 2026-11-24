package com.nuvio.tv.core.network

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P4 (review of #279, #434): a stream's origin is its scheme, host and port. */
class StreamOriginTest {

    private fun same(from: String, to: String) = StreamOrigin.same(from.toHttpUrl(), to.toHttpUrl())

    @Test
    fun `the same scheme, host and port is the same origin`() {
        assertTrue(same("https://cdn.invalid/a.m3u8", "https://CDN.invalid/b/c.ts"))
        assertTrue(same("https://cdn.invalid:8443/a", "https://cdn.invalid:8443/b"))
        assertTrue(same("http://cdn.invalid/a", "http://cdn.invalid:80/b"))
    }

    @Test
    fun `another host or another port is another origin`() {
        assertFalse(same("https://cdn.invalid/a", "https://edge.invalid/a"))
        assertFalse(same("https://cdn.invalid:8443/a", "https://cdn.invalid:9000/a"))
        assertFalse(same("https://cdn.invalid/a", "https://cdn.invalid:8443/a"))
        assertFalse(same("http://localhost:8001/a", "http://localhost:8002/a"))
    }

    @Test
    fun `down from https to http is never the same origin, up on the default ports is`() {
        assertFalse(same("https://cdn.invalid/a", "http://cdn.invalid/a"))
        assertFalse(same("https://cdn.invalid:8443/a", "http://cdn.invalid:8443/a"))
        assertTrue(same("http://cdn.invalid/a", "https://cdn.invalid/a"))
        assertFalse(same("http://cdn.invalid:8080/a", "https://cdn.invalid:8443/a"))
        assertTrue(StreamOrigin.isDowngrade("https://cdn.invalid/a".toHttpUrl(), "http://cdn.invalid/a".toHttpUrl()))
    }
}
