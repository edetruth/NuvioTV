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
        "ireland" to listOf("ire", "republic of ireland", "rep ireland", "rep of ireland"),
        "scotland" to listOf("sco"),
        "netherlands" to listOf("ned", "nl", "holland"),
        "namibia" to listOf("nam"),
        "hong kong" to listOf("hk", "hong kong china"),
        "nepal" to listOf("nep"),
        "cameroon" to listOf("cmr"),
        "cote d ivoire" to listOf("civ", "ivory coast", "côte d ivoire"),
        "dr congo" to listOf("cod", "drc", "congo dr"),
        "nigeria" to listOf("nga", "ngr"),
        "egypt" to listOf("egy"),
        "morocco" to listOf("mar"),
        "senegal" to listOf("sen"),
        "ghana" to listOf("gha"),
        "united states" to listOf("usa", "us", "usmnt"),
        "canada" to listOf("can"),
        "argentina" to listOf("arg"),
        "brazil" to listOf("bra"),
        "portugal" to listOf("por"),
        "germany" to listOf("ger"),
        "france" to listOf("fra"),
        "spain" to listOf("esp"),
        "italy" to listOf("ita"),
        // Men's national teams as ESPN and addons write them (#66). A women's or youth side keeps its
        // (W)/U21 qualifier, so it never resolves to these; "USWNT" is not an alias of the men's team.
        "south korea" to listOf("korea republic", "republic of korea", "korea rep", "kor"),
        "turkey" to listOf("turkiye", "türkiye", "tur"),
        "iran" to listOf("ir iran", "iri"),
        "czech republic" to listOf("czechia", "cze"),
        "bosnia and herzegovina" to listOf("bosnia herzegovina", "bosnia", "bih"),
        "cape verde" to listOf("cabo verde", "cape verde islands", "cpv"),
        "china" to listOf("china pr", "chn"),
        "saudi arabia" to listOf("ksa"),
        "north macedonia" to listOf("macedonia", "mkd"),
        "kyrgyz republic" to listOf("kyrgyzstan"),
        "dominican republic" to listOf("dominican rep"),
        "st vincent and the grenadines" to listOf("saint vincent and the grenadines", "st vincent", "saint vincent"),
        "st kitts and nevis" to listOf("saint kitts and nevis", "st kitts"),
        "st lucia" to listOf("saint lucia"),
        "trinidad and tobago" to listOf("trinidad", "tto")
    ) + COLLEGES + CLUBS

    /**
     * US colleges ESPN names by their initials ("SMU Mustangs", location "SMU") while addons often write
     * the full name ("Southern Methodist vs Oral Roberts", I6). Only names that mean one school.
     */
    private val COLLEGES: List<Pair<String, List<String>>>
        get() = listOf(
            "smu" to listOf("southern methodist"),
            "tcu" to listOf("texas christian"),
            "byu" to listOf("brigham young"),
            "lsu" to listOf("louisiana state"),
            "ucf" to listOf("central florida"),
            "unlv" to listOf("nevada las vegas"),
            "vcu" to listOf("virginia commonwealth"),
            "uab" to listOf("alabama birmingham"),
            "utep" to listOf("texas el paso"),
            "utsa" to listOf("texas san antonio"),
            // ESPN writes these by their short names; addons often by the state (P4). One school each.
            "uconn" to listOf("connecticut"),
            "umass" to listOf("massachusetts"),
            "ole miss" to listOf("mississippi"),
            // Initials for a state's name, as ESPN and addons both write them ("NC State", "North Carolina State", #174).
            "nc state" to listOf("north carolina state", "n c state", "nc st"),
            "sc state" to listOf("south carolina state"),
            "nc central" to listOf("north carolina central"),
            "fiu" to listOf("florida international"),
            "fau" to listOf("florida atlantic"),
            "fgcu" to listOf("florida gulf coast"),
            "uic" to listOf("illinois chicago"),
            "umbc" to listOf("maryland baltimore county"),
            "utrgv" to listOf("texas rio grande valley", "ut rio grande valley")
        )

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
            "manchester united" to listOf("man utd", "man united", "man u", "manchester utd"),
            "manchester city" to listOf("man city"),
            "tottenham hotspur" to listOf("tottenham"),
            "wolverhampton wanderers" to listOf("wolves", "wolverhampton"),
            "brighton and hove albion" to listOf("brighton"),
            "west ham united" to listOf("west ham"),
            "newcastle united" to listOf("newcastle"),
            "nottingham forest" to listOf("nottm forest", "nott m forest", "nottingham f"),
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
            "internazionale" to listOf("inter milan", "internazionale milano", "inter milano"),
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

    private val GROUP_OF: Map<String, Set<String>> = GROUPS.associate { (canonical, aliases) -> canonical to (setOf(canonical) + aliases) }

    /**
     * Every name of the team [normalisedName] stands for, itself included ("connecticut" → uconn,
     * connecticut), for team search (#149); empty when it is not a listed name.
     */
    fun group(normalisedName: String): Set<String> = TO_CANONICAL[normalisedName]?.let(GROUP_OF::get).orEmpty()

    /**
     * The canonical team for a normalised name (lowercase, no club suffix, no qualifier), or null when
     * the name is not a known abbreviation/alias. A canonical name resolves to itself, so a full name
     * and its abbreviation both return the same value.
     */
    fun canonical(normalisedName: String): String? =
        TO_CANONICAL[normalisedName]
            // A national A side ("BAN-A", "Bangladesh A", #145): the nation's canonical name, then "a".
            ?: normalisedName.takeIf { it.endsWith(" a") }?.let { TO_CANONICAL[it.dropLast(2)] }?.let { "$it a" }
}
