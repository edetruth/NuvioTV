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
        if (ids.isEmpty()) return false
        if (events.any { it.home.id in ids || it.away.id in ids }) return false
        return !matcher.touches(card, events)
    }
}
