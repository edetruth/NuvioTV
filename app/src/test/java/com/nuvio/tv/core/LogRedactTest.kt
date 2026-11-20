package com.nuvio.tv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactTest {

    private val secretHost = "private-addon.example.invalid"
    private val token = "s3cr3tT0ken123"

    private fun assertClean(line: String) {
        assertFalse(line, line.contains(token))
        assertFalse(line, line.contains("example.invalid"))
        assertFalse(line, line.contains("private-addon"))
    }

    @Test
    fun `token in the path is removed and the host becomes a label`() {
        val line = LogRedact.redactUrl("https://$secretHost/$token/manifest.json")
        assertClean(line)
        assertEquals("https://${LogRedact.hostLabel(secretHost)}/….json", line)
    }

    @Test
    fun `token in the query and in the userinfo is removed`() {
        assertClean(LogRedact.redactUrl("https://$secretHost/stream/x?token=$token&b=1"))
        assertClean(LogRedact.redactUrl("http://user:$token@$secretHost:8443/play"))
        assertEquals(
            "http://${LogRedact.hostLabel(secretHost)}/…",
            LogRedact.redactUrl("http://user:$token@$secretHost:8443/play?key=$token")
        )
    }

    @Test
    fun `the host label is stable and short and differs between hosts`() {
        val first = LogRedact.hostLabel(secretHost)
        assertEquals(first, LogRedact.hostLabel(secretHost.uppercase()))
        assertTrue(first, Regex("host-[0-9a-f]{4}").matches(first))
        assertFalse(first == LogRedact.hostLabel("other.example.invalid"))
    }

    @Test
    fun `a media extension is kept and an unknown last segment is not`() {
        assertEquals("https://${LogRedact.hostLabel(secretHost)}/….m3u8", LogRedact.redactUrl("https://$secretHost/$token/index.m3u8?sig=$token"))
        assertEquals("https://${LogRedact.hostLabel(secretHost)}/…", LogRedact.redactUrl("https://$secretHost/a/$token.abc12"))
        assertEquals("https://${LogRedact.hostLabel(secretHost)}", LogRedact.redactUrl("https://$secretHost/"))
    }

    @Test
    fun `odd values never come back raw`() {
        assertEquals("magnet:…", LogRedact.redactUrl("magnet:?xt=urn:btih:$token"))
        assertEquals("<redacted>", LogRedact.redactUrl("$secretHost/$token"))
        assertEquals("", LogRedact.redactUrl(""))
        assertEquals("null", LogRedact.redactUrl(null))
    }

    @Test
    fun `a link inside an exception message is redacted and the words are kept`() {
        val message = "Request timeout has expired [url=https://$secretHost/$token/stream.json, request_timeout=10000 ms]"
        val line = LogRedact.redactText(message)
        assertClean(line)
        assertTrue(line, line.startsWith("Request timeout has expired [url=https://host-"))
        assertTrue(line, line.endsWith("/….json, request_timeout=10000 ms]"))
    }

    @Test
    fun `bare hosts ip addresses and bearer tokens in text are redacted`() {
        val line = LogRedact.redactText(
            "Unable to resolve host \"$secretHost\" then Failed to connect to $secretHost/192.168.1.20:8096 " +
                "Authorization: Bearer $token apikey=$token jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abc"
        )
        assertClean(line)
        assertFalse(line, line.contains("192.168.1.20"))
        assertFalse(line, line.contains("eyJ"))
        assertTrue(line, line.contains("Unable to resolve host \"host-"))
        assertTrue(line, line.contains("Bearer <redacted>"))
    }

    @Test
    fun `plain text and class names are untouched`() {
        val plain = "HTTP 404 Not Found code=404 kotlinx.coroutines.JobCancellationException: Job was cancelled at 1.5x"
        assertEquals(plain, LogRedact.redactText(plain))
        assertEquals("Could not save manifest.json", LogRedact.redactText("Could not save manifest.json"))
        assertEquals("null", LogRedact.redactText(null))
    }

    @Test
    fun `an exception keeps its class and stack and loses the link`() {
        val cause = java.net.ConnectException("Failed to connect to $secretHost/10.0.0.2:443")
        val original = java.io.IOException("GET https://$secretHost/$token/meta.json failed", cause)
        val redacted = LogRedact.redactThrowable(original)
        assertTrue(redacted.toString(), redacted.toString().startsWith("java.io.IOException: GET https://host-"))
        assertClean(redacted.toString())
        assertEquals(original.stackTrace.toList(), redacted.stackTrace.toList())
        val redactedCause = redacted.cause!!
        assertTrue(redactedCause.toString(), redactedCause.toString().startsWith("java.net.ConnectException: Failed to connect to host-"))
        assertClean(redactedCause.toString())
        val printed = java.io.StringWriter().also { redacted.printStackTrace(java.io.PrintWriter(it)) }.toString()
        assertClean(printed)
        assertFalse(printed, printed.contains("10.0.0.2"))
    }

    @Test
    fun `an exception without a message stays without one`() {
        val redacted = LogRedact.redactThrowable(IllegalStateException())
        assertNull(redacted.message)
        assertEquals("java.lang.IllegalStateException", redacted.toString())
    }
}
