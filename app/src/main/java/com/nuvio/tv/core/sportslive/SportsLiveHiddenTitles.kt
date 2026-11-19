package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.SportsLiveItem
import java.util.Locale

/**
 * "Hide from Live Now" (#134): titles the viewer took off Live Now (a talk show, a series an addon lists as
 * live). The profile's hidden list stores each title's clean name, as Settings → More → Hidden lists it; a
 * card is hidden when its clean name, or an alternate's, reads the same ignoring case and spacing. Live Now
 * only: Upcoming, Channels and Replays are left alone.
 */
object SportsLiveHiddenTitles {

    /** The name [raw] (an addon title) is stored and listed under: its clean display name. */
    fun titleOf(raw: String): String = SportsLiveDisplayText.name(raw)

    /** What two titles are compared by: the clean name, lower case, single-spaced. */
    fun keyOf(title: String): String = keys(title)

    private val keys = Memo<String, String>(KEY_CACHE_SIZE) { title ->
        titleOf(title).lowercase(Locale.ROOT).split(' ').filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** [hidden] with [title] added (its clean name); unchanged when it is already there. */
    fun add(hidden: Set<String>, title: String): Set<String> {
        val clean = titleOf(title).trim()
        if (clean.isEmpty()) return hidden
        val key = keyOf(clean)
        return if (hidden.any { keyOf(it) == key }) hidden else hidden + clean
    }

    /** [hidden] without [title] (or any entry that reads the same). */
    fun remove(hidden: Set<String>, title: String): Set<String> {
        val key = keyOf(title)
        return hidden.filterTo(LinkedHashSet()) { keyOf(it) != key }
    }

    /** True when [item] (or any of its sources) is hidden. */
    fun isHidden(item: SportsLiveItem, hiddenKeys: Set<String>): Boolean =
        hiddenKeys.isNotEmpty() && (listOf(item) + item.alternates).any { keyOf(it.meta.name) in hiddenKeys }

    /** [items] without the hidden ones; [items] itself when nothing is hidden. */
    fun filter(items: List<SportsLiveItem>, hidden: Set<String>): List<SportsLiveItem> {
        if (hidden.isEmpty()) return items
        val hiddenKeys = hidden.mapTo(HashSet(), ::keyOf)
        return items.filterNot { isHidden(it, hiddenKeys) }
    }

    /** The hidden list as Settings shows it: A–Z, ignoring case. */
    fun sorted(hidden: Set<String>): List<String> = hidden.sortedWith(String.CASE_INSENSITIVE_ORDER)

    private const val KEY_CACHE_SIZE = 2048
}
