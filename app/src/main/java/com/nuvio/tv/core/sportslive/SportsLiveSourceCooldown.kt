package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.AddonStreams
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Sources that answered with a rate-limit notice (QA C1), cooling down for the minutes the notice
 * asks for ([SportsLiveNoticeEntries.rateLimitMinutes]). In memory for this process only. While a
 * source cools down, hover previews and the ended-probe leave it alone, and auto-play and zap skip
 * it unless a cached resolve or a known-good link exists. A source is keyed by the addon name the
 * app shows (case and spacing ignored).
 *
 * Each cool-down is announced once ([announcements]): the first screen collecting it shows the toast.
 */
class SportsLiveSourceCooldown(internal val nowMs: () -> Long = { System.currentTimeMillis() }) {
    companion object {
        /** The process-wide cool-downs the app uses. */
        val shared = SportsLiveSourceCooldown()

        private fun keyOf(source: String): String = source.trim().lowercase()
    }

    /** [source] (its display name) is rate-limited; try again in [minutes]. */
    data class Announcement(val source: String, val minutes: Int)

    private val until = HashMap<String, Long>()

    private val _announcements = Channel<Announcement>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** One announcement per cool-down started, delivered to a single collector. */
    val announcements: Flow<Announcement> = _announcements.receiveAsFlow()

    /** [source] asked to wait [minutes]. A cool-down already running is only ever lengthened. */
    fun note(source: String, minutes: Int) {
        if (source.isBlank()) return
        val key = keyOf(source)
        val now = nowMs()
        val started = synchronized(this) {
            val current = until[key]?.takeIf { it > now }
            until[key] = maxOf(current ?: 0L, now + minutes * 60_000L)
            current == null
        }
        if (started) _announcements.trySend(Announcement(source.trim(), minutes))
    }

    /** Notes every rate-limit notice in [results], per addon (the longest wait it names). */
    fun noteNotices(results: List<AddonStreams>) {
        results.forEach { addon ->
            addon.streams.mapNotNull(SportsLiveNoticeEntries::rateLimitMinutes).maxOrNull()
                ?.let { note(addon.addonName, it) }
        }
    }

    fun isCoolingDown(source: String?): Boolean {
        if (source.isNullOrBlank()) return false
        return synchronized(this) { (until[keyOf(source)] ?: 0L) > nowMs() }
    }

    /** Whole minutes left of [source]'s cool-down, rounded up; 0 when it is not cooling down. */
    fun remainingMinutes(source: String): Int {
        val left = synchronized(this) { (until[keyOf(source)] ?: 0L) - nowMs() }
        return if (left <= 0) 0 else ((left + 59_999L) / 60_000L).toInt()
    }

    internal fun clear() {
        synchronized(this) { until.clear() }
        while (_announcements.tryReceive().isSuccess) Unit
    }
}
