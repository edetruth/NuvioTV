package com.nuvio.tv.ui.screens.sportslive

import android.view.KeyEvent
import com.nuvio.tv.core.sportslive.report.SportsLiveReport
import com.nuvio.tv.core.sportslive.report.SportsLiveReportInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * Hold OK on any screen (#218 everywhere): a hold nothing focused answers opens the Report a problem sheet; a short
 * press, a hold the screens answer and the player's own Hold OK stay as they were.
 */
class SportsLiveHoldOkTest {

    private val down = KeyEvent.ACTION_DOWN
    private val up = KeyEvent.ACTION_UP
    private val ok = KeyEvent.KEYCODE_DPAD_CENTER
    private val settings = "settings"

    /** A fake set of screens: what they saw, and whether they consume OK's first press and its repeats. */
    private class Screens(val consumeRepeats: Boolean) {
        val seen = mutableListOf<String>()

        fun deliver(action: Int, repeat: Int): Boolean {
            seen += if (action == KeyEvent.ACTION_UP) "up" else "down$repeat"
            return action != KeyEvent.ACTION_DOWN || repeat == 0 || consumeRepeats
        }
    }

    private class Run(val key: SportsLiveHoldOkKey, val screens: Screens) {
        val opened = mutableListOf<String?>()
        val consumed = mutableListOf<Boolean>()

        fun event(
            action: Int,
            repeat: Int,
            downTime: Long,
            at: Long,
            route: String?,
            focused: Boolean = true,
            code: Int = KeyEvent.KEYCODE_DPAD_CENTER
        ) {
            consumed += key.onKey(code, action, repeat, downTime, at, route, focused, { screens.deliver(action, repeat) }) {
                opened += it
            }
        }
    }

    /** OK pressed at [at], repeating every 50 ms from 500 ms (the system's long-press time), released at [releaseAt]. */
    private fun hold(
        run: Run,
        at: Long,
        releaseAt: Long,
        route: (Long) -> String? = { settings },
        focused: (Long) -> Boolean = { true }
    ) {
        run.event(down, 0, at, at, route(at))
        var t = at + 500
        var repeat = 1
        while (t < releaseAt) {
            run.event(down, repeat++, at, t, route(t), focused(t))
            t += 50
        }
        run.event(up, 0, at, releaseAt, route(releaseAt), focused(releaseAt))
    }

    @Test
    fun `a release before the long-press time is a normal click`() {
        val run = Run(SportsLiveHoldOkKey(), Screens(consumeRepeats = true))
        hold(run, at = 1_000, releaseAt = 1_200)
        assertTrue(run.opened.isEmpty())
        assertEquals(listOf("down0", "up"), run.screens.seen)
    }

    @Test
    fun `a hold nothing focused answers opens the sheet at the first repeat and eats the rest of the press`() {
        val run = Run(SportsLiveHoldOkKey(), Screens(consumeRepeats = false))
        hold(run, at = 1_000, releaseAt = 2_000)
        assertEquals(listOf<String?>(settings), run.opened)
        // The screens saw the press and the first repeat only: never the release, so no click follows the sheet.
        assertEquals(listOf("down0", "down1"), run.screens.seen)
        assertTrue(run.consumed.drop(1).all { it })
    }

    @Test
    fun `a hold taken by an element that answers every OK opens the sheet after the grace time`() {
        val run = Run(SportsLiveHoldOkKey(graceMs = 500), Screens(consumeRepeats = true))
        hold(run, at = 1_000, releaseAt = 2_500)
        assertEquals(listOf<String?>(settings), run.opened)
        assertFalse("the release must not click", "up" in run.screens.seen)
        // First repeat at 1500, opened at the repeat 500 ms later (2000): repeats 1 to 10 reached the screens.
        assertEquals((0..10).map { "down$it" }, run.screens.seen)
    }

    @Test
    fun `a release within the grace time after a taken hold stays with the screens`() {
        val run = Run(SportsLiveHoldOkKey(graceMs = 500), Screens(consumeRepeats = true))
        hold(run, at = 1_000, releaseAt = 1_800)
        assertTrue(run.opened.isEmpty())
        assertEquals("up", run.screens.seen.last())
    }

    @Test
    fun `a hold whose own menu took the window opens no sheet`() {
        val run = Run(SportsLiveHoldOkKey(graceMs = 500), Screens(consumeRepeats = true))
        // Poster options or a Sports & Live row menu: a dialog has the window 150 ms after the first repeat.
        hold(run, at = 1_000, releaseAt = 3_000, focused = { it < 1_650 })
        assertTrue(run.opened.isEmpty())
        assertEquals("up", run.screens.seen.last())
    }

    @Test
    fun `a hold that opened another screen opens no sheet`() {
        val run = Run(SportsLiveHoldOkKey(graceMs = 500), Screens(consumeRepeats = true))
        hold(run, at = 1_000, releaseAt = 3_000, route = { if (it < 1_600) "detail/{itemId}" else "stream/{videoId}" })
        assertTrue(run.opened.isEmpty())
        assertEquals("up", run.screens.seen.last())
    }

    @Test
    fun `the player and dual screen keep their own Hold OK`() {
        listOf("player/{streamUrl}/{title}?streamName={streamName}", "sports_live_dual").forEach { route ->
            val run = Run(SportsLiveHoldOkKey(), Screens(consumeRepeats = false))
            hold(run, at = 1_000, releaseAt = 3_000, route = { route })
            assertTrue(route, run.opened.isEmpty())
            assertEquals(route, "up", run.screens.seen.last())
        }
    }

    @Test
    fun `other keys and repeats of a press never seen go to the screens`() {
        val run = Run(SportsLiveHoldOkKey(), Screens(consumeRepeats = false))
        run.event(down, 0, 1_000, 1_000, settings, code = KeyEvent.KEYCODE_DPAD_DOWN)
        run.event(down, 1, 1_000, 1_500, settings, code = KeyEvent.KEYCODE_DPAD_DOWN)
        // An OK repeat whose press another hook took (an alert card): not ours.
        run.event(down, 1, 2_000, 2_500, settings)
        run.event(up, 0, 2_000, 2_600, settings)
        assertTrue(run.opened.isEmpty())
        assertEquals(listOf("down0", "down1", "down1", "up"), run.screens.seen)
    }

    @Test
    fun `Enter and numpad Enter hold like OK, and the next press is a click again`() {
        listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER).forEach { code ->
            val run = Run(SportsLiveHoldOkKey(), Screens(consumeRepeats = false))
            run.event(down, 0, 1_000, 1_000, settings, code = code)
            run.event(down, 1, 1_000, 1_500, settings, code = code)
            run.event(up, 0, 1_000, 1_700, settings, code = code)
            assertEquals(listOf<String?>(settings), run.opened)
            run.event(down, 0, 5_000, 5_000, settings, code = code)
            run.event(up, 0, 5_000, 5_100, settings, code = code)
            assertEquals(listOf("down0", "down1", "down0", "up"), run.screens.seen)
        }
    }

    @Test
    fun `the report names the screen the hold began on, with no route arguments`() {
        assertEquals("Hold OK (settings)", holdOkTrigger("settings"))
        assertEquals("Hold OK (detail)", holdOkTrigger("detail/{itemId}/{itemType}?addonBaseUrl={addonBaseUrl}"))
        assertEquals("Hold OK (sports_live_setup)", holdOkTrigger("sports_live_setup?fromSettings={fromSettings}"))
        assertEquals("Hold OK (unknown)", holdOkTrigger(null))
        assertEquals("Playback settings", holdOkTitle("playback_settings"))
        assertEquals("Settings", holdOkTitle("settings"))
    }

    @Test
    fun `a Hold OK report says From Hold OK and the screen`() {
        val route = "playback_settings"
        val input = SportsLiveReportInput(
            number = 3,
            savedAtMs = 1_763_667_725_000L,
            zone = ZoneId.of("UTC"),
            trigger = holdOkTrigger(route),
            appVersion = "1.2.0-beta.3.live.22",
            appBuild = "1 release",
            device = "TV · Android 12 (API 31)",
            screen = route,
            focused = null,
            game = null,
            linkChecks = SportsLiveReportInput.LinkChecks(),
            scores = "show · delay 30 s · real-time off"
        )
        val lines = SportsLiveReport.text(input, emptyList()).lines()
        assertTrue(lines.toString(), "From: Hold OK (playback_settings)" in lines)
        assertTrue(lines.toString(), "Screen: playback_settings" in lines)
    }
}
