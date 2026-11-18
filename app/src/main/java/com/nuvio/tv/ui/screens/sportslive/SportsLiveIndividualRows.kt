package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.tv.material3.Border
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.scores.Fight
import com.nuvio.tv.core.sportslive.scores.FightMethod
import com.nuvio.tv.core.sportslive.scores.GolfLeader
import com.nuvio.tv.core.sportslive.scores.GolfResult
import com.nuvio.tv.core.sportslive.scores.IndividualFormat
import com.nuvio.tv.core.sportslive.scores.IndividualResult
import com.nuvio.tv.core.sportslive.scores.RaceResult
import com.nuvio.tv.core.sportslive.scores.ResultRow
import com.nuvio.tv.core.sportslive.scores.TennisEnding
import com.nuvio.tv.core.sportslive.scores.TennisMatch
import com.nuvio.tv.core.sportslive.scores.TennisPlayer
import com.nuvio.tv.ui.components.LightweightCard
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * An individual sport's Results row (#55 part 2), as the design's individual-sport row: tennis players
 * with set columns, a UFC fight and how it ended, an F1 podium, a golf winner (OK lists the top five).
 * [showScore] false (Hide until focused, before the 1 s reveal) hides every part of the result: the
 * winner's bold, sets, method, podium and leaderboard. No Replay pill and no Follow menu. A golf row
 * OK lists scrolls its new lines into view.
 */
@Composable
internal fun IndividualResultCard(
    row: ResultRow,
    result: IndividualResult,
    showScore: Boolean,
    /** Golf: the top five listed (OK toggles it). */
    expanded: Boolean,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    val bringIntoView = remember { BringIntoViewRequester() }
    // Only on OK: a row composed already listed (scrolled back to) stays where it is.
    var wasExpanded by remember { mutableStateOf(expanded) }
    LaunchedEffect(expanded) {
        val opened = expanded && !wasExpanded
        wasExpanded = expanded
        if (!opened) return@LaunchedEffect
        withFrameNanos { }
        runCatching { bringIntoView.bringIntoView() }
    }
    LightweightCard(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoView)
            .onFocusChanged { if (it.isFocused) onFocus() },
        shape = shape,
        containerColor = NuvioTheme.colors.BackgroundCard,
        focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
        ) {
            val tag = result.title ?: row.event.leagueName
            if (tag != null) {
                Text(
                    text = tag,
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            when (result) {
                is TennisMatch -> TennisLines(result, showScore)
                is Fight -> FightLines(result, showScore)
                is RaceResult -> ResultLine(if (showScore) IndividualFormat.podium(result) else null)
                is GolfResult -> GolfLines(result, showScore, expanded)
            }
        }
    }
}

/** Two player lines (winner bold), each with its set columns; "Ret." / "W/O" after the sets. */
@Composable
private fun TennisLines(match: TennisMatch, showScore: Boolean) {
    val sets = match.setCount
    match.players.forEachIndexed { index, player ->
        val opponent = match.players.getOrNull(1 - index)
        Row(
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = player.name,
                style = MaterialTheme.typography.titleMedium.let { if (sportsLiveWinnerBold(showScore, player.winner)) it.sportsLiveBold() else it },
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (!showScore) {
                if (index == 0) HiddenText(R.string.sports_live_results_score_hidden)
                return@Row
            }
            for (set in 0 until sets) {
                Text(
                    text = tennisSetCell(player, opponent, set),
                    style = MaterialTheme.typography.titleMedium.let {
                        if (opponent != null && tennisWonSet(match, player, opponent, set)) it.copy(fontWeight = FontWeight.Bold) else it
                    },
                    color = NuvioTheme.colors.TextPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.width(36.dp)
                )
            }
            val ending = when (match.ending) {
                TennisEnding.RETIRED -> stringResource(R.string.sports_live_results_tennis_retired)
                TennisEnding.WALKOVER -> stringResource(R.string.sports_live_results_tennis_walkover)
                TennisEnding.COMPLETED -> null
            }
            // On the line of the player who retired or gave the walkover.
            Text(
                text = if (ending != null && !player.winner && match.winner != null) ending else "",
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                modifier = Modifier.width(40.dp)
            )
        }
    }
}

/** A set's games, with the tiebreak points of the player who lost it small; a match tiebreak shows its points. */
private fun tennisSetCell(player: TennisPlayer, opponent: TennisPlayer?, set: Int) = buildAnnotatedString {
    val mine = player.sets[set]
    val theirs = opponent?.sets?.getOrNull(set)
    if (theirs != null && IndividualFormat.isMatchTiebreak(mine, theirs)) {
        append(mine.tiebreak.toString())
        return@buildAnnotatedString
    }
    append(mine.games.toString())
    if (mine.tiebreak != null && theirs != null && mine.games < theirs.games) {
        withStyle(SpanStyle(fontSize = 0.6.em, baselineShift = BaselineShift.Superscript)) { append(mine.tiebreak.toString()) }
    }
}

/**
 * True when [player] won [set] (its column is bold). After a retirement the last set may be unfinished
 * ("3–0 ret."): it is nobody's unless its score ends a set (6–4, 7–5, 7–6, a match tiebreak).
 */
