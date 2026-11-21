package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveChannelRegions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Channels stage's country line (channels-redesign.md): the country in words and how many addons list the channel. */
class SportsLiveChannelStageCountryTest {

    private val label: (String) -> String = { if (it == "US") "United States" else it }
    private val sources: (Int) -> String = { "$it sources" }

    @Test
    fun `the country and the sources when more than one addon lists it`() {
        assertEquals("United States · 3 sources", channelCountryLine("US", 3, label, sources))
        assertEquals("United States", channelCountryLine("US", 1, label, sources))
    }

    @Test
    fun `no country listed says nothing of the country, and nothing at all leaves no line`() {
        assertEquals("2 sources", channelCountryLine(SportsLiveChannelRegions.OTHER, 2, label, sources))
        assertNull(channelCountryLine(SportsLiveChannelRegions.OTHER, 1, label, sources))
        assertNull(channelCountryLine("XX", 1, { "" }, sources))
    }
}
