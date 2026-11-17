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
}
