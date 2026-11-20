package com.nuvio.tv.core.sportslive.scores

/** The reason line's words (#137), from string resources (tests pass the English ones). */
data class BigGameStrings(
    /** "%1$s vs %2$s" */
    val versus: String,
    /** "Top-4 clash" */
    val topFour: String,
    /** "Relegation battle" */
    val relegation: String,
    /** "Derby" */
    val derby: String,
    /** "Playoffs" */
    val playoffs: String,
    /** " · " */
    val separator: String,
    /** A place as words: 1 → "1st", 3 → "3rd". */
    val ordinal: (Int) -> String
)

/** The reason line of a big game (#137): "#1 vs #8 · ESPN · Players Era 8", "1st vs 3rd", "11-4 vs 12-3". */
object BigGameText {
    fun line(reasons: List<BigGameReason>, strings: BigGameStrings): String =
        reasons.mapNotNull { piece(it, strings) }.joinToString(strings.separator)

    fun piece(reason: BigGameReason, strings: BigGameStrings): String? = when (reason.kind) {
        BigGameReasonKind.RANKS -> pair(reason, strings) { RankDisplay.label(it) }
        BigGameReasonKind.RANKED -> reason.a?.let { rank -> listOfNotNull(RankDisplay.label(rank), reason.text?.takeIf { it.isNotBlank() }).joinToString(" ") }
        BigGameReasonKind.PLACES -> pair(reason, strings, strings.ordinal)
        BigGameReasonKind.TOP_FOUR -> strings.topFour
        BigGameReasonKind.RELEGATION -> strings.relegation
        BigGameReasonKind.DERBY -> strings.derby
        BigGameReasonKind.PLAYOFFS -> strings.playoffs
        // A record reads with en dashes, as the board writes records ("11–4 vs 12–3").
        BigGameReasonKind.RECORDS -> reason.text?.trim()?.takeIf { it.isNotEmpty() }?.replace('-', '–')
        BigGameReasonKind.ROUND, BigGameReasonKind.NETWORK, BigGameReasonKind.EVENT -> reason.text?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun pair(reason: BigGameReason, strings: BigGameStrings, label: (Int) -> String): String? {
        val a = reason.a ?: return null
        val b = reason.b ?: return null
        return String.format(strings.versus, label(a), label(b))
    }

    /** English ordinals: 1st, 2nd, 3rd, 4th, 11th, 12th, 13th, 21st. */
    fun englishOrdinal(n: Int): String {
        val suffix = when {
            n % 100 in 11..13 -> "th"
            n % 10 == 1 -> "st"
            n % 10 == 2 -> "nd"
            n % 10 == 3 -> "rd"
            else -> "th"
        }
        return "$n$suffix"
    }
}

/**
 * "Games to watch" (#137): the day's best games for the top of each Upcoming day, and Tonight's best. Only big
 * games ([BigGameRating.isBig]) qualify, the biggest first, at most [MAX], one card per ESPN game; a followed team's
 * game is boosted in its score but never picked for that alone. Ties keep the listed order (start time).
 */
object GamesToWatch {
    const val MAX = 3

    fun <T> pick(games: List<T>, rating: (T) -> BigGameRating?): List<Pair<T, BigGameRating>> =
        games.mapIndexedNotNull { index, game -> rating(game)?.takeIf { it.isBig && it.reasons.isNotEmpty() }?.let { Triple(game, it, index) } }
            .sortedWith(compareByDescending<Triple<T, BigGameRating, Int>> { it.second.score }.thenBy { it.third })
            // Two cards of one ESPN game (two addons, either team first) are one pick.
            .distinctBy { it.second.eventId ?: it.third }
            .take(MAX)
            .map { it.first to it.second }
}
