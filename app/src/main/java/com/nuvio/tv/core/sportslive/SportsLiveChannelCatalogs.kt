package com.nuvio.tv.core.sportslive

/**
 * Which `tv` catalogs list TV channels (live.24, user report: an IPTV list of ~1,700 "US| ABC HD" channels and a
 * second ~1,000-channel TV list never reached Channels, as their names hold no channel word). Generic rules, no addon:
 * - by name ([namesChannels]): "<Brand> IPTV", "<Brand> TV +", "Live TV", unless it names events, games, a schedule
 *   or replays;
 * - by items ([mostlyCountryPrefixed]): most of the first page's titles start with a country code ("US| …",
 *   "UK : …", "(US) …", "ASIA-IN| …", "LATIN-BR| …").
 * A channel catalog's entries titled as a game are still games ([SportsLiveItemBucketer]), so no event leaks into
 * Channels and no channel into the games tabs.
 */
object SportsLiveChannelCatalogs {

    /** Share of a first page whose titles must carry a country prefix. */
    const val PREFIXED_SHARE = 0.6

    /** A page this short says too little to call it a channel list by its titles. */
    const val MIN_ITEMS = 10

    /** Words in a catalog name that say it lists channels. */
    private val CHANNEL_WORDS: Set<String> = setOf("iptv", "tv", "live tv")

    /** Words that say a catalog lists games or shows, not channels ("TV Events", "TV Schedule", "TV Replays"). */
    private val NOT_CHANNEL_WORDS: Set<String> = SportsLiveKeywords.EVENTS + SportsLiveKeywords.UPCOMING +
        SportsLiveKeywords.REPLAY + setOf("game", "games", "match", "matches", "fixtures", "shows", "series", "movies")

    /** The addon's own "(tv)" type tag after a catalog name: every catalog of some addons carries it. */
    private val TYPE_TAG = Regex("""\s*\(\s*(?:tv|channel|channels|sport|sports|movie|series|other)\s*\)\s*$""", RegexOption.IGNORE_CASE)

    /** "US|", "US :", "UK |", "ASIA-IN|" or "(US)", "[US]" at the start of a title. */
    private val PREFIX = Regex("""^\s*(?:[(\[]\s*([A-Za-z]{2,3})\s*[)\]]|([A-Za-z]{2,6}(?:-[A-Za-z]{2,3})?)\s*[|:])""")

    /** True when [catalogName] says the catalog lists channels: IPTV, TV as a word, Live TV; not events or games. */
    fun namesChannels(catalogName: String): Boolean {
        val name = TYPE_TAG.replace(catalogName, "").trim()
        return SportsLiveKeywords.containsKeyword(name, CHANNEL_WORDS) &&
            !SportsLiveKeywords.containsKeyword(name, NOT_CHANNEL_WORDS)
    }

    /** True when at least [PREFIXED_SHARE] of [titles] (and [MIN_ITEMS] or more) start with a country code. */
    fun mostlyCountryPrefixed(titles: List<String>): Boolean {
        if (titles.size < MIN_ITEMS) return false
        val prefixed = titles.count(::hasCountryPrefix)
        return prefixed >= titles.size * PREFIXED_SHARE
    }

    /** True when [title] starts with a country code and a separator ("US| ESPN", "(UK) Sky Sports", "ASIA-IN| STAR SPORTS 1"). */
    fun hasCountryPrefix(title: String): Boolean {
        val match = PREFIX.find(title) ?: return false
        val code = match.groupValues[1].ifEmpty { match.groupValues[2] }
        return SportsLiveChannelRegions.isCode(code.substringAfterLast('-'))
    }
}
