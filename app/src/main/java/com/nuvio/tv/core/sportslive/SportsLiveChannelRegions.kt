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

    /** [item]'s region: a country code, a long group name, or [OTHER]. */
    fun regionOf(item: SportsLiveItem): String {
        val group = item.group?.trim().orEmpty()
        return code(group)
            ?: group.substringAfterLast('-').let(::code)
            ?: SportsLiveDisplayText.of(item.meta.name).region?.let(::code)
            ?: group.takeIf { it.length > LONG_NAME_MIN && it.any(Char::isWhitespace) }
            ?: OTHER
    }

    /** [regions] in the list's order: country codes A to Z, then long names A to Z, [OTHER] last. */
    fun ordered(regions: Collection<String>): List<String> =
        regions.distinct().sortedWith(compareBy<String>({ rankOf(it) }, { it.lowercase(Locale.ROOT) }))

    /** [items] grouped by region in [ordered] order, each region's channels as listed. */
    fun grouped(items: List<SportsLiveItem>): List<Pair<String, List<SportsLiveItem>>> {
        val byRegion = items.groupBy(::regionOf)
        return ordered(byRegion.keys).map { it to byRegion.getValue(it) }
    }

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
