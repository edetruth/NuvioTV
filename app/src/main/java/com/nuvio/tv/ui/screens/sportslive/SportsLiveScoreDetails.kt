package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.scores.ScoreDetails
import com.nuvio.tv.core.sportslive.scores.ScoreDetailsBuilder
import com.nuvio.tv.core.sportslive.scores.ScoreDetailsSide
import com.nuvio.tv.core.sportslive.scores.ScoreDetailsStrings
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun rememberScoreDetailsStrings(): ScoreDetailsStrings {
    val scoreline = stringResource(R.string.sports_live_details_scoreline)
    val sides = stringResource(R.string.sports_live_details_sides)
    val ot = stringResource(R.string.sports_live_details_period_ot)
    val otN = stringResource(R.string.sports_live_details_period_ot_n)
    val et = stringResource(R.string.sports_live_details_period_et)
    val scorer = stringResource(R.string.sports_live_details_scorer)
    val pen = stringResource(R.string.sports_live_details_scorer_pen)
    val og = stringResource(R.string.sports_live_details_scorer_og)
    val separator = stringResource(R.string.sports_live_details_separator)
    return remember(scoreline, sides, ot, otN, et, scorer, pen, og, separator) {
        ScoreDetailsStrings(scoreline, sides, ot, otN, et, scorer, pen, og, separator)
    }
}

/**
 * The details pane's score block for a matched game: the scoreline ("ARS 2–1 CHE") with the clock or
 * state, a linescore row, and soccer's goal scorers. Hide until focused shows "Score hidden" until the
 * card's reveal; postponed games show their status only.
 */
@Composable
internal fun SportsLiveScoreDetails(details: ScoreDetails, modifier: Modifier = Modifier) {
    val strings = rememberScoreDetailsStrings()
    val chipStrings = rememberScoreChipStrings()
    val tabular = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
    when (details) {
        is ScoreDetails.Hidden -> Text(
            text = chipStrings.hidden,
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextSecondary,
            modifier = modifier
        )
        is ScoreDetails.Status -> Text(
            text = details.text ?: chipStrings.postponed,
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextSecondary,
            modifier = modifier
        )
        is ScoreDetails.Shown -> Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                Text(
                    text = ScoreDetailsBuilder.scorelineText(details, strings),
                    style = tabular,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = details.status ?: chipStrings.finalNote,
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    color = if (details.live) NuvioTheme.colors.Error else NuvioTheme.colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (details.periods.isNotEmpty()) LinescoreTable(details, strings)
            if (details.soccer) {
                listOf(details.first, details.second).forEach { side ->
                    ScoreDetailsBuilder.scorersText(side, strings)?.let { scorers ->
                        Text(
                            text = stringResource(R.string.sports_live_details_side_scorers, side.short, scorers),
                            style = MaterialTheme.typography.bodySmall,
                            color = NuvioTheme.colors.TextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LinescoreTable(details: ScoreDetails.Shown, strings: ScoreDetailsStrings) {
    val cell = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum")
    val heads = details.periods.map { ScoreDetailsBuilder.periodText(it, strings) }
    val total = stringResource(R.string.sports_live_details_period_total)
    @Composable
    fun line(label: String, cells: List<String>, last: String, header: Boolean) {
        Row {
            Text(
                text = label,
                style = cell.copy(fontWeight = FontWeight.Bold),
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(56.dp)
            )
            (cells + last).forEachIndexed { index, text ->
                Text(
                    text = text,
                    style = if (index == cells.size) cell.copy(fontWeight = FontWeight.Bold) else cell,
                    color = if (header) NuvioTheme.colors.TextTertiary else NuvioTheme.colors.TextPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.width(32.dp)
                )
            }
        }
    }
    fun cells(side: ScoreDetailsSide) = side.linescores.map { it?.toString().orEmpty() }
    Column {
        line("", heads, total, header = true)
        line(details.first.short, cells(details.first), details.first.score?.toString().orEmpty(), header = false)
        line(details.second.short, cells(details.second), details.second.score?.toString().orEmpty(), header = false)
    }
}
