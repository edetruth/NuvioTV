package com.nuvio.tv.core.sportslive

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
import org.junit.Assert.assertTrue
import org.junit.Test

/** Copies of one game that name a team differently (#174). */
class SportsLiveVariantMergeTest {
    private val now = Instant.parse("2026-11-19T16:00:00Z").toEpochMilli()

    @Test
    fun `three spellings of Barcelona W vs Servette Chenois W merge into one row`() {
        val rows = live(
            event("a", "1", "Barcelona W vs Servette Chênois W", genres = listOf("Football")),
            event("b", "2", "Barcelona Women vs Servette Chenois Women", genres = listOf("Soccer")),
            event("c", "3", "FC Barcelona W vs Servette FC Chênois W", genres = listOf("UWCL"))
        )
        assertEquals(listOf("1"), rows.map { it.id })
        assertEquals(3, rows.single().sourceCount)
    }

    @Test
    fun `Virtus Bologna merges with Virtus Pallacanestro Bologna`() {
        val rows = live(
            event("a", "1", "Valencia Basket vs Virtus Bologna", genres = listOf("Basketball")),
            event("b", "2", "Valencia Basket vs Virtus Pallacanestro Bologna", genres = listOf("Basketball"))
        )
        assertEquals(listOf("1"), rows.map { it.id })
        assertEquals(listOf("2"), rows.single().alternates.map { it.id })
        assertEquals(SportsLiveTeams.MergeMatch.PREFIX, SportsLiveTeams.mergeMatch("Virtus Bologna", "Virtus Pallacanestro Bologna"))
    }

    @Test
    fun `a word subset needs both sides, one sport and one start window`() {
        val otherHome = live(
            event("a", "1", "Valencia Basket vs Virtus Bologna", genres = listOf("Basketball")),
            event("b", "2", "Real Madrid vs Virtus Pallacanestro Bologna", genres = listOf("Basketball"))
        )
        assertEquals(2, otherHome.size)
        val otherSport = live(
            event("a", "1", "Valencia Basket vs Virtus Bologna", genres = listOf("Basketball")),
            event("b", "2", "Valencia Basket vs Virtus Pallacanestro Bologna", genres = listOf("Soccer"))
        )
        assertEquals(2, otherSport.size)
        val upcoming = SportsLiveItemBucketer.bucketTab(
            listOf(
                event("a", "1", "Valencia Basket vs Virtus Bologna", "19 Nov 2026 · 18:00 UTC", listOf("Basketball")),
                event("b", "2", "Valencia Basket vs Virtus Pallacanestro Bologna", "19 Nov 2026 · 18:15 UTC", listOf("Basketball")),
                event("c", "3", "Valencia Basket vs Virtus Pallacanestro Bologna", "19 Nov 2026 · 20:00 UTC", listOf("Basketball"))
            ),
            SportsLiveTab.UPCOMING, now, addonOrder = listOf("a", "b", "c")
        )
        assertEquals(listOf("1", "3"), upcoming.map { it.id })
        assertEquals(listOf("2"), upcoming.first().alternates.map { it.id })
    }

    @Test
    fun `two clubs of one city are no word subset`() {
        val none = SportsLiveTeams.MergeMatch.NONE
        assertEquals(none, SportsLiveTeams.mergeMatch("Manchester City", "Manchester United"))
        assertEquals(none, SportsLiveTeams.mergeMatch("Manchester City Women", "Manchester United Women"))
        // Filler words alone ("Racing", "Club") name no club.
        assertEquals(none, SportsLiveTeams.mergeMatch("Racing Club", "Racing Club de Montevideo"))
        assertEquals(none, SportsLiveTeams.mergeMatch("Bristol City", "Bristol Rovers"))
        assertEquals(none, SportsLiveTeams.mergeMatch("Virtus Bologna", "Virtus Pallacanestro Bologna W"))
    }

