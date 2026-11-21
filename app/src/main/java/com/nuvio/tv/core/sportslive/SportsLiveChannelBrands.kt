package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.identify.TeamIdentityRules

/**
 * A channel brand family (Channels phase 2): its [key] (name words), [tier] (1: national flagship sports networks,
 * 2: big general networks, 3: other sports brands), the countries it is made for ([homes], empty: international) and
 * its [rank] in the curated list (lower first within a tier).
 */
data class SportsLiveChannelBrand(val key: String, val tier: Int, val homes: Set<String>, val rank: Int)

/** A channel's brand [family] and its [feed] in it ("" for the main one; "2", "main event", "502"). */
data class SportsLiveBrandFeed(val family: SportsLiveChannelBrand, val feed: String) {
    /** One Popular row per family and feed (§4.3): "ESPN" and "ESPN 2" are two rows, "ESPN HD" and "US: ESPN" one. */
    val rowKey: String get() = if (feed.isEmpty()) family.key else "${family.key} $feed"
}

/**
 * The curated brand list behind Popular now (Channels phase 2, design brief §4): data, versioned with the app, about 250
 * brand keys in 3 tiers. A channel belongs to the longest family its clean name starts with (after region words);
 * the words after it, less quality, region and variant words ("HD", "East", "Backup"), are its feed. Generic: no addon
 * is named here. Add a brand by adding a line.
 */
object SportsLiveChannelBrands {

    private class Entry(val names: List<String>, val tier: Int, val homes: Set<String>)

    private fun t(tier: Int, homes: String, vararg names: String) =
        Entry(names.toList(), tier, homes.split(' ').filter { it.isNotEmpty() }.toSet())

