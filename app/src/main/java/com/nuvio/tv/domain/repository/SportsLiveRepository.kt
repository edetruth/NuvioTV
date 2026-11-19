package com.nuvio.tv.domain.repository

import com.nuvio.tv.domain.model.CatalogOption
import com.nuvio.tv.domain.model.CatalogStats
import com.nuvio.tv.domain.model.ClaimedCatalog
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class SportsLiveLoad(
    val items: List<SportsLiveItem>,
    val failures: List<SportsLiveFailure>,
    val loadedAtMs: Long,
    /** Keys of the catalogs this load fetched. */
    val claimedKeys: Set<String>,
    /** The pinned option each catalog was loaded with, by catalog key; catalogs loaded with All are absent. */
    val catalogOptions: Map<String, CatalogOption> = emptyMap(),
    /** The sources (addon id → display name) this load asked, not carried over (#170). */
    val fetchedSources: Map<String, String> = emptyMap(),
    /** Every claimed source's addon id (#170). */
    val claimedSources: Set<String> = emptySet()
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
     * reused unless [force] or the claimed catalogs or their pinned options have changed since it was
     * loaded. A catalog with a pinned option is asked only for items matching it.
     * Without [includeChannels], CHANNELS catalogs the previous load fetched with items, for the same
     * claimed catalogs, addon installs and pinned option, are not refetched; their items are carried
     * over (they are large and rarely change). With [onlyKinds] (#71), only catalogs of those kinds are
     * fetched: the others are carried over the same way (a catalog the cached load has no items for is
     * fetched), so a tab's refresh asks only for the catalogs it shows.
     */
    suspend fun load(
        force: Boolean = false,
        includeChannels: Boolean = true,
        onlyKinds: Set<SportsLiveKind>? = null
    ): SportsLiveLoad

    /** The most recent load, whether or not every catalog succeeded. */
    val latest: StateFlow<SportsLiveLoad?>

    /** How the items of each catalog the last published load got (by catalog key) were recognised; in memory only. */
    val catalogStats: StateFlow<Map<String, CatalogStats>>

    /**
     * Keys (`addonId|id`) of live items whose streams are gone: a probe of the focused item (or a
     * reported stream fetch) found no playable stream. That never means the game is over (#145): Live now
     * keeps them in place, dimmed as "no streams yet"; zapping and the dual-screen picker skip them, and the
     * Home row leaves them out.
     */
    val endedKeys: StateFlow<Set<String>>

    /** Re-probes a focused Live now [item] in the background when its probe result is over 2 minutes old. */
    fun reprobeIfStale(item: SportsLiveItem)

    /** True when links were found recently for [item] or one of its alternates (a probe, preview or play). */
    fun hasRecentLinks(item: SportsLiveItem): Boolean

    /** Forgets every ended verdict (a manual refresh), so those games show and are checked again on focus. */
    fun clearEnded()

    /** The active profile's followed teams; their games are pinned first in Live Now, Upcoming and Replays. */
    val followedTeams: Flow<List<FollowedTeam>>

    /** Follows [team] for the active profile; a team already followed (same name and sport) is not added again. */
    suspend fun follow(team: FollowedTeam)

    /** Stops following [team] (same name and sport, ignoring case). */
    suspend fun unfollow(team: FollowedTeam)

    /**
     * Live items from [latest] that are not ended, followed teams' games first, capped at [limit];
     * empty when the home row or the section is off.
     */
    fun liveNow(limit: Int = 20): Flow<List<SportsLiveItem>>
}
