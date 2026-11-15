package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.ClaimedCatalog
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab

/** Annotates items from claimed catalogs and sorts them into the Sports & Live tabs. */
object SportsLiveItemBucketer {
    private const val STARTED_GRACE_MS = 10 * 60 * 1000L

    private val tabOrder: Comparator<SportsLiveItem> =
        compareByDescending<SportsLiveItem> { it.isLive }
            .thenBy(nullsLast()) { it.startMs }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.meta.name }

    fun describe(meta: MetaPreview, claimed: ClaimedCatalog, nowMs: Long): SportsLiveItem {
        val catalog = claimed.catalog
        val startMs = SportsLiveTimeParser.parseStartMs(meta.releaseInfo, meta.released, nowMs)
        val liveCatalog = SportsLiveKeywords.containsKeyword(catalog.name, SportsLiveKeywords.LIVE) ||
            SportsLiveKeywords.containsKeyword(catalog.id, SportsLiveKeywords.LIVE)
        val isLive = meta.releaseInfo?.trim().equals("LIVE", ignoreCase = true) ||
            meta.name.contains("LIVE NOW", ignoreCase = true) ||
            meta.description?.contains("LIVE NOW", ignoreCase = true) == true ||
            (liveCatalog && startMs == null) ||
            (claimed.kind == SportsLiveKind.EVENTS && startMs != null && startMs < nowMs - STARTED_GRACE_MS)
        val description = meta.description.orEmpty()
        val isChannel = claimed.kind == SportsLiveKind.CHANNELS ||
            description.contains("IPTV", ignoreCase = true) ||
            description.contains("Live on", ignoreCase = true)
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
            sport = sportOf(meta, catalog.name)
        )
    }

    /** Every tab is present in the result (possibly empty), each sorted live first, then by start, then by name. */
    fun bucket(items: List<SportsLiveItem>, nowMs: Long): Map<SportsLiveTab, List<SportsLiveItem>> =
        SportsLiveTab.entries.associateWith { tab ->
            items.filter { belongsTo(it, tab, nowMs) }.sortedWith(tabOrder)
        }

    // MetaPreview does not carry the addon's "category" field, so the fallback goes straight to the catalog name.
    fun sportOf(meta: MetaPreview, catalogName: String): String? =
        meta.genres.firstOrNull { it.isNotBlank() }?.trim()
            ?: catalogName.trim().takeIf { it.isNotEmpty() }

    private fun belongsTo(item: SportsLiveItem, tab: SportsLiveTab, nowMs: Long): Boolean {
        val replay = item.kind == SportsLiveKind.REPLAY
        return when (tab) {
            SportsLiveTab.LIVE_NOW -> item.isLive && !replay
            SportsLiveTab.UPCOMING -> !item.isLive && !replay &&
                ((item.startMs != null && item.startMs > nowMs) ||
                    SportsLiveKeywords.containsKeyword(item.catalogName, SportsLiveKeywords.UPCOMING))
            SportsLiveTab.CHANNELS -> item.isChannel && !replay
            SportsLiveTab.REPLAYS -> replay
        }
    }
}
