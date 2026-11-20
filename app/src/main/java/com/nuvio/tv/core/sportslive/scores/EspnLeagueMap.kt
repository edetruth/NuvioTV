package com.nuvio.tv.core.sportslive.scores

import androidx.annotation.VisibleForTesting
import com.nuvio.tv.core.sportslive.Memo
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.domain.model.SportsLiveItem
import java.text.Normalizer

/** A card's sport, its own texts (title, genres) and its routing-only texts (catalog name). */
private typealias CardTexts = Triple<String?, List<String>, List<String>>

/**
 * Picks the ESPN scoreboard feed(s) for a card from its sport and the competition keywords in its title,
 * genres and catalog name. Data-driven: add a row to [COMPETITIONS] or [SPORTS] to extend.
 *
 * Routing narrows which feeds are fetched and keeps women's, minor-league and college cards away from
 * the pro feeds whose teams share their names or cities; [ScoreMatcher] still requires both teams to match.
 */
object EspnLeagueMap {
    // Every card is routed on each load and again on each team directory update (#152): the answers for a
    // card's texts (sport, title and genres, catalog name) are kept. First, so they are set before any other initialiser runs.
    private const val CARD_CACHE_SIZE = 8_192
    private val routed = Memo(CARD_CACHE_SIZE) { (sport, hints, routing): CardTexts -> leaguesForUncached(sport, hints, routing) }
    private val collegeFallback = Memo(CARD_CACHE_SIZE) { (sport, hints, routing): CardTexts ->
        collegeFallbackForUncached(sport, hints, routing)
    }
    private val inferableTexts = Memo(CARD_CACHE_SIZE) { (sport, hints, routing): CardTexts ->
        val own = (listOfNotNull(sport) + hints).map(::fold)
        val texts = own + routing.map(::fold)
        own.none { EXCLUDED.containsMatchIn(it) || WOMEN_MARK.containsMatchIn(it) } && texts.none { COLLEGE.containsMatchIn(it) }
    }
    private val genders = Memo(CARD_CACHE_SIZE) { (sport, hints, routing): CardTexts ->
        genderOf((listOfNotNull(sport) + hints + routing).map(::fold))
    }

    val NBA = EspnLeague("basketball", "nba")
    val NFL = EspnLeague("football", "nfl")
    val NHL = EspnLeague("hockey", "nhl")
    val MLB = EspnLeague("baseball", "mlb")
    val COLLEGE_FOOTBALL = EspnLeague("football", "college-football")
    val COLLEGE_BASKETBALL = EspnLeague("basketball", "mens-college-basketball")
    // Women's leagues (#163). Their teams' names carry no women's marker: the feed itself says it.
    val COLLEGE_BASKETBALL_WOMEN = EspnLeague("basketball", "womens-college-basketball")
    val WNBA = EspnLeague("basketball", "wnba")
    val NWSL = EspnLeague("soccer", "usa.nwsl")

    // Leagues routed since #169: the NBA G League and NCAA volleyball on ESPN, EuroLeague and EuroCup on their
    // own feed ([EuroLeagueScoreSource]; ESPN's euroleague scoreboard lists no games).
    val G_LEAGUE = EspnLeague("basketball", "nba-development")
    val EUROLEAGUE = EspnLeague("basketball", "euroleague")
    val EUROCUP = EspnLeague("basketball", "eurocup")
    val COLLEGE_VOLLEYBALL_WOMEN = EspnLeague("volleyball", "womens-college-volleyball")
    val COLLEGE_VOLLEYBALL = EspnLeague("volleyball", "mens-college-volleyball")
    val RUGBY_WORLD_CUP_WOMEN = EspnLeague("rugby", "289237")

    /** The women's feeds: a game on one is a women's game, whatever its teams are called. */
    val WOMEN_FEEDS = setOf(COLLEGE_BASKETBALL_WOMEN, WNBA, NWSL, COLLEGE_VOLLEYBALL_WOMEN, RUGBY_WORLD_CUP_WOMEN)
    val SOCCER_ALL = EspnLeague("soccer", "all")

    // A women's feed here or a women's competition of the league index (#169).
    private fun isWomenFeed(league: EspnLeague) = league in WOMEN_FEEDS || EspnLeagueIndex.isWomen(league)

