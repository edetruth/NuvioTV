package com.nuvio.tv.core.sportslive

import java.util.Locale

/**
 * A raw addon title made fit for the screen (#100): the clean [name], its stream [quality] ("FHD") and
 * [region] ("UK") as separate badges, and the [raw] text, which only the Details view shows.
 */
data class SportsLiveDisplayTitle(
    val name: String,
    val quality: String? = null,
    val region: String? = null,
    val raw: String
)

/**
 * Turns raw addon titles and descriptions into display text (#100), the same everywhere the sports UI
 * shows a title (rows, the stage, the in-player panel, the zap banner, the dual picker, Home):
 * - quality prefixes and suffixes ("(FHD) :", "[HD]", "HD", "FHD", "UHD", "4K", "SD") go to [SportsLiveDisplayTitle.quality];
 * - a leading region code ("US :", "UK |") goes to [SportsLiveDisplayTitle.region];
 * - taglines and ids ("Live IPTV Recording", "Live on ‹ID›", "LIVE NOW") are dropped;
 * - emoji and symbols go, a pipe inside a name reads " · ", and a tournament written as a game ("ATP 250 vs
 *   Stockholm Open") reads as its two parts ("ATP 250 · Stockholm Open", F4);
 * - repeated whitespace collapses, and an ALL-CAPS name becomes Title Case, keeping known acronyms.
 *
 * Generic: it knows no addon. Matching and scores keep reading the raw title.
 */
object SportsLiveDisplayText {

    /** [raw]'s display title (cached: titles repeat on every recomposition). */
    fun of(raw: String): SportsLiveDisplayTitle = synchronized(cache) {
        cache.getOrPut(raw) { parse(raw) }
    }

    /** [text] with its emoji and symbols ("⏱", "📺", "🏆") made spaces; the rest as written (team names, F4). */
    fun withoutSymbols(text: String): String = if (text.all { it.code < 128 }) text else SYMBOLS.replace(text, " ")

    /** [raw]'s clean name alone. */
    fun name(raw: String): String = of(raw).name

    /**
     * [raw]'s clean name, or the first line of [description] that says more after it (F4: a tennis feed titled
     * "ATP 250" whose description reads "ATP 250 v Stockholm Open" is "ATP 250 · Stockholm Open"), cleaned the same way.
     */
    fun fullName(raw: String, description: String?): String =
        if (description.isNullOrBlank()) name(raw) else fullNames(raw to description)

    private val fullNames = Memo(1_024) { (raw, description): Pair<String, String> ->
        val short = name(raw)
        description.lines().asSequence()
            .map { it.trim() }
            .filter { it.length > short.length }
            .map(::name)
            .firstOrNull { line -> line.length > short.length && line.startsWith(short, ignoreCase = true) && !line[short.length].isLetterOrDigit() }
            ?: short
    }

    /**
     * A description without taglines, ids, emoji or symbols: lines that are only a tagline go, taglines inside a line
     * are cut out, blank lines collapse. Null when nothing is left.
     */
    fun description(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw.lines()
            .map { stripTaglines(SYMBOLS.replace(it, " ")) }
            .map { collapse(it) }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .ifEmpty { null }
    }

    /**
     * A sport, league or catalog label made fit for a tag or a status line (#132): bracketed parts
     * ("(tv)"), emoji and symbols ("⚡") go; letters, digits and word separators stay. Null when nothing is left.
     */
    fun label(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return collapse(LABEL_DECORATION.replace(LABEL_BRACKETED.replace(raw, " "), " ")).ifEmpty { null }
    }

    private val LABEL_BRACKETED = Regex("""[(\[{][^)\]}]*[)\]}]?""")
    private val LABEL_DECORATION = Regex("""[^\p{L}\p{N}\s/\-&'.]""")

