package com.nuvio.tv.data.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.sportslive.SportsLiveLiveCheck
import com.nuvio.tv.core.sportslive.SportsLiveNoticeEntries
import com.nuvio.tv.core.sportslive.SportsLiveSourceCooldown
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.ui.screens.sportslive.SportsLiveResolveCache
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The kickoff alerts' link probe (#162): a fresh stream search for the game (and its merged sources), one
 * request per addon as any search, then one manifest probe per addon ([SportsLiveLiveCheck]). A live
 * link found goes to the resolve cache, so Watch plays it first. Addons cooling down after a rate limit
 * are left out. Null when the search itself failed (no verdict).
 */
@Singleton
class SportsLiveKickoffProbe @Inject constructor(
    private val streamRepository: StreamRepository,
    private val resolveCache: SportsLiveResolveCache
) {
    suspend fun probe(item: SportsLiveItem): SportsLiveLiveCheck.Outcome? {
        val streams = streamsOf(item) ?: return null
        val outcome = liveCheck.firstLive(streams)
        outcome.live?.let { resolveCache.put(item.streamKey, it) }
        return outcome
    }

    private suspend fun streamsOf(item: SportsLiveItem): List<Stream>? {
        var any = false
        val streams = (listOf(item) + item.alternates).flatMap { source ->
            val found = search(source) ?: return@flatMap emptyList()
            any = true
            found
        }
        return if (any) streams else null
    }

    private suspend fun search(source: SportsLiveItem): List<Stream>? {
        val cooling = SportsLiveSourceCooldown.shared.coolingBaseUrls()
        var last: NetworkResult<List<AddonStreams>>? = null
        try {
            withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                val flow = if (cooling.isEmpty()) {
                    streamRepository.getStreamsFromAllAddons(source.apiType, source.id, forceRefresh = true)
                } else {
                    streamRepository.getStreamsFromAllAddonsExcept(source.apiType, source.id, cooling, true)
                }
                flow.collect { last = it }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        val data = (last as? NetworkResult.Success)?.data ?: return null
        return data.flatMap { it.streams }.filterNot { SportsLiveNoticeEntries.isNotice(it) || SportsLiveZapResolver.isNonStream(it) }
    }

    companion object {
        private const val SEARCH_TIMEOUT_MS = 10_000L

        /** The live check every sports player shares (the full player's waiting card too). */
        val liveCheck: SportsLiveLiveCheck by lazy { SportsLiveLiveCheck(SportsLiveManifestFetcher()::fetch) }
    }
}
