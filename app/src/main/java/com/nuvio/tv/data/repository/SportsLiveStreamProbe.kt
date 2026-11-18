package com.nuvio.tv.data.repository

import androidx.annotation.VisibleForTesting
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.core.sportslive.SportsLiveNoticeEntries
import com.nuvio.tv.core.sportslive.SportsLiveSourceCooldown
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.ui.screens.sportslive.SportsLiveResolveCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Some addons keep a game marked live after it ends; its streams are gone by then. Probing every
 * live item trips addon rate limits, so an item is probed only on demand (when focused), and stream
 * fetches made elsewhere can report their result through [recordResult].
 * Only a finished search with nothing playable marks an item ended; rate limits, errors and
 * timeouts never do, nor does anything within [KICKOFF_GRACE_MS] of a known kickoff, since links are
 * often posted after it. An ended verdict is sticky: it is never dropped on a timer (a quick tab
 * round trip must not bring the game back). Instead, [ENDED_TTL_MS] after it the item is re-probed
 * once in the background, at most [MAX_ENDED_RECHECKS] times in a row (see [shouldRecheckEnded]);
 * only a probe or fetch that finds links shows it again. After the last re-check the verdict is
 * dropped, with no request, [ENDED_FINAL_TTL_MS] later (links posted late still surface the same
 * day), and [clearEnded] (a manual refresh) drops every verdict at once.
 */