    private val ENTRIES: List<Entry> = listOf(
        // Tier 1: national flagship sports networks.
        t(1, "US", "espn"), t(1, "US", "fs1", "fox sports 1"), t(1, "US", "nbc sports"), t(1, "US", "cbs sports", "cbs sports network"),
        t(1, "US", "nfl network"), t(1, "US", "nba tv"), t(1, "US", "mlb network"), t(1, "US", "nhl network"),
        t(1, "US", "golf channel"), t(1, "US", "tennis channel"), t(1, "US", "big ten network", "btn"), t(1, "US", "sec network"),
        t(1, "US", "acc network"), t(1, "US", "espnu"), t(1, "US", "nfl redzone", "redzone"),
        t(1, "UK IE", "sky sports"), t(1, "UK IE", "tnt sports"), t(1, "UK IE", "bt sport"), t(1, "UK IE", "premier sports"),
        t(1, "CA", "tsn"), t(1, "CA", "sportsnet"), t(1, "CA", "rds"), t(1, "CA", "tva sports"),
        t(1, "AU", "fox sports"), t(1, "AU", "kayo"), t(1, "AU", "fox footy"), t(1, "AU", "fox league"), t(1, "AU", "fox cricket"),
        t(1, "", "bein sports", "bein sport", "bein"), t(1, "", "dazn"), t(1, "", "eurosport"),
        t(1, "ZA NG KE GH AF", "supersport"), t(1, "IN", "star sports"), t(1, "IN", "sony sports", "sony ten"),
        t(1, "ES", "movistar", "movistar deportes", "m laliga", "laliga tv"), t(1, "FR", "canal sport", "canal sports"),
        t(1, "FR", "rmc sport"), t(1, "IT DE AT CH", "sky sport"), t(1, "NL", "ziggo sport"), t(1, "PT", "sport tv"),
        t(1, "AR", "tyc sports"), t(1, "MX", "tudn"), t(1, "SA", "ssc"), t(1, "QA", "alkass"), t(1, "AE", "ad sports"),
        t(1, "SE NO DK FI", "viaplay sports", "viaplay"), t(1, "NZ", "sky sport nz"), t(1, "US CA", "willow", "willow cricket"),
        // Tier 2: big general networks (national broadcasters, news, entertainment).
        t(2, "US", "abc"), t(2, "US", "cbs"), t(2, "US", "nbc"), t(2, "US", "fox"), t(2, "US", "cnn"), t(2, "US", "fox news"),
        t(2, "US", "msnbc"), t(2, "US", "cnbc"), t(2, "US", "tbs"), t(2, "US", "tnt"), t(2, "US", "usa network"), t(2, "US", "hbo"),
        t(2, "US", "amc"), t(2, "US", "fx"), t(2, "US", "discovery", "discovery channel"), t(2, "US", "national geographic", "nat geo"),
        t(2, "US", "history", "history channel"), t(2, "US", "a e"), t(2, "US", "comedy central"), t(2, "US", "cartoon network"),
        t(2, "US", "nickelodeon"), t(2, "US", "disney channel"), t(2, "US", "pbs"), t(2, "US", "the cw", "cw"),
        t(2, "US", "telemundo"), t(2, "US", "univision"), t(2, "US", "paramount network"), t(2, "US", "bravo"),
        t(2, "UK", "bbc one", "bbc 1"), t(2, "UK", "bbc two", "bbc 2"), t(2, "UK", "bbc three", "bbc 3"), t(2, "UK", "bbc four", "bbc 4"),
        t(2, "UK", "bbc news"), t(2, "UK", "itv1", "itv 1", "itv"), t(2, "UK", "itv2", "itv 2"), t(2, "UK", "itv4", "itv 4"),
        t(2, "UK", "channel 4"), t(2, "UK", "channel 5"), t(2, "UK", "sky news"), t(2, "UK", "sky one", "sky showcase"),
        t(2, "UK", "e4"), t(2, "UK", "dave"),
        t(2, "CA", "cbc"), t(2, "CA", "ctv"), t(2, "CA", "global"), t(2, "CA", "citytv"), t(2, "CA", "tva"),
        t(2, "AU", "seven", "channel 7", "7mate"), t(2, "AU", "nine", "channel 9", "9gem"), t(2, "AU", "network 10", "channel 10"),
        t(2, "AU", "sbs"), t(2, "AU", "abc au"),
        t(2, "FR", "tf1"), t(2, "FR", "france 2"), t(2, "FR", "france 3"), t(2, "FR", "m6"), t(2, "FR", "bfm tv", "bfmtv"),
        t(2, "DE", "das erste", "ard"), t(2, "DE", "zdf"), t(2, "DE", "rtl"), t(2, "DE", "sat 1", "sat1"), t(2, "DE", "prosieben", "pro7"),
        t(2, "ES", "la 1"), t(2, "ES", "antena 3"), t(2, "ES", "telecinco"), t(2, "ES", "cuatro"),
        t(2, "IT", "rai 1", "rai uno"), t(2, "IT", "rai 2", "rai due"), t(2, "IT", "rai 3", "rai tre"), t(2, "IT", "canale 5"), t(2, "IT", "italia 1"),
        t(2, "", "al jazeera"), t(2, "", "bbc world news"), t(2, "", "euronews"), t(2, "", "france 24"), t(2, "", "dw"),
        // Tier 3: other sports brands.
        t(3, "US", "fs2", "fox sports 2"), t(3, "US", "espnews"), t(3, "US", "espn deportes"), t(3, "US", "nesn"), t(3, "US", "msg"),
        t(3, "US", "bally sports"), t(3, "US", "fanduel sports"), t(3, "US", "marquee sports"), t(3, "US", "spectrum sportsnet"),
        t(3, "US", "root sports"), t(3, "US", "pac 12 network"), t(3, "US", "cbs sports golazo", "golazo"), t(3, "US", "fox soccer plus"),
        t(3, "US", "bein sports usa"), t(3, "US", "nbc sports regional"),
        t(3, "UK IE", "racing tv"), t(3, "UK IE", "sky racing"), t(3, "UK IE", "setanta sports"), t(3, "UK IE", "lfctv"), t(3, "UK IE", "mutv"),
        t(3, "AU", "optus sport"), t(3, "AU", "stan sport"),
        t(3, "PK", "ptv sports"), t(3, "PK", "ten sports"), t(3, "PK", "a sports"), t(3, "BD", "t sports"), t(3, "BD", "gtv"),
        t(3, "MY", "astro supersport"), t(3, "RS HR BA SI", "arena sport"), t(3, "RS HR BA SI", "sport klub", "sportklub"),
        t(3, "RO", "digi sport"), t(3, "PL", "polsat sport"), t(3, "PL", "tvp sport"), t(3, "BE PT PL", "eleven sports"),
        t(3, "CZ GR", "nova sport"), t(3, "AR", "espn premium"), t(3, "IT", "sky sport uno"), t(3, "IT", "sky sport calcio"),
        t(3, "", "red bull tv"), t(3, "", "fight network"), t(3, "", "wwe network"), t(3, "", "olympic channel")
    )

