package com.nuvio.tv.domain.repository

import com.nuvio.tv.domain.model.ClaimedCatalog
import com.nuvio.tv.domain.model.SportsLiveItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class SportsLiveLoad(
    val items: List<SportsLiveItem>,
    val failures: List<SportsLiveFailure>,
    val loadedAtMs: Long
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

    /** Loads the first page of every claimed catalog in parallel; a result under 60 s old is reused unless [force]. */
    suspend fun load(force: Boolean = false): SportsLiveLoad

    /** The most recent load, or null before the first one. */
    val latest: StateFlow<SportsLiveLoad?>

    /** Live items from [latest], capped at [limit]; empty when the home row or the section is off. */
    fun liveNow(limit: Int = 20): Flow<List<SportsLiveItem>>
}
