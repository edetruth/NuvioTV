package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.ui.screens.sportslive.SportsLiveFocusMove.DOWN
import com.nuvio.tv.ui.screens.sportslive.SportsLiveFocusMove.RIGHT
import com.nuvio.tv.ui.screens.sportslive.SportsLiveFocusMove.UP
import com.nuvio.tv.ui.screens.sportslive.SportsLiveFocusSpot.HEADER_CONTROL
import com.nuvio.tv.ui.screens.sportslive.SportsLiveFocusSpot.LIST_TOP
import com.nuvio.tv.ui.screens.sportslive.SportsLiveFocusSpot.STAGE
import com.nuvio.tv.ui.screens.sportslive.SportsLiveFocusSpot.STAGE_LAST
import com.nuvio.tv.ui.screens.sportslive.SportsLiveFocusSpot.TAB
import com.nuvio.tv.ui.screens.sportslive.SportsLiveFocusTarget.DEFAULT
import com.nuvio.tv.ui.screens.sportslive.SportsLiveFocusTarget.HEADER_CONTROLS
import com.nuvio.tv.ui.screens.sportslive.SportsLiveFocusTarget.LIST
import com.nuvio.tv.ui.screens.sportslive.SportsLiveFocusTarget.SELECTED_TAB
import com.nuvio.tv.ui.screens.sportslive.SportsLiveFocusTarget.STAGE_PRIMARY
import com.nuvio.tv.ui.screens.sportslive.SportsLiveFocusTarget.STAY
import com.nuvio.tv.ui.screens.sportslive.SportsLiveHeaderStop.DAY_NEWER
import com.nuvio.tv.ui.screens.sportslive.SportsLiveHeaderStop.DAY_OLDER
import com.nuvio.tv.ui.screens.sportslive.SportsLiveHeaderStop.FILTER
import com.nuvio.tv.ui.screens.sportslive.SportsLiveHeaderStop.REFRESH
import org.junit.Assert.assertEquals
import org.junit.Test

/** #170: the list column goes with the tabs, the stage column with the header controls. */
class SportsLiveColumnFocusTest {

    @Test
    fun `up from the top of the list goes to the selected tab, with or without a stage`() {
        assertEquals(SELECTED_TAB, sportsLiveColumnTarget(LIST_TOP, UP, stageShown = true))
        assertEquals(SELECTED_TAB, sportsLiveColumnTarget(LIST_TOP, UP, stageShown = false))
    }

    @Test
    fun `up from any stage button goes to the header controls`() {
        assertEquals(HEADER_CONTROLS, sportsLiveColumnTarget(STAGE, UP, stageShown = true))
        assertEquals(HEADER_CONTROLS, sportsLiveColumnTarget(STAGE_LAST, UP, stageShown = true))
    }

    @Test
    fun `with a docked mini player the stage goes up to it, and the mini on to the header controls`() {
        assertEquals(SportsLiveFocusTarget.DOCKED_MINI, sportsLiveColumnTarget(STAGE, UP, stageShown = true, miniDocked = true))
        assertEquals(SportsLiveFocusTarget.DOCKED_MINI, sportsLiveColumnTarget(STAGE_LAST, UP, stageShown = true, miniDocked = true))
        assertEquals(HEADER_CONTROLS, sportsLiveColumnTarget(SportsLiveFocusSpot.DOCKED_MINI, UP, stageShown = true, miniDocked = true))
        assertEquals(SELECTED_TAB, sportsLiveColumnTarget(LIST_TOP, UP, stageShown = true, miniDocked = true))
    }

    @Test
    fun `right at the stage's last button stays put, elsewhere it is left to the search`() {
        assertEquals(STAY, sportsLiveColumnTarget(STAGE_LAST, RIGHT, stageShown = true))
        assertEquals(DEFAULT, sportsLiveColumnTarget(STAGE, RIGHT, stageShown = true))
        assertEquals(DEFAULT, sportsLiveColumnTarget(LIST_TOP, RIGHT, stageShown = true))
    }

    @Test
    fun `down from a tab goes to the list`() {
        assertEquals(LIST, sportsLiveColumnTarget(TAB, DOWN, stageShown = true))
        assertEquals(LIST, sportsLiveColumnTarget(TAB, DOWN, stageShown = false))
    }

    @Test
    fun `down from a header control goes to the stage's primary, or the list with no stage`() {
        assertEquals(STAGE_PRIMARY, sportsLiveColumnTarget(HEADER_CONTROL, DOWN, stageShown = true))
        assertEquals(LIST, sportsLiveColumnTarget(HEADER_CONTROL, DOWN, stageShown = false))
    }

    @Test
    fun `the stage's up lands on the filter when shown, else on Refresh`() {
        assertEquals(FILTER, sportsLiveHeaderControlsStop(hasFilter = true))
        assertEquals(REFRESH, sportsLiveHeaderControlsStop(hasFilter = false))
    }

    @Test
    fun `right along the header walks the tab, Results' day arrows, then the controls`() {
        val walk = generateSequence(SportsLiveHeaderStop.TAB) { sportsLiveHeaderNeighbour(it, right = true, hasDate = true, hasFilter = true) }.toList()
        assertEquals(listOf(SportsLiveHeaderStop.TAB, DAY_OLDER, DAY_NEWER, FILTER, REFRESH), walk)
        val noDate = generateSequence(SportsLiveHeaderStop.TAB) { sportsLiveHeaderNeighbour(it, right = true, hasDate = false, hasFilter = false) }.toList()
        assertEquals(listOf(SportsLiveHeaderStop.TAB, REFRESH), noDate)
    }

    @Test
    fun `up from any Your teams card goes to the tab row, down to the list (user TV report)`() {
        assertEquals(SELECTED_TAB, sportsLiveColumnTarget(SportsLiveFocusSpot.STRIP, UP, stageShown = true))
        assertEquals(SELECTED_TAB, sportsLiveColumnTarget(SportsLiveFocusSpot.STRIP, UP, stageShown = false))
        assertEquals(LIST, sportsLiveColumnTarget(SportsLiveFocusSpot.STRIP, DOWN, stageShown = true))
        assertEquals(DEFAULT, sportsLiveColumnTarget(SportsLiveFocusSpot.STRIP, RIGHT, stageShown = true))
        // The tabs' Down still enters the content (the strip's first card while it shows: YourTeams.entry).
        assertEquals(LIST, sportsLiveColumnTarget(TAB, DOWN, stageShown = true))
    }

    @Test
    fun `a strip card asks for the column's moves itself, only for up and down`() {
        assertEquals(UP, stripMoveOf(android.view.KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(DOWN, stripMoveOf(android.view.KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(null, stripMoveOf(android.view.KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(null, stripMoveOf(android.view.KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(null, stripMoveOf(android.view.KeyEvent.KEYCODE_DPAD_CENTER))
    }
}
