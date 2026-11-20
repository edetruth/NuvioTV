package com.nuvio.tv.core.sportslive.report

import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Saves a report when the app crashes (#172), then hands the crash on to [previous] (the system's handler, or one
 * another library put first), so the app still dies exactly as before. Saving runs on its own thread for at most
 * [budgetMs] and nothing in it can throw out of here: a crash report must never change how a crash ends.
 *
 * [snapshot] is the report's usual snapshot (only in-memory state, no network); when it throws, a bare report with
 * [app]'s version and device is saved instead. Only the first crash of the process is saved.
 */
class SportsLiveCrashHandler(
    private val previous: Thread.UncaughtExceptionHandler?,
    private val files: () -> SportsLiveReportFiles,
    private val secrets: () -> Collection<String>,
    private val app: App,
    private val snapshot: (number: Int, savedAtMs: Long, zone: ZoneId) -> SportsLiveReportInput,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val budgetMs: Long = BUDGET_MS
) : Thread.UncaughtExceptionHandler {

    /** What a bare report still says: the app's version and build, and the device. */
    data class App(val version: String, val build: String, val device: String)

    private val handled = AtomicBoolean(false)

    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            if (handled.compareAndSet(false, true)) {
                val worker = Thread({ runCatching { save(thread, error) } }, "nuvio-crash-report")
                worker.isDaemon = true
                worker.start()
                worker.join(budgetMs)
            }
        } catch (_: Throwable) {
            // Out of memory, or interrupted: no report, the crash goes on as before.
        }
        previous?.uncaughtException(thread, error)
    }

    /** Writes the crash report; the file saved. */
    internal fun save(thread: Thread, error: Throwable): java.io.File {
        val hidden = runCatching { secrets() }.getOrDefault(emptyList())
        val folder = files()
        val number = folder.nextNumber()
        val now = clock()
        val here = runCatching { zone() }.getOrDefault(ZoneId.of("UTC"))
        val input = runCatching { snapshot(number, now, here) }.getOrNull()?.copy(trigger = SportsLiveCrashReport.TRIGGER)
            ?: bare(number, now, here)
        val base = runCatching { SportsLiveReport.text(input, hidden) }.getOrElse { SportsLiveReport.text(bare(number, now, here), hidden) }
        val name = runCatching { thread.name }.getOrDefault("unknown")
        return folder.save(number, SportsLiveCrashReport.withCrash(base, name, error, hidden), now, here)
    }

    private fun bare(number: Int, now: Long, zone: ZoneId) = SportsLiveReportInput(
        number = number,
        savedAtMs = now,
        zone = zone,
        trigger = SportsLiveCrashReport.TRIGGER,
        appVersion = app.version,
        appBuild = app.build,
        device = app.device,
        screen = null,
        focused = null,
        game = null,
        linkChecks = SportsLiveReportInput.LinkChecks(),
        scores = "unknown"
    )

    companion object {
        /** The longest a crash waits for its report: the system's handler (and its dialog) follows. */
        const val BUDGET_MS = 200L
    }
}
