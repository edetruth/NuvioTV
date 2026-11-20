package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.ProxyHeaders
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveNoticeEntriesTest {

    @Test
    fun `a strong rate-limit phrase flags an entry even with a media url`() {
        listOf(
            stream(name = "Rate limit reached", description = "Please wait 7 min. The stream you are already watching keeps working."),
            stream(title = "Daily limit reached"),
            stream(title = "Too many requests, slow down"),
            stream(title = "Please wait 3 minutes")
        ).forEach { assertTrue(SportsLiveNoticeEntries.text(it), SportsLiveNoticeEntries.isNotice(it)) }
    }

    @Test
    fun `a soft word flags an entry with no media target`() {
        listOf(
            "Please try again later",
            "Error: could not load this event",
            "This event is not available in your region",
            "Stream unavailable",
            "No streams found for this game",
            "Upgrade to unlock more links",
            "Premium required for this channel",
            "Subscription required"
        ).flatMap { title ->
            listOf(
                stream(title = title, url = null, externalUrl = "https://site.example.invalid/info"),
                stream(title = title, url = "https://site.example.invalid/"),
                stream(title = title, url = "https://site.example.invalid/info.html")
            )
        }.forEach { assertTrue(SportsLiveNoticeEntries.text(it), SportsLiveNoticeEntries.isNotice(it)) }
    }

    @Test
    fun `soft words alone never flag an entry with a valid media url`() {
        listOf(
            stream(title = "4K not available · 1080p"),
            stream(title = "if it buffers, try again"),
            stream(title = "Upgrade: new 1080p server"),
            stream(title = "Backup feed (error correction on)")
        ).forEach { assertFalse(SportsLiveNoticeEntries.text(it), SportsLiveNoticeEntries.isNotice(it)) }
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
    fun `a wait time far from the rate-limit phrase is not its cool-down`() {
        val far = "Rate limit reached. " + "Our schedule lists every match of the week with kickoff times. ".repeat(2) +
            "Halftime show in 15 minutes"
        assertEquals(SportsLiveNoticeEntries.DEFAULT_COOLDOWN_MIN, SportsLiveNoticeEntries.rateLimitMinutes(stream(title = far)))
        assertEquals(9, SportsLiveNoticeEntries.rateLimitMinutes(stream(title = "Rate limit reached", description = "Try again in 9 min")))
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
        assertNull(SportsLiveNoticeEntries.rateLimitMinutes(stream(title = "Upgrade to unlock more links", url = null)))
        assertNull(SportsLiveNoticeEntries.rateLimitMinutes(stream(title = "Sports HD 1080p")))
    }

    // --- N2: tip, info and how-to entries ---

    @Test
    fun `a tip with no media url is a notice`() {
        val tip = "If streams are not working,\ntry using an external player.\nMPV and VLC are recommended.\nYou can disable this tip\nin the configure page."
        listOf(
            // The QA case: a "Tip:" title and its text, with a link that is a page, not media.
            stream(name = "Tip: Use an external player", description = tip, url = "https://addon.example.invalid/configure"),
            stream(name = "Tip: Use an external player", description = tip, url = "https://addon.example.invalid/tip"),
            stream(title = "Info: links refresh every 10 minutes", url = "https://addon.example.invalid/play/abc123"),
            stream(title = "How to: set up a player", url = "https://addon.example.invalid/help"),
            stream(title = "Sports 1", description = "If the stream is not working, switch servers", url = "https://addon.example.invalid/x"),
            stream(title = "Notice", description = "You can hide this message in settings · disable this notice", url = "https://addon.example.invalid/x"),
            // No url at all, but a page to open: still a notice.
            stream(name = "Tip: use an external player", url = null, externalUrl = "https://addon.example.invalid/configure")
        ).forEach { assertTrue(SportsLiveNoticeEntries.text(it), SportsLiveNoticeEntries.isNotice(it)) }
        assertTrue(SportsLiveNoticeEntries.isTip(stream(name = "Pro tip - use VLC")))
    }

    @Test
    fun `tip words never flag a url that is plainly media`() {
        listOf(
            stream(name = "Tip: Use an external player", url = "https://cdn.example.invalid/live/index.m3u8?token=1"),
            stream(name = "Tip: Use an external player", url = "https://cdn.example.invalid/live/stream.mpd"),
            stream(name = "Tip: Use an external player", url = "https://cdn.example.invalid/hls/chunk.ts/index"),
            stream(name = "Tip: Use an external player", url = "rtmp://cdn.example.invalid/live/1"),
            stream(name = "Tip: Use an external player", url = null, infoHash = "988c0dfb1d2b22fbf30d10bb9a75323534532dbb"),
            stream(name = "Tip: Use an external player", url = "https://cdn.example.invalid/play/abc", videoSize = 2_000_000_000L),
            withHeaders(stream(name = "Tip: Use an external player", url = "https://cdn.example.invalid/play/abc"))
        ).forEach { assertFalse(SportsLiveNoticeEntries.text(it), SportsLiveNoticeEntries.isNotice(it)) }
    }

    @Test
    fun `real streams with page-like links and no tip wording are not notices`() {
        listOf(
            stream(name = "Sports 1 (Strong)", description = "1920×1080 · Stereo · ~9.8 Mbps · small delay", url = "https://cdn.example.invalid/play/abc123"),
            stream(title = "Backup server · external CDN", url = "https://cdn.example.invalid/watch/42"),
            stream(title = "Information channel HD", url = "https://cdn.example.invalid/ch/7"),
            stream(title = "Tipico Bundesliga 1080p", url = "https://cdn.example.invalid/ch/8"),
            stream(title = "Player cam · external feed", url = "https://cdn.example.invalid/ch/9")
        ).forEach { assertFalse(SportsLiveNoticeEntries.text(it), SportsLiveNoticeEntries.isNotice(it)) }
    }

    @Test
    fun `a source asking for support is a notice, not a source (F9)`() {
        listOf(
            stream(name = "Support Us", description = "Help keep servers and streams alive\nTap to open Donation Center (Ko-fi / Crypto)", url = "https://addon.example.invalid/donate"),
            stream(title = "Donate to keep the links up", url = "https://addon.example.invalid/page"),
            stream(title = "Buy me a coffee", url = null, externalUrl = "https://pay.example.invalid/x")
        ).forEach { assertTrue(SportsLiveNoticeEntries.text(it), SportsLiveNoticeEntries.isNotice(it)) }
        // A real stream is never one, whatever its wording.
        assertFalse(SportsLiveNoticeEntries.isNotice(stream(name = "Support Us", url = "https://cdn.example.invalid/live/index.m3u8")))
        assertFalse(SportsLiveNoticeEntries.isNotice(stream(title = "Supporters Cup Final", url = "https://cdn.example.invalid/ch/3")))
    }

    private fun withHeaders(stream: Stream) = stream.copy(
        behaviorHints = StreamBehaviorHints(
            notWebReady = true, bingeGroup = null, countryWhitelist = null,
            proxyHeaders = ProxyHeaders(request = mapOf("Referer" to "https://site.example.invalid/"), response = null)
        )
    )

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