    private val FAMILIES: List<Pair<List<String>, SportsLiveChannelBrand>> = ENTRIES.flatMapIndexed { index, entry ->
        val family = SportsLiveChannelBrand(TeamIdentityRules.nameKey(entry.names.first()), entry.tier, entry.homes, index)
        entry.names.map { name -> TeamIdentityRules.nameKey(name).split(' ') to family }
    }.sortedByDescending { it.first.size }

    /** Brand words written run together with their feed number ("ESPN2", "SKY SPORTS1"): split before matching. */
    private val GLUED = Regex("""^([a-z]+?)(\d+)$""")

    /** Words that make no feed: quality, region and variant words ("ESPN (East) HD" is ESPN). */
    private val VARIANTS = setOf(
        "hd", "fhd", "uhd", "4k", "sd", "hevc", "1080p", "720p", "1080", "720", "50fps", "60fps", "east", "west", "pacific",
        "backup", "alt", "feed", "live", "tv", "channel", "network", "raw", "vip", "multi", "audio", "eng", "english",
        "us", "usa", "uk", "gb", "ca", "au", "nz", "ie", "intl", "int"
    )

    /** Words a name may start with before its brand: region and quality ("US: ESPN", "(FHD) Sky Sports"). */
    private val LEADING = setOf(
        "us", "usa", "uk", "gb", "ca", "au", "nz", "ie", "intl", "int", "hd", "fhd", "uhd", "4k", "sd", "hevc", "vip", "raw"
    )

    private val familyMemo = Memo(8_192) { title: String -> familyUncached(title) }

    /** [title]'s brand family and feed; null when it starts with no listed brand. */
    fun familyOf(title: String): SportsLiveBrandFeed? = familyMemo(title)

    private fun familyUncached(title: String): SportsLiveBrandFeed? {
        val words = TeamIdentityRules.nameKey(SportsLiveDisplayText.name(title)).split(' ').filter { it.isNotEmpty() }
            .flatMap { word -> GLUED.matchEntire(word)?.takeIf { match -> isBrandWord(match.groupValues[1]) }?.groupValues?.drop(1) ?: listOf(word) }
        var start = 0
        while (start < words.size && words[start] in LEADING) start++
        for ((names, family) in FAMILIES) {
            if (start + names.size > words.size || names.indices.any { words[start + it] != names[it] }) continue
            val feed = words.drop(start + names.size).filterNot { it in VARIANTS }.joinToString(" ")
            return SportsLiveBrandFeed(family, feed)
        }
        return null
    }

    // A glued word splits only when its letters are a brand's first word ("espn2" -> "espn 2", never "e4" -> "e 4").
    private val firstWords: Set<String> by lazy { FAMILIES.map { it.first.first() }.filter { it.length > 1 }.toSet() }

    private fun isBrandWord(word: String): Boolean = word in firstWords

    /** The feed's place among a family's rows: the main feed first, then numbers in order, then names A to Z. */
    fun feedOrder(feed: String): Pair<Int, String> = when {
        feed.isEmpty() -> 0 to ""
        feed.first().isDigit() -> 1 to feed.takeWhile(Char::isDigit).padStart(6, '0') + feed
        else -> 2 to feed
    }
}
