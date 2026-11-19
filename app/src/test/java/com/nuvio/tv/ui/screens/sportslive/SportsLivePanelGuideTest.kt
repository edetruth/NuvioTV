package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.EndedGame
import com.nuvio.tv.core.sportslive.EndedSignal
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.ScoresMode
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

/** #140: the in-player panel as a cable guide, Now playing pinned and the lineup in its own order, the game playing marked in place. */
class SportsLivePanelGuideTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    // Five live games, earliest start first: l1 … l5.
    private val live = (1..5).map { game("l$it", now - (60 - it * 5) * minute) }

    // A mix: live (a, and f followed so first), starting soon (s2 then s1), later (u), a channel, a game just ended.
    private val a = game("a", now - 90 * minute)
    private val f = game("f", now - 30 * minute).copy(followed = true)
    private val s1 = game("s1", now + 60 * minute)
    private val s2 = game("s2", now + 30 * minute)
    private val u = game("u", now + 5 * 60 * minute)
    private val ch = game("ch", null).copy(isChannel = true, meta = game("ch", null).meta.copy(name = "Sports Channel HD"))
    private val e = game("e", now - 60 * minute)
    private val mix = listOf(e, ch, u, s1, a, s2, f)
    private val ended = mapOf(e.streamKey to EndedGame(EndedSignal.ESTIMATE, now))

    private fun lineupOf(items: List<SportsLiveItem>): List<ZapPanelRow> =
        panelLineup(groupPanelRows(items, emptyMap(), ScoresMode.OFF, emptySet(), now, ended))

    private fun ids(rows: List<ZapPanelRow>) = rows.map { it.item.id }

    private fun sessionOn(item: SportsLiveItem, rows: List<ZapPanelRow>) =
        SportsLiveZapSession.of(rows.map { it.item }, item.streamKey, SportsLiveTab.LIVE_NOW, null, returnToHomeOnBack = false)!!
            .withLineup(rows)

    @Test
    fun `the list is the lineup in its own order, the current game in its place marked watching and pinned above`() {
        val rows = lineupOf(live)
        assertEquals(listOf("l1", "l2", "l3", "l4", "l5"), ids(rows))
        val guide = panelGuide(rows, live[2].streamKey)
        assertEquals("l3", guide.nowPlaying?.item?.id)
        assertEquals(listOf("l1", "l2", "l3", "l4", "l5"), ids(guide.rows))
        val games = guide.entries.filterIsInstance<PanelGuideEntry.Game>()
        assertEquals(listOf("l3"), games.filter { it.watching }.map { it.row.item.id })
        // One header over the one run; no divider of any kind.
        assertEquals(listOf(PanelGuideEntry.Header(ScoreboardGroup.LIVE, 5, run = 0)), guide.entries.filterIsInstance<PanelGuideEntry.Header>())
        assertEquals(6, guide.entries.size)
        // The watching place is never focused: Now playing is its row.
        assertEquals(listOf("l1", "l2", "l4", "l5").map { "addon|$it" }, guide.entries.mapNotNull { (it as? PanelGuideEntry.Game)?.takeUnless { g -> g.watching }?.key })
        assertEquals("addon|l1", guide.firstFocusableKey)
        assertEquals("addon|l2", panelGuide(rows, live[0].streamKey).firstFocusableKey)
    }

    @Test
    fun `the current game is pinned and listed once in its own place, whatever the game`() {
        val rows = lineupOf(mix)
        rows.forEach { row ->
            val guide = panelGuide(rows, row.item.streamKey)
            assertEquals(row.item.id, guide.nowPlaying?.item?.id)
            assertEquals(ids(rows), ids(guide.rows))
            val watching = guide.entries.filterIsInstance<PanelGuideEntry.Game>().single { it.watching }
            assertEquals(row.item.id, watching.row.item.id)
        }
        // The panel state pins the same row.
        val state = SportsLiveZapPanelState(rows = rows, currentKey = a.streamKey)
        assertEquals(a.id, state.nowPlayingRow?.item?.id)
        assertEquals(a.id, state.guide.nowPlaying?.item?.id)
    }

    @Test
    fun `CH minus plays the game after the watching place and CH plus the one before, wrapping at the ends`() {
        val rows = lineupOf(mix)
        val zappable = ids(rows.filter { it.zappable })
        rows.filter { it.zappable }.forEach { row ->
            val session = sessionOn(row.item, rows)
            val here = zappable.indexOf(row.item.id)
            val after = zappable[(here + 1) % zappable.size]
            val before = zappable[(here - 1 + zappable.size) % zappable.size]
            assertEquals("CH- from ${row.item.id}", after, session.step(true, emptySet())!!.current.id)
            assertEquals("CH+ from ${row.item.id}", before, session.step(false, emptySet())!!.current.id)
            // Walking CH- round passes the list's zappable games in the list's order.
            var at = session
            val walk = zappable.indices.map { at = at.step(true, emptySet())!!; at.current.id }
            assertEquals(zappable.drop(here + 1) + zappable.take(here + 1), walk)
        }
    }

    @Test
    fun `finals stay listed last, outside the zap order`() {
        val rows = lineupOf(mix)
        val guide = panelGuide(rows, a.streamKey)
        assertEquals("e", guide.rows.last().item.id)
        assertFalse(guide.rows.last().zappable)
        val header = guide.entries[guide.entries.size - 2] as PanelGuideEntry.Header
        assertEquals(ScoreboardGroup.JUST_ENDED, header.section)
    }

    @Test
    fun `a header starts each run of one group and counts its games`() {
        val rows = lineupOf(mix)
        val entries = panelGuide(rows, s2.streamKey).entries
        assertTrue(entries.first() is PanelGuideEntry.Header)
        entries.forEachIndexed { index, entry ->
            if (entry !is PanelGuideEntry.Header) return@forEachIndexed
            val run = entries.subList(index + 1, index + 1 + entry.count)
            assertTrue(run.all { it is PanelGuideEntry.Game && it.row.placed == entry.section })
            assertFalse(entries.getOrNull(index + 1 + entry.count) is PanelGuideEntry.Game)
        }
        // Live first: f (followed), then a.
        assertEquals(PanelGuideEntry.Header(ScoreboardGroup.LIVE, 2, run = 0), entries[0])
        assertEquals(listOf("f", "a"), entries.subList(1, 3).map { (it as PanelGuideEntry.Game).row.item.id })
        // Every key is unique, so the list can key its items by them.
        assertEquals(entries.size, entries.map { it.key }.toSet().size)
    }

    @Test
    fun `with the current game outside the list, nothing is pinned or marked watching`() {
        val rows = lineupOf(live)
        val guide = panelGuide(rows, "elsewhere|x")
        assertNull(guide.nowPlaying)
        assertEquals(ids(rows), ids(guide.rows))
        assertTrue(guide.entries.filterIsInstance<PanelGuideEntry.Game>().none { it.watching })
    }

    @Test
    fun `under My sports the list ends with the hidden line, which focus can reach`() {
        val rows = lineupOf(live)
        val guide = panelGuide(rows, live[0].streamKey, hiddenByMySports = 12)
        assertEquals(PanelGuideEntry.HiddenByMySports(12), guide.entries.last())
        assertEquals(guide.entries.size - 1, guide.indexOf(HIDDEN_BY_MY_SPORTS_KEY))
        assertFalse(panelGuide(rows, live[0].streamKey).entries.any { it is PanelGuideEntry.HiddenByMySports })
        // The panel state carries it to its guide.
        val state = SportsLiveZapPanelState(rows = rows, currentKey = live[0].streamKey, hiddenByMySports = 3)
        assertEquals(PanelGuideEntry.HiddenByMySports(3), state.guide.entries.last())
    }

    @Test
    fun `a line that left the list hands focus to the nearest focusable one`() {
        val rows = lineupOf(live)
        // Header 0, l1 1, l2 2 (watching), l3 3, l4 4, l5 5.
        val guide = panelGuide(rows, live[1].streamKey)
        assertEquals("addon|l3", guide.nearestFocusable(3))
        // Index 2 is the watching place: its neighbours tie, the lower one wins.
        assertEquals("addon|l3", guide.nearestFocusable(2))
        assertEquals("addon|l1", guide.nearestFocusable(0))
        assertEquals("addon|l5", guide.nearestFocusable(40))
        assertNull(panelGuide(emptyList(), null).nearestFocusable(3))
    }

    private fun game(id: String, startMs: Long?, sport: String = "Basketball") = SportsLiveItem(
        meta = MetaPreview(
            id = id, type = ContentType.UNKNOWN, rawType = "tv", name = "Team $id vs Other $id", poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList()
        ),
        addonId = "addon", addonName = "Sports", addonBaseUrl = "https://example.invalid", catalogKey = "addon:tv:live",
        catalogName = "Live", kind = SportsLiveKind.EVENTS, isLive = true, isChannel = false,
        startMs = startMs, sport = sport, startHasTimeOfDay = startMs != null,
        teams = SportsLiveTeams.parse("Team $id vs Other $id")
    )
}
