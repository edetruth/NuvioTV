package com.nuvio.tv.core.sportslive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #207: the debug main-thread watchdog names each slow message, and never a link. */
class SportsLiveJankMeterTest {

    private var now = 0L
    private val meter = SportsLiveJankMeter(thresholdMs = 100L) { now }

    private val handler = "Handler (android.view.Choreographer\$FrameHandler) {1a2b3c}"
    private val callback = "android.view.Choreographer\$FrameDisplayEventReceiver@4d5e6f"

    private fun run(target: String, tookMs: Long): String? {
        assertNull(meter.onLine(">>>>> Dispatching to $target: 0"))
        now += tookMs
        return meter.onLine("<<<<< Finished to $target")
    }

    @Test
    fun `a message over the threshold is logged with its handler and callback`() {
        val line = run("$handler $callback", 1_250L)
        assertEquals("main thread busy 1250 ms: $handler $callback: 0", line)
    }

    @Test
    fun `a message within the threshold is not logged`() {
        assertNull(run("$handler $callback", 100L))
        assertNull(run("$handler $callback", 16L))
    }

    @Test
    fun `a finish with no dispatch seen, and other lines, log nothing`() {
        assertNull(meter.onLine("<<<<< Finished to $handler $callback"))
        assertNull(meter.onLine("something else"))
        // A finish counts once.
        run("$handler $callback", 500L)
        now += 500L
        assertNull(meter.onLine("<<<<< Finished to $handler $callback"))
    }

    @Test
    fun `a coroutine's continuation is kept, any link is dropped`() {
        val target = "Handler (android.os.Handler) {7} DispatchedContinuation[Dispatchers.Main, " +
            "Continuation at com.nuvio.tv.Example.load(Example.kt:12)@9] https://cdn.example.invalid/live/token123.m3u8"
        val line = run(target, 300L)!!
        assertTrue(line.contains("Continuation at com.nuvio.tv.Example.load(Example.kt:12)"))
        assertFalse(line.contains("example.invalid"))
        assertFalse(line.contains("token123"))
        assertTrue(line.contains("<link>"))
    }
}
