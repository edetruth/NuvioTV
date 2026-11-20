package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.scores.TennisColumns
import com.nuvio.tv.core.sportslive.scores.RankDisplay
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import com.nuvio.tv.core.sportslive.SportsLiveLeagueTags
import com.nuvio.tv.core.sportslive.SportsLiveSportKind
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import java.util.Locale
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

/** A list section's heading (P4: a Channels region): "US · 3", in the group heading's style. */
@Composable
internal fun SportsLiveSectionHeader(label: String, count: Int, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.sports_live_section_count, label.uppercase(Locale.getDefault()), count),
        style = SportsLiveTokens.style(SportsLiveTokens.Type.SectionHeader),
        color = Colors.TextPrimary.copy(alpha = 0.6f),
        maxLines = 1,
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

/**
 * A row's height by its group (board px ÷ 2): live 56, starting soon / later 48, channel 36, just ended 40;
 * a single-line row (not a two-sided match, V2 P3) 40.
 */
internal fun scoreboardRowHeight(group: ScoreboardGroup, twoSided: Boolean = true): Dp = when {
    group == ScoreboardGroup.CHANNELS -> Size.RowChannel
    !twoSided -> SINGLE_LINE_ROW_HEIGHT
    group == ScoreboardGroup.LIVE -> Size.RowLive
    group == ScoreboardGroup.JUST_ENDED -> Size.RowJustEnded
    else -> Size.RowSoon
}

/**
 * The right side of an Agenda row (V2 Upcoming, and Replays in P4): an optional [trailing] word (a replay's
 * time), then the reminder bell. No "Stream listed" dot (P4): every listed game comes from an addon.
 */
@Immutable
internal data class AgendaRowSide(
    val bell: AgendaBell,
    val trailing: String? = null,
    /** The subtle "Big game" badge (#137): a big game in its time slot (not in Games to watch, which says it already). */
    val bigGame: Boolean = false
)

