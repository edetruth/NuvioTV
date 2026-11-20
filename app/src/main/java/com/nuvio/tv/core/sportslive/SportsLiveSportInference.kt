package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.identify.SportsLiveTeamIdentity
import com.nuvio.tv.core.sportslive.identify.TeamIdentityRules
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
 * names an excluded or college competition, or an already routed card is left alone. A "College" card that
 * names no sport (#145) may only land in a college league both its teams are in.
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
        EspnLeague("soccer", "bra.1") to "Brazil Serie A",
        // Women's leagues (#163): their lists are kept too (team search, #149), and a card both of whose teams
        // only they know is theirs.
        EspnLeagueMap.WNBA to "WNBA",
        EspnLeagueMap.NWSL to "NWSL",
        // The G League (#169, live.18): a bare "Basketball" card of two G League teams gets its feed and score.
        EspnLeagueMap.G_LEAGUE to "NBA G League"
    )

    /** The directory lists inference needs (asked for weekly with the rest, #82). */
    val leagues: List<EspnLeague> get() = LEAGUES.map { it.first }

    /** College feeds a bare sport word may also be on ([EspnLeagueMap.collegeFallbackFor]), with their tags. */
    private val COLLEGE: List<Pair<EspnLeague, String>> = listOf(
        EspnLeagueMap.COLLEGE_BASKETBALL to "NCAAM",
        EspnLeagueMap.COLLEGE_BASKETBALL_WOMEN to "NCAAW",
        EspnLeagueMap.COLLEGE_FOOTBALL to "NCAAF"
    )

    /**
     * The single league [item] can be inferred into from [directory]; null when it is not exactly one. A card
     * with no league at all may land in any of [LEAGUES] (its text naming no other sport); a card with only a
     * bare sport word ("Basketball", "Football") stays in that sport: its routed league and the college one
     * ("Arizona v Connecticut" → NCAAM, as neither side is an NBA team). A card naming its competition keeps it.
     */
    fun leagueOf(item: SportsLiveItem, directory: TeamDirectory): EspnLeague? {
        // A game listed live, upcoming or as a replay (#132): never a channel or a channels catalog's item.
        if (item.isChannel || item.kind == SportsLiveKind.CHANNELS || item.teams == null) return null
        // Two schools on a bare "Basketball" card: their college league, never a guess at the men's (item 4).
        collegeLeaguesOf(item, directory)?.let { return it.singleOrNull() }
        val routed = EspnLeagueMap.leaguesFor(item)
        val college = EspnLeagueMap.collegeFallbackFor(item)
        // A "College" card naming no sport (#145): routed to both college feeds.
        val collegeOnly = routed.size > 1 && routed.all { league -> COLLEGE.any { it.first == league } }
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
            // A "College" card naming no sport: the college league both its teams are in; a sport its text
            // names (a "Football" catalog) narrows it.
            collegeOnly -> {
                val named = SportsLiveSportKind.tagOf(item)
                routed.filter { named == null || kindOf(it) == named }.filter(TeamDirectory::covers)
            }
            else -> return null
        }
        if (candidates.isEmpty()) return null
        val (first, second) = SportsLiveTeams.writtenOrder(item.meta.name) ?: return null
        // Every candidate list must be there: a league not loaded yet could be the one that makes a name ambiguous.
        if (candidates.any { directory.fetchedAt(it) == null }) return null
        // A bare sport word may also be another of [LEAGUES] of that sport, when its list is loaded (the G League,
        // the WNBA): "Coachella Valley Lakers vs Stockton Kings" is no NBA game.
        val bare = !collegeOnly && (college != null || routed.all { it == EspnLeagueMap.SOCCER_ALL })
        val extra = if (!bare) emptyList() else LEAGUES.map { it.first }.filter { league ->
            league.sport != "soccer" && league !in candidates && routed.any { it.sport == league.sport } && directory.fetchedAt(league) != null
        }
        val firstLeagues = (candidates + extra).filter { directory.resolve(it, first) != null }
        val secondLeagues = (candidates + extra).filter { directory.resolve(it, second) != null }
        // Colleges field teams in both sports ("Nevada"): a College card takes the one league that has both
        // sides. Otherwise each side must name exactly one league, so a pro name two leagues share stays.
        val league = if (collegeOnly) {
            firstLeagues.filter { it in secondLeagues }.singleOrNull() ?: return null
        } else {
            firstLeagues.singleOrNull()?.takeIf { secondLeagues.singleOrNull() == it } ?: return null
        }
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

    /**
     * The college basketball leagues a bare "Basketball" card of two schools is on (item 4), told by the names alone,
     * before and whatever its score match: both sides resolve to two teams of a loaded college list, and neither side
     * to a team of the pro lists ([PRO_BASKETBALL], all loaded; a pro name keeps the strict rules). [NCAAM][EspnLeagueMap.COLLEGE_BASKETBALL]
     * or [NCAAW][EspnLeagueMap.COLLEGE_BASKETBALL_WOMEN] when only that list holds both and the other is loaded;
     * both when both hold them or the other is not loaded yet: the scoreboard with the pairing at that time decides
     * ([ScoreMatcher][com.nuvio.tv.core.sportslive.scores.ScoreMatcher]'s start tie-break; at one time, no score).
     * Null when it is no such card.
     */
    fun collegeLeaguesOf(item: SportsLiveItem, directory: TeamDirectory): List<EspnLeague>? {
        if (item.isChannel || item.kind == SportsLiveKind.CHANNELS || item.teams == null) return null
        if (EspnLeagueMap.leaguesFor(item) != listOf(EspnLeagueMap.NBA)) return null
        if (EspnLeagueMap.collegeFallbackFor(item) != EspnLeagueMap.COLLEGE_BASKETBALL) return null
        val (first, second) = SportsLiveTeams.writtenOrder(item.meta.name) ?: return null
        if (PRO_BASKETBALL.any { directory.fetchedAt(it) == null }) return null
        if (PRO_BASKETBALL.any { directory.resolve(it, first) != null || directory.resolve(it, second) != null }) return null
        val holding = COLLEGE_BASKETBALL_LISTS.filter { league ->
            if (directory.fetchedAt(league) == null) return@filter false
            val a = directory.resolve(league, first) ?: return@filter false
            val b = directory.resolve(league, second) ?: return@filter false
            a.id != b.id
        }
        if (holding.isEmpty()) return null
        val allLoaded = COLLEGE_BASKETBALL_LISTS.all { directory.fetchedAt(it) != null }
        return if (holding.size == 1 && allLoaded) holding else COLLEGE_BASKETBALL_LISTS
    }

    private val PRO_BASKETBALL = listOf(EspnLeagueMap.NBA, EspnLeagueMap.G_LEAGUE, EspnLeagueMap.WNBA)
    private val COLLEGE_BASKETBALL_LISTS = listOf(EspnLeagueMap.COLLEGE_BASKETBALL, EspnLeagueMap.COLLEGE_BASKETBALL_WOMEN)

    /** [item] as its inferred league's card (its sport set to that league's name), else [item] unchanged. */
    fun infer(item: SportsLiveItem, directory: TeamDirectory): SportsLiveItem {
        // Two schools on a bare "Basketball" card whose gender is not known: the neutral college tag, both college feeds.
        collegeLeaguesOf(item, directory)?.takeIf { it.size > 1 }?.let { return item.copy(sport = EspnLeagueMap.COLLEGE_BASKETBALL_EITHER) }
        val league = leagueOf(item, directory) ?: heldLeague(item, directory)
            ?: return heldSport(item, directory)?.let { item.copy(sport = it) } ?: item
        // The league's tag ("NHL", "NCAAM"), never the generic sport word.
        val sport = (LEAGUES + COLLEGE).first { it.first == league }.second
        return item.copy(sport = sport)
    }

    /**
     * The one loaded league whose list holds both teams of a game no source gives a sport (TV report #5: "Bayern vs
     * Köln", untagged): the league My sports already places it in ([SportsLiveMySportsScope.directoryLeaguesOf]), so
     * its row shows that league's tag and routes to its feed, one classification for both. Only a league with a tag
     * here ([LEAGUES], [COLLEGE]); a card that says women's only into a women's league. The score still needs both
     * teams on that feed at the card's time ([com.nuvio.tv.core.sportslive.scores.ScoreMatcher]).
     */
    internal fun heldLeague(item: SportsLiveItem, directory: TeamDirectory): EspnLeague? {
        if (item.isChannel || item.kind == SportsLiveKind.CHANNELS || item.teams == null) return null
        if (!SportsLiveTeamIdentity.sportUnknown(item)) return null
        val (first, second) = SportsLiveTeams.writtenOrder(item.meta.name) ?: return null
        // Two nations play several sports (#204): a national team list never decides.
        if (SportsLiveNations.isNationalPair(first, second)) return null
        val league = directory.leaguesHolding(first, second).singleOrNull() ?: return null
        if (EspnLeagueMap.saysWomen(item) && league !in EspnLeagueMap.WOMEN_FEEDS) return null
        return league.takeIf { held -> (LEAGUES + COLLEGE).any { it.first == held } }
    }

    /**
     * The sport word ("Soccer") of a game no source gives a sport whose two teams several loaded leagues all hold, all
     * of one sport (live.20: "Bayern vs Köln" in the Bundesliga and the Champions League lists): My sports places it
     * in those leagues, so its row says their sport rather than nothing. No league is claimed: its feed stays the
     * sport's own. Null for one league ([heldLeague]), none, or leagues of different sports ("Rangers vs Kings").
     */
    internal fun heldSport(item: SportsLiveItem, directory: TeamDirectory): String? {
        if (item.isChannel || item.kind == SportsLiveKind.CHANNELS || item.teams == null) return null
        if (!SportsLiveTeamIdentity.sportUnknown(item)) return null
        val (first, second) = SportsLiveTeams.writtenOrder(item.meta.name) ?: return null
        if (SportsLiveNations.isNationalPair(first, second)) return null
        val held = directory.leaguesHolding(first, second).takeIf { it.size > 1 } ?: return null
        return held.map { it.sport }.distinct().singleOrNull()?.let(TeamIdentityRules::sportWord)
    }
}
