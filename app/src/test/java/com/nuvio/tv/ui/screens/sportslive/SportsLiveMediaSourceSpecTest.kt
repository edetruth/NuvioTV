package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.domain.model.ProxyHeaders
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Test

class SportsLiveMediaSourceSpecTest {

    private fun stream(
        request: Map<String, String>?,
        response: Map<String, String>?,
        filename: String?
    ) = Stream(
        name = "HD",
        title = null,
        description = null,
        url = "https://cdn.example/hd.m3u8",
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = null,
            countryWhitelist = null,
            proxyHeaders = ProxyHeaders(request = request, response = response),
            filename = filename
        ),
        addonName = "Sports",
        addonLogo = null
    )

    @Test
    fun `spec carries both the request and response header maps and the filename`() {
        val spec = sportsLiveMediaSourceSpec(
            stream = stream(
                request = mapOf("Referer" to "https://site.example"),
                response = mapOf("Set-Cookie" to "sid=1"),
                filename = "game.mkv"
            ),
            url = "https://cdn.example/hd.m3u8"
        )

        assertEquals("https://cdn.example/hd.m3u8", spec.url)
        assertEquals("https://site.example", spec.requestHeaders["Referer"])
        assertEquals(mapOf("Set-Cookie" to "sid=1"), spec.responseHeaders)
        assertEquals("game.mkv", spec.filename)
    }

    @Test
    fun `missing proxy headers give empty maps rather than losing one side`() {
        val spec = sportsLiveMediaSourceSpec(
            stream = stream(request = null, response = null, filename = null),
            url = "https://cdn.example/hd.m3u8"
        )

        assertEquals(emptyMap<String, String>(), spec.requestHeaders)
        assertEquals(emptyMap<String, String>(), spec.responseHeaders)
        assertEquals(null, spec.filename)
    }
}
