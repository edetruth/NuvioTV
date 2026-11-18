package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.SportsLiveDisplayText
import com.nuvio.tv.core.sportslive.displayName
import com.nuvio.tv.core.sportslive.scores.GameSituationStrings
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveBadge
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveFocus
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Size

/** The situation line's words from string resources. */
@Composable
internal fun rememberGameSituationStrings(): GameSituationStrings {
    val separator = stringResource(R.string.sports_live_situation_separator)
    val possession = stringResource(R.string.sports_live_situation_possession)
    val redZone = stringResource(R.string.sports_live_situation_red_zone)
    val inning = stringResource(R.string.sports_live_situation_inning)
    val outs = stringResource(R.string.sports_live_situation_outs)
    val runnerOn = stringResource(R.string.sports_live_situation_runner_on)
    val twoRunners = stringResource(R.string.sports_live_situation_two_runners)
    val loaded = stringResource(R.string.sports_live_situation_bases_loaded)
    val first = stringResource(R.string.sports_live_situation_first)
    val second = stringResource(R.string.sports_live_situation_second)
    val third = stringResource(R.string.sports_live_situation_third)
    return remember(separator, possession, redZone, inning, outs, runnerOn, twoRunners, loaded, first, second, third) {
        GameSituationStrings(separator, possession, redZone, inning, outs, runnerOn, twoRunners, loaded, first, second, third)
    }
}

/** A group's heading (V2): 24 px caps, 0.08 em apart; LIVE in red with its count, the others 60 % white. */
@Composable
internal fun SportsLiveScoreboardHeader(group: ScoreboardGroup, count: Int, modifier: Modifier = Modifier) {
    val text = when (group) {
        ScoreboardGroup.LIVE -> stringResource(R.string.sports_live_group_live, count)
        ScoreboardGroup.STARTING_SOON -> stringResource(R.string.sports_live_group_starting_soon)
        ScoreboardGroup.LATER -> stringResource(R.string.sports_live_group_later)
        ScoreboardGroup.CHANNELS -> stringResource(R.string.sports_live_group_channels, count)
        ScoreboardGroup.JUST_ENDED -> stringResource(R.string.sports_live_group_just_ended)
    }
    Text(
        text = text,
        style = SportsLiveTokens.style(SportsLiveTokens.Type.SectionHeader),
        color = if (group == ScoreboardGroup.LIVE) Colors.LiveRed else Colors.TextPrimary.copy(alpha = 0.6f),
        maxLines = 1,
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

/** A row's height by its group (board px ÷ 2): live 56, starting soon / later 48, channel 36, just ended 40. */
internal fun scoreboardRowHeight(group: ScoreboardGroup): Dp = when (group) {
    ScoreboardGroup.LIVE -> Size.RowLive
    ScoreboardGroup.STARTING_SOON, ScoreboardGroup.LATER -> Size.RowSoon
    ScoreboardGroup.CHANNELS -> Size.RowChannel
    ScoreboardGroup.JUST_ENDED -> Size.RowJustEnded
}

/**
 * A Scoreboard row (V2 Live Now, #132), the same in the list and in the player's panel (F12): fixed
 * columns (league tag 48 dp, the two teams, stacked scores 48 dp, status 75 dp), so nothing shifts row to
 * row; a channel puts its logo in the league column and its clean name, quality and "Now: …" in the names
 * column. A live row draws the elapsed share of regulation as a 2 dp red bar along its bottom and, with
 * scores shown, its excitement badge under the period. A followed team's game gets a gold left bar;
 * [selected] (focus in the stage) a white one. Just ended rows are dimmed until focused. Hold OK opens
 * the More… menu ([onLongClick]).
 */
@Composable
internal fun SportsLiveScoreboardRow(
    row: ScoreboardRow,
    selected: Boolean,
    onClick: () -> Unit,
    /** Hold OK / Menu; null leaves long presses alone (OK held clicks on release). */
    onLongClick: (() -> Unit)?,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
    menuOpen: Boolean = false,
    /** An upcoming game with a reminder (#44): a blue bell in the scores column. */
    reminded: Boolean = false,
    /** A Just ended game with a strict replay match: the Replay pill. */
    hasReplay: Boolean = false,
    /** The game playing (the in-player panel): a white left bar, as selected. */
    nowPlaying: Boolean = false,
    /** Team names start from the short name (the panel's narrower rows). */
    shortNames: Boolean = false
) {
    val shape = SportsLiveTokens.Radius.RowShape
    var focused by remember { mutableStateOf(false) }
    val (longPress, click) = if (onLongClick != null) rememberSportsLiveLongPress(menuOpen, onLongClick, onClick) else Modifier to onClick
    val base = if (row.group == ScoreboardGroup.LIVE) Colors.LiveRow else Colors.Row
    Surface(
        onClick = click,
        modifier = modifier
            .fillMaxWidth()
            .height(scoreboardRowHeight(row.group))
            .then(if (row.dimmed(focused)) Modifier.alpha(JUST_ENDED_ALPHA) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .then(longPress),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = SportsLiveTokens.Focus.LIFT),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = base,
            contentColor = Colors.TextPrimary,
            focusedContainerColor = SportsLiveFocus.focusedFill(base),
            focusedContentColor = Colors.TextPrimary,
            pressedContainerColor = SportsLiveFocus.focusedFill(base),
            pressedContentColor = Colors.TextPrimary
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = SportsLiveFocus.ring(shape), pressedBorder = SportsLiveFocus.ring(shape))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (row.group == ScoreboardGroup.CHANNELS) ChannelCells(row) else GameCells(row, reminded, hasReplay, shortNames)
            // The left bar: white while selected (focus in the stage) or playing, else gold for a followed team.
            val bar = when {
                (selected || nowPlaying) && !focused -> Colors.FocusWhite
                row.followed -> Colors.FollowedGold
                else -> null
            }
            if (bar != null) {
                Box(modifier = Modifier.align(Alignment.CenterStart).width(BAR_WIDTH).fillMaxHeight().background(bar))
            }
            row.progress?.let { share ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(share)
                        .height(BAR_WIDTH)
                        .background(Colors.LiveRed)
                )
            }
        }
    }
}

