package com.nuvio.tv.ui.screens.sportslive.dual

import org.junit.Assert.assertEquals
import org.junit.Test

class SportsLiveDualNavTest {

    @Test
    fun `with the section on the stack everything above it goes`() {
        for (dual in listOf(true, false)) {
            for (stream in listOf(true, false)) {
                assertEquals(
                    SportsLiveDualPopTarget.ABOVE_SECTION,
                    sportsLiveDualEntryPopTarget(sectionOnStack = true, dualOnStack = dual, previousIsStreamScreen = stream)
                )
            }
        }
    }

    @Test
    fun `from Home only the player and what belongs to it go`() {
        assertEquals(SportsLiveDualPopTarget.DUAL, sportsLiveDualEntryPopTarget(false, dualOnStack = true, previousIsStreamScreen = true))
        assertEquals(SportsLiveDualPopTarget.STREAM, sportsLiveDualEntryPopTarget(false, dualOnStack = false, previousIsStreamScreen = true))
        assertEquals(SportsLiveDualPopTarget.PLAYER, sportsLiveDualEntryPopTarget(false, dualOnStack = false, previousIsStreamScreen = false))
    }

    @Test
    fun `full screen from a dual pane leaves dual behind, whatever opened it`() {
        // Opened from the section: Back from full screen returns to the section.
        assertEquals(
            listOf("home", "section", "player"),
            sportsLiveStackAfterDualHandOff(listOf("home", "section", "dual"), "dual", "player")
        )
        // Opened from Home's Live now row (intentional): Back from full screen returns to Home, not dual.
        assertEquals(listOf("home", "player"), sportsLiveStackAfterDualHandOff(listOf("home", "dual"), "dual", "player"))
        // A pane needing the Stream screen: that screen replaces dual the same way.
        assertEquals(listOf("home", "stream"), sportsLiveStackAfterDualHandOff(listOf("home", "dual"), "dual", "stream"))
    }

    @Test
    fun `leaving dual screen for full screen is never faded, other screens fade as before`() {
        val dual = com.nuvio.tv.ui.navigation.Screen.SportsLiveDual.route
        assertEquals(true, sportsLiveDualLeavesAtOnce(dual, dual))
        assertEquals(false, sportsLiveDualLeavesAtOnce("player", dual))
        assertEquals(false, sportsLiveDualLeavesAtOnce(null, dual))
    }
}
