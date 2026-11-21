package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveChannelFavorites
import com.nuvio.tv.core.sportslive.SportsLiveChannelLists
import com.nuvio.tv.core.sportslive.SportsLiveChannelPicks
import com.nuvio.tv.core.sportslive.SportsLiveChannelRegions
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** Channels phase 1: Favorites and Recent above the regions, the ★, and an order that never moves under focus. */
class SportsLiveChannelsPhase1Test {

    private fun channel(id: String, group: String) = SportsLiveItem(
        meta = MetaPreview(
            id = id, type = ContentType.UNKNOWN, rawType = "tv", name = id, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "a", addonName = "a", addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Channels",
        kind = SportsLiveKind.CHANNELS, isLive = true, isChannel = true, startMs = null, sport = null, group = group
    )

    private val channels = listOf(
        channel("US 1", "US"), channel("UK 1", "UK"), channel("US 2", "US"), channel("UK 2", "UK"), channel("US 3", "US")
    )

    private fun refs(vararg ids: String) = ids.map { id -> SportsLiveChannelFavorites.refOf(channels.first { it.id == id }) }

    private fun board(lists: SportsLiveChannelLists, filter: String? = null, items: List<SportsLiveItem> = channels) =
        SportsLiveBoard.build(
            SportsLiveBoardInputs(
                tab = SportsLiveTab.CHANNELS, filter = filter, items = items, scores = SportsLiveBoardScores(), ended = emptyMap(),
                excitement = emptyMap(), followed = emptyList(), clockMs = 0L, logoRevision = 0, channelLists = lists
            ),
            ZoneId.of("UTC")
        )

    @Test
    fun `favorites lead, then recent, then the regions without them, and focus opens on the first favorite`() {
        val picks = SportsLiveChannelPicks(favorites = refs("US 3", "UK 1"), recent = refs("UK 1", "US 2"))
        val built = board(SportsLiveChannelLists(order = picks, favorites = picks.favorites))
        val rows = built.rows!!
        assertEquals(listOf("US 3", "UK 1", "US 2", "UK 2", "US 1"), rows.map { it.item.id })
        assertEquals(
            listOf(SportsLiveChannelFavorites.FAVORITES, SportsLiveChannelFavorites.FAVORITES, SportsLiveChannelFavorites.RECENT, "UK", "US"),
            rows.map { it.section }
        )
        assertEquals(listOf(true, true, false, false, false), rows.map { it.favorite })
        // The list is the board's: the way into it and the stage start on its first row.
        assertEquals(rows.map { it.item }, built.items)
        assertEquals("US 3", sportsLiveStageKey(built.items.map { it.streamKey }, focusedKey = null, miniKey = null)?.substringAfter('|'))
    }

    @Test
    fun `with no favorites, recent leads, and with neither the regions are as before`() {
        val recentOnly = board(SportsLiveChannelLists(order = SportsLiveChannelPicks(recent = refs("US 2"))))
        assertEquals("US 2", recentOnly.rows!!.first().item.id)
        assertEquals(SportsLiveChannelFavorites.RECENT, recentOnly.rows!!.first().section)

        val none = board(SportsLiveChannelLists.NONE)
        assertEquals(listOf("UK 1", "UK 2", "US 1", "US 2", "US 3"), none.rows!!.map { it.item.id })
        assertTrue(none.rows!!.none { SportsLiveChannelFavorites.isPickSection(it.section) || it.favorite })
    }

    @Test
    fun `a favorite added on the tab gets its star where it is and moves up only when the order is taken again`() {
        val added = refs("US 1")
        val now = board(SportsLiveChannelLists(order = SportsLiveChannelPicks.NONE, favorites = added))
        assertEquals(listOf("UK 1", "UK 2", "US 1", "US 2", "US 3"), now.rows!!.map { it.item.id })
        assertTrue(now.rows!!.single { it.item.id == "US 1" }.favorite)

        val reopened = board(SportsLiveChannelLists(order = SportsLiveChannelPicks(favorites = added), favorites = added))
        assertEquals("US 1", reopened.rows!!.first().item.id)
    }

    @Test
    fun `a favorite removed on the tab keeps its row, without the star, until the order is taken again`() {
        val order = SportsLiveChannelPicks(favorites = refs("UK 2"))
        val rows = board(SportsLiveChannelLists(order = order, favorites = emptyList())).rows!!
        assertEquals("UK 2", rows.first().item.id)
        assertEquals(SportsLiveChannelFavorites.FAVORITES, rows.first().section)
        assertFalse(rows.first().favorite)
    }

    @Test
    fun `a region picked lists that region alone, its favorites starred in place`() {
        val picks = SportsLiveChannelPicks(favorites = refs("US 3"), recent = refs("US 2"))
        val us = channels.filter { SportsLiveChannelRegions.regionOf(it) == "US" }
        val rows = board(SportsLiveChannelLists(order = picks, favorites = picks.favorites), filter = "US", items = us).rows!!
        assertEquals(listOf("US 1", "US 2", "US 3"), rows.map { it.item.id })
        assertEquals(listOf(false, false, true), rows.map { it.favorite })
        assertTrue(rows.all { it.section == "US" })
    }

    @Test
    fun `a favorite gone from every catalog is left out of the list`() {
        val gone = SportsLiveChannelFavorites.refOf(channel("Gone", "US"))
        val picks = SportsLiveChannelPicks(favorites = listOf(gone) + refs("UK 1"))
        val rows = board(SportsLiveChannelLists(order = picks, favorites = picks.favorites)).rows!!
        assertEquals(listOf("UK 1", "UK 2", "US 1", "US 2", "US 3"), rows.map { it.item.id })
        assertEquals(channels.size, rows.size)
    }

    @Test
    fun `the order is read once per take and holds while the picks change`() = runTest(UnconfinedTestDispatcher()) {
        val stored = MutableStateFlow(SportsLiveChannelPicks.NONE)
        val takes = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }
        val seen = mutableListOf<SportsLiveChannelPicks>()
        val job = launch { sportsLiveFrozenOn(takes, stored).collect { seen += it } }
        val one = SportsLiveChannelPicks(favorites = refs("US 1"))
        stored.value = one
        assertEquals(listOf(SportsLiveChannelPicks.NONE), seen)
        takes.emit(Unit)
        assertEquals(listOf(SportsLiveChannelPicks.NONE, one), seen)
        job.cancel()
    }
}
