package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveSourceCooldownTest {

    private var now = 0L
    private val cooldown = SportsLiveSourceCooldown(nowMs = { now })

    @Test
    fun `a rate-limit notice cools its addon down by base url for the minutes it names, then expires`() {
        cooldown.noteNotices(listOf(addon("Sports", SPORTS_URL, notice("Rate limit reached, wait 7 min"))))

        assertTrue(cooldown.isCoolingDown(SPORTS_URL))
        assertTrue(cooldown.isCoolingDown("$SPORTS_URL/"))
        assertEquals(setOf(SPORTS_URL), cooldown.coolingBaseUrls())
        assertEquals(7, cooldown.remainingMinutes(SPORTS_URL))
        assertFalse(cooldown.isCoolingDown(OTHER_URL))

        now += 7 * 60_000L - 1
        assertTrue(cooldown.isCoolingDown(SPORTS_URL))
        now += 1
        assertFalse(cooldown.isCoolingDown(SPORTS_URL))
        assertTrue(cooldown.coolingBaseUrls().isEmpty())
    }

    @Test
    fun `a notice with no wait time cools down for five minutes`() {
        cooldown.noteNotices(listOf(addon("Sports", SPORTS_URL, notice("Too many requests"))))

        assertEquals(SportsLiveNoticeEntries.DEFAULT_COOLDOWN_MIN, cooldown.remainingMinutes(SPORTS_URL))
        now += 5 * 60_000L
        assertFalse(cooldown.isCoolingDown(SPORTS_URL))
    }

    @Test
    fun `streams without a rate-limit notice, or without a base url, start no cool-down`() {
        cooldown.noteNotices(listOf(addon("Sports", SPORTS_URL, notice("Sports HD 1080p"), notice("Upgrade for more links"))))
        cooldown.noteNotices(listOf(addon("Plugin", null, notice("Rate limit reached"))))

        assertTrue(cooldown.coolingBaseUrls().isEmpty())
    }

    @Test
    fun `each cool-down is announced once, and close ones share a toast`() = runTest {
        cooldown.note(SPORTS_URL, "Sports", 7)
        cooldown.note(OTHER_URL, "Other", 3)
        cooldown.note(SPORTS_URL, "Sports", 2) // still cooling down: no second toast, and not shortened

        assertEquals(SportsLiveSourceCooldown.Announcement(listOf("Sports", "Other"), 7), cooldown.announcements.first())
        assertNull(withTimeoutOrNull(5_000) { cooldown.announcements.first() })
        assertEquals(7, cooldown.remainingMinutes(SPORTS_URL))
    }

    @Test
    fun `a queued toast whose cool-down is over is dropped`() = runTest {
        cooldown.note(SPORTS_URL, "Sports", 1)
        now += 60_000L // over before any screen showed it

        assertNull(withTimeoutOrNull(5_000) { cooldown.announcements.first() })

        cooldown.note(SPORTS_URL, "Sports", 2)
        assertEquals(SportsLiveSourceCooldown.Announcement(listOf("Sports"), 2), cooldown.announcements.first())
    }

    private fun addon(name: String, baseUrl: String?, vararg streams: Stream) =
        AddonStreams(addonName = name, addonLogo = null, streams = streams.toList(), addonBaseUrl = baseUrl)

    private fun notice(title: String) = Stream(
        name = null,
        title = title,
        description = null,
        url = "https://cdn.example.invalid/live.m3u8",
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = "Sports",
        addonLogo = null
    )

    private companion object {
        const val SPORTS_URL = "https://sports.example.invalid/config"
        const val OTHER_URL = "https://other.example.invalid"
    }
}