    private fun parse(raw: String): SportsLiveDisplayTitle {
        // Emoji and symbols ("📺 Moto3", "🏆") are decoration (F4, F24): they go first, so a prefix behind one still reads.
        var text = collapse(SYMBOLS.replace(raw, " "))
        var quality: String? = null
        var region: String? = null
        // Prefixes in any order: "(FHD) : US : Name", "US | (HD) Name".
        while (true) {
            val q = QUALITY_PREFIX.find(text)
            if (q != null) {
                quality = quality ?: normalizeQuality(q.groupValues[1].ifEmpty { q.groupValues[2] })
                text = text.substring(q.range.last + 1)
                continue
            }
            // "24/7-EN - Name", "24/7 | Name": a round-the-clock channel marker, its language code kept as the region.
            val m = ALWAYS_ON_PREFIX.find(text)
            if (m != null) {
                region = region ?: m.groupValues[1].takeIf { it.isNotEmpty() }?.uppercase(Locale.ROOT)
                text = text.substring(m.range.last + 1)
                continue
            }
            // "LIVE: Bowls", "LIVE - Name", "● LIVE | Name": the addon's live marker, which the row's red dot already says (#134).
            val live = LIVE_PREFIX.find(text)
            if (live != null) {
                text = text.substring(live.range.last + 1)
                continue
            }
            val r = REGION_PREFIX.find(text)
            if (r != null && isRegion(r.groupValues[1])) {
                region = region ?: r.groupValues[1].uppercase(Locale.ROOT)
                text = text.substring(r.range.last + 1)
                continue
            }
            break
        }
        text = stripTaglines(text)
        // Quality suffixes: "Name HD", "Name (FHD)", "Name 4K".
        while (true) {
            val q = QUALITY_SUFFIX.find(text) ?: break
            val stripped = text.substring(0, q.range.first)
            if (trimSeparators(stripped).isEmpty()) break
            quality = quality ?: normalizeQuality(q.groupValues[1])
            text = stripped
        }
        text = trimSeparators(collapse(text))
        // "Moto2 | Round 22 | Portugal": a pipe inside a name reads as the section's dot (F4).
        text = INNER_PIPE.replace(text, " · ")
        text = MISSPELT.fold(text) { out, (wrong, right) -> wrong.replace(out, right) }
        if (isAllCaps(text)) text = titleCase(text)
        // "ATP 250 vs Stockholm Open": a tournament written as a game is no pairing (F4); it reads "ATP 250 · Stockholm Open".
        SportsLiveTeams.nonGamePair(text)?.let { (first, second) -> text = "$first · $second" }
        val name = text.ifEmpty { collapse(raw) }
        return SportsLiveDisplayTitle(name = name, quality = quality, region = region, raw = raw)
    }

    private fun stripTaglines(text: String): String {
        var out = text
        TAGLINES.forEach { out = it.replace(out, " ") }
        return trimSeparators(collapse(out))
    }

    private fun collapse(text: String): String = WHITESPACE.replace(text, " ").trim()

    private fun trimSeparators(text: String): String = text.trim().trim(*SEPARATORS).trim()

    private fun normalizeQuality(token: String): String = token.uppercase(Locale.ROOT)

    private fun isRegion(code: String): Boolean = code.uppercase(Locale.ROOT) in REGIONS

    /** Letters present and none of them lower case ("SPORTS CHANNEL 1"), so case says nothing. */
    private fun isAllCaps(text: String): Boolean = text.any { it.isLetter() } && text.none { it.isLowerCase() }

    private fun titleCase(text: String): String =
        text.split(' ').mapIndexed { index, word -> titleWord(word, first = index == 0) }.joinToString(" ")

