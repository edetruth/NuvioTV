package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.scores.ChipScore
import com.nuvio.tv.core.sportslive.scores.GameArtTeam
import com.nuvio.tv.core.sportslive.scores.GameSituationStrings
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.core.sportslive.scores.ScoreDetails
import com.nuvio.tv.core.sportslive.scores.ScoreDetailsBuilder
import com.nuvio.tv.core.sportslive.scores.TeamLogoUrls
import com.nuvio.tv.ui.components.LightweightCard
import com.nuvio.tv.ui.theme.NuvioTheme

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

/** A group's heading above its first row: LIVE in red, the others muted. */
@Composable
internal fun SportsLiveScoreboardHeader(group: ScoreboardGroup, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(
            when (group) {
                ScoreboardGroup.LIVE -> R.string.sports_live_scoreboard_live
                ScoreboardGroup.STARTING_SOON -> R.string.sports_live_scoreboard_starting_soon
                ScoreboardGroup.MORE_GAMES -> R.string.sports_live_scoreboard_more_games
                ScoreboardGroup.FINAL -> R.string.sports_live_scoreboard_final
            }
        ),
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        color = if (group == ScoreboardGroup.LIVE) ArtColors.LiveRed else ArtColors.Muted,
        maxLines = 1,
        modifier = modifier.padding(top = NuvioTheme.spacing.md, bottom = NuvioTheme.spacing.xs)
    )
}

/**
 * A stadium-board row: a compact league tag, the two teams in the card's order (logo, ★ for a followed
 * side, the longest name that fits: full, short or abbreviation, N1), the scores stacked beside them and
 * the clock (or kickoff and countdown, the final note, "Probably ended", "Score hidden") in a column only
 * as wide as its text. Hold OK / Menu opens the card menu, as on a card. The in-player panel (#83)
 * draws its games with this same row: [nowPlaying] labels the game playing, [situation] adds the SB1
 * line under the teams, [shortNames] starts from ESPN's short names.
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
    /** An upcoming game with a reminder (#44): a bell before its status. */
    reminded: Boolean = false,
    /** The game playing (the in-player panel): "NOW PLAYING" above its teams. */
    nowPlaying: Boolean = false,
    /** The situation line ("" for none), shown only with the live score. */
    situation: String = "",
    /** Team names start from the short name (the panel's narrower rows). */
    shortNames: Boolean = false
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    var focused by remember { mutableStateOf(false) }
    val (longPress, click) = if (onLongClick != null) rememberSportsLiveLongPress(menuOpen, onLongClick, onClick) else Modifier to onClick
    val item = row.item
    val final = row.group == ScoreboardGroup.FINAL
    LightweightCard(
        onClick = click,
        modifier = modifier
            .fillMaxWidth()
            .then(if (final && !focused) Modifier.alpha(FINAL_ROW_ALPHA) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .then(longPress),
        shape = shape,
        containerColor = when {
            selected -> NuvioTheme.colors.SurfaceVariant
            row.group == ScoreboardGroup.LIVE -> ArtColors.LiveBlock
            final -> ArtColors.FinalBlock
            else -> NuvioTheme.colors.BackgroundCard
        },
        focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = (row.art?.league ?: item.sport ?: item.catalogName).uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = ArtColors.Secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = ROW_TAG_MAX_WIDTH)
                    .clip(RoundedCornerShape(NuvioTheme.radii.sm))
                    .background(ArtColors.Panel)
                    .padding(horizontal = NuvioTheme.spacing.xs, vertical = NuvioTheme.spacing.xxs)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)) {
                if (nowPlaying) {
                    Text(
                        text = stringResource(R.string.sports_live_now_playing).uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = NuvioTheme.colors.Primary,
                        letterSpacing = 1.sp,
                        maxLines = 1
                    )
                }
                val art = row.art
                if (art != null) {
                    // Both lines step down together (full name, short name, abbreviation), so the two
                    // teams always read alike.
                    val firstChoices = remember(art.first) { scoreboardNameChoices(art.first) }
                    val secondChoices = remember(art.second) { scoreboardNameChoices(art.second) }
                    var level by remember(firstChoices, secondChoices, shortNames) { mutableIntStateOf(if (shortNames) 1 else 0) }
                    val maxLevel = maxOf(firstChoices.lastIndex, secondChoices.lastIndex)
                    val tooLong: () -> Unit = { if (level < maxLevel) level++ }
                    TeamLine(firstChoices, level, art.first, art.hasLogos, row.firstFollowed, tooLong)
                    TeamLine(secondChoices, level, art.second, art.hasLogos, row.secondFollowed, tooLong)
                } else {
                    ItemLine(item.meta.name, item.followed)
                }
                if (situation.isNotEmpty() && row.chip is ScoreChip.Live) {
                    Text(
                        text = situation,
                        style = MaterialTheme.typography.bodySmall,
                        color = ArtColors.Secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            scoreOf(row.chip)?.let { score ->
                Column(horizontalAlignment = Alignment.End) {
                    ScoreText(score.first)
                    ScoreText(score.second)
                }
            }
            if (reminded) ReminderPill()
            RowStatus(row, modifier = Modifier.widthIn(max = ROW_STATUS_MAX_WIDTH))
        }
    }
}

