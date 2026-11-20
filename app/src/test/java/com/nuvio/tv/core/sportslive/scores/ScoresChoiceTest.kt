package com.nuvio.tv.core.sportslive.scores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Research item 5: Show / Hide until I reveal / Never show, with "Hide scores for my teams only". */
class ScoresChoiceTest {
    private val show = ScoresMode.SHOW
    private val hide = ScoresMode.HIDE_UNTIL_FOCUSED
    private val never = ScoresMode.OFF

    @Test
    fun `every stored pair reads as a choice without changing what is stored`() {
        assertEquals(ScoresChoice(show), ScoresChoice.of(show, myTeams = false))
        // Show plus the old "my teams" switch always meant: only my teams' games hide.
        assertEquals(ScoresChoice(hide, myTeamsOnly = true), ScoresChoice.of(show, myTeams = true))
        assertEquals(ScoresChoice(hide), ScoresChoice.of(hide, myTeams = false))
        assertEquals(ScoresChoice(hide), ScoresChoice.of(hide, myTeams = true))
        assertEquals(ScoresChoice(never), ScoresChoice.of(never, myTeams = false))
        assertEquals(ScoresChoice(never), ScoresChoice.of(never, myTeams = true))
    }

    @Test
    fun `my teams only is stored as Show with my teams protected, so older versions read the same`() {
        val mine = ScoresChoice(hide, myTeamsOnly = true)
        assertEquals(show, mine.storedMode)
        assertTrue(mine.storedMyTeams)
        assertEquals(mine, ScoresChoice.of(mine.storedMode, mine.storedMyTeams))
    }

    @Test
    fun `every other choice stores its own mode with the switch off and reads back the same`() {
        for (choice in listOf(ScoresChoice(show), ScoresChoice(hide), ScoresChoice(never))) {
            assertEquals(choice.mode, choice.storedMode)
            assertFalse(choice.storedMyTeams)
            assertEquals(choice, ScoresChoice.of(choice.storedMode, choice.storedMyTeams))
        }
        // The scope means nothing outside Hide until I reveal.
        assertEquals(show, ScoresChoice(show, myTeamsOnly = true).storedMode)
        assertFalse(ScoresChoice(never, myTeamsOnly = true).storedMyTeams)
    }

    @Test
    fun `the my teams switch turns hiding on for my teams, and off widens it to every game`() {
        assertEquals(ScoresChoice(hide, myTeamsOnly = true), ScoresChoice(show).withMyTeamsOnly(true))
        assertEquals(ScoresChoice(hide, myTeamsOnly = true), ScoresChoice(hide).withMyTeamsOnly(true))
        assertEquals(ScoresChoice(hide), ScoresChoice(hide, myTeamsOnly = true).withMyTeamsOnly(false))
    }

    @Test
    fun `the scores picker keeps the scope only for Hide until I reveal`() {
        val mine = ScoresChoice(hide, myTeamsOnly = true)
        assertEquals(mine, mine.withMode(hide))
        assertEquals(ScoresChoice(show), mine.withMode(show))
        assertEquals(ScoresChoice(never), mine.withMode(never))
        assertEquals(ScoresChoice(hide), ScoresChoice(show).withMode(hide))
    }
}
