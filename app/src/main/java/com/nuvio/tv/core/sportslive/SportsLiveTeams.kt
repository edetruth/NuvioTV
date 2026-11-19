package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveItem

/** Display names of the two sides of a game, rankings removed. */
data class TeamPair(val home: String, val away: String)

/** Parses and matches team names in event titles; shared by My Teams and one-card-per-game. */
object SportsLiveTeams {
    // In order of preference. " at " / " @ " put the home side second.
    private val SEPARATORS = listOf(" vs ", " vs. ", " v ", " at ", " @ ", " - ")
    private val AWAY_FIRST = setOf(" at ", " @ ")
    private val LEADING_RANK = Regex("^\\d+\\s+")
    private val WHITESPACE = Regex("\\s+")
    private val CLUB_SUFFIXES = setOf("fc", "sc", "cf", "afc")
    // Leading club tags ("AC Milan", "AS Roma", "VfB Stuttgart", "1. FC Köln"): addons and ESPN differ
    // on them. "SC" and "CA" are left alone ("SC State" is a college).
    private val CLUB_PREFIXES = setOf("fc", "afc", "cf", "ac", "as", "ss", "ssc", "rc", "cd", "sv", "vfb", "vfl", "tsg")
    // "Celtics [HD]", "76ers (2026-11-10 19:00:05)": a trailing bracketed group is not part of the name.
    private val TRAILING_GROUP = Regex("""\s*[\[(][^\[\]()]*[\])]\s*$""")

