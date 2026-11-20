package com.nuvio.tv.core.sportslive

import androidx.annotation.VisibleForTesting
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveItem
import java.text.Normalizer

/** Display names of the two sides of a game, rankings removed. */
data class TeamPair(val home: String, val away: String)

/** Parses and matches team names in event titles; shared by My Teams and one-card-per-game. */
object SportsLiveTeams {
    // Every load folds the same few thousand names many times over (#152). First, so it is set before any other initialiser runs.
    private val normalised = Memo(8_192) { team: String -> normaliseUncached(team) }

    // In order of preference. " at " / " @ " put the home side second. " x " (Portuguese and Spanish, #165)
    // counts only between proper words ([isWordSeparatorAt]).
    private val SEPARATORS = listOf(" vs ", " vs. ", " v ", " at ", " @ ", X_SEPARATOR, " - ")
    private val AWAY_FIRST = setOf(" at ", " @ ")
    private val LEADING_RANK = Regex("^\\d+\\s+")
    private val WHITESPACE = Regex("\\s+")
    private val CLUB_SUFFIXES = setOf("fc", "sc", "cf", "afc")
    // Leading club tags ("AC Milan", "AS Roma", "VfB Stuttgart", "1. FC Köln"): addons and ESPN differ
    // on them. "SC" and "CA" are left alone ("SC State" is a college).
    // Basketball clubs' "KK", "BC", "BK" (live.18: "KK Crvena zvezda" is "Crvena Zvezda Beograd"'s club).
    private val CLUB_PREFIXES = setOf("fc", "afc", "cf", "ac", "as", "ss", "ssc", "rc", "cd", "sv", "vfb", "vfl", "tsg", "kk", "bc", "bk")
    // "Celtics [HD]", "76ers (2026-11-10 19:00:05)": a trailing bracketed group is not part of the name.
    private val TRAILING_GROUP = Regex("""\s*[\[(][^\[\]()]*[\])]\s*$""")

