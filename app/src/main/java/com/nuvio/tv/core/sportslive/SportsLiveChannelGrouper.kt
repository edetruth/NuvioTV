package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.MetaPreview
import java.util.Locale

/** Derives the group a channel is filed under on the Channels tab. */
object SportsLiveChannelGrouper {
    private val countryCode = Regex("^[A-Z]{2,3}$")
    private val prefixSeparator = Regex("""\||\s:\s""")
    private val groupPrefix = Regex("""^[\p{L}0-9-]{2,8}$""")
    private val letter = Regex("""\p{L}""")
    // Emoji (flags are pairs of So code points) and their joiners/variation selectors, plus the "(tv)" type tag.
    private val catalogNoise = Regex("""[\p{So}\p{Sk}\p{Cs}\u200D\uFE0F]|\(tv\)""", RegexOption.IGNORE_CASE)
    private val liveOrLiveTv = SportsLiveKeywords.LIVE + SportsLiveKeywords.LIVE_TV

    /** Tried in order; the first non-null result is the group. Add rules for other addons here. */
    private val strategies: List<(MetaPreview) -> String?> = listOf(
        // highfly: second genre is a channel code like "UK-TNT-SPORT-13434"; a bare tag like "NFL" is not.
        { meta ->
            meta.genres.getOrNull(1)?.takeIf { '-' in it }
                ?.substringBefore('-')?.trim()?.takeIf { countryCode.matches(it) }
        },
        // Name prefix like "US : ESPN HD", "UK| SKY SPORTS 1" or "ASIA-IN| JIO"; "(HD) : ..." does not match.
        { meta -> namePrefix(meta.name) }
    )

    /**
     * Falls back to the catalog name ("🇬🇧 United Kingdom TV" → "United Kingdom TV"), then the addon name;
     * a generic catalog name ("Channels", "Live TV") is skipped, so two addons' channels never share it,
     * and so is an events catalog's name ("USA Live", "Live Events", "Today"), which says nothing about the
     * channel; "Live TV" names a kind of catalog, not an event, so "EasyPlay Live TV" stays a group.
     * Groups are upper-cased so "BiH" and "BIH" are one group.
     */
    fun groupOf(meta: MetaPreview, catalogName: String, addonName: String): String =
        (strategies.firstNotNullOfOrNull { it(meta) }
            ?: catalogName.replace(catalogNoise, "").trim().takeIf {
                it.isNotEmpty() && it.lowercase(Locale.ROOT) !in SportsLiveKeywords.GENERIC_CHANNEL_CATALOGS &&
                    !isEventsCatalogName(it)
            }
            ?: addonName).uppercase(Locale.ROOT)

    private fun isEventsCatalogName(name: String): Boolean =
        SportsLiveKeywords.findKeyword(name, liveOrLiveTv) == "live" ||
            SportsLiveKeywords.containsKeyword(name, SportsLiveKeywords.EVENTS) ||
            SportsLiveKeywords.containsKeyword(name, SportsLiveKeywords.UPCOMING)

    private fun namePrefix(name: String): String? {
        val separator = prefixSeparator.find(name) ?: return null
        return name.substring(0, separator.range.first).trim()
            .takeIf { groupPrefix.matches(it) && letter.containsMatchIn(it) }
    }
}
