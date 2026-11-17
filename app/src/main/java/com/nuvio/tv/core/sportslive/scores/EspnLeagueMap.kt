package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.domain.model.SportsLiveItem
import java.text.Normalizer

/**
 * Picks the ESPN scoreboard feed(s) for a card from its sport and the competition keywords in its title,
 * genres and catalog name. Data-driven: add a row to [COMPETITIONS] or [SPORTS] to extend.
 *
 * Routing only narrows which feeds are fetched; [ScoreMatcher] still requires both teams to match, so a
 * keyword that over-reaches ("Egyptian Premier League" is not routed, but "Premier League" is) costs a
 * request, never a wrong score.
 */
object EspnLeagueMap {
    val NBA = EspnLeague("basketball", "nba")
    val NFL = EspnLeague("football", "nfl")
    val NHL = EspnLeague("hockey", "nhl")
    val MLB = EspnLeague("baseball", "mlb")
    val COLLEGE_FOOTBALL = EspnLeague("football", "college-football")
    val COLLEGE_BASKETBALL = EspnLeague("basketball", "mens-college-basketball")
    val SOCCER_ALL = EspnLeague("soccer", "all")

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
        Rule("""college football|\bncaaf\b|\bncaa football\b|\bcfb\b""", listOf(COLLEGE_FOOTBALL)),
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

    // The card's sport when no competition is named. "Football" is soccer; American football says so.
    private val SPORTS: List<Rule> = listOf(
        Rule("""american football""", listOf(NFL)),
        Rule("""\bbasketball\b""", listOf(NBA)),
        Rule("""\b(ice )?hockey\b""", listOf(NHL)),
        Rule("""\bbaseball\b""", listOf(MLB)),
        Rule("""\bsoccer\b|\bfootball\b""", listOf(SOCCER_ALL))
    )

    // Sports scores v1 does not cover: such a card gets no feed even when its title has a keyword.
    private val UNSUPPORTED = Regex(
        """\b(cricket|tennis|ufc|mma|boxing|golf|f1|formula 1|motogp|nascar|rugby|wwe|wrestling|darts|snooker)\b"""
    )

    /** Feeds for [item]: its sport, title, genres and catalog name. Empty when none applies. */
    fun leaguesFor(item: SportsLiveItem): List<EspnLeague> =
        leaguesFor(
            sport = SportsLiveTeams.knownSport(item),
            hints = listOf(item.meta.name) + item.meta.genres + item.catalogName
        )

    /**
     * Feeds for a card with [sport] (null when unknown) and text [hints] in priority order (title first).
     * A competition named in the sport or a hint wins; otherwise the sport word decides ("Basketball",
     * "Football" = soccer); an unsupported sport gets none.
     */
    fun leaguesFor(sport: String?, hints: List<String>): List<EspnLeague> {
        val sportText = sport?.let(::fold)
        if (sportText != null && UNSUPPORTED.containsMatchIn(sportText)) return emptyList()
        val texts = listOfNotNull(sportText) + hints.map(::fold).filter { it.isNotEmpty() }
        return (firstRule(COMPETITIONS, texts) ?: firstRule(SPORTS, texts)).orEmpty()
    }

    private fun firstRule(rules: List<Rule>, texts: List<String>): List<EspnLeague>? {
        for (text in texts) {
            val rule = rules.firstOrNull { it.regex.containsMatchIn(text) } ?: continue
            return rule.leagues(text)
        }
        return null
    }

    private val MARKS = Regex("""\p{Mn}+""")
    private val WHITESPACE = Regex("""\s+""")

    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "").lowercase().replace(WHITESPACE, " ").trim()
}
