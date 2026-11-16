package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.MetaPreview

/** Derives the group a channel is filed under on the Channels tab. */
object SportsLiveChannelGrouper {
    private val countryCode = Regex("^[A-Z]{2,3}$")

    /** Tried in order; the first non-null result is the group. Add rules for other addons here. */
    private val strategies: List<(MetaPreview) -> String?> = listOf(
        // highfly: second genre is a channel code like "UK-TNT-SPORT-13434".
        { meta -> meta.genres.getOrNull(1)?.substringBefore('-')?.trim()?.takeIf { countryCode.matches(it) } },
        // Name prefix like "US : ESPN HD"; "(HD) : ..." does not match.
        { meta ->
            meta.name.takeIf { " : " in it }?.substringBefore(" : ")?.trim()?.takeIf { countryCode.matches(it) }
        }
    )

    fun groupOf(meta: MetaPreview, addonName: String): String =
        strategies.firstNotNullOfOrNull { it(meta) } ?: addonName
}
