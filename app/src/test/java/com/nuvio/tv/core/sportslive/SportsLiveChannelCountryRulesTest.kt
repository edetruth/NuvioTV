package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.TeamDirectory
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ClaimedCatalog
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Field reports #26 and #27 (live.25): Channels listed 2 channels "in your countries" out of hundreds. A channel whose
 * catalog wrote no country was filed under its catalog's name ("SPORTS - TV (ACME)"), or under Tuvalu for a catalog
 * named "<Sport> - TV", neither a country nor "No country listed", so the viewer's countries left it out. Synthetic
 * names only.
 */
class SportsLiveChannelCountryRulesTest {
    private val now = Instant.parse("2026-11-22T20:00:00Z").toEpochMilli()

    // --- The country a channel's catalog and genres give ---

    @Test
    fun `a catalog named Sport - TV is no country, its channels' own prefixes are`() {
        val items = describe("Channels - TV", meta("📺 US : TENNIS CHANNEL"), meta("📺 Some Channel"), meta("📺 Other Channel"))
        assertEquals("US", SportsLiveChannelCountries.countryOf(items[0]))
        assertEquals(SportsLiveChannelRegions.OTHER, SportsLiveChannelCountries.countryOf(items[1]))
        // Never Tuvalu, read off the catalog name's last word.
        items.forEach { assertTrue(it.meta.name, SportsLiveChannelRegions.regionOf(it) != "TV") }
    }

    @Test
    fun `AR before an Arabic title is Arabic, before a Latin one Argentina`() {
        assertEquals("ARB", SportsLiveChannelRegions.regionOf(channel("AR| قناة الرياضة", null)))
        assertEquals("AR", SportsLiveChannelRegions.regionOf(channel("AR| TyC SPORTS", null)))
    }

    @Test
    fun `a dashed tag still names its country`() {
        assertEquals("IN", SportsLiveChannelRegions.regionOf(channel("STAR SPORTS 1", "ASIA-IN")))
        assertEquals("BR", SportsLiveChannelRegions.regionOf(channel("BAND SPORTS", "LATIN-BR")))
    }

    @Test
    fun `a country written in brackets after the name is the channel's country`() {
        assertEquals("US", SportsLiveChannelRegions.regionOf(channel("📺 ESPN (United States)", null)))
        assertEquals("PL", SportsLiveChannelRegions.regionOf(channel("📺 Discovery Channel (Poland)", null)))
        assertEquals("UK", SportsLiveChannelRegions.regionOf(channel("Sky Sports Golf (United Kingdom)", null)))
        // A bracket that names no country says nothing.
        assertEquals(SportsLiveChannelRegions.OTHER, SportsLiveChannelRegions.regionOf(channel("Some Channel (576i)", null)))
    }

    @Test
    fun `a genre group that starts with a country code gives the channel its country`() {
        val items = describe(
            "Sports - TV (Acme)",
            meta("📺 BBC ONE HD", "📺 UK| ✪ BBC GENERAL"),
            meta("📺 (NL) ESPN PLAY ESPN2", "📺 NL| ✪ ESPN PLAY"),
            meta("📺 NBA 08", "📺 NBA PACKAGE"),
            meta("📺 Plain", "📺 Sports")
        )
        assertEquals(listOf("UK", "NL", SportsLiveChannelRegions.OTHER, SportsLiveChannelRegions.OTHER), items.map(SportsLiveChannelCountries::countryOf))
    }

    @Test
    fun `a channel's own prefix beats its genre group`() {
        val items = describe("Sports - TV (Acme)", meta("US| FOX SPORTS 1", "📺 UK| ✪ SPORTS"))
        assertEquals("US", SportsLiveChannelCountries.countryOf(items[0]))
    }

    // --- The countries filter ---