internal fun tennisWonSet(match: TennisMatch, player: TennisPlayer, opponent: TennisPlayer, set: Int): Boolean {
    val mine = player.sets[set]
    val theirs = opponent.sets.getOrNull(set) ?: return false
    if (IndividualFormat.isMatchTiebreak(mine, theirs)) return (mine.tiebreak ?: 0) > (theirs.tiebreak ?: 0)
    val unfinished = match.ending == TennisEnding.RETIRED && set == match.setCount - 1 && !tennisSetOver(mine.games, theirs.games)
    return !unfinished && mine.games > theirs.games
}

/** A set's games that end it: six or more by two clear, or 7–5 / 7–6. */
private fun tennisSetOver(a: Int, b: Int): Boolean {
    val high = maxOf(a, b)
    val low = minOf(a, b)
    return (high >= 6 && high - low >= 2) || (high == 7 && low >= 5)
}

/** "Ciryl Gane vs Josh Hokit" (winner bold), then "Ciryl Gane · Decision" / "… · KO/TKO R2 3:14". */
@Composable
private fun FightLines(fight: Fight, showScore: Boolean) {
    val vs = stringResource(R.string.sports_live_results_fight_vs)
    Text(
        text = buildAnnotatedString {
            fight.fighters.forEachIndexed { index, fighter ->
                if (index > 0) append(" $vs ")
                if (showScore && fighter.winner) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(fighter.name) } else append(fighter.name)
            }
        },
        style = MaterialTheme.typography.titleMedium,
        color = NuvioTheme.colors.TextPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
    if (!showScore) {
        HiddenText(R.string.sports_live_results_result_hidden)
        return
    }
    val methods = mapOf(
        FightMethod.KO_TKO to stringResource(R.string.sports_live_results_fight_ko),
        FightMethod.SUBMISSION to stringResource(R.string.sports_live_results_fight_submission),
        FightMethod.DECISION to stringResource(R.string.sports_live_results_fight_decision)
    )
    val roundText = fight.round?.let { stringResource(R.string.sports_live_results_fight_round, it, fight.clock.orEmpty()).trim() }
    val line = fightResultLine(fight, methods::getValue) { _, _ -> roundText.orEmpty() }
    ResultLine(line ?: stringResource(R.string.sports_live_results_fight_no_result), secondary = true)
}

/**
 * The fight's result line: "‹winner› · ‹method›", with "R2 3:14" for a stoppage; null with no winner
 * (ESPN names none: a draw, a no contest or not given), never a guess.
 */
internal fun fightResultLine(fight: Fight, method: (FightMethod) -> String, round: (Int, String) -> String): String? {
    val winner = fight.winner ?: return null
    val how = fight.method?.let(method)
    val where = if (fight.showsRound) round(fight.round!!, fight.clock.orEmpty()) else null
    val detail = listOfNotNull(how, where).joinToString(" ").ifEmpty { null }
    return listOfNotNull(winner.name, detail).joinToString(" · ")
}

/** The winner and score to par, "+ 4 more" until OK lists the rest of the top five, then "OK to collapse". */
@Composable
private fun GolfLines(golf: GolfResult, showScore: Boolean, expanded: Boolean) {
    if (!showScore) {
        HiddenText(R.string.sports_live_results_result_hidden)
        return
    }
    val shown = if (expanded) golf.leaders else golf.leaders.take(1)
    shown.forEach { GolfLine(it, bold = it.position == 1 && !it.tied) }
    val more = golf.leaders.size - shown.size
    val hint = when {
        more > 0 -> pluralStringResource(R.plurals.sports_live_results_golf_more, more, more)
        expanded && golf.leaders.size > 1 -> stringResource(R.string.sports_live_results_golf_collapse)
        else -> null
    }
    if (hint != null) {
        Text(
            text = hint,
            style = MaterialTheme.typography.labelMedium,
            color = NuvioTheme.colors.TextTertiary,
            maxLines = 1
        )
    }
}

@Composable
private fun GolfLine(leader: GolfLeader, bold: Boolean) {
    val style = MaterialTheme.typography.titleMedium.let { if (bold) it.copy(fontWeight = FontWeight.Bold) else it }
    Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Text(text = IndividualFormat.position(leader), style = style, color = NuvioTheme.colors.TextSecondary, modifier = Modifier.width(40.dp))
        Text(
            text = leader.name,
            style = style,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(text = IndividualFormat.toPar(leader), style = style, color = NuvioTheme.colors.TextPrimary, maxLines = 1)
    }
}

/** A result line, or "Result hidden" when [text] is null. */
@Composable
private fun ResultLine(text: String?, secondary: Boolean = false) {
    if (text == null) {
        HiddenText(R.string.sports_live_results_result_hidden)
        return
    }
    Text(
        text = text,
        style = if (secondary) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
        color = if (secondary) NuvioTheme.colors.TextSecondary else NuvioTheme.colors.TextPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun HiddenText(res: Int) {
    Text(
        text = stringResource(res),
        style = MaterialTheme.typography.bodySmall,
        color = NuvioTheme.colors.TextTertiary,
        maxLines = 1
    )
}
