package com.nuvio.tv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class SharedFitFontSizeTest {

    // A label 13 characters long at 7 px per sp-character in a 1100 px slot: it fits at 12 sp, the others anyway.
    private fun allFit(labels: List<String>, slotPx: Int): (Float) -> Boolean =
        { sp -> labels.all { it.length * 7 * sp <= slotPx } }

    @Test
    fun `every label takes the size the longest one fits`() {
        val labels = listOf("Home", "Sports & Live", "Search", "Settings")

        assertEquals(12f, sharedFitFontSize(16f, 9f, allFit(labels, 1100)))
    }

    @Test
    fun `labels that all fit keep the full size`() {
        assertEquals(16f, sharedFitFontSize(16f, 9f, allFit(listOf("Home", "Search", "Settings"), 1100)))
    }

    @Test
    fun `nothing fitting stops at the minimum`() {
        assertEquals(9f, sharedFitFontSize(16f, 9f) { false })
    }
}
