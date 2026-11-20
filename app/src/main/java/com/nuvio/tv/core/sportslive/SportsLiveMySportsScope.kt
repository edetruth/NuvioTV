package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.MyLeague
import com.nuvio.tv.core.sportslive.scores.MySport
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.TeamDirectory
import com.nuvio.tv.domain.model.SportsLiveItem

/**
 * Which addon games My sports lets through (#146): Live now, Upcoming, Replays, the in-player panel and the CH±
 * lineup list only games whose sport (and, when the user named leagues for it, league) is in My sports.
 *
 * - Nothing picked ([MySports.isDefault]: every sport) scopes nothing.
 * - Channels and followed teams' games always show.
 * - A game whose sport cannot be told (#167: no tag, no inference, no team directory match) is hidden once the
 *   user has picked sports; All sports still lists it. A game with no tag whose teams the [TeamDirectory] knows
 *   takes their league's sport.
 * - A known sport My sports cannot hold (cricket, rugby, darts...) is out once the user has picked sports.
 * - A sport ticked without naming leagues takes all of its games, college ones included (its default leagues
 *   only choose the Results feeds). With leagues named, a game whose league is known must be one of them. A
 *   game whose league is unknown (a bare "Basketball" or "Football") is told by its teams (#159): hidden when
 *   neither is a team of a named league, by those leagues' ESPN team lists ([MyLeague.teamList], kept in the
 *   [TeamDirectory]); shown while a list is not loaded yet, or when no named league has one (Internationals).
 */
object SportsLiveMySportsScope {

    /** [items] My sports lets through, in their order; all of them when nothing is picked. */
    fun scope(
        items: List<SportsLiveItem>,
        mySports: MySports,
        directory: TeamDirectory = TeamDirectory.shared
    ): List<SportsLiveItem> =
        if (mySports.isDefault) items else items.filter { shows(it, mySports, directory) }

    /** True when [item] is listed under [mySports]. */
    fun shows(item: SportsLiveItem, mySports: MySports, directory: TeamDirectory = TeamDirectory.shared): Boolean {
        if (mySports.isDefault || item.isChannel || item.followed) return true
        val sources = listOf(item) + item.alternates
        // The first source that tells its sport decides.
        val source = sources.firstOrNull { kindOf(it) != null }
        if (source != null) {
            val sports = sportsOf(source)
            return sports.any { sport -> sport in mySports.effectiveSports && leagueAllowed(source, sport, mySports, directory) }
        }
        // None tells one (#167): its teams may, through the team directory; otherwise it is unidentified, hidden.
        val leagues = sources.firstNotNullOfOrNull { directoryLeaguesOf(it, directory).ifEmpty { null } } ?: return false
        return leagues.any { league ->
            val sport = MySport.ofEspn(league.sport) ?: return@any false
            val named = mySports.namedLeagues(sport)
            sport in mySports.effectiveSports && (named.isEmpty() || named.any { it.coversLeague(league) })
        }
    }

    /**
     * The leagues the [directory] places an untagged [item] in: its inferred league ([SportsLiveSportInference]),
     * else every loaded league whose list holds both of its teams. Empty: no match.
     */
    internal fun directoryLeaguesOf(item: SportsLiveItem, directory: TeamDirectory): List<EspnLeague> {
        // Two schools of either gender (item 4): both college leagues; the user's picks place it.
        SportsLiveSportInference.collegeLeaguesOf(item, directory)?.let { return it }
        SportsLiveSportInference.leagueOf(item, directory)?.let { return listOf(it) }
        if (item.isChannel || item.teams == null) return emptyList()
        val (first, second) = SportsLiveTeams.writtenOrder(item.meta.name) ?: return emptyList()
        return directory.leaguesHolding(first, second)
    }

    /**
     * The sport [item] is of: its sport word, title, genres or description ([SportsLiveSportKind.of]), else its
     * catalog's name only when that names a single sport (a mixed catalog never decides). Null: unknown.
     */
    internal fun kindOf(item: SportsLiveItem): SportsLiveSportKind? =
        (listOf(item.sport, item.meta.name) + item.meta.genres + item.meta.description).firstNotNullOfOrNull { SportsLiveSportKind.of(it) }
            ?: SportsLiveSportKind.single(item.catalogName)
            // Cricket told from its teams alone (#145): out under My sports, as a tagged cricket game is.
            ?: SportsLiveSportKind.CRICKET.takeIf { SportsLiveCricket.isCricketGame(item.teams) }

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

    /**
     * [item]'s league against the leagues named for [sport]: none named lets it through; a known league must be
     * one of them; an unknown one is told by the teams ([teamsInNamed]).
     */
    private fun leagueAllowed(item: SportsLiveItem, sport: MySport, mySports: MySports, directory: TeamDirectory): Boolean {
        val named = mySports.namedLeagues(sport)
        if (named.isEmpty()) return true
        // A bare sport word routes to its pro league but may be college: the league is not known.
        val routed = EspnLeagueMap.leaguesFor(item).filter { it.sport == sport.espnSport && it != EspnLeagueMap.SOCCER_ALL }
        if (EspnLeagueMap.collegeFallbackFor(item) == null && routed.isNotEmpty()) {
            return routed.any { league -> named.any { it.coversLeague(league) } }
        }
        return teamsInNamed(item, named, directory) != false
    }

    /**
     * Whether a game of unknown league is one of [named]'s (#159): true when either side is a team of a named
     * league's list, false when every named list is loaded and holds neither, null when it cannot be told (no
     * two sides, no named league with a list, or a list not loaded yet).
     */
    internal fun teamsInNamed(item: SportsLiveItem, named: Set<MyLeague>, directory: TeamDirectory): Boolean? {
        val lists = named.mapNotNull { it.teamList }.distinct()
        if (lists.isEmpty() || item.teams == null) return null
        val (first, second) = SportsLiveTeams.writtenOrder(item.meta.name) ?: return null
        val loaded = lists.filter { directory.fetchedAt(it) != null }
        if (directory.holdsAny(first, loaded) || directory.holdsAny(second, loaded)) return true
        return if (loaded.size == lists.size) false else null
    }
}
