package com.nuvio.tv.core.sportslive

/**
 * How long each Sports & Live content id has actually been playing in the full player this process
 * (#58: a catch-up replay played 2+ min counts as watched, wherever it was started). The player reports
 * play / pause ([onPlaying]) for sports playback only; [now] is a monotonic clock. In memory only.
 */
class SportsLivePlayedTime(private val now: () -> Long) {
    private val playedMs = HashMap<String, Long>()
    private val playingSince = HashMap<String, Long>()

    @Synchronized
    fun onPlaying(contentId: String?, playing: Boolean) {
        if (contentId.isNullOrEmpty()) return
        val started = playingSince.remove(contentId)
        if (started != null) playedMs[contentId] = (playedMs[contentId] ?: 0L) + (now() - started).coerceAtLeast(0L)
        if (playing) playingSince[contentId] = now()
        if (playedMs.size > MAX_ENTRIES) playedMs.keys.firstOrNull()?.let(playedMs::remove)
    }

    /** [contentId]'s playing time so far, the running stretch included. */
    @Synchronized
    fun playedMs(contentId: String): Long =
        (playedMs[contentId] ?: 0L) + (playingSince[contentId]?.let { (now() - it).coerceAtLeast(0L) } ?: 0L)

    /** Forgets [contentId] (counted already). */
    @Synchronized
    fun forget(contentId: String) {
        playedMs.remove(contentId)
        playingSince.remove(contentId)
    }

    /** Forgets everything (a profile switch: what was played belongs to the profile before). */
    @Synchronized
    fun clear() {
        playedMs.clear()
        playingSince.clear()
    }

    companion object {
        private const val MAX_ENTRIES = 200

        /** The app's one log, fed by the full player. */
        val shared = SportsLivePlayedTime(android.os.SystemClock::elapsedRealtime)
    }
}
