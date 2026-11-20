package com.nuvio.tv.ui.screens.sportslive

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.sportslive.report.SportsLiveCrashHandler
import com.nuvio.tv.core.sportslive.report.SportsLiveCrashReport
import com.nuvio.tv.core.sportslive.report.SportsLiveExitKind
import com.nuvio.tv.core.sportslive.report.SportsLiveExitRecord
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Crash reports (#172), installed once from NuvioApplication.onCreate: a crash on any thread saves a report
 * ([SportsLiveCrashHandler]) before the crash goes on as before, and at each start an ANR or native crash the system
 * recorded for the last process (Android 11+) is saved as a short report, once per exit record.
 */
object SportsLiveCrashCapture {
    private val installed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val PREFS = "sports_live_reports"
    private const val KEY_EXIT_HANDLED = "exit_handled_ms"

    /** The exit records asked for: the system keeps a few per app. */
    private const val EXIT_RECORDS = 16

    fun install(app: Application) {
        if (!installed.compareAndSet(false, true)) return
        try {
            val reporter = EntryPointAccessors.fromApplication(app, SportsLiveReporterEntryPoint::class.java).sportsLiveReporter()
            reporter.startCrashCaches()
            Thread.setDefaultUncaughtExceptionHandler(
                SportsLiveCrashHandler(
                    previous = Thread.getDefaultUncaughtExceptionHandler(),
                    files = { reporter.files },
                    secrets = { reporter.secretsNow() },
                    app = SportsLiveCrashHandler.App(
                        version = BuildConfig.VERSION_NAME,
                        build = "${BuildConfig.VERSION_CODE} ${BuildConfig.BUILD_TYPE}",
                        device = device()
                    ),
                    snapshot = { number, now, zone -> reporter.crashInput(number, now, zone) }
                )
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                scope.launch { runCatching { saveExitReports(app, reporter) } }
            }
        } catch (_: Throwable) {
            // Crash reports are a helper: the app starts the same without them.
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun saveExitReports(app: Application, reporter: SportsLiveReporter) {
        val manager = app.getSystemService(ActivityManager::class.java) ?: return
        val infos = manager.getHistoricalProcessExitReasons(app.packageName, 0, EXIT_RECORDS)
            .filter { it.processName == app.packageName }
        val records = infos.map { SportsLiveExitRecord(it.timestamp, kindOf(it.reason), it.description) }
        val store = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = if (store.contains(KEY_EXIT_HANDLED)) store.getLong(KEY_EXIT_HANDLED, 0L) else null
        val pending = SportsLiveCrashReport.pendingExits(records, last)
        // Remembered before saving: a record that makes saving fail is still never tried twice. The first start only
        // sets the mark (older records are not reported).
        store.edit().putLong(KEY_EXIT_HANDLED, SportsLiveCrashReport.handledThrough(records, last, System.currentTimeMillis())).commit()
        if (pending.isEmpty()) return
        val secrets = reporter.secrets()
        for (record in pending) {
            val info = infos.firstOrNull { it.timestamp == record.timestampMs }
            val trace = if (record.kind == SportsLiveExitKind.ANR && info != null) readTrace(info) else null
            reporter.saveText { number, now, zone ->
                SportsLiveCrashReport.exitText(
                    number = number,
                    savedAtMs = now,
                    zone = zone,
                    appVersion = BuildConfig.VERSION_NAME,
                    appBuild = "${BuildConfig.VERSION_CODE} ${BuildConfig.BUILD_TYPE}",
                    device = device(),
                    record = record,
                    trace = trace,
                    secrets = secrets
                )
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun kindOf(reason: Int): SportsLiveExitKind? = when (reason) {
        ApplicationExitInfo.REASON_ANR -> SportsLiveExitKind.ANR
        ApplicationExitInfo.REASON_CRASH_NATIVE -> SportsLiveExitKind.NATIVE_CRASH
        else -> null
    }

    /** The ANR trace's first lines; null when the system kept none or it cannot be read. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun readTrace(info: ApplicationExitInfo): List<String>? = runCatching {
        info.traceInputStream?.bufferedReader()?.use { reader ->
            generateSequence { reader.readLine() }.take(SportsLiveCrashReport.TRACE_LINES).toList()
        }
    }.getOrNull()

    private fun device(): String =
        "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
}
