package com.nuvio.tv.core.sportslive.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** Crash reports (#172): the stack is redacted, code frames stay readable, and each exit record is reported once. */
class SportsLiveCrashReportTest {

    private val host = "private-addon.example.invalid"
    private val token = "s3cr3tT0ken123"
    private val addonName = "Secret Sports Addon"
    private val secrets = listOf(addonName, host)
    private val utc = ZoneId.of("UTC")

    private fun assertNoSecret(text: String) {
        for (secret in listOf(host, token, "example.invalid", addonName)) {
            assertFalse("$secret in\n$text", text.contains(secret, ignoreCase = true))
        }
    }

    private fun crash(): Throwable {
        val cause = java.io.IOException("auth failed for https://$host/$token/manifest.json token=$token")
        cause.stackTrace = arrayOf(
            StackTraceElement("Script", "eval", "https://$host/$token/x.js", 3),
            StackTraceElement("okhttp3.internal.Call", "execute", "Call.kt", 40)
        )
        val error = IllegalStateException("GET https://$host/$token/seg.ts?sig=$token from $addonName failed", cause)
        error.stackTrace = arrayOf(StackTraceElement("com.nuvio.tv.Foo", "bar", "Foo.kt", 12))
        return error
    }

    @Test
    fun `a link or token in a message or a frame is redacted and code frames stay as they are`() {
        val lines = SportsLiveCrashReport.stackLines(crash(), secrets)
        val text = lines.joinToString("\n")
        assertNoSecret(text)
        assertTrue(text, lines[0].startsWith("java.lang.IllegalStateException: GET https://host-"))
        assertTrue(text, lines[0].contains("<private>"))
        assertEquals("    at com.nuvio.tv.Foo.bar(Foo.kt:12)", lines[1])
        assertTrue(text, lines[2].startsWith("Caused by: java.io.IOException: auth failed for https://host-"))
        assertTrue(text, lines[3].startsWith("    at Script.eval(https://host-"))
        assertEquals("    at okhttp3.internal.Call.execute(Call.kt:40)", lines[4])
        assertEquals(5, lines.size)
    }

    @Test
    fun `a long stack is cut and a cause loop ends`() {
        val error = RuntimeException("loop")
        error.stackTrace = Array(SportsLiveCrashReport.MAX_FRAMES + 5) { StackTraceElement("a.B", "c", "B.kt", it) }
        val other = RuntimeException("other", error)
        error.initCause(other)
        val lines = SportsLiveCrashReport.stackLines(error, emptyList())
        assertEquals("    … 5 more", lines[SportsLiveCrashReport.MAX_FRAMES + 1])
        assertEquals(1, lines.count { it.startsWith("Caused by") })
    }

    @Test
    fun `the crash goes under the time and its stack opens the details`() {
        val base = "Nuvio Live report #3\nSaved: x\nApp: y\nFrom: crash\n${SportsLiveReport.DETAILS}\n[Sign-in log]\nnone\n\nfooter\n"
        val text = SportsLiveCrashReport.withCrash(base, "OkHttp https://$host/$token/a", crash(), secrets)
        assertNoSecret(text)
        val lines = text.lines()
        assertEquals("Saved: x", lines[1])
        assertTrue(lines[2], lines[2].startsWith("Crash: java.lang.IllegalStateException: GET https://host-"))
        assertEquals("App: y", lines[3])
        val details = lines.indexOf(SportsLiveReport.DETAILS)
        assertTrue(lines[details + 1], lines[details + 1].startsWith("[Crash on thread OkHttp https://host-"))
        assertTrue(text.contains("[Sign-in log]"))
        assertTrue(text.contains("footer"))
    }

    @Test
    fun `the summary line is cut but the details keep the whole message`() {
        val long = "word ".repeat(100).trim()
        val text = SportsLiveCrashReport.withCrash("a\nb\n${SportsLiveReport.DETAILS}\n", "main", RuntimeException(long), emptyList())
        val crashLine = text.lines()[2]
        assertEquals(SportsLiveCrashReport.SUMMARY_MAX_CHARS + "Crash: ".length, crashLine.length)
        assertTrue(crashLine.endsWith("…"))
        assertTrue(text.contains("java.lang.RuntimeException: $long"))
    }

    @Test
    fun `a score-like pair of numbers never reaches the crash or why lines`() {
        val error = IllegalArgumentException("bad event Celtics 102-99 76ers, then 3 – 0 at 14:05 in game 7")
        val text = SportsLiveCrashReport.withCrash("a\nb\n${SportsLiveReport.DETAILS}\n", "main", error, emptyList())
        for (score in listOf("102-99", "3 – 0", "14:05")) assertFalse("$score in\n$text", text.contains(score))
        assertTrue(text, text.lines()[2].startsWith("Crash: java.lang.IllegalArgumentException: bad event Celtics <numbers> 76ers"))
        assertTrue(text, text.contains("game 7"))
        val why = exit(SportsLiveExitRecord(1_000L, SportsLiveExitKind.ANR, "stuck after 2:1"), null).lines()[3]
        assertEquals("Why: stuck after <numbers>", why)
    }

    private val anr1 = SportsLiveExitRecord(1_000L, SportsLiveExitKind.ANR, "Input dispatching timed out")
    private val native2 = SportsLiveExitRecord(2_000L, SportsLiveExitKind.NATIVE_CRASH, "crash")
    private val other3 = SportsLiveExitRecord(3_000L, null, "user requested")

    @Test
    fun `only ANRs and native crashes newer than the last handled are reported, oldest first`() {
        val records = listOf(other3, native2, anr1)
        assertEquals(listOf(anr1, native2), SportsLiveCrashReport.pendingExits(records, 0L))
        assertEquals(listOf(native2), SportsLiveCrashReport.pendingExits(records, 1_000L))
        assertEquals(emptyList<SportsLiveExitRecord>(), SportsLiveCrashReport.pendingExits(records, 2_000L))
    }

    @Test
    fun `the first start reports nothing and only sets the mark`() {
        val records = listOf(anr1, native2, other3)
        assertEquals(emptyList<SportsLiveExitRecord>(), SportsLiveCrashReport.pendingExits(records, null))
        assertEquals(3_000L, SportsLiveCrashReport.handledThrough(records, null, 50_000L))
        val anr4 = SportsLiveExitRecord(4_000L, SportsLiveExitKind.ANR, null)
        assertEquals(listOf(anr4), SportsLiveCrashReport.pendingExits(records + anr4, 3_000L))
    }

    @Test
    fun `an exit record is reported once across starts`() {
        val records = listOf(anr1, native2, other3)
        var last: Long? = 0L
        val first = SportsLiveCrashReport.pendingExits(records, last)
        last = SportsLiveCrashReport.handledThrough(records, last, 50_000L)
        assertEquals(2, first.size)
        assertEquals(3_000L, last)
        assertEquals(emptyList<SportsLiveExitRecord>(), SportsLiveCrashReport.pendingExits(records, last))
        val anr4 = SportsLiveExitRecord(4_000L, SportsLiveExitKind.ANR, null)
        assertEquals(listOf(anr4), SportsLiveCrashReport.pendingExits(records + anr4, last))
    }

    @Test
    fun `the handled time never goes back and a first start with no records marks now`() {
        assertEquals(50_000L, SportsLiveCrashReport.handledThrough(emptyList(), null, 50_000L))
        assertEquals(9_000L, SportsLiveCrashReport.handledThrough(listOf(anr1), 9_000L, 50_000L))
        val anr = SportsLiveExitRecord(60_000L, SportsLiveExitKind.ANR, null)
        assertEquals(listOf(anr), SportsLiveCrashReport.pendingExits(listOf(anr), 50_000L))
    }

    @Test
    fun `at most the newest few exits are reported at one start`() {
        val many = (1..10).map { SportsLiveExitRecord(it * 1_000L, SportsLiveExitKind.ANR, null) }
        val pending = SportsLiveCrashReport.pendingExits(many, 0L)
        assertEquals(SportsLiveCrashReport.MAX_EXITS, pending.size)
        assertEquals(10_000L, pending.last().timestampMs)
    }

    private fun exit(record: SportsLiveExitRecord, trace: List<String>?) = SportsLiveCrashReport.exitText(
        number = 7,
        savedAtMs = 1_763_667_725_000L,
        zone = utc,
        appVersion = "1.2.0",
        appBuild = "12345 release",
        device = "onn 4K Pro · Android 12 (API 31)",
        record = record,
        trace = trace,
        secrets = secrets
    )

    @Test
    fun `an ANR report has the reason, the time and the first trace lines redacted`() {
        val head = listOf("\"main\" prio=5 tid=1 Blocked", "  at com.nuvio.tv.Foo.bar(Foo.kt:12)", "  url https://$host/$token/x")
        val trace = head + List(300) { "line $it" }
        val record = SportsLiveExitRecord(1_763_667_600_000L, SportsLiveExitKind.ANR, "Input dispatching timed out, $host")
        val text = exit(record, trace)
        assertNoSecret(text)
        val lines = text.lines()
        assertEquals("Nuvio Live report #7", lines[0])
        assertEquals("Saved: 2025-11-20 19:42:05 +00:00", lines[1])
        assertEquals("Exit: ANR (the app stopped responding) at 2025-11-20 19:40:00 +00:00, before this start", lines[2])
        assertTrue(lines[3], lines[3].startsWith("Why: Input dispatching timed out, "))
        assertTrue(text.contains("From: anr"))
        assertTrue(text.contains("  at com.nuvio.tv.Foo.bar(Foo.kt:12)"))
        assertTrue(text.contains("line 196\n"))
        assertFalse(text.contains("line 197\n"))
    }

    @Test
    fun `a native crash report has no trace section and an unreadable ANR trace says so`() {
        val native = exit(native2, null)
        assertTrue(native.contains("From: native crash"))
        assertFalse(native.contains("[ANR trace"))
        assertTrue(exit(anr1, null).contains("not readable"))
    }
}
