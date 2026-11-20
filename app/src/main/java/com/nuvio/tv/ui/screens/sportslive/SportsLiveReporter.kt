package com.nuvio.tv.ui.screens.sportslive

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.sportslive.SportsLiveDisplayText
import com.nuvio.tv.core.sportslive.SportsLiveGameReadiness
import com.nuvio.tv.core.sportslive.SportsLiveGameWatch
import com.nuvio.tv.core.sportslive.SportsLiveLinkFailure
import com.nuvio.tv.core.sportslive.SportsLiveLinkMarks
import com.nuvio.tv.core.sportslive.SportsLiveReadyMark
import com.nuvio.tv.core.sportslive.SportsLiveToastKind
import com.nuvio.tv.core.sportslive.SportsLiveToasts
import com.nuvio.tv.core.sportslive.SportsLiveUnidentified
import com.nuvio.tv.core.sportslive.report.SportsLiveReport
import com.nuvio.tv.core.sportslive.report.SportsLiveReportFiles
import com.nuvio.tv.core.sportslive.report.SportsLiveReportInput
import com.nuvio.tv.core.sportslive.report.SportsLiveReportKey
import com.nuvio.tv.core.sportslive.report.SportsLiveReportKeyAction
import com.nuvio.tv.core.sportslive.report.SportsLiveReportTrail
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.ui.screens.player.LoggingDataSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.URI
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Report a problem (#172): saves a privacy-safe snapshot of what the app is doing to a file the coordinator pulls over
 * adb ([SportsLiveReportFiles]), shows its summary as a QR code where adb is not at hand, and takes a short voice note.
 * Triggered by holding Play/Pause or Info ([SportsLiveReportKey], from MainActivity) or from Settings → Sports & Live →
 * More → Report a problem. Nothing leaves the TV by itself.
 */
@Singleton
class SportsLiveReporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val addonRepository: AddonRepository,
    private val prefs: SportsLivePreferencesDataStore,
    private val authManager: AuthManager
) {
    /** One saved report: its [number], its [file] and the [text] in it. */
    data class Saved(val number: Int, val file: File, val text: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val key = SportsLiveReportKey()

    private val dir: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, SportsLiveReportFiles.FOLDER)

    private val files: SportsLiveReportFiles
        get() = SportsLiveReportFiles(dir)

    /** Where `adb pull` finds the reports; null when the TV has no external files folder (then only the QR code helps). */
    val pullPath: String?
        get() = context.getExternalFilesDir(null)?.let { "/sdcard/Android/data/${context.packageName}/files/${SportsLiveReportFiles.FOLDER}/" }

    /** MainActivity's dispatchKeyEvent: true when the key is the shortcut's (eaten); a long enough hold saves a report. */
    fun onKey(event: KeyEvent): Boolean {
        val action = key.onKey(event.keyCode, event.action, event.repeatCount, event.downTime, event.eventTime)
        if (action == SportsLiveReportKeyAction.REPORT) {
            val trigger = if (event.keyCode == KeyEvent.KEYCODE_INFO) "Hold Info" else "Hold Play/Pause"
            scope.launch {
                val saved = runCatching { save(trigger) }.getOrNull()
                if (saved == null) {
                    SportsLiveToasts.show(SportsLiveToastKind.PROBLEM, context.getString(R.string.sports_live_report_failed))
                } else {
                    SportsLiveToasts.show(
                        SportsLiveToastKind.INFO,
                        context.getString(R.string.sports_live_report_saved, saved.number),
                        context.getString(if (adbOn()) R.string.sports_live_report_saved_adb else R.string.sports_live_report_saved_qr)
                    )
                }
            }
        }
        return action != SportsLiveReportKeyAction.PASS
    }

    /** Saves a report now, asked for by [trigger]. */
    suspend fun save(trigger: String): Saved = withContext(Dispatchers.IO) {
        val secrets = secrets()
        val scores = withTimeoutOrNull(LOOKUP_MS) { prefs.scoreSettings.first() }
        lock.withLock {
            val files = files
            val number = files.nextNumber()
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val text = SportsLiveReport.text(input(number, now, zone, trigger, scores?.let { scoresLine(it) } ?: "unknown"), secrets)
            Saved(number, files.save(number, text, now, zone), text)
        }
    }

    /** The newest saved report, or null when there is none. */
    suspend fun latest(): Saved? = withContext(Dispatchers.IO) {
        val file = files.latest() ?: return@withContext null
        val number = SportsLiveReportFiles.numberOf(file.name) ?: return@withContext null
        runCatching { Saved(number, file, file.readText()) }.getOrNull()
    }

    /** Adds the voice note's [words] to [saved] (redacted like the rest); the updated report. */
    suspend fun addNote(saved: Saved, words: String): Saved = withContext(Dispatchers.IO) {
        val secrets = secrets()
        lock.withLock {
            val text = SportsLiveReport.withNote(saved.text, words, secrets)
            files.rewrite(saved.file, text)
            saved.copy(text = text)
        }
    }

    /** USB or Wi-Fi debugging is on: the coordinator can pull the file. */
    fun adbOn(): Boolean {
        val resolver = context.contentResolver
        val usb = runCatching { Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0) == 1 }.getOrDefault(false)
        val wifi = runCatching { Settings.Global.getInt(resolver, ADB_WIFI_ENABLED, 0) == 1 }.getOrDefault(false)
        return usb || wifi
    }

    private fun input(number: Int, now: Long, zone: ZoneId, trigger: String, scores: String): SportsLiveReportInput {
        val item = SportsLiveZapSessions.current()?.current ?: SportsLiveGameWatch.shared.lastWatchedItem()
        val readiness = SportsLiveGameReadiness.shared
        val marks = readiness.marks.value
        val game = item?.let {
            val snapshot = SportsLiveGameWatch.shared.snapshot(it.id)
            SportsLiveReportInput.Game(
                title = SportsLiveDisplayText.name(it.meta.name),
                teams = it.teams?.let { pair -> "${pair.home} vs ${pair.away}" },
                sport = it.sport,
                league = snapshot?.leagueName ?: it.meta.genres.firstOrNull(),
                live = it.isLive,
                sources = it.sourceCount,
                espn = snapshot?.let { s -> "${s.eventId} ${s.state.name.lowercase()}" }
            )
        }
        val (failed, played) = item?.let { SportsLiveLinkMarks.shared.counts(it.id) } ?: (emptyMap<SportsLiveLinkFailure, Int>() to 0)
        return SportsLiveReportInput(
            number = number,
            savedAtMs = now,
            zone = zone,
            trigger = trigger,
            appVersion = BuildConfig.VERSION_NAME,
            appBuild = "${BuildConfig.VERSION_CODE} ${BuildConfig.BUILD_TYPE}",
            device = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            screen = SportsLiveReportTrail.screen,
            focused = SportsLiveReportTrail.focused,
            game = game,
            linkChecks = SportsLiveReportInput.LinkChecks(
                gameMark = item?.let { readiness.of(it)?.name },
                gameChecking = item?.let { readiness.isChecking(it) } ?: false,
                gameFailed = failed.mapKeys { it.key.name.lowercase().replace('_', ' ') },
                gamePlayed = played,
                ready = marks.values.count { it == SportsLiveReadyMark.READY },
                noStream = marks.values.count { it == SportsLiveReadyMark.NO_STREAM },
                checking = readiness.checking.value.size
            ),
            scores = scores,
            playbackEvents = LoggingDataSource.recentEvents(),
            signInLog = SportsLiveReportTrail.signInLines(),
            unplaced = SportsLiveUnidentified.shared.snapshot().take(MAX_UNPLACED).map { "${it.loads}x | ${it.title}" },
            nearMisses = if (BuildConfig.IS_DEBUG_BUILD) SportsLiveReportTrail.nearMissLines() else null
        )
    }

    private fun scoresLine(settings: com.nuvio.tv.core.sportslive.scores.ScoreSettings): String =
        "${settings.mode.name.lowercase()} · delay ${settings.delaySeconds} s · real-time ${if (settings.realTime) "on" else "off"}"

    /** What must never be in a report: every addon's names, id, link and host, and the account's e-mail and id. */
    private suspend fun secrets(): List<String> {
        val addons = withTimeoutOrNull(LOOKUP_MS) { runCatching { addonRepository.getInstalledAddons().first() }.getOrNull() }.orEmpty()
        val account = (authManager.authState.value as? AuthState.FullAccount)?.let { listOf(it.email, it.userId) }.orEmpty()
        return addons.flatMap { addon ->
            listOfNotNull(addon.name, addon.displayName, addon.id, addon.baseUrl, runCatching { URI(addon.baseUrl).host }.getOrNull())
        } + account
    }

    private companion object {
        const val LOOKUP_MS = 2_000L
        const val MAX_UNPLACED = 30

        /** Settings.Global.ADB_WIFI_ENABLED (Android 11), by name so older SDK stubs compile. */
        const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    }
}

/** For screens without a view model of their own (Settings → Sports & Live). */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface SportsLiveReporterEntryPoint {
    fun sportsLiveReporter(): SportsLiveReporter
}
