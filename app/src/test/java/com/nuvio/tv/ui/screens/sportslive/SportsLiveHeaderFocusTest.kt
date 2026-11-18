package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.ui.screens.sportslive.SportsLiveHeaderFocus.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** C2, #79: the View button leads the filter row on Live now and Upcoming: tabs → View → chips → content. */
class SportsLiveHeaderFocusTest {

    @Test
    fun `the View button is offered on Live now and Upcoming only`() {
        for (tab in SportsLiveTab.entries) {
            val shown = SportsLiveHeaderFocus.of(tab, filterCount = 3).viewShown
            assertEquals("$tab", tab == SportsLiveTab.LIVE_NOW || tab == SportsLiveTab.UPCOMING, shown)
        }
    }

    @Test
    fun `focus goes tabs, then View, then the chips, then the content`() {
        val header = SportsLiveHeaderFocus.of(SportsLiveTab.LIVE_NOW, filterCount = 3)
        assertTrue(header.rowShown)
        assertEquals(Stop.VIEW, header.tabsDown)
        assertEquals(Stop.TABS, header.viewUp)
        assertEquals(Stop.CHIPS, header.viewRight)
        assertEquals(Stop.CONTENT, header.viewDown)
        // Up from the content lands on the selected chip, View one step to its left.
        assertEquals(Stop.CHIPS, header.contentUp)
    }

    @Test
    fun `with one sport the row still shows, holding View alone`() {
        val header = SportsLiveHeaderFocus.of(SportsLiveTab.UPCOMING, filterCount = 1)
        assertTrue(header.rowShown)
        assertFalse(header.chipsShown)
        assertEquals(Stop.VIEW, header.tabsDown)
        assertNull(header.viewRight)
        assertEquals(Stop.VIEW, header.contentUp)
    }

    @Test
    fun `Channels and Replays keep the chips without View, Results has neither`() {
        val channels = SportsLiveHeaderFocus.of(SportsLiveTab.CHANNELS, filterCount = 2)
        assertFalse(channels.viewShown)
        assertEquals(Stop.CHIPS, channels.tabsDown)
        assertEquals(Stop.CHIPS, channels.contentUp)
        val replays = SportsLiveHeaderFocus.of(SportsLiveTab.REPLAYS, filterCount = 1)
        assertFalse(replays.rowShown)
        assertEquals(Stop.CONTENT, replays.tabsDown)
        assertEquals(Stop.TABS, replays.contentUp)
        assertFalse(SportsLiveHeaderFocus.of(SportsLiveTab.RESULTS, filterCount = 5).rowShown)
    }

    @Test
    fun `a game that is over loses the addon's LIVE NOW line, nothing else`() {
        assertEquals("Toronto Raptors vs New York Knicks", withoutLiveMarkers("Toronto Raptors vs New York Knicks\nLIVE NOW"))
        assertEquals("Mexico vs Jamaica", withoutLiveMarkers("Mexico vs Jamaica\n🔴 Live"))
        assertEquals("Live coverage from the arena", withoutLiveMarkers("Live coverage from the arena"))
    }
}
