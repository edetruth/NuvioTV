package com.nuvio.tv.core.sportslive

import java.util.Locale

/**
 * The one short-tag vocabulary of Sports & Live (live.20 F5, #179, #217): a row's league or sport tag in at most
 * [MAX_CHARS] capitals, so a fixed tag column never cuts it and names start at one x on every row. The in-player panel
 * uses it; Live Now's rows, Home's cards and the dual captions take the same words.
 *
 * #217 (users could not read "BBALL", "CS", "AF", "ALM"): a tag is a league name fans know ("NBA", "NCAAM", "AFL",
 * "A-LEAGUE", "LIGA MX", "UFC") or a whole plain sport word ("BASKETBALL", "FIGHT", "TENNIS", "RACING"), never an
 * invented abbreviation.
 * - A tag of up to [MAX_CHARS] stays as it is.
 * - A longer one takes its sport's word ([sportWord]), else the row's sport word, else its first word when that fits.
 */
object SportsLiveTags {
    /** The longest tag: ten capitals ("BASKETBALL", "BUNDESLIGA"); the tag columns are sized for it. */
    const val MAX_CHARS = 10

    /** [tag] (any league or sport tag, any case) in the short vocabulary; [sport] is the row's sport when known. Empty stays empty. */
    fun short(tag: String, sport: SportsLiveSportKind?): String {
        val text = tag.trim().uppercase(Locale.ROOT)
        if (text.isEmpty()) return ""
        PLAIN_WORDS[text]?.let { return it }
        // A nationality alone ("INDIAN") is no tag: its country (live.23).
        COUNTRIES[text]?.let { return it }
        if (text.length <= MAX_CHARS) return text
        SportsLiveSportKind.of(text)?.let { return sportWord(it) }
        // "INDIAN SUPER LEAGUE" no table knows: its country, never "INDIAN" (live.23); else the sport, else its next word.
        val adjective = leadingNationality(text)
        if (adjective != null) {
            COUNTRIES[adjective]?.let { return it }
            sport?.let { return sportWord(it) }
            text.removePrefix(adjective).trim().split(' ').first().takeIf { it.isNotEmpty() && it.length <= MAX_CHARS }?.let { return it }
            return text.take(MAX_CHARS).trim()
        }
        sport?.let { return sportWord(it) }
        text.split(' ').first().takeIf { it.isNotEmpty() && it.length <= MAX_CHARS }?.let { return it }
        return text.take(MAX_CHARS).trim()
    }

    // The nationality [text] starts with ("COSTA RICAN", "INDIAN"), or null.
    private fun leadingNationality(text: String): String? {
        val words = text.split(' ').filter { it.isNotEmpty() }
        if (words.size >= 2 && "${words[0]} ${words[1]}" in NATIONALITIES) return "${words[0]} ${words[1]}"
        return words.firstOrNull()?.takeIf { it in NATIONALITIES }
    }

    /** Nationalities competitions are named by, and the country a tag says instead (when it fits ten capitals). */
    private val COUNTRIES: Map<String, String> = mapOf(
        "ENGLISH" to "ENGLAND", "SPANISH" to "SPAIN", "GERMAN" to "GERMANY", "ITALIAN" to "ITALY", "FRENCH" to "FRANCE",
        "SCOTTISH" to "SCOTLAND", "WELSH" to "WALES", "IRISH" to "IRELAND", "PORTUGUESE" to "PORTUGAL", "TURKISH" to "TURKEY",
        "GREEK" to "GREECE", "BELGIAN" to "BELGIUM", "AUSTRIAN" to "AUSTRIA", "DANISH" to "DENMARK", "SWEDISH" to "SWEDEN",
        "NORWEGIAN" to "NORWAY", "FINNISH" to "FINLAND", "RUSSIAN" to "RUSSIA", "UKRAINIAN" to "UKRAINE", "POLISH" to "POLAND",
        "CZECH" to "CZECHIA", "CROATIAN" to "CROATIA", "SERBIAN" to "SERBIA", "ROMANIAN" to "ROMANIA", "HUNGARIAN" to "HUNGARY",
        "ISRAELI" to "ISRAEL", "CYPRIOT" to "CYPRUS", "CHINESE" to "CHINA", "JAPANESE" to "JAPAN", "KOREAN" to "KOREA",
        "THAI" to "THAILAND", "INDONESIAN" to "INDONESIA", "MALAYSIAN" to "MALAYSIA", "INDIAN" to "INDIA",
        "AUSTRALIAN" to "AUSTRALIA", "AMERICAN" to "USA", "CANADIAN" to "CANADA", "MEXICAN" to "MEXICO", "BRAZILIAN" to "BRAZIL",
        "ARGENTINE" to "ARGENTINA", "ARGENTINIAN" to "ARGENTINA", "CHILEAN" to "CHILE", "COLOMBIAN" to "COLOMBIA",
        "PERUVIAN" to "PERU", "URUGUAYAN" to "URUGUAY", "PARAGUAYAN" to "PARAGUAY", "ECUADORIAN" to "ECUADOR",
        "VENEZUELAN" to "VENEZUELA", "BOLIVIAN" to "BOLIVIA", "HONDURAN" to "HONDURAS", "GUATEMALAN" to "GUATEMALA",
        "COSTA RICAN" to "COSTA RICA", "EGYPTIAN" to "EGYPT", "MOROCCAN" to "MOROCCO", "NIGERIAN" to "NIGERIA", "GHANAIAN" to "GHANA"
    )

    // Nationalities whose country does not fit a tag: never a tag alone either.
    private val NATIONALITIES: Set<String> = COUNTRIES.keys + setOf("DUTCH", "SWISS", "SAUDI", "SOUTH AFRICAN", "SALVADORAN")

    /** A sport's tag: its plain word ("BASKETBALL", "WRESTLING"); motorsport reads "RACING". */
    fun sportWord(sport: SportsLiveSportKind): String = when (sport) {
        SportsLiveSportKind.MOTORSPORT -> "RACING"
        else -> sport.name
    }

    /**
     * Sport names that are no word fans use on a row (#217), as the plain one: "COMBAT SPORTS" → "FIGHT", "AUSTRALIAN
     * FOOTBALL" → "AFL" (the one league that plays it), "MOTORSPORT" → "RACING".
     */
    private val PLAIN_WORDS = mapOf(
        "COMBAT SPORTS" to "FIGHT", "COMBAT SPORT" to "FIGHT", "COMBAT" to "FIGHT", "FIGHTING" to "FIGHT",
        "AUSTRALIAN FOOTBALL" to "AFL", "AUSSIE RULES" to "AFL", "AUSTRALIAN RULES" to "AFL", "AUSTRALIAN RULES FOOTBALL" to "AFL",
        "MOTORSPORT" to "RACING", "MOTORSPORTS" to "RACING", "MOTOR SPORT" to "RACING", "MOTOR SPORTS" to "RACING", "MOTOR" to "RACING"
    )
}
