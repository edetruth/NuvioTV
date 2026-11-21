package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveChannelBrowse
import com.nuvio.tv.core.sportslive.SportsLiveChannelBrowseKind
import com.nuvio.tv.core.sportslive.SportsLiveChannelCountries
import com.nuvio.tv.core.sportslive.SportsLiveChannelFavorites
import com.nuvio.tv.core.sportslive.SportsLiveChannelList
import com.nuvio.tv.core.sportslive.SportsLiveChannelLists
import com.nuvio.tv.core.sportslive.SportsLiveChannelPicks
import com.nuvio.tv.core.sportslive.SportsLiveChannelSummary
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** Channels phase 2 on the board: Popular now under Recent, the entry rows' counts and the browse view. */
class SportsLiveChannelsPhase2Test {

    private fun channel(id: String, name: String, addon: String = "a") = SportsLiveItem(
        meta = MetaPreview(
            id = id, type = ContentType.UNKNOWN, rawType = "tv", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = addon, addonName = addon, addonBaseUrl = "https://example.invalid", catalogKey = "k", catalogName = "Channels",
        kind = SportsLiveKind.CHANNELS, isLive = true, isChannel = true, startMs = null, sport = null
    )

    private val channels = listOf(
        channel("cnn", "US: CNN"),
        channel("espn", "US: ESPN"),
        channel("espn-b", "ESPN", addon = "b").copy(group = "US"),
        channel("golf", "US: Golf Channel"),
        channel("local", "US: Local Sports 4"),
        channel("weather", "US: Weather Now"),
        channel("ukone", "UK: BBC One")
    )

    private fun board(lists: SportsLiveChannelLists) = SportsLiveBoard.build(
        SportsLiveBoardInputs(
            tab = SportsLiveTab.CHANNELS, filter = null, items = channels, scores = SportsLiveBoardScores(), ended = emptyMap(),
            excitement = emptyMap(), followed = emptyList(), clockMs = 0L, logoRevision = 0, channelLists = lists
        ),
        ZoneId.of("UTC")
    ) { false }

    private val us = SportsLiveChannelLists(tvRegion = "US")

    @Test
    fun `the tab lists favorites, recent, then popular now, with no region headings`() {
        val picks = SportsLiveChannelPicks(
            favorites = listOf(SportsLiveChannelFavorites.refOf(channels.first { it.id == "weather" })),
            recent = listOf(SportsLiveChannelFavorites.refOf(channels.first { it.id == "golf" }))
        )
        val built = board(us.copy(order = picks, favorites = picks.favorites))
        val rows = built.rows!!
        assertEquals(listOf("weather", "golf", "espn", "cnn"), rows.map { it.item.id })
        assertEquals(
            listOf(SportsLiveChannelFavorites.FAVORITES, SportsLiveChannelFavorites.RECENT, SportsLiveChannelList.POPULAR, SportsLiveChannelList.POPULAR),
            rows.map { it.section }
        )
        val summary = built.channels!!
        assertEquals(SportsLiveChannelSummary.MAIN_VIEW, summary.view)
        assertFalse(summary.fallback)
        // BBC One is a UK channel: not in this viewer's countries.
        assertEquals(5, summary.all)
        // Sports channels: a sports brand, listed or tiered, or a sport word in the name (ESPN, Golf Channel, Local Sports 4).
        assertEquals(3, summary.sports)
        assertEquals(2, summary.popularShown)
    }

    @Test
    fun `one channel from two addons is one row whose links are both, starred when either is the favorite`() {
        val favoriteCopy = SportsLiveChannelFavorites.refOf(channels.first { it.id == "espn-b" })
        val rows = board(us.copy(favorites = listOf(favoriteCopy), order = SportsLiveChannelPicks(favorites = listOf(favoriteCopy)))).rows!!
        val espn = rows.single { it.item.id == "espn" }
        assertEquals(listOf("espn-b"), espn.item.alternates.map { it.id })
        assertTrue(espn.favorite)
        assertEquals(SportsLiveChannelFavorites.FAVORITES, espn.section)
    }

    @Test
    fun `browse lists the countries' channels A to Z under letters, sports only on the switch`() {
        val all = board(us.copy(browse = SportsLiveChannelBrowse(SportsLiveChannelBrowseKind.ALL))).rows!!
        assertEquals(listOf("cnn", "espn", "golf", "local", "weather"), all.map { it.item.id })
        assertEquals(listOf("C", "E", "G", "L", "W").map { SportsLiveChannelList.LETTER + it }, all.map { it.section })

        val sports = board(us.copy(browse = SportsLiveChannelBrowse(SportsLiveChannelBrowseKind.SPORTS))).rows!!
        assertEquals(listOf("espn", "golf", "local"), sports.map { it.item.id })
    }

    @Test
    fun `find a channel narrows the browse list, and every country lists all`() {
        val found = board(us.copy(browse = SportsLiveChannelBrowse(SportsLiveChannelBrowseKind.ALL, query = "golf"))).rows!!
        assertEquals(listOf("golf"), found.map { it.item.id })

        val everywhere = board(us.copy(countries = setOf(SportsLiveChannelCountries.ALL), browse = SportsLiveChannelBrowse(SportsLiveChannelBrowseKind.ALL)))
        assertTrue(everywhere.rows!!.any { it.item.id == "ukone" })
        assertEquals(null, everywhere.channels!!.selected)
    }

    @Test
    fun `see all popular lists every popular channel in its order`() {
        val popular = board(us.copy(browse = SportsLiveChannelBrowse(SportsLiveChannelBrowseKind.POPULAR)))
        assertEquals(listOf("espn", "golf", "cnn"), popular.rows!!.map { it.item.id })
        assertEquals(SportsLiveChannelBrowseKind.POPULAR.name, popular.channels!!.view)
    }
}
