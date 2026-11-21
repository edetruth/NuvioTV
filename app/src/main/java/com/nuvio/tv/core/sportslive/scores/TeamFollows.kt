package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveItem

/**
 * Following a team from ESPN data (Results rows, the Game page, #54): what is stored, and whether a
 * followed team is a given side of an ESPN event. The same My Teams list drives Live Now / Upcoming
 * (stars, the ★ My teams chip, followed games first), Results' YOUR TEAMS and My sports.
 */
object TeamFollows {

    /**
     * The team to store for [side] of [event]: ESPN's full name ("Boston Celtics", which the alias and
     * suffix matching links to an addon's "Celtics"), a sport word that routes back to the event's own
     * feed ([sportWord]; it brings that sport into My sports), and ESPN's team id, abbreviation and
     * league for exact matches in ESPN data. Null when the side has no name.
     */
    fun teamToFollow(event: ScoreSnapshot, side: ScoreTeam): FollowedTeam? {
        val name = (side.displayName ?: side.shortDisplayName ?: side.name ?: side.location ?: side.abbreviation)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return FollowedTeam(
            name = name,
            sport = sportWord(event.league),
            espnId = side.id,
            abbreviation = side.abbreviation,
            // The feed the event came from ("basketball/nba", "soccer/all"): the card side matches by it.
            league = event.league.path,
            logo = side.logo?.trim()?.takeIf { it.isNotEmpty() }
        )
    }

    /**
     * The team to store for [team] of [league]'s ESPN team list (team search, #149): the same fields as
     * [teamToFollow], so a team followed from search matches its games exactly as one followed from a game.
     */
    fun teamToFollow(league: EspnLeague, team: DirectoryTeam): FollowedTeam = FollowedTeam(
        name = team.displayName,
        sport = sportWord(league),
        espnId = team.id,
        abbreviation = team.abbreviation,
        league = league.path,
        logo = team.logo
    )

    /**
     * The followed entries that are [team] of [league]: by ESPN id within the league (soccer: the sport, as
     * club ids are shared across competitions), else a name-only entry with the same full name and sport word.
     */
    fun followersOf(followed: List<FollowedTeam>, league: EspnLeague, team: DirectoryTeam): List<FollowedTeam> =
        followed.filter { f ->
            val stored = f.league?.split('/')
            if (f.espnId != null && stored?.getOrNull(0) == league.sport) {
                f.espnId == team.id && (league.sport == SOCCER || stored.getOrNull(1) == league.league)
            } else {
                f.espnId == null && f.name.equals(team.displayName, ignoreCase = true) &&
                    (f.sport ?: "").equals(sportWord(league) ?: "", ignoreCase = true)
            }
        }

    /**
     * The stored sport word for an ESPN feed, chosen so [EspnLeagueMap] routes it back to that feed:
     * "American Football" (never "Football", which reads as soccer), "College Basketball"... Null for a
     * feed with no word.
     */
    fun sportWord(league: EspnLeague): String? = when {
        league == EspnLeagueMap.NBA -> "Basketball"
        league == EspnLeagueMap.COLLEGE_BASKETBALL -> "College Basketball"
        league == EspnLeagueMap.COLLEGE_BASKETBALL_WOMEN -> "Women's College Basketball"
        league == EspnLeagueMap.WNBA -> "WNBA"
        league == EspnLeagueMap.NWSL -> "NWSL"
        league == EspnLeagueMap.NFL -> "American Football"
        league == EspnLeagueMap.COLLEGE_FOOTBALL -> "College Football"
        league == EspnLeagueMap.NHL -> "Hockey"
        league == EspnLeagueMap.MLB -> "Baseball"
        league.sport == SOCCER -> "Soccer"
        else -> null
    }

    /**
     * True when [team] is [side] of [event]. A team followed from ESPN is matched by its ESPN id within
     * the same league (soccer: the same sport, as club ids are shared across competitions); otherwise
     * by name, with the same (W)/U21 qualifier and strong equality ([ScoreMatcher.isStrongSide]), in one
     * of the team's sports ([SportsResults.sportsOf]).
     */
    fun followsSide(team: FollowedTeam, event: ScoreSnapshot, side: ScoreTeam): Boolean {
        val stored = team.league?.split('/')
        val storedSport = stored?.getOrNull(0)
        if (team.espnId != null && side.id != null && storedSport == event.league.sport &&
            (storedSport == SOCCER || stored?.getOrNull(1) == event.league.league)
        ) {
            return team.espnId == side.id
        }
        val sports = SportsResults.sportsOf(team)
        if (sports != null && event.league.sport !in sports) return false
        return ScoreMatcher.isStrongSide(team.name, side, event.leagueSlug, event.leagueName)
    }

    /**
     * True when [team] is the card side [side] of [item] (a side as [ScoreChips.orderedSides] names it). With the card's
     * strict ESPN game [event] ([ScoreChips.matchedEvent]), a team followed from ESPN is that side only in the game's
     * league (soccer: its sport), by ESPN id (#212: a followed Toledo Rockets is never Houston's side of an NBA game);
     * otherwise the card's names decide ([SportsLiveTeams.followsSideOfAnySource]).
     */
    fun followsCardSide(team: FollowedTeam, side: String, item: SportsLiveItem, event: ScoreSnapshot?): Boolean {
        val espnSide = event?.let { espnSideOf(item, side, it) }
        val stored = team.league?.split('/')
        if (event != null && espnSide != null && team.espnId != null && stored != null && stored.size == 2) {
            if (stored[0] != event.league.sport) return false
            if (stored[0] != SOCCER && stored[1] != event.league.league) return false
            if (espnSide.id != null) return team.espnId == espnSide.id
        }
        return SportsLiveTeams.followsSideOfAnySource(team, side, item)
    }

    // The ESPN team that is [side] of [item] on its strict game [event]; null for a one-on-one sport or no strict match.
    private fun espnSideOf(item: SportsLiveItem, side: String, event: ScoreSnapshot): ScoreTeam? {
        if (event.individual != null) return null
        val (first, second) = ScoreChips.orderedSides(item) ?: return null
        val firstIsHome = ScoreMatcher.firstIsHome(first, second, event) ?: return null
        return when (side) {
            first -> if (firstIsHome) event.home else event.away
            second -> if (firstIsHome) event.away else event.home
            else -> null
        }
    }

    /** The followed teams that are [side] of [event]: unfollowing removes them all. */
    fun followersOf(followed: List<FollowedTeam>, event: ScoreSnapshot, side: ScoreTeam): List<FollowedTeam> =
        followed.filter { followsSide(it, event, side) }

    private const val SOCCER = "soccer"
}
