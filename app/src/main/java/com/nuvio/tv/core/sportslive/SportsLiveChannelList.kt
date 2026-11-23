package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.identify.TeamIdentityRules
import com.nuvio.tv.domain.model.SportsLiveItem

/** What Channels' browse view lists (phase 2): every channel, sports channels, or all of Popular now. */
enum class SportsLiveChannelBrowseKind { ALL, SPORTS, POPULAR }

/** Channels' browse view (phase 2): its [kind] and the Find a channel [query] ("" for none). */
data class SportsLiveChannelBrowse(val kind: SportsLiveChannelBrowseKind, val query: String = "")

/**
 * Channels phase 2's list rules, pure and generic: one row per channel across addons ([merge]), Popular now
 * ([popular]: brand family and feed, the viewer's countries, the curated tiers and this TV's own plays, links that
 * all failed left out), the A to Z browse list with its letters ([letterOf], [sortedAZ], [jump]) and Find a channel
 * ([matches]).
 */
object SportsLiveChannelList {

    /** Popular rows on the Channels tab. */
    const val POPULAR_SHOWN = 12

    /** Feeds of one brand family among the Popular rows on the tab, so no brand fills it ("FOX 5", "FOX 9", ...). */
    const val FEEDS_PER_FAMILY = 3

    /** Plays this many times in [PLAYS_WINDOW_MS] lift a channel within Popular (§4.4). */
    const val PLAYS_TO_RISE = 2

    const val PLAYS_WINDOW_MS = 30L * 24 * 60 * 60 * 1000

    /** The section of the Popular now rows. */
    const val POPULAR = "\u0000section:popular"

    /** A browse letter's section: [LETTER] then the letter ("A", "#"). */
    const val LETTER = "\u0000letter:"

    /** One channel across addons and copies: its country and its clean name as a key ("US|espn"). Also the plays key. */
    fun mergeKey(item: SportsLiveItem): String =
        "${SportsLiveChannelCountries.countryOf(item)}|${TeamIdentityRules.nameKey(SportsLiveDisplayText.name(item.meta.name))}"

    /**
     * [channels] with each channel listed once (§5 phase 2.2): copies with one [mergeKey] (other addons', or the
     * addon's own "HD" / "FHD") become the first one's alternates, so Choose stream lists every link. Order as listed.
     */
    fun merge(channels: List<SportsLiveItem>): List<SportsLiveItem> {
        val groups = LinkedHashMap<String, MutableList<SportsLiveItem>>()
        channels.forEach { groups.getOrPut(mergeKey(it)) { mutableListOf() } += it }
        return groups.values.map { copies ->
            if (copies.size == 1) {
                copies[0]
            } else {
                val primary = copies[0]
                val others = copies.drop(1).flatMap { listOf(it.copy(alternates = emptyList())) + it.alternates }
                primary.copy(alternates = primary.alternates + others)
            }
        }
    }

    /** [item]'s links that ran this session all failed (the "Didn't work" marks, §4.5). */
    fun deadByMarks(item: SportsLiveItem): Boolean = (listOf(item) + item.alternates).all { copy ->
        val (failed, played) = SportsLiveLinkMarks.shared.counts(copy.id)
        failed.values.sum() > 0 && played == 0
    }

    /** [plays] (plays key to times) counted for [keys] since [nowMs] − [PLAYS_WINDOW_MS]. */
    fun playCount(plays: Map<String, List<Long>>, keys: Collection<String>, nowMs: Long): Int =
        keys.distinct().sumOf { key -> plays[key].orEmpty().count { it > nowMs - PLAYS_WINDOW_MS && it <= nowMs } }

    /**
     * Popular now (§4): of [channels] (merged), those of a listed brand ([SportsLiveChannelBrands]) in the [selected]
     * countries (null: every country; a channel with no country only for an international brand or one made for a
     * selected country), one row per family and feed ([SportsLiveBrandFeed.rowKey]) whose links did not all fail
     * ([dead]). A row is its variant with a country first, the others its alternates. Order: channels played
     * [PLAYS_TO_RISE] times or more in 30 days ([plays]) first, most played first, then tier, the list's rank and feed.
     * [perFamily] caps the feeds of one family (null: no cap).
     */
    fun popular(
        channels: List<SportsLiveItem>,
        selected: Set<String>?,
        plays: Map<String, List<Long>>,
        nowMs: Long,
        dead: (SportsLiveItem) -> Boolean = ::deadByMarks,
        perFamily: Int? = FEEDS_PER_FAMILY
    ): List<SportsLiveItem> {
        class Row(val brand: SportsLiveBrandFeed, val item: SportsLiveItem, val score: Int)
        val groups = LinkedHashMap<String, MutableList<Pair<SportsLiveBrandFeed, SportsLiveItem>>>()
        for (channel in channels) {
            val brand = SportsLiveChannelBrands.familyOf(channel.meta.name) ?: continue
            if (!SportsLiveChannelCountries.listed(channel, selected)) continue
            val country = SportsLiveChannelCountries.countryOf(channel)
            if (selected != null && country == SportsLiveChannelRegions.OTHER && brand.family.homes.isNotEmpty() &&
                brand.family.homes.none { it in selected }
            ) continue
            groups.getOrPut(brand.rowKey) { mutableListOf() } += brand to channel
        }
        val rows = groups.values.mapNotNull { group ->
            val alive = group.filterNot { (_, item) -> dead(item) }
            if (alive.isEmpty()) return@mapNotNull null
            val ordered = alive.sortedBy { (_, item) -> countryRank(item) } +
                group.filter { it !in alive }
            val primary = ordered.first().second
            val others = ordered.drop(1).flatMap { (_, item) -> listOf(item.copy(alternates = emptyList())) + item.alternates }
            val item = if (others.isEmpty()) primary else primary.copy(alternates = primary.alternates + others)
            val score = playCount(plays, (listOf(item) + item.alternates).map(::mergeKey), nowMs)
            Row(ordered.first().first, item, score)
        }
        val sorted = rows.sortedWith(
            compareBy<Row>(
                { if (it.score >= PLAYS_TO_RISE) -it.score else 0 },
                { it.brand.family.tier },
                { it.brand.family.rank },
                { SportsLiveChannelBrands.feedOrder(it.brand.feed).first },
                { SportsLiveChannelBrands.feedOrder(it.brand.feed).second }
            )
        )
        val capped = if (perFamily == null) {
            sorted
        } else {
            val perKey = HashMap<String, Int>()
            sorted.filter { row ->
                val seen = (perKey[row.brand.family.key] ?: 0) + 1
                perKey[row.brand.family.key] = seen
                seen <= perFamily
            }
        }
        return capped.map { it.item }
    }

