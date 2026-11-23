package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveMoreLive
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** live.26 shorter paths (#223, A5): My sports ▾ on Live now adds All sports and each other sport with its game count. */
class SportsLiveAllSportsMenuTest {

    @Test
    fun `All sports comes first, then each other sport with its game count, largest first and Other last`() {
        val groups = listOf(
            SportsLiveMoreLive.Group("CRICKET", listOf(game("A"), game("B"), game("C"))),
            SportsLiveMoreLive.Group("RUGBY", listOf(game("D"))),
            SportsLiveMoreLive.Group(SportsLiveMoreLive.OTHER, listOf(game("E"), game("F")))
        )
        assertEquals(
            listOf(
                SportsLiveAllSportsEntry(null, null),
                SportsLiveAllSportsEntry("CRICKET", 3),
                SportsLiveAllSportsEntry("RUGBY", 1),
                SportsLiveAllSportsEntry(SportsLiveMoreLive.OTHER, 2)
            ),
            sportsLiveAllSportsEntries(groups)
        )
    }

    @Test
    fun `a sport with no games is never offered, and with nothing outside My sports there is no All sports entry`() {
        val groups = listOf(SportsLiveMoreLive.Group("CRICKET", emptyList()), SportsLiveMoreLive.Group("RUGBY", listOf(game("D"))))
        assertEquals(listOf(SportsLiveAllSportsEntry(null, null), SportsLiveAllSportsEntry("RUGBY", 1)), sportsLiveAllSportsEntries(groups))
        assertEquals(emptyList<SportsLiveAllSportsEntry>(), sportsLiveAllSportsEntries(emptyList()))
        assertEquals(emptyList<SportsLiveAllSportsEntry>(), sportsLiveAllSportsEntries(listOf(SportsLiveMoreLive.Group("CRICKET", emptyList()))))
    }

    @Test
    fun `the counts are the More live row's own counts, so the menu and the row always agree`() {
        val groups = SportsLiveMoreLive.groups(
            listOf(game("India vs Australia", "Cricket"), game("Pakistan vs England", "Cricket"), game("Saracens vs Harlequins", "Rugby")),
            emptyList(),
            untagged = { false }
        )
        val menu = sportsLiveAllSportsEntries(groups).mapNotNull { entry -> entry.group?.let { it to entry.count } }
        assertEquals(SportsLiveMoreLive.summary(groups), menu)
    }

    @Test
    fun `More live opened on a sport lands on its first game, else on the first row`() {
        val cricket = game("India vs Australia")
        val rugby = game("Saracens vs Harlequins")
        val rugby2 = game("Leinster vs Munster")
        val items = listOf(cricket, rugby, rugby2)
        val groupOf = mapOf(cricket.streamKey to "CRICKET", rugby.streamKey to "RUGBY", rugby2.streamKey to "RUGBY")
        assertSame(rugby, sportsLiveMoreLiveFocusTarget(items, groupOf, "RUGBY"))
        assertSame(cricket, sportsLiveMoreLiveFocusTarget(items, groupOf, null))
        // The sport's games ended meanwhile: the first row, never nothing.
        assertSame(cricket, sportsLiveMoreLiveFocusTarget(items, groupOf, "DARTS"))
        assertNull(sportsLiveMoreLiveFocusTarget(emptyList(), emptyMap(), "RUGBY"))
    }

    private fun game(name: String, sport: String? = null) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "tv", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "addon", addonName = "Sports", addonBaseUrl = "https://example.invalid", catalogKey = "addon:tv:live",
        catalogName = "Events", kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false,
        startMs = null, sport = sport, teams = SportsLiveTeams.parse(name)
    )
}
