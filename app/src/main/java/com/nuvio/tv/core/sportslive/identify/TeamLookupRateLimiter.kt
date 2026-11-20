package com.nuvio.tv.core.sportslive.identify

/**
 * How often team lookups may go out (#168), across both sources: one request every [spacingMs], at most
 * [dailyCap] a day (from the day's first request), and a pause of [pauseMs] after [failuresToPause]
 * failures in a row or one "too many requests" answer.
 */
class TeamLookupRateLimiter(
    private val now: () -> Long,
    val spacingMs: Long = SPACING_MS,
    private val dailyCap: Int = DAILY_CAP,
    private val failuresToPause: Int = FAILURES_TO_PAUSE,
    private val pauseMs: Long = PAUSE_MS
) {
    private var lastRequestAt: Long? = null
    private var dayStart: Long? = null
    private var dayCount = 0
    private var failures = 0
    private var pausedUntil: Long? = null

    /** How long until the next request may go; 0: now. */
    @Synchronized
    fun waitMs(): Long {
        val at = now()
        var wait = 0L
        pausedUntil?.let { wait = maxOf(wait, it - at) }
        lastRequestAt?.let { wait = maxOf(wait, it + spacingMs - at) }
        val start = dayStart
        if (start != null && dayCount >= dailyCap && at - start < DAY_MS) wait = maxOf(wait, start + DAY_MS - at)
        return wait.coerceAtLeast(0L)
    }

    /** A request is going out now. */
    @Synchronized
    fun onRequest() {
        val at = now()
        val start = dayStart
        if (start == null || at - start !in 0 until DAY_MS) {
            dayStart = at
            dayCount = 0
        }
        dayCount++
        lastRequestAt = at
    }

    @Synchronized
    fun onSuccess() {
        failures = 0
    }

    @Synchronized
    fun onFailure(rateLimited: Boolean) {
        failures++
        if (rateLimited || failures >= failuresToPause) {
            pausedUntil = now() + pauseMs
            failures = 0
        }
    }

    companion object {
        const val SPACING_MS = 2_000L
        const val DAILY_CAP = 200
        const val FAILURES_TO_PAUSE = 3
        const val PAUSE_MS = 30 * 60_000L
        private const val DAY_MS = 24 * 60 * 60_000L
    }
}
