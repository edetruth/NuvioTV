package com.nuvio.tv.ui.screens.sportslive

import androidx.annotation.VisibleForTesting
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.core.sportslive.SportsLiveAddonFetch
import com.nuvio.tv.core.sportslive.SportsLiveDebugTiming
import com.nuvio.tv.core.sportslive.SportsLiveLinkMarks
import com.nuvio.tv.core.sportslive.SportsLiveNoticeEntries
import com.nuvio.tv.core.sportslive.SportsLiveRecoveryCandidates
import com.nuvio.tv.core.sportslive.SportsLiveSourceCooldown
import com.nuvio.tv.core.sportslive.SportsLiveSourceLabels
import com.nuvio.tv.core.sportslive.SportsLiveSourceReliability
import com.nuvio.tv.core.sportslive.SportsLiveStreamThresholds
import com.nuvio.tv.core.sportslive.SportsLiveWorkingLinks
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
import com.nuvio.tv.ui.screens.sportslive.preview.SportsLivePreviewLookup
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** What OK's lookup for a game found (#56). */
sealed interface SportsLiveLookup {
    /** The stream to play. */
    data class Found(val stream: Stream) : SportsLiveLookup

    /**
     * Streams came back but none may play (the user's filters rule them out, only external links,
     * or the search timed out before a pick). [streams]: what came back (a merged game's sources in
     * rank order), already fetched, so a picker can list them without another request.
     */
    data class NonePlayable(val streams: List<Stream>) : SportsLiveLookup

