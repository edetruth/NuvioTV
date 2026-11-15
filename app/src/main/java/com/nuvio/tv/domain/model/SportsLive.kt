package com.nuvio.tv.domain.model

enum class SportsLiveKind { EVENTS, CHANNELS, REPLAY }

enum class SportsLiveOverride { INCLUDE, EXCLUDE }

/** Why and how the Sports & Live section claims a catalog; [reason] names the rule that matched. */
data class SportsLiveClaim(val kind: SportsLiveKind, val reason: String)

data class ClaimedCatalog(
    val addon: Addon,
    val catalog: CatalogDescriptor,
    val key: String,
    val kind: SportsLiveKind
)