    // A row's primary: a variant with a written country, then one whose country its network implies (live.24), then none.
    private fun countryRank(item: SportsLiveItem): Int = when {
        SportsLiveChannelCountries.countryOf(item) == SportsLiveChannelRegions.OTHER -> 2
        SportsLiveChannelRegions.inferred(item) -> 1
        else -> 0
    }

    /** A sports channel for the browse view's Sports only: a sports brand of either list, or a sport word in its name. */
    fun isSports(item: SportsLiveItem): Boolean =
        SportsLiveBroadcasters.isSportsChannel(item.meta.name) || SportsLiveChannelBrands.familyOf(item.meta.name)?.family?.tier.let { it == 1 || it == 3 }

    /** [item]'s browse letter: the first letter of its clean name, accents folded, A to Z; "#" for anything else. */
    fun letterOf(item: SportsLiveItem): String {
        val first = TeamIdentityRules.nameKey(SportsLiveDisplayText.name(item.meta.name)).firstOrNull() ?: return "#"
        return if (first in 'a'..'z') first.uppercaseChar().toString() else "#"
    }

    /** [items] A to Z by clean name, "#" (digits, symbols) first. */
    fun sortedAZ(items: List<SportsLiveItem>): List<SportsLiveItem> =
        items.map { Triple(it, letterOf(it), TeamIdentityRules.nameKey(SportsLiveDisplayText.name(it.meta.name))) }
            .sortedWith(compareBy({ it.second != "#" }, { it.third }))
            .map { it.first }

    /**
     * ◂ ▸ on the row at [index] of a list whose rows have [letters] (phase 2): ▸ the first row of the next letter, ◂ the
     * first row of the letter before the row's own; null at either end.
     */
    fun jump(letters: List<String>, index: Int, forward: Boolean): Int? {
        val current = letters.getOrNull(index) ?: return null
        return if (forward) {
            (index + 1 until letters.size).firstOrNull { letters[it] != current }
        } else {
            val start = (index downTo 0).takeWhile { letters[it] == current }.last()
            if (start == 0) return null
            val previous = letters[start - 1]
            (start - 1 downTo 0).takeWhile { letters[it] == previous }.last()
        }
    }

    /**
     * The letters round the row at [index]: the one before, its own and the next (the big letter at the right edge).
     * [letters] may be the rows' sections: each is shown as its letter, without the [LETTER] prefix (#220).
     */
    fun around(letters: List<String>, index: Int): Triple<String?, String, String?>? {
        val current = letters.getOrNull(index) ?: return null
        val before = (index downTo 0).map { letters[it] }.firstOrNull { it != current }
        val after = (index until letters.size).map { letters[it] }.firstOrNull { it != current }
        return Triple(before?.removePrefix(LETTER), current.removePrefix(LETTER), after?.removePrefix(LETTER))
    }

    /** Find a channel: [item]'s clean name, or its brand family, holds every word of [query]. */
    fun matches(item: SportsLiveItem, query: String): Boolean {
        val words = TeamIdentityRules.nameKey(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val name = TeamIdentityRules.nameKey(SportsLiveDisplayText.name(item.meta.name))
        val brand = SportsLiveChannelBrands.familyOf(item.meta.name)?.rowKey.orEmpty()
        return words.all { word -> name.contains(word) || brand.contains(word) }
    }
}

/**
 * What Channels shows besides its rows (phase 2): the [view] ([viewKey]); [fallback] when the tab had no favorite,
 * recent or popular channel and lists every channel A to Z instead; the counts its entry rows and browse title say;
 * the countries listed ([selected], null: every country), the panel's lists ([yours] with counts, [countryCounts]) and
 * what Report a problem says of them ([report]).
 */
data class SportsLiveChannelSummary(
    val view: String = MAIN_VIEW,
    val fallback: Boolean = false,
    val popularShown: Int = 0,
    val popularTotal: Int = 0,
    val sports: Int = 0,
    val all: Int = 0,
    val selected: Set<String>? = null,
    val yours: List<Pair<SportsLiveYourCountry, Int>> = emptyList(),
    val countryCounts: List<Pair<String, Int>> = emptyList(),
    /** Report a problem's Channels line ([SportsLiveChannelCountries.reportLine], #26). */
    val report: String = ""
) {
    companion object {
        const val MAIN_VIEW = "main"

        /** The view a board was built for: the tab's sections, or a browse kind (not its query: typing never blanks the list). */
        fun viewKey(browse: SportsLiveChannelBrowse?): String = browse?.kind?.name ?: MAIN_VIEW
    }
}
