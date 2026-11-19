package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.MySport
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.domain.model.SportsLiveItem

/**
 * Which addon games My sports lets through (#146): Live now, Upcoming, Replays, the in-player panel and the CH±
 * lineup list only games whose sport (and, when the user named leagues for it, league) is in My sports.
 *
 * - Nothing picked ([MySports.isDefault]: every sport) scopes nothing.
 * - Channels, followed teams' games and games whose sport is unknown (no tag, no inference) always show:
 *   hiding a game you care about is worse than showing one you don't.
 * - A known sport My sports cannot hold (cricket, rugby, darts...) is out once the user has picked sports.
 * - A sport ticked without naming leagues takes all of its games, college ones included (its default leagues
 *   only choose the Results feeds). With leagues named, a game whose league is known must be one of them; a
 *   game whose league is unknown (a bare "Basketball") shows.
 */
object SportsLiveMySportsScope {

    /** [items] My sports lets through, in their order; all of them when nothing is picked. */
    fun scope(items: List<SportsLiveItem>, mySports: MySports): List<SportsLiveItem> =
        if (mySports.isDefault) items else items.filter { shows(it, mySports) }

    /** True when [item] is listed under [mySports]. */
    fun shows(item: SportsLiveItem, mySports: MySports): Boolean {
        if (mySports.isDefault || item.isChannel || item.followed) return true
        val sources = listOf(item) + item.alternates
        // The first source that tells its sport decides; none telling one: unknown, shown.
        val source = sources.firstOrNull { kindOf(it) != null } ?: return true
        val sports = sportsOf(source)
        return sports.any { sport -> sport in mySports.effectiveSports && leagueAllowed(source, sport, mySports) }
    }

    /**
     * The sport [item] is of: its sport word, title, genres or description ([SportsLiveSportKind.of]), else its
     * catalog's name only when that names a single sport (a mixed catalog never decides). Null: unknown.
     */
    internal fun kindOf(item: SportsLiveItem): SportsLiveSportKind? =
        (listOf(item.sport, item.meta.name) + item.meta.genres + item.meta.description).firstNotNullOfOrNull { SportsLiveSportKind.of(it) }
            ?: SportsLiveSportKind.single(item.catalogName)

    /**
     * The My sports [item]'s sport can be ([kindOf]); empty for a sport My sports cannot hold. A bare
     * "Football" may be soccer or American football ([EspnLeagueMap.espnSportsOf]): either.
     */
    internal fun sportsOf(item: SportsLiveItem): Set<MySport> {
        val kind = kindOf(item) ?: return emptySet()
        val sports = LinkedHashSet<MySport>()
        mySportOf(kind)?.let(sports::add)
        if (kind == SportsLiveSportKind.SOCCER) {
            SportsLiveTeams.knownSport(item)?.let(EspnLeagueMap::espnSportsOf)?.mapNotNullTo(sports) { MySport.ofEspn(it) }
        }
        return sports
    }

    private fun mySportOf(kind: SportsLiveSportKind): MySport? = when (kind) {
        SportsLiveSportKind.FOOTBALL -> MySport.FOOTBALL
        SportsLiveSportKind.SOCCER -> MySport.SOCCER
        SportsLiveSportKind.BASKETBALL -> MySport.BASKETBALL
        SportsLiveSportKind.HOCKEY -> MySport.HOCKEY
        SportsLiveSportKind.BASEBALL -> MySport.BASEBALL
        SportsLiveSportKind.TENNIS -> MySport.TENNIS
        SportsLiveSportKind.MMA -> MySport.MMA
        SportsLiveSportKind.MOTORSPORT -> MySport.MOTORSPORT
        SportsLiveSportKind.GOLF -> MySport.GOLF
        else -> null
    }

    /** [item]'s league against the leagues named for [sport]: none named, or its league unknown, lets it through. */
    private fun leagueAllowed(item: SportsLiveItem, sport: MySport, mySports: MySports): Boolean {
        val named = mySports.namedLeagues(sport)
        if (named.isEmpty()) return true
        // A bare sport word routes to its pro league but may be college: the league is not known.
        if (EspnLeagueMap.collegeFallbackFor(item) != null) return true
        val routed = EspnLeagueMap.leaguesFor(item).filter { it.sport == sport.espnSport && it != EspnLeagueMap.SOCCER_ALL }
        if (routed.isEmpty()) return true
        return routed.any { league -> named.any { it.coversLeague(league) } }
    }
}
