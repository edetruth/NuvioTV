package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLiveZapPanelTest {

    private val football1 = item("f1", sport = "Football")
    private val football2 = item("f2", sport = "Football")
    private val basketball = item("b1", sport = "Basketball")
    private val upcoming = item("u1", sport = "Football", isLive = false, startMs = NOW + 3_600_000)
    private val unclaimed = item("x1", sport = "Football", catalogKey = "other:tv:live")
    private val all = listOf(football1, basketball, upcoming, unclaimed, football2)

    private fun session(tab: SportsLiveTab, filter: String?, on: SportsLiveItem = football1) =
        SportsLiveZapSession.of(listOf(football1, football2, basketball), on.streamKey, tab, filter, false)!!

    @Test
    fun `panel lists live games of claimed catalogs without ended ones`() {
        val items = liveGamesPanelItems(all, setOf(CATALOG), setOf(football2.streamKey), session(SportsLiveTab.LIVE_NOW, null), NOW)

        assertEquals(setOf("f1", "b1"), items.map { it.id }.toSet())
    }

    @Test
    fun `panel keeps the session's sport filter only for a live now session`() {
        val liveNow = liveGamesPanelItems(all, setOf(CATALOG), emptySet(), session(SportsLiveTab.LIVE_NOW, "Football"), NOW)
        val channels = liveGamesPanelItems(all, setOf(CATALOG), emptySet(), session(SportsLiveTab.CHANNELS, "UK"), NOW)

        assertEquals(setOf("f1", "f2"), liveNow.map { it.id }.toSet())
        assertEquals(setOf("f1", "f2", "b1"), channels.map { it.id }.toSet())
    }

    @Test
    fun `panel is empty without a load`() {
        assertTrue(liveGamesPanelItems(emptyList(), setOf(CATALOG), emptySet(), session(SportsLiveTab.LIVE_NOW, null), NOW).isEmpty())
    }

    @Test
    fun `picking a game of the session moves it there`() {
        val picked = session(SportsLiveTab.LIVE_NOW, "Football").pickFromPanel(basketball, listOf(football1))!!

        assertEquals(2, picked.index)
        assertEquals(3, picked.items.size)
        assertEquals("Football", picked.filter)
    }

    @Test
    fun `picking another game starts a live now session over the panel list`() {
        val other = item("o1", sport = "Tennis")
        val start = SportsLiveZapSession.of(listOf(football1), football1.streamKey, SportsLiveTab.CHANNELS, "UK", true)!!

        val picked = start.pickFromPanel(other, listOf(football1, other))!!

        assertEquals(listOf("f1", "o1"), picked.items.map { it.id })
        assertEquals(1, picked.index)
        assertEquals(SportsLiveTab.LIVE_NOW, picked.tab)
        assertNull(picked.filter)
        assertTrue(picked.returnToHomeOnBack)
        assertNull(start.pickFromPanel(other, listOf(football1)))
    }

    @Test
    fun `banner names the game, its league and addon, and its position`() {
        val banner = session(SportsLiveTab.LIVE_NOW, null, on = football2).banner(switching = false)

        assertEquals("f2", banner.name)
        assertEquals("Football · Sports", banner.detail)
        assertEquals(2, banner.position)
        assertEquals(3, banner.total)
        assertFalse(banner.switching)
        val noSport = SportsLiveZapSession.of(listOf(item("n1", sport = null)), "addon|n1", SportsLiveTab.LIVE_NOW, null, false)!!
        assertEquals("Live · Sports", noSport.banner(switching = true).detail)
        assertTrue(noSport.banner(switching = true).switching)
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val CATALOG = "addon:tv:live"

        fun item(
            id: String,
            sport: String?,
            isLive: Boolean = true,
            startMs: Long? = null,
            catalogKey: String = CATALOG
        ) = SportsLiveItem(
            meta = MetaPreview(
                id = id,
                type = ContentType.UNKNOWN,
                rawType = "tv",
                name = id,
                poster = null,
                posterShape = PosterShape.LANDSCAPE,
                background = null,
                logo = null,
                description = null,
                releaseInfo = null,
                imdbRating = null,
                genres = emptyList()
            ),
            addonId = "addon",
            addonName = "Sports",
            addonBaseUrl = "https://addon",
            catalogKey = catalogKey,
            catalogName = "Live",
            kind = SportsLiveKind.EVENTS,
            isLive = isLive,
            isChannel = false,
            startMs = startMs,
            sport = sport
        )
    }
}
