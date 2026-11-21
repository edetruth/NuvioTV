package com.nuvio.tv.ui.screens.home

/**
 * #211: Home's Sports & Live rows (Catch up, Live now) head the catalog rows, right under Continue Watching,
 * but they arrive after the first catalogs (the Sports & Live load is slower). On a fresh start Home has
 * already focused its first catalog row when they land above it, and Modern Home shows the focused row under
 * the hero with nothing above it: the rows stayed out of sight and Down never reached them. While the user
 * has not moved from the row Home picked by itself (still on its first card), the first Sports & Live row now
 * above it takes the focus, as if it had been there from the start. A row the user chose, or a focus restored
 * on return, is never moved; a held place still loading (the Catch up placeholder) is never focused.
 *
 * Returns the row to focus instead of [activeKey]'s, or null to keep it.
 */
internal fun sportsLiveRowTakingAutoFocus(
    rows: List<HeroCarouselRow>,
    activeKey: String?,
    autoSelectedKey: String?,
    activeItemIndex: Int
): HeroCarouselRow? {
    if (activeKey == null || activeKey != autoSelectedKey || activeItemIndex != 0) return null
    val activeIndex = rows.indexOfFirst { it.key == activeKey }
    if (activeIndex <= 0) return null
    return rows.subList(0, activeIndex).firstOrNull { row ->
        row.addonId == SportsLiveHomeRow.ADDON_ID && !row.isLoading && row.items.list.isNotEmpty()
    }
}

/**
 * #214: the row Home focuses by itself when it opens. The Catch up row's held place (still loading) heads Home on a
 * fresh start; focused, it left Home with nothing focused once it ended empty, Home's own pick was lost, and the Live
 * now row landing a few seconds later went in above the row the focus fell to, out of sight ("Popular - Series first").
 * Home now starts on the first row that is not a Sports & Live held place, so a sports row landing above it takes the
 * focus ([sportsLiveRowTakingAutoFocus]); with only held places, the first of them.
 */
internal fun homeFirstFocusRow(rows: List<HeroCarouselRow>): HeroCarouselRow? =
    rows.firstOrNull { !(it.addonId == SportsLiveHomeRow.ADDON_ID && it.isLoading) } ?: rows.firstOrNull()
