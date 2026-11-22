package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.SportsLiveItem
import java.util.Locale

/**
 * A channel remembered for Favorites or Recent (Channels phase 1): its addon, its id in that addon, and its [name] key
 * ([SportsLiveChannelFavorites.nameKey]), which finds it again when the addon gives it a new id. A pick synced from
 * another TV has neither addon nor id (blank: only the name key leaves a TV) and is found by name in any addon.
 * [playedAt]: when a recent pick was played (0: unknown, or a favorite), which the account sync merges Recent by.
 */
data class SportsLiveChannelRef(val addonId: String, val id: String, val name: String, val playedAt: Long = 0L)

/**
 * A profile's channel picks: [favorites] in the order added, [recent] plays newest first, and [plays] (phase 2: when
 * each channel was played in the last 30 days, by [SportsLiveChannelList.mergeKey]; Popular now reads them, on this TV only).
 */
data class SportsLiveChannelPicks(
    val favorites: List<SportsLiveChannelRef> = emptyList(),
    val recent: List<SportsLiveChannelRef> = emptyList(),
    val plays: Map<String, List<Long>> = emptyMap()
) {
    companion object {
        val NONE = SportsLiveChannelPicks()
    }
}

/**
 * What Channels reads for its top sections: the picks in [order] as they were when the tab was opened or refreshed
 * (so nothing moves under focus), and the [favorites] as they are now (the gold ★, and the menu's Add / Remove).
 */
data class SportsLiveChannelLists(
    val order: SportsLiveChannelPicks = SportsLiveChannelPicks.NONE,
    val favorites: List<SportsLiveChannelRef> = emptyList(),
    /** Phase 2: the browse view shown (null: the tab's own sections). */
    val browse: SportsLiveChannelBrowse? = null,
    /** Phase 2: the countries chosen ([SportsLiveChannelCountries.ALL] for all); null: the viewer's own. */
    val countries: Set<String>? = null,
    /** Phase 2: this TV's region ([SportsLiveChannelCountries.tvRegion]). */
    val tvRegion: String? = null
) {
    companion object {
        val NONE = SportsLiveChannelLists()
    }
}

/**
 * Channel favorites and recent plays (Channels redesign phase 1): pure rules, stored per profile by
 * [com.nuvio.tv.data.local.SportsLivePreferencesDataStore]. A pick is found again in a new load by its addon and id;
 * when that id is gone, by its name key in the same addon (an addon that renumbers its channels); never in another
 * addon, whose copy is a different stream. A pick found nowhere is hidden, and kept.
 */
object SportsLiveChannelFavorites {

    /** The section of the favorite rows on Channels (never an addon's text, as [SportsLiveChannelRegions.OTHER]). */
    const val FAVORITES = "\u0000section:favorites"

    /** The section of the recent rows on Channels. */
    const val RECENT = "\u0000section:recent"

    /** Recent rows shown on Channels. */
    const val RECENT_SHOWN = 3

    /** Recent plays kept: more than shown, so a favorite or a channel gone from the catalogs leaves room for others. */
    const val RECENT_KEPT = 10

    /** Whether [section] is one of the picks' sections (always headed, never a region). */
    fun isPickSection(section: String?): Boolean = section == FAVORITES || section == RECENT

    /**
     * [raw]'s name key: its clean name ([SportsLiveDisplayText]) with its region, lower case, quality and symbols left
     * out ("US: ESPN FHD" and "US | ESPN HD" are one key; "UK: ESPN" another).
     */
    fun nameKey(raw: String): String {
        val title = SportsLiveDisplayText.of(raw)
        return listOfNotNull(title.region, title.name).joinToString(" ").lowercase(Locale.ROOT).trim()
    }

    fun refOf(item: SportsLiveItem): SportsLiveChannelRef = SportsLiveChannelRef(item.addonId, item.id, nameKey(item.meta.name))

    /**
     * [picks] with [item] added to the favorites (last), or, when it shows as a favorite now ([favorite]), removed:
     * the entry with its id, else the ones it was found by name.
     */
    fun toggle(picks: SportsLiveChannelPicks, item: SportsLiveItem, favorite: Boolean): SportsLiveChannelPicks {
        val favorites = picks.favorites
        if (!favorite) {
            if (favorites.any { it.addonId == item.addonId && it.id == item.id }) return picks
            return picks.copy(favorites = favorites + refOf(item))
        }
        val exact = favorites.filterNot { it.addonId == item.addonId && it.id == item.id }
        if (exact.size < favorites.size) return picks.copy(favorites = exact)
        val name = nameKey(item.meta.name)
        return picks.copy(favorites = favorites.filterNot { (it.addonId == item.addonId || it.addonId.isEmpty()) && it.name == name })
    }