/** A game's cells: league tag, the two teams, stacked scores and the status column. */
@Composable
private fun GameCells(row: ScoreboardRow, reminded: Boolean, hasReplay: Boolean, shortNames: Boolean) {
    val item = row.item
    val live = row.group == ScoreboardGroup.LIVE
    val scores = scoreboardScoresOf(row.chip)
    // Just ended rows are 40 dp: their names sit a little closer.
    val compact = row.group == ScoreboardGroup.JUST_ENDED
    Row(
        modifier = Modifier.fillMaxSize().padding(start = 4.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LeagueTag(scoreboardRowTag(row.art?.league, item.sport, item.catalogName), live)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            val art = row.art
            if (art != null) {
                // Both lines step down together (full name, short name, abbreviation), so the two teams read alike.
                val firstChoices = remember(art.first) { scoreboardNameChoices(art.first) }
                val secondChoices = remember(art.second) { scoreboardNameChoices(art.second) }
                var level by remember(firstChoices, secondChoices, shortNames) { mutableIntStateOf(if (shortNames) 1 else 0) }
                val maxLevel = maxOf(firstChoices.lastIndex, secondChoices.lastIndex)
                val tooLong: () -> Unit = { if (level < maxLevel) level++ }
                val logos = rememberTeamLogoPair(art)?.takeIf { !it.failed }
                val leader = (scores as? ScoreboardScores.Shown)?.leader
                TeamLine(firstChoices, level, logos?.first, logos?.shown == true, row.firstFollowed, leader == 0, compact, tooLong)
                TeamLine(secondChoices, level, logos?.second, logos?.shown == true, row.secondFollowed, leader == 1, compact, tooLong)
            } else {
                // An unmatched title with no two sides: its clean name, in the same column.
                NameText(item.displayName, bold = false, compact = compact, maxLines = 2)
            }
        }
        ScoresCell(scores, reminded)
        StatusCell(row, hasReplay)
    }
}

