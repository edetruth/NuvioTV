package com.nuvio.tv.core.sportslive

import com.nuvio.tv.data.local.SportsLiveListSnapshotStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ClaimedCatalog
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** live.20 B1: the last list on the device, per profile, without secrets; too old or unreadable restores as nothing. */
class SportsLiveListSnapshotTest {
    private val moshi = Moshi.Builder().build()
    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private val token = "s3cr3tT0k3n"
    private val catalogA = claimed("a", "https://a.example.invalid/$token/manifest.json")
    private val catalogB = claimed("b", "https://b.example.invalid/other/manifest.json")

    private fun item(
        id: String,
        catalog: ClaimedCatalog = catalogA,
        isLive: Boolean = true,
        startMs: Long? = now - hour,
        poster: String? = null,
        alternates: List<SportsLiveItem> = emptyList(),
        liveByClock: Boolean = false
    ) = SportsLiveItem(
        meta = MetaPreview(
            id = id,
            type = ContentType.TV,
            rawType = "tv",
            name = "Team $id vs Other $id",
            poster = poster,
            posterShape = PosterShape.LANDSCAPE,
            background = null,
            logo = "https://img.example.invalid/$id.png",
            description = "A game",
            releaseInfo = null,
            imdbRating = null,
            genres = listOf("Basketball"),
            sourceAddonBaseUrl = catalog.addon.baseUrl
        ),
        addonId = catalog.addon.id,
        addonName = catalog.addon.name,
        addonBaseUrl = catalog.addon.baseUrl,
        catalogKey = catalog.key,
        catalogName = catalog.catalog.name,
        kind = SportsLiveKind.EVENTS,
        isLive = isLive,
        isChannel = false,
        startMs = startMs,
        sport = "Basketball",
        startHasTimeOfDay = startMs != null,
        teams = TeamPair("Team $id", "Other $id"),
        alternates = alternates,
        liveByClock = liveByClock
    )

    private fun snapshot(vararg live: SportsLiveItem, savedAtMs: Long = now - 10 * minute) =
        SportsLiveListSnapshot(savedAtMs = savedAtMs, tabs = mapOf(SportsLiveTab.LIVE_NOW to live.toList()))

    @Test
    fun `a list goes to the device and back`() {
        val original = item("1", alternates = listOf(item("1b", catalogB)))
        val decoded = SportsLiveListSnapshots.decode(SportsLiveListSnapshots.encode(snapshot(original), moshi), moshi)!!
        assertEquals(now - 10 * minute, decoded.savedAtMs)
        val restored = SportsLiveListSnapshots.restore(decoded, listOf(catalogA, catalogB), now)!!
        val back = restored.getValue(SportsLiveTab.LIVE_NOW).single()
        // Everything the lists read is back; the addon URL is the claimed catalog's.
        assertEquals(original.copy(meta = original.meta.copy(sourceAddonBaseUrl = null), alternates = back.alternates), back)
        assertEquals(original.alternates.single().copy(meta = original.alternates.single().meta.copy(sourceAddonBaseUrl = null)), back.alternates.single())
        assertEquals(SportsLiveTab.entries.toSet(), restored.keys)
    }

    @Test
    fun `no addon url or token is written, nor an image on the addon's own host`() {
        val json = SportsLiveListSnapshots.encode(
            snapshot(item("1", poster = "https://a.example.invalid/$token/poster.png"), item("2", poster = "https://img.example.invalid/2.png")),
            moshi
        )
        assertFalse(json.contains(token))
        assertFalse(json.contains("a.example.invalid"))
        assertTrue(json.contains("https://img.example.invalid/2.png"))
        val decoded = SportsLiveListSnapshots.decode(json, moshi)!!
        assertTrue(decoded.tabs.getValue(SportsLiveTab.LIVE_NOW).all { it.addonBaseUrl.isEmpty() && it.meta.sourceAddonBaseUrl == null })
    }

    @Test
    fun `only claimed catalogs come back, a merged copy of another is dropped from its game`() {
        val list = snapshot(item("1", alternates = listOf(item("1b", catalogB))), item("2", catalogB))
        val restored = SportsLiveListSnapshots.restore(list, listOf(catalogA), now)!!.getValue(SportsLiveTab.LIVE_NOW)
        assertEquals(listOf("1"), restored.map { it.id })
        assertTrue(restored.single().alternates.isEmpty())
        assertNull(SportsLiveListSnapshots.restore(snapshot(item("2", catalogB)), listOf(catalogA), now))
        assertNull(SportsLiveListSnapshots.restore(list, emptyList(), now))
    }

