package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveSourceCooldownTest {

    private var now = 0L
    private val cooldown = SportsLiveSourceCooldown(nowMs = { now })

    @Test
    fun `a rate-limit notice starts a cool-down for the minutes it names, then expires`() {
        cooldown.noteNotices(listOf(addon("Sports", notice("Rate limit reached, wait 7 min"))))

        assertTrue(cooldown.isCoolingDown("Sports"))
        assertTrue(cooldown.isCoolingDown(" sports "))
        assertEquals(7, cooldown.remainingMinutes("Sports"))
        assertFalse(cooldown.isCoolingDown("Other"))

        now += 7 * 60_000L - 1
        assertTrue(cooldown.isCoolingDown("Sports"))
        now += 1
        assertFalse(cooldown.isCoolingDown("Sports"))
        assertEquals(0, cooldown.remainingMinutes("Sports"))
    }

    @Test
    fun `a notice with no wait time cools down for five minutes`() {
        cooldown.noteNotices(listOf(addon("Sports", notice("Too many requests"))))

        assertEquals(SportsLiveNoticeEntries.DEFAULT_COOLDOWN_MIN, cooldown.remainingMinutes("Sports"))
        now += 5 * 60_000L
        assertFalse(cooldown.isCoolingDown("Sports"))
    }

    @Test
    fun `streams without a rate-limit notice start no cool-down`() {
        cooldown.noteNotices(listOf(addon("Sports", notice("Sports HD 1080p"), notice("Upgrade for more links"))))

        assertFalse(cooldown.isCoolingDown("Sports"))
    }

    @Test
    fun `each cool-down is announced once`() = runTest {
        cooldown.note("Sports", 7)
        cooldown.note("Sports", 3) // still cooling down: no second toast, and it is not shortened

        assertEquals(SportsLiveSourceCooldown.Announcement("Sports", 7), cooldown.announcements.first())
        assertNull(withTimeoutOrNull(10) { cooldown.announcements.first() })
        assertEquals(7, cooldown.remainingMinutes("Sports"))

        // Once it is over, a new notice starts (and announces) a new cool-down.
        now += 7 * 60_000L
        cooldown.note("Sports", 2)
        assertEquals(SportsLiveSourceCooldown.Announcement("Sports", 2), cooldown.announcements.first())
    }

    private fun addon(name: String, vararg streams: Stream) = AddonStreams(addonName = name, addonLogo = null, streams = streams.toList())

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
}