    // Individual sports (#55 part 2): Results only, never routed from a card (see EXCLUDED).
    val ATP = EspnLeague("tennis", "atp")
    val WTA = EspnLeague("tennis", "wta")
    val UFC = EspnLeague("mma", "ufc")
    val F1 = EspnLeague("racing", "f1")
    val PGA = EspnLeague("golf", "pga")
    val LPGA = EspnLeague("golf", "lpga")

    private fun soccer(vararg slugs: String) = slugs.map { EspnLeague("soccer", it) }

    private val WORLD_CUP_QUALIFIERS = listOf(
        Regex("""\b(uefa|europe(an)?)\b""") to "fifa.worldq.uefa",
        Regex("""\b(conmebol|south america(n)?)\b""") to "fifa.worldq.conmebol",
        Regex("""\bconcacaf\b""") to "fifa.worldq.concacaf",
        Regex("""\b(caf|africa(n)?)\b""") to "fifa.worldq.caf",
        Regex("""\b(afc|asia(n)?)\b""") to "fifa.worldq.afc",
        Regex("""\b(ofc|oceania)\b""") to "fifa.worldq.ofc"
    )

    /** A keyword rule: when [pattern] is found in a hint, [leagues] gives the feeds for that hint. */
    private class Rule(pattern: String, val leagues: (String) -> List<EspnLeague>) {
        val regex = Regex(pattern)
        constructor(pattern: String, fixed: List<EspnLeague>) : this(pattern, { fixed })
    }

    // Competitions named on the card. In order: the first rule found in a hint wins, so the more specific
    // rule ("AFCON qualifiers", "Conference League") comes before the general one.
    private val COMPETITIONS: List<Rule> = listOf(
        // Women's competitions first (#163): "Women's College Basketball" also says "college basketball".
        Rule("""\bwnba\b""", listOf(WNBA)),
        Rule(
            """\bncaaw\b|\bwbb\b|\bwcbb\b|women'?s? college basketball|college women'?s? basketball|\bncaa women'?s? basketball\b""",
            listOf(COLLEGE_BASKETBALL_WOMEN)
        ),
        Rule("""\bnwsl\b|national women'?s? soccer league""", listOf(NWSL)),
        Rule("""\buwcl\b|women'?s? champions league""", soccer("uefa.wchampions")),
        // #169: the G League before the NBA ("NBA G League"); EuroLeague and EuroCup basketball.
        Rule("""\bg[- ]?league\b|\bnba development\b""", listOf(G_LEAGUE)),
        Rule("""\beuro ?league\b""", listOf(EUROLEAGUE)),
        Rule("""\beurocup\b""", listOf(EUROCUP)),
        Rule("""\bnba\b""", listOf(NBA)),
        Rule("""\bnfl\b""", listOf(NFL)),
        Rule("""\bnhl\b""", listOf(NHL)),
        Rule("""\bmlb\b""", listOf(MLB)),
        Rule("""college (american )?football|\bncaaf\b|\bncaa (american )?football\b|\bcfb\b""", listOf(COLLEGE_FOOTBALL)),
        Rule("""college basketball|\bncaab\b|\bncaam\b|\bncaa (men'?s )?basketball\b""", listOf(COLLEGE_BASKETBALL)),
        Rule("""world cup qualif|\bwc qualif|\bwcq\b""") { hint ->
            val confederations = WORLD_CUP_QUALIFIERS.filter { (regex, _) -> regex.containsMatchIn(hint) }
            soccer(*(confederations.ifEmpty { WORLD_CUP_QUALIFIERS }).map { it.second }.toTypedArray())
        },
        Rule("""world cup""", soccer("fifa.world")),
        Rule("""(\bafcon\b|africa(n)? cup of nations).{0,20}qualif|\bafcon q\b""", soccer("caf.nations_qual")),
        Rule("""\bafcon\b|africa(n)? cup of nations""", soccer("caf.nations", "caf.nations_qual")),
        Rule("""(?<!caf |afc |concacaf )champions league|\bucl\b""", soccer("uefa.champions")),
        Rule("""conference league|\buecl\b""", soccer("uefa.europa.conf")),
        Rule("""europa league|\buel\b""", soccer("uefa.europa")),
        Rule("""(?<!concacaf )nations league""", soccer("uefa.nations")),
        Rule("""(?<![a-z] )premier league|english premier league|\bepl\b""", soccer("eng.1")),
        Rule("""\bla ?liga\b(?! ?2)""", soccer("esp.1")),
        // Brazil's top flight before Italy's: "Brazil Serie A" names it, as ESPN does (#132 P4).
        Rule("""\bbrazil(ian)? serie a\b|\bbrasileir(a|ao)\b""", soccer("bra.1")),
        Rule("""\bserie a\b""", soccer("ita.1")),
        // Austria's Bundesliga is its own (#169: ESPN's league index routes it).
        Rule("""(?<!2\. )(?<!2 )(?<!austrian )(?<!admiral )\bbundesliga\b""", soccer("ger.1")),
        Rule("""\bligue 1\b""", soccer("fra.1")),
        Rule("""\bmls\b|major league soccer""", soccer("usa.1")),
        Rule("""\bfriendl(y|ies)\b""", soccer("fifa.friendly"))
    )

