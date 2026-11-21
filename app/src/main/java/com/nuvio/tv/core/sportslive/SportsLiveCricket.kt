package com.nuvio.tv.core.sportslive

/**
 * Cricket told from the teams alone when a card names no sport (#145): no ESPN team list covers cricket,
 * so only sides no other sport uses count. Both sides Big Bash (BBL/WBBL) or IPL franchises
 * ("Hobart Hurricanes Women vs Sydney Sixers Women"), or a national A side against a cricket nation or
 * another A side ("Bangladesh A vs Hong Kong"). Two plain nations ("NZ vs IND") are never guessed: rugby,
 * hockey and football play them too.
 */
object SportsLiveCricket {
    private val FRANCHISES = setOf(
        // Big Bash League / Women's Big Bash League
        "adelaide strikers", "brisbane heat", "hobart hurricanes", "melbourne renegades", "melbourne stars",
        "perth scorchers", "sydney sixers", "sydney thunder",
        // Indian Premier League
        "mumbai indians", "chennai super kings", "royal challengers bengaluru", "royal challengers bangalore",
        "kolkata knight riders", "delhi capitals", "punjab kings", "rajasthan royals", "sunrisers hyderabad",
        "gujarat titans", "lucknow super giants"
    )

    // Cricket-playing nations, canonical names ([SportsLiveTeamAliases]: "NZ" is "new zealand").
    private val NATIONS = setOf(
        "australia", "england", "india", "new zealand", "pakistan", "south africa", "sri lanka", "west indies",
        "bangladesh", "afghanistan", "ireland", "zimbabwe", "scotland", "netherlands", "namibia", "nepal",
        "oman", "united arab emirates", "united states", "canada", "hong kong", "papua new guinea", "kenya",
        // ICC associates addons list (2026-11-21).
        "uganda", "tanzania", "rwanda", "nigeria", "malaysia", "singapore", "thailand", "japan", "qatar", "kuwait",
        "bahrain", "saudi arabia", "italy", "germany", "denmark", "jersey", "vanuatu", "bermuda", "cayman islands"
    )

    // A women's side: "Women", "(W)", a trailing "W".
    private val WOMEN = Regex("""\s*(\(w\)|\bwomen\b|\bw\b)\s*$""")

    /**
     * Nations whose national sides addons list almost only for cricket (coordinator decision, 2026-11-21): a pair of
     * cricket nations with one of these is cricket, in any women's, youth or A form.
     */
    private val MAINLY_CRICKET = setOf(
        "india", "pakistan", "sri lanka", "bangladesh", "afghanistan", "nepal", "west indies", "zimbabwe"
    )

    /**
     * True when [teams] can only be a cricket game; see the class doc. Since 2026-11-21 (coordinator decision) also two
     * cricket nations of which one is [MAINLY_CRICKET] ("Sri Lanka vs Hong Kong", "Ireland Women vs Zimbabwe Women"),
     * unless ESPN's soccer or field-hockey scoreboards the app has loaded list that pairing that day ([otherSports]).
     * "Australia vs England" stays unknown: rugby, football and hockey play it as much.
     */
    fun isCricketGame(
        teams: TeamPair?,
        otherSports: SportsLiveOtherSportPairs = SportsLiveOtherSportPairs.shared,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        teams ?: return false
        val a = side(teams.home)
        val b = side(teams.away)
        if (a in FRANCHISES && b in FRANCHISES) return true
        val aSideA = aSide(a)
        val aSideB = aSide(b)
        if ((aSideA != null || aSideB != null) && (aSideA ?: nation(a)) != null && (aSideB ?: nation(b)) != null) return true
        val x = nation(SportsLiveOtherSportPairs.nation(teams.home)) ?: return false
        val y = nation(SportsLiveOtherSportPairs.nation(teams.away)) ?: return false
        if (x == y || (x !in MAINLY_CRICKET && y !in MAINLY_CRICKET)) return false
        return !otherSports.lists(teams.home, teams.away, nowMs)
    }

    private fun side(name: String): String = SportsLiveTeams.normalise(name).replace(WOMEN, "").trim()

    // "bangladesh a" -> "bangladesh" when that is a cricket nation.
    private fun aSide(side: String): String? =
        side.takeIf { it.endsWith(" a") }?.removeSuffix(" a")?.trim()?.let(::nation)

    private fun nation(side: String): String? =
        (SportsLiveTeamAliases.canonical(side) ?: side).takeIf { it in NATIONS }
}
