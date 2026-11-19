package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.TeamDirectory
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind

/**
 * The league of a two-team card that names none (#132 P4): "Devils / Canadiens" with no sport tag gets no
 * logos, tag or score, as no feed is routed for it. When each side resolves to exactly one team across every
 * league of [LEAGUES] the [TeamDirectory] holds (#82), and both land in the same single league, the card is
 * that league's: it gets that league's name as its sport, so the usual routing, logos and strict-match
 * scores follow. Precision first: a name two leagues know ("Rangers"), a league not loaded yet, a card that
 * names an excluded or college competition, or an already routed card is left alone.
 */
object SportsLiveSportInference {

    /** The leagues a card may be inferred into, with the sport text that routes back to exactly that league. */
    val LEAGUES: List<Pair<EspnLeague, String>> = listOf(
        EspnLeagueMap.NBA to "NBA",
        EspnLeagueMap.NFL to "NFL",
        EspnLeagueMap.NHL to "NHL",
        EspnLeagueMap.MLB to "MLB",
        EspnLeague("soccer", "usa.1") to "MLS",
        EspnLeague("soccer", "eng.1") to "Premier League",
        EspnLeague("soccer", "esp.1") to "La Liga",
        EspnLeague("soccer", "ita.1") to "Serie A",
        EspnLeague("soccer", "ger.1") to "Bundesliga",
        EspnLeague("soccer", "fra.1") to "Ligue 1",
        EspnLeague("soccer", "bra.1") to "Brazil Serie A"
    )

    /** The directory lists inference needs (asked for weekly with the rest, #82). */
    val leagues: List<EspnLeague> get() = LEAGUES.map { it.first }

    /** College feeds a bare sport word may also be on ([EspnLeagueMap.collegeFallbackFor]), with their tags. */
    private val COLLEGE: List<Pair<EspnLeague, String>> = listOf(
        EspnLeagueMap.COLLEGE_BASKETBALL to "NCAAM",
        EspnLeagueMap.COLLEGE_FOOTBALL to "NCAAF"
    )

    /**
     * The single league [item] can be inferred into from [directory]; null when it is not exactly one. A card
     * with no league at all may land in any of [LEAGUES] (its text naming no other sport); a card with only a
     * bare sport word ("Basketball", "Football") stays in that sport: its routed league and the college one
     * ("Arizona v Connecticut" → NCAAM, as neither side is an NBA team). A card naming its competition keeps it.
     */
    fun leagueOf(item: SportsLiveItem, directory: TeamDirectory): EspnLeague? {
        if (item.isChannel || item.kind != SportsLiveKind.EVENTS || item.teams == null) return null
        val routed = EspnLeagueMap.leaguesFor(item)
        val college = EspnLeagueMap.collegeFallbackFor(item)
        val candidates = when {
            routed.isEmpty() -> {
                if (!EspnLeagueMap.inferable(item)) return null
                // A card whose text names another sport is never moved into this one.
                val named = SportsLiveSportKind.tagOf(item)
                LEAGUES.map { it.first }.filter { named == null || kindOf(it) == named }
            }
            // A bare sport word: that sport's league, the college one, or (a bare "Football") any soccer league.
            college != null || routed.all { it == EspnLeagueMap.SOCCER_ALL } -> {
                val soccer = if (EspnLeagueMap.SOCCER_ALL in routed) LEAGUES.map { it.first }.filter { it.sport == "soccer" } else emptyList()
                (routed.filter { it != EspnLeagueMap.SOCCER_ALL } + soccer + listOfNotNull(college)).filter(TeamDirectory::covers).distinct()
            }
            // One league already routed (a college card whose sport is only "Basketball"): its own tag when
            // both sides are that league's teams, never the generic sport word.
            routed.size == 1 && routed[0] in (LEAGUES + COLLEGE).map { it.first } -> routed
            else -> return null
        }
        if (candidates.isEmpty()) return null
        val (first, second) = SportsLiveTeams.writtenOrder(item.meta.name) ?: return null
        // Every candidate list must be there: a league not loaded yet could be the one that makes a name ambiguous.
        if (candidates.any { directory.fetchedAt(it) == null }) return null
        val firstLeagues = candidates.filter { directory.resolve(it, first) != null }
        val secondLeagues = candidates.filter { directory.resolve(it, second) != null }
        val league = firstLeagues.singleOrNull() ?: return null
        if (secondLeagues.singleOrNull() != league) return null
        if (directory.resolve(league, first)?.id == directory.resolve(league, second)?.id) return null
        // Nothing to say beyond what the card already routes to (a bare "Basketball" card of two NBA teams stays).
        return league.takeIf { it in LEAGUES.map { (l, _) -> l } || it in COLLEGE.map { (l, _) -> l } }
    }

    private fun kindOf(league: EspnLeague): SportsLiveSportKind = when (league.sport) {
        "basketball" -> SportsLiveSportKind.BASKETBALL
        "football" -> SportsLiveSportKind.FOOTBALL
        "hockey" -> SportsLiveSportKind.HOCKEY
        "baseball" -> SportsLiveSportKind.BASEBALL
        else -> SportsLiveSportKind.SOCCER
    }

    /** [item] as its inferred league's card (its sport set to that league's name), else [item] unchanged. */
    fun infer(item: SportsLiveItem, directory: TeamDirectory): SportsLiveItem {
        val league = leagueOf(item, directory) ?: return item
        // The league's tag ("NHL", "NCAAM"), never the generic sport word.
        val sport = (LEAGUES + COLLEGE).first { it.first == league }.second
        return item.copy(sport = sport)
    }
}
