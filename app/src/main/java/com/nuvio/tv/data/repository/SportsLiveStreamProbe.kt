package com.nuvio.tv.data.repository

import androidx.annotation.VisibleForTesting
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.repository.StreamRepository
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
 * often posted after it. An ended verdict expires after [ENDED_TTL_MS], so the item shows again and
 * the next focus re-checks it.
 */
@Singleton
class SportsLiveStreamProbe @Inject constructor(
    private val streamRepository: StreamRepository
) {
    companion object {
        const val CONCURRENCY = 3
        const val PROBE_TIMEOUT_MS = 8_000L
        const val ENDED_TTL_MS = 180_000L
        const val KICKOFF_GRACE_MS = 30 * 60 * 1000L
        const val REPROBE_AFTER_MS = 120_000L

        private val rateLimitWords = listOf("rate limit", "too many")
    }

    @VisibleForTesting
    internal var nowMs: () -> Long = { System.currentTimeMillis() }

    @VisibleForTesting
    internal var dispatcher: CoroutineDispatcher = Dispatchers.IO

    private data class Entry(val ended: Boolean, val probedAtMs: Long)

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

    /** Records the outcome of a stream fetch made elsewhere (zap, merged cards) for [item]. */
    fun recordResult(item: SportsLiveItem, result: NetworkResult<List<AddonStreams>>) {
        record(item, verdictOf(result))
    }

    /** [ended] null means inconclusive: the previous verdict stands, but the entry is stamped. */
    private fun record(item: SportsLiveItem, ended: Boolean?) {
        val key = item.streamKey
        val now = nowMs()
        val start = item.startMs?.takeIf { item.startHasTimeOfDay }
        val inGrace = start != null && now - start < KICKOFF_GRACE_MS
        val verdict = if (ended == true && inGrace) false else ended
        // Atomic so a probe and a recordResult racing on one key cannot leave the key in [ended]
        // while its entry says alive (or the reverse).
        val entry = synchronized(lock) {
            Entry(verdict ?: entries[key]?.ended ?: false, now).also { entry ->
                entries[key] = entry
                _ended.update { if (entry.ended) it + key else it - key }
            }
        }
        if (entry.ended) {
            scope.launch {
                delay(ENDED_TTL_MS)
                // Expire only if no newer result replaced this one.
                synchronized(lock) {
                    if (entries.remove(key, entry)) _ended.update { it - key }
                }
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
                streams.any(::isRateLimitNotice) -> null
                // A torrent may still be resolving through debrid; it is never proof of an end.
                streams.any { it.getEffectiveInfoHash() != null } -> false
                else -> streams.none(StreamAutoPlaySelector::isPlayable)
            }
        }
        is NetworkResult.Error -> if (result.code == NetworkResult.STREAMS_NOT_FOUND_CODE) true else null
        NetworkResult.Loading -> null
    }

    /** Addons such as highfly answer a rate limit with an informational "stream". */
    private fun isRateLimitNotice(stream: Stream): Boolean {
        val text = listOfNotNull(stream.name, stream.title, stream.description).joinToString(" ").lowercase()
        return rateLimitWords.any { it in text }
    }
}
