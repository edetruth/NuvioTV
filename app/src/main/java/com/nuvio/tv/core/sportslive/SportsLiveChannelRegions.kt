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

    /** The region [item]'s group or title writes ("US", "UK", an IPTV tag such as "DSTV"); null when it writes none. */
    fun writtenRegion(item: SportsLiveItem): String? = writtenCode(item, item.group?.trim().orEmpty())

    private fun writtenCode(item: SportsLiveItem, group: String): String? = written(item.meta.name to group)

    private val written = Memo(16_384) { key: Pair<String, String> ->
        val (name, group) = key
        val region = TAGS[group.uppercase(Locale.ROOT)]
            ?: code(group)
            // A tag's code after its dash ("ASIA-IN"); never a catalog name's last word ("SOCCER - TV" is no Tuvalu, #26).
            ?: group.takeIf { it.none(Char::isWhitespace) }?.substringAfterLast('-')?.let(::code)
            ?: namePrefixRegion(name)
            ?: nameSuffixCountry(name)
            ?: SportsLiveDisplayText.of(name).region?.let(::code)
        // "24/7 AR| <Arabic title>": AR is Arabic there, not Argentina.
        if (region == "AR" && ARABIC_SCRIPT.containsMatchIn(name)) ARABIC else region
    }

    /**
     * The region a title's own prefix names when the group missed it (live.24): an IPTV tag ("EXYU| …"), the last or
     * first code of a spaced or dashed prefix ("VIP - PK| …", "AF-WAFCON| …") or a country written out ("SRI LANKA| …").
     */
    private fun namePrefixRegion(name: String): String? {
        val bar = name.indexOf('|')
        if (bar !in 2..PREFIX_MAX) return null
        val prefix = name.substring(0, bar).trim().uppercase(Locale.ROOT)
        TAGS[prefix]?.let { return it }
        val tokens = prefix.split(' ', '-').filter { it.isNotEmpty() }
        return tokens.lastOrNull()?.let(::code) ?: tokens.firstOrNull()?.let(::code) ?: COUNTRY_NAMES[prefix]
    }

    private const val PREFIX_MAX = 16

    /** A country written out in brackets after the name ("ESPN (United States)", #26); null for any other bracket. */
    private fun nameSuffixCountry(name: String): String? =
        BRACKET_SUFFIX.find(name)?.groupValues?.get(1)?.trim()?.uppercase(Locale.ROOT)?.let { COUNTRY_NAMES[it] }

    private val BRACKET_SUFFIX = Regex("""\(([^()]+)\)\s*$""")

    /** Arabic channels' region (non-ISO, labelled [NAMES]). */
    private const val ARABIC = "ARB"

    /** The Balkans (ex-Yugoslavia) region (non-ISO, labelled [NAMES]). */
    private const val BALKANS = "BLK"

    /**
     * IPTV group tags that name a region but no country code (live.24): Africa's DStv lists, ex-Yugoslav ("EXYU"),
     * Arabic ("AR-SP", "ARABIC"), Latin America's Caribbean list ("LATIN-CAR", read as a code it would be the Central
     * African Republic). Any other tag stays "No country listed".
     */
    private val TAGS: Map<String, String> = mapOf(
        "DSTV" to "AF", "AFRICA" to "AF", "EXYU" to BALKANS, "EX-YU" to BALKANS, "BALKANS" to BALKANS, "BALKAN" to BALKANS,
        "AR-SP" to ARABIC, "ARABIC" to ARABIC, "ARAB" to ARABIC, "LATIN-CAR" to "LAT", "LATIN" to "LAT", "LATINO" to "LAT"
    )

    private val ARABIC_SCRIPT = Regex("""\p{InArabic}""")

    /** Countries written out in English ("SRI LANKA"), upper case, to their region code. */
    private val COUNTRY_NAMES: Map<String, String> by lazy {
        Locale.getISOCountries().associateBy { Locale("", it).getDisplayCountry(Locale.ENGLISH).uppercase(Locale.ROOT) }
            .mapValues { (_, iso) -> if (iso == "GB") "UK" else iso }
    }

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

    private val NAMES = mapOf(
        "AF" to "Africa", "EU" to "Europe", "LAT" to "Latin America", "INT" to "International", ARABIC to "Arabic",
        BALKANS to "Balkans"
    )

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
        addAll(listOf("UK", "EU", "LAT", "INT", ARABIC, BALKANS))
    }
}
