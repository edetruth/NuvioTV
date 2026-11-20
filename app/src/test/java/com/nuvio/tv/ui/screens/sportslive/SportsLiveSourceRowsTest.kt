package com.nuvio.tv.ui.screens.sportslive

import org.junit.Assert.assertEquals
import org.junit.Test

/** live.20: the Sources panel's groups and clean names for a game. */
class SportsLiveSourceRowsTest {

    @Test
    fun `each group gets one header, over its first row, in list order`() {
        // Playing (pinned), two links, one failed, then two notices.
        val notices = setOf(4, 5)
        val failed = setOf(3)
        val headers = sportsLiveSourceGroupHeaders(6, currentIndex = 0, isNotice = { it in notices }, isFailed = { it in failed })
        assertEquals(
            mapOf(
                0 to SportsLiveSourceGroup.NOW_PLAYING,
                1 to SportsLiveSourceGroup.LINKS,
                3 to SportsLiveSourceGroup.DIDNT_WORK,
                4 to SportsLiveSourceGroup.NOTICES
            ),
            headers
        )
    }

    @Test
    fun `nothing playing and nothing failed is one Links group`() {
        assertEquals(
            mapOf(0 to SportsLiveSourceGroup.LINKS),
            sportsLiveSourceGroupHeaders(3, currentIndex = -1, isNotice = { false }, isFailed = { false })
        )
    }

    @Test
    fun `source names read in words, without emoji, pipes or a tv suffix`() {
        assertEquals("Source A · HD", sportsLivePlainSourceName("⚡ Source A | HD"))
        assertEquals("Source B", sportsLivePlainSourceName("🏆Source B (tv)"))
        assertEquals("English 1080p", sportsLivePlainSourceName("English   1080p"))
        // Only symbols: the raw name, never an empty chip.
        assertEquals("⚡", sportsLivePlainSourceName(" ⚡ "))
    }
}
