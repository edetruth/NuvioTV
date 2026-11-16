package com.nuvio.tv.ui.screens.sportslive

import androidx.annotation.VisibleForTesting
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.core.streams.UnplayableStreamFilter
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.repository.SportsLiveStreamProbe
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.StreamRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Picks the stream a zap plays for a Sports & Live item, the way the player picks the next
 * episode's stream: the user's autoplay settings first, then the first playable stream.
 */
@Singleton
class SportsLiveZapResolver @Inject constructor(
    private val streamRepository: StreamRepository,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val addonRepository: AddonRepository,
    private val streamProbe: SportsLiveStreamProbe
) {
    companion object {
        const val TIMEOUT_MS = 12_000L

        /** After this, a still-running search stops as soon as it holds the stream the user's settings prefer. */
        const val EARLY_PICK_MS = 4_000L
    }

    @VisibleForTesting
    internal var timeoutMs = TIMEOUT_MS

    private sealed interface Event {
        data class Result(val result: NetworkResult<List<AddonStreams>>) : Event
        data object EarlyPick : Event
        data object Finished : Event
    }

    /**
     * The stream to play for [item], or null when nothing playable came back within [timeoutMs]
     * or the search failed. A merged game ([SportsLiveItem.alternates]) plays the best of the
     * primary's streams, else the best of the first alternate that has one.
     */
    suspend fun resolve(item: SportsLiveItem): Stream? =
        try {
            if (item.alternates.isEmpty()) search(item) else bestAcrossSources(item)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    /**
     * Every stream of [item] and its alternates, fetched in parallel (each within [timeoutMs]):
     * the primary's first, then each alternate's in its order (addon order). Empty when none came back.
     */
    suspend fun resolveAll(item: SportsLiveItem): List<Stream> =
        try {
            fetchSources(item, installedOrder()).flatten()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }

    private suspend fun installedOrder(): List<String> =
        addonRepository.getInstalledAddons().first().enabledAddons().map { it.displayName }

    private suspend fun bestAcrossSources(item: SportsLiveItem): Stream? {
        val settings = playerSettingsDataStore.playerSettings.first()
        val installedOrder = installedOrder()
        return fetchSources(item, installedOrder).firstNotNullOfOrNull { streams ->
            preferredStream(streams, settings, installedOrder.toSet())
                ?: streams.firstOrNull(StreamAutoPlaySelector::isPlayable)
        }
    }

    /** One list per source: the primary's, then each alternate's in order. */
    private suspend fun fetchSources(item: SportsLiveItem, installedOrder: List<String>): List<List<Stream>> =
        coroutineScope {
            (listOf(item) + item.alternates)
                .map { source -> async { fetch(source, installedOrder) } }
                .awaitAll()
        }

    /**
     * [source]'s streams in addon order, junk dropped. A finished fetch is reported to the probe for
     * the source's own key; a timeout keeps what came back so far and reports nothing.
     */
    private suspend fun fetch(source: SportsLiveItem, installedOrder: List<String>): List<Stream> {
        var last: NetworkResult<List<AddonStreams>>? = null
        val finished = try {
            withTimeoutOrNull(timeoutMs) {
                streamRepository.getStreamsFromAllAddons(source.apiType, source.id).collect { last = it }
                true
            } == true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return emptyList()
        }
        val result = last
        if (finished && result != null) streamProbe.recordResult(source, result)
        return StreamAutoPlaySelector.orderAddonStreams(
            (result as? NetworkResult.Success)?.data.orEmpty(),
            installedOrder
        ).flatMap { it.streams }.filterNot(UnplayableStreamFilter::isUnplayableJunk)
    }

    private suspend fun search(item: SportsLiveItem): Stream? {
        val settings = playerSettingsDataStore.playerSettings.first()
        val installedOrder = installedOrder()

        var last: NetworkResult<List<AddonStreams>>? = null
        var earlyPickAllowed = false
        var finished = false
        // Never forced: a forced fetch cancels other collectors of the same stream session.
        val events = merge(
            streamRepository.getStreamsFromAllAddons(item.apiType, item.id)
                .map<NetworkResult<List<AddonStreams>>, Event> { Event.Result(it) }
                .onCompletion { cause -> if (cause == null) emit(Event.Finished) },
            flow { delay(EARLY_PICK_MS); emit(Event.EarlyPick) }
        )
        val decision = withTimeoutOrNull(timeoutMs) {
            events.mapNotNull { event ->
                when (event) {
                    is Event.Result -> last = event.result
                    Event.EarlyPick -> earlyPickAllowed = true
                    Event.Finished -> finished = true
                }
                val ordered = StreamAutoPlaySelector.orderAddonStreams(
                    (last as? NetworkResult.Success)?.data.orEmpty(),
                    installedOrder
                ).flatMap { it.streams }
                val preferred = preferredStream(ordered, settings, installedOrder.toSet())
                when {
                    finished -> Decision(preferred ?: ordered.firstOrNull(StreamAutoPlaySelector::isPlayable))
                    earlyPickAllowed && preferred != null -> Decision(preferred)
                    else -> null
                }
            }.first()
        }
        // A timeout is inconclusive, so it is not reported; the probe would keep the old verdict anyway.
        if (decision != null) last?.let { streamProbe.recordResult(item, it) }
        return decision?.stream
    }

    private class Decision(val stream: Stream?)

    /** The user's autoplay choice; in MANUAL mode (no autoplay choice exists) the first playable stream. */
    private fun preferredStream(streams: List<Stream>, settings: PlayerSettings, installedNames: Set<String>): Stream? =
        if (settings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL) {
            streams.firstOrNull(StreamAutoPlaySelector::isPlayable)
        } else {
            StreamAutoPlaySelector.selectAutoPlayStream(
                streams = streams,
                mode = settings.streamAutoPlayMode,
                regexPattern = settings.streamAutoPlayRegex,
                source = settings.streamAutoPlaySource,
                installedAddonNames = installedNames,
                selectedAddons = settings.streamAutoPlaySelectedAddons,
                selectedPlugins = settings.streamAutoPlaySelectedPlugins
            )
        }
}
