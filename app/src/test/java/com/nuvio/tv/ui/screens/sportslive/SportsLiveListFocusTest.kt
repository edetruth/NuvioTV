package com.nuvio.tv.ui.screens.sportslive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SportsLiveListFocusTest {

    private val keys = listOf("a", "b", "c", "d", "e", "f")

    @Test
    fun `entry lands on the last focused card while it is on screen`() {
        assertEquals("c", sportsLiveListEntryKey(keys, focusedKey = "c", visibleIndices = listOf(1, 2, 3)))
    }

    @Test
    fun `entry lands on the first card on screen when the focused one scrolled away or is gone`() {
        assertEquals("d", sportsLiveListEntryKey(keys, focusedKey = "a", visibleIndices = listOf(3, 4, 5)))
        assertEquals("d", sportsLiveListEntryKey(keys, focusedKey = "gone", visibleIndices = listOf(3, 4, 5)))
        assertEquals("a", sportsLiveListEntryKey(keys, focusedKey = null, visibleIndices = listOf(0, 1)))
    }

    @Test
    fun `entry falls back to the first card before the list is laid out`() {
        assertEquals("a", sportsLiveListEntryKey(keys, focusedKey = "c", visibleIndices = emptyList()))
        // A layout from a longer list than the current one.
        assertEquals("a", sportsLiveListEntryKey(keys, focusedKey = null, visibleIndices = listOf(9, 10)))
        assertNull(sportsLiveListEntryKey(emptyList(), focusedKey = "c", visibleIndices = listOf(0)))
    }

    @Test
    fun `a selection still listed stays`() {
        assertEquals("c", sportsLiveKeyAfterRemoval(keys, keys - "e", "c"))
    }

    @Test
    fun `a removed selection moves to the next card still listed, else the previous one`() {
        assertEquals("d", sportsLiveKeyAfterRemoval(keys, keys - "c", "c"))
        assertEquals("e", sportsLiveKeyAfterRemoval(keys, keys - listOf("c", "d"), "c"))
        assertEquals("e", sportsLiveKeyAfterRemoval(keys, keys - "f", "f"))
        // Reordered by a refresh: the order the user saw decides.
        assertEquals("d", sportsLiveKeyAfterRemoval(keys, listOf("f", "d", "a", "b"), "c"))
    }

    @Test
    fun `an empty list has no selection and an unknown one goes to the first card`() {
        assertNull(sportsLiveKeyAfterRemoval(keys, emptyList(), "c"))
        assertEquals("a", sportsLiveKeyAfterRemoval(keys, keys, null))
        assertEquals("b", sportsLiveKeyAfterRemoval(emptyList(), listOf("b", "c"), "x"))
    }
}
