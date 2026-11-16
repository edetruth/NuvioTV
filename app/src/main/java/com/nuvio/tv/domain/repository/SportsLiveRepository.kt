package com.nuvio.tv.domain.repository

import com.nuvio.tv.domain.model.ClaimedCatalog
import com.nuvio.tv.domain.model.SportsLiveItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class SportsLiveLoad(
    val items: List<SportsLiveItem>,
    val failures: List<SportsLiveFailure>,
    val loadedAtMs: Long,
    /** Keys of the catalogs this load fetched. */
    val claimedKeys: Set<String>
)

data class SportsLiveFailure(val catalog: ClaimedCatalog, val message: String)

interface SportsLiveRepository {
    /** The Sports & Live master switch. */
    val enabled: Flow<Boolean>

    /** Catalogs of enabled addons that the section claims; empty when disabled. */
    val claimedCatalogs: Flow<List<ClaimedCatalog>>

    /** Keys of [claimedCatalogs]. */
    val claimedCatalogKeys: Flow<Set<String>>

    /** True when the section is enabled and claims at least one catalog. */
    val sectionAvailable: Flow<Boolean>

    /**
     * Loads the first page of every claimed catalog in parallel. A result under 60 s old is
     * reused unless [force] or the claimed catalogs have changed since it was loaded.
     * Without [includeChannels], CHANNELS catalogs the previous load fetched successfully are not
     * refetched; their items are carried over (they are large and rarely change).
     */
    suspend fun load(force: Boolean = false, includeChannels: Boolean = true): SportsLiveLoad

    /** The most recent load, whether or not every catalog succeeded. */
    val latest: StateFlow<SportsLiveLoad?>

    /**
     * Keys (`addonId|id`) of live items whose streams are gone: a probe of the focused item (or a
     * reported stream fetch) found no playable stream. They are left out of Live now everywhere.
     */
    val endedKeys: StateFlow<Set<String>>

    /** Re-probes a focused Live now [item] in the background when its probe result is over 2 minutes old. */
    fun reprobeIfStale(item: SportsLiveItem)

    /** Live items from [latest] that are not ended, capped at [limit]; empty when the home row or the section is off. */
    fun liveNow(limit: Int = 20): Flow<List<SportsLiveItem>>
}
