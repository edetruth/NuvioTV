package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ClaimedCatalog
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.repository.SportsLiveLoad
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/** live.20: one sorting per load, shared by the section, Home's row and the player's panel. */
class SportsLiveSharedBucketsTest {
    private val loadedAt = Instant.parse("2026-11-20T17:20:00Z").toEpochMilli()
    private val tipOff = Instant.parse("2026-11-20T17:30:00Z").toEpochMilli()
    private val catalog = claimed()
    private val keys = setOf(catalog.key)
    private val order = listOf(catalog.addon.id)

    private fun describe(id: String, name: String, released: String) =
        SportsLiveItemBucketer.describe(meta(id, name, released), catalog, loadedAt)

    private val load = SportsLiveLoad(
        items = listOf(
            describe("live", "Alpha vs Beta", "2026-11-20T17:00:00Z"),
            describe("next", "Gamma vs Delta", "2026-11-20T17:30:00Z")
        ),
        failures = emptyList(),
        loadedAtMs = loadedAt,
        claimedKeys = keys
    )

    @Test
    fun `each tab is the one bucketTab gives, built once per load`() {
        val shared = SportsLiveSharedBuckets()
        val live = shared.tab(load, keys, order, SportsLiveTab.LIVE_NOW, loadedAt, stamp = 0L)
        assertEquals(SportsLiveItemBucketer.bucketTab(load.items, SportsLiveTab.LIVE_NOW, loadedAt, order), live)
        assertSame(live, shared.tab(load, keys, order, SportsLiveTab.LIVE_NOW, loadedAt + 60_000L, stamp = 0L))
        assertSame(live, shared.all(load, keys, order, loadedAt, stamp = 0L).getValue(SportsLiveTab.LIVE_NOW))
        assertEquals(SportsLiveItemBucketer.bucket(load.items, loadedAt, order), shared.all(load, keys, order, loadedAt, stamp = 0L))
    }

    @Test
    fun `a new load, catalogs, team directory, tip-off or age builds it again`() {
        val shared = SportsLiveSharedBuckets(maxAgeMs = 5 * 60_000L)
        val live = shared.tab(load, keys, order, SportsLiveTab.LIVE_NOW, loadedAt, stamp = 0L)
        // live.24: a new load listing the same games keeps the rows built (incremental bucketing); a changed game does not.
        assertSame(live, shared.tab(load.copy(), keys, order, SportsLiveTab.LIVE_NOW, loadedAt, stamp = 0L))
        val renamed = load.copy(items = listOf(describe("live", "Alpha vs Beta II", "2026-11-20T17:00:00Z")) + load.items.drop(1))
        assertNotSame(live, shared.tab(renamed, keys, order, SportsLiveTab.LIVE_NOW, loadedAt, stamp = 0L))
        val again = shared.tab(load, keys, order, SportsLiveTab.LIVE_NOW, loadedAt, stamp = 0L)
        assertNotSame(again, shared.tab(load, keys, order, SportsLiveTab.LIVE_NOW, loadedAt, stamp = 1L))
        val stamped = shared.tab(load, keys, order, SportsLiveTab.LIVE_NOW, loadedAt, stamp = 1L)
        assertNotSame(stamped, shared.tab(load, keys + "other", order, SportsLiveTab.LIVE_NOW, loadedAt, stamp = 1L))
        // The next game's tip-off: it is live now.
        val atTip = shared.tab(load, keys, order, SportsLiveTab.LIVE_NOW, tipOff, stamp = 1L)
        assertEquals(listOf("live", "next"), atTip.map { it.id }.sorted())
        // Over the age limit.
        assertNotSame(atTip, shared.tab(load, keys, order, SportsLiveTab.LIVE_NOW, tipOff + 5 * 60_000L, stamp = 1L))
    }

    private fun claimed(): ClaimedCatalog {
        val catalog = CatalogDescriptor(type = ContentType.fromString("tv"), rawType = "tv", id = "games", name = "Games")
        val addon = Addon(
            id = "a",
            name = "Source A",
            version = "1.0.0",
            description = null,
            logo = null,
            baseUrl = "https://a.example.invalid",
            catalogs = listOf(catalog),
            types = listOf(catalog.type),
            resources = emptyList()
        )
        return ClaimedCatalog(addon, catalog, "a_tv_games", SportsLiveKind.EVENTS)
    }

    private fun meta(id: String, name: String, released: String) = MetaPreview(
        id = id,
        type = ContentType.UNKNOWN,
        rawType = "tv",
        name = name,
        poster = null,
        posterShape = PosterShape.LANDSCAPE,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = listOf("Basketball"),
        released = released
    )
}
