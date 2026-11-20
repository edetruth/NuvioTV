package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #147: which links start fast, learned per game and addon, with decay. */
class SportsLiveLinkSpeedTest {
    private var now = 1_000_000_000L
    private val speed = SportsLiveLinkSpeed(nowMs = { now })

    private val slow = link("Slow", "a")
    private val fast = link("Fast", "b")
    private val fresh = link("Fresh", "c")

    @Test
    fun `with no history the links keep their order`() {
        assertEquals(listOf(slow, fast, fresh), speed.order("game", listOf(slow, fast, fresh)))
    }

    @Test
    fun `a start moves its addon's links ahead and a failure moves them back`() {
        speed.recordStart("game", "Fast", 1_200)
        speed.recordFailure("game", "Slow")

        assertEquals(listOf(fast, fresh, slow), speed.order("game", listOf(slow, fast, fresh)))
    }

    @Test
    fun `among addons that start as often the faster median goes first`() {
        listOf(6_000L, 7_000L, 5_000L).forEach { speed.recordStart("game", "Slow", it) }
        listOf(900L, 15_000L, 1_100L).forEach { speed.recordStart("game", "Fast", it) }

        // Fast's median is 1.1 s despite one slow start; Slow's is 6 s.
        assertEquals(1_100L, speed.rank("game", "Fast").medianMs)
        assertEquals(6_000L, speed.rank("game", "Slow").medianMs)
        assertEquals(listOf(fast, slow), speed.order("game", listOf(slow, fast)))
    }

    @Test
    fun `a new game starts from what its addon did on other games`() {
        speed.recordStart("yesterday", "Fast", 1_000)
        speed.recordFailure("yesterday", "Slow")

        assertEquals(listOf(fast, slow), speed.order("today", listOf(slow, fast)))
    }

    @Test
    fun `the game's own history wins over the addon's`() {
        repeat(3) { speed.recordStart("other", "Slow", 1_000) }
        speed.recordFailure("game", "Slow")

        assertTrue(speed.rank("game", "Slow").rate < 0.5)
        assertTrue(speed.rank("next", "Slow").rate > 0.5)
    }

    @Test
    fun `evidence decays so an addon that failed days ago is neutral again`() {
        repeat(4) { speed.recordFailure("game", "Slow") }
        val bad = speed.rank("game", "Slow").rate

        now += 10 * SportsLiveLinkSpeed.HALF_LIFE_MS
        val later = speed.rank("game", "Slow").rate

        assertTrue(bad < 0.2)
        assertEquals(0.5, later, 0.01)
        // Its old times to first frame no longer count either.
        speed.recordStart("game", "Fast", 2_000)
        now += 10 * SportsLiveLinkSpeed.HALF_LIFE_MS
        assertNull(speed.rank("game", "Fast").medianMs)
    }

    @Test
    fun `a probe teaches half as much as a real start`() {
        speed.recordProbe("game", "Fast", ok = true)
        speed.recordStart("game", "Slow", 3_000)

        assertEquals(0.75, speed.rank("game", "Slow").rate, 1e-9)
        assertEquals((0.5 + 0.5) / 1.5, speed.rank("game", "Fast").rate, 1e-9)
        speed.recordProbe("game", "Fresh", ok = false)
        assertTrue(speed.rank("game", "Fresh").rate < 0.5)
    }

    @Test
    fun `the book survives a save and a load`() {
        speed.recordStart("game", "Fast", 1_500)
        speed.recordFailure("game", "Slow")
        var saved: Map<String, SportsLiveLinkSpeed.Record> = emptyMap()
        speed.install(emptyMap()) { saved = it }

        val loaded = SportsLiveLinkSpeed(nowMs = { now })
        loaded.install(SportsLiveLinkSpeed.decode(SportsLiveLinkSpeed.encode(saved))) {}

        assertEquals(speed.rank("game", "Fast"), loaded.rank("game", "Fast"))
        assertEquals(speed.rank("game", "Slow"), loaded.rank("game", "Slow"))
        assertTrue(SportsLiveLinkSpeed.decode("not json").isEmpty())
    }

    @Test
    fun `clear forgets everything`() {
        speed.recordStart("game", "Fast", 1_500)
        speed.clear()
        assertTrue(speed.snapshot().isEmpty())
    }

    private fun link(addon: String, id: String) = Stream(
        name = id, title = null, description = null, url = "https://cdn.example.invalid/$id.m3u8", ytId = null,
        infoHash = null, fileIdx = null, externalUrl = null, behaviorHints = null, addonName = addon, addonLogo = null
    )
}
