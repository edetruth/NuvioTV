package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.domain.model.SportsLiveItem
import java.text.Normalizer

/**
 * How long a game of a card's sport usually runs from its listed start, with a buffer for stoppages,
 * half-time and a late start (#60): past start + this, an unmatched card is "Probably ended". The one
 * table of lengths. Precision first: when in doubt the longer length wins, and sports whose sessions
 * run longer than the unknown-sport length (tennis, golf, cricket, motorsport, fight cards, ...) get no
 * estimate at all.
 */
object SportsLiveGameLength {
    private const val MINUTE_MS = 60_000L

    const val SOCCER_MS = 135 * MINUTE_MS
    const val BASKETBALL_MS = 180 * MINUTE_MS
    const val HOCKEY_MS = 180 * MINUTE_MS
    const val FOOTBALL_MS = 225 * MINUTE_MS
    const val BASEBALL_MS = 225 * MINUTE_MS
    /** A sport not recognised (or none). */
    const val UNKNOWN_MS = 240 * MINUTE_MS

    /** By ESPN sport (what [EspnLeagueMap] routes a card to). */
    private val byEspnSport = mapOf(
        "soccer" to SOCCER_MS,
        "basketball" to BASKETBALL_MS,
        "hockey" to HOCKEY_MS,
        "football" to FOOTBALL_MS,
        "baseball" to BASEBALL_MS
    )

    // Sessions that can run past [UNKNOWN_MS]: no estimate, so a long match or card is never moved.
    private val LONG_SESSIONS = Regex(
        """\b(tennis|atp|wta|golf|pga|cricket|icc|t20|odi|ipl|test match|f1|formula 1|formula one|grand prix|motogp|""" +
            """nascar|indycar|motorsport|racing|ufc|mma|boxing|fight|fights|bellator|wwe|aew|wrestling|darts|snooker|""" +
            """cycling|tour de france|olympics|olympic|marathon)\b"""
    )
    private val SOCCER_WORD = Regex("""\bsoccer\b""")
    private val FOOTBALL_WORD = Regex("""\bfootball\b""")

    /**
     * The estimated length of [item]'s game (a merged card: all its sources), or null when it gets no
     * estimate (a long-session sport). A bare "Football" may be either code: the longer American
     * football length applies unless the card also says soccer or names a soccer competition.
     */
    fun of(item: SportsLiveItem): Long? {
        val sources = listOf(item) + item.alternates
        // The card's own texts decide what it is; a mixed catalog's name never blocks its games.
        val own = sources.flatMap { listOfNotNull(it.sport) + it.meta.name + it.meta.genres }.map(::fold)
        if (own.any { LONG_SESSIONS.containsMatchIn(it) }) return null
        val leagues = sources.flatMap(EspnLeagueMap::leaguesFor).distinct()
        if (leagues.isEmpty()) return UNKNOWN_MS
        val texts = own + sources.map { fold(it.catalogName) }
        val ambiguousFootball = leagues.all { it == EspnLeagueMap.SOCCER_ALL } &&
            texts.any { FOOTBALL_WORD.containsMatchIn(it) } && texts.none { SOCCER_WORD.containsMatchIn(it) }
        val lengths = leagues.map { byEspnSport[it.sport] ?: UNKNOWN_MS } +
            listOfNotNull(FOOTBALL_MS.takeIf { ambiguousFootball })
        return lengths.max()
    }

    private val MARKS = Regex("""\p{Mn}+""")

    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "").lowercase().trim()
}
