package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.domain.model.SportsLiveItem
import java.text.Normalizer

/**
 * Picks the ESPN scoreboard feed(s) for a card from its sport and the competition keywords in its title,
 * genres and catalog name. Data-driven: add a row to [COMPETITIONS] or [SPORTS] to extend.
 *
 * Routing narrows which feeds are fetched and keeps women's, minor-league and college cards away from
 * the pro feeds whose teams share their names or cities; [ScoreMatcher] still requires both teams to match.
 */
object EspnLeagueMap {
    val NBA = EspnLeague("basketball", "nba")
    val NFL = EspnLeague("football", "nfl")
    val NHL = EspnLeague("hockey", "nhl")
    val MLB = EspnLeague("baseball", "mlb")
    val COLLEGE_FOOTBALL = EspnLeague("football", "college-football")
    val COLLEGE_BASKETBALL = EspnLeague("basketball", "mens-college-basketball")
    val SOCCER_ALL = EspnLeague("soccer", "all")

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
        Rule("""\bserie a\b""", soccer("ita.1")),
        Rule("""(?<!2\. )(?<!2 )\bbundesliga\b""", soccer("ger.1")),
        Rule("""\bligue 1\b""", soccer("fra.1")),
        Rule("""\bmls\b|major league soccer""", soccer("usa.1")),
        Rule("""\bfriendl(y|ies)\b""", soccer("fifa.friendly"))
    )

    // The card's sport when no competition is named, checked rule by rule across all texts, so an
    // "American Football" genre beats a "Football" sport. Soccer ("Football") is the last fallback.
    private val SPORTS: List<Rule> = listOf(
        Rule("""american football""", listOf(NFL)),
        Rule("""\bbasketball\b""", listOf(NBA)),
        Rule("""\b(ice )?hockey\b""", listOf(NHL)),
        Rule("""\bbaseball\b""", listOf(MLB)),
        Rule("""\bsoccer\b|\bfootball\b""", listOf(SOCCER_ALL))
    )

    // A card that mentions any of these anywhere gets no feed: sports scores v1 does not cover, and
    // women's / minor leagues whose teams share names or cities with the pro feeds (WNBA, PWHL, AHL...).
    private val EXCLUDED = Regex(
        """\b(cricket|icc|t20|odi|ipl|tennis|ufc|mma|boxing|golf|f1|formula 1|motogp|nascar|rugby|wwe|wrestling|""" +
            """darts|snooker|wnba|pwhl|ahl|echl|g[- ]?league|women'?s|womens)\b"""
    )

    // A college card routes only to a college feed, never to the pro league of the same sport.
    private val COLLEGE = Regex("""\bncaa\w*|\bcollege\b""")
    private val COLLEGE_FEEDS = setOf(COLLEGE_FOOTBALL, COLLEGE_BASKETBALL)

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
     * does a college card without a college feed. [routingHints] (the catalog name) route only.
     */
    fun leaguesFor(sport: String?, hints: List<String>, routingHints: List<String> = emptyList()): List<EspnLeague> {
        val own = (listOfNotNull(sport) + hints).map(::fold).filter { it.isNotEmpty() }
        if (own.any { EXCLUDED.containsMatchIn(it) }) return emptyList()
        val texts = own + routingHints.map(::fold).filter { it.isNotEmpty() }
        val leagues = (firstCompetition(texts) ?: firstSport(texts)).orEmpty()
        val college = texts.any { COLLEGE.containsMatchIn(it) }
        return if (college && leagues.none { it in COLLEGE_FEEDS }) emptyList() else leagues
    }

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
    fun collegeFallbackFor(sport: String?, hints: List<String>, routingHints: List<String> = emptyList()): EspnLeague? {
        val own = (listOfNotNull(sport) + hints).map(::fold).filter { it.isNotEmpty() }
        if (own.any { EXCLUDED.containsMatchIn(it) }) return null
        val texts = own + routingHints.map(::fold).filter { it.isNotEmpty() }
        if (firstCompetition(texts) != null || texts.any { SOCCER_WORD.containsMatchIn(it) }) return null
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
