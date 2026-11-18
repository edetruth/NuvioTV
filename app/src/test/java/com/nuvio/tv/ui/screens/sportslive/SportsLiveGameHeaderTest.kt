package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.scores.StandingsRow
import com.nuvio.tv.core.sportslive.scores.StandingsTable
import com.nuvio.tv.ui.screens.sportslive.GameHeaderButton.FOLLOW_AWAY
import com.nuvio.tv.ui.screens.sportslive.GameHeaderButton.FOLLOW_HOME
import com.nuvio.tv.ui.screens.sportslive.GameHeaderButton.REPLAY
import com.nuvio.tv.ui.screens.sportslive.GameHeaderButton.REVEAL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveGameHeaderTest {

    @Test
    fun `hidden score with a replay - Reveal, then Watch replay, then both Follow buttons on one row`() {
        val rows = gameHeaderRows(showsScore = false, hasReplay = true)

        assertEquals(listOf(listOf(REVEAL), listOf(REPLAY), listOf(FOLLOW_AWAY, FOLLOW_HOME)), rows)
        assertEquals(REVEAL, gameHeaderFirstFocus(rows))
    }

    @Test
    fun `first focus is Watch replay once the score shows, else the away Follow`() {
        assertEquals(REPLAY, gameHeaderFirstFocus(gameHeaderRows(showsScore = true, hasReplay = true)))
        assertEquals(FOLLOW_AWAY, gameHeaderFirstFocus(gameHeaderRows(showsScore = true, hasReplay = false)))
        assertEquals(REVEAL, gameHeaderFirstFocus(gameHeaderRows(showsScore = false, hasReplay = false)))
    }

    @Test
    fun `only the Follow row leaves the header on Down, so Follow home is reachable`() {
        val rows = gameHeaderRows(showsScore = false, hasReplay = true)

        assertFalse(gameHeaderDownLeaves(rows, REVEAL))
        assertFalse(gameHeaderDownLeaves(rows, REPLAY))
        assertTrue(gameHeaderDownLeaves(rows, FOLLOW_AWAY))
        assertTrue(gameHeaderDownLeaves(rows, FOLLOW_HOME))
        // Every button is laid out once.
        assertEquals(4, rows.flatten().toSet().size)
    }

    @Test
    fun `two division tables sit side by side, one stands alone, three go two then one`() {
        val southeast = table("Southeast")
        val atlantic = table("Atlantic")
        val central = table("Central")

        assertEquals(listOf(listOf(southeast, atlantic)), standingsColumns(listOf(southeast, atlantic)))
        assertEquals(listOf(listOf(southeast)), standingsColumns(listOf(southeast)))
        assertEquals(listOf(listOf(southeast, atlantic), listOf(central)), standingsColumns(listOf(southeast, atlantic, central)))
        assertEquals(emptyList<List<StandingsTable>>(), standingsColumns(emptyList()))
    }

    private fun table(title: String) = StandingsTable(
        title = title,
        columns = listOf("W", "L"),
        rows = listOf(StandingsRow(team = "$title team", values = listOf("1", "2"), inGame = true))
    )
}
