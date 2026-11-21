package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.identify.TeamIdentityRules
import com.nuvio.tv.domain.model.SportsLiveItem

/**
 * The country a channel with no written region implies by its network alone (live.24): an unambiguous US network
 * ("ABC", "📺 CBS", "ESPN 2", "ABC News Live", "FOX Deportes") is a US channel, so Channels lists it under the United
 * States instead of "No country listed". Strict on purpose:
 * - the name starts with a listed network ([NETWORKS], after "US"/quality words) and has nothing after it but a feed
 *   number or a plain feed word ([FEED_WORDS]): "ABC Australia", "CNN News18" and "FOX Sports 501 (cricket)" say more,
 *   so they stay unknown;
 * - a longer brand made for another country wins ("FOX Sports 503" is Australia's, "TNT Sports 1" the UK's,
 *   [SportsLiveChannelBrands]);
 * - a name or group naming another country or a non-US broadcaster ([NOT_US]) is never inferred.
 * A written region (group, "US|" prefix) always comes first ([SportsLiveChannelRegions.regionOf]). Generic: no addon.
 * A whole catalog can be a US list too ([wholeCatalog]).
 */
object SportsLiveUsNetworks {

    const val US = "US"

    /** Networks that are US channels wherever an addon lists them, as name words (lowercase, [TeamIdentityRules.nameKey]). */
    private val NETWORKS: List<List<String>> = listOf(
        "abc", "cbs", "nbc", "fox", "espn", "espn2", "espnu", "espnews", "tnt", "tbs", "usa network",
        "fs1", "fs2", "fox sports 1", "fox sports 2", "nfl network", "nfl redzone", "nba tv", "mlb network", "nhl network",
        "big ten network", "btn", "sec network", "acc network", "golf channel", "tennis channel",
        "cbs sports network", "cbs sports", "nbc sports", "cnn", "msnbc", "fox news", "fox business", "cw", "the cw",
        "pbs", "amc", "bravo", "hgtv", "fx", "fxx", "fxm", "freeform", "a e", "ae", "american heroes channel", "axs",
        "bbc america", "hallmark channel", "oxygen"
    ).map { it.split(' ') }.sortedByDescending { it.size }

    /** Words a name may start with before its network ("US ESPN", "HD ABC"). */
    private val LEADING = setOf("us", "usa", "hd", "fhd", "uhd", "4k", "sd", "vip", "raw")

    /** Words that may follow the network without changing its country: quality, feed and plain variant words. */
    private val FEED_WORDS = setOf(
        "hd", "fhd", "uhd", "4k", "sd", "east", "west", "pacific", "news", "live", "deportes", "sports", "network",
        "channel", "tv", "plus", "us", "usa", "backup", "alt"
    )

    /** Words that name another country, region or a non-US broadcaster: never inferred as US. Kept short. */
    private val NOT_US = setOf(
        "australia", "au", "aus", "canada", "ca", "uk", "gb", "britain", "kingdom", "ireland", "nz", "zealand",
        "mexico", "mx", "latino", "latin", "latam", "brasil", "brazil", "argentina", "india", "africa", "arabia",
        "asia", "europe", "international", "intl", "sky", "tsn", "dazn", "bein", "foxtel", "kayo"
    )

    private val memo = Memo(16_384) { key: Pair<String, String> -> countryUncached(key.first, key.second) }

    /** [US] when [name] (with its [group], if any) is an unambiguous US network's channel; else null. */
    fun countryOf(name: String, group: String = ""): String? = memo(name to group)

    private fun countryUncached(name: String, group: String): String? {
        val words = words(SportsLiveDisplayText.name(name))
        if (words.any { it in NOT_US } || words(group).any { it in NOT_US }) return null
        // "USA Network" is a network; "US ESPN" and "HD ABC" lead with a region or quality word.
        var start = 0
        var network = networkAt(words, start)
        while (network == null && start < words.size && words[start] in LEADING) network = networkAt(words, ++start)
        if (network == null) return null
        val rest = words.drop(start + network.size)
        if (rest.any { word -> word !in FEED_WORDS && !word.all(Char::isDigit) }) return null
        val family = SportsLiveChannelBrands.familyOf(name)?.family
        if (family != null && family.homes.isNotEmpty() && US !in family.homes) return null
        return US
    }

    private fun networkAt(words: List<String>, start: Int): List<String>? = NETWORKS.firstOrNull { network ->
        start + network.size <= words.size && network.indices.all { words[start + it] == network[it] }
    }

    /** Share of a catalog's channels with no written region that must be US networks for all of them to be US. */
    const val CATALOG_SHARE = 0.6

    /** A catalog with fewer channels with no written region says too little to call it a US list. */
    const val CATALOG_MIN = 5

    /**
     * [items] (one catalog's) with every channel that writes no region filed under the US (live.24), when at least
     * [CATALOG_SHARE] of those are US networks ([countryOf]) and none names another country or a non-US brand
     * ([namesOtherCountry]): a "Networks" list of ABC, CBS and ESPN also lists A&E and Animal Planet as US channels.
     * A catalog mixing in "beIN Sports Mena", "Canal+ Sport" or "Hub Sports Singapore" is no US list: nothing changes.
     */
    fun wholeCatalog(items: List<SportsLiveItem>): List<SportsLiveItem> {
        val unwritten = items.filter { it.isChannel && SportsLiveChannelRegions.writtenRegion(it) == null }
        if (unwritten.size < CATALOG_MIN) return items
        val us = unwritten.count { countryOf(it.meta.name, it.group?.trim().orEmpty()) != null }
        if (us < unwritten.size * CATALOG_SHARE) return items
        if (unwritten.any { namesOtherCountry(it.meta.name) }) return items
        val keys = unwritten.mapTo(HashSet()) { it.streamKey }
        return items.map { if (it.streamKey in keys) it.copy(group = US) else it }
    }

    /**
     * True when [name] names a country other than the US ("Hub Sports 1 Singapore"), a region or non-US broadcaster
     * ([NOT_US]), or starts with a brand made for other countries only ("Canal+ Sport", "BBC One London").
     */
    fun namesOtherCountry(name: String): Boolean {
        val words = words(SportsLiveDisplayText.name(name))
        if (words.any { it in NOT_US }) return true
        if ((1..3).any { size -> words.windowed(size).any { it.joinToString(" ") in OTHER_COUNTRIES } }) return true
        val homes = SportsLiveChannelBrands.familyOf(name)?.family?.homes.orEmpty()
        return homes.isNotEmpty() && US !in homes
    }

    /** Every country's English name but the US's, as name words ("singapore", "sri lanka"). */
    private val OTHER_COUNTRIES: Set<String> by lazy {
        java.util.Locale.getISOCountries().filter { it != US }
            .mapTo(HashSet()) { TeamIdentityRules.nameKey(java.util.Locale("", it).getDisplayCountry(java.util.Locale.ENGLISH)) }
    }

    private fun words(text: String): List<String> =
        if (text.isBlank()) emptyList() else TeamIdentityRules.nameKey(text).split(' ').filter { it.isNotEmpty() }
}