    // The card's sport when no competition is named, checked rule by rule across all texts, so an
    // "American Football" genre beats a "Football" sport. Soccer ("Football") is the last fallback.
    private val SPORTS: List<Rule> = listOf(
        Rule("""american[ _]football""", listOf(NFL)),
        Rule("""\bbasketball\b""", listOf(NBA)),
        Rule("""\b(ice )?hockey\b""", listOf(NHL)),
        Rule("""\bbaseball\b""", listOf(MLB)),
        // College only (#169): a pro volleyball card gets none ([leaguesForUncached]).
        Rule("""\bvolleyball\b""", listOf(COLLEGE_VOLLEYBALL_WOMEN, COLLEGE_VOLLEYBALL)),
        Rule("""\bsoccer\b|\bfootball\b""", listOf(SOCCER_ALL))
    )

    private val VOLLEYBALL_FEEDS = setOf(COLLEGE_VOLLEYBALL_WOMEN, COLLEGE_VOLLEYBALL)

    /**
     * Rugby union competitions on ESPN (#169), by ESPN's league id. Rugby stays an excluded sport for every
     * other feed; a card naming one of these gets its feed. The general names (cups, test matches) need the
     * card to say rugby; rugby league (NRL, Super League) and sevens never route here.
     */
    private val RUGBY: List<Pair<Regex, String>> = listOf(
        Regex("""women'?s? rugby world cup""") to "289237",
        Regex("""united rugby championship|\burc\b""") to "270557",
        Regex("""rugby championship""") to "244293",
        Regex("""\bsix nations\b|\b6 nations\b""") to "180659",
        Regex("""gallagher prem|premiership rugby|\bprem rugby\b|rugby premiership""") to "267979",
        Regex("""\btop ?14\b""") to "270559",
        Regex("""\bsuper rugby\b""") to "242041",
        Regex("""major league rugby|\bmlr\b""") to "289262",
        Regex("""lions tour|british (and|&) irish lions""") to "268565"
    )
    // Only when the card says rugby: these names mean other things in other sports.
    private val RUGBY_IN_CONTEXT: List<Pair<Regex, String>> = listOf(
        Regex("""\bchampions cup\b""") to "271937",
        Regex("""\bchallenge cup\b""") to "272073",
        Regex("""\bpremiership\b""") to "267979",
        Regex("""\bnations championship\b""") to "17567",
        Regex("""\bnpc\b|mitre 10|bunnings""") to "270563",
        Regex("""\btest\b|\binternational\b|autumn nations|nations series|\bfriendly\b""") to "289234"
    )
    private val RUGBY_WORD = Regex("""\brugby\b""")
    private val NOT_RUGBY_UNION = Regex("""rugby league|\bnrl\b|\bsevens\b|\b7s\b|super league""")