/** A channel's cells: its small logo in the league column, then its clean name, quality and "Now: …". */
@Composable
private fun ChannelCells(row: ScoreboardRow) {
    val item = row.item
    val title = SportsLiveDisplayText.of(item.meta.name)
    Row(
        modifier = Modifier.fillMaxSize().padding(start = 4.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = Size.ColumnLeague, height = CHANNEL_LOGO_HEIGHT)
                .clip(RoundedCornerShape(SportsLiveTokens.Radius.Badge))
                .background(Colors.SurfaceRaised),
            contentAlignment = Alignment.Center
        ) {
            val logo = item.meta.logo ?: item.meta.poster
            if (logo != null) {
                AsyncImage(
                    model = rememberSizedImage(logo, Size.ColumnLeague, CHANNEL_LOGO_HEIGHT),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(2.dp)
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title.name,
                    style = SportsLiveTokens.style(SportsLiveTokens.Type.RowName).copy(lineHeight = 18.sp),
                    color = Colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                title.quality?.let { SportsLiveBadge(it) }
                if (item.followed) StarMark()
            }
            channelNowText(item)?.let { now ->
                Text(
                    text = stringResource(R.string.sports_live_channel_now, now),
                    style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag).copy(fontWeight = FontWeight.Normal),
                    color = Colors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** The league column: the tag, right-aligned, shrinking to fit 48 dp rather than cut. */
@Composable
private fun LeagueTag(tag: String, live: Boolean) {
    Box(modifier = Modifier.width(Size.ColumnLeague), contentAlignment = Alignment.CenterEnd) {
        BasicText(
            text = tag,
            style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag).copy(
                color = if (live) Colors.LiveText else Colors.TextSecondary,
                textAlign = TextAlign.End
            ),
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = SportsLiveTokens.Type.Tag.fontSize, stepSize = 0.5.sp)
        )
    }
}

/** One team: its logo slot (kept empty without logos, #130), the name (semibold when leading), ★ after it when followed. */
@Composable
private fun TeamLine(
    choices: List<String>,
    level: Int,
    logo: Painter?,
    logoShown: Boolean,
    followed: Boolean,
    leading: Boolean,
    compact: Boolean,
    onTooLong: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val logoSize = if (compact) 14.dp else ROW_LOGO_SIZE
        Box(modifier = Modifier.size(logoSize), contentAlignment = Alignment.Center) {
            if (logo != null) TeamLogoSlot(logo, logoShown, logoSize)
        }
        val index = level.coerceAtMost(choices.lastIndex)
        Row(modifier = Modifier.weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = choices[index],
                style = nameStyle(leading, compact),
                color = Colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
                onTextLayout = { layout ->
                    if (index < choices.lastIndex && (layout.hasVisualOverflow || layout.isLineEllipsized(0))) onTooLong()
                }
            )
            if (followed) StarMark()
        }
    }
}