    private fun titleWord(word: String, first: Boolean): String {
        val letters = word.filter { it.isLetter() }
        if (letters.isEmpty()) return word
        val upper = letters.uppercase(Locale.ROOT)
        return when {
            upper in ACRONYMS -> word
            // An all-caps word of 2 to 4 letters reads as an acronym ("AEW", "ROH", "PFL") unless it is a
            // common word ("NEWS", "LIVE"), so a show or promotion keeps its own name's caps.
            isAcronymLike(word, letters) -> word
            // "F1", "4K", "ESPN2": a token with digits is a code; it keeps its case.
            word.any { it.isDigit() } -> word
            !first && upper in SMALL_WORDS -> word.lowercase(Locale.ROOT)
            else -> word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
    }

    private fun isAcronymLike(word: String, letters: String): Boolean =
        letters.length in 2..4 && word.none { it.isLowerCase() } &&
            letters.uppercase(Locale.ROOT).let { it !in COMMON_WORDS && it !in SMALL_WORDS }

    /**
     * A title without its result (#90, Replays): scorelines ("110-102", "2–1", "(3-2)") go, so a replay
     * never tells the score; dates ("11-16-2026"), seasons ("2025-26") and times ("20:00") stay. Then
     * [name]'s cleaning. Generic: it knows no addon.
     */
    /**
     * A side of a game written with its competition after it ("Real Madrid Euroleague", "Lakers (NBA)"): the
     * team's name without it, and the competition as written ("Euroleague"), for the row's league tag. Null when
     * [side] ends in no known competition, or is nothing but one. Generic: a list of competitions, no addon.
     */
    fun competitionSuffix(side: String): Pair<String, String>? {
        val match = COMPETITION_SUFFIX.find(side) ?: return null
        val team = trimSeparators(collapse(side.substring(0, match.range.first))).takeIf { it.isNotEmpty() } ?: return null
        return team to collapse(match.groupValues[1])
    }

    fun spoilerFree(raw: String): String {
        val removed = collapse(SCORELINE.replace(raw, " "))
        // "A 2-1 B": the score stood for the separator, so the sides keep one.
        val cut = if (GAME_SEPARATOR.containsMatchIn(removed)) {
            removed
        } else {
            collapse(SCORELINE.replace(raw) { match ->
                val before = raw.substring(0, match.range.first).trimEnd()
                val after = raw.substring(match.range.last + 1).trimStart()
                if (before.lastOrNull()?.isLetter() == true && after.firstOrNull()?.isLetter() == true) " vs " else " "
            })
        }
        return name(trimSeparators(cut).ifEmpty { raw })
    }

    private val GAME_SEPARATOR = Regex("""\s(?:vs\.?|v\.?|@|at|[-–—])\s""", RegexOption.IGNORE_CASE)

    /** A scoreline: 1-3 digits, a dash, 1-3 digits; never part of a longer number run (a date or a season). */
    private val SCORELINE = Regex("""[(\[]?(?<![\d/.\-–])\d{1,3}\s*[-–]\s*\d{1,3}(?![\d/.]|\s*[-–]\s*\d)[)\]]?""")

    private const val QUALITY = """(?:FHD|UHD|HD|SD|4K|8K)"""
    // A competition written after a team's name: basketball and soccer leagues addons append to sides.
    private val COMPETITION_SUFFIX = Regex(
        """(?:\s+[-|·]?\s*|\s*[(\[])((?:euro ?league|euro ?cup|liga (?:acb|endesa)|lega basket|basketball champions league|""" +
            """nba|wnba|ncaa[mbfw]?|nhl|khl|nfl|mlb|mls|premier league|la ?liga|serie a|bundesliga|ligue 1|eredivisie|""" +
            """champions league|europa league|conference league|super lig|primeira liga|""" +
            // Tennis events glued to a player's name (live.20: "Arthur Fils ATP Finals").
            """(?:atp|wta)(?: tour)? finals|next gen atp finals|(?:atp|wta)(?: \d{3,4})?|davis cup|billie jean king cup|united cup|laver cup))[)\]]?\s*$""",
        RegexOption.IGNORE_CASE
    )

    private val SEPARATORS = charArrayOf(':', '|', '-', '–', '—', '·', '•', '/', ',', ' ')

    /** "(FHD) :", "[HD]", "HD :", "4K |" at the start: bracketed, or followed by a separator ("HDTV" is a name). */
    private val QUALITY_PREFIX =
        Regex("""^(?:[(\[]\s*($QUALITY)\s*[)\]]\s*[:|\-–]?|($QUALITY)\s*[:|\-–])\s*(?=\S)""", RegexOption.IGNORE_CASE)

    /** "Name HD", "Name (FHD)", "Name [4K]", "Name - HD" at the end. */
    private val QUALITY_SUFFIX = Regex("""[\s:|\-–]+[(\[]?($QUALITY)[)\]]?\s*$""", RegexOption.IGNORE_CASE)

    /** "24/7 -", "24/7-EN -", "24-7 EN |" at the start, before the name. */
    private val ALWAYS_ON_PREFIX =
        Regex("""^24\s*[/\-]\s*7(?:\s*[-:|]?\s*([A-Za-z]{2,3}))?\s*[-:|–—]\s*(?=\S)""", RegexOption.IGNORE_CASE)

    /** "LIVE:", "Live -", "● LIVE |" at the start, before the name. */
    private val LIVE_PREFIX = Regex("""^[●•🔴]?\s*live\s*[:|\-–—]\s*(?=\S)""", RegexOption.IGNORE_CASE)

    /** "US :", "UK|", "CA -" at the start: a two- or three-letter code and a separator. */
    private val REGION_PREFIX = Regex("""^([A-Za-z]{2,3})\s*[:|]\s*(?=\S)""")

    private val WHITESPACE = Regex("""[\s   ]+""")

    /** Emoji and symbols (Unicode "other symbols", skin tones, joiners, variation selectors); never the degree sign. */
    private val SYMBOLS = Regex("""[[\p{So}\x{1F3FB}-\x{1F3FF}\x{FE0E}\x{FE0F}\x{200D}\x{20E3}]&&[^\x{00B0}]]""")

    /** A pipe between two parts of a name, with any spaces round it. */
    private val INNER_PIPE = Regex("""\s*\|+\s*""")

    /** Sport names addons misspell ("MotorGP"). */
    private val MISSPELT = listOf(Regex("""\bMotorGP\b""", RegexOption.IGNORE_CASE) to "MotoGP")

    private val TAGLINES = listOf(
        Regex("""\blive\s+iptv\s+recording\b""", RegexOption.IGNORE_CASE),
        // "Live on ‹ID›": the id is one token with a digit or a dash ("Live on Sky Sports" is a name).
        Regex("""\blive\s+on\s+[A-Za-z0-9]*[0-9\-][\w\-.…]*""", RegexOption.IGNORE_CASE),
        Regex("""\blive\s+now\b""", RegexOption.IGNORE_CASE)
    )

    /** Country codes (ISO 3166 alpha-2 and alpha-3) and the usual non-ISO ones. */
    private val REGIONS: Set<String> = buildSet {
        addAll(Locale.getISOCountries())
        Locale.getISOCountries().forEach { code -> runCatching { add(Locale("", code).isO3Country.uppercase(Locale.ROOT)) } }
        addAll(listOf("UK", "EU", "LAT", "INT"))
    }

    /** Kept upper case in a Title-Cased name. */
    private val ACRONYMS = setOf(
        "ESPN", "TNT", "NBA", "NFL", "NHL", "MLB", "MLS", "WNBA", "NCAA", "UFC", "WWE", "PGA", "ATP", "WTA",
        "HQ", "UK", "US", "USA", "EU", "TV", "BBC", "ITV", "CBS", "NBC", "ABC", "BT", "TSN", "RTL", "ZDF", "ARD",
        "EPL", "UEFA", "FIFA", "MMA", "PPV", "NOW", "TBS", "HBO", "AMC", "FX", "DAZN", "II", "III", "IV",
        "HD", "FHD", "UHD", "SD"
    )

    /** Short common words that stay Title Case (not acronyms) in a Title-Cased name. */
    private val COMMON_WORDS = setOf(
        "NEWS", "LIVE", "GAME", "CLUB", "CITY", "TEAM", "PLUS", "ONE", "TWO", "SIX", "TEN", "MAIN", "SHOW", "BEST",
        "GOLF", "RACE", "CUP", "OPEN", "TOUR", "HOME", "AWAY", "FREE", "KIDS", "MAX", "MORE", "GOLD", "BLUE", "RED",
        "BIG", "TOP", "NEW", "OLD", "ALL", "DAY", "WEEK", "YEAR", "FULL", "TIME", "PLAY", "STAR", "STARS", "FIRE",
        "BALL", "BOX", "RING", "WAR", "KING", "LIFE", "WILD", "FAST", "WEST", "EAST", "NORTH", "SOUTH", "REAL",
        "FOR", "BY", "TO", "UP", "OFF", "OUT", "NO", "YES", "MY", "OUR", "ARE", "IS", "IT", "WE", "BE", "OR", "AN",
        "HOUR", "ZONE", "ROAD", "PARK", "LINE", "BACK", "FILM", "MOVIE", "ARTS", "FOOD", "WIN", "WINS", "NET"
    )

    /** Kept lower case inside a Title-Cased name. */
    private val SMALL_WORDS = setOf("VS", "V", "AT", "OF", "AND", "THE", "IN", "ON", "DE", "LA", "DEL")

    private val cache = object : LinkedHashMap<String, SportsLiveDisplayTitle>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SportsLiveDisplayTitle>?): Boolean = size > CACHE_SIZE
    }

    // A load holds more titles than 1,024 (channels): a smaller LRU missed on every pass over them (live.20 B1).
    private const val CACHE_SIZE = 4096
}

/**
 * [SportsLiveItem]'s clean title for the screen (#100); the raw `meta.name` stays for matching and Details. A live or
 * upcoming event naming no two sides takes the fuller line its description gives ([SportsLiveDisplayText.fullName]).
 */
val com.nuvio.tv.domain.model.SportsLiveItem.displayName: String
    get() = if (isChannel || teams != null || kind != com.nuvio.tv.domain.model.SportsLiveKind.EVENTS) {
        SportsLiveDisplayText.name(meta.name)
    } else {
        SportsLiveDisplayText.fullName(meta.name, meta.description)
    }
