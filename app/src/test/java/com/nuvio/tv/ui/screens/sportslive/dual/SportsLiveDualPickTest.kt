package com.nuvio.tv.ui.screens.sportslive.dual

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapSessions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class SportsLiveDualPickTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val a = dualItem("a")
    private val b = dualItem("b")
    private val c = dualItem("c")

    @After
    fun tearDown() {
        SportsLiveZapSessions.clear()
    }

    @Test
    fun `the chosen game is never a candidate`() {
        val pick = SportsLiveDualPick(chosen = b, items = listOf(a, b, c), filter = null, tab = SportsLiveTab.LIVE_NOW)
        assertEquals(listOf(a, c), pick.candidates)
    }

    @Test
    fun `games known to have ended are left out`() {
        val pick = SportsLiveDualPick(chosen = a, items = listOf(a, b, c), filter = null, tab = SportsLiveTab.LIVE_NOW, endedKeys = setOf(b.streamKey))
        assertEquals(listOf(c), pick.candidates)
    }

    @Test
    fun `another card sharing a source with the chosen game is left out`() {
        val merged = dualItem("m").copy(alternates = listOf(dualItem("x").copy(addonId = "other")))
        val sameGameElsewhere = dualItem("x").copy(addonId = "other")
        val pick = SportsLiveDualPick(chosen = merged, items = listOf(merged, sameGameElsewhere, c), filter = null, tab = SportsLiveTab.LIVE_NOW)
        assertEquals(listOf(c), pick.candidates)
    }

    @Test
    fun `the pick starts a session over the list with its filter`() {
        val pick = SportsLiveDualPick(chosen = a, items = listOf(a, b, c), filter = "Soccer", tab = SportsLiveTab.LIVE_NOW)
        assertEquals(SportsLiveDualSession(a, c, listOf(a, b, c), "Soccer", SportsLiveTab.LIVE_NOW), pick.session(c))
    }

    @Test
    fun `a long-pressed panel game pairs with the game playing over the panel list`() {
        val pick = SportsLiveDualPick(chosen = a, items = listOf(a, b, c), filter = "Soccer", tab = SportsLiveTab.LIVE_NOW)
        assertEquals(SportsLiveDualSession(a, c, listOf(a, b, c), "Soccer", SportsLiveTab.LIVE_NOW), pick.pairWith(c))
    }

    @Test
    fun `a long press on the game playing, a card sharing its source or an ended game pairs with nothing`() {
        val pick = SportsLiveDualPick(chosen = a, items = listOf(a, b, c), filter = null, tab = SportsLiveTab.LIVE_NOW)
        assertNull("the game playing", pick.pairWith(a))

        val playing = dualItem("m").copy(alternates = listOf(dualItem("x").copy(addonId = "other")))
        val sameGameElsewhere = dualItem("x").copy(addonId = "other")
        val merged = SportsLiveDualPick(chosen = playing, items = listOf(playing, sameGameElsewhere, c), filter = null, tab = SportsLiveTab.LIVE_NOW)
        assertNull("a card sharing a source", merged.pairWith(sameGameElsewhere))

        val ended = SportsLiveDualPick(chosen = a, items = listOf(a, b), filter = null, tab = SportsLiveTab.LIVE_NOW, endedKeys = setOf(b.streamKey))
        assertNull("an ended game", ended.pairWith(b))
    }

    @Test
    fun `OK from a Channels dual session keeps the Channels tab and its group filter`() {
        val viewModel = SportsLiveDualViewModel(
            resolve = { null },
            endedKeys = { emptySet() },
            session = SportsLiveDualSession(a, b, listOf(a, b, c), "UK", SportsLiveTab.CHANNELS)
        )
        viewModel.startZapSession(a)
        val session = SportsLiveZapSessions.current()!!
        assertEquals(SportsLiveTab.CHANNELS, session.tab)
        assertEquals("UK", session.filter)
        assertEquals(a, session.current)
    }

    @Test
    fun `a pick carries its tab into the session`() {
        val pick = SportsLiveDualPick(chosen = a, items = listOf(a, b), filter = "UK", tab = SportsLiveTab.CHANNELS)
        assertEquals(SportsLiveTab.CHANNELS, pick.pairWith(b)?.tab)
    }

    @Test
    fun `OK starts a zap session over the dual list on the highlighted game`() {
        val viewModel = SportsLiveDualViewModel(
            resolve = { null },
            endedKeys = { emptySet() },
            session = SportsLiveDualSession(a, b, listOf(a, b, c), "Soccer", SportsLiveTab.LIVE_NOW)
        )
        viewModel.startZapSession(b)
        val session = SportsLiveZapSessions.current()
        assertNotNull(session)
        session!!
        assertEquals(listOf(a, b, c), session.items)
        assertEquals(b, session.current)
        assertEquals(SportsLiveTab.LIVE_NOW, session.tab)
        assertEquals("Soccer", session.filter)
        assertEquals(false, session.returnToHomeOnBack)
    }
}
