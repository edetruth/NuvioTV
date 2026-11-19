package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.AddonStreams
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Stream addons that answered with a rate-limit notice (QA C1), cooling down for the minutes the
 * notice asks for ([SportsLiveNoticeEntries.rateLimitMinutes]). An addon is keyed by its transport
 * base url ([AddonStreams.addonBaseUrl]), which is held in memory only and never logged; its display
 * name is kept for the toast. While an addon cools down, Sports & Live searches leave it out of the
 * per-addon fan-out ([coolingBaseUrls]), so every other addon still answers.
 *
 * New cool-downs are announced once ([announcements]): those started close together share one toast,
 * and one that has already ended by then is dropped.
 */
class SportsLiveSourceCooldown(internal val nowMs: () -> Long = { System.currentTimeMillis() }) {
    companion object {
        /** The process-wide cool-downs the app uses. */
        val shared = SportsLiveSourceCooldown()

        /** Cool-downs noted within this time of the first one share its toast. */
        const val COLLAPSE_MS = 500L

        /** [baseUrl] as a key: trimmed, without a trailing slash. */
        fun keyOf(baseUrl: String): String = baseUrl.trim().trimEnd('/')
    }

    /** [sources] (display names) are rate-limited; try again in [minutes] (the longest wait). */
    data class Announcement(val sources: List<String>, val minutes: Int)

    private class Entry(val name: String, val untilMs: Long)

    private val entries = HashMap<String, Entry>()

    /** Cool-downs started and not yet announced, by key. */
    private val pending = LinkedHashSet<String>()

    private val started = Channel<Unit>(Channel.CONFLATED)

    /** One announcement per batch of cool-downs started, delivered to a single collector. */
    val announcements: Flow<Announcement> = started.receiveAsFlow()
        .map {
            delay(COLLAPSE_MS)
            takePending()
        }
        .filter { it.sources.isNotEmpty() }

    /** The addon at [baseUrl], shown as [name], asked to wait [minutes]. A running cool-down is only lengthened. */
    fun note(baseUrl: String, name: String, minutes: Int) {
        if (baseUrl.isBlank()) return
        val key = keyOf(baseUrl)
        val now = nowMs()
        val isNew = synchronized(this) {
            val current = entries[key]?.takeIf { it.untilMs > now }
            entries[key] = Entry(name.trim(), maxOf(current?.untilMs ?: 0L, now + minutes * 60_000L))
            if (current == null) pending += key
            current == null
        }
        if (isNew) started.trySend(Unit)
    }

    /** Notes every rate-limit notice in [results], per addon (the longest wait it names). */
    fun noteNotices(results: List<AddonStreams>) {
        results.forEach { addon ->
            val baseUrl = addon.addonBaseUrl ?: return@forEach
            addon.streams.mapNotNull(SportsLiveNoticeEntries::rateLimitMinutes).maxOrNull()
                ?.let { note(baseUrl, addon.addonName, it) }
        }
    }

    fun isCoolingDown(baseUrl: String?): Boolean {
        if (baseUrl.isNullOrBlank()) return false
        return synchronized(this) { (entries[keyOf(baseUrl)]?.untilMs ?: 0L) > nowMs() }
    }

    /** Base urls of the addons cooling down now, for a search to leave out. */
    fun coolingBaseUrls(): Set<String> = synchronized(this) {
        val now = nowMs()
        entries.values.removeAll { it.untilMs <= now }
        entries.keys.toSet()
    }

    /** Display names of the addons cooling down now, for a recovery to skip their streams (#138). */
    fun coolingNames(): Set<String> = synchronized(this) {
        val now = nowMs()
        entries.values.filter { it.untilMs > now }.mapTo(HashSet()) { it.name }
    }

    /** Whole minutes left of [baseUrl]'s cool-down, rounded up; 0 when it is not cooling down. */
    fun remainingMinutes(baseUrl: String): Int {
        val left = synchronized(this) { (entries[keyOf(baseUrl)]?.untilMs ?: 0L) - nowMs() }
        return if (left <= 0) 0 else ((left + 59_999L) / 60_000L).toInt()
    }

    /** The cool-downs waiting for their toast, without those already over. */
    private fun takePending(): Announcement = synchronized(this) {
        val now = nowMs()
        val live = pending.mapNotNull { key -> entries[key]?.takeIf { it.untilMs > now } }
        pending.clear()
        val minutes = live.maxOfOrNull { ((it.untilMs - now + 59_999L) / 60_000L).toInt() } ?: 0
        Announcement(live.map { it.name }.distinct(), minutes)
    }

    internal fun clear() {
        synchronized(this) {
            entries.clear()
            pending.clear()
        }
        while (started.tryReceive().isSuccess) Unit
    }
}
