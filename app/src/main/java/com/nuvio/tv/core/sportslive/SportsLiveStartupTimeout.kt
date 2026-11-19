package com.nuvio.tv.core.sportslive

import java.net.UnknownHostException
import kotlinx.coroutines.delay

/**
 * The first-frame timeout of a sports link (#138), for the full player (watch and zap), each dual
 * pane and the mini player: no first frame within [SportsLiveStreamThresholds.NO_FIRST_FRAME_MS] of
 * the link starting to load means the next link is tried, or within
 * [SportsLiveStreamThresholds.NO_FIRST_FRAME_AFTER_FAILED_REQUEST_MS] once one of its manifest or
 * segment requests has failed. It is a step of the recovery ladder
 * ([SportsLiveStreamHealth.Problem.NO_START]), so it counts toward the game's recovery budget.
 */
object SportsLiveStartupTimeout {
    /** How often [await] looks at whether a request failed meanwhile. */
    internal const val CHECK_EVERY_MS = 500L

    /** How long a link may take to show its first frame, given whether a request of it already [requestFailed]. */
    fun limitMs(requestFailed: Boolean): Long =
        if (requestFailed) {
            SportsLiveStreamThresholds.NO_FIRST_FRAME_AFTER_FAILED_REQUEST_MS
        } else {
            SportsLiveStreamThresholds.NO_FIRST_FRAME_MS
        }

    fun isExpired(elapsedMs: Long, requestFailed: Boolean): Boolean = elapsedMs >= limitMs(requestFailed)

    /**
     * Suspends until the link loading now has had its time ([limitMs]), then returns; the caller
     * checks it still has no first frame and moves on. Cancelling the caller's job cancels the wait.
     */
    suspend fun await(requestFailed: () -> Boolean) {
        var elapsed = 0L
        while (!isExpired(elapsed, requestFailed())) {
            val step = minOf(CHECK_EVERY_MS, limitMs(requestFailed()) - elapsed).coerceAtLeast(1L)
            delay(step)
            elapsed += step
        }
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