/** One team: its logo (when both sides have one), ★ when followed, and [choices] at [level] or shorter. */
@Composable
private fun TeamLine(
    choices: List<String>,
    level: Int,
    team: GameArtTeam,
    withLogo: Boolean,
    followed: Boolean,
    onTooLong: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        if (withLogo) {
            SportsLiveTeamLogo(team.logo, team.short, TeamLogoUrls.CARD_PX, Modifier.size(ROW_LOGO_SIZE), fontSize = 9.sp)
        }
        if (followed) {
            Icon(imageVector = Icons.Rounded.Star, contentDescription = null, tint = FOLLOWED_GOLD, modifier = Modifier.size(18.dp))
        }
        val index = level.coerceAtMost(choices.lastIndex)
        Text(
            text = choices[index],
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout ->
                if (index < choices.lastIndex && (layout.hasVisualOverflow || layout.isLineEllipsized(0))) onTooLong()
            }
        )
    }
}

/** A row with no two sides (a channel, an unparsed title): its name, on up to two lines. */
@Composable
private fun ItemLine(name: String, followed: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        if (followed) {
            Icon(imageVector = Icons.Rounded.Star, contentDescription = null, tint = FOLLOWED_GOLD, modifier = Modifier.size(18.dp))
        }
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ScoreText(value: Int) {
    Text(
        text = value.toString(),
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
        color = NuvioTheme.colors.TextPrimary,
        maxLines = 1
    )
}

/** A live or final chip's score, in the card's team order; null shows no score column. */
private fun scoreOf(chip: ScoreChip?): ChipScore? = when (chip) {
    is ScoreChip.Live -> chip.score
    is ScoreChip.Final -> chip.score
    else -> null
}

/** The row's right column: what the clock, kickoff or end says. */
@Composable
private fun RowStatus(row: ScoreboardRow, modifier: Modifier = Modifier) {
    val chipStrings = rememberScoreChipStrings()
    val live = row.chip is ScoreChip.Live
    val text = when {
        row.probablyEnded -> stringResource(R.string.sports_live_probably_ended)
        row.chip is ScoreChip.Hidden -> chipStrings.hidden
        row.chip is ScoreChip.Live -> row.chip.clock ?: stringResource(R.string.sports_live_live_pill)
        row.chip is ScoreChip.Final -> row.chip.note ?: chipStrings.finalNote
        row.chip is ScoreChip.Status -> row.chip.text ?: chipStrings.postponed
        row.group == ScoreboardGroup.FINAL -> chipStrings.finalNote
        row.group == ScoreboardGroup.LIVE -> stringResource(R.string.sports_live_live_pill)
        else -> null
    }
    val kickoff = if (text == null) row.art?.let { kickoffText(it) } else null
    // Worked out with the rows, on the view model's clock: nothing here reads the time.
    val countdown = row.startsIn?.takeIf { text == null }?.let { (h, m) ->
        if (h > 0) stringResource(R.string.sports_live_scoreboard_in_hm, h, m) else stringResource(R.string.sports_live_scoreboard_in_m, m)
    }
    // A channel or an undated game has nothing to say here: its name gets the whole width.
    if (text == null && kickoff == null && countdown == null) return
    Column(modifier = modifier, horizontalAlignment = Alignment.End) {
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            if (live) Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(ArtColors.LiveRed))
            Text(
                text = text ?: kickoff.orEmpty(),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                color = if (live) ArtColors.LiveText else ArtColors.Secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End
            )
        }
        countdown?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = ArtColors.Muted, maxLines = 1)
        }
    }
}

