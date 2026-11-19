package com.nuvio.tv.ui.screens.sportslive

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** The remote's mic as a text source (#149): [available] when the device has a speech recogniser. */
class SportsLiveVoiceInput internal constructor(
    val available: Boolean,
    private val listeningState: () -> Boolean,
    private val startListening: () -> Unit
) {
    val listening: Boolean get() = listeningState()

    /** Starts listening when [event] is the mic (Search / voice-assist key) going down; true when it was. */
    fun onKey(event: KeyEvent): Boolean {
        if (!available) return false
        if (event.key != Key.Search && event.key != Key.VoiceAssist) return false
        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) startListening()
        return true
    }
}

/**
 * The remote's mic button fills a field: [onResult] gets the recognised words. Asks for the microphone
 * the first time. A failure (no speech, busy, no permission) just stops listening: no system toast.
 */
@Composable
fun rememberSportsLiveVoiceInput(onResult: (String) -> Unit): SportsLiveVoiceInput {
    val context = LocalContext.current
    val latestOnResult by rememberUpdatedState(onResult)
    var listening by remember { mutableStateOf(false) }
    val available = remember(context) { SpeechRecognizer.isRecognitionAvailable(context) }
    val recognizer = remember(context, available) {
        if (available) runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull() else null
    }
    val intent = remember(context) {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
    }
    val listen: () -> Unit = {
        listening = runCatching {
            recognizer?.cancel()
            recognizer?.startListening(intent)
        }.isSuccess && recognizer != null
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen()
    }
    DisposableEffect(recognizer) {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onError(error: Int) {
                listening = false
            }

            override fun onResults(results: Bundle?) {
                listening = false
                val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                if (words.isNotEmpty()) latestOnResult(words)
            }
        })
        onDispose {
            recognizer?.setRecognitionListener(null)
            recognizer?.destroy()
        }
    }
    return SportsLiveVoiceInput(
        available = recognizer != null,
        listeningState = { listening },
        startListening = {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) listen() else permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    )
}
