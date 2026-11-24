package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #486 (TV QA): the stage hands the mini player its media box's last known place as it docks, not only on a layout pass. */
class SportsLiveMiniSlotTest {

    @Test
    fun `the box's last place goes to the mini player once it docks, nothing while it does not`() {
        val box = SportsLiveMiniSlot(1104, 148, 720, 405)
        // Laid out before the mini player started (Back from the player): kept, not handed over yet.
        assertNull(sportsLiveMiniSlotToReport(docked = false, bounds = box))
        // The mini player docks with no layout pass since: the kept place is handed over.
        assertEquals(box, sportsLiveMiniSlotToReport(docked = true, bounds = box))
        assertNull(sportsLiveMiniSlotToReport(docked = true, bounds = null))
    }
}