/**
 * A Scoreboard row (V2 Live Now, #132), the same in the list and in the player's panel (F12): fixed
 * columns (league tag 80 dp on every row type, #134, the two teams, stacked scores 48 dp, status 75 dp), so nothing shifts row to
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
    shortNames: Boolean = false,
    /** An Agenda row (Upcoming): its right side shows the listing and the bell, never scores or a status. */
    agenda: AgendaRowSide? = null,
    /** The mini player's game (#157): a small "▶ Watching" marker at its top-right. */
    watching: Boolean = false,
    /** "▶ Ready" (live.20 1b): my game holds a verified link; the pill beside its status, never "No stream yet". */
    ready: Boolean = false
) {
    val shape = SportsLiveTokens.Radius.RowShape
    var focused by remember { mutableStateOf(false) }
    val (longPress, click) = if (onLongClick != null) rememberSportsLiveLongPress(menuOpen, onLongClick, onClick) else Modifier to onClick
    val base = if (row.group == ScoreboardGroup.LIVE) Colors.LiveRow else Colors.Row
    Surface(
        onClick = click,
        modifier = modifier
            .fillMaxWidth()
            .height(if (agenda != null && row.twoSided) Size.RowSoon else scoreboardRowHeight(row.group, row.twoSided))
            .then(if (row.dimmed(focused)) Modifier.alpha(JUST_ENDED_ALPHA) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .then(longPress),
        shape = ClickableSurfaceDefaults.shape(shape),
        // While its More… sheet is open (#134) the row reads as selected (the white bar), not focused (ring and lift).
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (menuOpen) 1f else SportsLiveTokens.Focus.LIFT),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = base,
            contentColor = Colors.TextPrimary,
            focusedContainerColor = if (menuOpen) base else SportsLiveFocus.focusedFill(base),
            focusedContentColor = Colors.TextPrimary,
            pressedContainerColor = SportsLiveFocus.focusedFill(base),
            pressedContentColor = Colors.TextPrimary
        ),
        border = if (menuOpen) {
            ClickableSurfaceDefaults.border()
        } else {
            ClickableSurfaceDefaults.border(focusedBorder = SportsLiveFocus.ring(shape), pressedBorder = SportsLiveFocus.ring(shape))
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                row.group == ScoreboardGroup.CHANNELS -> ChannelCells(row)
                agenda != null -> AgendaCells(row, agenda, shortNames, ready)
                !row.twoSided -> SingleLineCells(row, hasReplay, ready)
                else -> GameCells(row, reminded, hasReplay, shortNames, ready)
            }
            // The left bar: white while selected (focus in the stage) or playing, else gold for a followed team.
            val bar = when {
                (selected || nowPlaying) && !focused -> Colors.FocusWhite
                menuOpen -> Colors.FocusWhite
                row.followed -> Colors.FollowedGold
                else -> null
            }
            if (bar != null) {
                Box(modifier = Modifier.align(Alignment.CenterStart).width(BAR_WIDTH).fillMaxHeight().background(bar))
            }
            if (watching) {
                Text(
                    text = stringResource(R.string.sports_live_row_watching),
                    style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag).copy(fontSize = 10.sp, lineHeight = 12.sp),
                    color = Colors.OnPrimary,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 3.dp, end = 4.dp)
                        .clip(RoundedCornerShape(SportsLiveTokens.Radius.Badge))
                        .background(Colors.PrimaryFill)
                        .padding(horizontal = 4.dp)
                )
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
private fun GameCells(row: ScoreboardRow, reminded: Boolean, hasReplay: Boolean, shortNames: Boolean, ready: Boolean) {
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
        LeagueTag(boardTagText(row), live)
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
                val leader = row.tennis?.leader ?: (scores as? ScoreboardScores.Shown)?.leader
                val ranks = art.takeIf { it.ranksOnRows }
                TeamLine(firstChoices, level, logos?.first, logos?.shown == true, row.firstFollowed, leader == 0, compact, tooLong, ranks?.first?.rank)
                TeamLine(secondChoices, level, logos?.second, logos?.shown == true, row.secondFollowed, leader == 1, compact, tooLong, ranks?.second?.rank)
            } else {
                // An unmatched title with no two sides: its clean name, in the same column.
                NameText(item.displayName, bold = false, compact = compact, maxLines = 2)
            }
        }
        // A tennis match (#160): its set columns in place of the stacked scores.
        row.tennis?.let { TennisSetsCell(it, live, compact) } ?: ScoresCell(scores, reminded, compact)
        StatusCell(row, hasReplay, ready = ready)
    }
}

/** The row's tag: ESPN's league, the card's sport, else the sport its text tells ("TENNIS", "CRICKET"). */
@Composable
/** The board's tag in the one shared vocabulary (live.20: same short tags as the panel, Home and dual). */
@Composable
internal fun boardTagText(row: ScoreboardRow): String =
    com.nuvio.tv.core.sportslive.SportsLiveTags.short(rowTagText(row), row.sportTag ?: SportsLiveSportKind.plainTagOf(row.item))

internal fun rowTagText(row: ScoreboardRow): String =
    rowTagOf(row, row.sportTag?.let { sportKindLabel(it) }, SportsLiveSportKind.plainTagOf(row.item)?.let { sportKindLabel(it) })

/**
 * [rowTagText] with the sport its text tells already worded ([sportLabel]): ESPN's league, the card's sport, else
 * [sportLabel] in capitals; empty when none is known. The same for a two-team row as for a single-line one (#167).
 */
