package com.nuvio.tv.core.sportslive

import java.net.UnknownHostException
import kotlinx.coroutines.delay

/**
 * The start-up timeout of a sports link (#138, #158), for the full player (watch and zap), each dual
 * pane and the mini player. A link with no first frame is given up (the next link is tried):
 * - never before [SportsLiveStreamThresholds.NO_FIRST_FRAME_MS], or
 *   [SportsLiveStreamThresholds.NO_FIRST_FRAME_AFTER_FAILED_REQUEST_MS] once one of its manifest or
 *   segment requests has failed;
 * - after that, once it has made no progress ([Progress]) for [SportsLiveStreamThresholds.STARTUP_NO_PROGRESS_MS]
 *   (the shorter failed-request time once a request failed), so a slow link that keeps loading is
 *   not cut off;
 * - in any case after [SportsLiveStreamThresholds.STARTUP_HARD_CAP_MS].
 * It is a step of the recovery ladder ([SportsLiveStreamHealth.Problem.NO_START]).
 */
object SportsLiveStartupTimeout {
    /** How often [await] samples the link's progress. */
    internal const val CHECK_EVERY_MS = 500L

    /**
     * What a link still starting has loaded so far: [bytes] read over the network (only ever grows,
     * from any baseline) and [bufferedMs] of media buffered ahead of the playback position.
     */
    data class Progress(val bytes: Long, val bufferedMs: Long)

    /** How long a link is always given, given whether a request of it already [requestFailed]. */
    fun limitMs(requestFailed: Boolean): Long =
        SportsLiveDebugTiming.override(SportsLiveDebugTiming.STARTUP_TIMEOUT) ?: if (requestFailed) {
            SportsLiveStreamThresholds.NO_FIRST_FRAME_AFTER_FAILED_REQUEST_MS
        } else {
            SportsLiveStreamThresholds.NO_FIRST_FRAME_MS
        }

    /** Past [limitMs], how long a link may go without progress. */
    fun noProgressLimitMs(requestFailed: Boolean): Long =
        if (requestFailed) {
            SportsLiveStreamThresholds.NO_FIRST_FRAME_AFTER_FAILED_REQUEST_MS
        } else {
            SportsLiveStreamThresholds.STARTUP_NO_PROGRESS_MS
        }

    /** How long a link may take to show its first frame, however much it loads. */
    fun hardCapMs(): Long =
        SportsLiveDebugTiming.override(SportsLiveDebugTiming.STARTUP_TIMEOUT)
            ?: SportsLiveStreamThresholds.STARTUP_HARD_CAP_MS

    /**
     * One link's start-up clock. The owner calls [advance] about every [CHECK_EVERY_MS] while the link
     * shows no first frame, with what it loaded so far; [advance] says when the link has had its time.
     * Progress is [SportsLiveStreamThresholds.STARTUP_PROGRESS_MIN_BYTES] more bytes than at the last
     * progress (a playlist reload alone is not), or more media buffered than ever before.
     */
    class Watch(start: Progress? = null) {
        private var elapsedMs = 0L
        private var lastProgressAtMs = 0L
        private var progressBytes: Long? = start?.bytes
        private var maxBufferedMs: Long = start?.bufferedMs ?: 0L

        /** [stepMs] passed, the link has loaded [progress] (null: unknown); true when it has had its time. */
        fun advance(stepMs: Long, progress: Progress?, requestFailed: Boolean): Boolean {
            elapsedMs += stepMs
            if (progress != null) {
                val base = progressBytes
                if (base == null) {
                    progressBytes = progress.bytes
                } else if (progress.bytes - base >= SportsLiveStreamThresholds.STARTUP_PROGRESS_MIN_BYTES) {
                    progressBytes = progress.bytes
                    lastProgressAtMs = elapsedMs
                }
                if (progress.bufferedMs > maxBufferedMs) {
                    maxBufferedMs = progress.bufferedMs
                    lastProgressAtMs = elapsedMs
                }
            }
            return isExpired(requestFailed)
        }

        fun isExpired(requestFailed: Boolean): Boolean =
            elapsedMs >= hardCapMs() ||
                (elapsedMs >= limitMs(requestFailed) && elapsedMs - lastProgressAtMs >= noProgressLimitMs(requestFailed))
    }

    /**
     * Suspends until the link loading now has had its time ([Watch]), then returns; the caller checks
     * it still has no first frame and moves on. [progress] is what the link has loaded so far (null:
     * unknown, so only the time counts). Cancelling the caller's job cancels the wait.
     */
    suspend fun await(progress: () -> Progress? = { null }, requestFailed: () -> Boolean) {
        val watch = Watch(progress())
        do {
            delay(CHECK_EVERY_MS)
        } while (!watch.advance(CHECK_EVERY_MS, progress(), requestFailed()))
    }
}

/** What a sports link's playback errors mean for the failover (#138). */
object SportsLiveLinkErrors {
    /**
     * Whether [error] (or any cause) says the link's host does not exist: an unknown host, a DNS
     * failure. Such a link is dead: it is never retried, the next link is tried at once.
     */
    fun isDeadHost(error: Throwable?): Boolean {
        var cause = error
        var depth = 0
        while (cause != null && depth++ < 16) {
            if (cause is UnknownHostException) return true
            cause = cause.cause
        }
        return false
    }
}

/**
 * The error text a sports viewer sees (#138): never a url or a host name. A message that holds one
 * (a raw exception) becomes [fallback].
 */
object SportsLiveViewerText {
    private val URL = Regex("""[a-z][a-z0-9+.-]*://""", RegexOption.IGNORE_CASE)
    private val HOST = Regex(
        """\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,24}\b(?::\d{1,5})?""",
        RegexOption.IGNORE_CASE
    )
    private val IP = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b""")

    /** Exception class names (java.net.UnknownHostException) look like host names; they are not. */
    private val CLASS_NAME = Regex("""\b(?:[a-z][a-z0-9_]*\.)+[A-Z][A-Za-z0-9_$]*\b""")

    fun forViewer(message: String?, fallback: String): String {
        val text = message?.trim().orEmpty()
        if (text.isEmpty()) return fallback
        val withoutClasses = CLASS_NAME.replace(text, "")
        return if (URL.containsMatchIn(text) || HOST.containsMatchIn(withoutClasses) || IP.containsMatchIn(text)) {
            fallback
        } else {
            text
        }
    }
}
