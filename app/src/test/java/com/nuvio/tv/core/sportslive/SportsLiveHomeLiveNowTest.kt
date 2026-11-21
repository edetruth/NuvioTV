package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.MySport
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ClaimedCatalog
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.ui.screens.sportslive.liveNowItemTag
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** RC TV finding (live.23): Home's Live now row follows Live Now's rules, order and tags. */
class SportsLiveHomeLiveNowTest {
    private val now = Instant.parse("2026-11-21T15:00:00Z").toEpochMilli()
    private val soccer = MySports(mapOf(MySport.SOCCER to emptySet()))
    private val sameItem: (SportsLiveItem) -> SportsLiveItem = { it }

    @Test
    fun `listings that are no game leave Home's row under My sports, as they leave Live now`() {
        val game = event("a", "g", "Aston Villa vs Sunderland", genres = listOf("Soccer"))
        val atp = event("a", "atp", "ATP", catalog = "PlayzTV Live Events")
        val stockholm = event("a", "s1", "ATP 250 · Stockholm Open", catalog = "SportzX Live Events")
        val row = SportsLiveHomeLiveNow.of(listOf(atp, game, stockholm), emptyList(), soccer, emptySet(), sameItem)
        assertEquals(listOf("g"), row.map { it.id })
    }

    @Test
    fun `copies of one listing are one card`() {
        val first = event("a", "s1", "ATP 250 · Stockholm Open", catalog = "SportzX Live Events")
        val second = event("b", "s2", "ATP 250 · Stockholm Open", catalog = "LivXow Live Events")
        val row = SportsLiveHomeLiveNow.of(listOf(first, second), emptyList(), MySports.DEFAULT, emptySet(), sameItem)
        assertEquals(1, row.size)
        assertEquals(listOf("s2"), row.single().alternates.map { it.id })
    }

    @Test
    fun `followed teams' games come first, as on Live now (J2)`() {
        val other = event("a", "o", "Arsenal vs Chelsea", genres = listOf("Soccer"))
        val mine = event("a", "m", "Liverpool vs Everton", genres = listOf("Soccer"))
        val row = SportsLiveHomeLiveNow.of(listOf(other, mine), listOf(FollowedTeam("Liverpool", null)), soccer, emptySet(), sameItem)
        assertEquals(listOf("m", "o"), row.map { it.id })
    }

    @Test
    fun `an addon's catalog name is never a card's tag`() {
        val game = event("a", "g", "Aston Villa vs Sunderland", genres = listOf("⚡PLAYZTV"), catalog = "⚡PlayzTV Live Events (tv)")
        val tag = liveNowItemTag(game, null) { it.name }
        assertFalse(tag, tag.contains("PLAYZ"))
    }

    @Test
    fun `the card's tag is Live Now's league tag`() {
        val game = event("a", "g", "Aston Villa vs Sunderland", genres = listOf("Premier League"))
        assertEquals("EPL", liveNowItemTag(game, null) { it.name })
    }

    private fun event(
        addonId: String,
        id: String,
        name: String,
        genres: List<String> = emptyList(),
        catalog: String = "Live Events"
    ): SportsLiveItem =
        SportsLiveItemBucketer.describe(
            MetaPreview(
                id = id, type = ContentType.UNKNOWN, rawType = "sport", name = name, poster = null,
                posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
                releaseInfo = "LIVE", imdbRating = null, genres = genres
            ),
            claimed(addonId, catalog),
            now
        )

    private fun claimed(addonId: String, name: String): ClaimedCatalog {
        val catalog = CatalogDescriptor(type = ContentType.fromString("sport"), rawType = "sport", id = name.lowercase().replace(' ', '_'), name = name)
        val addon = Addon(
            id = addonId, name = "Sports $addonId", version = "1.0.0", description = null, logo = null,
            baseUrl = "https://example.invalid", catalogs = listOf(catalog), types = listOf(catalog.type), resources = emptyList()
        )
        return ClaimedCatalog(addon, catalog, "${addonId}_sport_${catalog.id}", SportsLiveKind.EVENTS, false)
    }
}
