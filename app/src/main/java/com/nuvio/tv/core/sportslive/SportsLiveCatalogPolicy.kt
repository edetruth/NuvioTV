package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.SportsLiveClaim
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveOverride
import com.nuvio.tv.domain.model.catalogRowLegacyKey

/** Decides which addon catalogs the Sports & Live section claims, and as what kind. */
object SportsLiveCatalogPolicy {
    /** The claim reason of a catalog the user included by override. */
    const val REASON_INCLUDE = "override:include"

    private val CLAIMED_TYPES = setOf("sport", "sports", "tv", "channel")
    private val CATALOG_RULE_TYPES = setOf(ContentType.MOVIE, ContentType.SERIES, ContentType.UNKNOWN)

    fun keyFor(addon: Addon, catalog: CatalogDescriptor): String =
        catalogRowLegacyKey(addon.id, catalog.apiType, catalog.id)

    /** False for a catalog with a required extra: the section loads first pages without extras. */
    fun isClaimable(catalog: CatalogDescriptor): Boolean =
        catalog.extra.none { it.isRequired } && catalog.extraRequired.isEmpty()

    /**
     * A catalog with a required extra is never claimed, since the section loads first pages
     * without extras. Otherwise precedence: override, then type rule, then addon-name rule,
     * then catalog name/id rule. Addon descriptions are never matched.
     */
    fun classify(
        addon: Addon,
        catalog: CatalogDescriptor,
        overrides: Map<String, SportsLiveOverride>
    ): SportsLiveClaim? {
        if (!isClaimable(catalog)) return null
        val reason = when (overrides[keyFor(addon, catalog)]) {
            SportsLiveOverride.EXCLUDE -> return null
            SportsLiveOverride.INCLUDE -> REASON_INCLUDE
            null -> matchReason(addon, catalog) ?: return null
        }
        return SportsLiveClaim(kindOf(addon, catalog), reason)
    }

    private fun matchReason(addon: Addon, catalog: CatalogDescriptor): String? {
        val apiType = catalog.apiType.lowercase()
        if (apiType in CLAIMED_TYPES) return "type:$apiType"
        SportsLiveKeywords.findKeyword(addon.name, SportsLiveKeywords.SPORTS)?.let { return "addon:$it" }
        if (catalog.type !in CATALOG_RULE_TYPES) return null
        val catalogKeyword = SportsLiveKeywords.findKeyword(catalog.name, SportsLiveKeywords.SPORTS)
            ?: SportsLiveKeywords.findKeyword(catalog.id, SportsLiveKeywords.SPORTS)
        return catalogKeyword?.let { "catalog:$it" }
    }

    private fun kindOf(addon: Addon, catalog: CatalogDescriptor): SportsLiveKind {
        if (catalog.apiType.equals("channel", ignoreCase = true)) return SportsLiveKind.CHANNELS
        val isReplay = catalog.id.startsWith("recap", ignoreCase = true) ||
            listOf(addon.name, catalog.name, catalog.id).any {
                SportsLiveKeywords.containsKeyword(it, SportsLiveKeywords.REPLAY)
            }
        if (isReplay) return SportsLiveKind.REPLAY
        val isChannels = listOf(catalog.name, catalog.id).any {
            SportsLiveKeywords.containsKeyword(it, SportsLiveKeywords.CHANNELS)
        } || isLiveTv(catalog) || isNetworks(catalog) ||
            // live.24: a tv catalog named as a channel list ("<Brand> IPTV", "<Brand> TV +").
            (catalog.apiType.equals("tv", ignoreCase = true) && SportsLiveChannelCatalogs.namesChannels(catalog.name))
        return if (isChannels) SportsLiveKind.CHANNELS else SportsLiveKind.EVENTS
    }

    // "Networks" (ABC, A&E, AMC...) or a bare "TV" lists channels (#145); "Network Events" would still list events.
    private fun isNetworks(catalog: CatalogDescriptor): Boolean =
        listOf(catalog.name, catalog.id).any {
            SportsLiveKeywords.containsKeyword(it, SportsLiveKeywords.NETWORKS) ||
                it.trim().lowercase() in SportsLiveKeywords.GENERIC_CHANNEL_CATALOGS
        } && !SportsLiveKeywords.containsKeyword(catalog.name, SportsLiveKeywords.EVENTS)

    // "EasyPlay Live TV (tv)" lists channels; "Live TV Events" would still list events.
    private fun isLiveTv(catalog: CatalogDescriptor): Boolean =
        listOf(catalog.name, catalog.id).any { SportsLiveKeywords.containsKeyword(it, SportsLiveKeywords.LIVE_TV) } &&
            !SportsLiveKeywords.containsKeyword(catalog.name, SportsLiveKeywords.EVENTS)
}
