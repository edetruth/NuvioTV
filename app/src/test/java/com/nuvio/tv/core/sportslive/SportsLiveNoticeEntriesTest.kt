package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveNoticeEntriesTest {

    @Test
    fun `informational entries are notices`() {
        listOf(
            stream(name = "Rate limit reached", description = "Please wait 7 min. The stream you are already watching keeps working."),
            stream(title = "Daily limit reached"),
            stream(title = "Too many requests, slow down"),
            stream(title = "Please try again later"),
            stream(title = "Error: could not load this event"),
            stream(title = "This event is not available in your region"),
            stream(title = "Stream unavailable"),
            stream(title = "No streams found for this game"),
            stream(title = "Upgrade to unlock more links"),
            stream(title = "Premium required for this channel"),
            stream(title = "Subscription required")
        ).forEach { assertTrue(SportsLiveNoticeEntries.text(it), SportsLiveNoticeEntries.isNotice(it)) }
    }

    @Test
    fun `an entry with nothing to play is a notice`() {
        assertTrue(SportsLiveNoticeEntries.isNotice(stream(title = "Join our community", url = null)))
    }

    @Test
    fun `a placeholder url or a tiny clip is a notice`() {
        assertTrue(SportsLiveNoticeEntries.isNotice(stream(title = "Sports 1", url = "https://cdn.example.invalid/banner.png")))
        assertTrue(SportsLiveNoticeEntries.isNotice(stream(title = "Sports 1", url = "https://cdn.example.invalid/info.txt?x=1")))
        assertTrue(
            SportsLiveNoticeEntries.isNotice(
                stream(title = "Sports 1", url = "https://cdn.example.invalid/clip.mp4", videoSize = 300_000L)
            )
        )
    }

    @Test
    fun `real stream titles are not notices`() {
        listOf(
            stream(name = "Sports HD", title = "1080p 60fps"),
            stream(title = "Error-free HD feed"),
            stream(title = "Terrorists vs Raiders 720p"),
            stream(title = "Premium HD · English"),
            stream(title = "Channel 2 HD - waiting room show"),
            stream(title = "Limited commentary · 4K"),
            stream(title = "Sports 1", url = "https://cdn.example.invalid/live.m3u8", videoSize = 4_000_000_000L)
        ).forEach { assertFalse(SportsLiveNoticeEntries.text(it), SportsLiveNoticeEntries.isNotice(it)) }
    }

    @Test
    fun `a torrent or an external link alone is not a notice`() {
        assertFalse(
            SportsLiveNoticeEntries.isNotice(
                stream(title = "Game 1080p", url = null, infoHash = "988c0dfb1d2b22fbf30d10bb9a75323534532dbb")
            )
        )
        assertFalse(SportsLiveNoticeEntries.isNotice(stream(title = "Watch on site", url = null, externalUrl = "https://site.example.invalid/watch")))
    }

    @Test
    fun `rate limit minutes come from the notice`() {
        assertEquals(7, SportsLiveNoticeEntries.rateLimitMinutes(stream(title = "Rate limit reached, wait 7 min")))
        assertEquals(10, SportsLiveNoticeEntries.rateLimitMinutes(stream(title = "Limit reached. Try again in 10 minutes")))
        assertEquals(2, SportsLiveNoticeEntries.rateLimitMinutes(stream(title = "Too many requests, wait 90 seconds")))
        assertEquals(60, SportsLiveNoticeEntries.rateLimitMinutes(stream(title = "Quota exceeded, try again in 3 hours")))
    }

    @Test
    fun `a rate limit with no wait time gets the default cool-down`() {
        assertEquals(
            SportsLiveNoticeEntries.DEFAULT_COOLDOWN_MIN,
            SportsLiveNoticeEntries.rateLimitMinutes(stream(title = "Rate limit reached for 1080p channel 2"))
        )
    }

    @Test
    fun `other notices and real streams are not rate limits`() {
        assertNull(SportsLiveNoticeEntries.rateLimitMinutes(stream(title = "Upgrade to unlock more links")))
        assertNull(SportsLiveNoticeEntries.rateLimitMinutes(stream(title = "Sports HD 1080p")))
    }

    private fun stream(
        name: String? = null,
        title: String? = null,
        description: String? = null,
        url: String? = "https://cdn.example.invalid/live.m3u8",
        externalUrl: String? = null,
        infoHash: String? = null,
        videoSize: Long? = null
    ) = Stream(
        name = name,
        title = title,
        description = description,
        url = url,
        ytId = null,
        infoHash = infoHash,
        fileIdx = null,
        externalUrl = externalUrl,
        behaviorHints = videoSize?.let {
            StreamBehaviorHints(notWebReady = null, bingeGroup = null, countryWhitelist = null, proxyHeaders = null, videoSize = it)
        },
        addonName = "Sports",
        addonLogo = null
    )
}
