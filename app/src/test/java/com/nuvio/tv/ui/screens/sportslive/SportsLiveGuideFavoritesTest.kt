package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.GameStatusStrings
import com.nuvio.tv.core.sportslive.SportsLiveChannelFavorites
import com.nuvio.tv.core.sportslive.scores.ScoreChipStrings
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Channels phase 1 in the player: favorite channels first in the panel and the guide (so CH± zaps them first), with the ★. */
class SportsLiveGuideFavoritesTest {

    private val now = 1_800_000_000_000L

    private fun channel(id: String) = SportsLiveItem(
        meta = MetaPreview(
            id = id, type = ContentType.UNKNOWN, rawType = "tv", name = "Channel $id HD", poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "addon", addonName = "Sports", addonBaseUrl = "https://example.invalid", catalogKey = "addon:tv:channels",
        catalogName = "Channels", kind = SportsLiveKind.CHANNELS, isLive = true, isChannel = true, startMs = null, sport = null
    )

    private val channels = (1..4).map { channel("c$it") }
    private val strings = GameStatusStrings(
        liveClock = "● %1\$s", live = "LIVE", clockPeriod = "%1\$s · %2\$s", timeCountdown = "%1\$s · %2\$s",
        inHoursMinutes = "in %1\$d h %2\$d m", inHours = "in %1\$d h", inMinutes = "in %1\$d min",
        final = "Final", fullTime = "FT", probablyEnded = "Probably ended", postponed = "Postponed",
        chip = ScoreChipStrings("%1\$d–%2\$d", "%1\$s · %2\$s", "%1\$s %2\$s", "Final", "Postponed", "Score hidden")
    )

    @Test
    fun `favorite channels come first, in the list's own order, the rest after`() {
        val favorites = setOf(channels[2].streamKey, channels[3].streamKey)
        val rows = panelLineup(groupPanelRows(channels, emptyMap(), ScoresMode.OFF, emptySet(), now), favorites)
        assertEquals(listOf("c3", "c4", "c1", "c2"), rows.map { it.item.id })
        // No favorites: as listed.
        assertEquals(listOf("c1", "c2", "c3", "c4"), panelLineup(groupPanelRows(channels, emptyMap(), ScoresMode.OFF, emptySet(), now)).map { it.item.id })
    }

    @Test
    fun `a favorite channel's row carries the star in the panel and the guide`() {
        val favorites = setOf(channels[1].streamKey)
        val boards = panelBoards(groupPanelRows(channels, emptyMap(), ScoresMode.OFF, emptySet(), now), emptyMap(), ScoresMode.OFF, emptySet(), now, favorites = favorites)
            .associateBy { it.board.item.id }
        assertTrue(boards.getValue("c2").board.favorite)
        assertFalse(boards.getValue("c1").board.favorite)
        assertTrue(GuideMark.MY_TEAM in guideRowOf(boards.getValue("c2").board, strings, kickoff = null, tag = "").marks)
        assertFalse(GuideMark.MY_TEAM in guideRowOf(boards.getValue("c1").board, strings, kickoff = null, tag = "").marks)
    }

    @Test
    fun `the favorites are found among the panel's channels by the stored picks`() {
        val picks = SportsLiveChannelFavorites.toggle(com.nuvio.tv.core.sportslive.SportsLiveChannelPicks.NONE, channels[3], favorite = false)
        val found = SportsLiveChannelFavorites.resolve(picks.favorites, channels).map { it.streamKey }.toSet()
        assertEquals(setOf(channels[3].streamKey), found)
        // Picked again while it is one: removed.
        assertTrue(SportsLiveChannelFavorites.toggle(picks, channels[3], favorite = true).favorites.isEmpty())
    }
}
