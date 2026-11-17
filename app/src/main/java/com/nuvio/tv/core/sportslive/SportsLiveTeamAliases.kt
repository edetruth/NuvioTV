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
        "cote d ivoire" to listOf("civ", "ivory coast", "côte d ivoire"),
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
    ) + CLUBS

    /**
     * Soccer clubs: the names addons use for them. Keys and values in normalised form (leading
     * AC/AS/FC tags and trailing FC dropped, "&" as "and"). Names that mean more than one club or
     * team are left out ("spurs", "villa", "palace", "galaxy", "atletico"), and "milan" is listed so
     * "Inter Milan" never matches it by suffix.
     *
     * Adapted from the ALIASES table of SportsCatalog in homelessbrian/NuvioPlusIPTV (GPL-3.0, as
     * this project).
     */
    private val CLUBS: List<Pair<String, List<String>>>
        get() = listOf(
            "manchester united" to listOf("man utd", "man united", "man u"),
            "manchester city" to listOf("man city"),
            "tottenham hotspur" to listOf("tottenham"),
            "wolverhampton wanderers" to listOf("wolves", "wolverhampton"),
            "brighton and hove albion" to listOf("brighton"),
            "west ham united" to listOf("west ham"),
            "newcastle united" to listOf("newcastle"),
            "nottingham forest" to listOf("nottm forest", "nottingham f"),
            "leicester city" to listOf("leicester"),
            "leeds united" to listOf("leeds"),
            "sheffield united" to listOf("sheff utd", "sheffield utd"),
            "sheffield wednesday" to listOf("sheff wed", "sheffield wed"),
            "queens park rangers" to listOf("qpr"),
            "west bromwich albion" to listOf("west brom", "wba"),
            "bournemouth" to emptyList(),
            "ipswich town" to listOf("ipswich"),
            "luton town" to listOf("luton"),
            "paris saint germain" to listOf("psg", "paris sg", "paris st germain"),
            "internazionale" to listOf("inter milan", "internazionale milano"),
            "milan" to emptyList(),
            "bayern munich" to listOf("bayern", "bayern munchen", "bayern münchen"),
            "borussia dortmund" to listOf("dortmund", "bvb"),
            "bayer leverkusen" to listOf("leverkusen"),
            "barcelona" to listOf("barca", "barça"),
            "inter miami" to emptyList(),
            "new york red bulls" to listOf("red bulls", "ny red bulls"),
            "new york city" to listOf("nycfc")
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
