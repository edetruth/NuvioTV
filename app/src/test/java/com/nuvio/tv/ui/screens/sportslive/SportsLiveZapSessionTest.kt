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

    @Test
    fun `next and previous wrap around`() {
        val session = session(listOf(item("a"), item("b"), item("c")), index = 0)

        assertEquals("b", session.next().current.id)
        assertEquals("a", session.next().next().next().current.id)
        assertEquals("c", session.previous().current.id)
        assertEquals("a", session.previous().next().current.id)
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
    }

    @Test
    fun `moveTo returns null for an absent key`() {
        val session = session(listOf(item("a"), item("b")), index = 0)

        assertNull(session.moveTo("addon|missing"))
        assertNull(session.moveTo("other|a"))
    }

    @Test
    fun `position label args are one based`() {
        val session = session(listOf(item("a"), item("b"), item("c")), index = 1)

        assertEquals(2 to 3, session.positionLabelArgs)
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
    fun `update replaces the held session`() {
        val session = session(listOf(item("a"), item("b")), index = 0)
        SportsLiveZapSessions.start(session)

        SportsLiveZapSessions.update(session.next())

        assertEquals("b", SportsLiveZapSessions.current()?.current?.id)
        assertNull(SportsLiveZapSessions.activeFor("a"))
        assertEquals("b", SportsLiveZapSessions.activeFor("b")?.current?.id)
    }

    @Test
    fun `clear empties the holder`() {
        SportsLiveZapSessions.start(session(listOf(item("a")), index = 0))

        SportsLiveZapSessions.clear()

        assertNull(SportsLiveZapSessions.current())
        assertNull(SportsLiveZapSessions.activeFor("a"))
    }

    @Test
    fun `step skips a merged game only when every source has ended`() {
        val merged = item("b").copy(alternates = listOf(item("b2", addonId = "other")))
        val start = session(listOf(item("a"), merged, item("c")), 0)

        assertEquals("b", start.step(forward = true, endedKeys = setOf("addon|b"))?.current?.id)
        assertEquals("c", start.step(forward = true, endedKeys = setOf("addon|b", "other|b2"))?.current?.id)
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
