package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.Memo
import java.text.Normalizer

/**
 * ESPN's league index by name (#169 part A): the soccer and basketball competitions ESPN keeps a scoreboard
 * for, from its league index (`site.api.espn.com/apis/site/v2/leagues/dropdown`, November 2026) plus checked
 * slugs the index leaves out (Romania, Czechia, Switzerland, Israel...) and common other names for them
 * (TheSportsDB's, as #168 tags identified games). A card naming one ("Romanian Liga 1", "Saudi Pro League")
 * routes to its own feed instead of `soccer/all`, which lists only some competitions. Names too general to
 * tell one competition ("Premier League", "Super League", "Championship") and names two leagues share are
 * left out. Plain string work, kept per text (#152): no regex.
 */
object EspnLeagueIndex {

    private class Entry(val league: EspnLeague, val women: Boolean, val names: List<String>)

    private fun entry(sport: String, slug: String, women: Boolean, vararg names: String) = Entry(EspnLeague(sport, slug), women, names.toList())

    private val ENTRIES: List<Entry> = listOf(
        entry("soccer", "fifa.world", false, "fifa world cup"),
        entry("soccer", "fifa.wwc", true, "fifa womens world cup"),
        entry("soccer", "uefa.champions", false, "uefa champions league"),
        entry("soccer", "eng.1", false, "english premier league"),
        entry("soccer", "esp.1", false, "spanish laliga"),
        entry("soccer", "uefa.europa", false, "uefa europa league"),
        entry("soccer", "uefa.europa.conf", false, "uefa conference league"),
        entry("soccer", "eng.league_cup", false, "english carabao cup", "carabao cup"),
        entry("soccer", "concacaf.nations.league", false, "concacaf nations league"),
        entry("soccer", "uefa.nations", false, "uefa nations league"),
        entry("soccer", "ger.1", false, "german bundesliga", "bundesliga"),
        entry("soccer", "ita.1", false, "italian serie a", "serie a"),
        entry("soccer", "mex.1", false, "mexican liga bbva mx", "liga mx"),
        entry("soccer", "fifa.friendly", false, "mens international friendly"),
        entry("soccer", "concacaf.leagues.cup", false, "leagues cup"),
        entry("soccer", "campeones.cup", false, "campeones cup"),
        entry("soccer", "usa.nwsl.cup", true, "nwsl challenge cup"),
        entry("soccer", "fifa.shebelieves", true, "shebelieves cup"),
        entry("soccer", "uefa.wchampions", true, "uefa womens champions league"),
        entry("soccer", "fifa.w.champions_cup", true, "fifa womens champions cup", "womens champions cup"),
        entry("soccer", "eng.w.1", true, "english womens super league", "womens super league"),
        entry("soccer", "fra.1", false, "french ligue 1", "ligue 1"),
        entry("soccer", "eng.fa", false, "english fa cup"),
        entry("soccer", "esp.copa_del_rey", false, "spanish copa del rey", "copa del rey"),
        entry("soccer", "ger.dfb_pokal", false, "german cup"),
        entry("soccer", "ita.coppa_italia", false, "coppa italia"),
        entry("soccer", "fra.coupe_de_france", false, "coupe de france"),
        entry("soccer", "ned.1", false, "dutch eredivisie", "eredivisie"),
        entry("soccer", "esp.super_cup", false, "spanish supercopa"),
        entry("soccer", "ita.super_cup", false, "italian supercoppa"),
        entry("soccer", "fra.super_cup", false, "french trophee des champions", "trophee des champions"),
        entry("soccer", "ger.playoff.relegation", false, "german bundesliga promotion relegation playoff", "bundesliga pro rel"),
        entry("soccer", "ger.super_cup", false, "german supercup"),
        entry("soccer", "eng.2", false, "english league championship", "efl championship"),
        entry("soccer", "eng.charity", false, "english fa community shield", "community shield"),
        entry("soccer", "eng.w.promotion.relegation", true, "english womens super league promotion relegation playoff", "womens super league pro rel"),
        entry("soccer", "fifa.wworldq.uefa", true, "fifa womens world cup qualifying uefa", "wwcq uefa"),
        entry("soccer", "fifa.friendly.w", true, "womens international friendly"),
        entry("soccer", "usa.open", false, "u s open cup"),
        entry("soccer", "conmebol.libertadores", false, "conmebol libertadores"),
        entry("soccer", "fifa.worldq.uefa", false, "fifa world cup qualifying uefa", "wcq uefa"),
        entry("soccer", "fifa.wcq.ply", false, "fifa world cup qualifying playoff tournament", "wcq playoff tournament"),
        entry("soccer", "fifa.worldq.concacaf", false, "fifa world cup qualifying concacaf", "wcq concacaf"),
        entry("soccer", "fifa.worldq.afc", false, "fifa world cup qualifying afc", "wcq afc"),
        entry("soccer", "fifa.worldq.caf", false, "fifa world cup qualifying caf", "wcq caf"),
        entry("soccer", "fifa.worldq.conmebol", false, "fifa world cup qualifying conmebol", "wcq conmebol"),
        entry("soccer", "fifa.worldq.ofc", false, "fifa world cup qualifying ofc", "wcq ofc"),
        entry("soccer", "fifa.wwcq.ply", true, "fifa womens world cup qualifying playoff tournament", "wwcq playoff tournament"),
        entry("soccer", "uefa.w.nations", true, "uefa womens nations league", "womens nations league"),
        entry("soccer", "usa.w.usl.1", true, "usl super league"),
        entry("soccer", "uefa.champions_qual", false, "uefa champions league qualifying", "ucl qualifying"),
        entry("soccer", "uefa.wchampions_qual", true, "uefa womens champions league qualifying"),
        entry("soccer", "eng.w.fa", true, "english womens fa cup", "womens fa cup"),
        entry("soccer", "eng.w.league_cup", true, "english wsl players cup", "wsl players cup"),
        entry("soccer", "esp.w.1", true, "spanish liga f", "liga f"),
        entry("soccer", "esp.copa_de_la_reina", true, "spanish copa de la reina", "copa de la reina"),
        entry("soccer", "fra.w.1", true, "french premiere ligue", "premiere ligue"),
        entry("soccer", "ned.cup", false, "dutch knvb beker", "knvb beker"),
        entry("soccer", "sco.1", false, "scottish premiership", "spfl premiership", "scottish premier league"),
        entry("soccer", "sco.tennents", false, "scottish cup"),
        entry("soccer", "sco.cis", false, "scottish league cup"),
        entry("soccer", "aus.1", false, "australian a league men", "a league men", "australian a league"),
        entry("soccer", "aus.w.1", true, "australian a league women", "a league women"),
        entry("soccer", "ksa.1", false, "saudi pro league", "saudi arabian pro league", "saudi professional league", "roshn saudi league"),
        entry("soccer", "ksa.kings.cup", false, "saudi kings cup"),
        entry("soccer", "por.1", false, "portuguese primeira liga", "portuguese liga", "liga portugal", "liga portugal betclic", "portuguese liga nos", "primeira liga"),
        entry("soccer", "por.taca.portugal", false, "taca de portugal"),
        entry("soccer", "tur.1", false, "turkish super lig", "turkish super league", "super lig", "trendyol super lig"),
        entry("soccer", "caf.nations", false, "africa cup of nations"),
        entry("soccer", "afc.champions", false, "afc champions league elite", "afc champions league", "acl elite"),
        entry("soccer", "caf.nations_qual", false, "africa cup of nations qualifying", "afcon qualifying"),
        entry("soccer", "afc.cup", false, "afc champions league two"),
        entry("soccer", "fifa.cwc", false, "fifa club world cup", "club world cup"),
        entry("soccer", "concacaf.gold", false, "concacaf gold cup"),
        entry("soccer", "concacaf.gold_qual", false, "concacaf gold cup qualifying", "gold cup qualifying"),
        entry("soccer", "concacaf.w.gold", true, "concacaf w gold cup", "w gold cup"),
        entry("soccer", "concacaf.confederations_playoff", false, "concacaf cup", "conf pl", "confed cup playoff"),
        entry("soccer", "concacaf.w.champions_cup", true, "concacaf w champions cup", "w champions cup"),
        entry("soccer", "concacaf.womens.championship", true, "concacaf w championship"),
        entry("soccer", "uefa.euro", false, "uefa european championship"),
        entry("soccer", "uefa.euroq", false, "uefa european championship qualifying", "euro qualifying"),
        entry("soccer", "uefa.weuro", true, "uefa womens european championship", "womens euro"),
        entry("soccer", "uefa.euro_u21", false, "uefa european under 21 championship", "euro under 21"),
        entry("soccer", "uefa.super_cup", false, "uefa super cup"),
        entry("soccer", "conmebol.america", false, "copa america"),
        entry("soccer", "conmebol.america.femenina", true, "copa america femenina"),
        entry("soccer", "usa.usl.1", false, "usl championship"),
        entry("soccer", "usa.usl.l1", false, "usl league one"),
        entry("soccer", "usa.usl.l1.cup", false, "usl cup"),
        entry("soccer", "mex.2", false, "mexican liga de expansion mx", "liga de expansion mx"),
        entry("soccer", "mex.w.1", true, "mexican liga bbva mx femenil", "liga mx femenil"),
        entry("soccer", "global.finalissima", false, "conmebol uefa cup of champions", "mens finalissima"),
        entry("soccer", "global.u20.intercontinental_cup", false, "conmebol uefa u20 intercontinental cup", "u20 intercontinental cup"),
        entry("soccer", "global.w.finalissima", true, "conmebol uefa womens cup of champions", "womens finalissima"),
        entry("soccer", "fifa.world.u20", false, "fifa under 20 world cup", "fifa u 20 world cup"),
        entry("soccer", "afc.asian.cup", false, "afc asian cup"),
        entry("soccer", "afc.w.asian.cup", true, "afc womens asian cup"),
        entry("soccer", "afc.cupq", false, "afc asian cup qualifiers", "afc cup qualifying", "asian cup qualifiers"),
        entry("soccer", "aff.championship", false, "asean championship", "asean champ"),
        entry("soccer", "caf.w.nations", true, "womens africa cup of nations"),
        entry("soccer", "caf.championship", false, "african nations championship"),
        entry("soccer", "can.w.nsl", true, "northern super league"),
        entry("soccer", "uefa.europa_qual", false, "uefa europa league qualifying", "uefa europa league qualfiying", "uel qualifying"),
        entry("soccer", "uefa.europa.conf_qual", false, "uefa conference league qualifying", "uecl qualifying"),
        entry("soccer", "uefa.w.europa", true, "uefa womens europa cup", "womens europa cup"),
        entry("soccer", "fifa.intercontinental_cup", false, "fifa intercontinental cup", "intercontinental cup"),
        entry("soccer", "afc.champions_qual", false, "afc champions league elite qualifying"),
        entry("soccer", "afc.cup_qual", false, "afc champions league two qualifying"),
        entry("soccer", "club.friendly", false, "club friendly"),
        entry("soccer", "nonfifa", false, "non fifa friendly"),
        entry("soccer", "rus.1", false, "russian premier league", "russian premier"),
        entry("soccer", "rus.1.promotion.relegation", false, "russian premier league relegation promotion playoffs", "russian premier league pro rel"),
        entry("soccer", "bel.1", false, "belgian pro league", "belgian first division a", "jupiler pro league"),
        entry("soccer", "bel.promotion.relegation", false, "belgian pro league promotion relegation playoffs", "belgian pro league pro rel"),
        entry("soccer", "esp.2", false, "spanish laliga 2", "laliga 2"),
        entry("soccer", "ger.2", false, "german 2 bundesliga", "2 bundesliga"),
        entry("soccer", "ita.2", false, "italian serie b"),
        entry("soccer", "fra.1.promotion.relegation", false, "french ligue 1 promotion relegation playoffs", "ligue 1 pro rel"),
        entry("soccer", "fra.2", false, "french ligue 2", "ligue 2"),
        entry("soccer", "por.1.promotion.relegation", false, "portuguese primeira liga promotion relegation playoffs", "portuguese liga pro rel"),
        entry("soccer", "aut.1", false, "austrian bundesliga", "admiral bundesliga"),
        entry("soccer", "gre.1", false, "greek super league", "greek superleague", "super league greece", "greek super league 1"),
        entry("soccer", "chn.1", false, "chinese super league"),
        entry("soccer", "global.club_challenge", false, "conmebol uefa club challenge"),
        entry("soccer", "ned.supercup", false, "dutch johan cruyff shield", "johan cruyff shield"),
        entry("soccer", "global.pinatar_cup", false, "pinatar cup"),
        entry("soccer", "friendly.emirates_cup", false, "emirates cup"),
        entry("soccer", "esp.joan_gamper", false, "trofeo joan gamper"),
        entry("soccer", "jpn.world_challenge", false, "japanese j league world challenge", "j league world challenge"),
        entry("soccer", "global.arnold.clark_cup", false, "arnold clark cup"),
        entry("soccer", "fifa.world.u17", false, "fifa under 17 world cup", "fifa u 17 world cup"),
        entry("soccer", "fifa.wworld.u17", true, "fifa under 17 womens world cup", "fifa u 17 womens world cup"),
        entry("soccer", "uefa.euro_u21_qual", false, "uefa european under 21 championship qualifying", "euro u 21 qualifying"),
        entry("soccer", "uefa.euro.u19", false, "uefa european under 19 championship", "euro under 19"),
        entry("soccer", "fifa.friendly_u21", false, "under 21 international friendly", "mens u 21 friendly"),
        entry("soccer", "ger.2.promotion.relegation", false, "german bundesliga 2 promotion relegation playoffs", "2 bundesliga pro rel"),
        entry("soccer", "eng.trophy", false, "english efl trophy", "efl trophy"),
        entry("soccer", "eng.3", false, "english league one", "efl league one"),
        entry("soccer", "eng.4", false, "english league two", "efl league two"),
        entry("soccer", "eng.5", false, "english national league"),
        entry("soccer", "eng.fa_qual", false, "english fa cup qualifying"),
        entry("soccer", "sco.1.promotion.relegation", false, "scottish premiership promotion relegation playoffs", "spfl premiership pro rel"),
        entry("soccer", "sco.2", false, "scottish championship", "spfl championship"),
        entry("soccer", "sco.2.promotion.relegation", false, "scottish championship promotion relegation playoffs", "spfl championship pro rel"),
        entry("soccer", "sco.challenge", false, "scottish league challenge cup", "spfl challenge cup"),
        entry("soccer", "sco.tennents_qual", false, "scottish cup qualifying"),
        entry("soccer", "ned.playoff.relegation", false, "dutch eredivisie promotion relegation playoffs", "eredivisie pro rel"),
        entry("soccer", "ned.2", false, "dutch keuken kampioen divisie", "keuken kampioen divisie"),
        entry("soccer", "ned.3.promotion.relegation", false, "dutch tweede divisie promotion relegation playoffs", "dutch playoffs 3 4", "dutch tweede divisie pro rel playoffs"),
        entry("soccer", "ned.w.knvb_cup", true, "dutch knvb beker vrouwen", "dutch vrouwen knvb beker"),
        entry("soccer", "ned.w.1", true, "dutch vrouwen eredivisie"),
        entry("soccer", "swe.1", false, "swedish allsvenskan"),
        entry("soccer", "swe.1.promotion.relegation", false, "swedish allsvenskan promotion relegation playoffs", "swedish allsvenskan pro rel"),
        entry("soccer", "den.1", false, "danish superliga", "danish superligaen", "superligaen"),
        entry("soccer", "nor.1.promotion.relegation", false, "norwegian eliteserien promotion relegation playoffs", "norwegian eliteserien pro rel", "eliteserien pro rel"),
        entry("soccer", "nor.1", false, "norwegian eliteserien"),
        entry("soccer", "conmebol.sudamericana", false, "conmebol sudamericana"),
        entry("soccer", "conmebol.recopa", false, "conmebol recopa"),
        entry("soccer", "arg.1", false, "argentine liga profesional de futbol", "argentine lpf"),
        entry("soccer", "arg.copa", false, "copa argentina"),
        entry("soccer", "arg.copa_de_la_superliga", false, "argentine copa de la superliga", "asuperpliga", "argcopasuperliga"),
        entry("soccer", "arg.trofeo_de_la_campeones", false, "argentine trofeo de campeones"),
        entry("soccer", "arg.2", false, "argentine nacional b"),
        entry("soccer", "arg.supercopa", false, "argentine supercopa"),
        entry("soccer", "arg.supercopa.internacional", false, "argentine supercopa internacional", "supercopa internacional"),
        entry("soccer", "arg.3", false, "argentine primera b"),
        entry("soccer", "bra.supercopa_do_brazil", false, "brazilian supercopa rei", "supercopa rei"),
        entry("soccer", "bra.1", false, "brazilian serie a", "brazil serie a"),
        entry("soccer", "bra.2", false, "brazilian serie b", "brazil serie b"),
        entry("soccer", "bra.copa_do_brazil", false, "copa do brasil", "copa do brazil"),
        entry("soccer", "bra.camp.carioca", false, "brazilian campeonato carioca", "brazil carioca"),
        entry("soccer", "bra.camp.paulista", false, "brazilian campeonato paulista", "brazil paulista"),
        entry("soccer", "bra.camp.gaucho", false, "brazilian campeonato gaucho", "brazil gaucho"),
        entry("soccer", "bra.camp.mineiro", false, "brazilian campeonato mineiro", "brazil mineiro"),
        entry("soccer", "chi.super_cup", false, "chilean supercopa"),
        entry("soccer", "chi.1", false, "chilean primera division", "chilean primera"),
        entry("soccer", "chi.1.promotion.relegation", false, "chilean primera division promotion relegation playoffs", "chilean pro rel"),
        entry("soccer", "chi.copa_chi", false, "copa chile"),
        entry("soccer", "uru.1", false, "liga auf uruguaya"),
        entry("soccer", "uru.2", false, "segunda division de uruguay"),
        entry("soccer", "col.superliga", false, "colombian superliga"),
        entry("soccer", "col.1", false, "colombian primera a"),
        entry("soccer", "col.copa", false, "copa colombia"),
        entry("soccer", "per.1", false, "peruvian liga 1", "peru liga 1"),
        entry("soccer", "par.1", false, "paraguayan primera division", "paraguayan primera"),
        entry("soccer", "par.1.supercopa", false, "paraguayan supercopa"),
        entry("soccer", "ecu.1", false, "ligapro ecuador"),
        entry("soccer", "ven.1", false, "venezuelan primera division", "liga futve"),
        entry("soccer", "bol.ply.rel", false, "bolivian liga profesional promotion relegation playoffs", "bolivian liga pro rel"),
        entry("soccer", "bol.copa", false, "copa bolivia"),
        entry("soccer", "bol.1", false, "bolivian liga profesional"),
        entry("soccer", "jpn.1", false, "japanese j league", "japanese j1 league", "j1 league", "j league"),
        entry("soccer", "mex.campeon", false, "mexican campeon de campeones", "campeon de campeones"),
        entry("soccer", "concacaf.central.american.cup", false, "concacaf central american cup"),
        entry("soccer", "concacaf.champions_cup", false, "conc cc"),
        entry("soccer", "concacaf.u23", false, "concacaf u23 tournament", "conc u23"),
        entry("soccer", "hon.1", false, "honduran liga nacional"),
        entry("soccer", "crc.1", false, "costa rican primera division", "liga fpd"),
        entry("soccer", "gua.1", false, "guatemalan liga nacional"),
        entry("soccer", "slv.1", false, "salvadoran primera division", "salvadoran primera"),
        entry("soccer", "fifa.intercontinental.cup", false, "intercontinental cup india"),
        entry("soccer", "afc.saff.championship", false, "saff championship"),
        entry("soccer", "chn.1.promotion.relegation", false, "chinese super league promotion relegation playoffs", "chinese pro rel"),
        entry("soccer", "ind.1", false, "indian super league"),
        entry("soccer", "ind.2", false, "indian football league"),
        entry("soccer", "global.gulf_cup", false, "arabian gulf cup"),
        entry("soccer", "caf.cosafa", false, "cosafa cup"),
        entry("soccer", "caf.champions", false, "caf champions league"),
        entry("soccer", "caf.confed", false, "caf confederation cup"),
        entry("soccer", "rsa.1", false, "south african premiership", "south african premier"),
        entry("soccer", "usa.ncaa.m.1", false, "ncaa mens soccer", "ncaam soccer"),
        entry("soccer", "usa.ncaa.w.1", true, "ncaa womens soccer", "ncaaw soccer"),
        entry("basketball", "fiba", false, "fiba world cup"),
        entry("basketball", "mens-college-basketball", false, "ncaa mens basketball"),
        entry("basketball", "nba", false, "national basketball association"),
        entry("basketball", "nba-development", false, "nba g league"),
        entry("basketball", "nba-summer-california", false, "nba california classic summer league"),
        entry("basketball", "nba-summer-golden-state", false, "golden state summer league"),
        entry("basketball", "nba-summer-las-vegas", false, "las vegas summer league"),
        entry("basketball", "nba-summer-orlando", false, "orlando summer league"),
        entry("basketball", "nba-summer-sacramento", false, "sacramento summer league"),
        entry("basketball", "nba-summer-utah", false, "salt lake city summer league"),
        entry("basketball", "nbl", false, "australian nbl", "nbl australia"),
        entry("basketball", "wnba", true, "womens national basketball association"),
        entry("basketball", "womens-college-basketball", true, "ncaa womens basketball"),
        entry("soccer", "rou.1", false, "romanian liga 1", "romanian liga i", "romanian superliga", "superliga romania", "liga i romania", "romania liga 1"),
        entry("soccer", "cze.1", false, "czech first league", "czech liga", "gambrinus liga", "chance liga", "czech chance liga", "czech fortuna liga"),
        entry("soccer", "sui.1", false, "swiss super league", "swiss superleague"),
        entry("soccer", "isr.1", false, "israeli premier league", "ligat haal", "israeli ligat haal"),
        entry("soccer", "irl.1", false, "irish premier division", "league of ireland premier division", "league of ireland"),
        entry("soccer", "cyp.1", false, "cypriot first division", "cyprus first division"),
        entry("soccer", "tha.1", false, "thai league 1", "thai league"),
        entry("soccer", "idn.1", false, "indonesian super league", "indonesian liga 1", "liga 1 indonesia"),
        entry("soccer", "mys.1", false, "malaysian super league", "malaysia super league"),
        entry("soccer", "fin.1", false, "finnish veikkausliiga", "veikkausliiga")
    )

