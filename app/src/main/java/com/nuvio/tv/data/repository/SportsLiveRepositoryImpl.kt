package com.nuvio.tv.data.repository

import androidx.annotation.VisibleForTesting
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.sportslive.SportsLiveCatalogPolicy
import com.nuvio.tv.core.sportslive.SportsLiveItemBucketer
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ClaimedCatalog
import com.nuvio.tv.domain.model.SportsLiveItem
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        const val CATALOG_TIMEOUT_MS = 15_000L
    }

    @VisibleForTesting
    internal var nowMs: () -> Long = { System.currentTimeMillis() }

    @VisibleForTesting
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    private val _latest = MutableStateFlow<SportsLiveLoad?>(null)
    private val loadMutex = Mutex()

    /** The most recent load, whether or not every catalog succeeded. */
    override val latest: StateFlow<SportsLiveLoad?> = _latest.asStateFlow()

    override val enabled: Flow<Boolean> = prefs.showSection

    override val claimedCatalogs: Flow<List<ClaimedCatalog>> = combine(
        enabled,
        addonRepository.getInstalledAddons().map { it.enabledAddons() },
        prefs.overrides
    ) { enabled, addons, overrides ->
        if (!enabled) return@combine emptyList()
        addons.flatMap { addon ->
            addon.catalogs.mapNotNull { catalog ->
                SportsLiveCatalogPolicy.classify(addon, catalog, overrides)?.let {
                    ClaimedCatalog(
                        addon = addon,
                        catalog = catalog,
                        key = SportsLiveCatalogPolicy.keyFor(addon, catalog),
                        kind = it.kind,
                        viaOverride = it.reason == SportsLiveCatalogPolicy.REASON_INCLUDE
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
     * follow the active profile and the overrides) did not change while it was fetching, so a load
     * started for one profile never overwrites the cache after a switch to another.
     */
    override suspend fun load(force: Boolean): SportsLiveLoad = loadMutex.withLock {
        val claimed = claimedCatalogs.first()
        val claimedKeys = claimed.map { it.key }.toSet()
        val cached = _latest.value
        if (!force && cached != null && nowMs() - cached.loadedAtMs < CACHE_MS && cached.claimedKeys == claimedKeys) {
            return@withLock cached
        }

        val results = coroutineScope {
            claimed.map { catalog -> async(ioDispatcher) { catalog to fetchFirstPage(catalog) } }.awaitAll()
        }
        val now = nowMs()
        val items = mutableListOf<SportsLiveItem>()
        val failures = mutableListOf<SportsLiveFailure>()
        for ((catalog, result) in results) {
            when (result) {
                is NetworkResult.Success ->
                    result.data.items.mapTo(items) { SportsLiveItemBucketer.describe(it, catalog, now) }
                is NetworkResult.Error -> failures += SportsLiveFailure(catalog, result.message)
                NetworkResult.Loading -> Unit
            }
        }
        val load = SportsLiveLoad(items, failures, loadedAtMs = now, claimedKeys = claimedKeys)
        if (claimedCatalogKeys.first() == claimedKeys) _latest.value = load
        load
    }

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
                    supportsSkip = false
                ).filter { it !is NetworkResult.Loading }.first()
            } ?: NetworkResult.Error("Timed out")
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            NetworkResult.Error(error.message ?: error::class.java.simpleName)
        }
    }

    override val endedKeys: StateFlow<Set<String>> = streamProbe.ended

    override fun reprobeIfStale(item: SportsLiveItem) = streamProbe.reprobeIfStale(item)

    /** Only items of catalogs claimed right now, so a profile switch or an exclusion hides stale items at once. */
    override fun liveNow(limit: Int): Flow<List<SportsLiveItem>> =
        combine(latest, prefs.showHomeRow, enabled, claimedCatalogKeys, endedKeys) { load, showHomeRow, enabled, keys, ended ->
            if (load == null || !showHomeRow || !enabled) {
                emptyList()
            } else {
                val items = load.items.filter { it.catalogKey in keys }
                SportsLiveItemBucketer.bucket(items, nowMs())[SportsLiveTab.LIVE_NOW].orEmpty()
                    .filterNot { it.streamKey in ended }
                    .take(limit)
            }
        }
}
