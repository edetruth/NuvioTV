package com.nuvio.tv.core.sportslive

/**
 * Maps common team abbreviations and alternate spellings to one canonical team name, so copies of a
 * game that name the sides differently ("PAK vs SL" vs "Pakistan vs Sri Lanka") resolve to the same
 * teams. Keys and values are normalised: lowercase, no club suffix, no (W)/U21 qualifier.
 *
 * Precision over recall: only entries listed here resolve; everything else stays as written, and
 * [SportsLiveTeams.matches] folds on an alias only when BOTH sides resolve to the same canonical team.
 * Data-driven and easy to extend; shared with live-scores matching (#17), where ESPN exposes the same
 * country codes/aliases to seed and verify this table.
 */
object SportsLiveTeamAliases {
    // canonical -> the abbreviations / alternate spellings that mean it. International country codes
    // (cricket and football) plus a few obvious alternates. Add rows here to extend.
    private val GROUPS: List<Pair<String, List<String>>> = listOf(
        "pakistan" to listOf("pak"),
        "sri lanka" to listOf("sl"),
        "new zealand" to listOf("nz"),
        "india" to listOf("ind"),
        "australia" to listOf("aus"),
        "england" to listOf("eng"),
        "south africa" to listOf("sa", "rsa"),
        "bangladesh" to listOf("ban", "bd"),
        "afghanistan" to listOf("afg"),
        "zimbabwe" to listOf("zim"),
        "west indies" to listOf("wi", "windies"),
        "united arab emirates" to listOf("uae"),
        "ireland" to listOf("ire"),
        "scotland" to listOf("sco"),
        "netherlands" to listOf("ned", "nl"),
        "namibia" to listOf("nam"),
        "cameroon" to listOf("cmr"),
        "cote d'ivoire" to listOf("civ", "ivory coast", "côte d'ivoire"),
        "dr congo" to listOf("cod", "drc", "congo dr"),
        "nigeria" to listOf("nga", "ngr"),
        "egypt" to listOf("egy"),
        "morocco" to listOf("mar"),
        "senegal" to listOf("sen"),
        "ghana" to listOf("gha"),
        "united states" to listOf("usa", "us"),
        "canada" to listOf("can"),
        "argentina" to listOf("arg"),
        "brazil" to listOf("bra"),
        "portugal" to listOf("por"),
        "germany" to listOf("ger"),
        "france" to listOf("fra"),
        "spain" to listOf("esp"),
        "italy" to listOf("ita")
    )

    private val TO_CANONICAL: Map<String, String> = buildMap {
        for ((canonical, aliases) in GROUPS) {
            put(canonical, canonical)
            for (alias in aliases) put(alias, canonical)
        }
    }

    /**
     * The canonical team for a normalised name (lowercase, no club suffix, no qualifier), or null when
     * the name is not a known abbreviation/alias. A canonical name resolves to itself, so a full name
     * and its abbreviation both return the same value.
     */
    fun canonical(normalisedName: String): String? = TO_CANONICAL[normalisedName]
}
