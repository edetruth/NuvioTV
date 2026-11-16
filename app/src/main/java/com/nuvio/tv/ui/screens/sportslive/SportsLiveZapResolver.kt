package com.nuvio.tv.ui.screens.sportslive

import androidx.annotation.VisibleForTesting
import com.nuvio.tv.core.debrid.DirectDebridResolver
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private val streamProbe: SportsLiveStreamProbe,
    private val directDebridResolver: DirectDebridResolver
) {
    companion object {
        const val TIMEOUT_MS = 12_000L

        /** After this, a still-running search stops as soon as it holds the stream the user's settings prefer. */
        const val EARLY_PICK_MS = 4_000L
    }

    @VisibleForTesting
    internal var timeoutMs = TIMEOUT_MS

    /** Runs a merged game's fetches, so alternates still finish (and are recorded) after the primary is played. */
    @VisibleForTesting
    internal var fetchScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The last merged game resolved ([SportsLiveItem.streamKey]) and its fetches, still running or done. */
    private var backgroundFetches: Pair<String, List<Deferred<List<Stream>>>>? = null

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

    /**
     * The alternates' streams of [item] (not the primary's), fetched in parallel, junk dropped,
     * each keeping its addon name. Unlike [resolveAll], streams that need preparation (server,
     * YouTube or debrid resolve) are kept: the player's Sources panel prepares them in place.
     * The fetches join the stream repository's shared search sessions, so a search the play
     * path already ran is reused. Empty when there are no alternates or none came back; not
     * [SportsLiveExtraLookup.complete] when a source timed out or failed.
     */
    suspend fun resolveAlternates(item: SportsLiveItem): SportsLiveExtraLookup =
        try {
            val installedOrder = installedOrder()
            val fetched = coroutineScope {
                item.alternates
                    .map { source -> async { fetchSource(source, installedOrder, dropPreparation = false) } }
                    .awaitAll()
            }
            SportsLiveExtraLookup(fetched.flatMap { it.streams }, complete = fetched.all { it.finished })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            SportsLiveExtraLookup(emptyList(), complete = false)
        }

    private suspend fun installedOrder(): List<String> =
        addonRepository.getInstalledAddons().first().enabledAddons().map { it.displayName }

    /**
     * All sources are fetched at once in [fetchScope]; the primary's best stream plays as soon as it
     * is known, while the alternates finish in the background (their results are still recorded).
     * Only when the primary has nothing are the alternates awaited, in order. Starting on another
     * game cancels the previous game's fetches still running, so fast zaps do not pile them up.
     */
    private suspend fun bestAcrossSources(item: SportsLiveItem): Stream? {
        val settings = playerSettingsDataStore.playerSettings.first()
        val installedOrder = installedOrder()
        val fetches = sources(item).mapIndexed { index, source ->
            fetchScope.async { fetch(source, installedOrder, dropPreparation = index > 0) }
        }
        val previous = synchronized(this) {
            backgroundFetches.also { backgroundFetches = item.streamKey to fetches }
        }
        // Finished fetches have recorded their results already; only running ones are cancelled.
        if (previous != null && previous.first != item.streamKey) previous.second.forEach { it.cancel() }
        return fetches.firstNotNullOfOrNull { fetch ->
            val streams = try {
                fetch.await()
            } catch (e: CancellationException) {
                // A newer game cancelled this fetch: unless this lookup is cancelled too, it found nothing.
                currentCoroutineContext().ensureActive()
                emptyList()
            }
            preferredStream(streams, settings, installedOrder.toSet())
                ?: streams.firstOrNull(StreamAutoPlaySelector::isPlayable)
        }
    }

    private fun sources(item: SportsLiveItem): List<SportsLiveItem> = listOf(item) + item.alternates

    /** One list per source: the primary's, then each alternate's in order. */
    private suspend fun fetchSources(item: SportsLiveItem, installedOrder: List<String>): List<List<Stream>> =
        coroutineScope {
            sources(item)
                .mapIndexed { index, source -> async { fetch(source, installedOrder, dropPreparation = index > 0) } }
                .awaitAll()
        }

    private suspend fun fetch(source: SportsLiveItem, installedOrder: List<String>, dropPreparation: Boolean): List<Stream> =
        fetchSource(source, installedOrder, dropPreparation).streams

    private class Fetched(val streams: List<Stream>, val finished: Boolean)

    /**
     * [source]'s streams in addon order, junk dropped. A finished fetch is reported to the probe for
     * the source's own key; a timeout keeps what came back so far and reports nothing.
     * With [dropPreparation] (an alternate on the play path), streams that need the Stream screen's
     * preparation are dropped: that screen opens for the primary, which does not list them.
     * Any failure, the preparation check included, gives no streams rather than failing the caller.
     */
    private suspend fun fetchSource(source: SportsLiveItem, installedOrder: List<String>, dropPreparation: Boolean): Fetched =
        try {
            var last: NetworkResult<List<AddonStreams>>? = null
            val finished = withTimeoutOrNull(timeoutMs) {
                streamRepository.getStreamsFromAllAddons(source.apiType, source.id).collect { last = it }
                true
            } == true
            val result = last
            if (finished && result != null) streamProbe.recordResult(source, result)
            val streams = StreamAutoPlaySelector.orderAddonStreams(
                (result as? NetworkResult.Success)?.data.orEmpty(),
                installedOrder
            ).flatMap { it.streams }
                .filterNot(UnplayableStreamFilter::isUnplayableJunk)
                .filterNot { dropPreparation && sportsLiveNeedsPreparation(it, directDebridResolver) }
            Fetched(streams, finished)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Fetched(emptyList(), finished = false)
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