    // Every name, padded for whole-word search, longest first: the most specific name wins
    // ("austrian bundesliga" before "bundesliga").
    private val NAMES: List<Pair<String, Entry>> =
        ENTRIES.flatMap { entry -> entry.names.map { " $it " to entry } }.sortedByDescending { it.first.length }

    private val WOMEN: Set<EspnLeague> = ENTRIES.filter { it.women }.mapTo(HashSet()) { it.league }

    private val found = Memo(8_192) { text: String -> findUncached(text) }

    /** The league [text] names (whole words, any case or accents); null when none. */
    fun find(text: String): EspnLeague? = found(text)

    /** True when [league] is a women's competition of this index ("eng.w.1", "uefa.wchampions"). */
    fun isWomen(league: EspnLeague): Boolean = league in WOMEN

    /** Every league of the index. */
    val leagues: List<EspnLeague> get() = ENTRIES.map { it.league }

    private fun findUncached(text: String): EspnLeague? {
        val key = key(text)
        if (key.isEmpty()) return null
        val padded = " $key "
        return NAMES.firstOrNull { (name, _) -> padded.contains(name) }?.second?.league
    }

    /** Lowercase letters and digits, accents and apostrophes dropped, every other run of characters one space. */
    internal fun key(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        var space = false
        for (c in decomposed) {
            when {
                Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> Unit
                c == '\'' || c == '\u2019' -> Unit
                c.isLetterOrDigit() -> {
                    if (space && out.isNotEmpty()) out.append(' ')
                    space = false
                    out.append(c.lowercaseChar())
                }
                else -> space = true
            }
        }
        return out.toString()
    }
}
