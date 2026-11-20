package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ClaimedCatalog
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** live.20 C1: a game moves from Upcoming to Live now at tip-off by the clock alone, without a catalog refresh. */
class SportsLiveKickoffTest {
    private val loadedAt = Instant.parse("2026-11-20T17:20:00Z").toEpochMilli()
    private val tipOff = Instant.parse("2026-11-20T17:30:00Z").toEpochMilli()
    private val minute = 60_000L
    private val hour = 60 * minute
    private val events = claimed()

    private fun game(id: String = "minn", name: String = "Minnesota vs Oklahoma State", status: String = "") =
        SportsLiveItemBucketer.describe(meta(id, status + name, released = "2026-11-20T17:30:00Z"), events, loadedAt)

    @Test
    fun `a game loaded before its tip-off is upcoming then, and live after it without a new load`() {
        val item = game()
        assertFalse(item.isLive)
        assertTrue(item.liveByClock)
        assertEquals(listOf("minn"), SportsLiveItemBucketer.bucket(listOf(item), loadedAt).getValue(SportsLiveTab.UPCOMING).map { it.id })

        val tabs = SportsLiveItemBucketer.bucket(listOf(item), tipOff + minute)
        val live = tabs.getValue(SportsLiveTab.LIVE_NOW).single()
        assertEquals("minn", live.id)
        assertTrue(live.isLive)
        assertTrue(tabs.getValue(SportsLiveTab.UPCOMING).isEmpty())
        // Home's row and the player's panel read one tab the same way.
        assertEquals(listOf("minn"), SportsLiveItemBucketer.bucketTab(listOf(item), SportsLiveTab.LIVE_NOW, tipOff).map { it.id })
    }

    @Test
    fun `its live window ends by the clock too`() {
        val item = SportsLiveItemBucketer.reclock(game(), tipOff + minute)
        assertTrue(item.isLive)
        val later = SportsLiveItemBucketer.bucket(listOf(item), tipOff + 5 * hour)
        assertTrue(later.getValue(SportsLiveTab.LIVE_NOW).isEmpty())
    }

    @Test
    fun `an addon's own soon or live status is never overruled by the clock`() {
        val soon = game(id = "soon", status = "🔜 ")
        assertFalse(soon.liveByClock)
        assertTrue(SportsLiveItemBucketer.bucket(listOf(soon), tipOff + minute).getValue(SportsLiveTab.LIVE_NOW).isEmpty())
        val marked = SportsLiveItemBucketer.describe(meta("marked", "A vs B", releaseInfo = "LIVE"), events, loadedAt)
        assertFalse(marked.liveByClock)
        assertSame(marked, SportsLiveItemBucketer.reclock(marked, tipOff + 9 * hour))
    }

    @Test
    fun `reclock keeps the same list when nothing changes`() {
        val items = listOf(game())
        assertSame(items, SportsLiveItemBucketer.reclock(items, loadedAt))
    }

    @Test
    fun `the next clock change is the next tip-off then the end of its window`() {
        val items = listOf(game())
        assertEquals(tipOff, SportsLiveItemBucketer.nextClockChange(items, loadedAt))
        assertEquals(tipOff + 4 * hour, SportsLiveItemBucketer.nextClockChange(items, tipOff))
        assertNull(SportsLiveItemBucketer.nextClockChange(items, tipOff + 4 * hour))
        assertTrue(SportsLiveItemBucketer.clockChangedBetween(items, loadedAt, tipOff))
        assertFalse(SportsLiveItemBucketer.clockChangedBetween(items, loadedAt, tipOff - 1))
    }

    @Test
    fun `tabs built earlier move a started game to live, sorted in, without bucketing again`() {
        val other = game(id = "early", name = "Alpha vs Beta")
        val tabs = SportsLiveItemBucketer.bucket(listOf(other, game()), loadedAt)
        assertEquals(2, tabs.getValue(SportsLiveTab.UPCOMING).size)
        val moved = SportsLiveItemBucketer.moveKickedOff(tabs, tipOff + minute)
        assertEquals(listOf("early", "minn"), moved.getValue(SportsLiveTab.LIVE_NOW).map { it.id })
        assertTrue(moved.getValue(SportsLiveTab.UPCOMING).isEmpty())
        assertSame(tabs, SportsLiveItemBucketer.moveKickedOff(tabs, loadedAt))
    }

    @Test
    fun `the live marker and the live now phrase are read without a regex as before`() {
        assertTrue(SportsLiveItemBucketer.isLiveMarker("LIVE"))
        assertTrue(SportsLiveItemBucketer.isLiveMarker("🔴 Live"))
        assertTrue(SportsLiveItemBucketer.isLiveMarker("12:00 - LIVE2"))
        assertFalse(SportsLiveItemBucketer.isLiveMarker("Alive"))
        assertFalse(SportsLiveItemBucketer.isLiveMarker("LIVELY"))
        assertFalse(SportsLiveItemBucketer.isLiveMarker(null))
        assertTrue(SportsLiveItemBucketer.saysLiveNow("Watch LIVE NOW on the app"))
        assertTrue(SportsLiveItemBucketer.saysLiveNow("live now"))
        assertFalse(SportsLiveItemBucketer.saysLiveNow("Alive nowhere"))
        assertTrue(SportsLiveItemBucketer.saysLiveNow("alive now, then live now."))
        assertFalse(SportsLiveItemBucketer.saysLiveNow("live nowadays"))
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

    private fun meta(id: String, name: String, releaseInfo: String? = null, released: String? = null) = MetaPreview(
        id = id,
        type = ContentType.UNKNOWN,
        rawType = "tv",
        name = name,
        poster = null,
        posterShape = PosterShape.LANDSCAPE,
        background = null,
        logo = null,
        description = null,
        releaseInfo = releaseInfo,
        imdbRating = null,
        genres = listOf("Basketball"),
        released = released
    )
}
