package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.domain.model.ClaimedCatalog
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.repository.SportsLiveFailure
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The one empty / error card (#134): every empty tab names its next step, never just "Nothing here". */
class SportsLiveEmptyStatesTest {

    private val now = 1_800_000_000_000L

    private fun item(name: String, startMs: Long?, isChannel: Boolean = false) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Live",
        kind = SportsLiveKind.EVENTS, isLive = false, isChannel = isChannel, startMs = startMs, sport = "Basketball",
        startHasTimeOfDay = true
    )

    @Test
    fun `nothing live names the next game Upcoming lists`() {
        val later = item("Lakers vs Suns", now + 5 * HOUR)
        val next = item("Knicks vs Celtics", now + 2 * HOUR)
        val started = item("Bulls vs Heat", now - HOUR)
        val state = SportsLiveUiState(
            selectedTab = SportsLiveTab.LIVE_NOW,
            tabs = mapOf(SportsLiveTab.UPCOMING to listOf(later, started, next, item("Channel 1", null, isChannel = true)))
        )
        val empty = sportsLiveEmptyState(state, now)
        assertEquals(SportsLiveEmptyKind.NOTHING_LIVE, empty.kind)
        assertEquals(next, empty.next)
        // Nothing coming up either: the card still offers Upcoming, with no next game.
        assertNull(sportsLiveEmptyState(SportsLiveUiState(selectedTab = SportsLiveTab.LIVE_NOW), now).next)
    }

    @Test
    fun `each tab's card points somewhere useful`() {
        assertEquals(SportsLiveEmptyKind.NOTHING_UPCOMING, sportsLiveEmptyState(SportsLiveUiState(selectedTab = SportsLiveTab.UPCOMING), now).kind)
        assertEquals(SportsLiveEmptyKind.NO_CHANNELS, sportsLiveEmptyState(SportsLiveUiState(selectedTab = SportsLiveTab.CHANNELS), now).kind)
        assertEquals(SportsLiveEmptyKind.NO_REPLAYS, sportsLiveEmptyState(SportsLiveUiState(selectedTab = SportsLiveTab.REPLAYS), now).kind)
    }

    @Test
    fun `a chip that hides every game of the tab offers Show all`() {
        val state = SportsLiveUiState(
            selectedTab = SportsLiveTab.LIVE_NOW,
            tabs = mapOf(SportsLiveTab.LIVE_NOW to listOf(item("Knicks vs Celtics", now))),
            selectedFilter = "Soccer",
            visibleItems = emptyList()
        )
        assertEquals(SportsLiveEmptyKind.FILTER_EMPTY, sportsLiveEmptyState(state, now).kind)
    }

    @Test
    fun `sources that all failed with nothing loaded say so`() {
        val failure = SportsLiveFailure(mockk<ClaimedCatalog>(relaxed = true), "timeout")
        val state = SportsLiveUiState(selectedTab = SportsLiveTab.LIVE_NOW, failures = listOf(failure))
        assertEquals(SportsLiveEmptyKind.UNREACHABLE, sportsLiveEmptyState(state, now).kind)
        // Some tab still lists games: the tab's own card, not the error.
        val partial = state.copy(tabs = mapOf(SportsLiveTab.UPCOMING to listOf(item("Knicks vs Celtics", now + HOUR))))
        assertEquals(SportsLiveEmptyKind.NOTHING_LIVE, sportsLiveEmptyState(partial, now).kind)
    }

    private companion object {
        const val HOUR = 3_600_000L
    }
}
