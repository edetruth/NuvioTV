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

/** #140: the in-player panel as a cable guide, Now playing pinned and the lineup wrapped round from the next game. */
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
    fun `the list starts at the game after the current one and wraps round to the one before it`() {
        val rows = lineupOf(live)
        assertEquals(listOf("l1", "l2", "l3", "l4", "l5"), ids(rows))
        val guide = panelGuide(rows, live[2].streamKey)
        assertEquals("l3", guide.nowPlaying?.item?.id)
        assertEquals(listOf("l4", "l5", "l1", "l2"), ids(guide.rows))
    }

    @Test
    fun `From the top sits where the lineup wraps, and only when it wraps`() {
        val rows = lineupOf(live)
        val wrapped = panelGuide(rows, live[2].streamKey).entries
        val divider = wrapped.indexOf(PanelGuideEntry.FromTheTop)
        // Header, l4, l5, From the top, header, l1, l2.
        assertEquals(3, divider)
        assertEquals("l5", (wrapped[divider - 1] as PanelGuideEntry.Game).row.item.id)
        assertTrue(wrapped[divider + 1] is PanelGuideEntry.Header)
        assertEquals("l1", (wrapped[divider + 2] as PanelGuideEntry.Game).row.item.id)
        assertEquals(1, wrapped.count { it == PanelGuideEntry.FromTheTop })

        // On the first game nothing wraps; on the last, the list is the lineup from its top.
        val onFirst = panelGuide(rows, live.first().streamKey)
        assertFalse(PanelGuideEntry.FromTheTop in onFirst.entries)
        assertEquals(listOf("l2", "l3", "l4", "l5"), ids(onFirst.rows))
        val onLast = panelGuide(rows, live.last().streamKey)
        assertFalse(PanelGuideEntry.FromTheTop in onLast.entries)
        assertEquals(listOf("l1", "l2", "l3", "l4"), ids(onLast.rows))
    }

    @Test
    fun `the current game is pinned as Now playing and never repeated in the list`() {
        val rows = lineupOf(mix)
        rows.forEach { row ->
            val guide = panelGuide(rows, row.item.streamKey)
            assertEquals(row.item.id, guide.nowPlaying?.item?.id)
            assertFalse(row.item.id in ids(guide.rows))
            assertEquals(rows.size - 1, guide.rows.size)
            assertEquals(-1, guide.indexOf(row.item.streamKey))
        }
        // The panel state pins the same row.
        val state = SportsLiveZapPanelState(rows = rows, currentKey = a.streamKey)
        assertEquals(a.id, state.nowPlayingRow?.item?.id)
        assertEquals(a.id, state.guide.nowPlaying?.item?.id)
    }

    @Test
    fun `the list is the zap order - its first row is CH minus and its last game before the finals is CH plus`() {
        val rows = lineupOf(mix)
        rows.filter { it.zappable }.forEach { row ->
            val guide = panelGuide(rows, row.item.streamKey)
            val zappable = ids(guide.rows.filter { it.zappable })
            val session = sessionOn(row.item, rows)
            // Walking CH- from the game playing passes the list's games top to bottom, then comes back to it.
            var at = session
            val forward = (zappable.indices).map { at = at.step(true, emptySet())!!; at.current.id }
            assertEquals("CH- from ${row.item.id}", zappable, forward)
            assertEquals(row.item.id, at.step(true, emptySet())!!.current.id)
            // CH+ walks it bottom to top.
            at = session
            val backward = (zappable.indices).map { at = at.step(false, emptySet())!!; at.current.id }
            assertEquals("CH+ from ${row.item.id}", zappable.reversed(), backward)
            // Down from Now playing is the first row: what CH- plays.
            assertEquals(guide.rows.first().item.id, session.step(true, emptySet())!!.current.id)
        }
    }

    @Test
    fun `finals stay listed after the wrapped games, outside the zap order`() {
        val rows = lineupOf(mix)
        val guide = panelGuide(rows, a.streamKey)
        assertEquals("e", guide.rows.last().item.id)
        assertFalse(guide.rows.last().zappable)
        assertTrue(guide.rows.dropLast(1).all { it.zappable })
        val header = guide.entries[guide.entries.size - 2] as PanelGuideEntry.Header
        assertEquals(ScoreboardGroup.JUST_ENDED, header.section)
    }

    @Test
    fun `section headers start each run within a segment and count only its games`() {
        // Lineup: f, a (live) · s2, s1 … (soon) · ch · e (just ended). On s2: s1 … ch, From the top, then f, a.
        val rows = lineupOf(mix)
        val entries = panelGuide(rows, s2.streamKey).entries
        val first = entries.first() as PanelGuideEntry.Header
        assertEquals(rows.single { it.item == s1 }.section, first.section)
        assertEquals("s1", (entries[1] as PanelGuideEntry.Game).row.item.id)
        // Each header is followed by exactly its count of games of its group, then a header, the divider or the end.
        entries.forEachIndexed { index, entry ->
            if (entry !is PanelGuideEntry.Header) return@forEachIndexed
            val run = entries.subList(index + 1, index + 1 + entry.count)
            assertTrue(run.all { it is PanelGuideEntry.Game && it.row.section == entry.section })
            assertFalse(entries.getOrNull(index + 1 + entry.count) is PanelGuideEntry.Game)
        }
        // After the wrap, the live group again under its own header: f, a.
        val top = entries.indexOf(PanelGuideEntry.FromTheTop)
        assertEquals(PanelGuideEntry.Header(ScoreboardGroup.LIVE, 2, segment = 1), entries[top + 1])
        assertEquals(listOf("f", "a"), entries.subList(top + 2, top + 4).map { (it as PanelGuideEntry.Game).row.item.id })
        // Every key is unique, so the list can key its items by them.
        assertEquals(entries.size, entries.map { it.key }.toSet().size)
    }

    @Test
    fun `with the current game outside the list, the lineup reads in its own order and nothing is pinned from it`() {
        val rows = lineupOf(live)
        val guide = panelGuide(rows, "elsewhere|x")
        assertNull(guide.nowPlaying)
        assertEquals(ids(rows), ids(guide.rows))
        assertFalse(PanelGuideEntry.FromTheTop in guide.entries)
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
