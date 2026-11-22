package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.TeamDirectory
import com.nuvio.tv.domain.model.SportsLiveItem

/**
 * The importance order of live games (J2, user 2026-11-21), replacing "live, then start, then name"; since live.25 (#225)
 * every live list takes it through [SportsLiveLineup], the one owner of the order:
 * 1. followed teams' games, always first: a section no other key can outrank (sorting model, live.24);
 * 2. games of My sports' leagues (every placed league while nothing is picked), by [tierOf]: Champions League, Premier
 *    League, NBA, NFL, the other top leagues, then college and the rest; inside a tier, games matched to a score feed
 *    ([scored]) before the unmatched ones;
 * 3. games of other placed leagues (listed under All sports), by tier;
 * 4. games whose league is not known.
 * Within a group: earliest start first (the game furthest in on top), then name, then the card's key, so the order is the
 * same on every refresh. "Other events" (H) are folded apart and never ranked here.
 *
 * Spoilers: no key reads a score, a clock or a game state ([scored] says only that a feed has the game). The close/late
 * bump is gone (live.25, Fable sorting audit): excitement shows as a badge in place and never moves a row, so the order
 * is the same under Scores Show and Hide and never tells a score that is not on screen.
 */
object SportsLiveImportance {

    internal const val GROUP_FOLLOWED = 0
    internal const val GROUP_MY_SPORTS = 1
    internal const val GROUP_OTHER_LEAGUES = 2
    internal const val GROUP_UNPLACED = 3

    /**
     * [items] in importance order; see the class doc. [scored]: card keys of games matched to a score feed (live.24 audit
     * A6 / sorting model, [SportsLiveLineup.matchedKeys]): inside a league tier they come before the unmatched ones; never
     * above the followed section or another tier. Null: nothing moves.
     */
    fun sort(
        items: List<SportsLiveItem>,
        mySports: MySports,
        directory: TeamDirectory = TeamDirectory.shared,
        scored: Set<String>? = null
    ): List<SportsLiveItem> {
        if (items.size < 2) return items
        val keys = items.associateWith { keyOf(it, mySports, directory, scored) }
        return items.sortedWith(compareBy<SportsLiveItem> { keys.getValue(it) }.then(withinGroup))
    }

    /**
     * The group and tier of [item] and whether it is matched to a score feed: what [sort] orders by before the start.
     * Followed teams' games are a section of their own ([GROUP_FOLLOWED]), always first (sorting model, live.24).
     */
    internal data class Key(val group: Int, val tier: Int, val unscored: Int = 0) : Comparable<Key> {
        override fun compareTo(other: Key): Int = compareValuesBy(this, other, Key::group, Key::tier, Key::unscored)
    }

    internal fun keyOf(
        item: SportsLiveItem,
        mySports: MySports,
        directory: TeamDirectory = TeamDirectory.shared,
        scored: Set<String>? = null
    ): Key {
        val unscored = if (scored == null || item.streamKey in scored) 0 else 1
        if (item.followed) return Key(GROUP_FOLLOWED, 0, unscored)
        val league = leagueOf(item)
        val mine = mySports.isDefault || SportsLiveMySportsScope.shows(item, mySports, directory)
        val group = when {
            league != null && mine -> GROUP_MY_SPORTS
            league != null -> GROUP_OTHER_LEAGUES
            // A sport picked whole keeps its games of no known league under My sports: last of that group.
            mine && !mySports.isDefault -> GROUP_MY_SPORTS
            else -> GROUP_UNPLACED
        }
        return Key(group, tierOf(league), unscored)
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