    /** No source returned any stream (every addon empty, failed or timed out with nothing). */
    data object NothingFound : SportsLiveLookup
}

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
    private val directDebridResolver: DirectDebridResolver,
    private val resolveCache: SportsLiveResolveCache
) {
    companion object {
        /** A lookup with nothing to play gives up after this long (#158); the search goes on in the background. */
        const val TIMEOUT_MS = SportsLiveStreamThresholds.LOOKUP_HARD_CAP_MS

        /** After this, a lookup plays the best of what came back without waiting for slower addons (#158). */
        const val SOFT_DEADLINE_MS = SportsLiveStreamThresholds.LOOKUP_SOFT_DEADLINE_MS

        /** After this, a still-running search stops as soon as it holds the stream the user's settings prefer. */
        const val EARLY_PICK_MS = 4_000L

        /** A hover preview resolves a game at most once in this window; a cached resolve is free (C1). */
        const val PREVIEW_RESOLVE_WINDOW_MS = 10 * 60_000L

        /** Preview resolves allowed per minute across every game (C1). */
        const val PREVIEW_RESOLVES_PER_MINUTE = 3

        /** A preview resolve cancelled sooner than this (the hover moved on) spends no budget (C1). */
        const val PREVIEW_FREE_CANCEL_MS = 2_000L
        private const val MAX_PREVIEW_ENTRIES = 200

        /**
         * An addon's informational entry, a junk link, or a DRM-protected link this TV cannot play (#166):
         * never auto-picked, never counted as playable (C1).
         */
        internal fun isNonStream(stream: Stream): Boolean =
            isNoticeEntry(stream) || SportsLiveLinkMarks.shared.isProtected(stream.getStreamUrl())

        /** An addon's informational entry or junk link: a stream list shows it as a notice row (N2). */
        internal fun isNoticeEntry(stream: Stream): Boolean =
            UnplayableStreamFilter.isUnplayableJunk(stream) || SportsLiveNoticeEntries.isNotice(stream)
    }

    @VisibleForTesting
    internal var timeoutMs = TIMEOUT_MS

    @VisibleForTesting
    internal var softDeadlineMs = SOFT_DEADLINE_MS

    /** Which links worked or failed for each game (#65): good ones are tried first, quarantined ones last. */
    @VisibleForTesting
    internal var workingLinks: SportsLiveWorkingLinks = SportsLiveWorkingLinks.shared

    /** Sources that answered with a rate-limit notice, left alone while they cool down (C1). */
    @VisibleForTesting
    internal var cooldown: SportsLiveSourceCooldown = SportsLiveSourceCooldown.shared

    @VisibleForTesting
    internal var nowMs: () -> Long = { System.currentTimeMillis() }

    /** Per game ([SportsLiveItem.streamKey]): when a hover preview last resolved it. Guarded by [previewLock]. */
    private val previewResolvedAtMs = HashMap<String, Long>()

    /** When each preview resolve of the last minute started, across games. Guarded by [previewLock]. */
    private val previewStarts = ArrayDeque<Long>()
    private val previewLock = Any()

    /** Runs a merged game's fetches, so alternates still finish (and are recorded) after the primary is played. */
    @VisibleForTesting
    internal var fetchScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The last merged game resolved ([SportsLiveItem.streamKey]) and its fetches, still running or done. */
    private var backgroundFetches: Pair<String, List<Deferred<Fetched>>>? = null

    private sealed interface Event {
        data class Result(val result: NetworkResult<List<AddonStreams>>) : Event
        data object EarlyPick : Event
        data object SoftDeadline : Event
        data object Finished : Event
    }

    /**
     * The stream to play for [item], or null when nothing playable came back within [timeoutMs]
     * or the search failed. A merged game ([SportsLiveItem.alternates]) plays the best of the
     * primary's streams, else the best of the first alternate that has one.
     */
    suspend fun resolve(item: SportsLiveItem): Stream? = resolve(item, forceRefresh = false)

    /**
     * The stream to play for [item]. A fresh cached resolve (keyed by [SportsLiveItem.streamKey]) is
     * reused so re-visiting a card while flipping does not re-resolve against the provider's quota.
     * [forceRefresh] (a re-resolve after a playback failure) skips the cache and refreshes the entry.
     */
    suspend fun resolve(item: SportsLiveItem, forceRefresh: Boolean): Stream? =
        (lookup(item, forceRefresh) as? SportsLiveLookup.Found)?.stream

    /**
     * [resolve] that also tells, when nothing plays, whether any stream came back at all (#56: OK on
     * a game). Same single search and cache as [resolve]; no further request.
     */
    suspend fun lookup(item: SportsLiveItem): SportsLiveLookup = lookup(item, forceRefresh = false)

    suspend fun lookup(item: SportsLiveItem, forceRefresh: Boolean): SportsLiveLookup {
        val startedAt = System.currentTimeMillis()
        return lookupNow(item, forceRefresh).also { found ->
            SportsLiveDebugTiming.log {
                "gameLookup ms=${System.currentTimeMillis() - startedAt} outcome=${found::class.simpleName} " +
                    "sport=${item.sport} source=${SportsLiveDebugTiming.label(item.addonName)}"
            }
        }
    }

    private suspend fun lookupNow(item: SportsLiveItem, forceRefresh: Boolean): SportsLiveLookup =
        try {
            // A link known to play this game (#162, #173: Watch on a kickoff prompt) comes first. A cached
            // link that has since failed is not served again, unless a search already found nothing better
            // than a quarantined link: that one is served until its window ends, so a game with only failed
            // links is searched at most once per quarantine window.
            val cached = if (forceRefresh) null else {
                workingLinks.goodStream(item.id)
                    ?: resolveCache.get(item.streamKey)?.takeUnless { workingLinks.isQuarantined(item.id, it) }
                    ?: workingLinks.fallback(item.id)
            }
            if (cached != null) {
                SportsLiveLookup.Found(cached)
            } else {
                val found = if (item.alternates.isEmpty()) search(item) else bestAcrossSources(item)
                if (found is SportsLiveLookup.Found) {
                    resolveCache.put(item.streamKey, found.stream)
                    if (workingLinks.isQuarantined(item.id, found.stream)) workingLinks.noteFallback(item.id, found.stream)
                    found
                } else {
                    // An addon cooling down was left out of the search: a link known to work still plays (C1).
                    workingLinks.goodStream(item.id)
                        ?.takeIf { cooldown.coolingBaseUrls().isNotEmpty() }
                        ?.let { SportsLiveLookup.Found(it) }
                        ?: found
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            SportsLiveLookup.NothingFound
        }

    /**
     * The hover preview's lookup (C1): a cached resolve costs nothing; otherwise a game is resolved at
     * most once per [PREVIEW_RESOLVE_WINDOW_MS], and at most [PREVIEW_RESOLVES_PER_MINUTE] games a
     * minute. Its search leaves out addons cooling down after a rate limit, as every search does. A
     * preview that may not resolve shows nothing ([SportsLivePreviewLookup.NotPreviewable]). A resolve
     * cancelled within [PREVIEW_FREE_CANCEL_MS] (the hover moved on) is not counted.
     */
    suspend fun previewLookup(item: SportsLiveItem): SportsLivePreviewLookup {
        val cached = resolveCache.get(item.streamKey)?.takeUnless { workingLinks.isQuarantined(item.id, it) }
        val stream = cached ?: run {
            val startedAtMs = takePreviewResolve(item) ?: return SportsLivePreviewLookup.NotPreviewable
            val resolved = try {
                resolve(item)
            } catch (e: CancellationException) {
                if (nowMs() - startedAtMs < PREVIEW_FREE_CANCEL_MS) refundPreviewResolve(item, startedAtMs)
                throw e
            }
            resolved ?: return SportsLivePreviewLookup.NoStream
        }
        return if (sportsLiveLightPlayable(stream, directDebridResolver)) {
            SportsLivePreviewLookup.Found(stream)
        } else {
            SportsLivePreviewLookup.NotPreviewable
        }
    }

    /**
     * Spends a preview resolve of [item] and returns when it started, or null when [item] resolved
     * within [PREVIEW_RESOLVE_WINDOW_MS] or [PREVIEW_RESOLVES_PER_MINUTE] games already did this minute.
     */
    private fun takePreviewResolve(item: SportsLiveItem): Long? = synchronized(previewLock) {
        val now = nowMs()
        while (previewStarts.isNotEmpty() && now - previewStarts.first() >= 60_000L) previewStarts.removeFirst()
        if (previewStarts.size >= PREVIEW_RESOLVES_PER_MINUTE) return null
        val last = previewResolvedAtMs[item.streamKey]
        if (last != null && now - last < PREVIEW_RESOLVE_WINDOW_MS) return null
        previewResolvedAtMs[item.streamKey] = now
        previewStarts.addLast(now)
        if (previewResolvedAtMs.size > MAX_PREVIEW_ENTRIES) {
            previewResolvedAtMs.values.removeAll { now - it >= PREVIEW_RESOLVE_WINDOW_MS }
        }
        now
    }

    /** Gives back the preview resolve of [item] that started at [startedAtMs] (cancelled at once). */
    private fun refundPreviewResolve(item: SportsLiveItem, startedAtMs: Long) {
        synchronized(previewLock) {
            if (previewResolvedAtMs[item.streamKey] == startedAtMs) previewResolvedAtMs.remove(item.streamKey)
            previewStarts.remove(startedAtMs)
        }
    }

    /**
     * [item]'s streams from every addon but those cooling down after a rate limit (C1); each addon
     * answers within a few seconds, its meta fallback asked alongside (#138, [SportsLiveAddonFetch]).
     */
    private fun streamsOf(item: SportsLiveItem): Flow<NetworkResult<List<AddonStreams>>> {
        SportsLiveAddonFetch.shared.markSports(item.id)
        val cooling = cooldown.coolingBaseUrls()
        return if (cooling.isEmpty()) {
            streamRepository.getStreamsFromAllAddons(item.apiType, item.id)
        } else {
            streamRepository.getStreamsFromAllAddonsExcept(item.apiType, item.id, cooling)
        }
    }

    /** Drops the cached resolve for [item] (after a playback failure), so the next resolve refreshes it. */
    fun invalidate(item: SportsLiveItem) = resolveCache.invalidate(item.streamKey)

    /** Drops cached resolves for content id [contentId] (the full player knows only the content id). */
    fun invalidateByContentId(contentId: String) = resolveCache.invalidateByContentId(contentId)

    /**
     * The next link a light player (a dual pane, the mini player) may switch to for [item] after its
     * links in [tried] ([SportsLiveWorkingLinks.linkKey]s) failed (#138), or null when none is left.
     * First what recent searches already hold for the game and then its other sources (a merged
     * card's siblings), with no request; only when nothing usable is cached, one lookup (which joins a
     * search still running and leaves out sources cooling down). Ordered and filtered by
     * [SportsLiveRecoveryCandidates]; a link the light player cannot play as is is skipped.
     */
    suspend fun recoveryStream(item: SportsLiveItem, tried: Set<String>): Stream? =
        try {
            val installed = installedOrder()
            val cached = (listOf(item) + item.alternates).map { source ->
                StreamAutoPlaySelector.orderAddonStreams(
                    streamRepository.getCachedStreamsFromAllAddons(source.apiType, source.id).orEmpty(),
                    installed
                ).flatMap { it.streams }
            }
            val pick: suspend (List<Stream>, List<Stream>) -> Stream? = { primary, siblings ->
                SportsLiveRecoveryCandidates.order(item.id, primary, siblings, tried, workingLinks, cooldown.coolingNames())
                    .firstOrNull { sportsLiveLightPlayable(it, directDebridResolver) }
            }
            pick(cached.first(), cached.drop(1).flatten())
                ?: (lookup(item, forceRefresh = true) as? SportsLiveLookup.Found)?.stream
                    ?.let { pick(listOf(it), emptyList()) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    /**
     * How many distinct links recent searches hold for [item] and its other sources (#138), for a
     * starting card's "link N of M"; no request is made. 0 when none is cached.
     */
    suspend fun cachedLinkCount(item: SportsLiveItem): Int =
        try {
            (listOf(item) + item.alternates).flatMap { source ->
                streamRepository.getCachedStreamsFromAllAddons(source.apiType, source.id).orEmpty().flatMap { it.streams }
            }.filterNot(::isNonStream).mapNotNull { it.getStreamUrl() }.distinct().size
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            0
        }

    /**
     * Every stream of [item] and its alternates, fetched in parallel (each within [timeoutMs]):
     * source by source in [rankedSources] order (the most reliable first; with no evidence, the
     * primary's first, then each alternate's in addon order). Empty when none came back. The
     * alternates' streams seed the Sources panel ([seedExtras]).
     */
    suspend fun resolveAll(item: SportsLiveItem): List<Stream> =
        try {
            val settings = playerSettingsDataStore.playerSettings.first()
            val sources = rankedSources(item, settings)
            val installedOrder = installedOrder()
            val fetched = coroutineScope {
                sources.map { source -> async { fetchSource(item, source, installedOrder) } }.awaitAll()
            }
            seedExtras(item, sources.zip(fetched))
            sources.zip(fetched).flatMap { (source, result) -> playablePerSource(item, source, result.streams) }
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
        coroutineScope {
            val lookups = item.alternates.map { source -> async { resolveAlternate(item, source) } }.awaitAll()
            SportsLiveExtraLookup(lookups.flatMap { it.streams }, complete = lookups.all { it.complete })
        }

    /**
     * One alternate [source] of merged [card], as [resolveAlternates] lists it: the Sources panel
     * fetches each alternate this way, so each source's group shows as soon as it answers.
     */
    suspend fun resolveAlternate(card: SportsLiveItem, source: SportsLiveItem): SportsLiveExtraLookup =
        try {
            val fetched = fetchSource(card, source, installedOrder())
            SportsLiveExtraLookup(labeled(card, source, fetched.streams), complete = fetched.finished)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            SportsLiveExtraLookup(emptyList(), complete = false)
        }

    /**
     * [streams] of [card]'s [source] under the source's own label (provider sub-catalog, or addon
     * name): the Sources panel groups by stream.addonName, so a shared addon's sub-catalogs must not
     * reuse one label.
     */
    private fun labeled(card: SportsLiveItem, source: SportsLiveItem, streams: List<Stream>): List<Stream> {
        val label = SportsLiveSourceLabels.of(card)[source.streamKey] ?: return streams
        return streams.map { it.copy(addonName = label) }
    }

    /**
     * The play path fetched every source of merged [card] ([fetched], any order): its alternates'
     * streams, labeled and listed as [resolveAlternates] lists them, seed the Sources panel so it
     * does not fetch them again. Nothing is seeded unless every alternate finished.
     */
    private fun seedExtras(card: SportsLiveItem, fetched: List<Pair<SportsLiveItem, Fetched>>) {
        val alternates = card.alternates.map { alt -> fetched.firstOrNull { it.first === alt } ?: return }
        SportsLiveExtraSources.seed(
            item = card,
            streams = alternates.flatMap { (source, result) -> labeled(card, source, result.streams) },
            complete = alternates.all { it.second.finished }
        )
    }

    /**
     * [streams] of [card]'s [source] that the play path may play: an alternate's that need the Stream
     * screen's preparation are dropped, since that screen opens for the primary, which does not list
     * them. A failing preparation check gives that source nothing rather than failing the caller.
     */
    private suspend fun playablePerSource(card: SportsLiveItem, source: SportsLiveItem, streams: List<Stream>): List<Stream> =
        if (source === card) {
            streams
        } else {
            try {
                streams.filterNot { sportsLiveNeedsPreparation(it, directDebridResolver) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }

    private suspend fun installedOrder(): List<String> =
        addonRepository.getInstalledAddons().first().enabledAddons().map { it.displayName }

    /**
     * All sources are fetched at once in [fetchScope]; the primary's best stream plays as soon as it
     * is known, while the alternates finish in the background (their results are still recorded).
     * Only when the primary has nothing are the alternates awaited, in order. Starting on another
     * game cancels the previous game's fetches still running, so fast zaps do not pile them up.
     */
    private suspend fun bestAcrossSources(item: SportsLiveItem): SportsLiveLookup {
        val settings = playerSettingsDataStore.playerSettings.first()
        val installedOrder = installedOrder()
        // The most reliable source first; its best stream plays as soon as it is known.
        val sources = rankedSources(item, settings)
        val fetches = sources.map { source -> fetchScope.async { fetchSource(item, source, installedOrder) } }
        val previous = synchronized(this) {
            backgroundFetches.also { backgroundFetches = item.streamKey to fetches }
        }
        // Finished fetches have recorded their results already; only running ones are cancelled.
        if (previous != null && previous.first != item.streamKey) previous.second.forEach { it.cancel() }
        // Once every alternate has answered, the Sources panel gets them without fetching them again.
        fetchScope.launch {
            val fetched = try {
                sources.zip(fetches.awaitAll())
            } catch (_: CancellationException) {
                return@launch // a newer game cancelled them: nothing complete to seed
            }
            seedExtras(item, fetched)
        }
        // First the user's choice across every source, in rank order, so a reliable source with
        // nothing the user's regex or chosen addons allow never plays a stream they rule out.
        // Within a source, links known to work for this game go first; quarantined ones are left
        // for the end, across every source (#65).
        val fetched = mutableListOf<List<Stream>>()
        for ((source, fetch) in sources.zip(fetches)) {
            val streams = try {
                workingLinks.order(item.id, playablePerSource(item, source, fetch.await().streams))
            } catch (e: CancellationException) {
                // A newer game cancelled this fetch: unless this lookup is cancelled too, it found nothing.
                currentCoroutineContext().ensureActive()
                emptyList()
            }
            preferredStream(workingLinks.withoutQuarantined(item.id, streams), settings, installedOrder.toSet())
                ?.let { return SportsLiveLookup.Found(it) }
            fetched += streams
        }
        // Then, only when the user's settings do not narrow what may play, the first playable stream.
        val firstPlayable = { lists: List<List<Stream>> ->
            if (honoursAutoPlayFilters(settings)) null
            else lists.firstNotNullOfOrNull { streams -> streams.firstOrNull(StreamAutoPlaySelector::isPlayable) }
        }
        firstPlayable(fetched.map { workingLinks.withoutQuarantined(item.id, it) })
            ?.let { return SportsLiveLookup.Found(it) }
        // Nothing else is left: a quarantined link, picked the same way.
        val quarantined = fetched.map { streams -> streams.filter { workingLinks.isQuarantined(item.id, it) } }
        (preferredStream(quarantined.flatten(), settings, installedOrder.toSet()) ?: firstPlayable(quarantined))
            ?.let { return SportsLiveLookup.Found(it) }
        val all = fetched.flatten()
        return if (all.isEmpty()) SportsLiveLookup.NothingFound else SportsLiveLookup.NonePlayable(all)
    }

    /**
     * [item]'s sources (the primary, then its alternates) ordered by remembered reliability, best
     * first ([SportsLiveSourceReliability.rank]): the primary keeps a small edge, and sources of addons
     * the user's autoplay settings leave out stay behind the chosen ones, in their order.
     */
    private fun rankedSources(item: SportsLiveItem, settings: PlayerSettings): List<SportsLiveItem> {
        val chosen = settings.streamAutoPlaySelectedAddons
        return SportsLiveSourceReliability.rank(
            candidates = listOf(item) + item.alternates,
            scoreOf = { SportsLiveSourceReliability.scoreOf(SportsLiveSourceReliability.keyOf(it)) },
            allowed = { chosen.isEmpty() || it.addonName in chosen }
        )
    }

    private class Fetched(val streams: List<Stream>, val finished: Boolean)

    /**
     * [source]'s streams in addon order, junk dropped (those that need preparation kept: see
     * [playablePerSource]). Past the soft deadline ([softDeadlineMs], #158) it stops as soon as any
     * stream came back; slower addons keep answering in the background. A finished fetch, or one that
     * stopped with streams, is reported to the probe for the source's own key; a timeout keeps what
     * came back so far and reports nothing. Any failure gives no streams rather
     * than failing the caller.
     */
    private suspend fun fetchSource(
        card: SportsLiveItem,
        source: SportsLiveItem,
        installedOrder: List<String>
    ): Fetched =
        try {
            var last: NetworkResult<List<AddonStreams>>? = null
            var finished = false
            var softPassed = false
            val hasStreams = { (last as? NetworkResult.Success)?.data.orEmpty().any { it.streams.isNotEmpty() } }
            // Past the soft deadline, what came back is used; slower addons answer in the background (#158).
            val stoppedWithStreams = withTimeoutOrNull(timeoutMs) {
                merge(
                    streamsOf(source)
                        .map<NetworkResult<List<AddonStreams>>, Event> { Event.Result(it) }
                        .onCompletion { cause -> if (cause == null) emit(Event.Finished) },
                    flow { delay(softDeadlineMs); emit(Event.SoftDeadline) }
                ).first { event ->
                    when (event) {
                        is Event.Result -> last = event.result
                        Event.Finished -> finished = true
                        Event.SoftDeadline -> softPassed = true
                        Event.EarlyPick -> Unit
                    }
                    finished || (softPassed && hasStreams())
                }
                !finished
            } == true
            val result = last
            // Streams found are a verdict even before the search finished (the game is on).
            if ((finished || stoppedWithStreams) && result != null) streamProbe.recordResult(source, result)
            (result as? NetworkResult.Success)?.data?.let(cooldown::noteNotices)
            val streams = StreamAutoPlaySelector.orderAddonStreams(
                (result as? NetworkResult.Success)?.data.orEmpty(),
                installedOrder
            ).flatMap { it.streams }
                .filterNot(::isNonStream)
            // Remember where each stream came from, so its plays are credited to this source.
            streams.forEach { SportsLiveExtraSources.noteStreamSource(card.id, it, source) }
            Fetched(streams, finished)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Fetched(emptyList(), finished = false)
        }

    private suspend fun search(item: SportsLiveItem): SportsLiveLookup {
        val settings = playerSettingsDataStore.playerSettings.first()
        val installedOrder = installedOrder()

        var last: NetworkResult<List<AddonStreams>>? = null
        var earlyPickAllowed = false
        var softPassed = false
        var finished = false
        // Never forced: a forced fetch cancels other collectors of the same stream session.
        val events = merge(
            streamsOf(item)
                .map<NetworkResult<List<AddonStreams>>, Event> { Event.Result(it) }
                .onCompletion { cause -> if (cause == null) emit(Event.Finished) },
            flow { delay(EARLY_PICK_MS); emit(Event.EarlyPick) },
            flow { delay(softDeadlineMs); emit(Event.SoftDeadline) }
        )
        val decision = withTimeoutOrNull(timeoutMs) {
            events.mapNotNull { event ->
                when (event) {
                    is Event.Result -> last = event.result
                    Event.EarlyPick -> earlyPickAllowed = true
                    Event.SoftDeadline -> softPassed = true
                    Event.Finished -> finished = true
                }
                // Links known to work for this game first; quarantined ones only when nothing else plays (#65).
                // An informational entry or junk link is never picked (C1).
                val ordered = workingLinks.order(
                    item.id,
                    StreamAutoPlaySelector.orderAddonStreams(
                        (last as? NetworkResult.Success)?.data.orEmpty(),
                        installedOrder
                    ).flatMap { it.streams }.filterNot(::isNonStream)
                )
                val fresh = workingLinks.withoutQuarantined(item.id, ordered)
                val preferred = preferredStream(fresh, settings, installedOrder.toSet())
                when {
                    finished -> Decision(
                        preferred ?: fresh.firstOrNull(StreamAutoPlaySelector::isPlayable)
                            ?: preferredStream(ordered, settings, installedOrder.toSet())
                            ?: ordered.firstOrNull(StreamAutoPlaySelector::isPlayable)
                    )
                    earlyPickAllowed && preferred != null -> Decision(preferred)
                    // Past the soft deadline, a slow addon is not waited for when something plays (#158).
                    softPassed -> (preferred ?: fresh.firstOrNull(StreamAutoPlaySelector::isPlayable))?.let(::Decision)
                    else -> null
                }
            }.first()
        }
        // A timeout is inconclusive, so it is not reported; the probe would keep the old verdict anyway.
        if (decision != null) last?.let { streamProbe.recordResult(item, it) }
        (last as? NetworkResult.Success)?.data?.let(cooldown::noteNotices)
        decision?.stream?.let { return SportsLiveLookup.Found(it) }
        val came = StreamAutoPlaySelector.orderAddonStreams(
            (last as? NetworkResult.Success)?.data.orEmpty(),
            installedOrder
        ).flatMap { it.streams }.filterNot(::isNonStream)
        return if (came.isEmpty()) SportsLiveLookup.NothingFound else SportsLiveLookup.NonePlayable(came)
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
