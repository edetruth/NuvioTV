package com.nuvio.tv.core.sportslive

import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.dto.CatalogDescriptorDto
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogExtra
import com.nuvio.tv.domain.model.CatalogOption
import com.nuvio.tv.domain.model.ContentType
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveCatalogOptionsTest {
    @Test
    fun `the CNCVerse live TV catalog offers its genre extra of countries`() {
        val liveTv = cncCatalogs().single { it.id == "cnc_EasyPlayLiveTV_tv" }

        val extra = SportsLiveCatalogOptions.eligibleExtra(liveTv)!!

        assertEquals("genre", extra.name)
        assertTrue("United States" in extra.options.orEmpty())
        assertTrue(extra.options.orEmpty().size > 100)
    }

    @Test
    fun `every CNCVerse tv catalog offers genre, not search or skip`() {
        val catalogs = cncCatalogs()
        assertTrue(catalogs.size > 1)
        catalogs.forEach { assertEquals(it.id, "genre", SportsLiveCatalogOptions.eligibleExtra(it)?.name) }
    }

    @Test
    fun `genre is preferred over an earlier eligible extra`() {
        val catalog = catalog(
            CatalogExtra("sport", options = listOf("Football")),
            CatalogExtra("genre", options = listOf("United States"))
        )
        assertEquals("genre", SportsLiveCatalogOptions.eligibleExtra(catalog)?.name)
    }

    @Test
    fun `without genre the first eligible extra is used`() {
        val catalog = catalog(
            CatalogExtra("search"),
            CatalogExtra("sport", options = listOf("Football")),
            CatalogExtra("league", options = listOf("NFL"))
        )
        assertEquals("sport", SportsLiveCatalogOptions.eligibleExtra(catalog)?.name)
    }

    @Test
    fun `required extras and extras without options are not eligible`() {
        assertNull(SportsLiveCatalogOptions.eligibleExtra(catalog(CatalogExtra("genre", isRequired = true, options = listOf("A")))))
        assertNull(SportsLiveCatalogOptions.eligibleExtra(catalog(CatalogExtra("genre", options = emptyList()))))
        assertNull(SportsLiveCatalogOptions.eligibleExtra(catalog(CatalogExtra("genre"), CatalogExtra("skip"))))
        assertNull(SportsLiveCatalogOptions.eligibleExtra(catalog()))
        assertNull(
            SportsLiveCatalogOptions.eligibleExtra(
                catalog(CatalogExtra("genre", options = listOf("A"))).copy(extraRequired = listOf("genre"))
            )
        )
    }

    @Test
    fun `a stored pin counts only while the catalog still offers it`() {
        val catalog = catalog(CatalogExtra("genre", options = listOf("United States", "Canada")))
        val us = CatalogOption("genre", "United States")

        assertEquals(us, SportsLiveCatalogOptions.effectiveOption(catalog, us))
        assertNull(SportsLiveCatalogOptions.effectiveOption(catalog, null))
        assertNull(SportsLiveCatalogOptions.effectiveOption(catalog, CatalogOption("genre", "Atlantis")))
        assertNull(SportsLiveCatalogOptions.effectiveOption(catalog, CatalogOption("sport", "United States")))
        assertNull(SportsLiveCatalogOptions.effectiveOption(catalog(), us))
    }

    internal data class ManifestCatalogs(val catalogs: List<CatalogDescriptorDto>)

    /** Catalog descriptors from CNCVerse's manifest (tv catalogs only, no URLs). */
    private fun cncCatalogs(): List<CatalogDescriptor> {
        val json = javaClass.classLoader!!.getResourceAsStream("sportslive/cnc_manifest_catalogs.json")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        return Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            .adapter(ManifestCatalogs::class.java)
            .fromJson(json)!!
            .catalogs.map { it.toDomain() }
    }

    private fun catalog(vararg extras: CatalogExtra) = CatalogDescriptor(
        type = ContentType.fromString("tv"),
        rawType = "tv",
        id = "live",
        name = "Live TV",
        extra = extras.toList()
    )
}
