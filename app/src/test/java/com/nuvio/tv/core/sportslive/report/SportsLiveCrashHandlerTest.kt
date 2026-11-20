package com.nuvio.tv.core.sportslive.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId

/** Crash reports (#172): a crash saves a redacted report and is always handed on to the handler before. */
class SportsLiveCrashHandlerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val host = "private-addon.example.invalid"
    private val token = "s3cr3tT0ken123"
    private val addonName = "Secret Sports Addon"
    private val at = 1_763_667_725_000L
    private val utc = ZoneId.of("UTC")
    private val app = SportsLiveCrashHandler.App("1.2.0", "12345 release", "onn 4K Pro · Android 12 (API 31)")

    private class Recorder : Thread.UncaughtExceptionHandler {
        val calls = mutableListOf<Pair<Thread, Throwable>>()
        override fun uncaughtException(t: Thread, e: Throwable) {
            calls += t to e
        }
    }

    private fun dir() = File(temp.root, SportsLiveReportFiles.FOLDER)

    private fun input(number: Int, now: Long, zone: ZoneId) = SportsLiveReportInput(
        number = number,
        savedAtMs = now,
        zone = zone,
        trigger = "Hold Play/Pause",
        appVersion = "1.2.0",
        appBuild = "12345 release",
        device = "onn 4K Pro",
        screen = "sports_live",
        focused = "Celtics vs 76ers",
        game = null,
        linkChecks = SportsLiveReportInput.LinkChecks(),
        scores = "show · delay 30 s · real-time off"
    )

    private fun handler(
        previous: Thread.UncaughtExceptionHandler?,
        files: () -> SportsLiveReportFiles = { SportsLiveReportFiles(dir()) },
        snapshot: (Int, Long, ZoneId) -> SportsLiveReportInput = { number, now, zone -> input(number, now, zone) }
    ) = SportsLiveCrashHandler(
        previous = previous,
        files = files,
        secrets = { listOf(addonName, host) },
        app = app,
        snapshot = snapshot,
        clock = { at },
        zone = { utc },
        budgetMs = 10_000L
    )

    private val error = IllegalStateException("GET https://$host/$token/seg.ts?sig=$token from $addonName failed")
    private val thread = Thread({}, "OkHttp https://$host/$token/a")

    private fun saved(): String = SportsLiveReportFiles(dir()).latest()!!.readText()

    @Test
    fun `a crash saves a redacted report marked crash and chains to the handler before`() {
        val previous = Recorder()
        handler(previous).uncaughtException(thread, error)
        assertEquals(1, previous.calls.size)
        assertSame(thread, previous.calls[0].first)
        assertSame(error, previous.calls[0].second)
        val text = saved()
        for (secret in listOf(host, token, "example.invalid", addonName)) {
            assertFalse("$secret in\n$text", text.contains(secret, ignoreCase = true))
        }
        assertTrue(text, text.contains("From: crash"))
        assertTrue(text, text.contains("Screen: sports_live"))
        assertTrue(text, text.contains("Crash: java.lang.IllegalStateException: GET https://host-"))
        assertTrue(text, text.contains("[Crash on thread OkHttp https://host-"))
        assertEquals(text, File(dir(), SportsLiveReportFiles.LATEST).readText())
    }

    @Test
    fun `a throwing snapshot still saves a bare report and chains`() {
        val previous = Recorder()
        handler(previous, snapshot = { _, _, _ -> throw IllegalStateException("snapshot broke") }).uncaughtException(thread, error)
        assertEquals(1, previous.calls.size)
        val text = saved()
        assertTrue(text, text.contains("From: crash"))
        assertTrue(text, text.contains("Screen: unknown"))
        assertTrue(text, text.contains("App: 1.2.0 (12345 release)"))
        assertTrue(text, text.contains("Crash: java.lang.IllegalStateException"))
    }

    @Test
    fun `a report that cannot be saved still chains`() {
        val previous = Recorder()
        handler(previous, files = { throw IllegalStateException("no folder") }).uncaughtException(thread, error)
        assertEquals(1, previous.calls.size)
        assertSame(error, previous.calls[0].second)
    }

    @Test
    fun `only the first crash is saved and every crash chains`() {
        val previous = Recorder()
        val handler = handler(previous)
        handler.uncaughtException(thread, error)
        handler.uncaughtException(thread, RuntimeException("second"))
        assertEquals(2, previous.calls.size)
        assertEquals(1, SportsLiveReportFiles(dir()).reports().size)
    }

    @Test
    fun `with no handler before nothing is thrown`() {
        handler(null).uncaughtException(thread, error)
        assertTrue(saved().contains("From: crash"))
    }
}
