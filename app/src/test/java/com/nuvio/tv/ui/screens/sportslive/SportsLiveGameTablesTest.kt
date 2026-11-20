package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.scores.StandingsRow
import com.nuvio.tv.core.sportslive.scores.StandingsTable
import org.junit.Assert.assertEquals
import org.junit.Test

/** live.20: box score column widths (BUG1) and where Standings open (BUG18). */
class SportsLiveGameTablesTest {

    @Test
    fun `a made-attempted total gets a column wide enough for every character (BUG1)`() {
        val columns = tableColumns(
            labels = listOf("MIN", "FG", "3PT", "REB"),
            rows = listOf(listOf("34", "6-9", "1-3", "8"), listOf("35", "7-9", "4-6", "3")),
            totals = listOf("200", "28-48", "11-22", "36")
        )
        assertEquals(listOf("FG", "6-9", "7-9", "28-48"), columns[1])
        val widths = tableColumnWidthsDp(columns, minDp = 44)
        // "28-48": five characters, wider than the old fixed 44 dp; short columns keep the minimum.
        assertEquals(5 * CELL_CHAR_DP + CELL_PAD_DP, widths[1])
        assertEquals(5 * CELL_CHAR_DP + CELL_PAD_DP, widths[2])
        assertEquals(44, widths[0])
        assertEquals(44, widths[3])
    }

    @Test
    fun `other sports' long values widen their columns by the same rule`() {
        val widths = tableColumnWidthsDp(listOf(listOf("C/ATT", "22/31"), listOf("TOI", "21:34"), listOf("G", "1")), minDp = 40)
        assertEquals(listOf(51, 51, 40), widths)
        // A column with no value at all keeps the minimum.
        assertEquals(listOf(40), tableColumnWidthsDp(listOf(emptyList()), minDp = 40))
    }

    private fun table(size: Int, inGame: Set<Int>) = StandingsTable(
        title = "Conference",
        columns = listOf("W-L"),
        rows = List(size) { StandingsRow("Team $it", listOf("0-0"), inGame = it in inGame, teamId = "$it") }
    )

    @Test
    fun `standings open at the top when the game's team is near it`() {
        assertEquals(StandingsEntry(group = 0, table = 1, row = 0, scrollIndex = 0), standingsEntry(listOf(table(16, setOf(9)), table(16, setOf(0)))))
        assertEquals(StandingsEntry(0, 0, 3, 0), standingsEntry(listOf(table(16, setOf(3)))))
    }

    @Test
    fun `standings scroll to a team far down its table, two rows above it (BUG18)`() {
        // One group: its heads item 0, row r is item 1 + r. Row 11 → start at item 10 (two rows above).
        assertEquals(StandingsEntry(0, 0, 11, 10), standingsEntry(listOf(table(16, setOf(11)))))
        // The highest of the two teams across the paired tables wins.
        assertEquals(StandingsEntry(0, 1, 8, 7), standingsEntry(listOf(table(16, setOf(12)), table(14, setOf(8)))))
    }

    @Test
    fun `standings with no team of the game in the first group look further, else open at the top`() {
        // Group 0 (two tables of 8: heads + 8 rows = items 0..8), group 1 starts at item 9; its row 6 → item 9 + 1 + 6 - 2.
        val tables = listOf(table(8, emptySet()), table(8, emptySet()), table(10, setOf(6)))
        assertEquals(StandingsEntry(1, 0, 6, 14), standingsEntry(tables))
        assertEquals(StandingsEntry(0, 0, 0, 0), standingsEntry(listOf(table(5, emptySet()))))
        assertEquals(StandingsEntry(0, 0, 0, 0), standingsEntry(emptyList()))
    }
}
