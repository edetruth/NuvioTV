package com.nuvio.tv.data.mapper

import com.nuvio.tv.core.sportslive.SportsLiveStreamLicence
import com.nuvio.tv.core.sportslive.SportsLiveStreamLicences
import com.nuvio.tv.data.remote.dto.StreamResponseDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #166: licence details an addon sends with a stream reach the players, whatever their JSON shape. */
class StreamLicenceMappingTest {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    @After
    fun tearDown() {
        SportsLiveStreamLicences.clear()
    }

    private fun parse(json: String) =
        moshi.adapter(StreamResponseDto::class.java).fromJson(json)!!.streams.orEmpty().map { it.toDomain("Addon", null) }

    @Test
    fun `a licence in behaviour hints is remembered for the link`() {
        val streams = parse(
            """
            {"streams": [{"name": "Live", "url": "https://example.invalid/live.mpd",
              "behaviorHints": {"drm": {"type": "widevine", "licenseUrl": "https://example.invalid/wv", "headers": {"X-T": "1"}}}}]}
            """.trimIndent()
        )
        assertEquals(1, streams.size)
        assertEquals(
            SportsLiveStreamLicence(SportsLiveStreamLicence.Scheme.WIDEVINE, "https://example.invalid/wv", mapOf("X-T" to "1")),
            SportsLiveStreamLicences.forUrl("https://example.invalid/live.mpd")
        )
    }

    @Test
    fun `a stream level licence in another shape is read too`() {
        parse(
            """
            {"streams": [{"url": "https://example.invalid/a.mpd",
              "license": {"com.widevine.alpha": {"serverURL": "https://example.invalid/l"}}}]}
            """.trimIndent()
        )
        assertEquals("https://example.invalid/l", SportsLiveStreamLicences.forUrl("https://example.invalid/a.mpd")?.licenseUrl)
    }

    @Test
    fun `odd licence values never break parsing the streams`() {
        val streams = parse(
            """
            {"streams": [
              {"url": "https://example.invalid/b.mpd", "drm": "widevine"},
              {"url": "https://example.invalid/c.mpd", "behaviorHints": {"license": [1, 2]}},
              {"url": "https://example.invalid/d.mpd", "behaviorHints": {"drm": {"type": "clearkey", "keys": {"k": "v"}}}}
            ]}
            """.trimIndent()
        )
        assertEquals(3, streams.size)
        assertNull(SportsLiveStreamLicences.forUrl("https://example.invalid/b.mpd"))
        assertEquals("fields", SportsLiveStreamLicences.describe("https://example.invalid/b.mpd"))
        assertEquals("keys", SportsLiveStreamLicences.describe("https://example.invalid/d.mpd"))
    }

    @Test
    fun `a stream without licence fields registers nothing`() {
        parse("""{"streams": [{"url": "https://example.invalid/e.m3u8"}]}""")
        assertEquals("none", SportsLiveStreamLicences.describe("https://example.invalid/e.m3u8"))
    }
}
