package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.identify.TeamIdentityRules

/**
 * Channel names an events catalog lists beside its games without a "Network", "Channel" or "TV" suffix (live.20,
 * user TV report #4): "Fox Footy", "Sky Sports Main Event", "beIN Sports 1", "TSN 4", "ESPN 2". A title is a
 * broadcaster's channel when, after any region codes ("US", "UK"), it starts with a [BRANDS] name and every other word
 * is a channel's filler ([FILLER]: numbers, quality, "Main Event", a sport word, a region). A title naming anything
 * more ("Sky Sports F1 Grand Prix Brazil", "ESPN FC") is no channel. Placeholder titles that name nothing but a
 * stream ("Streams", "Live Stream 2", "Backup Links") are channels too: playable, but no game to list.
 *
 * The caller checks that the title names no two sides first. Plain word work on memoised name keys, no regex (#152).
 * Data-driven: add a brand or a filler word here.
 */
object SportsLiveBroadcasters {

    /** Broadcaster brands, as name keys (lowercase, accents folded, punctuation a space: "Canal+" is "canal"). */
    private val BRANDS: List<List<String>> = listOf(
        // Australia, New Zealand
        "fox footy", "fox league", "fox cricket", "fox sports", "kayo", "stan sport", "optus sport", "sky sport nz",
        // UK, Ireland
        "sky sports", "sky sport", "tnt sports", "tnt sport", "bt sport", "premier sports", "eurosport", "racing tv",
        "sky racing", "setanta sports", "viaplay sports", "viaplay",
        // US, Canada
        "espn", "espn2", "espnu", "espnews", "espn deportes", "fs1", "fs2", "nbc sports", "cbs sports", "tsn", "sportsnet",
        "bally sports", "fanduel sports", "nesn", "msg", "marquee sports", "spectrum sportsnet", "root sports", "tudn",
        "willow", "willow cricket",
        // Middle East, Africa, Asia
        "bein sports", "bein sport", "bein", "supersport", "ssc", "alkass", "ad sports", "star sports", "sony sports",
        "sony ten", "ten sports", "ptv sports", "a sports", "astro supersport", "t sports", "gtv",
        // Europe, Latin America
        "dazn", "canal sport", "rmc sport", "movistar", "movistar deportes", "arena sport", "sport klub", "sportklub",
        "digi sport", "polsat sport", "eleven sports", "tvp sport", "nova sport", "ziggo sport", "sport tv", "tyc sports",
        "espn premium", "sky sport uno", "sky sport calcio"
    ).map { brand -> TeamIdentityRules.nameKey(brand).split(' ') }.sortedByDescending { it.size }

    /** Words a channel's name carries beside its brand ("Sky Sports Main Event HD", "beIN Sports MAX 4"). */
    private val FILLER = setOf(
        "hd", "fhd", "uhd", "4k", "sd", "hevc", "1080p", "720p", "main", "event", "action", "arena", "premier", "league",
        "football", "footy", "cricket", "golf", "f1", "racing", "tennis", "nfl", "nba", "mix", "extra", "plus", "max", "xtra",
        "select", "premium", "ultra", "one", "two", "news", "deportes", "espanol", "english", "east", "west", "pacific",
        "backup", "stream", "streams", "live", "feed", "alt", "channel", "network", "tv", "mena", "asia", "africa", "uno",
        "due", "calcio", "sport", "sports", "box", "office", "ppv"
    )

    /** Region codes before or after a channel's name ("US: ESPN 2", "Sky Sports 1 UK"). */
    private val REGIONS = setOf(
        "us", "usa", "uk", "gb", "au", "aus", "nz", "ca", "ie", "de", "es", "fr", "it", "pt", "br", "mx", "ar", "za", "in",
        "ae", "qa", "sa", "nl", "be", "pl", "ro", "rs", "hr", "tr", "gr", "se", "no", "dk", "fi", "latam", "intl"
    )

    /** Words a placeholder title is made of; it must say one of [PLACEHOLDER_CORE]. */
    private val PLACEHOLDER = setOf(
        "stream", "streams", "link", "links", "mirror", "mirrors", "server", "servers", "feed", "feeds", "source", "sources",
        "live", "watch", "backup", "alt", "alternative", "hd", "sd", "all", "here", "now", "multi", "multistream", "channel",
        "channels", "main", "player", "option", "options"
    )
    private val PLACEHOLDER_CORE = setOf(
        "stream", "streams", "link", "links", "mirror", "mirrors", "server", "servers", "feed", "feeds", "source", "sources",
        "multistream"
    )

    private val broadcaster = Memo(4_096) { title: String -> broadcasterUncached(title) }
    private val placeholder = Memo(4_096) { title: String -> placeholderUncached(title) }
    private val sportFeed = Memo(4_096) { title: String -> sportFeedUncached(title) }

    // Tours named alone as a feed's title; a plain sport word ("Tennis", "Ice Hockey") is one too.
    private val TOURS = setOf("atp", "wta", "itf", "atp tour", "wta tour")

    /** True when [title] is a broadcaster's channel name; see the class doc. */
    fun isBroadcaster(title: String): Boolean = broadcaster(title)

    /** True when [title] names nothing but a stream ("Streams", "Live Stream 2", "Backup Links"). */
    fun isPlaceholder(title: String): Boolean = placeholder(title)

    /**
     * True when [title] names nothing but a sport or a tour, with at most a stream word ("Tennis", "ATP", "WTA",
     * "Live Tennis", "Ice Hockey HD"; TV report #5): a rolling feed of whatever is on, like a channel, and no event to
     * list on Live now. A title naming more ("ATP Finals 2026", "ATP 250 · Stockholm", "Court 3") is an event.
     */
    fun isSportFeed(title: String): Boolean = sportFeed(title)

    private fun words(title: String): List<String> =
        TeamIdentityRules.nameKey(SportsLiveDisplayText.name(title)).split(' ').filter { it.isNotEmpty() }

    private fun broadcasterUncached(title: String): Boolean {
        val words = words(title)
        var start = 0
        while (start < words.size && words[start] in REGIONS) start++
        val brand = BRANDS.firstOrNull { brand ->
            start + brand.size <= words.size && brand.indices.all { words[start + it] == brand[it] }
        } ?: return false
        return words.drop(start + brand.size).all { it in FILLER || it in REGIONS || it.all(Char::isDigit) }
    }

    private fun sportFeedUncached(title: String): Boolean {
        val core = words(title).filter { it !in PLACEHOLDER }.joinToString(" ")
        return core.isNotEmpty() && (core in TOURS || SportsLiveSportKind.isPlainSportWord(core))
    }

    private fun placeholderUncached(title: String): Boolean {
        val words = words(title)
        return words.isNotEmpty() && words.any { it in PLACEHOLDER_CORE } &&
            words.all { it in PLACEHOLDER || it.all(Char::isDigit) }
    }
}
