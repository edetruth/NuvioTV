package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveWorkingLinksTest {

    private var now = 1_000_000L
    private val links = SportsLiveWorkingLinks(nowMs = { now })

    private val a = link("a")
    private val b = link("b")
    private val c = link("c")

    @Test
    fun `good links go first and quarantined ones last, the rest keep their order`() {
        links.markGood(GAME, c.url)
        links.quarantine(GAME, a.url)

        assertEquals(listOf(c, b, a), links.order(GAME, listOf(a, b, c)))
        assertEquals(listOf(b, c), links.withoutQuarantined(GAME, listOf(a, b, c)))
        // Another game is not affected.
        assertEquals(listOf(a, b, c), links.order("other", listOf(a, b, c)))
    }

    @Test
    fun `a good link expires after three hours`() {
        links.markGood(GAME, a.url)
        now += SportsLiveWorkingLinks.GOOD_FOR_MS - 1
        assertTrue(links.isGood(GAME, a.url))
        now += 1
        assertFalse(links.isGood(GAME, a.url))
        assertEquals(listOf(b, a), links.order(GAME, listOf(b, a)))
    }

    @Test
    fun `a quarantine ends after fifteen minutes`() {
        links.quarantine(GAME, a.url)
        now += SportsLiveWorkingLinks.QUARANTINE_MS - 1
        assertTrue(links.isQuarantined(GAME, a.url))
        now += 1
        assertFalse(links.isQuarantined(GAME, a.url))
        assertEquals(listOf(a, b), links.order(GAME, listOf(a, b)))
    }

    @Test
    fun `every link quarantined keeps them all, in their order`() {
        links.quarantine(GAME, a.url)
        links.quarantine(GAME, b.url)
        assertEquals(listOf(a, b), links.order(GAME, listOf(a, b)))
        assertEquals(emptyList<Stream>(), links.withoutQuarantined(GAME, listOf(a, b)))
    }

    @Test
    fun `a failure undoes good and a later success undoes the quarantine`() {
        links.markGood(GAME, a.url)
        links.quarantine(GAME, a.url)
        assertFalse(links.isGood(GAME, a.url))
        assertTrue(links.isQuarantined(GAME, a.url))

        links.markGood(GAME, a.url)
        assertTrue(links.isGood(GAME, a.url))
        assertFalse(links.isQuarantined(GAME, a.url))
    }

    @Test
    fun `a link is good only once it played thirty seconds`() {
        links.played(GAME, a.url, SportsLiveWorkingLinks.GOOD_AFTER_MS - 1)
        assertFalse(links.isGood(GAME, a.url))
        links.played(GAME, a.url, SportsLiveWorkingLinks.GOOD_AFTER_MS)
        assertTrue(links.isGood(GAME, a.url))
    }

    @Test
    fun `the full player's link turns good after thirty seconds of progress, not of wall time`() {
        links.startedPlaying(GAME, a)
        // Paused or buffering for an hour: no progress reported, nothing good.
        now += 60 * 60 * 1000L
        assertFalse(links.progressed(GAME, SportsLiveWorkingLinks.GOOD_AFTER_MS - 1))
        assertFalse(links.isGood(GAME, a.url))
        // The same link starting again (a seek) keeps its progress.
        links.startedPlaying(GAME, a)
        assertTrue(links.progressed(GAME, 1))
        assertTrue(links.isGood(GAME, a.url))
    }

    @Test
    fun `another link starting resets the progress`() {
        links.startedPlaying(GAME, a)
        links.progressed(GAME, SportsLiveWorkingLinks.GOOD_AFTER_MS - 1)
        links.startedPlaying(GAME, b)
        assertFalse(links.progressed(GAME, 1))
        assertFalse(links.isGood(GAME, b.url))
    }

    @Test
    fun `a link that fails before it is good is quarantined`() {
        assertTrue(links.failed(GAME, a.url))
        assertTrue(links.isQuarantined(GAME, a.url))
    }

    @Test
    fun `a good link stays good after one failure, and is quarantined by a second within ten minutes`() {
        links.markGood(GAME, a.url)
        assertFalse(links.failed(GAME, a.url))
        assertTrue(links.isGood(GAME, a.url))
        assertFalse(links.isQuarantined(GAME, a.url))

        now += SportsLiveWorkingLinks.STRIKE_WINDOW_MS - 1
        assertTrue(links.failed(GAME, a.url))
        assertFalse(links.isGood(GAME, a.url))
        assertTrue(links.isQuarantined(GAME, a.url))
    }

    @Test
    fun `a good link's strike lapses after ten minutes`() {
        links.markGood(GAME, b.url)
        links.failed(GAME, b.url)
        now += SportsLiveWorkingLinks.STRIKE_WINDOW_MS
        assertFalse(links.failed(GAME, b.url))
        assertTrue(links.isGood(GAME, b.url))
    }

    @Test
    fun `the addon's url and the url the player plays are the same link`() {
        val withCredentials = "https://user:secret@example.invalid/a.m3u8"
        links.quarantine(GAME, withCredentials)
        assertTrue(links.isQuarantined(GAME, a.url))
        links.markGood(GAME, a.url)
        assertTrue(links.isGood(GAME, withCredentials))
    }

    @Test
    fun `the game used least recently is dropped first`() {
        links.markGood("first", a.url)
        repeat(199) { links.markGood("g$it", a.url) }
        // Reading the first game makes it the most recently used.
        assertTrue(links.isGood("first", a.url))
        links.markGood("new", a.url)
        assertEquals(200, links.gameCount())
        assertTrue(links.isGood("first", a.url))
        assertFalse(links.isGood("g0", a.url))
    }

    @Test
    fun `a fallback is kept for one quarantine window`() {
        links.noteFallback(GAME, a)
        now += SportsLiveWorkingLinks.QUARANTINE_MS - 1
        assertSame(a, links.fallback(GAME))
        now += 1
        assertNull(links.fallback(GAME))
    }

    @Test
    fun `the playing stream is kept until it fails or another one launches`() {
        links.startedPlaying(GAME, a)
        assertSame(a, links.playing(GAME))

        links.noteLaunch(GAME, b)
        assertNull(links.playing(GAME))
        assertSame(b, links.launched(GAME))

        links.startedPlaying(GAME, b)
        links.quarantine(GAME, b.url)
        assertNull(links.playing(GAME))

        links.startedPlaying(GAME, a)
        links.stoppedPlaying(GAME)
        assertNull(links.playing(GAME))
    }

    private companion object {
        const val GAME = "game"

        fun link(name: String) = Stream(
            name = name,
            title = null,
            description = null,
            url = "https://example.invalid/$name.m3u8",
            ytId = null,
            infoHash = null,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = null,
            addonName = "addon",
            addonLogo = null
        )
    }
}