    /**
     * The rugby union feed [texts] name (#169), or null: a named competition, or a general one on a card that
     * says rugby. A women's card goes only to the women's World Cup; any other card never does.
     */
    private fun rugbyLeague(texts: List<String>, women: Boolean): List<EspnLeague>? {
        if (texts.any { NOT_RUGBY_UNION.containsMatchIn(it) }) return null
        val saysRugby = texts.any { RUGBY_WORD.containsMatchIn(it) }
        val rules = if (saysRugby) RUGBY + RUGBY_IN_CONTEXT else RUGBY
        for (text in texts) {
            val id = rules.firstOrNull { (regex, _) -> regex.containsMatchIn(text) }?.second ?: continue
            val league = EspnLeague("rugby", id)
            return if (women == (league in WOMEN_FEEDS)) listOf(league) else emptyList()
        }
        return null
    }

    /**
     * The competition of ESPN's league index ([EspnLeagueIndex]) the first text names, when its sport is the
     * card's (#169): a "Basketball" card is never moved onto a soccer league. [sport] is the card's sport rule's
     * feeds ([firstSport]); null when the card names no sport.
     */
    private fun indexedLeague(texts: List<String>, sport: List<EspnLeague>?): List<EspnLeague>? {
        val sports = sport?.mapTo(HashSet()) { it.sport }
        for (text in texts) {
            val league = EspnLeagueIndex.find(text) ?: continue
            return if (sports == null || league.sport in sports) listOf(league) else null
        }
        return null
    }

    // A card that mentions any of these anywhere gets no feed: sports scores v1 does not cover, and
    // minor leagues whose teams share names or cities with the pro feeds (PWHL, AHL...). A women's card goes
    // to the women's feeds only, and to none where there is no women's feed ([forGender], #163). The G League
    // has its own feed since #169; rugby union's named competitions do too ([rugbyLeague]).
    private val EXCLUDED = Regex(
        """\b(cricket|icc|t20|odi|ipl|w?bbl|big bash|test match|tennis|ufc|mma|boxing|golf|f1|formula 1|motogp|nascar|rugby|wwe|aew|wrestling|""" +
            """darts|snooker|pwhl|ahl|echl)\b"""
    )

    /** Whose game a card is (#163), when it says. */
    private enum class Gender { WOMEN, MEN }

    // "Women's", "WBB", "NCAAW", "WNBA", "NWSL", or a "(W)" / " W" side ("Purdue W vs Florida State W").
    private val WOMEN_MARK = Regex(
        """\bwomen'?s?\b|\bwbb\b|\bwcbb\b|\bncaaw\b|\bwnba\b|\bnwsl\b|\buwcl\b|\(w\)|\bw(?=\s+(?:vs?\.?|at|@|x)\s)|\sw$"""
    )
    // The word itself: a card that says "Women's" and has no women's feed gets none, as before #163.
    private val WOMEN_WORD = Regex("""\bwomen'?s?\b""")
    private val MEN_MARK = Regex("""\bmen'?s\b|\bncaam\b|\bmbb\b|\bnba\b""")

    // Both or neither said: unknown.
    private fun genderOf(texts: List<String>): Gender? {
        val women = texts.any { WOMEN_MARK.containsMatchIn(it) }
        val men = texts.any { MEN_MARK.containsMatchIn(it) }
        return when {
            women && !men -> Gender.WOMEN
            men && !women -> Gender.MEN
            else -> null
        }
    }

    /**
     * [leagues] for a card of [gender]: a women's card's basketball goes to the women's feeds (college: NCAAW;
     * a bare "Basketball": NCAAW and the WNBA), and to no other feed when it says "Women's" ([saysWomen]); a
     * "(W)" side on a mixed feed (soccer/all) stays, as the matcher's qualifier tells the games apart. A men's
     * card never goes to a women's feed.
     */
    private fun forGender(leagues: List<EspnLeague>, gender: Gender?, saysWomen: Boolean): List<EspnLeague> = when (gender) {
        null -> leagues
        Gender.MEN -> leagues.filter { !isWomenFeed(it) }
        Gender.WOMEN -> leagues.flatMap { league ->
            when {
                isWomenFeed(league) -> listOf(league)
                league == NBA -> listOf(COLLEGE_BASKETBALL_WOMEN, WNBA)
                league == COLLEGE_BASKETBALL -> listOf(COLLEGE_BASKETBALL_WOMEN)
                saysWomen -> emptyList()
                else -> listOf(league)
            }
        }.distinct()
    }

