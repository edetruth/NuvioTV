package com.nuvio.tv.core.sportslive

import java.util.Locale

/**
 * Readable short row tags (at most 10 characters, upper case) for ESPN competitions whose own abbreviation or
 * name says little on a TV row ("FU17W" → "U17 WC", "UEFA Champions League" → "UCL"). Keyed by ESPN's league
 * abbreviation, its competition name and its league slug. Null when there is no mapping: the row keeps its
 * usual tag. Cached; plain string work, no regex (#152).
 */
object SportsLiveLeagueTags {
    private val cache = Memo(512) { text: String -> TAGS[key(text)] }

    /** The short tag for [league] (an ESPN abbreviation, competition name or slug); null when none is mapped. */
    fun of(league: String): String? = cache(league)

    private fun key(text: String): String =
        text.replace('’', '\'').trim().uppercase(Locale.ROOT).split(' ', '\t').filter { it.isNotEmpty() }.joinToString(" ")

    private fun tag(value: String, vararg keys: String): List<Pair<String, String>> = keys.map { key(it) to value }

    private val TAGS: Map<String, String> = listOf(
        // FIFA
        tag("U17 WC", "FU17W", "FIFA U-17 World Cup", "FIFA U17 World Cup", "U-17 World Cup", "fifa.world.u17"),
        tag("U20 WC", "FU20W", "FIFA U-20 World Cup", "FIFA U20 World Cup", "U-20 World Cup", "fifa.world.u20"),
        tag("WORLD CUP", "FIFA World Cup", "FIFA WC", "FWC", "fifa.world"),
        tag("WC QUAL", "FIFA World Cup Qualifying", "World Cup Qualifying", "WCQ", "fifa.worldq.uefa", "fifa.worldq.conmebol",
            "fifa.worldq.concacaf", "fifa.worldq.caf", "fifa.worldq.afc", "fifa.worldq.ofc"),
        tag("CLUB WC", "FIFA Club World Cup", "fifa.cwc"),
        tag("WOMEN'S WC", "FIFA Women's World Cup", "fifa.wwc"),
        tag("FRIENDLY", "Friendly", "Friendlies", "International Friendly", "Men's International Friendly", "FIFA Friendly",
            "Club Friendly", "Club Friendlies", "fifa.friendly", "club.friendly"),
        tag("U21 FRNDLY", "Men's U-21 Friendly", "U-21 International Friendly", "fifa.friendly_u21"),
        // UEFA
        tag("UCL", "UEFA Champions League", "Champions League", "UCL", "uefa.champions"),
        tag("UEL", "UEFA Europa League", "Europa League", "UEL", "uefa.europa"),
        tag("UECL", "UEFA Conference League", "UEFA Europa Conference League", "Conference League", "UECL", "uefa.europa.conf"),
        tag("UWCL", "UEFA Women's Champions League", "uefa.wchampions"),
        tag("NATIONS LG", "UEFA Nations League", "uefa.nations"),
        tag("EURO", "UEFA European Championship", "UEFA EURO", "uefa.euro"),
        tag("EURO QUAL", "UEFA European Championship Qualifying", "UEFA EURO Qualifying", "uefa.euroq"),
        tag("EURO U21", "UEFA European Under-21 Championship", "uefa.euro_u21"),
        // CONMEBOL, CONCACAF, CAF, AFC
        tag("COPA LIB", "CONMEBOL Libertadores", "Copa Libertadores", "CONMEBOL Copa Libertadores", "conmebol.libertadores"),
        tag("COPA SUD", "CONMEBOL Sudamericana", "Copa Sudamericana", "conmebol.sudamericana"),
        tag("COPA AM", "Copa América", "Copa America", "CONMEBOL Copa America", "conmebol.america"),
        tag("GOLD CUP", "CONCACAF Gold Cup", "concacaf.gold"),
        tag("CNCF NL", "CONCACAF Nations League", "concacaf.nations.league"),
        tag("CNCF CUP", "CONCACAF Champions Cup", "CONCACAF Champions League", "concacaf.champions"),
        tag("AFCON", "Africa Cup of Nations", "AFCON", "caf.nations"),
        tag("AFCON QUAL", "Africa Cup of Nations Qualifying", "caf.nations_qual"),
        tag("ASIAN CUP", "AFC Asian Cup", "afc.asian.cup"),
        tag("AFC CL", "AFC Champions League", "AFC Champions League Elite", "afc.champions"),
        // Leagues and cups
        tag("EPL", "English Premier League", "Premier League", "eng.1"),
        tag("CHAMPNSHP", "English League Championship", "EFL Championship", "eng.2"),
        tag("FA CUP", "English FA Cup", "FA Cup", "eng.fa"),
        tag("LEAGUE CUP", "English Carabao Cup", "Carabao Cup", "EFL Cup", "eng.league_cup"),
        tag("LA LIGA", "Spanish LALIGA", "LALIGA", "La Liga", "esp.1"),
        tag("COPA REY", "Spanish Copa del Rey", "Copa del Rey", "esp.copa_del_rey"),
        tag("SERIE A", "Italian Serie A", "ita.1"),
        tag("COPPA IT", "Italian Coppa Italia", "Coppa Italia", "ita.coppa_italia"),
        tag("BUNDESLIGA", "German Bundesliga", "ger.1"),
        tag("DFB POKAL", "German DFB Pokal", "DFB Pokal", "ger.dfb_pokal"),
        tag("LIGUE 1", "French Ligue 1", "fra.1"),
        tag("EREDIVISIE", "Dutch Eredivisie", "ned.1"),
        tag("PRIMEIRA", "Portuguese Primeira Liga", "Liga Portugal", "por.1"),
        tag("SPL", "Scottish Premiership", "sco.1"),
        tag("SUPER LIG", "Turkish Super Lig", "tur.1"),
        tag("SAUDI PRO", "Saudi Pro League", "ksa.1"),
        tag("LIGA 1 ROU", "Romanian Liga 1", "Romanian Liga I", "rou.1"),
        tag("LIGA MX", "Mexican Liga MX", "mex.1"),
        tag("BRAZIL A", "Brazilian Serie A", "Brasileirão", "Brasileirao", "bra.1"),
        // ESPN's own abbreviation is the key rows use: "Argentine LPF" read "AL" by its initials (TV QA live.20).
        tag("ARG LIGA", "Argentine Liga Profesional de Fútbol", "Argentine Liga Profesional", "Argentine LPF", "arg.1"),
        // More leagues whose ESPN abbreviation reads as bare initials ("Austrian Bundesliga" → "AB").
        tag("A-LEAGUE", "A-League Men", "Australian A-League Men", "aus.1"),
        tag("COLOMBIA", "Colombian Primera A", "col.1"),
        tag("CHILE", "Chilean Primera", "Chilean Primera División", "chi.1"),
        tag("URUGUAY", "Liga AUF Uruguaya", "uru.1"),
        tag("J.LEAGUE", "Japanese J1 League", "Japanese J.League", "jpn.1"),
        tag("BEL PRO", "Belgian Pro League", "bel.1"),
        tag("AUSTRIA", "Austrian Bundesliga", "aut.1"),
        tag("SWISS SL", "Swiss Super League", "sui.1"),
        tag("DENMARK", "Danish Superliga", "den.1"),
        tag("GREEK SL", "Greek Super League", "gre.1"),
        tag("MLS", "Major League Soccer", "usa.1"),
        tag("NWSL", "National Women's Soccer League", "usa.nwsl"),
        tag("WSL", "English Women's Super League", "Women's Super League", "eng.w.1"),
        // Basketball (#169)
        tag("EUROLEAGUE", "EuroLeague", "Turkish Airlines EuroLeague", "euroleague"),
        tag("EUROCUP", "EuroCup", "BKT EuroCup", "eurocup"),
        tag("G LEAGUE", "NBA G League", "nba-development"),
        // A college basketball game whose gender is not known yet (item 4).
        tag("COLLEGE", com.nuvio.tv.core.sportslive.scores.EspnLeagueMap.COLLEGE_BASKETBALL_EITHER)
    ).flatten().toMap()
}
