package com.nuvio.tv.core.sportslive

/**
 * Rugby union told from the teams alone when a card names no sport (live.23, TV report #9: "Saracens vs Harlequin FC"
 * came untagged). Both sides must be clubs of the Premiership, the Top 14 or the URC whose names no other sport's
 * clubs use; a name other clubs share ("Bath", "Toulouse", "Lyon", "Cardiff", "Sharks", "Lions") never counts.
 */
object SportsLiveRugby {
    // Normalised ([SportsLiveTeams.normalise], accents folded): "RC Toulon" is "toulon", "Harlequin FC" is "harlequin".
    private val CLUBS = setOf(
        // Premiership
        "saracens", "harlequins", "harlequin", "bath rugby", "bristol bears", "exeter chiefs", "gloucester rugby",
        "leicester tigers", "newcastle falcons", "newcastle red bulls", "northampton saints", "sale sharks",
        // Top 14
        "stade toulousain", "racing 92", "stade francais", "stade francais paris", "toulon", "asm clermont auvergne",
        "clermont auvergne", "union bordeaux begles", "bordeaux begles", "castres olympique", "section paloise",
        "aviron bayonnais", "usa perpignan", "stade rochelais", "la rochelle", "montpellier herault rugby", "vannes",
        // URC
        "leinster", "munster", "ulster", "connacht", "glasgow warriors", "edinburgh rugby", "cardiff rugby", "ospreys",
        "scarlets", "dragons rfc", "benetton", "benetton rugby", "zebre", "zebre parma", "stormers", "dhl stormers",
        "vodacom bulls", "hollywoodbets sharks", "emirates lions"
    )

    /** True when both sides of [teams] are such clubs. */
    fun isRugbyGame(teams: TeamPair?): Boolean {
        teams ?: return false
        return club(teams.home) && club(teams.away)
    }

    private fun club(side: String): Boolean = SportsLiveTeams.foldAccents(SportsLiveTeams.normalise(side)) in CLUBS
}