    // A college card routes only to a college feed, never to the pro league of the same sport.
    private val COLLEGE = Regex("""\bncaa\w*|\bcollege\b""")
    private val COLLEGE_FEEDS = setOf(COLLEGE_FOOTBALL, COLLEGE_BASKETBALL, COLLEGE_BASKETBALL_WOMEN) + VOLLEYBALL_FEEDS

    /**
     * Feeds for [item]: its sport, title, genres and catalog name. Empty when none applies. The catalog
     * name routes but never excludes: a mixed "Live Sports (Soccer, Tennis, F1)" catalog does not block
     * its soccer cards.
     */
    fun leaguesFor(item: SportsLiveItem): List<EspnLeague> =
        leaguesFor(
            sport = SportsLiveTeams.knownSport(item),
            hints = listOf(item.meta.name) + item.meta.genres,
            routingHints = listOf(item.catalogName)
        )

    /**
     * Feeds for a card with [sport] (null when unknown) and text [hints] in priority order (title first).
     * A competition named in the sport or a hint wins; otherwise the sport word decides ("Basketball",
     * "Football" = soccer). A card whose sport or hints name an excluded sport or league gets none, as
     * does a college card routed to a pro feed. A college card naming no sport at all ("COLLEGE", #145)
     * gets every college feed, men's and women's basketball included (#163); [ScoreMatcher]'s two-team match,
     * and the start when the same schools play twice that day, picks the one its game is on. A women's card
     * ("Women's", "W", "WBB", "NCAAW") goes to women's feeds only, a men's card to men's only.
     * [routingHints] (the catalog name) route only.
     */
    fun leaguesFor(sport: String?, hints: List<String>, routingHints: List<String> = emptyList()): List<EspnLeague> =
        routed(Triple(sport, hints, routingHints))

    @VisibleForTesting
    internal fun leaguesForUncached(sport: String?, hints: List<String>, routingHints: List<String>): List<EspnLeague> {
        val own = (listOfNotNull(sport) + hints).map(::fold).filter { it.isNotEmpty() }
        val texts = own + routingHints.map(::fold).filter { it.isNotEmpty() }
        val gender = genders(Triple(sport, hints, routingHints))
        // Rugby union's named competitions (#169) before the exclusions, which still keep every other rugby card out.
        rugbyLeague(texts, women = gender == Gender.WOMEN)?.let { return it }
        if (own.any { EXCLUDED.containsMatchIn(it) }) return emptyList()
        val sportRule = firstSport(texts)
        // A competition ESPN's league index names comes after the known ones, before the bare sport word (#169).
        val named = firstCompetition(texts) ?: indexedLeague(texts, sportRule) ?: sportRule
        val leagues = forGender(named.orEmpty(), gender, saysWomen = own.any { WOMEN_WORD.containsMatchIn(it) })
        val college = texts.any { COLLEGE.containsMatchIn(it) }
        return when {
            // NCAA volleyball is the only volleyball ESPN lists: a pro volleyball card gets none.
            !college -> leagues.filter { it !in VOLLEYBALL_FEEDS }
            // A women's college card naming no sport: NCAAW alone (there is no women's college football feed).
            named == null -> forGender(COLLEGE_ONLY, gender, saysWomen = true)
            else -> leagues.filter { it in COLLEGE_FEEDS }
        }
    }

    // A college card that names no sport: any college feed.
    private val COLLEGE_ONLY = listOf(COLLEGE_BASKETBALL, COLLEGE_BASKETBALL_WOMEN, COLLEGE_FOOTBALL)

    /**
     * True when one of [a] and [b] says it is a women's game and the other a men's one (#163): never one game,
     * whatever their teams are called. A card that says neither ("Basketball", "COLLEGE") may be either.
     */
    fun otherGender(a: SportsLiveItem, b: SportsLiveItem): Boolean {
        val first = genderOf(a) ?: return false
        val second = genderOf(b) ?: return false
        return first != second
    }

    /** True when [item] says it is a women's game ("Women's", "W" sides, NCAAW, WNBA...), live.18. */
    fun saysWomen(item: SportsLiveItem): Boolean = genderOf(item) == Gender.WOMEN

