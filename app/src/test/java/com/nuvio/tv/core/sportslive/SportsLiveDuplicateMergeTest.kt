package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.ScoreCard
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ClaimedCatalog
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Copies of one game that did not merge (#165) and women's games kept apart from men's (#163). */
class SportsLiveDuplicateMergeTest {
    private val now = Instant.parse("2026-11-19T16:00:00Z").toEpochMilli()

    @Test
    fun `youth markers all fold to one`() {
        val forms = listOf("Qatar Sub-17", "Qatar Sub17", "Qatar Sub 17", "Qatar U-17", "Qatar U17", "Qatar Under-17", "Qatar Under 17")
        assertEquals(setOf("qatar u17"), forms.map(SportsLiveTeams::normalise).toSet())
        assertEquals("brazil u20", SportsLiveTeams.normalise("Brazil Sub-20"))
        assertEquals(SportsLiveTeams.MergeMatch.FULL, SportsLiveTeams.mergeMatch("Panamá Sub-17", "Panama U17"))
    }

    @Test
    fun `x separates two sides between proper words`() {
        assertEquals(TeamPair("Qatar U17", "Panamá U17"), SportsLiveTeams.parse("Qatar U17 x Panamá U17"))
        assertEquals(TeamPair("Flamengo", "Vasco"), SportsLiveTeams.parse("Brasileirão - Flamengo x Vasco"))
        assertEquals(TeamPair("Qatar U17", "Panamá U17"), SportsLiveTeams.parse("LIVE: Qatar U17 x Panamá U17"))
        // A lone lower-case letter either side is no team.
        assertNull(SportsLiveTeams.parse("a x b"))
    }

    @Test
    fun `Xavier is unaffected`() {
        assertEquals(TeamPair("Xavier", "Butler"), SportsLiveTeams.parse("Xavier vs Butler"))
        assertEquals(TeamPair("Butler", "Xavier"), SportsLiveTeams.parse("Xavier at Butler"))
        assertNull(SportsLiveTeams.parse("Xavier"))
        assertNull(SportsLiveTeams.parse("Xavier Musketeers Highlights"))
        assertEquals("xavier", SportsLiveTeams.normalise("Xavier"))
        // "Liverpool" starts with "Live" but is no live marker.
        assertEquals(TeamPair("Liverpool", "Everton"), SportsLiveTeams.parse("Liverpool vs Everton"))
    }

    @Test
    fun `the issue's three games merge into one row each`() {
        val items = listOf(
            event("a", "q1", "Qatar Sub-17 vs Panamá Sub-17", genres = listOf("Football")),
            event("b", "q2", "LIVE: Qatar U17 x Panamá U17"),
            event("c", "q3", "Qatar U17 vs Panama U17", genres = listOf("Soccer")),
            event("a", "r1", "Argentina Sub-17 vs Australia Sub-17", genres = listOf("Football")),
            event("b", "r2", "Argentina U17 x Australia U17"),
            event("c", "r3", "Argentina U-17 vs Australia U-17", genres = listOf("Soccer")),
            event("b", "s1", "Egypt U17 x Greece U17"),
            event("c", "s2", "Egypt Under-17 vs Greece Under 17", genres = listOf("Soccer"))
        )
        val rows = SportsLiveItemBucketer.bucketTab(items, SportsLiveTab.LIVE_NOW, now, addonOrder = listOf("a", "b", "c"))
        assertEquals(3, rows.size)
        assertEquals(listOf(2, 3, 3), rows.map { it.sourceCount }.sorted())
        assertEquals(setOf("q1", "r1", "s1"), rows.map { it.id }.toSet())
    }

    @Test
    fun `a youth side never merges with the senior side`() {
        val rows = SportsLiveItemBucketer.bucketTab(
            listOf(
                event("a", "1", "Qatar U17 vs Panama U17", genres = listOf("Soccer")),
                event("b", "2", "Qatar vs Panamá", genres = listOf("Soccer")),
                event("c", "3", "Qatar Sub-17 x Panama", genres = listOf("Soccer"))
            ),
            SportsLiveTab.LIVE_NOW, now
        )
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.alternates.isEmpty() })
    }

    @Test
    fun `an ESPN routed copy absorbs an unmatched copy whose teams fold equal`() {
        // The plain copy comes first in the user's order; the merged card still routes and matches as one game.
        val rows = SportsLiveItemBucketer.bucketTab(
            listOf(event("a", "1", "LIVE: Qatar U17 x Panamá U17"), event("b", "2", "Qatar Sub-17 vs Panama Sub-17", genres = listOf("Soccer"))),
            SportsLiveTab.LIVE_NOW, now, addonOrder = listOf("a", "b")
        )
        val card = rows.single()
        assertEquals(2, card.sourceCount)
        assertEquals("Soccer", card.sport)
        assertTrue((listOf(card) + card.alternates).any { EspnLeagueMap.leaguesFor(it).isNotEmpty() })
        assertEquals(TeamPair("Qatar U17", "Panamá U17"), ScoreCard.from(card)?.teams)
    }

    @Test
    fun `women's and men's copies of one pairing stay apart`() {
        val women = event("a", "w", "Purdue at Florida State", genres = listOf("NCAAW"))
        val men = event("b", "m", "Purdue at Florida State", genres = listOf("NCAAM"))
        val college = event("c", "c", "Purdue at Florida State", genres = listOf("COLLEGE"))
        assertEquals(2, SportsLiveItemBucketer.bucketTab(listOf(women, men), SportsLiveTab.LIVE_NOW, now).size)
        // A copy that says neither joins either.
        val merged = SportsLiveItemBucketer.bucketTab(listOf(women, college), SportsLiveTab.LIVE_NOW, now, addonOrder = listOf("a", "c"))
        assertEquals(2, merged.single().sourceCount)
    }

    @Test
    fun `a basketball ticked with no league subset lets women's games through`() {
        val ncaaw = event("a", "w", "Purdue at Florida State", genres = listOf("NCAAW"))
        val nwsl = event("a", "n", "Portland Thorns vs Orlando Pride", genres = listOf("NWSL"))
        val ticked = MySports.decode(setOf("basketball", "soccer"))
        assertTrue(SportsLiveMySportsScope.shows(ncaaw, ticked))
        assertTrue(SportsLiveMySportsScope.shows(nwsl, ticked))
        // Leagues named without them: out.
        assertFalse(SportsLiveMySportsScope.shows(ncaaw, MySports.decode(setOf("basketball/nba"))))
        assertFalse(SportsLiveMySportsScope.shows(nwsl, MySports.decode(setOf("soccer/eng.1"))))
        assertTrue(SportsLiveMySportsScope.shows(ncaaw, MySports.decode(setOf("basketball/womens-college-basketball"))))
    }

    @Test
    fun `a school's St is State, so two addons' copies of one college game merge (user TV report)`() {
        assertEquals(SportsLiveTeams.MergeMatch.FULL, SportsLiveTeams.mergeMatch("Oklahoma St", "Oklahoma State"))
        assertEquals(SportsLiveTeams.MergeMatch.PREFIX, SportsLiveTeams.mergeMatch("Oklahoma St.", "Oklahoma State Cowboys"))
        // A first St is Saint, as before.
        assertEquals(SportsLiveTeams.MergeMatch.FULL, SportsLiveTeams.mergeMatch("St Johns", "St. John's"))
        val rows = SportsLiveItemBucketer.bucketTab(
            listOf(
                event("a", "1", "Minnesota Golden Gophers vs Oklahoma State Cowboys", genres = listOf("Basketball")),
                event("b", "2", "Minnesota vs Oklahoma St", genres = listOf("Basketball")),
                event("c", "3", "Oklahoma St. at Minnesota", genres = listOf("Basketball"))
            ),
            SportsLiveTab.LIVE_NOW, now, addonOrder = listOf("a", "b", "c")
        )
        assertEquals(1, rows.size)
        assertEquals(3, rows.single().sourceCount)
    }

    private fun event(addonId: String, id: String, name: String, genres: List<String> = emptyList()): SportsLiveItem =
        SportsLiveItemBucketer.describe(
            MetaPreview(
                id = id, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
                posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
                releaseInfo = "LIVE", imdbRating = null, genres = genres
            ),
            claimed(addonId),
            now
        )

    private fun claimed(addonId: String): ClaimedCatalog {
        val catalog = CatalogDescriptor(type = ContentType.fromString("sport"), rawType = "sport", id = "events", name = "Events")
        val addon = Addon(
            id = addonId, name = "Sports $addonId", version = "1.0.0", description = null, logo = null,
            baseUrl = "https://example.invalid", catalogs = listOf(catalog), types = listOf(catalog.type), resources = emptyList()
        )
        return ClaimedCatalog(addon, catalog, "${addonId}_sport_events", SportsLiveKind.EVENTS, false)
    }
}
