package com.nuvio.tv.ui.screens.sportslive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** live.24 (design audit B5): dark logos get a light plate; light, colourful and empty ones do not. */
class SportsLiveDarkLogosTest {

    private fun argb(alpha: Int, r: Int, g: Int, b: Int) = (alpha shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun `a navy or black mark on a transparent ground reads dark`() {
        val navy = IntArray(100) { i -> if (i % 2 == 0) argb(255, 10, 20, 60) else argb(0, 255, 255, 255) }
        assertTrue(DarkLogos.isDark(navy))
    }

    @Test
    fun `a light or colourful mark reads as it is, the transparent ground never counts`() {
        val gold = IntArray(100) { i -> if (i % 2 == 0) argb(255, 230, 180, 40) else argb(0, 0, 0, 0) }
        assertFalse(DarkLogos.isDark(gold))
        // Mostly white with a dark outline.
        val white = IntArray(100) { i -> if (i % 10 == 0) argb(255, 0, 0, 0) else argb(255, 250, 250, 250) }
        assertFalse(DarkLogos.isDark(white))
        // Nothing visible at all: no plate.
        assertFalse(DarkLogos.isDark(IntArray(100) { argb(0, 0, 0, 0) }))
    }
}
