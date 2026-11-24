package com.nuvio.tv.core.sportslive.scores

/**
 * The scoreboard half of "ESPN has no game for this live card" (live.27, [com.nuvio.tv.core.sportslive.SportsLiveOtherStreams]),
 * kept apart so the score repository and the nightly accuracy run read one rule.
 */
object NoEspnGame {

    /**
     * True when neither side of [card] plays on [events] (the fresh scoreboards at hand of [leagues]). Fails open (false) unless:
     * - one of [leagues] (never soccer/all, which has no team list) has ESPN's (US Eastern) today ([hasTodayBoard]), and
     *   its loaded team list in [directory] names each of the card's two sides as one team, two different teams;
     * - or, for a soccer card no competition names ([isUntaggedSoccer], live.28: "Real Madrid vs Inter" under "Soccer"),
     *   soccer/all has ESPN's today and the loaded lists know both sides as two soccer clubs ([untaggedSoccerIds]);
     * - neither team plays on any of [events]: not by ESPN id, nor by any name [matcher] reads ([ScoreMatcher.touches], even WEAK).
     */
    fun hasNone(
        card: ScoreCard,
        leagues: List<EspnLeague>,
        hasTodayBoard: (EspnLeague) -> Boolean,
        directory: TeamDirectory,
        events: List<ScoreSnapshot>,
        matcher: ScoreMatcher
    ): Boolean {
        val ids = HashSet<String>()
        for (league in leagues) {
            if (league == EspnLeagueMap.SOCCER_ALL || !hasTodayBoard(league)) continue
            val home = directory.teamId(league, card.teams.home) ?: continue
            val away = directory.teamId(league, card.teams.away) ?: continue
            if (home == away) continue
            ids += home
            ids += away
        }
        if (ids.isEmpty() && isUntaggedSoccer(leagues) && hasTodayBoard(EspnLeagueMap.SOCCER_ALL)) {
            untaggedSoccerIds(card, directory)?.let { ids += it }
        }
        if (ids.isEmpty()) return false
        if (events.any { it.home.id in ids || it.away.id in ids }) return false
        return !matcher.touches(card, events)
    }

    /**
     * True when a card on [leagues] is soccer no competition of which is named: soccer/all, beside no soccer league (a
     * bare "Football" card also has the college football fallback). Its scoreboards at hand are every soccer feed's.
     */
    fun isUntaggedSoccer(leagues: List<EspnLeague>): Boolean =
        EspnLeagueMap.SOCCER_ALL in leagues && leagues.none { it.sport == "soccer" && it != EspnLeagueMap.SOCCER_ALL }

    /**
     * The two ESPN clubs an untagged soccer [card] names (live.28), or null on any doubt, as a wrong "not a game" is
     * worse than leaving it: every loaded soccer list that knows a side must name the same one club (ESPN's soccer ids
     * are the same in every competition; "United" naming several, or a men's and a women's club, is none), at least one
     * must know it, the two clubs must differ, and no loaded list of another sport may name either side (a "Football"
     * card may be American football: the NFL's list, kept for inference, must be loaded to tell).
     */
    internal fun untaggedSoccerIds(card: ScoreCard, directory: TeamDirectory): Set<String>? {
        val loaded = directory.loadedLeagues()
        if (EspnLeagueMap.NFL !in loaded) return null
        val (soccer, others) = loaded.filter { it != EspnLeagueMap.SOCCER_ALL }.partition { it.sport == "soccer" }
        val ids = listOf(card.teams.home, card.teams.away).map { side ->
            if (others.any { directory.teamIdsNaming(it, side).isNotEmpty() }) return null
            soccer.flatMapTo(HashSet()) { directory.teamIdsNaming(it, side) }.singleOrNull() ?: return null
        }
        return if (ids[0] == ids[1]) null else ids.toSet()
    }
}
