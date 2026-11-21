package com.nuvio.tv.ui.screens.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.sportslive.report.SportsLiveReport
import com.nuvio.tv.core.sportslive.report.SportsLiveReportFiles
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.sportslive.SportsLiveReporter
import com.nuvio.tv.ui.screens.sportslive.SportsLiveReporterEntryPoint
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButton
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButtonRole
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * Settings → Sports & Live → More → Report a problem (#172): the newest report (saved now when there is none) as a QR
 * code of its summary for a phone, where adb pulls the whole file from, "Add a voice note" (where the TV can take
 * speech) and "Save a new report". The voice note is the TV's speech recognition, kept as words (redacted like the rest),
 * never as audio.
 */
@Composable
internal fun SportsLiveReportDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val reporter = remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(context.applicationContext, SportsLiveReporterEntryPoint::class.java)
            .sportsLiveReporter()
    }
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf<SportsLiveReporter.Saved?>(null) }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<Int?>(null) }

    fun saveNew() {
        loading = true
        status = null
        scope.launch {
            saved = runCatching { reporter.save(TRIGGER_SETTINGS) }.getOrNull() ?: saved
            if (saved == null) status = R.string.sports_live_report_failed
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        // The newest report as it is only while it is fresh (or a crash's): an old one would show a stale state.
        val latest = reporter.latest()
        if (latest != null && SportsLiveReportFiles.showsAsIs(latest.file.name, latest.text, latest.file.lastModified(), System.currentTimeMillis())) {
            saved = latest
            loading = false
        } else {
            saved = latest
            saveNew()
        }
    }

    val qr by produceState<Bitmap?>(null, saved?.text) {
        val text = saved?.text
        value = if (text == null) null else withContext(Dispatchers.Default) {
            runCatching { QrCodeGenerator.generate(SportsLiveReport.qrPayload(text), QR_PX, margin = 1) }.getOrNull()
        }
    }

    val voice = rememberVoiceNote(
        onStatus = { status = it },
        onWords = { words ->
            val current = saved ?: return@rememberVoiceNote
            scope.launch {
                saved = runCatching { reporter.addNote(current, words) }.getOrNull() ?: current
                status = R.string.sports_live_report_note_added
            }
        }
    )

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(loading) { if (!loading) runCatching { firstFocus.requestFocus() } }

    val report = saved
    NuvioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.sports_live_setting_report_problem),
        subtitle = when {
            loading -> stringResource(R.string.sports_live_report_saving)
            report == null -> stringResource(R.string.sports_live_report_failed)
            else -> stringResource(
                R.string.sports_live_report_subtitle,
                report.number,
                DateFormat.getTimeFormat(context).format(Date(report.file.lastModified()))
            )
        },
        width = 760.dp,
        usePlatformDefaultWidth = false
    ) {
        if (report != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg), verticalAlignment = Alignment.Top) {
                qr?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.cd_qr_code),
                        modifier = Modifier.size(QR_DP.dp)
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                    BodyLine(stringResource(R.string.sports_live_report_scan))
                    val path = reporter.pullPath
                    if (path != null) {
                        BodyLine(stringResource(R.string.sports_live_report_pull))
                        Text(
                            text = "adb pull $path${SportsLiveReportFiles.LATEST}",
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextTertiary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        BodyLine(stringResource(R.string.sports_live_report_no_pull))
                    }
                    BodyLine(stringResource(R.string.sports_live_report_privacy))
                }
            }
        }
        status?.let { BodyLine(stringResource(it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(SportsLiveTokens.Size.ButtonGap)) {
            if (voice != null && report != null) {
                SportsLiveButton(onClick = voice, modifier = Modifier.focusRequester(firstFocus), role = SportsLiveButtonRole.PRIMARY) {
                    ButtonText(R.string.sports_live_report_note)
                }
            }
            SportsLiveButton(onClick = { if (!loading) saveNew() }) {
                ButtonText(R.string.sports_live_report_new)
            }
            SportsLiveButton(
                onClick = onDismiss,
                modifier = if (voice != null && report != null) Modifier else Modifier.focusRequester(firstFocus)
            ) {
                ButtonText(R.string.action_close)
            }
        }
    }
}

/** MainActivity's layer: the report dialog a remote save opens when adb is off and no video plays (#172, decision 4). */
@Composable
internal fun SportsLiveReportQrHost() {
    val context = LocalContext.current
    val reporter = remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(context.applicationContext, SportsLiveReporterEntryPoint::class.java)
            .sportsLiveReporter()
    }
    val open by reporter.qrPrompt.collectAsState()
    if (open) SportsLiveReportDialog(onDismiss = reporter::dismissQrPrompt)
}

@Composable
private fun BodyLine(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary)
}

@Composable
private fun ButtonText(@StringRes res: Int) {
    Text(stringResource(res), style = SportsLiveTokens.style(SportsLiveTokens.Type.Button))
}

/**
 * Starts one voice note: the TV's speech recognition, asking for the microphone first when needed. Null when this TV
 * has no speech recognition (the button is left out). [onStatus] gets what to tell the user; [onWords] the words heard.
 */
@Composable
private fun rememberVoiceNote(onStatus: (Int?) -> Unit, onWords: (String) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val recognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(context)) runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull() else null
    } ?: return null
    val status by rememberUpdatedState(onStatus)
    val words by rememberUpdatedState(onWords)

    fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        status(R.string.sports_live_report_note_listening)
        runCatching {
            recognizer.cancel()
            recognizer.startListening(intent)
        }.onFailure { status(R.string.sports_live_report_note_missed) }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen() else status(R.string.sports_live_report_note_no_mic)
    }

    DisposableEffect(recognizer) {
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit

            override fun onError(error: Int) {
                status(
                    if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) R.string.sports_live_report_note_no_mic
                    else R.string.sports_live_report_note_missed
                )
            }

            override fun onResults(results: Bundle?) {
                val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                if (heard.isEmpty()) status(R.string.sports_live_report_note_missed) else words(heard)
            }
        })
        onDispose {
            recognizer.setRecognitionListener(null)
            recognizer.destroy()
        }
    }

    return {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) listen() else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
}

private const val TRIGGER_SETTINGS = "Settings"
private const val QR_PX = 420
private const val QR_DP = 240
