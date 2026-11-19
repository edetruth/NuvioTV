package com.nuvio.tv.core.sportslive

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveStreamLicenceTest {

    @After
    fun tearDown() {
        SportsLiveStreamLicences.clear()
    }

    @Test
    fun `a flat licence object gives its scheme, server and headers`() {
        val parsed = SportsLiveStreamLicences.parse(
            mapOf(
                "type" to "com.widevine.alpha",
                "licenseUrl" to "https://example.invalid/wv",
                "headers" to mapOf("X-Token" to "abc", "Empty" to "")
            )
        )
        assertEquals(
            SportsLiveStreamLicence(SportsLiveStreamLicence.Scheme.WIDEVINE, "https://example.invalid/wv", mapOf("X-Token" to "abc")),
            parsed.licence
        )
        assertFalse(parsed.hasRawKeys)
    }

    @Test
    fun `the dash js protection data shape is read`() {
        val parsed = SportsLiveStreamLicences.parse(
            mapOf("com.microsoft.playready" to mapOf("serverURL" to "https://example.invalid/pr", "httpRequestHeaders" to mapOf("A" to "b")))
        )
        assertEquals(SportsLiveStreamLicence.Scheme.PLAYREADY, parsed.licence?.scheme)
        assertEquals("https://example.invalid/pr", parsed.licence?.licenseUrl)
        assertEquals(mapOf("A" to "b"), parsed.licence?.headers)
    }

    @Test
    fun `a licence server with no scheme named is taken as Widevine`() {
        val parsed = SportsLiveStreamLicences.parse(mapOf("license_url" to "https://example.invalid/l"))
        assertEquals(SportsLiveStreamLicence.Scheme.WIDEVINE, parsed.licence?.scheme)
    }

    @Test
    fun `raw keys are reported but never used as a licence`() {
        val parsed = SportsLiveStreamLicences.parse(mapOf("type" to "clearkey", "keys" to mapOf("kid" to "key")))
        assertNull(parsed.licence)
        assertTrue(parsed.hasRawKeys)
    }

    @Test
    fun `a value that is not an object, or has no http server, gives no licence`() {
        assertNull(SportsLiveStreamLicences.parse("widevine", 3, null).licence)
        assertNull(SportsLiveStreamLicences.parse(mapOf("licenseUrl" to "skd://example.invalid")).licence)
    }

    @Test
    fun `the first candidate with a licence wins`() {
        val parsed = SportsLiveStreamLicences.parse(
            null,
            mapOf("licenseUrl" to "https://example.invalid/first"),
            mapOf("licenseUrl" to "https://example.invalid/second")
        )
        assertEquals("https://example.invalid/first", parsed.licence?.licenseUrl)
    }

    @Test
    fun `scheme names and uuids are recognised`() {
        assertEquals(SportsLiveStreamLicence.Scheme.WIDEVINE, SportsLiveStreamLicences.schemeOf("urn:uuid:EDEF8BA9-79D6-4ACE-A3C8-27DCD51D21ED"))
        assertEquals(SportsLiveStreamLicence.Scheme.CLEARKEY, SportsLiveStreamLicences.schemeOf("org.w3.clearkey"))
        assertNull(SportsLiveStreamLicences.schemeOf("fairplay"))
    }

    @Test
    fun `a licence is found for its link with or without user info`() {
        val licence = SportsLiveStreamLicence(SportsLiveStreamLicence.Scheme.WIDEVINE, "https://example.invalid/wv")
        SportsLiveStreamLicences.note("https://user:pass@example.invalid/live.mpd", licence)
        assertEquals(licence, SportsLiveStreamLicences.forUrl("https://example.invalid/live.mpd"))
        assertEquals("licence", SportsLiveStreamLicences.describe("https://example.invalid/live.mpd"))
        assertNull(SportsLiveStreamLicences.forUrl("https://example.invalid/other.mpd"))
        assertEquals("none", SportsLiveStreamLicences.describe("https://example.invalid/other.mpd"))
    }

    @Test
    fun `unusable licence fields are described for the probe`() {
        SportsLiveStreamLicences.note("https://example.invalid/k.mpd", SportsLiveStreamLicences.parse(mapOf("keys" to listOf("x"))))
        SportsLiveStreamLicences.note("https://example.invalid/f.mpd", SportsLiveStreamLicences.parse(mapOf("type" to "widevine")))
        assertEquals("keys", SportsLiveStreamLicences.describe("https://example.invalid/k.mpd"))
        assertEquals("fields", SportsLiveStreamLicences.describe("https://example.invalid/f.mpd"))
        assertFalse(SportsLiveStreamLicences.has("https://example.invalid/k.mpd"))
    }
}
