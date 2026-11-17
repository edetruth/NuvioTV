package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.domain.model.FollowedTeam

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
            league = "${event.league.sport}/${event.leagueSlug}"
        )
    }

    /**
     * The stored sport word for an ESPN feed, chosen so [EspnLeagueMap] routes it back to that feed:
     * "American Football" (never "Football", which reads as soccer), "College Basketball"... Null for a
     * feed with no word.
     */
    fun sportWord(league: EspnLeague): String? = when {
        league == EspnLeagueMap.NBA -> "Basketball"
        league == EspnLeagueMap.COLLEGE_BASKETBALL -> "College Basketball"
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
            (storedSport == SOCCER || stored?.getOrNull(1) == event.leagueSlug)
        ) {
            return team.espnId == side.id
        }
        val sports = SportsResults.sportsOf(team)
        if (sports != null && event.league.sport !in sports) return false
        return ScoreMatcher.isStrongSide(team.name, side, event.leagueSlug, event.leagueName)
    }

    /** The followed teams that are [side] of [event]: unfollowing removes them all. */
    fun followersOf(followed: List<FollowedTeam>, event: ScoreSnapshot, side: ScoreTeam): List<FollowedTeam> =
        followed.filter { followsSide(it, event, side) }

    private const val SOCCER = "soccer"
}
