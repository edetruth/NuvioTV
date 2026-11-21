package com.nuvio.tv.core.sportslive

import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.LogRedact

/**
 * Debug builds only (#207): times every message the main thread runs, through the main Looper's message logging, and
 * logs each one over [THRESHOLD_MS] under [TAG] with its handler and callback (`adb logcat -s NuvioJank`). A coroutine
 * resumed on Main names its continuation (class, method, file and line); a Compose frame shows as Choreographer's
 * frame callback. Never a link: anything shaped like one is dropped from the line ([SportsLiveJankMeter]).
 */
object SportsLiveJankWatchdog {
    const val TAG = "NuvioJank"
    const val THRESHOLD_MS = 100L

    fun install() {
        if (!BuildConfig.IS_DEBUG_BUILD) return
        val meter = SportsLiveJankMeter(THRESHOLD_MS) { SystemClock.uptimeMillis() }
        Looper.getMainLooper().setMessageLogging { line -> meter.onLine(line)?.let { Log.w(TAG, LogRedact.text(it)) } }
    }
}

/**
 * Reads the Looper's ">>>>> Dispatching to ‹handler› ‹callback›: ‹what›" and "<<<<< Finished to ‹handler› ‹callback›"
 * lines: a message that took longer than [thresholdMs] by [clock] gives one line to log, else null.
 */
internal class SportsLiveJankMeter(private val thresholdMs: Long, private val clock: () -> Long) {
    private var startedAt = -1L
    private var target = ""

    fun onLine(line: String): String? {
        if (line.startsWith(DISPATCHING)) {
            startedAt = clock()
            target = line.substring(DISPATCHING.length)
            return null
        }
        if (!line.startsWith(FINISHED)) return null
        val began = startedAt
        startedAt = -1L
        if (began < 0) return null
        val tookMs = clock() - began
        if (tookMs <= thresholdMs) return null
        return "main thread busy $tookMs ms: ${describe(target)}"
    }

    companion object {
        private const val DISPATCHING = ">>>>> Dispatching to "
        private const val FINISHED = "<<<<< Finished to "
        private const val MAX_CHARS = 400
        private val LINK = Regex("""[A-Za-z][A-Za-z0-9+.-]*://\S*""")

        /** The handler and callback as the Looper wrote them, any link dropped, at most [MAX_CHARS] long. */
        internal fun describe(target: String): String = LINK.replace(target, "<link>").take(MAX_CHARS)
    }
}
