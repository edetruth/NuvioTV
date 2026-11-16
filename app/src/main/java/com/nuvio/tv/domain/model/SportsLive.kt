package com.nuvio.tv.domain.model

import com.nuvio.tv.core.sportslive.TeamPair

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
    val viaOverride: Boolean = false,
    /** The pinned extra value the catalog is loaded with; null loads everything (All). */
    val option: CatalogOption? = null
)

/** A team the user follows; [sport] (when known at follow time) scopes it, so a namesake in another sport does not match. */
data class FollowedTeam(val name: String, val sport: String? = null)

/** A pinned catalog extra value, e.g. `genre` = "United States". */
data class CatalogOption(val extra: String, val value: String)

enum class SportsLiveTab { LIVE_NOW, UPCOMING, CHANNELS, REPLAYS }

/**
 * How a catalog's items were recognised in a load. Each item that is not finished counts once, in the
 * first of: live (in Live Now), channel, replay, timed (upcoming with a start), upcoming "Time TBA",
 * ended (a past start in no tab), unrecognised (in no tab).
 */
data class CatalogStats(
    val live: Int = 0,
    val timed: Int = 0,
    val upcomingTba: Int = 0,
    val channels: Int = 0,
    val replays: Int = 0,
    val unrecognised: Int = 0,
    /** Timed events whose start is past the live window (not counted as unrecognised). */
    val ended: Int = 0
)

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
    val isFinished: Boolean = false,
    /** The two sides when the name is a game ("A vs B"); null for channels and other names. */
    val teams: TeamPair? = null,
    /** True when a followed team plays in it; set where the followed teams are known, not by describe. */
    val followed: Boolean = false,
    /** Other addons' copies of the same game (see SportsLiveItemBucketer.merge); this item is the primary. */
    val alternates: List<SportsLiveItem> = emptyList()
) {
    val id: String get() = meta.id
    val apiType: String get() = meta.apiType

    /** Identifies the item across loads, for its stream probe; see SportsLiveRepository.endedKeys. */
    val streamKey: String get() = "$addonId|$id"

    /** Addons this card plays from: 1 plus the [alternates]. */
    val sourceCount: Int get() = 1 + alternates.size

    /** True when this item and every alternate are in [endedKeys] (ended keys are per source). */
    fun hasEnded(endedKeys: Set<String>): Boolean =
        streamKey in endedKeys && alternates.all { it.streamKey in endedKeys }
}