    private fun genderOf(item: SportsLiveItem): Gender? =
        genders(Triple(SportsLiveTeams.knownSport(item), listOf(item.meta.name) + item.meta.genres, listOf(item.catalogName)))

    /**
     * Whether [item]'s league may be inferred from its teams (#132 P4): it names no excluded sport or league
     * (women's, minor, individual sports) and no college competition, whose teams share pro teams' names.
     */
    fun inferable(item: SportsLiveItem): Boolean =
        inferableTexts(Triple(SportsLiveTeams.knownSport(item), listOf(item.meta.name) + item.meta.genres, listOf(item.catalogName)))

    /**
     * The college feed an ambiguous card may also be on (#66), tried only when its pro feed has no match:
     * a card routed by a bare sport word, with no competition named. "Basketball" → men's college
     * basketball; "American football" or a "Football" that never says soccer → college football. Women's
     * and youth cards are excluded before this ([leaguesFor]), so they never reach a men's college feed.
     */
    fun collegeFallbackFor(item: SportsLiveItem): EspnLeague? =
        collegeFallbackFor(
            sport = SportsLiveTeams.knownSport(item),
            hints = listOf(item.meta.name) + item.meta.genres,
            routingHints = listOf(item.catalogName)
        )

    /** See [collegeFallbackFor]; the same texts as [leaguesFor]. */
    fun collegeFallbackFor(sport: String?, hints: List<String>, routingHints: List<String> = emptyList()): EspnLeague? =
        collegeFallback(Triple(sport, hints, routingHints))

    @VisibleForTesting
    internal fun collegeFallbackForUncached(sport: String?, hints: List<String>, routingHints: List<String>): EspnLeague? {
        val own = (listOfNotNull(sport) + hints).map(::fold).filter { it.isNotEmpty() }
        if (own.any { EXCLUDED.containsMatchIn(it) }) return null
        val texts = own + routingHints.map(::fold).filter { it.isNotEmpty() }
        if (firstCompetition(texts) != null || texts.any { SOCCER_WORD.containsMatchIn(it) }) return null
        if (indexedLeague(texts, firstSport(texts)) != null) return null
        // A women's card already goes to both women's basketball feeds ([leaguesFor]).
        if (genders(Triple(sport, hints, routingHints)) == Gender.WOMEN) return null
        return when (firstSport(texts)) {
            listOf(NBA) -> COLLEGE_BASKETBALL
            listOf(NFL), listOf(SOCCER_ALL) -> COLLEGE_FOOTBALL
            else -> null
        }
    }

    private val SOCCER_WORD = Regex("""\bsoccer\b""")

    /**
     * The ESPN sports a stored or addon [sport] word can mean: those of the feeds it routes to ("NBA" and
     * "Basketball" → basketball). A word routed only to `soccer/all` that says "Football" may also be
     * American football. Null when it routes nowhere (unknown or excluded).
     */
    fun espnSportsOf(sport: String): Set<String>? {
        val leagues = leaguesFor(sport = sport, hints = emptyList())
        if (leagues.isEmpty()) return null
        val sports = leagues.mapTo(HashSet()) { it.sport }
        if (leagues.all { it == SOCCER_ALL } && sport.contains("football", ignoreCase = true)) sports += NFL.sport
        return sports
    }

    // Text by text (sport, title, genres, catalog): the first text naming a competition decides.
    private fun firstCompetition(texts: List<String>): List<EspnLeague>? {
        for (text in texts) {
            val rule = COMPETITIONS.firstOrNull { it.regex.containsMatchIn(text) } ?: continue
            return rule.leagues(text)
        }
        return null
    }

    // Rule by rule: the first sport word found in any text decides, with soccer last.
    private fun firstSport(texts: List<String>): List<EspnLeague>? {
        for (rule in SPORTS) {
            val text = texts.firstOrNull { rule.regex.containsMatchIn(it) } ?: continue
            return rule.leagues(text)
        }
        return null
    }

    private val MARKS = Regex("""\p{Mn}+""")
    private val WHITESPACE = Regex("""\s+""")

    private fun fold(text: String): String =
        Normalizer.normalize(text.replace('’', '\''), Normalizer.Form.NFD).replace(MARKS, "").lowercase().replace(WHITESPACE, " ").trim()
}
