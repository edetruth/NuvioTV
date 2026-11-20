package com.nuvio.tv.core.sportslive.report

import com.nuvio.tv.core.LogRedact
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale

/** How the app's last process ended, when it is worth a report (#172): the system kept it in its exit records. */
enum class SportsLiveExitKind(val trigger: String, val label: String) {
    ANR("anr", "ANR (the app stopped responding)"),
    NATIVE_CRASH("native crash", "native crash")
}

/** One of the system's exit records of the app ([android.app.ApplicationExitInfo]); [kind] null for any other end. */
data class SportsLiveExitRecord(val timestampMs: Long, val kind: SportsLiveExitKind?, val description: String?)

/**
 * The crash reports (#172): a report saved by the crash handler ([SportsLiveCrashHandler]) is a normal report with
 * `From: crash`, a `Crash:` line under its time and the stack in the details; an ANR or native crash found in the
 * system's exit records at the next start is a short report of its own. Every message, thread name and odd frame
 * passes [LogRedact.report]: an exception message can hold a link or a token. No score is ever part of it.
 */
object SportsLiveCrashReport {
    const val TRIGGER = "crash"

    /** Most frames kept per exception, and exceptions followed down the cause chain. */
    const val MAX_FRAMES = 40
    const val MAX_CAUSES = 8

    /** The longest `Crash:` summary line (the details keep the message whole). */
    const val SUMMARY_MAX_CHARS = 200

    /** The ANR trace lines kept, and the exit records reported at one start. */
    const val TRACE_LINES = 200
    const val MAX_EXITS = 3

    private const val NUMBERS = "<numbers>"
    private val SCORE_LIKE = Regex("""(?<!\d)\d+\s*[-–:]\s*\d+(?!\d)""")
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx", Locale.ROOT)

    /** A frame made only of code names (class, method, file, line): nothing a user or an addon wrote can be in it. */
    private val CODE_FRAME = Regex("""^[\w$.<>/\-]+\((?:[\w$.\-]+(?::\d+)?|Native Method|Unknown Source)\)$""")

    /** [base] (a report's text, `From: crash`) with the crash added: a `Crash:` line under the time, the stack in the details. */
    fun withCrash(base: String, threadName: String, error: Throwable, secrets: Collection<String>): String {
        val lines = base.lines()
        val details = lines.indexOf(SportsLiveReport.DETAILS)
        val summary = if (details < 0) lines else lines.subList(0, details)
        val rest = if (details < 0) emptyList() else lines.subList(details + 1, lines.size)
        val head = describe(error, secrets)
        val short = if (head.length > SUMMARY_MAX_CHARS) head.take(SUMMARY_MAX_CHARS - 1) + "…" else head
        val section = listOf("[Crash on thread ${clean(threadName, secrets)}]") + stackLines(error, secrets) + ""
        return (summary.take(2) + "Crash: $short" + summary.drop(2) + SportsLiveReport.DETAILS + section + rest).joinToString("\n")
    }

    /** The exception, its frames and its causes, like a stack trace, with every message redacted. */
    fun stackLines(error: Throwable, secrets: Collection<String>): List<String> {
        val out = mutableListOf<String>()
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth <= MAX_CAUSES && seen.add(current)) {
            val t: Throwable = current
            val head = describe(t, secrets)
            out += if (depth == 0) head else "Caused by: $head"
            val frames = runCatching { t.stackTrace }.getOrNull().orEmpty()
            frames.take(MAX_FRAMES).forEach { out += "    at " + frame(it.toString(), secrets) }
            if (frames.size > MAX_FRAMES) out += "    … ${frames.size - MAX_FRAMES} more"
            current = runCatching { t.cause }.getOrNull()
            depth++
        }
        return out
    }

    /** `Type: message`, the message redacted, without anything shaped like a score, and on one line. */
    fun describe(error: Throwable, secrets: Collection<String>): String {
        val message = runCatching { error.message }.getOrNull()
        return error.javaClass.name + (message?.let { ": " + noScores(clean(it, secrets)) } ?: "")
    }

    /**
     * [text] with every score-like pair of numbers (`2-1`, `3 – 0`, `14:05`) replaced: an exception message or the
     * system's description could quote a feed's score (spoilers). A clock time goes too; telling them apart is a guess.
     */
    fun noScores(text: String): String = SCORE_LIKE.replace(text, NUMBERS)

    internal fun frame(text: String, secrets: Collection<String>): String =
        if (CODE_FRAME.matches(text)) text else clean(text, secrets)

    /** The records to report now: an ANR or native crash newer than [lastHandledMs], oldest first, the newest [MAX_EXITS]. */
    fun pendingExits(records: List<SportsLiveExitRecord>, lastHandledMs: Long?): List<SportsLiveExitRecord> =
        records.filter { it.kind != null && (lastHandledMs == null || it.timestampMs > lastHandledMs) }
            .sortedBy { it.timestampMs }
            .takeLast(MAX_EXITS)

    /** What to remember as handled after this start: the newest record seen, never earlier than before. */
    fun handledThrough(records: List<SportsLiveExitRecord>, lastHandledMs: Long?): Long? =
        (records.map { it.timestampMs } + listOfNotNull(lastHandledMs)).maxOrNull()

    /** A short report for an exit record: reason, the system's description, when, and an ANR's trace (first lines). */
    fun exitText(
        number: Int,
        savedAtMs: Long,
        zone: ZoneId,
        appVersion: String,
        appBuild: String,
        device: String,
        record: SportsLiveExitRecord,
        trace: List<String>?,
        secrets: Collection<String>
    ): String {
        val kind = record.kind ?: SportsLiveExitKind.NATIVE_CRASH
        return buildString {
            appendLine("Nuvio Live report #$number")
            appendLine("Saved: ${stamp(savedAtMs, zone)}")
            appendLine("Exit: ${kind.label} at ${stamp(record.timestampMs, zone)}, before this start")
            appendLine("Why: ${record.description?.takeIf { it.isNotBlank() }?.let { noScores(clean(it, secrets)) } ?: "not given"}")
            appendLine("App now: ${clean(appVersion, secrets)} (${clean(appBuild, secrets)})")
            appendLine("Device: ${clean(device, secrets)}")
            appendLine("From: ${kind.trigger}")
            appendLine(SportsLiveReport.DETAILS)
            if (kind == SportsLiveExitKind.ANR) {
                appendLine("[ANR trace, first $TRACE_LINES lines]")
                val lines = trace.orEmpty().take(TRACE_LINES)
                if (lines.isEmpty()) appendLine("not readable") else lines.forEach { appendLine(LogRedact.report(it, secrets).trimEnd()) }
                appendLine()
            }
            appendLine("Links, hosts, IP addresses, tokens, addon names and ids, e-mail and account ids are removed. No scores.")
        }
    }

    private fun stamp(ms: Long, zone: ZoneId): String = STAMP.format(Instant.ofEpochMilli(ms).atZone(zone))

    private fun clean(text: String, secrets: Collection<String>): String =
        LogRedact.report(text, secrets).replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim()
}
