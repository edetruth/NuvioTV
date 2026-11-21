package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.FinalNote
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.ResultRow
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoreTeam
import com.nuvio.tv.core.sportslive.scores.SportsResults
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #79: chips select on OK (focus only highlights), carry counts, hide when empty; one View menu. */
class SportsLiveChipsTest {

    /**
     * A chip row as the screen drives it: focus moving along the row ([SportsLiveChipCursor.move]) and
     * OK on the focused chip ([sportsLiveChipClick], as every chip's onClick is built).
     */
    private class Row<T>(chips: List<T>, selected: T) {
        var cursor = SportsLiveChipCursor(chips, selected)
        var openedList = 0

        fun focus(step: Int) {
            cursor = cursor.move(step)
        }

        fun ok() {
            val focused = cursor.highlighted
            sportsLiveChipClick(
                isSelected = focused == cursor.selected,
                onSelect = { cursor = cursor.copy(selected = focused) },
                onOpenList = { openedList++ }
            )()
        }
    }

    private fun <T> assertOkSelectsAndFocusDoesNot(chips: List<T>, start: T) {
        val row = Row(chips, start)
        row.focus(1)
        row.focus(1)
        assertEquals("focus moved", chips[(chips.indexOf(start) + 2).coerceAtMost(chips.lastIndex)], row.cursor.highlighted)
        assertEquals("focus alone never selects", start, row.cursor.selected)
        row.ok()
        assertEquals("OK selects the focused chip", row.cursor.highlighted, row.cursor.selected)
        assertEquals(0, row.openedList)
        row.ok()
        assertEquals("OK on the selected chip opens the list", 1, row.openedList)
        assertEquals(row.cursor.highlighted, row.cursor.selected)
    }

    @Test
    fun `Live now chips select on OK, never on focus`() {
        val items = listOf(item("a", "Soccer", followed = true), item("b", "NBA"), item("c", "Tennis"))
        val counts = sportsLiveFilterCounts(items, SportsLiveItem::sport)
        val filters = sportsLiveShownFilters(listOf("NBA", "Soccer", "Tennis"), counts, null)
        val myTeams = SportsLiveUiState.MY_TEAMS_FILTER
        // The row as shown: ★ My teams, All, then each sport.
        val chips: List<String?> = listOf(myTeams, null) + filters.filter { it != myTeams }
        assertOkSelectsAndFocusDoesNot(chips, null)
    }

    @Test
    fun `Upcoming chips select on OK, never on focus`() {
        val items = listOf(item("a", "Soccer", isLive = false), item("b", "NBA", isLive = false))
        val filters = sportsLiveShownFilters(listOf("Soccer", "NBA"), sportsLiveFilterCounts(items, SportsLiveItem::sport), null)
        assertOkSelectsAndFocusDoesNot(listOf<String?>(null) + filters, null)
    }

    @Test
    fun `the player panel chips select on OK, never on Left or Right`() {
        val items = listOf(item("a", "Soccer"), item("b", "NBA"), item("c", "NFL"))
        assertOkSelectsAndFocusDoesNot(panelChips(items, null), null)
        // The highlight stays on the row at its ends.
        val atEnd = SportsLiveChipCursor(listOf(null, "NBA"), null).move(-1).move(5).move(5)
        assertEquals("NBA", atEnd.highlighted)
        assertNull(atEnd.selected)
    }

    @Test
    fun `chip counts follow the tab's list, my teams included`() {
        val items = listOf(
            item("a", "Soccer", followed = true),
            item("b", "Soccer"),
            item("c", "NBA", followed = true),
            item("d", "NBA"),
            item("e", "NBA"),
            item("f", null)
        )
        val counts = sportsLiveFilterCounts(items, SportsLiveItem::sport)
        assertEquals(6, counts[null])
        assertEquals(2, counts[SportsLiveUiState.MY_TEAMS_FILTER])
        assertEquals(2, counts["Soccer"])
        assertEquals(3, counts["NBA"])
        // Channels never count ★ My teams; the panel counts as the section does.
        assertNull(sportsLiveFilterCounts(items, SportsLiveItem::sport, myTeams = false)[SportsLiveUiState.MY_TEAMS_FILTER])
        // The panel counts one value per sport (V2 P3): "NBA" is Basketball.
        assertEquals(sportsLiveFilterCounts(items, { com.nuvio.tv.core.sportslive.SportsLiveSportKind.filterKeyOf(it) }), panelChipCounts(items))
        assertEquals(3, panelChipCounts(items)[sportKey("Basketball")])
        assertEquals("Soccer 2", sportsLiveChipText("Soccer", counts["Soccer"]))
        assertEquals("Edit", sportsLiveChipText("Edit", null))
    }