/**
 * The Scoreboard's focused game: the muted hover preview (or the drawn card), a LIVE tag, the big
 * scoreline, the clock line, soccer's scorers, the situation line, then Watch, Watch with another
 * game and Game stats.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SportsLiveScoreboardPane(
    row: ScoreboardRow,
    onWatch: () -> Unit,
    buttons: ScoreboardButtons,
    onWatchWith: () -> Unit,
    onGameStats: () -> Unit,
    preview: (@Composable () -> Unit)?,
    previewShowing: Boolean,
    noStream: Boolean,
    details: ScoreDetails?,
    situation: String,
    /** Watch's requester: focus goes back there when the focused button is taken away. */
    watchFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    watchModifier: Modifier = Modifier
) {
    val item = row.item
    val chipStrings = rememberScoreChipStrings()
    val detailStrings = rememberScoreDetailsStrings()
    // The picture keeps one size for every row (N6), so the pane never jumps as focus moves; the text
    // below scrolls (following the focused button) rather than squeeze it.
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        Box(
            modifier = Modifier
                .width(SCOREBOARD_ART_WIDTH)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(NuvioTheme.radii.md))
                .background(NuvioTheme.colors.PosterFallback)
        ) {
            preview?.invoke()
            if (!previewShowing) {
                val art = row.art
                if (art != null) {
                    SportsLiveGameArt(art = art, compact = false, chip = row.chip, modifier = Modifier.fillMaxSize())
                } else {
                    AsyncImage(
                        model = item.meta.backdropUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        if (row.group == ScoreboardGroup.LIVE || previewShowing) {
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                if (row.group == ScoreboardGroup.LIVE) SportsLiveStatePill(label = stringResource(R.string.sports_live_live_pill), isLive = true)
                if (previewShowing) {
                    Text(
                        text = stringResource(R.string.sports_live_scoreboard_preview_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1
                    )
                }
            }
        }
        val big = when (details) {
            is ScoreDetails.Shown -> ScoreDetailsBuilder.scorelineText(details, detailStrings)
            is ScoreDetails.Hidden -> chipStrings.hidden
            is ScoreDetails.Status -> details.text ?: chipStrings.postponed
            null -> item.meta.name
        }
        // A scoreline is short and set large; a channel's or unmatched game's title gets a smaller face
        // so it never runs to two display-size lines (N6).
        Text(
            text = big,
            style = (if (details == null) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.displaySmall)
                .copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        val shown = details as? ScoreDetails.Shown
        val clock = when {
            row.probablyEnded -> stringResource(R.string.sports_live_probably_ended)
            shown != null -> shown.status ?: chipStrings.finalNote.takeIf { !shown.live }
            else -> null
        }
        val sources = item.alternates.takeIf { it.isNotEmpty() }?.let {
            pluralStringResource(R.plurals.sports_live_sources_count, item.sourceCount, item.sourceCount)
        }
        Text(
            text = listOfNotNull(clock, item.sport, item.addonName, sources).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = if (shown?.live == true) ArtColors.LiveText else NuvioTheme.colors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (shown != null && shown.soccer) {
            listOf(shown.first, shown.second).forEach { side ->
                ScoreDetailsBuilder.scorersText(side, detailStrings)?.let { scorers ->
                    Text(
                        text = "${side.short}: $scorers",
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (situation.isNotEmpty()) {
            Text(
                text = situation,
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (noStream) {
            Text(
                text = stringResource(R.string.sports_live_no_streams_now),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1
            )
        }
        // Each button keeps its label on one line (N4, as I4 on the Cards pane): the row wraps instead.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            SportsLiveBoardButtons(
                buttons = buttons,
                onWatch = onWatch,
                onWatchWith = onWatchWith,
                onGameStats = onGameStats,
                noStream = noStream,
                watchFocusRequester = watchFocusRequester,
                watchModifier = watchModifier
            )
        }
    }
}

/**
 * The focused game's buttons (Scoreboard pane, Timeline panel): **Watch**, then **Watch with another
 * game** and **Game stats** when [buttons] offers them. Laid out by the caller's row or column.
 */
@Composable
internal fun SportsLiveBoardButtons(
    buttons: ScoreboardButtons,
    onWatch: () -> Unit,
    onWatchWith: () -> Unit,
    onGameStats: () -> Unit,
    noStream: Boolean,
    /** Watch's requester: focus goes back there when the focused button is taken away. */
    watchFocusRequester: FocusRequester,
    watchModifier: Modifier = Modifier,
    otherModifier: Modifier = Modifier
) {
    // A refresh can take Watch with another game or Game stats away while focused (the game ended, the
    // match was lost); focus then goes to Watch instead of being lost.
    var watchWithFocused by remember { mutableStateOf(false) }
    var gameStatsFocused by remember { mutableStateOf(false) }
    // Read in the focus callbacks: losing focus because the button is being removed keeps the flag.
    val currentButtons by rememberUpdatedState(buttons)
    LaunchedEffect(buttons.watchWith, buttons.gameStats) {
        val lost = (!buttons.watchWith && watchWithFocused) || (!buttons.gameStats && gameStatsFocused)
        if (!buttons.watchWith) watchWithFocused = false
        if (!buttons.gameStats) gameStatsFocused = false
        if (!lost) return@LaunchedEffect
        withFrameNanos { }
        runCatching { watchFocusRequester.requestFocus() }
    }
    val button = Modifier.widthIn(min = PANE_BUTTON_MIN_WIDTH)
    SportsLivePaneButton(onClick = onWatch, modifier = watchModifier.then(button).then(if (noStream) Modifier.alpha(0.5f) else Modifier)) {
        Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.xs))
        PaneButtonLabel(stringResource(R.string.sports_live_watch))
    }
    if (buttons.watchWith) {
        SportsLivePaneButton(
            onClick = onWatchWith,
            modifier = otherModifier.then(button).onFocusChanged { if (it.isFocused || currentButtons.watchWith) watchWithFocused = it.isFocused }
        ) { PaneButtonLabel(stringResource(R.string.sports_live_dual_watch_with)) }
    }
    if (buttons.gameStats) {
        SportsLivePaneButton(
            onClick = onGameStats,
            modifier = otherModifier.then(button).onFocusChanged { if (it.isFocused || currentButtons.gameStats) gameStatsFocused = it.isFocused }
        ) { PaneButtonLabel(stringResource(R.string.sports_live_game_stats)) }
    }
}

private val FOLLOWED_GOLD = Color(0xFFFFC107)

/** A row's league tag never takes more than this (N1); a long name ellipsizes. */
private val ROW_TAG_MAX_WIDTH = 64.dp

/** A row's status column is only as wide as its text, up to this ("Probably ended"). */
private val ROW_STATUS_MAX_WIDTH = 112.dp

private val ROW_LOGO_SIZE = 22.dp

/** The Scoreboard pane's picture: one width for every row (N6). */
private val SCOREBOARD_ART_WIDTH = 344.dp

/** A FINAL row is dimmed until focused, like a Just ended card. */
private const val FINAL_ROW_ALPHA = 0.6f
