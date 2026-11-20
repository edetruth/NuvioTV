package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.sportslive.SportsLiveMyGamesToday
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home's quiet "2 games tonight · 1 ready" line (live.20 1b extra a): when it shows. */
class SportsLiveHomeTodayLineTest {

    private val liveNow = SportsLiveHomeRow.ADDON_ID to SportsLiveHomeRow.CATALOG_ID

    @Test
    fun `shown above the Live now row on a day with my games`() {
        assertTrue(SportsLiveHomeTodayRule.shown(SportsLiveMyGamesToday(games = 2, ready = 1), liveNow.first, liveNow.second))
        assertTrue(SportsLiveHomeTodayRule.shown(SportsLiveMyGamesToday(games = 1, ready = 0), liveNow.first, liveNow.second))
    }

    @Test
    fun `never on a day without my games`() {
        assertFalse(SportsLiveHomeTodayRule.shown(null, liveNow.first, liveNow.second))
        assertFalse(SportsLiveHomeTodayRule.shown(SportsLiveMyGamesToday(games = 0, ready = 0), liveNow.first, liveNow.second))
    }

    @Test
    fun `never above any other row`() {
        val today = SportsLiveMyGamesToday(games = 2, ready = 1)
        assertFalse(SportsLiveHomeTodayRule.shown(today, "com.example.addon", "popular"))
        assertFalse(SportsLiveHomeTodayRule.shown(today, SportsLiveHomeRow.ADDON_ID, "catch_up"))
        assertFalse(SportsLiveHomeTodayRule.shown(today, null, null))
    }
}
