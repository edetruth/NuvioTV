package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.DMSansFamily
import com.nuvio.tv.ui.theme.InterFamily
import com.nuvio.tv.ui.theme.OpenSansFamily

/** A Results side is bold when it won: only while its result shows (a hidden score never tells who won). */
internal fun sportsLiveWinnerBold(showScore: Boolean, mine: Int?, theirs: Int?): Boolean =
    showScore && mine != null && theirs != null && mine > theirs

/** The same rule for a side whose win the feed states (tennis, UFC). */
internal fun sportsLiveWinnerBold(showScore: Boolean, won: Boolean): Boolean = showScore && won

/**
 * Bold that shows. The app's fonts are variable fonts declared without a weight axis setting, so
 * `FontWeight.Bold` alone draws them at their default weight; the winner's bold sets the axis.
 * Another font family keeps plain `FontWeight.Bold`.
 */
internal fun TextStyle.sportsLiveBold(): TextStyle =
    copy(fontWeight = FontWeight.Bold, fontFamily = BOLD_FAMILIES[fontFamily] ?: fontFamily)

@OptIn(ExperimentalTextApi::class)
private fun weightedBold(resId: Int): FontFamily = FontFamily(
    Font(resId, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(FontWeight.Bold.weight)))
)

private val BOLD_FAMILIES: Map<FontFamily, FontFamily> by lazy {
    mapOf(
        InterFamily to weightedBold(R.font.inter_variable),
        DMSansFamily to weightedBold(R.font.dm_sans_variable),
        OpenSansFamily to weightedBold(R.font.opensans_variable)
    )
}
