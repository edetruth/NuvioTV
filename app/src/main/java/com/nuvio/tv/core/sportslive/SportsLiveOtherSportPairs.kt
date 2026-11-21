package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot

/**
 * The team pairs ESPN's soccer and field-hockey scoreboards list around now (live.23 coordinator decision, 2026-11-21):
 * two nations a cricket guess would tag ([SportsLiveCricket]) are never cricket when one of these feeds the app already
 * loaded has them playing that day ("India vs Pakistan" in field hockey, "Bangladesh vs Nepal" in the SAFF Cup).
 * Filled by the score repository from every scoreboard it reads; no feed is asked for it. Bounded, thread-safe.
 */
class SportsLiveOtherSportPairs {

    // Pair key -> the game's start.
    private val starts = HashMap<String, Long>()

    @Synchronized
    fun record(events: List<ScoreSnapshot>) {
        for (event in events) {
            if (event.league.sport !in SPORTS) continue
            val start = event.startMs ?: continue
            val home = event.home.displayName ?: event.home.name ?: continue
            val away = event.away.displayName ?: event.away.name ?: continue
            if (starts.size >= MAX_KEPT) starts.clear()
            starts[key(home, away)] = start
        }
    }

    /** True when [first] plays [second] in one of those feeds within [WINDOW_MS] of [nowMs], women's or youth sides alike. */
    @Synchronized
    fun lists(first: String, second: String, nowMs: Long): Boolean {
        if (starts.isEmpty()) return false
        val start = starts[key(first, second)] ?: return false
        return kotlin.math.abs(start - nowMs) <= WINDOW_MS
    }

    @Synchronized
    internal fun clear() = starts.clear()

    companion object {
        private val SPORTS = setOf("soccer", "field-hockey")
        private const val MAX_KEPT = 4_000

        /** "That day": a game starting within this long of now. */
        const val WINDOW_MS = 18 * 60 * 60_000L

        /** What every list uses. */
        val shared = SportsLiveOtherSportPairs()

        // A side as one nation, whatever its women's, youth or A form: "Bangladesh W", "India U19" and "India" are one.
        internal fun nation(side: String): String {
            val words = SportsLiveTeams.foldAccents(SportsLiveTeams.normalise(side)).split(' ').filter { it.isNotEmpty() }.toMutableList()
            while (words.size > 1 && (words.last() in TAILS || (words.last().startsWith("u") && words.last().drop(1).all(Char::isDigit) && words.last().length > 1) ||
                    words.last().removeSurrounding("(", ")") in TAILS)) {
                words.removeAt(words.lastIndex)
            }
            val base = words.joinToString(" ")
            return SportsLiveTeamAliases.canonical(base) ?: base
        }

        private val TAILS = setOf("w", "women", "womens", "a")

        private fun key(a: String, b: String): String {
            val x = nation(a)
            val y = nation(b)
            return if (x <= y) "$x\u0000$y" else "$y\u0000$x"
        }
    }
}
