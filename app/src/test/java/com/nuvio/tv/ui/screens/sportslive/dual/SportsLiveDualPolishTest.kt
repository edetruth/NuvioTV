package com.nuvio.tv.ui.screens.sportslive.dual

import org.junit.Assert.assertEquals
import org.junit.Test

/** live.19 QA F13: the dual screen's link count and pane captions. */
class SportsLiveDualPolishTest {

    private val a = dualItem("a")
    private val b = dualItem("b")

    @Test
    fun `the link count never shrinks while a pane stays on its game`() {
        var state = DualState(DualPane(a), DualPane(b))
            .reduce(DualAction.Resolved(a, dualStream("a1")))
            .reduce(DualAction.LinkCount(a, 5))
            .reduce(DualAction.Switched(a, dualStream("a2")))
        // It read "2 of 5" then "3 of 4".
        state = state.reduce(DualAction.LinkCount(a, 4))
        assertEquals(5, state.left.linkCount)
        state = state.reduce(DualAction.Switched(a, dualStream("a3"))).reduce(DualAction.LinkCount(a, 6))
        assertEquals(6, state.left.linkCount)
    }

    @Test
    fun `the link count never drops under the link the pane is on`() {
        val state = DualState(DualPane(a), DualPane(b))
            .reduce(DualAction.Resolved(a, dualStream("a1")))
            .reduce(DualAction.Switched(a, dualStream("a2")))
            .reduce(DualAction.Switched(a, dualStream("a3")))
            .reduce(DualAction.LinkCount(a, 2))
        assertEquals(3, state.left.linkNumber)
        assertEquals(3, state.left.linkCount)
    }

    @Test
    fun `a pane's caption is words only, with the addon's emoji gone`() {
        val item = dualItem("game").let {
            it.copy(meta = it.meta.copy(name = "⏱ Clemson Tigers @ Duke Blue Devils"), sport = "🏈 American Football")
        }
        assertEquals("Clemson Tigers @ Duke Blue Devils · American Football", dualPaneCaption(item))
        // No sport: the name alone.
        assertEquals("game", dualPaneCaption(dualItem("game")))
    }
}
