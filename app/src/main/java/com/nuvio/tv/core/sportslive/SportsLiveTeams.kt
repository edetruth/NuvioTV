package com.nuvio.tv.core.sportslive

/** Display names of the two sides of a game, rankings removed. */
data class TeamPair(val home: String, val away: String)

/** Parses and matches team names in event titles; shared by My Teams and one-card-per-game. */
object SportsLiveTeams {
    // In order of preference. " at " / " @ " put the home side second.
    private val SEPARATORS = listOf(" vs ", " vs. ", " v ", " at ", " @ ", " - ")
    private val AWAY_FIRST = setOf(" at ", " @ ")
    private val LEADING_RANK = Regex("^\\d+\\s+")
    private val WHITESPACE = Regex("\\s+")
    private val CLUB_SUFFIXES = setOf("fc", "sc", "cf")

    fun parse(name: String): TeamPair? {
        for (separator in SEPARATORS) {
            val index = name.indexOf(separator, ignoreCase = true)
            if (index < 0) continue
            val first = display(name.substring(0, index))
            val second = display(name.substring(index + separator.length))
            if (first.isEmpty() || second.isEmpty()) continue
            return if (separator in AWAY_FIRST) TeamPair(home = second, away = first) else TeamPair(first, second)
        }
        return null
    }

    fun normalise(team: String): String {
        val tokens = team.trim().lowercase().replace(LEADING_RANK, "").split(WHITESPACE).toMutableList()
        while (tokens.size > 1 && tokens.last() in CLUB_SUFFIXES) tokens.removeAt(tokens.lastIndex)
        return tokens.joinToString(" ").trim()
    }

    fun matches(a: String, b: String): Boolean {
        val x = normalise(a)
        val y = normalise(b)
        if (x.isEmpty() || y.isEmpty() || x in CLUB_SUFFIXES || y in CLUB_SUFFIXES) return false
        return x == y || x.endsWith(" $y") || y.endsWith(" $x")
    }

    private fun display(side: String): String = side.trim().replace(LEADING_RANK, "").trim()
}