    @Test
    fun `a list too old or from the future is not shown`() {
        val list = snapshot(item("1"), savedAtMs = now - SportsLiveListSnapshots.MAX_AGE_MS)
        assertNotNull(SportsLiveListSnapshots.restore(list, listOf(catalogA), now))
        assertNull(SportsLiveListSnapshots.restore(list, listOf(catalogA), now + 1))
        assertNull(SportsLiveListSnapshots.restore(snapshot(item("1"), savedAtMs = now + minute), listOf(catalogA), now))
    }

    @Test
    fun `a restored list still moves a game that has started since to live`() {
        val upcoming = item("u", isLive = false, startMs = now - 5 * minute, liveByClock = true)
        val list = SportsLiveListSnapshot(now - 20 * minute, mapOf(SportsLiveTab.UPCOMING to listOf(upcoming)))
        val restored = SportsLiveListSnapshots.restore(SportsLiveListSnapshots.decode(SportsLiveListSnapshots.encode(list, moshi), moshi)!!, listOf(catalogA), now)!!
        assertEquals(listOf("u"), restored.getValue(SportsLiveTab.LIVE_NOW).map { it.id })
        assertTrue(restored.getValue(SportsLiveTab.LIVE_NOW).single().isLive)
        assertTrue(restored.getValue(SportsLiveTab.UPCOMING).isEmpty())
    }

    @Test
    fun `an unreadable list or one of another version restores as nothing`() {
        assertNull(SportsLiveListSnapshots.decode(null, moshi))
        assertNull(SportsLiveListSnapshots.decode("not json", moshi))
        val json = SportsLiveListSnapshots.encode(snapshot(item("1")), moshi)
        assertNull(SportsLiveListSnapshots.decode(json.replace("\"version\":${SportsLiveListSnapshots.VERSION}", "\"version\":999"), moshi))
    }

    @Test
    fun `each tab keeps at most its cap on the device`() {
        val many = (1..SportsLiveListSnapshots.MAX_PER_TAB + 5).map { item("$it") }
        val json = SportsLiveListSnapshots.encode(SportsLiveListSnapshot(now, mapOf(SportsLiveTab.CHANNELS to many)), moshi)
        assertEquals(SportsLiveListSnapshots.MAX_PER_TAB, SportsLiveListSnapshots.decode(json, moshi)!!.tabs.getValue(SportsLiveTab.CHANNELS).size)
    }

    @Test
    fun `each profile keeps its own list in memory and on the device`() {
        val dir = Files.createTempDirectory("sports_lists").toFile()
        try {
            var profile = 1
            val store = SportsLiveListSnapshotStore(dir, moshi, { profile }, Dispatchers.Unconfined, writeDelayMs = Long.MAX_VALUE)
            val first = snapshot(item("1"))
            val second = snapshot(item("2"))
            store.save(first)
            profile = 2
            assertNull(store.peek())
            store.save(second)
            assertEquals(second, store.peek())
            profile = 1
            assertEquals(first, store.peek())

            store.write(1, first)
            store.write(2, second)
            val fresh = SportsLiveListSnapshotStore(dir, moshi, { profile }, Dispatchers.Unconfined)
            profile = 2
            assertEquals(listOf("2"), runBlocking { fresh.read() }!!.tabs.getValue(SportsLiveTab.LIVE_NOW).map { it.id })
            profile = 1
            assertEquals(listOf("1"), runBlocking { fresh.read() }!!.tabs.getValue(SportsLiveTab.LIVE_NOW).map { it.id })
            profile = 3
            assertNull(runBlocking { fresh.read() })
            // Nothing of the addon's URL on the device.
            assertTrue(dir.walk().filter { it.isFile }.none { it.readText().contains(token) })
            File(dir, "1.json").writeText("garbage")
            assertNull(fresh.readFile(1))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun claimed(id: String, baseUrl: String): ClaimedCatalog {
        val catalog = CatalogDescriptor(type = ContentType.fromString("tv"), rawType = "tv", id = "games", name = "Games")
        val addon = Addon(
            id = id,
            name = "Source ${id.uppercase()}",
            version = "1.0.0",
            description = null,
            logo = null,
            baseUrl = baseUrl,
            catalogs = listOf(catalog),
            types = listOf(catalog.type),
            resources = emptyList()
        )
        return ClaimedCatalog(addon, catalog, "${id}_tv_games", SportsLiveKind.EVENTS)
    }
}
