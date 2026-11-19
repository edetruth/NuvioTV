package com.nuvio.tv.ui.screens.sportslive

import org.junit.Assert.assertEquals
import org.junit.Test

/** #157: the stage never reads the docked mini player's game and the selected one as one. */
class SportsLiveStageHeaderTest {

    @Test
    fun `with no mini player the stage is as always`() {
        assertEquals(SportsLiveStageHeader.NO_MINI, sportsLiveStageHeader(miniKey = null, selectedKey = "a|tennis"))
        assertEquals(SportsLiveStageHeader.NO_MINI, sportsLiveStageHeader(miniKey = null, selectedKey = null))
    }

    @Test
    fun `another game than the mini player's reads as Selected under the WATCHING video`() {
        assertEquals(SportsLiveStageHeader.SELECTED, sportsLiveStageHeader(miniKey = "a|u17", selectedKey = "a|tennis"))
        // The same game from another addon is another row.
        assertEquals(SportsLiveStageHeader.SELECTED, sportsLiveStageHeader(miniKey = "a|u17", selectedKey = "b|u17"))
        assertEquals(SportsLiveStageHeader.SELECTED, sportsLiveStageHeader(miniKey = "a|u17", selectedKey = null))
    }

    @Test
    fun `the mini player's own row merges into one stage`() {
        assertEquals(SportsLiveStageHeader.MERGED, sportsLiveStageHeader(miniKey = "a|u17", selectedKey = "a|u17"))
    }
}
