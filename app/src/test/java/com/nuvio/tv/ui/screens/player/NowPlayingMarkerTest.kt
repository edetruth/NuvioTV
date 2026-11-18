package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingMarkerTest {
    private val sources = listOf("a", "b", "c", "d")

    @Test
    fun `the playing source comes first and the rest keep their order`() {
        assertEquals(listOf("c", "a", "b", "d"), pinPlayingFirst(sources, 2))
    }

    @Test
    fun `the pinned source is not listed twice`() {
        val pinned = pinPlayingFirst(sources, 3)
        assertEquals(sources.size, pinned.size)
        assertEquals(1, pinned.count { it == "d" })
        assertEquals(sources.toSet(), pinned.toSet())
    }

    @Test
    fun `a source already first stays as is`() {
        assertSame(sources, pinPlayingFirst(sources, 0))
    }

    @Test
    fun `no playing source leaves the list unchanged`() {
        assertSame(sources, pinPlayingFirst(sources, -1))
        assertSame(sources, pinPlayingFirst(sources, 9))
        assertEquals(emptyList<String>(), pinPlayingFirst(emptyList<String>(), -1))
    }

    @Test
    fun `duplicate entries keep their own copies`() {
        assertEquals(listOf("x", "x", "y"), pinPlayingFirst(listOf("x", "y", "x"), 2))
    }

    @Test
    fun `the equalizer moves only while playing`() {
        assertTrue(nowPlayingBarsAnimate(isPlaying = true, isBuffering = false))
        assertFalse(nowPlayingBarsAnimate(isPlaying = false, isBuffering = false))
        assertFalse(nowPlayingBarsAnimate(isPlaying = true, isBuffering = true))
        assertFalse(nowPlayingBarsAnimate(isPlaying = false, isBuffering = true))
    }
}
