package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.domain.model.Stream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** N2: a source's notices are info rows listed after its streams, in every Sports & Live stream list. */
class SportsLiveSourceNoticesTest {

    @Before
    fun setUp() = SportsLiveExtraSources.reset()

    @After
    fun tearDown() = SportsLiveExtraSources.reset()

    private val real = stream("Sports 1 (Strong)", "https://cdn.example.invalid/play/abc123")
    private val backup = stream("Sports 1 backup", "https://cdn.example.invalid/live/index.m3u8")
    private val tip = stream("Tip: Use an external player", "https://addon.example.invalid/configure")

    @Test
    fun `notices go after the streams, each part in its own order`() {
        assertEquals(listOf(1, 3, 0, 2), sportsLiveNoticesLast(listOf(0, 1, 2, 3)) { it % 2 == 0 })
        val none = listOf(1, 3)
        assertSame(none, sportsLiveNoticesLast(none) { false })
    }

    @Test
    fun `a sports game's Stream screen lists the tip last, other titles stay as they are`() {
        val streams = listOf(tip, real, backup)
        // Not a Sports & Live playback: the upstream list, untouched.
        assertEquals(streams, sportsLiveStreamScreenOrder("movie-id", streams))
        assertFalse(sportsLiveSourceNotice("movie-id", tip))

        SportsLiveExtraSources.markSportsPlayback("game-id")
        assertEquals(listOf(real, backup, tip), sportsLiveStreamScreenOrder("game-id", streams))
        assertTrue(sportsLiveSourceNotice("game-id", tip))
        assertFalse(sportsLiveSourceNotice("game-id", real))
    }

    private fun stream(name: String, url: String) = Stream(
        name = name, title = null, description = null, url = url, ytId = null, infoHash = null, fileIdx = null,
        externalUrl = null, behaviorHints = null, addonName = "Sports", addonLogo = null
    )
}
