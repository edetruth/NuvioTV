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
        val pick = SportsLiveDualPick(chosen = b, items = listOf(a, b, c), filter = null)
        assertEquals(listOf(a, c), pick.candidates)
    }

    @Test
    fun `games known to have ended are left out`() {
        val pick = SportsLiveDualPick(chosen = a, items = listOf(a, b, c), filter = null, endedKeys = setOf(b.streamKey))
        assertEquals(listOf(c), pick.candidates)
    }

    @Test
    fun `another card sharing a source with the chosen game is left out`() {
        val merged = dualItem("m").copy(alternates = listOf(dualItem("x").copy(addonId = "other")))
        val sameGameElsewhere = dualItem("x").copy(addonId = "other")
        val pick = SportsLiveDualPick(chosen = merged, items = listOf(merged, sameGameElsewhere, c), filter = null)
        assertEquals(listOf(c), pick.candidates)
    }

    @Test
    fun `the pick starts a session over the list with its filter`() {
        val pick = SportsLiveDualPick(chosen = a, items = listOf(a, b, c), filter = "Soccer")
        assertEquals(SportsLiveDualSession(a, c, listOf(a, b, c), "Soccer"), pick.session(c))
    }

    @Test
    fun `a long-pressed panel game pairs with the game playing over the panel list`() {
        val pick = SportsLiveDualPick(chosen = a, items = listOf(a, b, c), filter = "Soccer")
        assertEquals(SportsLiveDualSession(a, c, listOf(a, b, c), "Soccer"), pick.pairWith(c))
    }

    @Test
    fun `a long press on the game playing pairs with nothing`() {
        val pick = SportsLiveDualPick(chosen = a, items = listOf(a, b, c), filter = null)
        assertNull(pick.pairWith(a))
    }

    @Test
    fun `a long press on a card sharing a source with the game playing pairs with nothing`() {
        val playing = dualItem("m").copy(alternates = listOf(dualItem("x").copy(addonId = "other")))
        val sameGameElsewhere = dualItem("x").copy(addonId = "other")
        val pick = SportsLiveDualPick(chosen = playing, items = listOf(playing, sameGameElsewhere, c), filter = null)
        assertNull(pick.pairWith(sameGameElsewhere))
    }

    @Test
    fun `a long press on a game known to have ended pairs with nothing`() {
        val pick = SportsLiveDualPick(chosen = a, items = listOf(a, b), filter = null, endedKeys = setOf(b.streamKey))
        assertNull(pick.pairWith(b))
    }

    @Test
    fun `pick mode OK on Live now pairs the first game with the card over the list shown`() {
        val session = sportsLivePickModeSession(
            chosen = a, item = c, tab = SportsLiveTab.LIVE_NOW, items = listOf(b, c), filter = "Soccer"
        )
        assertEquals(SportsLiveDualSession(a, c, listOf(b, c), "Soccer"), session)
    }

    @Test
    fun `pick mode OK on Channels pairs too`() {
        val session = sportsLivePickModeSession(
            chosen = a, item = b, tab = SportsLiveTab.CHANNELS, items = listOf(a, b), filter = null
        )
        assertEquals(SportsLiveDualSession(a, b, listOf(a, b), null), session)
    }

    @Test
    fun `pick mode OK on Upcoming or Replays does nothing`() {
        for (tab in listOf(SportsLiveTab.UPCOMING, SportsLiveTab.REPLAYS)) {
            assertNull(sportsLivePickModeSession(chosen = a, item = b, tab = tab, items = listOf(a, b), filter = null))
        }
    }

    @Test
    fun `pick mode OK on the first game or a card sharing its source does nothing`() {
        val first = dualItem("m").copy(alternates = listOf(dualItem("x").copy(addonId = "other")))
        val sameGameElsewhere = dualItem("x").copy(addonId = "other")
        val items = listOf(first, sameGameElsewhere, c)
        assertNull(sportsLivePickModeSession(first, first, SportsLiveTab.LIVE_NOW, items, null))
        assertNull(sportsLivePickModeSession(first, sameGameElsewhere, SportsLiveTab.LIVE_NOW, items, null))
    }

    @Test
    fun `OK starts a zap session over the dual list on the highlighted game`() {
        val viewModel = SportsLiveDualViewModel(
            resolve = { null },
            endedKeys = { emptySet() },
            session = SportsLiveDualSession(a, b, listOf(a, b, c), "Soccer")
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