internal fun rowTagOf(row: ScoreboardRow, sportLabel: String?, plainLabel: String? = null): String {
    // A tennis match's tour (#160): "ATP", never its tournament's initials.
    row.leagueTag?.let { return it }
    val item = row.item
    // One word per sport (F4): an addon's plain sport word ("Football", "Soccer") as the sport's one name, unless ESPN named the league.
    plainLabel?.takeIf { row.art?.league.let { league -> league == null || league == item.sport } }?.let { return it.uppercase(Locale.getDefault()) }
    // A non-sport tag ("OTHER", #145) says nothing about the game: the sport its title names, else none.
    val tag = scoreboardRowTag(
        row.art?.league,
        item.sport?.takeUnless(SportsLiveTeams::isNonSportTag),
        item.catalogName.takeUnless(SportsLiveTeams::isNonSportTag).orEmpty()
    )
    if (tag.isNotEmpty()) return tag
    return sportLabel?.uppercase(Locale.getDefault()).orEmpty()
}

/**
 * A single-line row (V2 P3): not a two-sided match ("ATP 250 - Stockholm Open", "Tennis"): the sport tag, the
 * clean title on one line, and the status (the red dot and LIVE, or the kickoff).
 */
@Composable
private fun SingleLineCells(row: ScoreboardRow, hasReplay: Boolean, ready: Boolean) {
    Row(
        modifier = Modifier.fillMaxSize().padding(start = 4.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LeagueTag(boardTagText(row), row.group == ScoreboardGroup.LIVE)
        // The logo slot holds the event's mark (live.20 item 4: a tournament feed's trophy, else its sport), so the
        // title starts where team names do and the slot is never empty.
        SportsLiveEventMarkIcon(row.sportTag, Modifier.size(ROW_LOGO_SIZE))
        Text(
            text = singleLineTitle(row.item),
            style = nameStyle(bold = false, compact = false),
            color = Colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        // One line (#134): a live event with no clock says "● LIVE" at the tag's size, not the clock's.
        StatusCell(row, hasReplay, singleLine = true, ready = ready)
    }
}

/**
 * An Agenda row's cells (V2 Upcoming, Replays): the league tag, the two teams (or the one-line title), then the
 * trailing word and the bell: outline when off, filled blue when set. Never a score or a live mark.
 */
@Composable
private fun AgendaCells(row: ScoreboardRow, side: AgendaRowSide, shortNames: Boolean, ready: Boolean) {
    val item = row.item
    Row(
        modifier = Modifier.fillMaxSize().padding(start = 4.dp, end = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // No score column here: the tag gets the room to read at its full size, as on every other row.
        LeagueTag(boardTagText(row), live = false)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            val art = row.art
            if (art != null && row.twoSided) {
                val firstChoices = remember(art.first) { scoreboardNameChoices(art.first) }
                val secondChoices = remember(art.second) { scoreboardNameChoices(art.second) }
                var level by remember(firstChoices, secondChoices, shortNames) { mutableIntStateOf(if (shortNames) 1 else 0) }
                val maxLevel = maxOf(firstChoices.lastIndex, secondChoices.lastIndex)
                val tooLong: () -> Unit = { if (level < maxLevel) level++ }
                val logos = rememberTeamLogoPair(art)?.takeIf { !it.failed }
                val ranks = art.takeIf { it.ranksOnRows }
                TeamLine(firstChoices, level, logos?.first, logos?.shown == true, row.firstFollowed, false, false, tooLong, ranks?.first?.rank)
                TeamLine(secondChoices, level, logos?.second, logos?.shown == true, row.secondFollowed, false, false, tooLong, ranks?.second?.rank)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    // The event's mark in the logo slot (live.20 item 4), never an empty space.
                    SportsLiveEventMarkIcon(row.sportTag, Modifier.size(ROW_LOGO_SIZE))
                    NameText(singleLineTitle(item), bold = false, compact = false, maxLines = 1)
                }
            }
        }
        if (ready) SportsLiveReadyPill()
        if (side.bigGame) BigGameBadge()
        side.trailing?.let { trailing ->
            Text(
                text = trailing,
                style = SportsLiveTokens.style(STATUS_STYLE),
                color = Colors.TextSecondary,
                maxLines = 1
            )
        }
        if (side.bell != AgendaBell.NONE) AgendaBellIcon(side.bell, Modifier.size(16.dp))
    }
}

/** The reminder bell (#44): outline when off, filled blue when set; nothing when no reminder can be set. */
@Composable
internal fun AgendaBellIcon(bell: AgendaBell, modifier: Modifier = Modifier, offTint: Color = Colors.TextMuted) {
    when (bell) {
        AgendaBell.NONE -> Spacer(modifier = modifier)
        AgendaBell.OFF -> Icon(
            imageVector = Icons.Outlined.Notifications,
            contentDescription = stringResource(R.string.sports_live_remind_me),
            tint = offTint,
            modifier = modifier
        )
        AgendaBell.ON -> Icon(
            imageVector = Icons.Rounded.Notifications,
            contentDescription = stringResource(R.string.sports_live_reminder_on),
            tint = Colors.WhyTagBlue,
            modifier = modifier
        )
    }
}

/** A sport's short name ("Football", "Soccer", "Tennis"). */
@Composable
internal fun sportKindLabel(kind: SportsLiveSportKind): String = stringResource(
    when (kind) {
        SportsLiveSportKind.FOOTBALL -> R.string.sports_live_sport_football
        SportsLiveSportKind.SOCCER -> R.string.sports_live_sport_soccer
        SportsLiveSportKind.BASKETBALL -> R.string.sports_live_sport_basketball
        SportsLiveSportKind.HOCKEY -> R.string.sports_live_sport_hockey
        SportsLiveSportKind.BASEBALL -> R.string.sports_live_sport_baseball
        SportsLiveSportKind.TENNIS -> R.string.sports_live_sport_tennis
        SportsLiveSportKind.CRICKET -> R.string.sports_live_sport_cricket
        SportsLiveSportKind.RUGBY -> R.string.sports_live_sport_rugby
        SportsLiveSportKind.GOLF -> R.string.sports_live_sport_golf
        SportsLiveSportKind.MMA -> R.string.sports_live_sport_mma
        SportsLiveSportKind.BOXING -> R.string.sports_live_sport_boxing
        SportsLiveSportKind.MOTORSPORT -> R.string.sports_live_sport_motorsport
        SportsLiveSportKind.DARTS -> R.string.sports_live_sport_darts
        SportsLiveSportKind.SNOOKER -> R.string.sports_live_sport_snooker
        SportsLiveSportKind.WRESTLING -> R.string.sports_live_sport_wrestling
        SportsLiveSportKind.CYCLING -> R.string.sports_live_sport_cycling
    }
)

/** A sport filter option's label: the short name of a [SportsLiveSportKind] value, else the addon's text cleaned. */
@Composable
internal fun sportFilterLabel(value: String): String =
    SportsLiveSportKind.ofFilterKey(value)?.let { sportKindLabel(it) } ?: (SportsLiveDisplayText.label(value) ?: value)

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

/**
 * The league column (#134): the tag, right-aligned, at the Tag size on every row type. The column (80 dp) fits a
 * ten-letter sport word ("BASKETBALL"), so a tag is never shrunk; anything longer is already initials ([scoreboardTag]).
 */
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
            overflow = TextOverflow.Ellipsis
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
    onTooLong: () -> Unit,
    /** A ranked college team (#137): a small muted "#3" before the name. */
    rank: Int? = null
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val logoSize = if (compact) 14.dp else ROW_LOGO_SIZE
        Box(modifier = Modifier.size(logoSize), contentAlignment = Alignment.Center) {
            if (logo != null) TeamLogoSlot(logo, logoShown, logoSize)
        }
        val index = level.coerceAtMost(choices.lastIndex)
        Row(modifier = Modifier.weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            rank?.let {
                Text(
                    text = RankDisplay.label(it),
                    style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag).copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
                    color = Colors.TextMuted,
                    maxLines = 1
                )
            }
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
        fontWeight = FontWeight.Normal,
        lineHeight = if (compact) 17.sp else 20.sp
    ).let { if (bold) it.sportsLiveBold() else it }

