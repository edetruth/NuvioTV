package com.nuvio.tv.data.repository

import androidx.annotation.VisibleForTesting
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.repository.StreamRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
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
 * Some addons keep a game marked live after it ends; its streams are gone by then. This probes the
 * streams of live items and marks an item ended when the search finishes with no playable stream.
 * A timeout or a transport error never marks an item ended.
 */
@Singleton
class SportsLiveStreamProbe @Inject constructor(
    private val streamRepository: StreamRepository
) {
    companion object {
        const val CONCURRENCY = 3
        const val PROBE_TIMEOUT_MS = 8_000L
        const val CACHE_TTL_MS = 600_000L
        const val REPROBE_AFTER_MS = 120_000L
    }

    @VisibleForTesting
    internal var nowMs: () -> Long = { System.currentTimeMillis() }

    @VisibleForTesting
    internal var dispatcher: CoroutineDispatcher = Dispatchers.IO

    private data class Entry(val ended: Boolean, val probedAtMs: Long)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val permits = Semaphore(CONCURRENCY)
    private val scope by lazy { CoroutineScope(SupervisorJob() + dispatcher) }

    private val _ended = MutableStateFlow<Set<String>>(emptySet())

    /** Keys ([SportsLiveItem.streamKey]) of items whose latest conclusive probe found no playable stream. */
    val ended: StateFlow<Set<String>> = _ended.asStateFlow()

    /**
     * Probes [items] whose result is missing or older than [CACHE_TTL_MS]; returns when they finish.
     * An ended item stays ended until its re-probe finds streams. Expired entries of items no
     * longer listed are dropped.
     */
    suspend fun probe(items: List<SportsLiveItem>) {
        val listed = items.distinctBy { it.streamKey }
        val listedKeys = listed.mapTo(HashSet()) { it.streamKey }
        val now = nowMs()
        val dropped = entries.filter { (key, entry) -> key !in listedKeys && now - entry.probedAtMs >= CACHE_TTL_MS }.keys
        if (dropped.isNotEmpty()) {
            dropped.forEach { entries.remove(it) }
            _ended.update { it - dropped }
        }
        coroutineScope {
            listed.filter { isStale(it.streamKey, CACHE_TTL_MS) }.forEach { launch { probeOne(it) } }
        }
    }

    /** Re-probes [item] in the background when its result is missing or older than [REPROBE_AFTER_MS]. */
    fun reprobeIfStale(item: SportsLiveItem) {
        if (isStale(item.streamKey, REPROBE_AFTER_MS)) scope.launch { probeOne(item) }
    }

    private fun isStale(key: String, maxAgeMs: Long): Boolean {
        val entry = entries[key] ?: return true
        return nowMs() - entry.probedAtMs >= maxAgeMs
    }

    private suspend fun probeOne(item: SportsLiveItem) {
        val key = item.streamKey
        if (!inFlight.add(key)) return
        try {
            val ended = permits.withPermit { searchEnded(item) }
            // An inconclusive probe keeps the previous verdict, so it is not retried on every load.
            val verdict = ended ?: entries[key]?.ended ?: false
            entries[key] = Entry(verdict, nowMs())
            _ended.update { if (verdict) it + key else it - key }
        } finally {
            inFlight.remove(key)
        }
    }

    /** True when the search finished with no playable stream; null on a timeout or a transport error. */
    private suspend fun searchEnded(item: SportsLiveItem): Boolean? {
        var last: NetworkResult<List<AddonStreams>>? = null
        val finished = try {
            withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                streamRepository.getStreamsFromAllAddons(item.apiType, item.id).collect { last = it }
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (finished == null) return null
        return when (val result = last) {
            is NetworkResult.Success ->
                result.data.none { group -> group.streams.any(StreamAutoPlaySelector::isPlayable) }
            is NetworkResult.Error -> if (result.code == NetworkResult.STREAMS_NOT_FOUND_CODE) true else null
            NetworkResult.Loading, null -> null
        }
    }
}
