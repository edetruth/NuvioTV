package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.ClaimedCatalog
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab

/** Annotates items from claimed catalogs and sorts them into the Sports & Live tabs. */
object SportsLiveItemBucketer {
    /** How long after a timed start an events-catalog item still counts as live. */
    private const val LIVE_WINDOW_MS = 4 * 60 * 60 * 1000L

    private val tabOrder: Comparator<SportsLiveItem> =
        compareByDescending<SportsLiveItem> { it.isLive }
            .thenBy(nullsLast()) { it.startMs }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.meta.name }

    fun describe(meta: MetaPreview, claimed: ClaimedCatalog, nowMs: Long): SportsLiveItem {
        val catalog = claimed.catalog
        val start = SportsLiveTimeParser.parseStart(meta.releaseInfo, meta.released)
        val startMs = start?.startMs
        val liveCatalog = SportsLiveKeywords.containsKeyword(catalog.name, SportsLiveKeywords.LIVE) ||
            SportsLiveKeywords.containsKeyword(catalog.id, SportsLiveKeywords.LIVE)
        val isLive = meta.releaseInfo?.trim().equals("LIVE", ignoreCase = true) ||
            meta.name.contains("LIVE NOW") ||
            meta.description?.contains("LIVE NOW") == true ||
            (liveCatalog && startMs == null) ||
            (claimed.kind == SportsLiveKind.EVENTS && start != null && start.hasTimeOfDay &&
                start.startMs > nowMs - LIVE_WINDOW_MS && start.startMs <= nowMs)
        val description = meta.description.orEmpty()
        val isChannel = claimed.kind == SportsLiveKind.CHANNELS ||
            description.contains("IPTV") ||
            description.contains("Live on")
        return SportsLiveItem(
            meta = meta,
            addonId = claimed.addon.id,
            addonName = claimed.addon.name,
            addonBaseUrl = claimed.addon.baseUrl,
            catalogKey = claimed.key,
            catalogName = catalog.name,
            kind = claimed.kind,
            isLive = isLive,
            isChannel = isChannel,
            startMs = startMs,
            startHasTimeOfDay = start?.hasTimeOfDay == true,
            sport = sportOf(meta, catalog.name),
            viaOverride = claimed.viaOverride,
            group = if (isChannel) SportsLiveChannelGrouper.groupOf(meta, claimed.addon.name) else null
        )
    }

    /**
     * Every tab is present in the result (possibly empty), each sorted live first, then by start, then by name.
     * An addon often lists the same item in several catalogs (Live Now and Today); within a tab it
     * appears once, as its first occurrence in [items].
     */
    fun bucket(items: List<SportsLiveItem>, nowMs: Long): Map<SportsLiveTab, List<SportsLiveItem>> =
        SportsLiveTab.entries.associateWith { tab ->
            items.filter { belongsTo(it, tab, nowMs) }
                .distinctBy { it.addonId to it.id }
                .sortedWith(tabOrder)
        }

    // MetaPreview does not carry the addon's "category" field, so the fallback goes straight to the catalog name.
    fun sportOf(meta: MetaPreview, catalogName: String): String? =
        meta.genres.firstOrNull { it.isNotBlank() }?.trim()
            ?: catalogName.trim().takeIf { it.isNotEmpty() }

    private fun belongsTo(item: SportsLiveItem, tab: SportsLiveTab, nowMs: Long): Boolean {
        val replay = item.kind == SportsLiveKind.REPLAY
        return when (tab) {
            SportsLiveTab.LIVE_NOW -> item.isLive && !replay
            SportsLiveTab.UPCOMING -> !item.isLive && !replay && isUpcoming(item, nowMs)
            SportsLiveTab.CHANNELS -> item.isChannel && !replay
            // A finished event from a catalog the user included by override is shown on demand as a
            // replay rather than dropped; rule-claimed catalogs keep the 4-hour cut-off.
            SportsLiveTab.REPLAYS -> replay ||
                (item.viaOverride && item.kind == SportsLiveKind.EVENTS && !item.isLive && !isUpcoming(item, nowMs))
        }
    }

    // An upcoming-named catalog vouches only for items without a known time of day;
    // a timed start in the past never counts as upcoming. An events item with no start at
    // all is kept as "Time TBA" so a manually included catalog never disappears.
    private fun isUpcoming(item: SportsLiveItem, nowMs: Long): Boolean =
        (item.startMs != null && item.startMs > nowMs) ||
            (item.startMs == null && item.kind == SportsLiveKind.EVENTS) ||
            (!item.startHasTimeOfDay &&
                SportsLiveKeywords.containsKeyword(item.catalogName, SportsLiveKeywords.UPCOMING))
}