    @Test
    fun `NC State merges with North Carolina State`() {
        val rows = live(
            event("a", "1", "Murray State vs NC State", genres = listOf("Basketball")),
            event("b", "2", "Murray State vs North Carolina State", genres = listOf("Basketball"))
        )
        assertEquals(listOf("1"), rows.map { it.id })
        assertEquals(2, rows.single().sourceCount)
        assertEquals(SportsLiveTeams.MergeMatch.FULL, SportsLiveTeams.mergeMatch("N.C. State", "North Carolina State"))
        assertEquals(SportsLiveTeams.MergeMatch.FULL, SportsLiveTeams.mergeMatch("FIU", "Florida International"))
    }

    @Test
    fun `an unmarked copy of a women's competition game merges with it`() {
        val rows = live(
            event("a", "1", "Arsenal W vs OL Lyonnes W", genres = listOf("UWCL")),
            event("b", "2", "Arsenal vs OL Lyonnes", genres = listOf("FOOTBALL"))
        )
        assertEquals(listOf("1"), rows.map { it.id })
        assertEquals(2, rows.single().sourceCount)
        // The women's competition copy itself names no gender; the other marks one side only.
        val oneSide = live(
            event("a", "1", "Arsenal vs OL Lyonnes", genres = listOf("UWCL")),
            event("b", "2", "Arsenal Women vs OL Lyonnes", genres = listOf("FOOTBALL"))
        )
        assertEquals(2, oneSide.single().sourceCount)
    }

    @Test
    fun `a men's copy never merges with a women's one`() {
        val marked = live(
            event("a", "1", "Arsenal W vs OL Lyonnes W", genres = listOf("UWCL")),
            event("b", "2", "Arsenal vs OL Lyonnes", genres = listOf("Men's Football"))
        )
        assertEquals(2, marked.size)
        val sameTitles = live(
            event("a", "1", "Arsenal vs OL Lyonnes", genres = listOf("UWCL")),
            event("b", "2", "Arsenal vs OL Lyonnes (Men's)", genres = listOf("Football"))
        )
        assertEquals(2, sameTitles.size)
        // No women's competition: a "W" copy and a plain copy stay apart, as before.
        val plain = live(
            event("a", "1", "Arsenal W vs Chelsea W", genres = listOf("Soccer")),
            event("b", "2", "Arsenal vs Chelsea", genres = listOf("Soccer"))
        )
        assertEquals(2, plain.size)
        assertTrue((marked + sameTitles + plain).all { it.alternates.isEmpty() })
    }

    @Test
    fun `Manchester United vs City stay apart and their short names still merge`() {
        val rows = live(
            event("a", "1", "Manchester United vs Manchester City", genres = listOf("Soccer")),
            event("b", "2", "Man United vs Man City", genres = listOf("Soccer")),
            event("c", "3", "Manchester United vs Liverpool", genres = listOf("Soccer")),
            event("d", "4", "Manchester City vs Liverpool", genres = listOf("Soccer"))
        )
        assertEquals(setOf("1", "3", "4"), rows.map { it.id }.toSet())
        assertEquals(listOf("2"), rows.single { it.id == "1" }.alternates.map { it.id })
        assertTrue(rows.filter { it.id != "1" }.all { it.alternates.isEmpty() })
    }

    private fun live(vararg items: SportsLiveItem): List<SportsLiveItem> =
        SportsLiveItemBucketer.bucketTab(items.toList(), SportsLiveTab.LIVE_NOW, now, addonOrder = listOf("a", "b", "c", "d"))

    private fun event(
        addonId: String,
        id: String,
        name: String,
        releaseInfo: String = "LIVE",
        genres: List<String> = emptyList()
    ): SportsLiveItem =
        SportsLiveItemBucketer.describe(
            MetaPreview(
                id = id, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
                posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
                releaseInfo = releaseInfo, imdbRating = null, genres = genres
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
