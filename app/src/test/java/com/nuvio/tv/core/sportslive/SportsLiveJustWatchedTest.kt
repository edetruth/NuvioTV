package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.ui.screens.sportslive.PanelGuideEntry
import com.nuvio.tv.ui.screens.sportslive.ZapPanelGroup
import com.nuvio.tv.ui.screens.sportslive.ZapPanelRow
import com.nuvio.tv.ui.screens.sportslive.filterPanelItems
import com.nuvio.tv.ui.screens.sportslive.justWatchedPool
import com.nuvio.tv.ui.screens.sportslive.panelGuide
import com.nuvio.tv.ui.screens.sportslive.SportsLiveUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** J7 (user 2026-11-21): the game watched before this one, one ▼ OK away. */
class SportsLiveJustWatchedTest {
    private val city = game("a", "1", "Manchester City vs Fulham")
    private val liverpool = game("a", "2", "Liverpool vs Chelsea")
    private val arsenal = game("b", "3", "Arsenal vs Spurs")
    private val live = listOf(city, liverpool, arsenal)

    @Test
    fun `zapping A to B shows A, and A to B to C shows B`() {
        val record = SportsLiveJustWatched()
        record.played(city)
        assertNull(record.justWatched(city, live))
        record.played(liverpool)
        assertEquals(city, record.justWatched(liverpool, live))
        record.played(arsenal)
        assertEquals(liverpool, record.justWatched(arsenal, live))
        // Back to B: the one before is C now.
        record.played(liverpool)
        assertEquals(arsenal, record.justWatched(liverpool, live))
    }

    @Test
    fun `a game over or gone is not shown, nor an older one in its place`() {
        val record = SportsLiveJustWatched()
        record.played(city)
        record.played(liverpool)
        record.played(arsenal)
        assertNull(record.justWatched(arsenal, listOf(city, arsenal)))
    }

    @Test
    fun `another addon's copy of the game playing is never the one before, and a copy of the last one stands for it`() {
        val record = SportsLiveJustWatched()
        record.played(city)
        record.played(liverpool)
        val copy = game("b", "9", "Liverpool FC vs Chelsea FC")
        // Playing Liverpool from another addon: still City.
        assertEquals(city, record.justWatched(copy, live))
        val cityCopy = game("b", "8", "Man City vs Fulham FC")
        assertEquals(city, record.justWatched(liverpool, listOf(liverpool, city)))
        // The last one listed only by another addon: that copy.
        record.played(arsenal)
        assertEquals(copy, record.justWatched(arsenal, listOf(copy, arsenal, cityCopy)))
    }

    @Test
    fun `the panel lists it first, under Now playing, and its copies not again`() {
        val rows = live.map { ZapPanelRow(it, ZapPanelGroup.LIVE_NOW, chip = null, art = null) }
        val back = rows[0]
        val guide = panelGuide(rows, liverpool.streamKey, justWatched = back)
        val games = guide.entries.filterIsInstance<PanelGuideEntry.Game>()
        assertTrue(games.first().justWatched)
        assertEquals(listOf(city.streamKey, arsenal.streamKey), games.map { it.key })
        // CH± still walk the lineup as it is.
        assertEquals(rows, guide.rows)
    }

    @Test
    fun `the line shows only when the game watched is in the filter picked`() {
        val nice = game("a", "10", "Nice vs PSG")
        val mavs = game("a", "11", "Dallas Mavericks vs Los Angeles Clippers", sport = "Basketball", followed = true)
        val lakers = game("a", "12", "Lakers vs Celtics", sport = "Basketball")
        val games = listOf(nice, mavs, lakers)
        val record = SportsLiveJustWatched()
        record.played(nice)
        record.played(lakers)
        fun under(chip: String?, finished: Set<String> = emptySet()) =
            record.justWatched(lakers, justWatchedPool(filterPanelItems(games, chip), finished))
        // My sports (no chip): the games it lists.
        assertEquals(nice, under(null))
        // A sport chip: that sport's games only.
        assertNull(under(SportsLiveSportKind.filterKeyOf(SportsLiveSportKind.BASKETBALL)))
        assertEquals(nice, under(SportsLiveSportKind.filterKeyOf(SportsLiveSportKind.SOCCER)))
        // ★ My teams: a followed team's game only.
        assertNull(under(SportsLiveUiState.MY_TEAMS_FILTER))
        record.played(mavs)
        record.played(lakers)
        assertEquals(mavs, under(SportsLiveUiState.MY_TEAMS_FILTER))
        assertEquals(mavs, under(SportsLiveSportKind.filterKeyOf(SportsLiveSportKind.BASKETBALL)))
        // A finished game is never the line.
        assertNull(under(null, finished = setOf(mavs.streamKey)))
    }

    private fun game(addon: String, id: String, name: String, sport: String = "Soccer", followed: Boolean = false) = SportsLiveItem(
        meta = MetaPreview(
            id = id, type = ContentType.UNKNOWN, rawType = "tv", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = addon, addonName = "Sports $addon", addonBaseUrl = "https://example.invalid", catalogKey = "$addon:tv:live",
        catalogName = "Events", kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false,
        startMs = null, sport = sport, teams = SportsLiveTeams.parse(name), followed = followed
    )
}