    @Test
    fun `a channel filed under a catalog's or addon's name has no country listed, so your countries keep it`() {
        val named = channel("Some Channel", "SPORTS - TV (ACME)")
        val us = channel("US: ESPN", null)
        // The header's region filter still shows the long name (P4); the countries filter reads it as no country.
        assertEquals("SPORTS - TV (ACME)", SportsLiveChannelRegions.regionOf(named))
        assertEquals(SportsLiveChannelRegions.OTHER, SportsLiveChannelCountries.countryOf(named))
        val yours = SportsLiveChannelCountries.yours("US", emptyList())
        val selected = SportsLiveChannelCountries.selected(null, yours, listOf(named, us))
        assertTrue(SportsLiveChannelCountries.listed(named, selected))
        assertTrue(SportsLiveChannelCountries.listed(us, selected))
        // Not a country in the panel's lists either.
        assertEquals(listOf("US" to 1, SportsLiveChannelRegions.OTHER to 1).toSet(), SportsLiveChannelCountries.counts(listOf(named, us)).toSet())
    }

    @Test
    fun `channels of a catalog with no countries stay listed under the viewer's countries`() {
        val items = describe("Sports - TV (Acme)", *(1..20).map { meta("📺 Channel $it") }.toTypedArray()) +
            describe("Channels - TV", meta("📺 US : TENNIS CHANNEL"), meta("📺 Cartoon Channel (Pakistan)"))
        val yours = SportsLiveChannelCountries.yours("US", emptyList())
        val selected = SportsLiveChannelCountries.selected(null, yours, items)
        // 20 with no country and the US one; the Pakistani one is another country's.
        assertEquals(21, items.count { SportsLiveChannelCountries.listed(it, selected) })
    }

    // --- Report a problem ---

    @Test
    fun `the report line counts channels per source, merged and listed, with no names`() {
        val a = (1..3).map { channel("US: Channel $it", null, addonId = "a") }
        val b = listOf(channel("US: Channel 1", null, addonId = "b"), channel("FR: Canal 9", null, addonId = "b"))
        val items = a + b
        val merged = SportsLiveChannelList.merge(items)
        val line = SportsLiveChannelCountries.reportLine(items, merged, setOf("US", SportsLiveChannelRegions.OTHER))
        assertEquals("loaded 3, 2 from 2 sources · 4 channels · 3 listed in US, no country · left out: FR 1", line)
        assertEquals("loaded 3, 2 from 2 sources · 4 channels · 4 listed in every country", SportsLiveChannelCountries.reportLine(items, merged, null))
        assertEquals("no channels loaded", SportsLiveChannelCountries.reportLine(emptyList(), emptyList(), null))
    }

    private fun describe(catalogName: String, vararg metas: MetaPreview): List<SportsLiveItem> =
        SportsLiveItemBucketer.describeCatalog(metas.toList(), claimed(catalogName), now, TeamDirectory())

    private fun channel(name: String, group: String?, addonId: String = "a"): SportsLiveItem =
        SportsLiveItemBucketer.describe(meta(name), claimed("Channels", addonId), now, TeamDirectory()).copy(group = group)

    private fun meta(name: String, vararg genres: String) = MetaPreview(
        id = name, type = ContentType.UNKNOWN, rawType = "tv", name = name, poster = null,
        posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
        releaseInfo = null, imdbRating = null, genres = genres.toList()
    )

    private fun claimed(name: String, addonId: String = "a"): ClaimedCatalog {
        val catalog = CatalogDescriptor(type = ContentType.fromString("tv"), rawType = "tv", id = name.lowercase(), name = name)
        val addon = Addon(
            id = addonId, name = "Addon $addonId", version = "1.0.0", description = null, logo = null,
            baseUrl = "https://example.invalid", catalogs = listOf(catalog), types = listOf(catalog.type), resources = emptyList()
        )
        return ClaimedCatalog(addon, catalog, "${addonId}_tv_${catalog.id}", SportsLiveKind.CHANNELS, false)
    }
}
