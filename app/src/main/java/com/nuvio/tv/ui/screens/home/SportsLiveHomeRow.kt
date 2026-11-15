package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.SportsLiveItem

/** The synthetic Home row that shows live Sports & Live items, marked by sentinel ids. */
object SportsLiveHomeRow {
    const val ADDON_ID = "nuvio.sportslive"
    const val BASE_URL = "nuvio://sports-live"
    const val CATALOG_ID = "live_now"

    fun build(items: List<SportsLiveItem>, title: String): CatalogRow = CatalogRow(
        addonId = ADDON_ID,
        addonName = "Sports & Live",
        addonBaseUrl = BASE_URL,
        catalogId = CATALOG_ID,
        catalogName = title,
        type = ContentType.UNKNOWN,
        rawType = "sport",
        items = items.map { it.meta },
        isLoading = false,
        hasMore = false,
        supportsSkip = false
    )

    fun isLiveNowRow(addonBaseUrl: String): Boolean = addonBaseUrl == BASE_URL

    /** The Live now row always ends with See all, which opens the section, whatever its size. */
    fun alwaysShowsSeeAll(row: CatalogRow): Boolean = isLiveNowRow(row.addonBaseUrl)
}
