package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.FinalNote
import com.nuvio.tv.core.sportslive.scores.ResultRow
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoreTeam
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SportsLiveResultsTest {

    @Test
    fun `hide until focused reveals a row's score after 1 s of focus`() = runTest {
        val reveal = ResultSpoilerReveal(backgroundScope)
        reveal.onFocus("a")
        advanceTimeBy(999); runCurrent()
        assertFalse(ResultSpoilerReveal.showsScore(ScoresMode.HIDE_UNTIL_FOCUSED, reveal.revealed.value, "a"))
        advanceTimeBy(1); runCurrent()
        assertTrue(ResultSpoilerReveal.showsScore(ScoresMode.HIDE_UNTIL_FOCUSED, reveal.revealed.value, "a"))
        // Revealed stays revealed after focus moves on.
        reveal.onFocus("b")
        runCurrent()
        assertTrue(ResultSpoilerReveal.showsScore(ScoresMode.HIDE_UNTIL_FOCUSED, reveal.revealed.value, "a"))
    }

    @Test
    fun `moving on before 1 s reveals nothing`() = runTest {
        val reveal = ResultSpoilerReveal(backgroundScope)
        reveal.onFocus("a")
        advanceTimeBy(600); runCurrent()
        reveal.onFocus("b")
        advanceTimeBy(600); runCurrent()
        reveal.onFocus(null)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(emptySet<String>(), reveal.revealed.value)
    }

    @Test
    fun `refocusing the same row does not restart its timer`() = runTest {
        val reveal = ResultSpoilerReveal(backgroundScope)
        reveal.onFocus("a")
        advanceTimeBy(700); runCurrent()
        reveal.onFocus("a")
        advanceTimeBy(300); runCurrent()
        assertEquals(setOf("a"), reveal.revealed.value)
    }

    @Test
    fun `Show shows every score at once and Off none`() {
        assertTrue(ResultSpoilerReveal.showsScore(ScoresMode.SHOW, emptySet(), "a"))
        assertFalse(ResultSpoilerReveal.showsScore(ScoresMode.HIDE_UNTIL_FOCUSED, emptySet(), "a"))
        assertFalse(ResultSpoilerReveal.showsScore(ScoresMode.OFF, setOf("a"), "a"))
    }

    private fun row(id: String, sport: String, followed: Boolean = false): ResultRow {
        val team = ScoreTeam(null, null, "Team $id", null, null, null, null, 1, emptyList())
        val event = ScoreSnapshot(
            eventId = id, league = EspnLeague(sport, "x"), startMs = 0L, state = ScoreState.POST, clockText = "Final",
            isFinal = true, home = team, away = team
        )
        return ResultRow(event, followed, FinalNote.FT)
    }

    @Test
    fun `filters lead with my teams when a followed team played, then the sports present`() {
        val rows = listOf(row("1", "soccer"), row("2", "basketball", followed = true), row("3", "soccer"))
        assertEquals(listOf(SportsLiveUiState.MY_TEAMS_FILTER, "basketball", "soccer"), resultFilters(rows))
        assertEquals(listOf("basketball", "soccer"), resultFilters(rows.map { it.copy(followed = false) }))
        assertEquals(listOf("2"), filterResults(rows, SportsLiveUiState.MY_TEAMS_FILTER).map { it.key })
        assertEquals(listOf("1", "3"), filterResults(rows, "soccer").map { it.key })
        assertEquals(rows, filterResults(rows, null))
    }
}
