package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.ui.screens.sportslive.ZapPanelGroup
import com.nuvio.tv.ui.screens.sportslive.ZapPanelRow
import com.nuvio.tv.ui.screens.sportslive.panelGuide
import com.nuvio.tv.ui.screens.sportslive.panelPickKeepsPlaying
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** J5 (user TV report 2026-11-21): another addon's copy of the game playing is the game playing. */
class SportsLivePlayingGameTest {
    private val hour = 60 * 60 * 1000L

    @Test
    fun `an unmerged copy with club suffixes, either way round, is the game playing`() {
        val playing = game("a", "1", "Liverpool vs Chelsea", start = 0)
        assertTrue(SportsLivePlayingGame.isSameGame(game("b", "9", "Liverpool FC vs Chelsea FC", start = 5 * 60_000L), playing))
        assertTrue(SportsLivePlayingGame.isSameGame(game("b", "9", "Chelsea vs Liverpool", start = 0), playing))
        assertTrue(SportsLivePlayingGame.isSameGame(playing, playing))
        // One of its merged sources.
        val source = game("c", "3", "Something else entirely", start = 0)
        assertTrue(SportsLivePlayingGame.isSameGame(source, playing.copy(alternates = listOf(source))))
    }

    @Test
    fun `other games, other days and teamless entries are not`() {
        val playing = game("a", "1", "Liverpool vs Chelsea", start = 0)
        assertFalse(SportsLivePlayingGame.isSameGame(game("b", "2", "Liverpool vs Arsenal", start = 0), playing))
        assertFalse(SportsLivePlayingGame.isSameGame(game("b", "3", "Liverpool vs Chelsea", start = 3 * 24 * hour), playing))
        assertFalse(SportsLivePlayingGame.isSameGame(game("b", "4", "MotoGP", start = 0), game("a", "5", "MotoGP", start = 0)))
    }

    @Test
    fun `the panel and the guide never list the game playing again`() {
        val playing = game("a", "1", "Liverpool vs Chelsea", start = 0)
        val copy = game("b", "9", "Liverpool FC vs Chelsea FC", start = 0)
        val other = game("b", "2", "Arsenal vs Spurs", start = 0)
        val lineup = listOf(copy, playing, other).map { ZapPanelRow(it, ZapPanelGroup.LIVE_NOW, chip = null, art = null) }
        val guide = panelGuide(lineup, playing.streamKey)
        assertEquals(listOf(other.streamKey), guide.entries.filterIsInstance<com.nuvio.tv.ui.screens.sportslive.PanelGuideEntry.Game>().map { it.key })
        // The lineup leaves the game playing out (a chip): its copy is still left out.
        val filtered = panelGuide(lineup.filter { it.item != playing }, playing.streamKey, playing = playing)
        assertEquals(listOf(other.streamKey), filtered.entries.filterIsInstance<com.nuvio.tv.ui.screens.sportslive.PanelGuideEntry.Game>().map { it.key })
    }

    @Test
    fun `OK on the game playing or a copy only closes the panel while the stream plays well, else it recovers`() {
        val playing = game("a", "1", "Liverpool vs Chelsea", start = 0)
        val copy = game("b", "9", "Liverpool FC vs Chelsea FC", start = 0)
        val other = game("b", "2", "Arsenal vs Spurs", start = 0)
        // Healthy: the stream plays on (J5).
        assertTrue(panelPickKeepsPlaying(playing, playing, streamHealthy = true))
        assertTrue(panelPickKeepsPlaying(copy, playing, streamHealthy = true))
        // Buffering, stalled or failed (M1): the pick restarts the game or plays the copy's link.
        assertFalse(panelPickKeepsPlaying(playing, playing, streamHealthy = false))
        assertFalse(panelPickKeepsPlaying(copy, playing, streamHealthy = false))
        // Another game always switches, and with nothing known as playing the pick goes ahead.
        assertFalse(panelPickKeepsPlaying(other, playing, streamHealthy = true))
        assertFalse(panelPickKeepsPlaying(playing, null, streamHealthy = true))
    }

    private fun game(addon: String, id: String, name: String, start: Long) = SportsLiveItem(
        meta = MetaPreview(
            id = id, type = ContentType.UNKNOWN, rawType = "tv", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = addon, addonName = "Sports $addon", addonBaseUrl = "https://example.invalid", catalogKey = "$addon:tv:live",
        catalogName = "Events", kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false,
        startMs = start, sport = "Soccer", startHasTimeOfDay = true, teams = SportsLiveTeams.parse(name)
    )
}
