package com.nuvio.tv.core.sportslive

import java.util.Locale

/**
 * The one short-tag vocabulary of Sports & Live (live.20 F5, #179): a row's league or sport tag in at most [MAX_CHARS]
 * capitals, so a fixed tag column never cuts it and names start at one x on every row. The in-player panel uses it;
 * Live Now's rows, Home's cards and the dual captions can take the same words.
 *
 * - A short league or sport tag ("NBA", "NCAAM", "TENNIS", "CRICKET", "FOOTBALL") stays as it is.
 * - A long league tag takes its known short form ("BUNDESLIGA" → "BUNDES", "NATIONS LG" → "NATIONS").
 * - A long sport word takes its sport's word ([sportWord]: "MOTORSPORT" → "MOTOR", "BASKETBALL" → "BBALL").
 * - Any other long tag takes the row's sport word, else its first word when that fits, else its first [MAX_CHARS].
 */
object SportsLiveTags {
    /** The longest tag: eight capitals fit an about 62 dp column at the 11 sp Tag size ("FOOTBALL" is about 57 dp). */
    const val MAX_CHARS = 8

    /** [tag] (any league or sport tag, any case) in the short vocabulary; [sport] is the row's sport when known. Empty stays empty. */
    fun short(tag: String, sport: SportsLiveSportKind?): String {
        val text = tag.trim().uppercase(Locale.ROOT)
        if (text.isEmpty()) return ""
        LEAGUE_SHORT[text]?.let { return it }
        if (text.length <= MAX_CHARS) return text
        SportsLiveSportKind.of(text)?.let { return sportWord(it) }
        sport?.let { return sportWord(it) }
        text.split(' ').first().takeIf { it.isNotEmpty() && it.length <= MAX_CHARS }?.let { return it }
        return text.take(MAX_CHARS).trim()
    }

    /** A sport's short tag: its name, the three long ones shortened ("BBALL", "MOTOR", "WRESTLE"). */
    fun sportWord(sport: SportsLiveSportKind): String = when (sport) {
        SportsLiveSportKind.BASKETBALL -> "BBALL"
        SportsLiveSportKind.MOTORSPORT -> "MOTOR"
        SportsLiveSportKind.WRESTLING -> "WRESTLE"
        else -> sport.name
    }

    /** Short forms of the league tags longer than [MAX_CHARS] ([SportsLiveLeagueTags]). */
    private val LEAGUE_SHORT = mapOf(
        "WORLD CUP" to "WC",
        "WOMEN'S WC" to "WWC",
        "U21 FRNDLY" to "U21",
        "NATIONS LG" to "NATIONS",
        "EURO QUAL" to "EURO Q",
        "AFCON QUAL" to "AFCON Q",
        "ASIAN CUP" to "ASIA CUP",
        "CHAMPNSHP" to "EFL CH",
        "LEAGUE CUP" to "EFL CUP",
        "BUNDESLIGA" to "BUNDES",
        "DFB POKAL" to "POKAL",
        "EREDIVISIE" to "EREDIV",
        "SUPER LIG" to "SUPERLIG",
        "SAUDI PRO" to "SAUDI",
        "LIGA 1 ROU" to "LIGA 1",
        "EUROLEAGUE" to "EUROLG"
    )
}
