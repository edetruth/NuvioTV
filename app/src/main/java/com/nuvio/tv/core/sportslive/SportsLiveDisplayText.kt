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
 * - repeated whitespace collapses, and an ALL-CAPS name becomes Title Case, keeping known acronyms.
 *
 * Generic: it knows no addon. Matching and scores keep reading the raw title.
 */
object SportsLiveDisplayText {

    /** [raw]'s display title (cached: titles repeat on every recomposition). */
    fun of(raw: String): SportsLiveDisplayTitle = synchronized(cache) {
        cache.getOrPut(raw) { parse(raw) }
    }

    /** [raw]'s clean name alone. */
    fun name(raw: String): String = of(raw).name

    /**
     * A description without taglines and ids: lines that are only a tagline go, taglines inside a line
     * are cut out, blank lines collapse. Null when nothing is left.
     */
    fun description(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw.lines()
            .map { stripTaglines(it) }
            .map { collapse(it) }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .ifEmpty { null }
    }

    private fun parse(raw: String): SportsLiveDisplayTitle {
        var text = collapse(raw)
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
        if (isAllCaps(text)) text = titleCase(text)
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
            // "F1", "4K", "ESPN2": a token with digits is a code; it keeps its case.
            word.any { it.isDigit() } -> word
            !first && upper in SMALL_WORDS -> word.lowercase(Locale.ROOT)
            else -> word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
        }
    }

    private const val QUALITY = """(?:FHD|UHD|HD|SD|4K|8K)"""
    private val SEPARATORS = charArrayOf(':', '|', '-', '–', '—', '·', '•', '/', ',', ' ')

    /** "(FHD) :", "[HD]", "HD :", "4K |" at the start: bracketed, or followed by a separator ("HDTV" is a name). */
    private val QUALITY_PREFIX =
        Regex("""^(?:[(\[]\s*($QUALITY)\s*[)\]]\s*[:|\-–]?|($QUALITY)\s*[:|\-–])\s*(?=\S)""", RegexOption.IGNORE_CASE)

    /** "Name HD", "Name (FHD)", "Name [4K]", "Name - HD" at the end. */
    private val QUALITY_SUFFIX = Regex("""[\s:|\-–]+[(\[]?($QUALITY)[)\]]?\s*$""", RegexOption.IGNORE_CASE)

    /** "US :", "UK|", "CA -" at the start: a two- or three-letter code and a separator. */
    private val REGION_PREFIX = Regex("""^([A-Za-z]{2,3})\s*[:|]\s*(?=\S)""")

    private val WHITESPACE = Regex("""\s+""")

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

    /** Kept lower case inside a Title-Cased name. */
    private val SMALL_WORDS = setOf("VS", "V", "AT", "OF", "AND", "THE", "IN", "ON", "DE", "LA", "DEL")

    private val cache = object : LinkedHashMap<String, SportsLiveDisplayTitle>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SportsLiveDisplayTitle>?): Boolean = size > CACHE_SIZE
    }

    private const val CACHE_SIZE = 1024
}

/** [SportsLiveItem]'s clean title for the screen (#100); the raw `meta.name` stays for matching and Details. */
val com.nuvio.tv.domain.model.SportsLiveItem.displayName: String get() = SportsLiveDisplayText.name(meta.name)
