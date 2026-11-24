package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.MySport
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** More live (user decision 2026-11-21): the games My sports leaves out, by sport, behind one last row. */
class SportsLiveMoreLiveTest {
    private val known: (SportsLiveItem) -> Boolean = { false }

    @Test
    fun `groups are by sport, largest first, fights together, Other last`() {
        val hidden = listOf(
            game("India vs Australia", "Cricket"), game("Pakistan vs England", "Cricket"), game("Sri Lanka vs Hong Kong", "Cricket"),
            game("Saracens vs Harlequins", "Rugby"),
            game("Jack Paul vs Cedric Doumbe", "MMA"), game("Joshua vs Fury", "Boxing"),
            game("Mystery A vs Mystery B", null)
        )
        val other = game("BKFC", null)
        val groups = SportsLiveMoreLive.groups(hidden, listOf(other), untagged = { it.sport == null })
        assertEquals(listOf("CRICKET", SportsLiveMoreLive.FIGHTS, "RUGBY", SportsLiveMoreLive.OTHER), groups.map { it.key })
        assertEquals(listOf("Mystery A vs Mystery B", "BKFC"), groups.last().items.map { it.meta.name })
        assertEquals(
            listOf("CRICKET" to 3, SportsLiveMoreLive.FIGHTS to 2, "RUGBY" to 1, SportsLiveMoreLive.OTHER to 2),
            SportsLiveMoreLive.summary(groups)
        )
    }

    @Test
    fun `the row names four sports at most, the rest count as Other`() {
        val hidden = listOf("Cricket", "Rugby", "Darts", "Snooker", "Cycling", "Cycling").mapIndexed { i, sport -> game("A$i vs B$i", sport) }
        val summary = SportsLiveMoreLive.summary(SportsLiveMoreLive.groups(hidden, emptyList(), known))
        assertEquals(5, summary.size)
        assertEquals("CYCLING" to 2, summary.first())
        assertEquals(SportsLiveMoreLive.OTHER to 1, summary.last())
        // Nothing left out: no row.
        assertEquals(emptyList<Pair<String, Int>>(), SportsLiveMoreLive.summary(SportsLiveMoreLive.groups(emptyList(), emptyList(), known)))
    }

    @Test
    fun `Other holds only what isUntagged calls unknown, a sport the app has no name for keeps its own word`() {
        val volleyball = game("Perugia vs Trento", "Volleyball")
        assertEquals(SportsLiveMoreLive.SPORT_TEXT + "Volleyball", SportsLiveMoreLive.keyOf(volleyball, untagged = { false }))
        assertEquals("Volleyball", SportsLiveMoreLive.textOf(SportsLiveMoreLive.keyOf(volleyball, untagged = { false })))
        assertEquals(SportsLiveMoreLive.OTHER, SportsLiveMoreLive.keyOf(volleyball, untagged = { true }))
        // The app's own rule: a game no source gives a sport is Other.
        assertEquals(SportsLiveMoreLive.OTHER, SportsLiveMoreLive.keyOf(game("Mystery A vs Mystery B", null)))
    }

    @Test
    fun `a 24-7 channel goes in a Channels group of its own after the sports, never under its catalog's sport (#285)`() {
        val news = game("Sky Sports News", "Soccer").copy(isChannel = true)
        assertEquals(SportsLiveMoreLive.CHANNELS, SportsLiveMoreLive.keyOf(news, untagged = { false }))
        val hidden = listOf(game("Al Hilal vs Al Ahli", "Soccer"), news, game("India vs Australia", "Cricket"), game("Pakistan vs England", "Cricket"))
        val groups = SportsLiveMoreLive.groups(hidden, listOf(game("BKFC", null)), untagged = { it.sport == null })
        assertEquals(listOf("CRICKET", "SOCCER", SportsLiveMoreLive.CHANNELS, SportsLiveMoreLive.OTHER), groups.map { it.key })
        assertEquals(listOf("Al Hilal vs Al Ahli"), groups.first { it.key == "SOCCER" }.items.map { it.meta.name })
        // The row names Channels like a sport, after the sports, Other last.
        assertEquals(
            listOf("CRICKET" to 2, "SOCCER" to 1, SportsLiveMoreLive.CHANNELS to 1, SportsLiveMoreLive.OTHER to 1),
            SportsLiveMoreLive.summary(groups)
        )
        // Past four groups, Channels counts under Other like any group.
        val many = listOf("Rugby", "Darts", "Snooker", "Cycling").mapIndexed { i, sport -> game("A$i vs B$i", sport) } + news
        val summary = SportsLiveMoreLive.summary(SportsLiveMoreLive.groups(many, emptyList(), untagged = { false }))
        assertEquals(SportsLiveMoreLive.OTHER to 1, summary.last())
        assertTrue(summary.none { it.first == SportsLiveMoreLive.CHANNELS })
    }

    @Test
    fun `adding a sport is offered once per sport My sports can hold`() {
        val mine = MySports(mapOf(MySport.SOCCER to emptySet()))
        val tennis = game("Sinner vs Alcaraz", "Tennis")
        assertEquals(MySport.TENNIS, SportsLiveMoreLive.promptFor(tennis, mine, declined = emptySet()))
        // "Not now" is remembered for that sport.
        assertNull(SportsLiveMoreLive.promptFor(tennis, mine, declined = setOf("tennis")))
        // A sport My sports cannot hold, a sport already picked, or nothing picked: no prompt.
        assertNull(SportsLiveMoreLive.promptFor(game("India vs Australia", "Cricket"), mine, emptySet()))
        assertNull(SportsLiveMoreLive.promptFor(game("Khor Fakkan vs Al-Jazira", "Soccer"), mine, emptySet()))
        assertNull(SportsLiveMoreLive.promptFor(tennis, MySports.DEFAULT, emptySet()))
    }

    private fun game(name: String, sport: String?) = SportsLiveItem(
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
