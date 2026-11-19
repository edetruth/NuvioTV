package com.nuvio.tv.core.sportslive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The one toast slot (#134): one toast at a time, the newest wins, and an old timer never takes a new toast down. */
class SportsLiveToastsTest {

    @Test
    fun `one toast at a time and the newest replaces the one up`() {
        val queue = SportsLiveToastQueue()
        val first = queue.show(SportsLiveToastKind.REMINDER, "Reminder set", "Today 7:30 PM")!!
        assertEquals(first, queue.current.value)
        val second = queue.show(SportsLiveToastKind.PROBLEM, "No stream for Arizona vs Duke right now")!!
        assertEquals(second, queue.current.value)
        assertNotEquals(first.id, second.id)
        assertEquals(SportsLiveToastKind.PROBLEM, queue.current.value?.kind)
    }

    @Test
    fun `the replaced toast's timer leaves the newer toast up`() {
        val queue = SportsLiveToastQueue()
        val first = queue.show(SportsLiveToastKind.INFO, "Playing from Source A")!!
        val second = queue.show(SportsLiveToastKind.FINAL, "Full time", "Patriots 10-35 Lions")!!
        queue.dismiss(first.id)
        assertEquals(second, queue.current.value)
        queue.dismiss(second.id)
        assertNull(queue.current.value)
    }

    @Test
    fun `a blank title shows nothing and a blank line is dropped`() {
        val queue = SportsLiveToastQueue()
        assertNull(queue.show(SportsLiveToastKind.PROBLEM, "  "))
        assertNull(queue.current.value)
        val shown = queue.show(SportsLiveToastKind.PROBLEM, " Source A is busy ", "  ")!!
        assertEquals("Source A is busy", shown.title)
        assertNull(shown.line)
    }

    @Test
    fun `toasts stay up for six seconds`() {
        assertEquals(6_000L, SportsLiveToastQueue.SHOW_MS)
    }
}
