package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.SportsLiveItem

/**
 * Names the pooled sources of a merged Sports & Live game for the player's Sources panel. One live-TV
 * addon can expose the same game from several provider sub-catalogs (SportzX, LivXow, …), so grouping
 * those sources by addon name alone would collapse them under one label. A source whose addon carries
 * more than one of the card's sources is labeled by its cleaned catalog/provider name; a source that is
 * its addon's only one keeps the addon name.
 */
object SportsLiveSourceLabels {
    private val LEADING_JUNK = Regex("""^[^\p{L}\p{N}]+""")
    private val TRAILING_TV = Regex("""\s*\(tv\)\s*$""", RegexOption.IGNORE_CASE)
    private val TRAILING_LIVE_EVENTS = Regex("""\s+live events\s*$""", RegexOption.IGNORE_CASE)

    /**
     * A provider/catalog display label, e.g. "⚡SportzX Live Events (tv)" -> "SportzX": leading emoji,
     * dots and whitespace stripped, then a trailing " (tv)" and " Live Events" suffix removed. Null when
     * nothing usable is left (the caller falls back to the addon name); names are never invented.
     */
    fun cleanCatalog(name: String): String? =
        name.trim()
            .replace(LEADING_JUNK, "")
            .replace(TRAILING_TV, "")
            .replace(TRAILING_LIVE_EVENTS, "")
            .trim()
            .ifBlank { null }

    /**
     * The label for each of [item]'s sources (the primary and its alternates), keyed by
     * [SportsLiveItem.streamKey]. A source whose addon contributes more than one of the card's sources
     * is labeled by [cleanCatalog] (falling back to the addon name when it cleans to nothing); a source
     * that is its addon's only one keeps the addon name.
     */
    fun of(item: SportsLiveItem): Map<String, String> {
        val sources = listOf(item) + item.alternates
        val perAddon = sources.groupingBy { it.addonId }.eachCount()
        return sources.associate { source ->
            val label = if ((perAddon[source.addonId] ?: 0) > 1) {
                cleanCatalog(source.catalogName) ?: source.addonName
            } else {
                source.addonName
            }
            source.streamKey to label
        }
    }
}
