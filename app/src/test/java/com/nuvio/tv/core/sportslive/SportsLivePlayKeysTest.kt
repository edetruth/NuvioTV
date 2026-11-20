package com.nuvio.tv.core.sportslive

import org.junit.Assert.assertEquals
import org.junit.Test

/** "OK on live" (research Q19) and "At tip-off" (Q17): safe defaults, read back by name. */
class SportsLivePlayKeysTest {

    @Test
    fun `OK on live shows the controls unless Pause was picked`() {
        assertEquals(SportsLiveOkOnLive.SHOW_CONTROLS, SportsLiveOkOnLive.fromStored(null))
        assertEquals(SportsLiveOkOnLive.SHOW_CONTROLS, SportsLiveOkOnLive.fromStored("junk"))
        assertEquals(SportsLiveOkOnLive.PAUSE, SportsLiveOkOnLive.fromStored("PAUSE"))
    }

    @Test
    fun `at tip-off reminds unless Switch automatically was picked`() {
        assertEquals(SportsLiveTipOff.REMIND, SportsLiveTipOff.fromStored(null))
        assertEquals(SportsLiveTipOff.REMIND, SportsLiveTipOff.fromStored("junk"))
        assertEquals(SportsLiveTipOff.SWITCH, SportsLiveTipOff.fromStored("SWITCH"))
    }
}
