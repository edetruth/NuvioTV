package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.ui.screens.sportslive.SportsLiveFilterKey.LEFT
import com.nuvio.tv.ui.screens.sportslive.SportsLiveFilterKey.OK
import com.nuvio.tv.ui.screens.sportslive.SportsLiveFilterKey.RIGHT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveFilterControlTest {

    private val myTeams = SportsLiveUiState.MY_TEAMS_FILTER
    private val values = listOf(myTeams, null, "Basketball", "Football", "Soccer")

    /** Presses [keys] in turn from [start]; returns the last state and every action. */
    private fun press(start: SportsLiveFilterState, vararg keys: SportsLiveFilterKey): Pair<SportsLiveFilterState, List<SportsLiveFilterAction>> {
        var state = start
        val actions = keys.map { key ->
            val (next, action) = state.onKey(key)
            state = next
            action
        }
        return state to actions
    }

    @Test
    fun `left and right move the cursor and leave the selection alone`() {
        val (state, actions) = press(SportsLiveFilterState(values, selected = null), RIGHT, RIGHT)
        assertEquals("Football", state.cursor)
        assertEquals(null, state.selected)
        assertEquals(listOf(SportsLiveFilterAction.Stay, SportsLiveFilterAction.Stay), actions)
    }

    @Test
    fun `OK selects the option under the cursor`() {
        val (state, actions) = press(SportsLiveFilterState(values, selected = null), RIGHT, OK)
        assertEquals("Basketball", state.selected)
        assertEquals(SportsLiveFilterAction.Select("Basketball"), actions.last())
    }

    @Test
    fun `OK on the selected option opens the list`() {
        val (state, actions) = press(SportsLiveFilterState(values, selected = "Soccer"), OK)
        assertEquals("Soccer", state.selected)
        assertEquals(SportsLiveFilterAction.OpenList, actions.single())
    }

    @Test
    fun `left on the first option and right on the last leave the control`() {
        val first = SportsLiveFilterState(values, selected = myTeams)
        assertEquals(SportsLiveFilterAction.ExitLeft, first.onKey(LEFT).second)
        assertEquals(first, first.onKey(LEFT).first)
        val last = SportsLiveFilterState(values, selected = "Soccer")
        assertEquals(SportsLiveFilterAction.ExitRight, last.onKey(RIGHT).second)
    }

    @Test
    fun `selecting My teams from All`() {
        val (state, actions) = press(SportsLiveFilterState(values, selected = null), LEFT, OK)
        assertEquals(myTeams, state.selected)
        assertEquals(SportsLiveFilterAction.Select(myTeams), actions.last())
    }

    @Test
    fun `losing focus puts the cursor back on the selection`() {
        val (moved, _) = press(SportsLiveFilterState(values, selected = null), RIGHT, RIGHT, RIGHT)
        assertEquals("Soccer", moved.cursor)
        assertEquals(null, moved.blurred().cursor)
    }

    @Test
    fun `new values keep the cursor while it is listed, else it goes to the selection`() {
        val state = SportsLiveFilterState(values, selected = null, cursor = "Soccer")
        assertEquals("Soccer", state.updated(values - "Football", null).cursor)
        assertEquals("Basketball", state.updated(listOf(null, "Basketball"), "Basketball").cursor)
    }

    @Test
    fun `a selection missing from the values starts the cursor on the first option`() {
        val state = SportsLiveFilterState(listOf(null, "Soccer"), selected = "Hockey")
        assertEquals(0, state.cursorIndex)
        assertEquals(SportsLiveFilterAction.Select(null), state.onKey(OK).second)
    }

    @Test
    fun `no options do nothing`() {
        val empty = SportsLiveFilterState(emptyList(), selected = null)
        listOf(LEFT, RIGHT, OK).forEach { assertEquals(SportsLiveFilterAction.Stay, empty.onKey(it).second) }
    }

    @Test
    fun `options lead with My teams then All then the rest`() {
        val options = sportsLiveFilterOptions(listOf(myTeams, "Basketball", "Soccer"), "★ My teams", "All")
        assertEquals(listOf(myTeams, null, "Basketball", "Soccer"), options.map { it.value })
        assertEquals(listOf("★ My teams", "All", "Basketball", "Soccer"), options.map { it.label })
        assertTrue(options.first().gold)
        assertFalse(options.drop(1).any { it.gold })
    }

    @Test
    fun `options without followed teams start with All`() {
        val options = sportsLiveFilterOptions(listOf("Football"), "★ My teams", "All")
        assertEquals(listOf(null, "Football"), options.map { it.value })
    }

    @Test
    fun `the header filter shows on Upcoming, Channels and Replays with two or more values`() {
        assertTrue(sportsLiveHeaderFilterShown(SportsLiveTab.UPCOMING, 2))
        assertTrue(sportsLiveHeaderFilterShown(SportsLiveTab.CHANNELS, 3))
        assertTrue(sportsLiveHeaderFilterShown(SportsLiveTab.REPLAYS, 2))
        assertFalse(sportsLiveHeaderFilterShown(SportsLiveTab.UPCOMING, 1))
        assertFalse(sportsLiveHeaderFilterShown(SportsLiveTab.LIVE_NOW, 5))
        assertFalse(sportsLiveHeaderFilterShown(SportsLiveTab.RESULTS, 5))
    }
}
