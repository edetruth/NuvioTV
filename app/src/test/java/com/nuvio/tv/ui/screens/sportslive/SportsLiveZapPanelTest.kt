package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.FollowedTeam
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
        val items = liveGamesPanelItems(all, setOf(CATALOG), setOf(football2.streamKey), emptyList(), NOW)

        assertEquals(setOf("f1", "b1"), items.map { it.id }.toSet())
    }

    @Test
    fun `panel lists every live game, followed ones first, whatever the session's filter (#68)`() {
        val celtics = item("c1", sport = "Basketball").let { it.copy(teams = SportsLiveTeams.parse("Celtics vs Heat")) }
        val items = listOf(football1, basketball, celtics)
        val followed = listOf(FollowedTeam("Celtics", null))

        val all = liveGamesPanelItems(items, setOf(CATALOG), emptySet(), followed, NOW)

        assertEquals("c1", all.first().id)
        assertTrue(all.first().followed)
        assertEquals(setOf("c1", "f1", "b1"), all.map { it.id }.toSet())
    }

    @Test
    fun `panel chips are my teams, all, then each sport, and narrow the games (#68)`() {
        val mine = basketball.copy(followed = true)
        val items = listOf(mine, football1, football2)
        val my = SportsLiveUiState.MY_TEAMS_FILTER

        assertEquals(listOf(my, null, sportKey("Basketball"), sportKey("Football")), panelChips(items, null))
        assertEquals(listOf(null, sportKey("Football")), panelChips(listOf(football1), null))
        // A chip picked earlier stays listed when its games are gone.
        assertEquals(listOf(null, sportKey("Football"), sportKey("Tennis")), panelChips(listOf(football1), sportKey("Tennis")))
        assertEquals(listOf(my, null, sportKey("Football")), panelChips(listOf(football1), my))

        assertEquals(items, filterPanelItems(items, null))
        assertEquals(listOf(mine), filterPanelItems(items, my))
        assertEquals(listOf(football1, football2), filterPanelItems(items, sportKey("Football")))
        assertTrue(filterPanelItems(items, sportKey("Tennis")).isEmpty())
    }

    @Test
    fun `panel is empty without a load`() {
        assertTrue(liveGamesPanelItems(emptyList(), setOf(CATALOG), emptySet(), emptyList(), NOW).isEmpty())
    }

    @Test
    fun `picking a game of the session moves it there, another starts a live now session over the panel list`() {
        val inSession = session(SportsLiveTab.LIVE_NOW, "Football").pickFromPanel(basketball, listOf(football1))!!
        assertEquals(2, inSession.index)
        assertEquals(3, inSession.items.size)
        assertEquals("Football", inSession.filter)

        val other = item("o1", sport = "Tennis")
        val start = SportsLiveZapSession.of(listOf(football1), football1.streamKey, SportsLiveTab.CHANNELS, "UK", true)!!

        val picked = start.pickFromPanel(other, listOf(football1, other))!!

        assertEquals(listOf("f1", "o1"), picked.items.map { it.id })
        assertEquals(1, picked.index)
        assertEquals(SportsLiveTab.LIVE_NOW, picked.tab)
        assertNull(picked.filter)
        assertTrue(picked.returnToHomeOnBack)
        assertNull(start.pickFromPanel(other, listOf(football1)))
        // The chip picked in the panel goes with it.
        val chipped = start.copy(panelChip = ZapPanelChip("Tennis")).pickFromPanel(other, listOf(football1, other))!!
        assertEquals(ZapPanelChip("Tennis"), chipped.panelChip)
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
