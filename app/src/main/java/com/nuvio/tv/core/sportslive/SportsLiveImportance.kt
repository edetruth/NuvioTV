package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.TeamDirectory
import com.nuvio.tv.domain.model.SportsLiveItem

/**
 * Live now's order by importance (J2, user 2026-11-21), replacing "live, then start, then name":
 * 1. followed teams' games;
 * 2. games of My sports' leagues (every placed league while nothing is picked), by [tierOf]: Champions League, Premier
 *    League, NBA, NFL, the other top leagues, then college and the rest; a close or late game ([bumped]: the excitement
 *    badge, #43, which exists only while scores are shown) first within its tier;
 * 3. games of other placed leagues (listed under All sports), by tier;
 * 4. games whose league is not known.
 * Within a group: soonest start first, then name, then the card's key, so the order is the same on every refresh.
 * "Other events" (H) are folded apart and never ranked here.
 *
 * Spoilers: nothing but [bumped] reads a score, and the caller fills it only under Scores Show (never Hide or Off,
 * never a spoiler-protected game), so the order never tells a score that is not on screen.
 */
object SportsLiveImportance {

    internal const val GROUP_FOLLOWED = 0
    internal const val GROUP_MY_SPORTS = 1
    internal const val GROUP_OTHER_LEAGUES = 2
    internal const val GROUP_UNPLACED = 3

    /** [items] in importance order; see the class doc. [bumped]: card keys of close or late games (Scores shown only). */
    fun sort(
        items: List<SportsLiveItem>,
        mySports: MySports,
        bumped: Set<String> = emptySet(),
        directory: TeamDirectory = TeamDirectory.shared
    ): List<SportsLiveItem> {
        if (items.size < 2) return items
        val keys = items.associateWith { keyOf(it, mySports, bumped, directory) }
        return items.sortedWith(compareBy<SportsLiveItem> { keys.getValue(it) }.then(withinGroup))
    }

    /** The group and tier of [item], with its bump: what [sort] orders by before the start. */
    internal data class Key(val group: Int, val tier: Int, val bump: Int) : Comparable<Key> {
        override fun compareTo(other: Key): Int = compareValuesBy(this, other, Key::group, Key::tier, Key::bump)
    }

    internal fun keyOf(item: SportsLiveItem, mySports: MySports, bumped: Set<String>, directory: TeamDirectory = TeamDirectory.shared): Key {
        val bump = if (item.streamKey in bumped) 0 else 1
        if (item.followed) return Key(GROUP_FOLLOWED, 0, bump)
        val league = leagueOf(item)
        val mine = mySports.isDefault || SportsLiveMySportsScope.shows(item, mySports, directory)
        val group = when {
            league != null && mine -> GROUP_MY_SPORTS
            league != null -> GROUP_OTHER_LEAGUES
            // A sport picked whole keeps its games of no known league under My sports: last of that group.
            mine && !mySports.isDefault -> GROUP_MY_SPORTS
            else -> GROUP_UNPLACED
        }
        return Key(group, tierOf(league), bump)
    }

    private val withinGroup: Comparator<SportsLiveItem> =
        compareBy<SportsLiveItem, Long?>(nullsLast()) { it.startMs }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
            .thenBy { it.streamKey }

    /**
     * The league [item] (or one of its sources) is placed in: the one its routing names, never a bare sport word that
     * may be college ("Basketball") or soccer's catch-all. Null when none is known.
     */
    internal fun leagueOf(item: SportsLiveItem): EspnLeague? =
        (listOf(item) + item.alternates).firstNotNullOfOrNull { source ->
            if (EspnLeagueMap.collegeFallbackFor(source) != null) return@firstNotNullOfOrNull null
            EspnLeagueMap.leaguesFor(source).firstOrNull { it != EspnLeagueMap.SOCCER_ALL }
        }

    /** A league's tier, lower first; [TIER_OTHER] for any league not in the table, [TIER_NONE] for none. */
    internal fun tierOf(league: EspnLeague?): Int {
        league ?: return TIER_NONE
        TIERS[league.path]?.let { return it }
        // National teams' competitions (World Cup, qualifiers, Nations League) sit with the other top leagues.
        if (league.sport == "soccer" && league.league.startsWith("fifa.") && league.league != "fifa.cwc") return TIER_TOP
        return TIER_OTHER
    }

    private const val TIER_TOP = 4
    private const val TIER_SECOND = 5
    internal const val TIER_OTHER = 6
    internal const val TIER_NONE = 7

    /** The tier table (user, 2026-11-21): Champions League, Premier League, NBA, NFL, then other top leagues, then college. */
    private val TIERS: Map<String, Int> = mapOf(
        "soccer/uefa.champions" to 0,
        "soccer/eng.1" to 1,
        "basketball/nba" to 2,
        "football/nfl" to 3,
        "soccer/esp.1" to TIER_TOP, "soccer/ita.1" to TIER_TOP, "soccer/ger.1" to TIER_TOP, "soccer/fra.1" to TIER_TOP,
        "soccer/uefa.europa" to TIER_TOP, "hockey/nhl" to TIER_TOP, "baseball/mlb" to TIER_TOP, "tennis/atp" to TIER_TOP,
        "tennis/wta" to TIER_TOP, "mma/ufc" to TIER_TOP, "racing/f1" to TIER_TOP,
        "football/college-football" to TIER_SECOND, "basketball/mens-college-basketball" to TIER_SECOND,
        "basketball/womens-college-basketball" to TIER_SECOND, "soccer/usa.1" to TIER_SECOND,
        "soccer/uefa.europa.conf" to TIER_SECOND, "basketball/wnba" to TIER_SECOND, "soccer/usa.nwsl" to TIER_SECOND,
        "golf/pga" to TIER_SECOND, "golf/lpga" to TIER_SECOND
    )
}
