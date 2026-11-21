package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SportsLiveSourceLabelsTest {

    @Test
    fun `cleanCatalog strips leading emoji and dots and trailing suffixes`() {
        assertEquals("SportzX", SportsLiveSourceLabels.cleanCatalog("⚡SportzX Live Events (tv)"))
        assertEquals("LivXow", SportsLiveSourceLabels.cleanCatalog("⚡LivXow Live Events (tv)"))
        assertEquals("PlayFy", SportsLiveSourceLabels.cleanCatalog("⚡ PlayFy Live Events (tv)"))
        assertEquals("SportzX", SportsLiveSourceLabels.cleanCatalog(".SportzX Live Events (tv)"))
        // "Live TV" is not the "Live Events" suffix, so it stays.
        assertEquals("EasyPlay Live TV", SportsLiveSourceLabels.cleanCatalog("EasyPlay Live TV (tv)"))
        assertEquals("Nebula Sports", SportsLiveSourceLabels.cleanCatalog("Nebula Sports"))
        assertNull(SportsLiveSourceLabels.cleanCatalog("⚡"))
        assertNull(SportsLiveSourceLabels.cleanCatalog("   "))
    }

    @Test
    fun `of labels a shared addon's sources by provider and a lone source by addon name`() {
        val primary = item("example", "Example Addon", "g0", "⚡SportzX Live Events (tv)")
        val merged = primary.copy(
            alternates = listOf(
                item("example", "Example Addon", "g1", "⚡LivXow Live Events (tv)"),
                item("example", "Example Addon", "g2", "⚡SKTech Live Events (tv)"),
                item("neb", "Nebula Sports", "g3", "Nebula Sports: Live Now")
            )
        )

        val labels = SportsLiveSourceLabels.of(merged)

        assertEquals("SportzX", labels["example|g0"])
        assertEquals("LivXow", labels["example|g1"])
        assertEquals("SKTech", labels["example|g2"])
        // The lone source of its addon keeps the addon name, not the cleaned catalog name.
        assertEquals("Nebula Sports", labels["neb|g3"])
    }

    @Test
    fun `of falls back to the addon name when the catalog cleans to nothing`() {
        val primary = item("example", "Example Addon", "g0", "⚡")
        val merged = primary.copy(alternates = listOf(item("example", "Example Addon", "g1", "⚡LivXow Live Events (tv)")))

        assertEquals("Example Addon", SportsLiveSourceLabels.of(merged)["example|g0"])
    }

    private fun item(addonId: String, addonName: String, id: String, catalogName: String) = SportsLiveItem(
        meta = MetaPreview(
            id = id,
            type = ContentType.UNKNOWN,
            rawType = "tv",
            name = "PAK vs SL",
            poster = null,
            posterShape = PosterShape.LANDSCAPE,
            background = null,
            logo = null,
            description = null,
            releaseInfo = "LIVE",
            imdbRating = null,
            genres = emptyList()
        ),
        addonId = addonId,
        addonName = addonName,
        addonBaseUrl = "https://$addonId.example",
        catalogKey = "${addonId}_tv_$id",
        catalogName = catalogName,
        kind = SportsLiveKind.EVENTS,
        isLive = true,
        isChannel = false,
        startMs = null,
        sport = null
    )
}
