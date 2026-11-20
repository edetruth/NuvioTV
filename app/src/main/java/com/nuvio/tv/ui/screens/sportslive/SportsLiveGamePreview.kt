package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.scores.BigGameText
import com.nuvio.tv.core.sportslive.scores.GamePreview
import com.nuvio.tv.core.sportslive.scores.GameSummary
import com.nuvio.tv.core.sportslive.scores.PreviewBlock
import com.nuvio.tv.core.sportslive.scores.PreviewHeaderSide
import com.nuvio.tv.core.sportslive.scores.RankDisplay
import com.nuvio.tv.core.sportslive.scores.ScoreSide
import com.nuvio.tv.core.sportslive.scores.TeamForm
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Preview (#171): an upcoming game on one calm screen, no drill-down. On the left the header line (records,
 * places, college ranks), each side's form as W / L chips (the opponent under each) and the head-to-head; on the
 * right season leaders, injuries and the win chance as a small split bar. Only the blocks with data are drawn,
 * each a focus stop so ↓ reaches them all. Sides read in the card's order ([homeFirst]).
 */
internal fun LazyListScope.previewTab(summary: GameSummary, first: Modifier, homeFirst: Boolean) {
    val preview = summary.preview ?: return
    val blocks = preview.blocks
    val leftBlocks = blocks.filter { it == PreviewBlock.HEADER || it == PreviewBlock.FORM || it == PreviewBlock.HEAD_TO_HEAD }
    val rightBlocks = blocks.filter { it == PreviewBlock.LEADERS || it == PreviewBlock.INJURIES || it == PreviewBlock.WIN_CHANCE }
    val firstBlock = (leftBlocks + rightBlocks).firstOrNull()
    fun focus(block: PreviewBlock): Modifier = if (block == firstBlock) first else Modifier
    item(key = "preview") {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            if (leftBlocks.isNotEmpty()) {
                Column(modifier = Modifier.weight(0.5f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    leftBlocks.forEach { block ->
                        PreviewCard(focus(block)) {
                            when (block) {
                                PreviewBlock.HEADER -> PreviewHeader(summary, preview, homeFirst)
                                PreviewBlock.FORM -> PreviewForm(preview.form, homeFirst)
                                PreviewBlock.HEAD_TO_HEAD -> PreviewHeadToHead(preview)
                                else -> Unit
                            }
                        }
                    }
                }
            }
            if (rightBlocks.isNotEmpty()) {
                Column(modifier = Modifier.weight(0.5f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    rightBlocks.forEach { block ->
                        PreviewCard(focus(block)) {
                            when (block) {
                                PreviewBlock.LEADERS -> PreviewLeaders(summary, preview, homeFirst)
                                PreviewBlock.INJURIES -> PreviewInjuries(summary, preview, homeFirst)
                                PreviewBlock.WIN_CHANCE -> PreviewWinChance(summary, preview, homeFirst)
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewCard(modifier: Modifier, content: @Composable () -> Unit) {
    SportsLivePageSurface(modifier = Modifier.fillMaxWidth().then(modifier).wholeInViewOnFocus(), container = Colors.Surface, lift = false) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    }
}

/** "GS 8–7 · 10th in West" / "#3 UConn 15–2 · 1st in Big East": each side on its line. */
@Composable
private fun PreviewHeader(summary: GameSummary, preview: GamePreview, homeFirst: Boolean) {
    val (top, bottom) = inCardOrder(summary.away.short to preview.away, summary.home.short to preview.home, homeFirst)
    SportsLivePageHeading(stringResource(R.string.sports_live_preview_header))
    listOf(top, bottom).forEach { (team, side) -> HeaderLine(team, side, summary) }
}

@Composable
private fun HeaderLine(team: String, side: PreviewHeaderSide, summary: GameSummary) {
    val rank = RankDisplay.rank(summary.league, side.rank)
    val place = side.place?.let { place ->
        val group = side.group?.let(::previewGroupName)
        if (group != null) stringResource(R.string.sports_live_preview_place_in, BigGameText.englishOrdinal(place), group) else BigGameText.englishOrdinal(place)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        rank?.let { Text(RankDisplay.label(it), style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta), color = Colors.TextMuted, maxLines = 1) }
        Text(team, style = SportsLiveTokens.style(SportsLiveTokens.Type.RowName).sportsLiveBold(), color = Colors.TextPrimary, maxLines = 1)
        val rest = listOfNotNull(side.record?.let(::sportsLiveRecordText), place).joinToString(" · ")
        if (rest.isNotEmpty()) {
            Text(rest, style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta).copy(fontFeatureSettings = "tnum"), color = Colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "2026-27 Atlantic Division Standings" → "Atlantic Division". */
internal fun previewGroupName(group: String): String =
    group.trim().removeSuffix("Standings").trim().replace(SEASON_PREFIX, "").trim().ifEmpty { group.trim() }

private val SEASON_PREFIX = Regex("""^\d{4}(-\d{2,4})?\s+""")

@Composable
private fun PreviewForm(forms: List<TeamForm>, homeFirst: Boolean) {
    SportsLivePageHeading(stringResource(R.string.sports_live_preview_form))
    val ordered = forms.sortedBy { form -> if ((form.side == ScoreSide.HOME) == homeFirst) 0 else 1 }
    ordered.forEach { form ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Text(
                form.team,
                style = SportsLiveTokens.style(SportsLiveTokens.Type.RowName).sportsLiveBold(),
                color = Colors.TextPrimary,
                maxLines = 1,
                modifier = Modifier.width(56.dp).padding(top = 2.dp)
            )
            form.games.forEach { game ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Box(
                        modifier = Modifier
                            .size(width = 26.dp, height = 22.dp)
                            .clip(RoundedCornerShape(SportsLiveTokens.Radius.Badge))
                            .background(resultColor(game.result)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(game.result, style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag), color = Colors.TextPrimary, maxLines = 1)
                    }
                    Text(
                        text = game.opponent.orEmpty(),
                        style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag).copy(fontSize = 10.sp, lineHeight = 12.sp),
                        color = Colors.TextMuted,
                        maxLines = 1,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/** A win green, a loss red, a draw or tie grey: quiet fills, the letter says it too. */
@Composable
private fun resultColor(result: String): Color = when (result) {
    "W" -> Colors.ReplayGreen.copy(alpha = 0.35f)
    "L" -> Colors.LiveRed.copy(alpha = 0.35f)
    else -> Colors.SurfaceRaised
}

@Composable
private fun PreviewHeadToHead(preview: GamePreview) {
    val h2h = preview.headToHead ?: return
    SportsLivePageHeading(stringResource(R.string.sports_live_preview_head_to_head))
    Text(h2h.summary, style = SportsLiveTokens.style(SportsLiveTokens.Type.RowName), color = Colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    val locale = Locale.getDefault()
    h2h.meetings.forEach { meeting ->
        val date = meeting.dateMs?.let { DateTimeFormatter.ofPattern("d MMM yyyy", locale).format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) }
        val line = stringResource(R.string.sports_live_preview_meeting, meeting.away, meeting.awayScore.orEmpty(), meeting.homeScore.orEmpty(), meeting.home)
        Text(
            listOfNotNull(date, line).joinToString(" · "),
            style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta).copy(fontFeatureSettings = "tnum"),
            color = Colors.TextSecondary,
            maxLines = 1
        )
    }
}

@Composable
private fun PreviewLeaders(summary: GameSummary, preview: GamePreview, homeFirst: Boolean) {
    SportsLivePageHeading(stringResource(R.string.sports_live_preview_leaders))
    sidesInOrder(homeFirst).forEach { side ->
        val leaders = preview.leaders.filter { it.side == side }
        if (leaders.isEmpty()) return@forEach
        Text(teamOf(summary, side), style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta).sportsLiveBold(), color = Colors.TextSecondary, maxLines = 1)
        leaders.forEach { leader ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(leader.category, style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta), color = Colors.TextMuted, maxLines = 1, modifier = Modifier.width(84.dp))
                Text(leader.player, style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta), color = Colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(leader.value, style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta).copy(fontFeatureSettings = "tnum").sportsLiveBold(), color = Colors.TextPrimary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun PreviewInjuries(summary: GameSummary, preview: GamePreview, homeFirst: Boolean) {
    SportsLivePageHeading(stringResource(R.string.sports_live_preview_injuries))
    sidesInOrder(homeFirst).forEach { side ->
        val injuries = preview.injuries.filter { it.side == side }
        if (injuries.isEmpty()) return@forEach
        val line = injuries.joinToString(", ") { injury ->
            listOfNotNull(injury.player, injury.status.takeUnless { it.equals("Out", ignoreCase = true) }?.let { "($it)" }).joinToString(" ")
        }
        val out = injuries.all { it.status.equals("Out", ignoreCase = true) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(teamOf(summary, side), style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta).sportsLiveBold(), color = Colors.TextSecondary, maxLines = 1, modifier = Modifier.width(56.dp))
            Text(
                text = if (out) stringResource(R.string.sports_live_preview_out, line) else line,
                style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
                color = Colors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun PreviewWinChance(summary: GameSummary, preview: GamePreview, homeFirst: Boolean) {
    val chance = preview.winChance ?: return
    SportsLivePageHeading(stringResource(R.string.sports_live_preview_win_chance))
    val (left, right) = inCardOrder(summary.away.short to chance.away, summary.home.short to chance.home, homeFirst)
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.sports_live_preview_percent, left.first, left.second), style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta).copy(fontFeatureSettings = "tnum"), color = Colors.TextPrimary, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.sports_live_preview_percent, right.first, right.second), style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta).copy(fontFeatureSettings = "tnum"), color = Colors.TextPrimary, maxLines = 1)
    }
    val (leftWeight, rightWeight) = statBarWeights(left.second, right.second)
    Row(modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))) {
        if (leftWeight > 0f) Box(Modifier.weight(leftWeight).height(6.dp).background(Colors.TextSecondary))
        Spacer(Modifier.width(2.dp))
        if (rightWeight > 0f) Box(Modifier.weight(rightWeight).height(6.dp).background(Colors.Divider))
    }
}

private fun sidesInOrder(homeFirst: Boolean): List<ScoreSide> = if (homeFirst) listOf(ScoreSide.HOME, ScoreSide.AWAY) else listOf(ScoreSide.AWAY, ScoreSide.HOME)

private fun teamOf(summary: GameSummary, side: ScoreSide): String = if (side == ScoreSide.HOME) summary.home.short else summary.away.short