@Composable
private fun NameText(text: String, bold: Boolean, compact: Boolean, maxLines: Int) {
    Text(
        text = text,
        style = nameStyle(bold, compact),
        color = Colors.TextPrimary,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun nameStyle(bold: Boolean, compact: Boolean): TextStyle =
    SportsLiveTokens.style(SportsLiveTokens.Type.RowName).copy(
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        lineHeight = if (compact) 17.sp else 20.sp
    )

@Composable
private fun StarMark() {
    Icon(imageVector = Icons.Rounded.Star, contentDescription = null, tint = Colors.FollowedGold, modifier = Modifier.size(12.dp))
}

/** The scores column (48 dp): stacked, tabular, right-aligned; the hidden mark under Hide until focused; a bell when reminded. */
@Composable
private fun ScoresCell(scores: ScoreboardScores, reminded: Boolean) {
    Column(modifier = Modifier.width(Size.ColumnScores), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
        when (scores) {
            is ScoreboardScores.Shown -> {
                val style = SportsLiveTokens.style(SportsLiveTokens.Type.RowScore)
                Text(text = scores.first.toString(), style = style, color = if (scores.leader == 1) Colors.TextMuted else Colors.TextPrimary, maxLines = 1)
                Text(text = scores.second.toString(), style = style, color = if (scores.leader == 0) Colors.TextMuted else Colors.TextPrimary, maxLines = 1)
            }
            ScoreboardScores.Hidden -> Icon(
                imageVector = Icons.Rounded.VisibilityOff,
                contentDescription = stringResource(R.string.sports_live_results_score_hidden),
                tint = Colors.TextMuted,
                modifier = Modifier.size(16.dp)
            )
            ScoreboardScores.None -> if (reminded) {
                Icon(
                    imageVector = Icons.Rounded.Notifications,
                    contentDescription = stringResource(R.string.sports_live_reminder_on),
                    tint = Colors.WhyTagBlue,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/** The status column (75 dp): the red dot and clock over the period, or the kickoff over its countdown; then the badge. */
@Composable
private fun StatusCell(row: ScoreboardRow, hasReplay: Boolean) {
    val strings = rememberGameStatusStrings()
    val kickoff = row.art?.let { kickoffText(it) }
    val lines = scoreboardStatusLines(row, strings, kickoff)
    Column(modifier = Modifier.width(Size.ColumnStatus), verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically)) {
        lines.primary?.let { primary ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (lines.live) Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Colors.LiveRed))
                Text(
                    text = primary,
                    style = SportsLiveTokens.style(STATUS_STYLE),
                    color = Colors.TextPrimary,
                    maxLines = if (lines.secondary == null && !lines.live) 2 else 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        lines.secondary?.let { secondary ->
            val style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag).copy(fontWeight = FontWeight.Medium)
            if (lines.urgent) {
                Text(
                    text = secondary,
                    style = style,
                    color = Colors.LiveText,
                    maxLines = 1,
                    modifier = Modifier
                        .border(1.dp, Colors.LiveRed, SportsLiveTokens.Radius.PillShape)
                        .padding(horizontal = 5.dp)
                )
            } else {
                Text(text = secondary, style = style, color = Colors.TextPrimary.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        row.excitement?.let { tag -> ExcitementBadge(whyTagText(tag)) }
        if (hasReplay && row.group == ScoreboardGroup.JUST_ENDED) ReplayPill()
    }
}

/** The excitement badge (#43, F2): small and blue, so it never reads as live (red) or followed (gold). */
@Composable
private fun ExcitementBadge(text: String) {
    Text(
        text = text,
        style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag).copy(fontSize = 10.sp, lineHeight = 12.sp),
        color = Colors.WhyTagText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(SportsLiveTokens.Radius.Badge))
            .background(Colors.WhyTagBackground)
            .border(1.dp, Colors.WhyTagBorder, RoundedCornerShape(SportsLiveTokens.Radius.Badge))
            .padding(horizontal = 4.dp)
    )
}

/** What a channel shows now, when known: the game in its title's brackets ("[Celtics vs. 76ers]"). */
@Composable
internal fun channelNowText(item: com.nuvio.tv.domain.model.SportsLiveItem): String? {
    val teams = item.teams ?: return null
    return stringResource(R.string.sports_live_matchup, teams.home, teams.away)
}

/**
 * A row's league tag (QA pass 3): [name] in capitals while it is short ("NBA", "BASKETBALL"); a longer one
 * by its words' initials ("Brazil Serie A" → "BSA"), never an ellipsis that leaves "BRAZIL …".
 */
internal fun scoreboardTag(name: String): String {
    // Addon decoration goes first (#100): emoji and symbols, and bracketed parts ("⚡ Live Events (X)").
    val upper = scoreboardTagText(name).uppercase()
    if (upper.length <= TAG_MAX_CHARS) return upper
    val words = upper.split(' ', '-', '/').filter { it.isNotBlank() }
    if (words.size > 1) return words.take(TAG_MAX_WORDS).joinToString("") { if (it.all(Char::isDigit)) it else it.take(1) }
    return upper.take(TAG_MAX_CHARS)
}

/**
 * The league or sport a status line or the drawn art names (#132): [league] (ESPN's, else the card's
 * sport) cleaned, or null when it is only the item's catalog name, which says nothing about the game.
 */
internal fun sportsLiveLeagueLabel(league: String?, catalogName: String): String? =
    league?.takeIf { it.trim() != catalogName.trim() }?.let(SportsLiveDisplayText::label)

/** [name] without bracketed parts, emoji or symbols ([SportsLiveDisplayText.label]). */
private fun scoreboardTagText(name: String): String = SportsLiveDisplayText.label(name).orEmpty()

/**
 * A row's tag from what is known of its game: ESPN's [league], else the card's [sport], else its
 * [catalogName] only when that is short enough to read whole (a long catalog name's initials say nothing).
 */
internal fun scoreboardRowTag(league: String?, sport: String?, catalogName: String): String {
    // A sport that is only the catalog's name (the addon gave none) reads as the catalog's.
    val catalog = catalogName.trim()
    league?.takeIf { it.isNotBlank() && it.trim() != catalog }?.let { return scoreboardTag(it) }
    sport?.takeIf { it.trim() != catalog }?.let(::scoreboardTag)?.takeIf { it.isNotEmpty() }?.let { return it }
    return scoreboardTagText(catalogName).uppercase().takeIf { it.length <= TAG_MAX_CHARS }.orEmpty()
}


private const val TAG_MAX_CHARS = 10
private const val TAG_MAX_WORDS = 4

private val ROW_LOGO_SIZE = 18.dp
private val CHANNEL_LOGO_HEIGHT = 27.dp
private val COLUMN_GAP = 6.dp
private val BAR_WIDTH = 2.dp

/** The status column's first line: the clock or the kickoff, 28 px semibold, tabular. */
private val STATUS_STYLE = TextStyle(fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")

/** A Just ended row is dimmed until focused (#60). */
private const val JUST_ENDED_ALPHA = 0.6f
