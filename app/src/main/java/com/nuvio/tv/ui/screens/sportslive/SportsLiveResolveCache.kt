package com.nuvio.tv.ui.screens.sportslive

import androidx.annotation.VisibleForTesting
import com.nuvio.tv.domain.model.Stream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A short-lived cache of a Sports & Live game/channel's resolved best stream, keyed by
 * [com.nuvio.tv.domain.model.SportsLiveItem.streamKey] (per content). Shared by the section, zap,
 * hover preview and the ended-probe, so re-visiting a card while flipping — and "I'll come back to
 * that one" — are free rather than re-resolving against a metered/free-tier provider's quota.
 *
 * An entry lives [TTL_MS]; a stale one is dropped on read. On a playback failure the caller
 * [invalidate]s the key so the next resolve refreshes an expired link.
 */
@Singleton
class SportsLiveResolveCache @Inject constructor() {
    companion object {
        const val TTL_MS = 5 * 60_000L
    }

    @VisibleForTesting
    internal var nowMs: () -> Long = { System.currentTimeMillis() }

    private class Entry(val stream: Stream, val atMs: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** The cached stream for [key] when still within [TTL_MS]; null (and evicted) when stale or absent. */
    fun get(key: String): Stream? {
        val entry = entries[key] ?: return null
        if (nowMs() - entry.atMs < TTL_MS) return entry.stream
        entries.remove(key, entry)
        return null
    }

    /**
     * The cached stream for [key] with when it was resolved, while within [TTL_MS] (#60 phase 2: a link
     * check reads only such a fresh resolve); null when stale or absent.
     */
    fun getFresh(key: String): Pair<Stream, Long>? {
        val entry = entries[key] ?: return null
        return if (nowMs() - entry.atMs < TTL_MS) entry.stream to entry.atMs else null
    }

    /** Whether a non-stale resolve is cached for [key] (the ended-probe uses it to skip a request). */
    fun isFresh(key: String): Boolean = get(key) != null

    fun put(key: String, stream: Stream) {
        entries[key] = Entry(stream, nowMs())
    }

    fun invalidate(key: String) {
        entries.remove(key)
    }

    /**
     * Drops every entry for content id [contentId] (streamKey is "addonId|id"), used by the full
     * player — which knows only the content id — to evict a stream that just failed so the next
     * play/zap re-resolves instead of serving the dead URL.
     */
    fun invalidateByContentId(contentId: String) {
        entries.keys
            .filter { it == contentId || it.endsWith("|$contentId") }
            .forEach { entries.remove(it) }
    }

    @VisibleForTesting
    internal fun clear() = entries.clear()
}