@Singleton
class SportsLiveStreamProbe @Inject constructor(
    private val streamRepository: StreamRepository,
    private val resolveCache: SportsLiveResolveCache
) {
    companion object {
        const val CONCURRENCY = 3
        const val PROBE_TIMEOUT_MS = 8_000L
        const val ENDED_TTL_MS = 180_000L
        const val KICKOFF_GRACE_MS = 30 * 60 * 1000L
        const val REPROBE_AFTER_MS = 120_000L

        /** After the last background re-check, how long a still-ended verdict lasts before it is dropped. */
        const val ENDED_FINAL_TTL_MS = 30 * 60_000L

        /** How long links found for an item count as recent ([hasRecentLinks]). */
        const val RECENT_LINKS_MS = 15 * 60_000L

        /** Background re-checks of a game still found ended, before its verdict just times out. */
        const val MAX_ENDED_RECHECKS = 2

        /** Whether an ended verdict reached after [recheckCount] background re-checks gets another one. */
        fun shouldRecheckEnded(recheckCount: Int): Boolean = recheckCount < MAX_ENDED_RECHECKS
    }

    @VisibleForTesting
    internal var nowMs: () -> Long = { System.currentTimeMillis() }

    /** Sources cooling down after a rate-limit notice are not probed (C1). */
    @VisibleForTesting
    internal var cooldown: SportsLiveSourceCooldown = SportsLiveSourceCooldown.shared

    @VisibleForTesting
    internal var dispatcher: CoroutineDispatcher = Dispatchers.IO

    /**
     * [rechecks]: background re-checks in a row that found the item still ended. [linksAtMs]: when a
     * result last found links (null: never, as far as this entry knows).
     */
    private data class Entry(
        val ended: Boolean,
        val probedAtMs: Long,
        val rechecks: Int = 0,
        val linksAtMs: Long? = null
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val permits = Semaphore(CONCURRENCY)
    private val lock = Any()
    private val scope by lazy { CoroutineScope(SupervisorJob() + dispatcher) }

    private val _ended = MutableStateFlow<Set<String>>(emptySet())

    /** Keys ([SportsLiveItem.streamKey]) of items whose latest conclusive result found no playable stream. */
    val ended: StateFlow<Set<String>> = _ended.asStateFlow()

    /** Probes [item] in the background when its result is missing or older than [REPROBE_AFTER_MS]. */
    fun reprobeIfStale(item: SportsLiveItem) {
        val key = item.streamKey
        // A fresh resolve (from preview/zap/section) already proved the game alive; don't spend a
        // request probing on mere focus.
        if (resolveCache.isFresh(key)) return
        // A source that asked to wait is left alone until its cool-down ends (C1).
        if (cooldown.isCoolingDown(item.addonName)) return
        val entry = entries[key]
        if (entry != null && nowMs() - entry.probedAtMs < REPROBE_AFTER_MS) return
        if (!inFlight.add(key)) return
        // A re-probe bypasses the stream session cache, which would otherwise replay the old answer.
        val forceRefresh = entry != null
        scope.launch {
            try {
                permits.withPermit { record(item, search(item, forceRefresh)) }
            } finally {
                inFlight.remove(key)
            }
        }
    }

    /** True when a result found links for [item] (or a fresh resolve holds one) within [RECENT_LINKS_MS]. */
    fun hasRecentLinks(item: SportsLiveItem): Boolean {
        val key = item.streamKey
        if (resolveCache.isFresh(key)) return true
        val linksAt = entries[key]?.linksAtMs ?: return false
        return nowMs() - linksAt < RECENT_LINKS_MS
    }

    /** Drops every ended verdict (a manual refresh); the next probe of each bypasses the stream cache. */
    fun clearEnded() {
        synchronized(lock) {
            entries.replaceAll { _, entry -> if (entry.ended) Entry(ended = false, probedAtMs = 0L) else entry }
            _ended.value = emptySet()
        }
    }

    /** Records the outcome of a stream fetch made elsewhere (zap, merged cards) for [item]. */
    fun recordResult(item: SportsLiveItem, result: NetworkResult<List<AddonStreams>>) {
        record(item, verdictOf(result))
    }

    /**
     * [ended] null means inconclusive: the previous verdict stands, but the entry is stamped.
     * [rechecks] counts the background re-checks behind this result (0 for any other probe or fetch).
     */
    private fun record(item: SportsLiveItem, ended: Boolean?, rechecks: Int = 0) {
        val key = item.streamKey
        val now = nowMs()
        val start = item.startMs?.takeIf { item.startHasTimeOfDay }
        val inGrace = start != null && now - start < KICKOFF_GRACE_MS
        val verdict = if (ended == true && inGrace) false else ended
        // Atomic so a probe and a recordResult racing on one key cannot leave the key in [ended]
        // while its entry says alive (or the reverse).
        val entry = synchronized(lock) {
            val previous = entries[key]
            val linksAt = if (ended == false) now else previous?.linksAtMs
            Entry(verdict ?: previous?.ended ?: false, now, rechecks, linksAt).also { entry ->
                entries[key] = entry
                _ended.update { if (entry.ended) it + key else it - key }
            }
        }
        if (!entry.ended) return
        if (!shouldRecheckEnded(entry.rechecks)) {
            scope.launch {
                delay(ENDED_FINAL_TTL_MS)
                // No request: the verdict just lapses, unless a newer result replaced it. A stale
                // alive entry (not none) stays so the next probe bypasses the stream cache.
                synchronized(lock) {
                    if (entries[key] === entry) {
                        entries[key] = Entry(ended = false, probedAtMs = 0L, linksAtMs = entry.linksAtMs)
                        _ended.update { it - key }
                    }
                }
            }
            return
        }
        scope.launch {
            delay(ENDED_TTL_MS)
            // Only if no newer result replaced this one, and no probe of it is running; checked
            // and marked together so a racing probe cannot slip in between.
            synchronized(lock) {
                if (entries[key] !== entry || !inFlight.add(key)) return@launch
            }
            try {
                // Bypasses the stream session cache, which would replay the old empty answer.
                permits.withPermit { record(item, search(item, forceRefresh = true), entry.rechecks + 1) }
            } finally {
                inFlight.remove(key)
            }
        }
    }

    /** The verdict of a full search; null on a timeout or an exception. */
    private suspend fun search(item: SportsLiveItem, forceRefresh: Boolean): Boolean? {
        var last: NetworkResult<List<AddonStreams>>? = null
        val finished = try {
            withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                streamRepository.getStreamsFromAllAddons(item.apiType, item.id, forceRefresh = forceRefresh).collect { last = it }
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return if (finished == null) null else last?.let(::verdictOf)
    }

    /** True = ended, false = alive, null = inconclusive. */
    private fun verdictOf(result: NetworkResult<List<AddonStreams>>): Boolean? = when (result) {
        is NetworkResult.Success -> {
            val streams = result.data.flatMap { it.streams }
            when {
                streams.any { SportsLiveNoticeEntries.rateLimitMinutes(it) != null } -> null
                // A torrent may still be resolving through debrid; it is never proof of an end.
                streams.any { it.getEffectiveInfoHash() != null } -> false
                // An informational entry is never a playable stream (C1).
                else -> streams.filterNot(SportsLiveNoticeEntries::isNotice).none(StreamAutoPlaySelector::isPlayable)
            }
        }
        is NetworkResult.Error -> if (result.code == NetworkResult.STREAMS_NOT_FOUND_CODE) true else null
        NetworkResult.Loading -> null
    }
}
