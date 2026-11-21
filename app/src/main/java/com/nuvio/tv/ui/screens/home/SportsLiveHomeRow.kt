package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.sportslive.SportsLiveDisplayText
import com.nuvio.tv.core.sportslive.displayName
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.ScoreChipStrings
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.core.sportslive.SportsLiveSportKind
import com.nuvio.tv.ui.screens.sportslive.liveNowItemTag

/** The synthetic Home row that shows live Sports & Live items, marked by sentinel ids. */
object SportsLiveHomeRow {
    const val ADDON_ID = "nuvio.sportslive"
    const val BASE_URL = "nuvio://sports-live"
    const val CATALOG_ID = "live_now"

    /** Home's words for the hero (V2 phase 10): "Live now", "★ Your team", "2 streams". */
    data class Words(
        val liveNow: String,
        val yourTeam: String,
        val streams: (Int) -> String,
        /** A sport's word ("Soccer"), for the tag when no league is known (Live Now's rule). */
        val sportWord: (SportsLiveSportKind) -> String = { it.name.lowercase().replaceFirstChar(Char::uppercase) }
    )

    /**
     * [scoreLabels] (item streamKey → [SportsLiveHomeMeta.statusLine]) replace a card's label line (its release
     * info), so a live game shows its clock and score under its title and in the hero.
     *
     * With [words] (V2 phase 10) a game's card carries no addon image at all (Home draws the section's game card in
     * its place, [rememberLiveNowCardArt]: two sides, or the neutral card for any other game, live.19 F3 / #182, so no
     * addon art that may carry a score or only a channel's logo), and the hero reads "NBA · Live now" (the genres
     * line) and "★ Your team · 2 streams" (the description) instead of the addon's type, date and blurb. Titles are
     * cleaned of pipes and stray spaces ([SportsLiveHomeMeta.cleanTitle]).
     */
    fun build(
        items: List<SportsLiveItem>,
        title: String,
        scoreLabels: Map<String, String> = emptyMap(),
        words: Words? = null,
        /** Each card's tag read with its score ([tags]); without one the hero's eyebrow is read from the card alone. */
        tags: Map<String, String> = emptyMap()
    ): CatalogRow = CatalogRow(
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
            val clean = item.meta.copy(
                name = SportsLiveHomeMeta.cleanTitle(item.displayName),
                description = SportsLiveDisplayText.description(item.meta.description)
            )
            val labelled = scoreLabels[item.streamKey]?.let { clean.copy(releaseInfo = it) } ?: clean
            val drawn = words != null && SportsLiveHomeMeta.drawsCard(item)
            val meta = if (words == null) {
                labelled
            } else {
                labelled.copy(
                    // The hero's status line is the score label only: never the addon's date ("Nov 19, 20:30").
                    releaseInfo = scoreLabels[item.streamKey],
                    genres = listOf(SportsLiveHomeMeta.eyebrow(
                        // Live Now's own tag for the row (RC TV finding): its league ("EPL"), else the sport word; never a
                        // catalog's or addon's name, never a catch-all ("Other").
                        (tags[item.streamKey] ?: liveNowItemTag(item, null, words.sportWord)).ifEmpty { null },
                        words.liveNow
                    )),
                    description = SportsLiveHomeMeta.extraLine(item.followed, item.sourceCount, words.yourTeam, words.streams),
                    // A game is drawn (logos, clock, score): never the addon's art with LIVE or a score baked in.
                    poster = if (drawn) null else labelled.poster,
                    background = if (drawn) null else labelled.background,
                    landscapePoster = if (drawn) null else labelled.landscapePoster,
                    logo = if (drawn) null else labelled.logo
                )
            }
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
     * The Live now cards' score labels, by item streamKey (two addons may share an item id): the status line
     * ("● 4:12 · Q3 · IND 80–88 PHI"), with [liveFormat] ("● %1$s") marking a live one. Only under Show: Home
     * has no focus reveal, so Hide until focused (and Off, or no strict match) shows nothing there.
     */
    fun scoreLabels(
        items: List<SportsLiveItem>,
        scores: Map<String, ScoreSnapshot>,
        mode: ScoresMode?,
        strings: ScoreChipStrings,
        liveFormat: String
    ): Map<String, String> = if (mode != ScoresMode.SHOW) emptyMap() else items.mapNotNull { item ->
        val snapshot = scores[item.streamKey]
        val chip = ScoreChips.of(item, snapshot, mode, revealed = false) ?: return@mapNotNull null
        // "● 4:12 · Q3 · IND 80–88 PHI" (V2 phase 10): the hero's status line, the abbreviations from the matched game.
        item.streamKey to SportsLiveHomeMeta.statusLine(chip, GameArt.of(item, snapshot), strings, liveFormat)
    }.toMap()

    /**
     * The Live now cards' tags by item streamKey (live.24): the game identity's, read with each game's [scores] as the
     * card itself reads it ([rememberLiveNowCardArt]), so the hero's eyebrow says what the focused card says ("NCAAM",
     * not "BASKETBALL").
     */
    fun tags(items: List<SportsLiveItem>, scores: Map<String, ScoreSnapshot>, sportWord: (SportsLiveSportKind) -> String): Map<String, String> =
        items.associate { item -> item.streamKey to liveNowItemTag(item, scores[item.streamKey], sportWord) }

    fun isLiveNowRow(addonBaseUrl: String): Boolean = addonBaseUrl == BASE_URL

    /** The Live now row always ends with See all, which opens the section, whatever its size. */
    fun alwaysShowsSeeAll(row: CatalogRow): Boolean = isLiveNowRow(row.addonBaseUrl)
}
