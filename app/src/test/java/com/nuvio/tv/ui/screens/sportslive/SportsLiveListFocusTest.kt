package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `while browsed the list keeps its rows, order and count, and new rows wait (F6, BUG7)`() {
        val before = cards("a", "b", "c", "d", "e")
        // Dropped rows stay in place.
        assertEquals(listOf("a", "b", "c", "d", "e"), ids(sportsLiveFreeze(before, cards("a", "c", "e")).items))
        // A load that re-orders and adds: the order shown stays, the new row waits for the user.
        val frozen = sportsLiveFreeze(before, cards("e", "new", "a", "c", "later"))
        assertEquals(listOf("a", "b", "c", "d", "e"), ids(frozen.items))
        assertEquals(listOf("new", "later"), ids(frozen.heldBack))
        // Nothing shown yet (the first load), or an emptied list: the list as it is.
        assertEquals(listOf("e", "a"), ids(sportsLiveFreeze(emptyList(), cards("e", "a")).items))
        assertEquals(emptyList<String>(), ids(sportsLiveFreeze(before, emptyList()).items))
    }

    @Test
    fun `a frozen row still shows its latest data`() {
        val before = cards("a", "b")
        val updated = cards("b", "a").map { it.copy(isLive = !it.isLive) }
        assertEquals(updated.reversed(), sportsLiveFreeze(before, updated).items)
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

    /**
     * The Scoreboard list as Compose builds it on the TV: its focus restorer remembers the row focus was on when the
     * section was left (the row played from), and a request from outside the list lands on that row while it is listed;
     * once the list holds focus, a request moves inside it.
     */
    private class RestoringList(private val remembered: String?, private val listed: List<String>) {
        var focused: String? = null
        var requests = 0

        fun request(key: String) {
            requests++
            if (key !in listed) return
            focused = if (focused == null && remembered != null && remembered in listed) remembered else key
        }
    }

    private val board = listOf("hawks", "broncos", "chargers", "giants")

    @Test
    fun `back after a zap focuses the game playing though the list restores the row played from`() = runTest {
        // Path A on the TV: Broncos played from its row, zapped down to Chargers (the mini player's game), then Back.
        val target = checkNotNull(sportsLiveReturnTarget(board, board, focusedKey = "broncos", watchedKey = "chargers"))
        assertEquals("chargers", target)
        val list = RestoringList(remembered = "broncos", listed = board)
        val done = sportsLiveFocusReturnRow(
            target = target,
            attempts = 10,
            focusedKey = { list.focused },
            request = { list.request(target) },
            awaitFrame = {}
        )
        assertTrue(done)
        assertEquals("chargers", list.focused)
        // The first request was answered with the row played from: the list holding focus is not the return.
        assertEquals(2, list.requests)
    }

    @Test
    fun `back after zaps and the panel focuses the game playing on a rebuilt board without the row played from`() = runTest {
        // Path B on the TV: Hawks played, zapped to Broncos, the live games panel opened and closed, then Back while
        // the board was rebuilt without Hawks.
        val rebuilt = listOf("bears", "cowboys", "broncos", "lions", "chargers")
        val target = checkNotNull(sportsLiveReturnTarget(rebuilt, board, focusedKey = "hawks", watchedKey = "broncos"))
        assertEquals("broncos", target)
        val list = RestoringList(remembered = "hawks", listed = rebuilt)
        assertTrue(
            sportsLiveFocusReturnRow(target, 10, { list.focused }, { list.request(target) }, {})
        )
        assertEquals("broncos", list.focused)
    }

    @Test
    fun `back with no zap focuses the row played from at the first request`() = runTest {
        val list = RestoringList(remembered = "broncos", listed = board)
        assertTrue(sportsLiveFocusReturnRow("broncos", 10, { list.focused }, { list.request("broncos") }, {}))
        assertEquals(1, list.requests)
    }

    @Test
    fun `a row that never takes focus is asked for at most the attempts given`() = runTest {
        val list = RestoringList(remembered = null, listed = board)
        assertFalse(sportsLiveFocusReturnRow("gone", 10, { list.focused }, { list.request("gone") }, {}))
        assertEquals(10, list.requests)
        assertNull(list.focused)
    }
}
