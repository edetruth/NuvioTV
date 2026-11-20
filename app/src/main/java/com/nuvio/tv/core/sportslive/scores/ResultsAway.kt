package com.nuvio.tv.core.sportslive.scores

/**
 * Results' time away (live.20): from leaving Results (another tab, the player, another screen) to coming
 * back, less the time a Game page opened from Results was on screen. The player or a Team page opened from
 * that Game page counts. Kept for the app process, like [ResultsRepository.chosenDay].
 */
class ResultsAway {
    /** What [back] found: when Results was left, and the time away that counts. */
    data class Return(val leftAtMs: Long, val awayMs: Long)

    private var leftAt: Long? = null
    private var excludedMs = 0L
    private var gamePageSince: Long? = null

    /** Results left at [nowMs]. A Game page already on screen (it can start first) is excluded from here on. */
    @Synchronized
    fun leave(nowMs: Long) {
        leftAt = nowMs
        excludedMs = 0L
    }

    /** A Game page opened from Results came on screen ([shown]) or left it, at [nowMs]. */
    @Synchronized
    fun gamePageShown(shown: Boolean, nowMs: Long) {
        if (!shown) closeGamePage(nowMs) else if (gamePageSince == null) gamePageSince = nowMs
    }

    /** Back on Results at [nowMs]; null when it was never left. */
    @Synchronized
    fun back(nowMs: Long): Return? {
        closeGamePage(nowMs)
        val left = leftAt ?: return null
        leftAt = null
        return Return(left, (nowMs - left - excludedMs).coerceAtLeast(0L))
    }

    private fun closeGamePage(nowMs: Long) {
        val since = gamePageSince ?: return
        gamePageSince = null
        val left = leftAt ?: return
        excludedMs += (nowMs - maxOf(since, left)).coerceAtLeast(0L)
    }
}
