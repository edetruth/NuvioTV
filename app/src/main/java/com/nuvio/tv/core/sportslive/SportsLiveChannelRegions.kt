package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.SportsLiveItem
import java.util.Locale

/**
 * The region a channel is filed under on the Channels tab (V2 P4, §3.6): its group's country code ("US",
 * "UK", "CA"), else the code its title starts with ("US : …"), else a long group name as written ("United
 * Kingdom TV"); a short prefix that is no country ("NOW", an addon's own tag) goes under [OTHER]. Generic:
 * it knows no addon.
 */
object SportsLiveChannelRegions {

    /** The filter value of channels with no known region; never an addon's own text. */
    const val OTHER = "\u0000region:other"

    /**
     * [item]'s region: a country code its group or title names, else the one its name implies (live.24: an unambiguous
     * US network such as "ABC" or "ESPN 2", [SportsLiveUsNetworks]), else a long group name, else [OTHER].
     */
    fun regionOf(item: SportsLiveItem): String {
        val group = item.group?.trim().orEmpty()
        return writtenCode(item, group)
            ?: SportsLiveUsNetworks.countryOf(item.meta.name, group)
            ?: group.takeIf { it.length > LONG_NAME_MIN && it.any(Char::isWhitespace) }
            ?: OTHER
    }

    /** True when [item]'s region comes from its network's name alone ([SportsLiveUsNetworks]), not a written code. */
    fun inferred(item: SportsLiveItem): Boolean {
        val group = item.group?.trim().orEmpty()
        return writtenCode(item, group) == null && SportsLiveUsNetworks.countryOf(item.meta.name, group) != null
    }

    /** True when [text] is a country or region code ("US", "UK", "ARG"), as a channel's prefix writes one. */
    fun isCode(text: String): Boolean = code(text) != null

    private fun writtenCode(item: SportsLiveItem, group: String): String? =
        code(group)
            ?: group.substringAfterLast('-').let(::code)
            ?: SportsLiveDisplayText.of(item.meta.name).region?.let(::code)

    /** [regions] in the list's order: country codes A to Z by [label], then long names A to Z, [OTHER] last. */
    fun ordered(regions: Collection<String>, locale: Locale = Locale.getDefault()): List<String> =
        regions.distinct().sortedWith(compareBy<String>({ rankOf(it) }, { (label(it, locale) ?: it).lowercase(locale) }))

    /**
     * [items] grouped by region (P9, TV QA live.20: a sports app's Channels opened on religious and news channels):
     * the regions with a sports channel ([isSports]) first, then the rest, each part in [ordered] order and [OTHER]
     * last; in each region its sports channels first, each part as listed.
     */
    fun grouped(
        items: List<SportsLiveItem>,
        isSports: (SportsLiveItem) -> Boolean = { SportsLiveBroadcasters.isSportsChannel(it.meta.name) }
    ): List<Pair<String, List<SportsLiveItem>>> {
        val byRegion = items.groupBy(::regionOf).mapValues { (_, channels) ->
            val (sports, others) = channels.partition(isSports)
            sports + others
        }
        val (withSports, without) = ordered(byRegion.keys).partition { it != OTHER && byRegion.getValue(it).any(isSports) }
        return (withSports + without).map { it to byRegion.getValue(it) }
    }

    /**
     * A region's name in words (P8, TV QA live.20: "AF · 6", "AL · 2" read as codes): a country code by its name in
     * [locale] ("AL" → "Albania", "UK" → "United Kingdom"), the non-ISO ones by [NAMES]; a long group name as is;
     * null for [OTHER]. "AF" is Africa, not Afghanistan: sports addons file African channels (Gabon, Senegal,
     * Cameroon) under it.
     */
    fun label(region: String, locale: Locale = Locale.getDefault()): String? {
        if (region == OTHER) return null
        if (code(region) == null) return region
        NAMES[region]?.let { return it }
        val name = Locale("", if (region == "UK") "GB" else region).getDisplayCountry(locale)
        return name.takeIf { it.isNotBlank() && !it.equals(region, ignoreCase = true) } ?: region
    }

    private val NAMES = mapOf("AF" to "Africa", "EU" to "Europe", "LAT" to "Latin America", "INT" to "International")

    private fun rankOf(region: String): Int = when {
        region == OTHER -> 2
        code(region) != null -> 0
        else -> 1
    }

    private fun code(text: String): String? {
        val upper = text.trim().uppercase(Locale.ROOT)
        if (upper.length !in 2..3 || upper.any { !it.isLetter() }) return null
        if (upper !in REGIONS) return null
        // One code per country: "ARG" and "AR" are one region, as are "GB" and "UK".
        val two = ALPHA3_TO_2[upper] ?: upper
        return if (two == "GB") "UK" else two
    }

    private val ALPHA3_TO_2: Map<String, String> = buildMap {
        Locale.getISOCountries().forEach { code -> runCatching { put(Locale("", code).isO3Country.uppercase(Locale.ROOT), code) } }
    }

    private const val LONG_NAME_MIN = 3

    /** Country codes (ISO 3166 alpha-2 and alpha-3) and the usual non-ISO ones. */
    private val REGIONS: Set<String> = buildSet {
        addAll(Locale.getISOCountries())
        Locale.getISOCountries().forEach { code -> runCatching { add(Locale("", code).isO3Country.uppercase(Locale.ROOT)) } }
        addAll(listOf("UK", "EU", "LAT", "INT"))
    }
}
