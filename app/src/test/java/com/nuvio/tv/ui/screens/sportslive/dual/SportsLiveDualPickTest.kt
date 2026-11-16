package com.nuvio.tv.ui.screens.sportslive.dual

import com.nuvio.tv.MainDispatcherRule
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapSessions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
