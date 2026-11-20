package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SportsLiveZapSessionTest {

    @After
    fun tearDown() {
        SportsLiveZapSessions.clear()
    }

    // One session for every Watch (#173 follow-up)

    @Test
    fun `a Watch from outside Live now starts a Live now session over the shared list, on its game`() {
        val lineup = listOf(item("a"), item("b"), item("c"))
        val session = SportsLiveZapSessions.ensureFor(item("b"), lineup)
        assertEquals("b", session.current.id)
        assertEquals(listOf("a", "b", "c"), session.items.map { it.id })
        assertEquals(SportsLiveTab.LIVE_NOW, session.tab)
        assertSame(session, SportsLiveZapSessions.activeFor("b"))
    }

    @Test
    fun `a game the shared list lacks, or a list not loaded yet, still gets a session`() {
        val missing = SportsLiveZapSessions.ensureFor(item("x"), listOf(item("a")))
        assertEquals(listOf("x", "a"), missing.items.map { it.id })
        SportsLiveZapSessions.clear()
        val alone = SportsLiveZapSessions.ensureFor(item("x"), emptyList())
        assertEquals(listOf("x"), alone.items.map { it.id })
        assertSame(alone, SportsLiveZapSessions.activeFor("x"))
    }

    @Test
    fun `the session already on the game, from Live now or Home, is kept`() {
        val home = session(listOf(item("a"), item("b")), index = 1).copy(returnToHomeOnBack = true)
        SportsLiveZapSessions.start(home)
        assertSame(home, SportsLiveZapSessions.ensureFor(item("b"), listOf(item("c"))))
        // On another game it is replaced.
        assertEquals(listOf("c"), SportsLiveZapSessions.ensureFor(item("c"), listOf(item("c"))).items.map { it.id })
    }

    @Test
    fun `next and previous wrap around`() {
        val session = session(listOf(item("a"), item("b"), item("c")), index = 0)

        assertEquals("b", session.next().current.id)
        assertEquals("a", session.next().next().next().current.id)
        assertEquals("c", session.previous().current.id)
        assertEquals("a", session.previous().next().current.id)
        // The position label is one based.
        assertEquals(2 to 3, session.next().positionLabelArgs)
    }

    @Test
    fun `next and previous keep tab filter and back behaviour`() {
        val session = session(listOf(item("a"), item("b")), index = 0)

        val next = session.next()

        assertEquals(SportsLiveTab.CHANNELS, next.tab)
        assertEquals("UK", next.filter)
        assertEquals(true, next.returnToHomeOnBack)
    }

    @Test
    fun `single item list stays on that item`() {
        val session = session(listOf(item("only")), index = 0)

        assertEquals("only", session.next().current.id)
        assertEquals(0, session.next().index)
        assertEquals("only", session.previous().current.id)
        assertEquals(0, session.previous().index)
    }

    @Test
    fun `moveTo finds an item by addon and id`() {
        val session = session(listOf(item("a"), item("b", addonId = "other"), item("b")), index = 0)

        val moved = session.moveTo("addon|b")

        assertEquals(2, moved?.index)
        assertEquals("addon", moved?.current?.addonId)
        assertEquals(1, session.moveTo("other|b")?.index)
        // An absent key: null.
        assertNull(session.moveTo("addon|missing"))
        assertNull(session.moveTo("other|a"))
    }

    @Test
    fun `activeFor matches only the current item id`() {
        val session = session(listOf(item("a"), item("b")), index = 1)
        SportsLiveZapSessions.start(session)

        assertSame(session, SportsLiveZapSessions.activeFor("b"))
        assertNull(SportsLiveZapSessions.activeFor("a"))
        assertNull(SportsLiveZapSessions.activeFor("movie"))
        assertNull(SportsLiveZapSessions.activeFor(null))
    }

    @Test
    fun `the holder - update replaces the held session, clear empties it`() {
        val session = session(listOf(item("a"), item("b")), index = 0)
        SportsLiveZapSessions.start(session)

        SportsLiveZapSessions.update(session.next())

        assertEquals("b", SportsLiveZapSessions.current()?.current?.id)
        assertNull(SportsLiveZapSessions.activeFor("a"))
        assertEquals("b", SportsLiveZapSessions.activeFor("b")?.current?.id)

        SportsLiveZapSessions.clear()

        assertNull(SportsLiveZapSessions.current())
        assertNull(SportsLiveZapSessions.activeFor("b"))
    }

    @Test
    fun `step skips a merged game only when every source has ended`() {
        val merged = item("b").copy(alternates = listOf(item("b2", addonId = "other")))
        val start = session(listOf(item("a"), merged, item("c")), 0)

        assertEquals("b", start.step(forward = true, endedKeys = setOf("addon|b"))?.current?.id)
        assertEquals("c", start.step(forward = true, endedKeys = setOf("addon|b", "other|b2"))?.current?.id)
    }

    // #68: a Live now session from "My teams": f1, f2 followed; s1 soccer, b1 basketball.
    private val f1 = item("f1").copy(sport = "Basketball", followed = true)
    private val s1 = item("s1").copy(sport = "Soccer")
    private val f2 = item("f2").copy(sport = "Soccer", followed = true)
    private val b1 = item("b1").copy(sport = "Basketball")
    private fun myTeamsSession(on: SportsLiveItem = f1) = SportsLiveZapSession.of(
        listOf(f1, s1, f2, b1), on.streamKey, SportsLiveTab.LIVE_NOW, SportsLiveUiState.MY_TEAMS_FILTER, false
    )!!

    /** The games [steps] zaps reach, one after another. */
    private fun SportsLiveZapSession.zapIds(steps: Int, forward: Boolean = true): List<String> {
        val ids = mutableListOf<String>()
        var at = this
        repeat(steps) { at = at.step(forward, emptySet()) ?: return ids; ids += at.current.id }
        return ids
    }

    @Test
    fun `a session from my teams zaps through the followed games first, then the rest (#68)`() {
        val session = myTeamsSession()

        assertEquals(listOf("f1", "s1", "f2", "b1"), session.items.map { it.id })
        assertEquals(listOf("f2", "s1", "b1", "f1"), session.zapIds(4))
        assertEquals(listOf("b1", "s1", "f2", "f1"), session.zapIds(4, forward = false))
        assertEquals(1 to 4, session.positionLabelArgs)
        assertEquals(3 to 4, session.moveTo(s1.streamKey)!!.positionLabelArgs)
    }

    @Test
    fun `a chip picked in the panel sets the zap order (#68)`() {
        val session = myTeamsSession()

        // All: the list's own order.
        val all = session.copy(panelChip = ZapPanelChip(null))
        assertEquals(listOf("s1", "f2", "b1", "f1"), all.zapIds(4))
        assertEquals(1 to 4, all.positionLabelArgs)
        // A sport: only its games; from a game outside it, the first (or last) of them.
        val soccer = session.copy(panelChip = ZapPanelChip(sportKey("Soccer")))
        assertEquals(listOf("s1", "f2", "s1"), soccer.zapIds(3))
        assertEquals(listOf("f2", "s1", "f2"), soccer.zapIds(3, forward = false))
        assertEquals("f2", soccer.moveTo(s1.streamKey)!!.step(true, emptySet())!!.current.id)
        // My teams picked: the followed games only.
        val mine = session.copy(panelChip = ZapPanelChip(SportsLiveUiState.MY_TEAMS_FILTER))
        assertEquals(listOf("f2", "f1", "f2"), mine.zapIds(3))
        // The chip survives zaps.
        assertEquals(ZapPanelChip(sportKey("Soccer")), soccer.step(true, emptySet())!!.panelChip)
        // A chip whose only game is the one playing: no zap.
        assertNull(SportsLiveZapSession(listOf(f1, s1, b1), 1, SportsLiveTab.LIVE_NOW, null, false, ZapPanelChip(sportKey("Soccer"))).step(true, emptySet()))
        // A chip with no games in the session: every game, as under All.
        val tennis = session.copy(panelChip = ZapPanelChip(sportKey("Tennis")))
        assertEquals(listOf("s1", "f2", "b1", "f1"), tennis.zapIds(4))
        assertEquals(1 to 4, tennis.positionLabelArgs)
    }

    @Test
    fun `a chip sets the zap order of a session from any tab, by sport`() {
        // A Channels session: the chip is a sport, never a channel group of the same name.
        val grouped = f1.copy(group = "Soccer")
        val channels = SportsLiveZapSession(listOf(grouped, s1, b1), 0, SportsLiveTab.CHANNELS, null, false, ZapPanelChip(sportKey("Soccer")))
        assertEquals(listOf("s1"), channels.zapIds(3))
        listOf(SportsLiveTab.UPCOMING, SportsLiveTab.REPLAYS).forEach { tab ->
            val session = SportsLiveZapSession(listOf(f1, s1, f2, b1), 0, tab, null, false, ZapPanelChip(sportKey("Basketball")))
            assertEquals(listOf("b1", "f1", "b1"), session.zapIds(3))
            assertEquals(1 to 2, session.positionLabelArgs)
            val mine = session.copy(panelChip = ZapPanelChip(SportsLiveUiState.MY_TEAMS_FILTER))
            assertEquals(listOf("f2", "f1"), mine.zapIds(2))
        }
    }

    @Test
    fun `the picked chip lasts for its session only (#68)`() {
        SportsLiveZapSessions.start(myTeamsSession())
        val current = SportsLiveZapSessions.current()!!
        SportsLiveZapSessions.update(current.copy(panelChip = ZapPanelChip(sportKey("Soccer"))))
        assertEquals(ZapPanelChip(sportKey("Soccer")), SportsLiveZapSessions.current()!!.panelChip)

        SportsLiveZapSessions.start(myTeamsSession(on = f2))

        assertNull(SportsLiveZapSessions.current()!!.panelChip)
    }

    @Test
    fun `the of N count leaves finished games out`() {
        val items = listOf(item("a"), item("b"), item("f"), item("c"))
        val lineup = SportsLiveZapSession(
            items = items,
            index = 3,
            tab = SportsLiveTab.LIVE_NOW,
            filter = null,
            returnToHomeOnBack = false,
            lineup = items.map { it.streamKey },
            skipped = setOf(items[2].streamKey)
        )

        // c is the third of three games CH plus and minus stop on; the final f is not counted.
        assertEquals(3 to 3, lineup.positionLabelArgs)
        assertEquals(1 to 3, lineup.moveTo(items[0].streamKey)!!.positionLabelArgs)
        // A finished game playing takes the place after the games before it.
        assertEquals(3 to 3, lineup.moveTo(items[2].streamKey)!!.positionLabelArgs)
    }

    private fun session(items: List<SportsLiveItem>, index: Int) = SportsLiveZapSession(
        items = items,
        index = index,
        tab = SportsLiveTab.CHANNELS,
        filter = "UK",
        returnToHomeOnBack = true
    )

    private fun item(id: String, addonId: String = "addon") = SportsLiveItem(
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
        addonId = addonId,
        addonName = addonId,
        addonBaseUrl = "https://$addonId",
        catalogKey = "$addonId:tv:live",
        catalogName = "Live",
        kind = SportsLiveKind.EVENTS,
        isLive = true,
        isChannel = false,
        startMs = null,
        sport = null
    )
}
