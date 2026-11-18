package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.sportslive.SportsLiveDisplayText
import com.nuvio.tv.core.sportslive.displayName
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.core.sportslive.scores.ScoreChipStrings
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.SportsLiveItem

/** The synthetic Home row that shows live Sports & Live items, marked by sentinel ids. */
object SportsLiveHomeRow {
    const val ADDON_ID = "nuvio.sportslive"
    const val BASE_URL = "nuvio://sports-live"
    const val CATALOG_ID = "live_now"

    /**
     * [scoreLabels] (item streamKey → score chip text) replace a card's label line (its release info), so a
     * live game shows its clock and score under its title.
     */
    fun build(items: List<SportsLiveItem>, title: String, scoreLabels: Map<String, String> = emptyMap()): CatalogRow = CatalogRow(
        addonId = ADDON_ID,
        addonName = "Sports & Live",
        addonBaseUrl = BASE_URL,
        catalogId = CATALOG_ID,
        catalogName = title,
        type = ContentType.UNKNOWN,
        rawType = "sport",
        items = items.map { item ->
            // Clean addon text (#100): the card and hero show the display name and no taglines; Details
            // loads the addon's own meta.
            val clean = item.meta.copy(name = item.displayName, description = SportsLiveDisplayText.description(item.meta.description))
            val meta = scoreLabels[item.streamKey]?.let { clean.copy(releaseInfo = it) } ?: clean
            // The card's own art as its landscape poster: a channel's art carries its logo, so the focused
            // card draws no title over it.
            val art = meta.backdropUrl
            if (meta.landscapePoster.isNullOrBlank() && !art.isNullOrBlank()) meta.copy(landscapePoster = art) else meta
        },
        isLoading = false,
        hasMore = false,
        supportsSkip = false
    )

    /**
     * The Live now cards' score labels, by item streamKey (two addons may share an item id): the chip
     * text ("● Q3 4:12 · 88–80"), with [liveFormat] ("● %1$s") marking a live one. Only under Show: Home
     * has no focus reveal, so Hide until focused (and Off, or no strict match) shows nothing there.
     */
    fun scoreLabels(
        items: List<SportsLiveItem>,
        scores: Map<String, ScoreSnapshot>,
        mode: ScoresMode?,
        strings: ScoreChipStrings,
        liveFormat: String
    ): Map<String, String> = if (mode != ScoresMode.SHOW) emptyMap() else items.mapNotNull { item ->
        val chip = ScoreChips.of(item, scores[item.streamKey], mode, revealed = false) ?: return@mapNotNull null
        val text = ScoreChips.text(chip, strings)
        val live = chip is ScoreChip.Live
        item.streamKey to if (live) String.format(liveFormat, text) else text
    }.toMap()

    fun isLiveNowRow(addonBaseUrl: String): Boolean = addonBaseUrl == BASE_URL

    /** The Live now row always ends with See all, which opens the section, whatever its size. */
    fun alwaysShowsSeeAll(row: CatalogRow): Boolean = isLiveNowRow(row.addonBaseUrl)
}
