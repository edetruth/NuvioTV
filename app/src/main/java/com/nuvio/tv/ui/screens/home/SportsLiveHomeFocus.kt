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