    // "(W)", "(Women)", "(U21)", "[U21]", "U21", "U-21", "Under 21", "U21s": they tell teams apart, so
    // they stay part of the name. An unbracketed youth form is written "u21" in a normalised name.
    private val QUALIFIER = Regex(
        """\s*(\((w|women|u\d+)\)|\[u\d+]|\b(u|under)[- ]?(\d{1,2})s?)\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val BRACKET_GROUP = Regex("""\[([^\[\]]*)]""")

    /**
     * A game named inside a channel's `[...]` group ("US| PEACOCK PPV 004 [Celtics vs. 76ers (...)]");
     * the rest of a channel name is never parsed, so "Sky Sports - HD" stays null.
     */
    fun parseBracketed(channelName: String): TeamPair? =
        BRACKET_GROUP.findAll(channelName).firstNotNullOfOrNull { parse(it.groupValues[1]) }

    fun parse(name: String): TeamPair? = parseWritten(name)?.let { (first, second, awayFirst) ->
        if (awayFirst) TeamPair(home = second, away = first) else TeamPair(first, second)
    }

    /**
     * The game's two sides in the order [name] writes them, as [parse] reads it ("Castilla at Real Madrid":
     * Castilla, then Real Madrid); for a channel, the bracketed game's. Null when it names no game.
     */
    fun writtenOrder(name: String, bracketed: Boolean = false): Pair<String, String>? {
        val written = if (bracketed) {
            BRACKET_GROUP.findAll(name).firstNotNullOfOrNull { parseWritten(it.groupValues[1]) }
        } else {
            parseWritten(name)
        }
        return written?.let { it.first to it.second }
    }

    /**
     * First side, second side, and whether the separator puts the away side first (" at ", " @ "). A
     * tournament written before the game ("Premier League - Arsenal vs Chelsea") or after it ("Arsenal vs
     * Chelsea - Matchday 5") is not part of a side; " - " alone splits only two team-like sides, so "ATP 250 -
     * Stockholm Open" names no game ([isTeamLike]).
     */
    private fun parseWritten(name: String): Triple<String, String, Boolean>? {
        for (separator in SEPARATORS) {
            val index = name.indexOf(separator, ignoreCase = true)
            if (index < 0) continue
            val dash = separator == DASH
            var firstText = name.substring(0, index)
            var secondText = name.substring(index + separator.length)
            if (!dash) {
                // A tournament before the first side, or a stage after the second, is dropped.
                firstText = firstText.substringAfterLast(DASH)
                secondText = secondText.substringBefore(DASH)
            }
            val first = display(firstText)
            val second = display(secondText)
            if (first.isEmpty() || second.isEmpty()) continue
            if (dash && (!isTeamLike(first) || !isTeamLike(second))) continue
            return Triple(first, second, separator in AWAY_FIRST)
        }
        return null
    }

    private const val DASH = " - "

    /**
     * Whether [side] reads as a team or player rather than a tournament, round or session: it has a letter,
     * no event word ("Open", "Cup", "Grand Prix", "Round", "Qualifying"...) and no long number ("250", "2026").
     */
    fun isTeamLike(side: String): Boolean {
        val text = side.trim()
        if (text.none { it.isLetter() }) return false
        val lower = text.lowercase()
        if (EVENT_WORDS.containsMatchIn(lower)) return false
        return lower.split(WHITESPACE).none { token -> token.length >= 3 && token.all(Char::isDigit) }
    }

    private val EVENT_WORDS = Regex(
        """\b(atp|wta|itf|challenger|open|masters|grand prix|gp|championships?|tournament|tour|trophy|cup|series|""" +
            """round|qualifying|qualifiers?|finals?|semi-?finals?|quarter-?finals?|stage|group|matchday|week|day|""" +
            """race|practice|session|sprint|highlights|replay|league|ufc|wwe|pga|lpga|f1|formula|nascar|motogp|""" +
            """olympics?|world|games)\b"""
    )

    /**
     * A team name for comparison: lowercase; small raised letters made plain ("ᴸᴵⱽᴱ" marks dropped);
     * "&" read as "and" and other punctuation as a space ("St. Louis" = "St Louis"); leading rank and
     * club tags/suffixes removed; a (W)/U21 qualifier kept at the end.
     */
    fun normalise(team: String): String {
        val cleaned = SportsLiveSmallLetters.plain(clean(team)).lowercase()
        // A qualifier is set aside so "Arsenal FC (W)" still loses its club suffix: "arsenal (w)".
        val qualifier = QUALIFIER.find(cleaned)
        val base = if (qualifier == null) cleaned else cleaned.substring(0, qualifier.range.first)
        // A possessive joins its word ("St. John's" is "st johns", as addons write it), before punctuation splits.
        val words = base.replace("&", " and ").replace(POSSESSIVE, "s").replace(PUNCTUATION, " ").trim()
        val tokens = words.replace(LEADING_RANK, "").split(WHITESPACE).filter { it.isNotEmpty() }.toMutableList()
        while (tokens.size > 1 && tokens.first() in CLUB_PREFIXES) tokens.removeAt(0)
        while (tokens.size > 1 && tokens.last() in CLUB_SUFFIXES) tokens.removeAt(tokens.lastIndex)
        return (tokens.joinToString(" ") + " " + qualifier?.let(::qualifierText).orEmpty()).trim()
    }

    private val POSSESSIVE = Regex("""['\u2019]s(?![\p{L}\p{N}])""")
    private val PUNCTUATION = Regex("""[^\p{L}\p{N}\s]+""")

    // "(w)" and "[u21]" as written; an unbracketed youth form as "u21".
    private fun qualifierText(match: MatchResult): String {
        val age = match.groups[4]?.value
        return if (age != null) "u$age" else match.value.trim()
    }

    fun matches(a: String, b: String): Boolean {
        val x = normalise(a)
        val y = normalise(b)
        if (x.isEmpty() || y.isEmpty() || x in CLUB_SUFFIXES || y in CLUB_SUFFIXES) return false
        if (x == y) return true
        // Alias layer: fold "PAK" and "Pakistan" only when BOTH resolve to the same canonical team;
        // two different known teams never match by suffix ("Inter Milan" is not "Milan").
        val canonicalX = canonicalOrNull(x)
        val canonicalY = canonicalOrNull(y)
        if (canonicalX != null && canonicalY != null) return canonicalX == canonicalY
        return x.endsWith(" $y") || y.endsWith(" $x")
    }

    /**
     * The canonical form of a team name for bucketing and matching: its normalised name, with a known
     * abbreviation/alias replaced by the canonical team ([SportsLiveTeamAliases]). An unknown name is
     * returned unchanged. A (W)/U21 qualifier is kept, so "PAK (W)" never resolves to "Pakistan".
     */
    fun canonicalise(team: String): String {
        val normalised = normalise(team)
        return canonicalOrNull(normalised) ?: normalised
    }

    // [normalised] is already lowercase with club suffix removed; a trailing qualifier is set aside so
    // only the base name is looked up, then reattached to the canonical team.
    private fun canonicalOrNull(normalised: String): String? {
        val qualifier = QUALIFIER.find(normalised)
        val base = (if (qualifier == null) normalised else normalised.substring(0, qualifier.range.first)).trim()
        val canonical = SportsLiveTeamAliases.canonical(base) ?: return null
        return if (qualifier == null) canonical else "$canonical ${qualifierText(qualifier)}"
    }

    /**
     * True when [team] plays in [item]: its name matches either side, and the sports agree or either is
     * unknown ([knownSport]). A channel's sport is only its catalog's name ("EasyPlay Live TV"), so it is not compared.
     */
    fun follows(team: FollowedTeam, item: SportsLiveItem): Boolean {
        val pair = item.teams ?: return false
        return followsSide(team, pair.home, item) || followsSide(team, pair.away, item)
    }

    /**
     * True when [team] is the side [side] of [item], under the same sport rule as [follows]: the
     * stored sport and the item's [knownSport] are compared only when both are known (same word,
     * ignoring case). A team followed from ESPN ([FollowedTeam.espnId] and [FollowedTeam.league]) is
     * matched by league instead: the card must route to that same ESPN feed ([EspnLeagueMap]; pro and
     * college feeds stay apart, any soccer feed for a soccer team), so an NFL "Panthers" never stars
     * a college "Pittsburgh Panthers" and an NBA "Kings" never an NBL "Sydney Kings".
     */
    fun followsSide(team: FollowedTeam, side: String, item: SportsLiveItem): Boolean {
        val itemSport = if (item.isChannel) null else knownSport(item)
        val espnLeague = espnLeagueOf(team)
        val sportsAgree = if (espnLeague != null) {
            item.isChannel || cardMatchesLeague(item, itemSport, espnLeague)
        } else {
            val teamSport = team.sport?.takeUnless(::isGenericSport)
            teamSport == null || itemSport == null || teamSport.equals(itemSport, ignoreCase = true)
        }
        return sportsAgree && matches(team.name, side)
    }

    /** The ESPN feed a team followed from ESPN came from ("basketball/nba"); null for a name-only follow. */
    private fun espnLeagueOf(team: FollowedTeam): EspnLeague? {
        if (team.espnId == null) return null
        val parts = team.league?.split('/')?.takeIf { it.size == 2 && it.all(String::isNotBlank) } ?: return null
        return EspnLeague(parts[0], parts[1])
    }

    // The card's feeds must include the team's (soccer: any soccer feed, as soccer/all lists every
    // competition). A card that routes nowhere and has no known sport is not compared, as before.
    private fun cardMatchesLeague(item: SportsLiveItem, itemSport: String?, league: EspnLeague): Boolean {
        val feeds = EspnLeagueMap.leaguesFor(item)
        if (feeds.isEmpty()) return itemSport == null
        return if (league.sport == SOCCER) feeds.any { it.sport == SOCCER } else league in feeds
    }

    private const val SOCCER = "soccer"

    /** [followsSide] for a merged card: true when [team] is [side] in the card or any of its alternates. */
    fun followsSideOfAnySource(team: FollowedTeam, side: String, item: SportsLiveItem): Boolean =
        followsSide(team, side, item) || item.alternates.any { followsSide(team, side, it) }

    /**
     * The item's sport when it is one: taken from its genres, not its catalog's name ("⚡SportzX Live
     * Events (tv)"), and not a generic genre ("Sports", "Live TV"). Null when unknown.
     */
    fun knownSport(item: SportsLiveItem): String? {
        val sport = item.sport?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // With no genres the sport is the catalog's name (a merged card may carry an alternate's sport).
        val fromCatalog = item.meta.genres.none { it.isNotBlank() } && sport.equals(item.catalogName.trim(), ignoreCase = true)
        return sport.takeUnless { fromCatalog || isGenericSport(it) }
    }

    private val GENERIC_SPORTS = setOf("sports", "live tv", "live", "tv", "events")

    private fun isGenericSport(sport: String): Boolean = sport.trim().lowercase() in GENERIC_SPORTS

    fun isFollowed(item: SportsLiveItem, followed: List<FollowedTeam>): Boolean = followed.any { follows(it, item) }

    private fun display(side: String): String = clean(side).replace(LEADING_RANK, "").trim()

    /**
     * Drops what an addon wraps around a team name: anything up to an unclosed `[`
     * ("US| PEACOCK PPV 004 [Celtics"), then trailing `[...]` / `(...)` groups and a stray `]` or `)`.
     */
    private fun clean(side: String): String {
        var text = side.trim()
        val open = text.lastIndexOf('[')
        if (open >= 0 && text.indexOf(']', open) < 0) text = text.substring(open + 1).trim()
        while (true) {
            val next = when {
                QUALIFIER.containsMatchIn(text) -> break
                TRAILING_GROUP.containsMatchIn(text) -> text.replace(TRAILING_GROUP, "")
                text.endsWith("]") && '[' !in text -> text.dropLast(1)
                text.endsWith(")") && '(' !in text -> text.dropLast(1)
                else -> break
            }.trim()
            if (next.isEmpty()) break
            text = next
        }
        return text
    }
}
