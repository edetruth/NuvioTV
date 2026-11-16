package com.nuvio.tv.domain.model

enum class SportsLiveKind { EVENTS, CHANNELS, REPLAY }

enum class SportsLiveOverride { INCLUDE, EXCLUDE }

/** Why and how the Sports & Live section claims a catalog; [reason] names the rule that matched. */
data class SportsLiveClaim(val kind: SportsLiveKind, val reason: String)

data class ClaimedCatalog(
    val addon: Addon,
    val catalog: CatalogDescriptor,
    val key: String,
    val kind: SportsLiveKind,
    /** True when the user included this catalog by override rather than a rule claiming it. */
    val viaOverride: Boolean = false
)

enum class SportsLiveTab { LIVE_NOW, UPCOMING, CHANNELS, REPLAYS }

/** A catalog item from a claimed catalog, annotated with what the Sports & Live tabs need. */
data class SportsLiveItem(
    val meta: MetaPreview,
    val addonId: String,
    val addonName: String,
    val addonBaseUrl: String,
    val catalogKey: String,
    val catalogName: String,
    val kind: SportsLiveKind,
    val isLive: Boolean,
    val isChannel: Boolean,
    val startMs: Long?,
    val sport: String?,
    val startHasTimeOfDay: Boolean = false,
    /** True when the item's catalog was included by override; see [ClaimedCatalog.viaOverride]. */
    val viaOverride: Boolean = false,
    /** Channels-tab group (e.g. a country code); null for non-channel items. */
    val group: String? = null,
    /** True for an item its addon marks as finished (a leading ✅); it is shown in no tab. */
    val isFinished: Boolean = false
) {
    val id: String get() = meta.id
    val apiType: String get() = meta.apiType

    /** Identifies the item across loads, for its stream probe; see SportsLiveRepository.endedKeys. */
    val streamKey: String get() = "$addonId|$id"
}
