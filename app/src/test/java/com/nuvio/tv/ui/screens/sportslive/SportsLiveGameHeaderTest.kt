package com.nuvio.tv.ui.screens.sportslive

import android.view.KeyEvent
import com.nuvio.tv.core.sportslive.scores.GameSection
import com.nuvio.tv.core.sportslive.scores.GameSection.BOX_SCORE
import com.nuvio.tv.core.sportslive.scores.GameSection.GAME
import com.nuvio.tv.core.sportslive.scores.GameSection.HIGHLIGHTS
import com.nuvio.tv.core.sportslive.scores.StandingsRow
import com.nuvio.tv.core.sportslive.scores.StandingsTable
import com.nuvio.tv.ui.screens.sportslive.GameAction.REMIND
import com.nuvio.tv.ui.screens.sportslive.GameAction.REPLAY
import com.nuvio.tv.ui.screens.sportslive.GameAction.REVEAL
import com.nuvio.tv.ui.screens.sportslive.GameAction.WATCH
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Game page's one action row and its text tabs (#134, phase 3). */
class SportsLiveGameHeaderTest {

    @Test
    fun `a live game a card plays - Watch is the primary`() {
        assertEquals(listOf(WATCH), gameActions(showsScore = true, hasReplay = false, canWatchLive = true))
    }

    @Test
    fun `an upcoming game - Remind me is the primary`() {
        assertEquals(listOf(REMIND), gameActions(showsScore = true, hasReplay = false, canRemind = true))
    }

    @Test
    fun `a final with a replay - Watch replay is the primary`() {
        assertEquals(listOf(REPLAY), gameActions(showsScore = true, hasReplay = true))
    }

    @Test
    fun `a final with no replay - only More shows`() {
        assertEquals(emptyList<GameAction>(), gameActions(showsScore = true, hasReplay = false))
    }

    @Test
    fun `scores hidden with a replay - Watch replay leads and Reveal sits beside it`() {
        assertEquals(listOf(REPLAY, REVEAL), gameActions(showsScore = false, hasReplay = true))
    }

    @Test
    fun `scores hidden without a replay - Reveal score is the primary`() {
        assertEquals(listOf(REVEAL), gameActions(showsScore = false, hasReplay = false))
        // A live game stays watchable, behind Reveal.
        assertEquals(listOf(REVEAL, WATCH), gameActions(showsScore = false, hasReplay = false, canWatchLive = true))
    }

    @Test
    fun `scores hidden on an upcoming game - Remind me keeps the lead, Reveal beside it`() {
        assertEquals(listOf(REMIND, REVEAL), gameActions(showsScore = false, hasReplay = false, canRemind = true))
    }

    @Test
    fun `every action is laid out once`() {
        listOf(true, false).forEach { shows ->
            listOf(true, false).forEach { replay ->
                val actions = gameActions(showsScore = shows, hasReplay = replay, canWatchLive = true, canRemind = true)
                assertEquals(actions.toSet().size, actions.size)
            }
        }
    }

    private val sections = listOf(GAME, BOX_SCORE, HIGHLIGHTS)

    @Test
    fun `left and right step through the tabs`() {
        assertEquals(GameTabKey.Move(BOX_SCORE), gameTabKey(sections, GAME, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(GameTabKey.Move(GAME), gameTabKey(sections, BOX_SCORE, KeyEvent.KEYCODE_DPAD_LEFT))
    }

    @Test
    fun `left on the first tab and right on the last stay on the tabs`() {
        assertEquals(GameTabKey.Stay, gameTabKey(sections, GAME, KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(GameTabKey.Stay, gameTabKey(sections, HIGHLIGHTS, KeyEvent.KEYCODE_DPAD_RIGHT))
    }

    @Test
    fun `up, down and OK are left to the usual focus handling`() {
        listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER).forEach { key ->
            assertEquals(GameTabKey.Pass, gameTabKey(sections, BOX_SCORE, key))
        }
    }

    @Test
    fun `a section that is gone moves to the first tab, and no tabs stay put`() {
        assertEquals(GameTabKey.Move(GAME), gameTabKey(sections, GameSection.STANDINGS, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(GameTabKey.Stay, gameTabKey(emptyList(), null, KeyEvent.KEYCODE_DPAD_RIGHT))
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
