package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
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

    @Test
    fun `a focused card a load dropped is kept where it was`() {
        val before = cards("a", "b", "c", "d")

        assertEquals(listOf("a", "b", "c", "d"), ids(sportsLiveKeepFocused(before, cards("a", "c", "d"), "b")))
        assertEquals(listOf("a", "b", "d"), ids(sportsLiveKeepFocused(before, cards("a", "d"), "b")))
        assertEquals(listOf("b", "c"), ids(sportsLiveKeepFocused(before, cards("c"), "b")))
        // Still listed, nothing focused, or an emptied list: the list as it is.
        assertEquals(listOf("a", "c"), ids(sportsLiveKeepFocused(before, cards("a", "c"), "c")))
        assertEquals(listOf("a", "c"), ids(sportsLiveKeepFocused(before, cards("a", "c"), null)))
        assertEquals(emptyList<String>(), ids(sportsLiveKeepFocused(before, emptyList(), "b")))
    }

    @Test
    fun `while the list holds focus every row a load dropped stays in place (F6)`() {
        val before = cards("a", "b", "c", "d", "e")
        assertEquals(listOf("a", "b", "c", "d", "e"), ids(sportsLiveKeepShown(before, cards("a", "c", "e"))))
        assertEquals(listOf("a", "b", "c", "d"), ids(sportsLiveKeepShown(before.take(4), cards("c"))))
        // The dropped ones follow the rows they followed; a new row comes after them.
        assertEquals(listOf("a", "b", "new", "c", "d", "e"), ids(sportsLiveKeepShown(before, cards("a", "new", "c", "e"))))
        // Nothing dropped, or an emptied list: the list as it is.
        assertEquals(listOf("e", "a"), ids(sportsLiveKeepShown(cards("a", "e"), cards("e", "a"))))
        assertEquals(emptyList<String>(), ids(sportsLiveKeepShown(before, emptyList())))
    }

    private fun ids(items: List<SportsLiveItem>) = items.map { it.id }

    private fun cards(vararg ids: String) = ids.map { id ->
        SportsLiveItem(
            meta = MetaPreview(
                id = id, type = ContentType.UNKNOWN, rawType = "tv", name = id, poster = null,
                posterShape = PosterShape.LANDSCAPE, background = null, logo = null,
                description = null, releaseInfo = null, imdbRating = null, genres = emptyList()
            ),
            addonId = "x", addonName = "x", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
            kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false, startMs = null, sport = null
        )
    }
}
