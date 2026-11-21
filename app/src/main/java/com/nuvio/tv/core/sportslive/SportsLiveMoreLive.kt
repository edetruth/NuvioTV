package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.MySport
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.domain.model.SportsLiveItem

/**
 * "More live" (user decision 2026-11-21, replacing the All sports chip and the "Other events" row): with My sports
 * picked, Live now, the player's panel and the guide end with one row, "More live: 4 Racing · 3 Cricket · 2 Fights ·
 * 2 Other ›", which opens the live games My sports leaves out, grouped by sport:
 * - games of sports not in My sports;
 * - games whose league is not known while My sports names leagues for their sport (J3);
 * - listings that are no game ([SportsLiveOtherEvents], H), under Other, with any game of unknown sport
 *   ([SportsLiveUnidentified.isUntagged]); they say LIVE only when ESPN confirms.
 * With nothing picked (every sport) there is no More live: everything is listed as before.
 */
object SportsLiveMoreLive {
    /** The group of games whose sport is not known, and of listings that are no game. Always last. */
    const val OTHER = "other"

    /** MMA and boxing, one group ("Fights"). */
    const val FIGHTS = "fights"

    /** How many groups the row names before the rest count as Other. */
    const val NAMED_MAX = 4

    /** One sport's games: [key] is a [SportsLiveSportKind] name, [FIGHTS] or [OTHER]. */
    data class Group(val key: String, val items: List<SportsLiveItem>)

    /**
     * The groups of [hidden] (live games My sports leaves out, in list order) and [others] (listings that are no
     * game, always Other): the largest first, ties by key; Other last. Each keeps its games' order.
     */
    fun groups(
        hidden: List<SportsLiveItem>,
        others: List<SportsLiveItem>,
        untagged: (SportsLiveItem) -> Boolean = SportsLiveUnidentified::isUntagged
    ): List<Group> {
        val byKey = LinkedHashMap<String, MutableList<SportsLiveItem>>()
        for (item in hidden) byKey.getOrPut(keyOf(item, untagged)) { mutableListOf() } += item
        if (others.isNotEmpty()) byKey.getOrPut(OTHER) { mutableListOf() } += others
        return byKey.map { (key, items) -> Group(key, items) }
            .sortedWith(compareBy<Group> { it.key == OTHER }.thenByDescending { it.items.size }.thenBy { it.key })
    }

    /**
     * What the row names: at most [named] groups as (key, count), largest first, then Other with every game left
     * (its own and the groups past [named]); none when there is nothing to show.
     */
    fun summary(groups: List<Group>, named: Int = NAMED_MAX): List<Pair<String, Int>> {
        val sports = groups.filter { it.key != OTHER }
        val shown = sports.take(named).map { it.key to it.items.size }
        val rest = sports.drop(named).sumOf { it.items.size } + (groups.firstOrNull { it.key == OTHER }?.items?.size ?: 0)
        return if (rest > 0) shown + (OTHER to rest) else shown
    }

    /**
     * The group [item] goes under: Other exactly when no source tells its sport ([SportsLiveUnidentified.isUntagged],
     * the one "unknown sport" rule); else its sport (MMA and boxing as Fights), or the sport word its addon gives when
     * that is no sport the app names ("Volleyball": [SPORT_TEXT]).
     */
    internal fun keyOf(item: SportsLiveItem, untagged: (SportsLiveItem) -> Boolean = SportsLiveUnidentified::isUntagged): String {
        if (untagged(item)) return OTHER
        val kind = SportsLiveMySportsScope.kindOf(item) ?: SportsLiveSportKind.tagOf(item)
            ?: return (listOf(item) + item.alternates).firstNotNullOfOrNull { SportsLiveTeams.knownSport(it)?.trim()?.takeIf(String::isNotEmpty) }
                ?.let { SPORT_TEXT + it } ?: OTHER
        return when (kind) {
            SportsLiveSportKind.MMA, SportsLiveSportKind.BOXING -> FIGHTS
            else -> kind.name
        }
    }

    /** A group key for a sport the app has no name of its own for: this, then the addon's word ("Volleyball"). */
    const val SPORT_TEXT = "sport:"

    /** The sport a group key names, for its label; null for [FIGHTS], [OTHER] and an addon's own word ([textOf]). */
    fun kindOf(key: String): SportsLiveSportKind? = SportsLiveSportKind.entries.firstOrNull { it.name == key }

    /** The addon's sport word a [SPORT_TEXT] key carries; null for any other key. */
    fun textOf(key: String): String? = key.takeIf { it.startsWith(SPORT_TEXT) }?.removePrefix(SPORT_TEXT)

    /**
     * The sport to offer adding to My sports once [item], a More live game, plays ("Add Cricket to My sports?"): its
     * sport when My sports can hold it, it is not in [mySports] and the user has not said "Not now" to it ([declined],
     * by [MySport.id]); null otherwise (nothing picked, a sport My sports cannot hold, a game of a picked sport).
     */
    fun promptFor(item: SportsLiveItem, mySports: MySports, declined: Set<String>): MySport? {
        if (mySports.isDefault) return null
        val sport = SportsLiveMySportsScope.sportsOf(item).firstOrNull() ?: return null
        return sport.takeIf { it !in mySports.effectiveSports && it.id !in declined }
    }
}
