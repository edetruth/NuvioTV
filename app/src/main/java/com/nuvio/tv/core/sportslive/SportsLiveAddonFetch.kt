package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How one addon's streams are fetched for a Sports & Live game (#138, #158):
 * - each addon gets [SportsLiveStreamThresholds.ADDON_LOOKUP_TIMEOUT_MS] for its whole answer; a lookup
 *   does not wait that long ([SportsLiveStreamThresholds.LOOKUP_SOFT_DEADLINE_MS]), but a slow answer
 *   still lands in the shared search for the Sources list, the recovery ladder and the next lookup;
 * - the meta fallback (an addon whose stream answer is empty may list inline streams in its meta) is
 *   asked at the same time as the streams, not after them, and only from addons not known to answer
 *   it with nothing ([noInlineStreams]).
 *
 * The stream repository uses it for the ids a sports lookup [markSports]; every other search is unchanged.
 */
class SportsLiveAddonFetch(private val timeoutMs: Long = SportsLiveStreamThresholds.ADDON_LOOKUP_TIMEOUT_MS) {
    companion object {
        private const val MAX_IDS = 500

        /** The process-wide one the app uses. */
        val shared = SportsLiveAddonFetch()
    }

    private val sportsIds = LinkedHashSet<String>()

    /** Addons (by key) whose meta fallback came back without streams last time: it is not asked again. */
    private val noInlineStreams = HashSet<String>()

    /** Each addon's latest lookup times (#147), by [SportsLiveSourceCooldown.keyOf] its key, the latest last. */
    private val lookupMs = HashMap<String, ArrayDeque<Long>>()

    /** The addon at [addonKey] answered a sports lookup in [ms] (a timeout counts its whole limit). */
    fun noteLookupMs(addonKey: String, ms: Long) {
        if (addonKey.isBlank()) return
        synchronized(lookupMs) {
            val times = lookupMs.getOrPut(SportsLiveSourceCooldown.keyOf(addonKey)) { ArrayDeque() }
            times.addLast(ms)
            while (times.size > SportsLiveStreamThresholds.SLOW_ADDON_SAMPLES) times.removeFirst()
        }
    }

    /**
     * Keys ([SportsLiveSourceCooldown.keyOf] the base url) of the addons whose median lookup over their last
     * [SportsLiveStreamThresholds.SLOW_ADDON_SAMPLES] took longer than [SportsLiveStreamThresholds.SLOW_ADDON_MS]
     * (#158): the in-player check-ahead skips them (#147).
     */
    fun slowAddonKeys(): Set<String> = synchronized(lookupMs) {
        lookupMs.filterValues { times -> times.sorted()[times.size / 2] > SportsLiveStreamThresholds.SLOW_ADDON_MS }.keys.toSet()
    }

    /** [videoId] is a Sports & Live game's: its stream searches use [fetch]. */
    fun markSports(videoId: String) = synchronized(sportsIds) {
        sportsIds.remove(videoId)
        sportsIds.add(videoId)
        while (sportsIds.size > MAX_IDS) sportsIds.remove(sportsIds.first())
    }

    fun isSports(videoId: String): Boolean = synchronized(sportsIds) { videoId in sportsIds }

    /**
     * Addon [addonKey]'s streams: [streams]' answer, or when that is an empty success, [inlineFromMeta]'s
     * streams (asked at the same time). Null when the addon did not answer within the timeout.
     */
    suspend fun fetch(
        addonKey: String,
        streams: suspend () -> NetworkResult<List<Stream>>,
        inlineFromMeta: suspend () -> List<Stream>,
        /** The addon's name, for debug timing lines only. */
        label: String = ""
    ): NetworkResult<List<Stream>>? {
        val limit = SportsLiveDebugTiming.override(SportsLiveDebugTiming.LOOKUP_TIMEOUT)
            ?.let { if (it <= 0) Long.MAX_VALUE else it } ?: timeoutMs
        val startedAt = System.currentTimeMillis()
        val result = withTimeoutOrNull(limit) { fetchNow(addonKey, streams, inlineFromMeta) }
        noteLookupMs(addonKey, System.currentTimeMillis() - startedAt)
        SportsLiveDebugTiming.log {
            val count = (result as? NetworkResult.Success)?.data?.size
            "lookup addon=${SportsLiveDebugTiming.label(label)} ms=${System.currentTimeMillis() - startedAt} " +
                "limit=$limit outcome=${if (result == null) "timeout" else if (count != null) "ok" else "error"} links=${count ?: 0}"
        }
        return result
    }

    private suspend fun fetchNow(
        addonKey: String,
        streams: suspend () -> NetworkResult<List<Stream>>,
        inlineFromMeta: suspend () -> List<Stream>
    ): NetworkResult<List<Stream>> =
        coroutineScope {
            val askMeta = synchronized(noInlineStreams) { addonKey !in noInlineStreams }
            val meta: Deferred<List<Stream>>? = if (askMeta) {
                async {
                    try {
                        inlineFromMeta()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            } else {
                null
            }
            val answer = streams()
            if (answer !is NetworkResult.Success || answer.data.isNotEmpty() || meta == null) {
                meta?.cancel()
                return@coroutineScope answer
            }
            val inline = meta.await()
            synchronized(noInlineStreams) {
                if (inline.isEmpty()) noInlineStreams += addonKey else noInlineStreams -= addonKey
            }
            if (inline.isEmpty()) answer else NetworkResult.Success(inline)
        }
}
