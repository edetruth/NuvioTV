package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.ui.util.StableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #211: the Sports & Live rows that land above the row Home focused by itself take the focus. */
class SportsLiveHomeFocusTest {

    private val liveNow = row("live", addonId = SportsLiveHomeRow.ADDON_ID)
    private val catchUp = row("catchup", addonId = SportsLiveHomeRow.ADDON_ID)
    private val movies = row("movies")
    private val series = row("series")

    @Test
    fun `live now landing above the first catalog row Home picked takes the focus`() {
        val rows = listOf(liveNow, movies, series)
        assertEquals(liveNow, sportsLiveRowTakingAutoFocus(rows, "movies", autoSelectedKey = "movies", activeItemIndex = 0))
    }

    @Test
    fun `catch up heading the sports rows takes it, even from live now taken that way before`() {
        assertEquals(catchUp, sportsLiveRowTakingAutoFocus(listOf(catchUp, liveNow, movies), "movies", "movies", 0))
        assertEquals(catchUp, sportsLiveRowTakingAutoFocus(listOf(catchUp, liveNow, movies), "live", "live", 0))
    }

    @Test
    fun `a row the user chose keeps the focus`() {
        // Moved to another row (Home clears its own pick then), or along the row Home picked.
        assertNull(sportsLiveRowTakingAutoFocus(listOf(liveNow, movies, series), "series", autoSelectedKey = null, activeItemIndex = 0))
        assertNull(sportsLiveRowTakingAutoFocus(listOf(liveNow, movies), "movies", "movies", activeItemIndex = 2))
    }

    @Test
    fun `a focus restored on return is never moved`() {
        assertNull(sportsLiveRowTakingAutoFocus(listOf(liveNow, movies), "movies", autoSelectedKey = null, activeItemIndex = 0))
    }

    @Test
    fun `sports rows below the focused row, as under Continue Watching, leave it alone`() {
        val cw = row(MODERN_CONTINUE_WATCHING_ROW_KEY)
        assertNull(sportsLiveRowTakingAutoFocus(listOf(cw, liveNow, movies), MODERN_CONTINUE_WATCHING_ROW_KEY, MODERN_CONTINUE_WATCHING_ROW_KEY, 0))
        assertNull(sportsLiveRowTakingAutoFocus(listOf(liveNow, movies), "live", "live", 0))
    }

    @Test
    fun `only a sports row with cards takes it, never a held place still loading or another late row`() {
        val held = row("catchup", addonId = SportsLiveHomeRow.ADDON_ID, isLoading = true)
        assertNull(sportsLiveRowTakingAutoFocus(listOf(held, movies), "movies", "movies", 0))
        assertEquals(liveNow, sportsLiveRowTakingAutoFocus(listOf(held, liveNow, movies), "movies", "movies", 0))
        assertNull(sportsLiveRowTakingAutoFocus(listOf(series, movies), "movies", "movies", 0))
        val empty = HeroCarouselRow(key = "live", title = "live", globalRowIndex = 0, items = StableList(emptyList()), addonId = SportsLiveHomeRow.ADDON_ID)
        assertNull(sportsLiveRowTakingAutoFocus(listOf(empty, movies), "movies", "movies", 0))
    }

    @Test
    fun `nothing moves while Home has no focus or its row is gone`() {
        assertNull(sportsLiveRowTakingAutoFocus(listOf(liveNow, movies), null, null, 0))
        assertNull(sportsLiveRowTakingAutoFocus(listOf(liveNow, movies), "gone", "gone", 0))
    }

    @Test
    fun `Home first focuses the first row that is not the Catch up held place`() {
        val held = row("catchup", addonId = SportsLiveHomeRow.ADDON_ID, isLoading = true)
        assertEquals(movies, homeFirstFocusRow(listOf(held, movies, series)))
        // Live now landing above that pick then takes the focus, and the held place ending empty takes nothing with it.
        assertEquals(liveNow, sportsLiveRowTakingAutoFocus(listOf(held, liveNow, movies), "movies", "movies", 0))
    }

    @Test
    fun `Home first focus keeps the first row otherwise`() {
        val cw = row(MODERN_CONTINUE_WATCHING_ROW_KEY)
        assertEquals(cw, homeFirstFocusRow(listOf(cw, liveNow, movies)))
        assertEquals(catchUp, homeFirstFocusRow(listOf(catchUp, movies)))
        val loadingCatalog = row("lazy", isLoading = true)
        assertEquals(loadingCatalog, homeFirstFocusRow(listOf(loadingCatalog, movies)))
        val held = row("catchup", addonId = SportsLiveHomeRow.ADDON_ID, isLoading = true)
        assertEquals(held, homeFirstFocusRow(listOf(held)))
        assertNull(homeFirstFocusRow(emptyList()))
    }

    private fun row(key: String, addonId: String? = "movies.addon", isLoading: Boolean = false): HeroCarouselRow =
        HeroCarouselRow(
            key = key,
            title = key,
            globalRowIndex = 0,
            items = StableList(listOf(item(key))),
            addonId = addonId,
            isLoading = isLoading
        )

    private fun item(key: String): ModernCarouselItem =
        ModernCarouselItem(
            key = key,
            title = key,
            subtitle = null,
            imageUrl = null,
            heroPreview = HeroPreview(
                title = key,
                logo = null,
                description = null,
                contentTypeText = null,
                yearText = null,
                imdbText = null,
                genres = StableList(emptyList()),
                poster = null,
                backdrop = null,
                imageUrl = null
            ),
            payload = ModernPayload.Catalog(
                focusKey = key,
                itemId = key,
                itemType = "movie",
                addonBaseUrl = "https://example.invalid",
                trailerTitle = key,
                trailerReleaseInfo = null,
                trailerApiType = "movie"
            )
        )
}
