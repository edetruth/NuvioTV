package com.nuvio.tv.data.repository

import androidx.annotation.VisibleForTesting
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.sportslive.SportsLiveCalmOpen
import com.nuvio.tv.core.sportslive.SportsLiveCatalogOptions
import com.nuvio.tv.core.sportslive.SportsLiveCatalogPolicy
import com.nuvio.tv.core.sportslive.SportsLiveDebugTiming
import com.nuvio.tv.core.sportslive.SportsLiveSharedBuckets
import com.nuvio.tv.core.sportslive.SportsLiveItemBucketer
import com.nuvio.tv.core.sportslive.SportsLiveMySportsScope
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.domain.model.CatalogOption
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.CatalogStats
import com.nuvio.tv.domain.model.ClaimedCatalog
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.SportsLiveFailure
import com.nuvio.tv.domain.repository.SportsLiveLoad
import com.nuvio.tv.domain.repository.SportsLiveRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SportsLiveRepositoryImpl @Inject constructor(
    addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val prefs: SportsLivePreferencesDataStore,
    private val streamProbe: SportsLiveStreamProbe
) : SportsLiveRepository {
    private companion object {
        const val CACHE_MS = 60_000L
        const val REBUCKET_DEBOUNCE_MS = 2_000L
        const val CATALOG_TIMEOUT_MS = 15_000L
    }

    @VisibleForTesting
    internal var nowMs: () -> Long = { System.currentTimeMillis() }

    /** The one sorting per load the section, Home and the player share (live.20). */
    @VisibleForTesting
    internal var sharedBuckets: SportsLiveSharedBuckets = SportsLiveSharedBuckets.shared

    @VisibleForTesting
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    /** Describing and bucketing thousands of channels is too much work for the caller's (main) thread. */
    @VisibleForTesting
    internal var computeDispatcher: CoroutineDispatcher = Dispatchers.Default

    private val _latest = MutableStateFlow<SportsLiveLoad?>(null)
    private val loadMutex = Mutex()

    /** The most recent load, whether or not every catalog succeeded. */
    override val latest: StateFlow<SportsLiveLoad?> = _latest.asStateFlow()

    private val _catalogStats = MutableStateFlow<Map<String, CatalogStats>>(emptyMap())

    override val catalogStats: StateFlow<Map<String, CatalogStats>> = _catalogStats.asStateFlow()

    override val enabled: Flow<Boolean> = prefs.showSection

    override val claimedCatalogs: Flow<List<ClaimedCatalog>> = combine(
        enabled,
        addonRepository.getInstalledAddons().map { it.enabledAddons() },
        prefs.overrides,
        prefs.catalogOptions
    ) { enabled, addons, overrides, options ->
        if (!enabled) return@combine emptyList()
        addons.flatMap { addon ->
            addon.catalogs.mapNotNull { catalog ->
                SportsLiveCatalogPolicy.classify(addon, catalog, overrides)?.let {
                    val key = SportsLiveCatalogPolicy.keyFor(addon, catalog)
                    ClaimedCatalog(
                        addon = addon,
                        catalog = catalog,
                        key = key,
                        kind = it.kind,
                        viaOverride = it.reason == SportsLiveCatalogPolicy.REASON_INCLUDE,
                        option = SportsLiveCatalogOptions.effectiveOption(catalog, options[key])
                    )
                }
            }
        }
    }.distinctUntilChanged()

    override val claimedCatalogKeys: Flow<Set<String>> =
        claimedCatalogs.map { claimed -> claimed.map { it.key }.toSet() }

    override val sectionAvailable: Flow<Boolean> =
        combine(enabled, claimedCatalogs) { enabled, claimed -> enabled && claimed.isNotEmpty() }

    /**
     * Loads are serialised. The result is published to [latest] only if the claimed catalogs (which
     * follow the active profile, the overrides and the pinned options) did not change while it was
     * fetching, so a load started for one profile never overwrites the cache after a switch to another.
     */
    override suspend fun load(force: Boolean, includeChannels: Boolean, onlyKinds: Set<SportsLiveKind>?): SportsLiveLoad = loadMutex.withLock {
        // Debug builds (live.20 B1): each stage's time, to measure the open on a slow box.
        val startedAt = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - startedAt) / 1_000_000
        val claimed = claimedCatalogs.first()
        val claimedKeys = claimed.map { it.key }.toSet()
        val options = optionsOf(claimed)
        val cached = _latest.value
        if (!force && cached != null && !cached.partial && nowMs() - cached.loadedAtMs < CACHE_MS &&
            cached.claimedKeys == claimedKeys && cached.catalogOptions == options
        ) {
            return@withLock cached
        }
        // A channels catalog is carried over from the cached load, rather than refetched, only when that
        // load covered exactly the catalogs claimed now, loaded this one with the same pinned option, and
        // its items came from the same addon install: keys have no base URL, and two profiles can install
        // one addon under different manifest URLs.
        // A tab's refresh (#71) carries over every catalog of another kind the same way.
        fun carried(catalog: ClaimedCatalog): Boolean =
            (onlyKinds != null && catalog.kind !in onlyKinds) || (!includeChannels && catalog.kind == SportsLiveKind.CHANNELS)
        val carriedItems: Map<String, List<SportsLiveItem>> =
            if ((includeChannels && onlyKinds == null) || cached == null || cached.claimedKeys != claimedKeys) {
                emptyMap()
            } else {
                val cachedByKey = cached.items.groupBy { it.catalogKey }
                claimed.filter { carried(it) && cached.catalogOptions[it.key] == it.option }
                    .mapNotNull { catalog ->
                        cachedByKey[catalog.key]
                            ?.takeIf { items -> items.all { it.addonBaseUrl == catalog.addon.baseUrl } }
                            ?.let { catalog.key to it }
                    }.toMap()
            }

        SportsLiveDebugTiming.log { "sportsOpen stage=loadStart t=${elapsedMs()} catalogs=${claimed.size} carried=${carriedItems.size} force=$force" }
        // The load's items, failures and stats from [fetched] (every catalog, or most of them for the early list).
        suspend fun built(fetched: Map<String, NetworkResult<CatalogRow>>, partial: Boolean): Pair<SportsLiveLoad, Map<String, CatalogStats>> {
            val now = nowMs()
            val items = mutableListOf<SportsLiveItem>()
            val failures = mutableListOf<SportsLiveFailure>()
            val stats = mutableMapOf<String, CatalogStats>()
            withContext(computeDispatcher) {
                // In claimed order, as the bucketer keeps an item's first occurrence.
                for (catalog in claimed) {
                    val catalogItems = when (val result = fetched[catalog.key]) {
                        null -> carriedItems[catalog.key].orEmpty()
                        is NetworkResult.Success -> SportsLiveItemBucketer.describeCatalog(result.data.items, catalog, now)
                        is NetworkResult.Error -> {
                            failures += SportsLiveFailure(catalog, result.message)
                            continue
                        }
                        NetworkResult.Loading -> continue
                    }
                    items += catalogItems
                    stats[catalog.key] = SportsLiveItemBucketer.stats(catalogItems, now)
                }
            }
            val load = SportsLiveLoad(
                items, failures, loadedAtMs = now, claimedKeys = claimedKeys, catalogOptions = options,
                fetchedSources = claimed.filter { it.key in fetched }.associate { it.addon.id to it.addon.displayName },
                claimedSources = claimed.mapTo(HashSet()) { it.addon.id },
                partial = partial
            )
            return load to stats
        }

        suspend fun publish(load: SportsLiveLoad, stats: Map<String, CatalogStats>): Boolean {
            val claimedNow = claimedCatalogs.first()
            if (claimedNow.map { it.key }.toSet() == claimedKeys && optionsOf(claimedNow) == options) {
                _latest.value = load
                _catalogStats.value = stats
                return true
            }
            return false
        }

        // J1's early list of this load, while it is the one published: withdrawn if the claims change before the end.
        val statsBefore = _catalogStats.value
        var earlyPublished: SportsLiveLoad? = null

        val toFetch = claimed.filterNot { it.key in carriedItems }
        val answered = java.util.concurrent.ConcurrentHashMap<String, NetworkResult<CatalogRow>>()
        val answeredCount = MutableStateFlow(0)
        val fetched = coroutineScope {
            // J1 (live.23): on the first load (nothing in memory to show), the list goes out once most catalogs have
            // answered, so one slow addon never holds the whole list; the rest follow with the full load.
            val early = if (cached == null && SportsLiveCalmOpen.offersEarlyList(toFetch.size)) {
                launch {
                    answeredCount.first { SportsLiveCalmOpen.mostAnswered(it, toFetch.size) }
                    val (load, stats) = built(HashMap(answered), partial = true)
                    SportsLiveDebugTiming.log { "sportsOpen stage=early t=${elapsedMs()} answered=${answered.size}/${toFetch.size}" }
                    if (publish(load, stats)) earlyPublished = load
                }
            } else {
                null
            }
            val results = toFetch
                .map { catalog ->
                    async(ioDispatcher) {
                        val fetchedAt = System.nanoTime()
                        val result = fetchFirstPage(catalog)
                        SportsLiveDebugTiming.log {
                            val size = if (result is NetworkResult.Success) result.data.items.size else null
                            "sportsOpen stage=catalog ms=${(System.nanoTime() - fetchedAt) / 1_000_000} source=${SportsLiveDebugTiming.label(catalog.addon.displayName)} " +
                                "kind=${catalog.kind.name} items=${size ?: "error"}"
                        }
                        answered[catalog.key] = result
                        answeredCount.update { it + 1 }
                        catalog.key to result
                    }
                }
                .awaitAll()
            // Every catalog answered before the early list went out: none is needed.
            early?.cancel()
            results
        }.toMap()
        SportsLiveDebugTiming.log { "sportsOpen stage=fetched t=${elapsedMs()}" }
        val (load, stats) = built(fetched, partial = false)
        SportsLiveDebugTiming.log { "sportsOpen stage=described t=${elapsedMs()} items=${load.items.size} failures=${load.failures.size}" }
        if (!publish(load, stats)) {
            // The profile, the overrides or a pin changed mid-load: no part of this load stays published, not even its
            // early list (it would show another profile's or option's games), so the list is as it was before it.
            val early = earlyPublished
            if (early != null && _latest.value === early) {
                _latest.value = cached
                _catalogStats.value = statsBefore
            }
        }
        load
    }

    private fun optionsOf(claimed: List<ClaimedCatalog>): Map<String, CatalogOption> =
        claimed.mapNotNull { catalog -> catalog.option?.let { catalog.key to it } }.toMap()

    /** The catalog's first non-loading result; a timeout or exception becomes an [NetworkResult.Error]. */
    private suspend fun fetchFirstPage(claimed: ClaimedCatalog): NetworkResult<CatalogRow> {
        val addon = claimed.addon
        val catalog = claimed.catalog
        return runCatching {
            withTimeoutOrNull(CATALOG_TIMEOUT_MS) {
                catalogRepository.getCatalog(
                    addonBaseUrl = addon.baseUrl,
                    addonId = addon.id,
                    addonName = addon.name,
                    catalogId = catalog.id,
                    catalogName = catalog.name,
                    type = catalog.apiType,
                    skip = 0,
                    extraArgs = claimed.option?.let { mapOf(it.extra to it.value) }.orEmpty(),
                    supportsSkip = false
                ).filter { it !is NetworkResult.Loading }.first()
            } ?: NetworkResult.Error("Timed out")
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            NetworkResult.Error(error.message ?: error::class.java.simpleName)
        }
    }

    override val endedKeys: StateFlow<Set<String>> = streamProbe.ended

    override val followedTeams: Flow<List<FollowedTeam>> = prefs.followedTeams

    override suspend fun follow(team: FollowedTeam) = prefs.follow(team)

    override suspend fun unfollow(team: FollowedTeam) = prefs.unfollow(team)

    // A merged game ends only when every source has, so each source is probed.
    override fun hasRecentLinks(item: SportsLiveItem): Boolean =
        (listOf(item) + item.alternates).any(streamProbe::hasRecentLinks)

    override fun clearEnded() = streamProbe.clearEnded()

    override fun reprobeIfStale(item: SportsLiveItem) {
        streamProbe.reprobeIfStale(item)
        item.alternates.forEach(streamProbe::reprobeIfStale)
    }

    /** The team directory or a #168 identity changed, 2 s after the last change; once at the start (live.18). */
    @OptIn(FlowPreview::class)
    private val directoryChanges: Flow<Unit> =
        SportsLiveSharedBuckets.stampChanges()
            .debounce(REBUCKET_DEBOUNCE_MS)
            .map { }
            .onStart { emit(Unit) }

    /** Only items of catalogs claimed right now, so a profile switch or an exclusion hides stale items at once. */
    override fun liveNow(limit: Int): Flow<List<SportsLiveItem>> =
        combine(
            latest,
            combine(prefs.showHomeRow, enabled) { showHomeRow, enabled -> showHomeRow && enabled },
            claimedCatalogs,
            endedKeys,
            // A team list landing merges copies of one game by their teams (live.18): the row is built again.
            combine(followedTeams, prefs.mySports, directoryChanges) { followed, mySports, _ -> followed to mySports }
        ) { load, shown, claimed, ended, (followed, mySports) ->
            if (load == null || !shown) {
                emptyList()
            } else {
                val keys = claimed.mapTo(HashSet()) { it.key }
                // The user's addon order picks a merged game's primary.
                val addonOrder = claimed.map { it.addon.id }.distinct()
                // The section's own sorting of this load, when it has one (live.20): never a second merge of the same games.
                val live = sharedBuckets.tab(load, keys, addonOrder, SportsLiveTab.LIVE_NOW, nowMs())
                // My sports scopes the row as it does the section (#146, #167): followed teams' games and channels stay;
                // games of no identifiable sport go.
                SportsLiveMySportsScope.scope(SportsLiveItemBucketer.pinFollowed(live, followed), mySports)
                    .filterNot { it.hasEnded(ended) }
                    .take(limit)
            }
        }.flowOn(computeDispatcher)
}