    @Test
    fun `Results Jump to counts follow the day's games`() {
        val rows = listOf(
            result("1", "basketball", "nba"),
            result("2", "basketball", "nba"),
            // College is not in the default My sports: under Other sports, not counted here.
            result("3", "basketball", "mens-college-basketball"),
            result("4", "soccer", "eng.1", followed = true)
        )
        val counts = resultsJumpEntries(rows, MySports.DEFAULT).associate { it.jump to it.count }
        assertEquals(3, counts[null])
        assertEquals(1, counts[ResultsJump.YourTeams])
        assertEquals(2, counts[ResultsJump.League(SportsResults.groupKey(rows[0].event))])
        assertEquals(1, counts[ResultsJump.League(SportsResults.groupKey(rows[3].event))])
        assertNull(counts[ResultsJump.League(SportsResults.groupKey(rows[2].event))])
        // No followed team played: no Your teams entry.
        assertTrue(resultsJumpEntries(rows.take(2), MySports.DEFAULT).none { it.jump == ResultsJump.YourTeams })
    }

    @Test
    fun `chips with no games hide, never All or the selected one`() {
        val items = listOf(item("a", "Soccer"), item("b", "NBA"))
        val counts = sportsLiveFilterCounts(items, SportsLiveItem::sport)
        // Tennis is a value with no games counted (its games are over): hidden.
        assertEquals(listOf("NBA", "Soccer"), sportsLiveShownFilters(listOf("Soccer", "NBA", "Tennis"), counts, null))
        // Selected, it stays (with 0) until the user moves.
        assertEquals(listOf("NBA", "Soccer", "Tennis"), sportsLiveShownFilters(listOf("Soccer", "NBA"), counts, "Tennis"))
        val myTeams = SportsLiveUiState.MY_TEAMS_FILTER
        assertEquals(listOf(myTeams, "NBA", "Soccer"), sportsLiveShownFilters(listOf("Soccer", "NBA"), counts, myTeams))
        val state = SportsLiveUiState(selectedFilter = "Tennis", filterCounts = counts)
        assertTrue(state.selectedFilterEmpty)
        assertFalse(state.copy(selectedFilter = null).selectedFilterEmpty)
        assertFalse(state.copy(selectedFilter = "NBA").selectedFilterEmpty)
    }

    @Test
    fun `my teams and my sports chips carry different icons`() {
        assertEquals(SportsLiveChipIcon.MY_TEAMS, sportsLiveChipIconOf(SportsLiveUiState.MY_TEAMS_FILTER))
        assertEquals(SportsLiveChipIcon.NONE, sportsLiveChipIconOf(null))
        assertEquals(SportsLiveChipIcon.NONE, sportsLiveChipIconOf("Soccer"))
        assertTrue(SportsLiveChipIcon.MY_TEAMS != SportsLiveChipIcon.MY_SPORTS)
    }

    private fun result(id: String, sport: String, league: String, followed: Boolean = false): ResultRow {
        val team = ScoreTeam(null, null, "Team $id", null, null, null, null, 1, emptyList())
        val event = ScoreSnapshot(
            eventId = id, league = EspnLeague(sport, league), startMs = 0L, state = ScoreState.POST, clockText = "Final",
            isFinal = true, home = team, away = team
        )
        return ResultRow(event, followed, FinalNote.FT)
    }

    private fun item(id: String, sport: String?, isLive: Boolean = true, followed: Boolean = false) = SportsLiveItem(
        meta = MetaPreview(
            id = id,
            type = ContentType.UNKNOWN,
            rawType = "tv",
            name = id,
            poster = null,
            posterShape = PosterShape.LANDSCAPE,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList()
        ),
        addonId = "addon",
        addonName = "Sports",
        addonBaseUrl = "https://addon",
        catalogKey = "addon:tv:live",
        catalogName = "Live",
        kind = SportsLiveKind.EVENTS,
        isLive = isLive,
        isChannel = false,
        startMs = null,
        sport = sport,
        followed = followed
    )
}
