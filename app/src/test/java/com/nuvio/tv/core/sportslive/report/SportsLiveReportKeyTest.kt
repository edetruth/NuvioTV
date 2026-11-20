package com.nuvio.tv.core.sportslive.report

import android.view.KeyEvent
import com.nuvio.tv.core.sportslive.report.SportsLiveReportKeyAction.CONSUME
import com.nuvio.tv.core.sportslive.report.SportsLiveReportKeyAction.PASS
import com.nuvio.tv.core.sportslive.report.SportsLiveReportKeyAction.REPORT
import org.junit.Assert.assertEquals
import org.junit.Test

/** Report a problem (#172): hold ★, TV, Play/Pause or Info for 2 s; nothing else about the remote changes. */
class SportsLiveReportKeyTest {

    private val down = KeyEvent.ACTION_DOWN
    private val up = KeyEvent.ACTION_UP
    private val playPause = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE

    /** A hold of [key] pressed at [at], repeating every 50 ms after 500 ms, released at [releaseAt]. */
    private fun hold(key: SportsLiveReportKey, code: Int, at: Long, releaseAt: Long): List<SportsLiveReportKeyAction> {
        val out = mutableListOf(key.onKey(code, down, 0, at, at))
        var t = at + 500
        var repeat = 1
        while (t < releaseAt) {
            out += key.onKey(code, down, repeat++, at, t)
            t += 50
        }
        out += key.onKey(code, up, 0, at, releaseAt)
        return out
    }

    @Test
    fun `a short press does its usual job`() {
        val key = SportsLiveReportKey()
        assertEquals(listOf(PASS, PASS), hold(key, playPause, 1_000, 1_150))
    }

    @Test
    fun `a two second hold reports once and eats the repeats and the release`() {
        val key = SportsLiveReportKey()
        val actions = hold(key, playPause, 1_000, 4_000)
        assertEquals(PASS, actions.first())
        assertEquals(1, actions.count { it == REPORT })
        assertEquals(CONSUME, actions.last())
        assertEquals(listOf(PASS), actions.filter { it == PASS })
    }

    @Test
    fun `it reports at two seconds, not before`() {
        val key = SportsLiveReportKey()
        assertEquals(PASS, key.onKey(playPause, down, 0, 0, 0))
        assertEquals(CONSUME, key.onKey(playPause, down, 30, 0, 1_999))
        assertEquals(REPORT, key.onKey(playPause, down, 31, 0, 2_000))
        assertEquals(CONSUME, key.onKey(playPause, down, 32, 0, 2_050))
    }

    @Test
    fun `a hold let go too soon passes its release`() {
        val key = SportsLiveReportKey()
        val actions = hold(key, playPause, 1_000, 2_500)
        assertEquals(PASS, actions.last())
        assertEquals(0, actions.count { it == REPORT })
    }

    @Test
    fun `info works the same`() {
        val key = SportsLiveReportKey()
        assertEquals(1, hold(key, KeyEvent.KEYCODE_INFO, 0, 2_600).count { it == REPORT })
    }

    @Test
    fun `the star key and the TV key held 2 s report once, a short press passes through (onn remote)`() {
        for (code in listOf(KeyEvent.KEYCODE_MACRO_1, KeyEvent.KEYCODE_GUIDE)) {
            val key = SportsLiveReportKey()
            val held = hold(key, code, 0, 2_600)
            assertEquals("$code", 1, held.count { it == REPORT })
            assertEquals("$code", PASS, held.first())
            assertEquals("$code", CONSUME, held.last())
            assertEquals("$code", listOf(PASS, PASS), hold(key, code, 10_000, 10_150))
        }
    }

    @Test
    fun `every other key is left alone, held or not`() {
        val key = SportsLiveReportKey()
        for (code in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_DPAD_LEFT)) {
            assertEquals("$code", setOf(PASS), hold(key, code, 0, 3_000).toSet())
        }
    }

    @Test
    fun `a new hold after a report can report again`() {
        val key = SportsLiveReportKey()
        assertEquals(1, hold(key, playPause, 0, 2_600).count { it == REPORT })
        assertEquals(1, hold(key, playPause, 10_000, 12_600).count { it == REPORT })
    }
}