@Composable
private fun StarMark() {
    Icon(imageVector = Icons.Rounded.Star, contentDescription = null, tint = Colors.FollowedGold, modifier = Modifier.size(12.dp))
}

/** The scores column (48 dp): stacked, tabular, right-aligned; the hidden mark under Hide until focused; a bell when reminded. */
@Composable
private fun ScoresCell(scores: ScoreboardScores, reminded: Boolean, compact: Boolean = false) {
    Column(modifier = Modifier.width(Size.ColumnScores), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
        when (scores) {
            is ScoreboardScores.Shown -> {
                // Real bold (the app's variable fonts ignore FontWeight alone).
                // A Just ended row is 40 dp: its two scores step down so both fit (P4), never clipped.
                val style = SportsLiveTokens.style(SportsLiveTokens.Type.RowScore).sportsLiveBold()
                    .let { if (compact) it.copy(fontSize = scoreboardScoreSize(compact), lineHeight = JUST_ENDED_SCORE_LINE) else it }
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
private fun StatusCell(row: ScoreboardRow, hasReplay: Boolean, singleLine: Boolean = false, ready: Boolean = false) {
    val strings = rememberGameStatusStrings()
    val kickoff = row.art?.let { kickoffText(it) }
    val lines = scoreboardStatusLines(row, strings, kickoff)
    // A one-line live event with no clock: the red dot and LIVE at the tag's size (#134).
    val liveMark = singleLine && lines.live && lines.secondary == null && lines.primary == strings.live
    Column(modifier = Modifier.width(Size.ColumnStatus), verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically)) {
        lines.primary?.let { primary ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (lines.live) Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Colors.LiveRed))
                Text(
                    text = primary,
                    style = if (liveMark) SportsLiveTokens.style(SportsLiveTokens.Type.Tag) else SportsLiveTokens.style(STATUS_STYLE),
                    color = if (liveMark) Colors.LiveText else Colors.TextPrimary,
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
        // One badge fits (live.20): "▶ Ready" on my game, else the excitement badge (Scores shown), else "Close game" in
        // crunch time (#137, under Hide too: it tells no score).
        val excitement = row.excitement
        when (rowBadgeOf(ready, excitement != null, row.closeGame)) {
            RowBadge.READY -> SportsLiveReadyPill()
            RowBadge.EXCITEMENT -> excitement?.let { ExcitementBadge(whyTagText(it)) }
            RowBadge.CLOSE_GAME -> ExcitementBadge(stringResource(R.string.sports_live_close_game))
            RowBadge.NONE -> Unit
        }
        if (hasReplay && row.group == ScoreboardGroup.JUST_ENDED) ReplayPill()
    }
}

/**
 * A tennis match's set columns (#160): "6 3 2" over "4 6 1", tabular, the set being played in white and bold
 * ([live]), the ones before muted; a small dot before the side serving.
 */
@Composable
private fun TennisSetsCell(columns: TennisColumns, live: Boolean, compact: Boolean) {
    val size = if (compact) 14.sp else 16.sp
    val style = SportsLiveTokens.style(SportsLiveTokens.Type.RowScore).copy(fontSize = size, lineHeight = if (compact) 17.sp else 20.sp)
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
        listOf(columns.first to (columns.servingFirst == true), columns.second to (columns.servingFirst == false)).forEach { (games, serving) ->
            Row(horizontalArrangement = Arrangement.spacedBy(TENNIS_SET_GAP), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(if (serving && live) Colors.FollowedGold else Color.Transparent))
                games.forEachIndexed { index, value ->
                    val current = live && index == games.lastIndex
                    Text(
                        text = value.toString(),
                        style = if (current) style.sportsLiveBold() else style.copy(fontWeight = FontWeight.Medium),
                        color = if (current) Colors.TextPrimary else Colors.TextSecondary,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        modifier = Modifier.width(TENNIS_SET_WIDTH)
                    )
                }
            }
        }
    }
}

/** The "Big game" badge (#137): small, outlined and muted, so it never competes with the names. */
@Composable
private fun BigGameBadge() {
    Text(
        text = stringResource(R.string.sports_live_big_game),
        style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag).copy(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium),
        color = Colors.TextSecondary,
        maxLines = 1,
        modifier = Modifier
            .border(1.dp, Colors.OutlineStrong, RoundedCornerShape(SportsLiveTokens.Radius.Badge))
            .padding(horizontal = 4.dp)
    )
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
    league?.takeIf { it.trim() != catalogName.trim() && !SportsLiveTeams.isNonSportTag(it) }?.let(SportsLiveDisplayText::label)

/** [name] without bracketed parts, emoji or symbols ([SportsLiveDisplayText.label]). */
private fun scoreboardTagText(name: String): String = SportsLiveDisplayText.label(name).orEmpty()

/**
 * A row's tag from what is known of its game: ESPN's [league], else the card's [sport]. Never the catalog's name
 * ("LIVE NOW", "Live Events") and never a word that names no sport ("Live", "Sports", #134): such a row takes
 * the sport its text tells ([rowTagText]), else no tag.
 */
internal fun scoreboardRowTag(league: String?, sport: String?, catalogName: String): String {
    // A sport that is only the catalog's name (the addon gave none) says nothing about the game.
    val catalog = catalogName.trim()
    // ESPN's raw abbreviation ("FU17W") gives way to a readable short tag ("U17 WC") when one is known.
    // A catch-all ("Other Sports") is no league either: it would read "OS".
    league?.takeIf { it.isNotBlank() && it.trim() != catalog }
        ?.takeUnless { raw -> SportsLiveTeams.isGenericSport(SportsLiveDisplayText.label(raw) ?: raw) }
        ?.let { return SportsLiveLeagueTags.of(it) ?: scoreboardTag(it) }
    return sport
        ?.takeIf { it.trim() != catalog }
        ?.takeUnless { raw -> SportsLiveTeams.isGenericSport(SportsLiveDisplayText.label(raw) ?: raw) }
        ?.let(::scoreboardTag)
        .orEmpty()
}


private const val TAG_MAX_CHARS = 10
private const val TAG_MAX_WORDS = 4

private val ROW_LOGO_SIZE = 18.dp

/** A tennis set column (#160): one or two digits, tabular. */
private val TENNIS_SET_WIDTH = 11.dp
private val TENNIS_SET_GAP = 3.dp

private val SINGLE_LINE_ROW_HEIGHT = 40.dp
private val CHANNEL_LOGO_HEIGHT = 27.dp
private val COLUMN_GAP = 6.dp
private val BAR_WIDTH = 2.dp

/** The status column's first line: the clock or the kickoff, 28 px semibold, tabular. */
private val STATUS_STYLE = TextStyle(fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")

/** The stacked scores' size: Just ended rows (40 dp) use a smaller one, so two lines fit (P4). */
internal fun scoreboardScoreSize(compact: Boolean) = if (compact) 16.sp else SportsLiveTokens.Type.RowScore.fontSize

/** Two Just ended score lines, 2 × 17 sp, fit its 40 dp row. */
internal val JUST_ENDED_SCORE_LINE = 17.sp

/** A Just ended row is dimmed until focused (#60). */
private const val JUST_ENDED_ALPHA = 0.6f

/** A Channels region's label (P4): its country code as is, a long group name cleaned, else [other] ("Other"). */
internal fun channelRegionLabel(value: String, other: String): String = when {
    value == com.nuvio.tv.core.sportslive.SportsLiveChannelRegions.OTHER -> other
    value.length <= 3 -> value
    else -> SportsLiveDisplayText.name(value)
}