    /**
     * [picks] with [item] played at [nowMs]: first in recent, once, at most [RECENT_KEPT]; and one more play for it,
     * plays older than 30 days dropped, at most [PLAYS_PER_CHANNEL] per channel and [PLAYS_CHANNELS] channels.
     */
    fun played(picks: SportsLiveChannelPicks, item: SportsLiveItem, nowMs: Long = System.currentTimeMillis()): SportsLiveChannelPicks {
        val ref = refOf(item).copy(playedAt = nowMs)
        // A synced pick of the same name (no addon) is this play too.
        val rest = picks.recent.filterNot { (it.addonId == ref.addonId && it.id == ref.id) || (it.addonId.isEmpty() && it.name == ref.name) }
        val key = SportsLiveChannelList.mergeKey(item)
        val since = nowMs - SportsLiveChannelList.PLAYS_WINDOW_MS
        val plays = (picks.plays + (key to (picks.plays[key].orEmpty() + nowMs)))
            .mapValues { (_, times) -> times.filter { it > since }.takeLast(PLAYS_PER_CHANNEL) }
            .filterValues { it.isNotEmpty() }
            .entries.sortedByDescending { it.value.last() }.take(PLAYS_CHANNELS)
            .associate { it.key to it.value }
        return picks.copy(recent = (listOf(ref) + rest).take(RECENT_KEPT), plays = plays)
    }

    const val PLAYS_PER_CHANNEL = 30
    const val PLAYS_CHANNELS = 100

    /**
     * The channels of [channels] that [refs] name, in [refs]' order, each once: by addon and id, else by name key in
     * the same addon (the first such channel listed); a synced ref (no addon) by name key in any addon, a merged
     * channel's copies too. A ref found in neither way is left out.
     */
    fun resolve(refs: List<SportsLiveChannelRef>, channels: List<SportsLiveItem>): List<SportsLiveItem> {
        if (refs.isEmpty() || channels.isEmpty()) return emptyList()
        val byKey = HashMap<String, SportsLiveItem>(channels.size * 2)
        channels.forEach { byKey.putIfAbsent(it.streamKey, it) }
        // A merged channel (phase 2) is found by any of its copies.
        channels.forEach { channel -> channel.alternates.forEach { byKey.putIfAbsent(it.streamKey, channel) } }
        // Built only for an addon a ref is not found in by id: name keys cost a parse per channel.
        val byName = HashMap<String, Map<String, SportsLiveItem>>()
        fun namesOf(addonId: String): Map<String, SportsLiveItem> = byName.getOrPut(addonId) {
            val names = HashMap<String, SportsLiveItem>()
            channels.forEach { if (addonId.isEmpty() || it.addonId == addonId) names.putIfAbsent(nameKey(it.meta.name), it) }
            if (addonId.isEmpty()) channels.forEach { channel -> channel.alternates.forEach { names.putIfAbsent(nameKey(it.meta.name), channel) } }
            names
        }
        val found = LinkedHashMap<String, SportsLiveItem>()
        refs.forEach { ref ->
            val item = byKey["${ref.addonId}|${ref.id}"] ?: namesOf(ref.addonId)[ref.name] ?: return@forEach
            found.putIfAbsent(item.streamKey, item)
        }
        return found.values.toList()
    }

    /** Channels split for the list: the favorites, up to [RECENT_SHOWN] recent that are not favorites, then the rest as listed. */
    data class Sections(val favorites: List<SportsLiveItem>, val recent: List<SportsLiveItem>, val rest: List<SportsLiveItem>)

    /** [channels] split by [order] ([Sections]). */
    fun sections(channels: List<SportsLiveItem>, order: SportsLiveChannelPicks): Sections {
        if (order.favorites.isEmpty() && order.recent.isEmpty()) return Sections(emptyList(), emptyList(), channels)
        val favorites = resolve(order.favorites, channels)
        val favoriteKeys = favorites.mapTo(HashSet()) { it.streamKey }
        val recent = resolve(order.recent, channels).filterNot { it.streamKey in favoriteKeys }.take(RECENT_SHOWN)
        val top = favoriteKeys + recent.map { it.streamKey }
        return Sections(favorites, recent, channels.filterNot { it.streamKey in top })
    }
}