    // "(W)", "(Women)", "(U21)", "[U21]", "U21", "U-21", "Under 21", "U21s", "Sub-17", "Sub 17" (#165): they tell
    // teams apart, so they stay part of the name. An unbracketed youth form is written "u21" in a normalised name.
    private val QUALIFIER = Regex(
        """\s*(\((w|women|u\d+)\)|\[u\d+]|\b(u|under|sub)[- ]?(\d{1,2})s?)\s*$""",
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
            if (separator == X_SEPARATOR && !isWordSeparatorAt(name, index)) continue
            val dash = separator == DASH
            var firstText = name.substring(0, index)
            var secondText = name.substring(index + separator.length)
            if (!dash) {
                // A tournament before the first side, or a stage after the second, is dropped.
                firstText = firstText.substringAfterLast(DASH)
                secondText = secondText.substringBefore(DASH)
            }
            // "LIVE: Qatar U17" (#165): the addon's live marker is no part of the first side.
            firstText = withoutLivePrefix(firstText)
            val first = display(firstText)
            val second = display(secondText)
            if (first.isEmpty() || second.isEmpty()) continue
            if (dash && (!isTeamLike(first) || !isTeamLike(second))) continue
            // "ATP 250 vs Stockholm Open" (F4): a tournament written as a game, no side a team, names no game.
            if (!isTeamLike(first) && !isTeamLike(second)) continue
            return Triple(first, second, separator in AWAY_FIRST)
        }
        return null
    }

    /**
     * The two parts of a title written as a game whose sides are both no team ("ATP 250 vs Stockholm Open", F4:
     * a tournament and its event), for display; null when [title] has no "vs" / "v" or either part reads as a team.
     */
    fun nonGamePair(title: String): Pair<String, String>? {
        val parts = VERSUS.split(title, limit = 2).map { it.trim() }
        if (parts.size != 2 || parts.any { it.isEmpty() }) return null
        return (parts[0] to parts[1]).takeIf { (first, second) -> !isTeamLike(first) && !isTeamLike(second) }
    }

    private val VERSUS = Regex("""\s+v(?:s\.?|\.)?\s+""", RegexOption.IGNORE_CASE)

    private const val DASH = " - "
    private const val X_SEPARATOR = " x "

    /**
     * Whether the " x " at [index] of [name] separates two sides (#165): the words either side of it are proper
     * words (two or more characters, or one capital letter), so a stray lone "x" never splits a title. A name
     * like "Xavier" is never touched: it has no spaces round its x.
     */
    private fun isWordSeparatorAt(name: String, index: Int): Boolean {
        val before = name.substring(0, index).trimEnd().substringAfterLast(' ')
        val after = name.substring(index + X_SEPARATOR.length).trimStart().substringBefore(' ')
        return isProperWord(before) && isProperWord(after)
    }

    private fun isProperWord(word: String): Boolean =
        word.any(Char::isLetterOrDigit) && (word.length >= 2 || word[0].isUpperCase())

    /** [side] without a leading "LIVE:" / "LIVE |" marker (#165); plain string checks, no regex (#152). */
    private fun withoutLivePrefix(side: String): String {
        val text = side.trimStart()
        val start = text.indexOfFirst(Char::isLetter)
        // At most an emoji or a bullet before it ("🔴 LIVE:").
        if (start < 0 || start > 4 || !text.regionMatches(start, "live", 0, 4, ignoreCase = true)) return side
        val rest = text.substring(start + 4).trimStart()
        if (rest.isEmpty() || (rest[0] != ':' && rest[0] != '|')) return side
        return rest.substring(1).trim().ifEmpty { side }
    }

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
    fun normalise(team: String): String = normalised(team)

    @VisibleForTesting
    internal fun normaliseUncached(team: String): String {
        val cleaned = SportsLiveSmallLetters.plain(clean(team)).lowercase()
        // A qualifier is set aside so "Arsenal FC (W)" still loses its club suffix: "arsenal (w)".
        val qualifier = QUALIFIER.find(cleaned)
        val base = if (qualifier == null) cleaned else cleaned.substring(0, qualifier.range.first)
        // A possessive joins its word ("St. John's" is "st johns", as addons write it), before punctuation splits.
        val words = base.replace("&", " and ").replace(POSSESSIVE, "s").replace(PUNCTUATION, " ").trim()
        val tokens = words.replace(LEADING_RANK, "").split(WHITESPACE).filter { it.isNotEmpty() }.map { EXONYMS[it] ?: it }.toMutableList()
        while (tokens.size > 1 && tokens.first() in CLUB_PREFIXES) tokens.removeAt(0)
        while (tokens.size > 1 && tokens.last() in CLUB_SUFFIXES) tokens.removeAt(tokens.lastIndex)
        return (tokens.joinToString(" ") + " " + qualifier?.let(::qualifierText).orEmpty()).trim()
    }

    // A club's city in its own language and in English (F4: "FC Bayern München" is "Bayern Munich", "1. FC Köln" is
    // "FC Cologne"): the English word, as ESPN writes them. Only cities whose clubs addons name both ways.
    private val EXONYMS = mapOf(
        "münchen" to "munich", "munchen" to "munich", "muenchen" to "munich",
        "köln" to "cologne", "koln" to "cologne", "koeln" to "cologne",
        "praha" to "prague",
        "københavn" to "copenhagen", "kobenhavn" to "copenhagen", "koebenhavn" to "copenhagen"
    )

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

    /** How closely two addons' names for one side agree when copies of a game are merged ([mergeMatch]). */
    enum class MergeMatch { NONE, PREFIX, FULL }

    /**
     * Whether [a] and [b] name one side for merging copies of a game (#153): [FULL][MergeMatch.FULL] when they
     * [match][matches] or are equal once a women's marker is folded ("Brisbane Heat W" = "Brisbane Heat Women");
     * [PREFIX][MergeMatch.PREFIX] when one's words begin the other's and the shorter has a significant word
     * ("Adelaide" / "Adelaide 36ers", "Brisbane W" / "Brisbane Heat Women"), or every word of the shorter is in
     * the other and two of them are significant ("Virtus Bologna" / "Virtus Pallacanestro Bologna", #174). Never
     * across a (W)/U21 qualifier, nor between two different known teams. "Manchester United" and "Manchester
     * City" are [NONE][MergeMatch.NONE]. Not for My Teams: a followed "Adelaide 36ers" must not star every "Adelaide".
     */
    fun mergeMatch(a: String, b: String): MergeMatch {
        val x = mergeForms(a)
        val y = mergeForms(b)
        if (x.words.isEmpty() || y.words.isEmpty() || x.qualifier != y.qualifier) return MergeMatch.NONE
        if (x.words == y.words || matches(a, b)) return MergeMatch.FULL
        if (x.canonical != null && y.canonical != null) return MergeMatch.NONE
        val (shorter, longer) = if (x.words.size <= y.words.size) x to y else y to x
        val prefix = shorter.words == longer.words.subList(0, shorter.words.size) &&
            shorter.words.any(::isSignificantWord)
        // One word set inside the other (#174). Two significant words, so filler alone ("Racing Club") never stands
        // for a club; two clubs of one city differ by a word ("City" / "United"), so neither is inside the other.
        val subset = shorter.words.count(::isSignificantWord) >= 2 && longer.words.containsAll(shorter.words)
        // "Sporting Delhi" / "Sporting Club Delhi" (F4): the longer adds only a club word, and the shorter has a word of its own.
        val clubWord = shorter.words.any(::isSignificantWord) && longer.words.containsAll(shorter.words) &&
            (longer.words - shorter.words.toSet()).let { extra -> extra.isNotEmpty() && extra.all { it in CLUB_WORDS } }
        return if (prefix || subset || clubWord) MergeMatch.PREFIX else MergeMatch.NONE
    }

    // Words a club's full name adds to its short one ("Sporting Club Delhi"): never a word that tells two clubs apart.
    private val CLUB_WORDS = setOf("club", "the", "de", "of", "and", "football")

    /**
     * [teams] read as a women's game (#174): a side with no (W)/U21 qualifier gets the women's marker ("Arsenal"
     * as "Arsenal W"). For a copy of a game on a women's competition, compared with one that names no gender.
     */
    fun asWomen(teams: TeamPair): TeamPair = TeamPair(asWomenSide(teams.home), asWomenSide(teams.away))

    private fun asWomenSide(side: String): String = if (mergeForms(side).qualifier == null) "$side W" else side

    /** A side's words for [mergeMatch], its (W)/U21 qualifier ("w", "u21") apart and its known canonical team. */
    private class MergeForm(val words: List<String>, val qualifier: String?, val canonical: String?)

    private val mergeForms = Memo(8_192) { team: String -> mergeFormUncached(team) }

    private val WOMEN_WORDS = setOf("w", "women", "womens", "(w)", "(women)")

    private fun mergeFormUncached(team: String): MergeForm {
        // "Panamá" and "Panama" are one side when copies are merged (#165).
        val normalised = normalise(foldAccents(team))
        val words = normalised.split(' ').filter { it.isNotEmpty() }.toMutableList()
        val last = words.lastOrNull()?.trim('(', ')', '[', ']')
        val qualifier = when {
            words.size > 1 && words.last() in WOMEN_WORDS -> "w"
            words.size > 1 && last != null && last.length > 1 && last[0] == 'u' && last.drop(1).all(Char::isDigit) -> last
            else -> null
        }
        if (qualifier != null) words.removeAt(words.lastIndex)
        // A club suffix before the marker ("Arsenal FC W", #174) goes as it does before "(W)".
        while (words.size > 1 && words.last() in CLUB_SUFFIXES) words.removeAt(words.lastIndex)
        // A last "St" is State, as ESPN shortens schools ("Oklahoma St" is "Oklahoma State Cowboys", user TV report);
        // a first or inner one is Saint ("St Johns", "Mount St Marys") and stays.
        if (words.size > 1 && words.last() == "st") words[words.lastIndex] = "state"
        return MergeForm(words, qualifier, canonicalOrNull(normalised))
    }

    // A word that names a team on its own: three or more characters with a letter, not a place or club filler.
    private val FILLER_WORDS = setOf(
        "the", "and", "club", "team", "city", "united", "real", "sporting", "athletic", "new", "san", "santa",
        "los", "las", "saint", "north", "south", "east", "west", "inter", "atletico", "deportivo", "racing"
    )

    private fun isSignificantWord(word: String): Boolean =
        word.length >= 3 && word.any(Char::isLetter) && word !in FILLER_WORDS

    /**
     * [text] without accents ("Panamá" → "Panama"), for merge keys (#165). No regex: each Regex call costs
     * native memory on Android (#152). Plain ASCII is returned as it is.
     */
    fun foldAccents(text: String): String {
        if (text.all { it.code < 128 }) return text
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        return buildString(decomposed.length) {
            for (c in decomposed) if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) append(c)
        }
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

    /**
     * Tags an addon puts on items that are no sport at all (#145): a catch-all ("Other", "Misc", "General"),
     * TV ("Entertainment") or a channel list ("Networks"). Never a sport for matching, filters or the row tag.
     */
    private val NON_SPORT_TAGS = setOf("other", "others", "misc", "general", "entertainment", "network", "networks")

    private val GENERIC_SPORTS = setOf(
        "sports", "live tv", "live", "tv", "events", "live now", "live events", "live sport", "live sports", "now", "other sports"
    ) + NON_SPORT_TAGS

    /** True for a genre that names no sport ("Sports", "Live", "LIVE NOW", "Live Events", #134), or a non-sport tag. */
    fun isGenericSport(sport: String): Boolean = sport.trim().lowercase() in GENERIC_SPORTS

    /**
     * What a detail line names [item]'s sport by: its sport, else its catalog's name, never a non-sport tag
     * ("OTHER", #145); null when neither says anything.
     */
    fun sportLabelOf(item: SportsLiveItem): String? =
        listOfNotNull(item.sport, item.catalogName).firstOrNull { it.isNotBlank() && !isNonSportTag(it) }

    /** True for an addon tag that names no sport ("OTHER", "Entertainment", "NETWORKS"); see [NON_SPORT_TAGS]. */
    fun isNonSportTag(tag: String): Boolean = tag.trim().lowercase() in NON_SPORT_TAGS

    // "TBD", "TBA", "To be announced": a side not named yet; also at the end of a side ("NCAA TBD").
    private val PLACEHOLDERS = setOf("tbd", "tba", "tbc", "to be determined", "to be announced", "to be confirmed")

    /** True when [side] is a placeholder for a team not known yet ("TBD", "College TBD", "To be announced"). */
    fun isPlaceholder(side: String): Boolean {
        val name = normalise(side)
        return PLACEHOLDERS.any { name == it || name.endsWith(" $it") }
    }

    /**
     * True when neither side of [teams] is named yet ("TBD vs TBD", #145): such a listing is no game to
     * show on Live now or Upcoming, and never a merge key (every TBD-vs-TBD pair would look alike).
     * One named side ("TBD vs Lakers") is a game.
     */
    fun isPlaceholderGame(teams: TeamPair?): Boolean =
        teams != null && isPlaceholder(teams.home) && isPlaceholder(teams.away)

    fun isFollowed(item: SportsLiveItem, followed: List<FollowedTeam>): Boolean = followed.any { follows(it, item) }

    private fun display(side: String): String = clean(side).replace(LEADING_RANK, "").trim()

    /**
     * Drops what an addon wraps around a team name: anything up to an unclosed `[`
     * ("US| PEACOCK PPV 004 [Celtics"), then trailing `[...]` / `(...)` groups and a stray `]` or `)`.
     */
    private fun clean(side: String): String {
        // "⏱ FC Bayern München" (F4): an emoji before or in a side is no part of its name.
        var text = SportsLiveDisplayText.withoutSymbols(side).replace(WHITESPACE, " ").trim()
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
