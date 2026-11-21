package com.nuvio.tv.ui.screens.sportslive

import android.view.KeyEvent as AndroidKeyEvent
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.SportsLiveDisplayText
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.ui.screens.ambient.AmbientDialogEffect
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveFocus
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors

/** The More… sheet's heading (#134): the stage's clean title and one status line ([live]: it starts with the red dot). */
internal data class SportsLiveMoreHeading(val title: String, val status: String?, val live: Boolean = false)

/**
 * The heading for [row] (the card's Scoreboard row; null: [item] alone): "UConn vs Arizona" over
 * "NCAAM · 9:51 · 2nd half · 64–51", never the raw addon title. A channel by its clean name; an event that is
 * not a two-team match by its one-line title, with its sport tag.
 */
@Composable
internal fun rememberMoreHeading(item: SportsLiveItem, row: ScoreboardRow?): SportsLiveMoreHeading {
    val strings = rememberGameStatusStrings()
    val vs = stringResource(R.string.sports_live_matchup, "\u0000", "\u0001")
    val tag = row?.let { rowTagText(it) }.orEmpty()
    val kickoff = row?.art?.let { kickoffText(it) }
    return remember(item, row, strings, vs, tag, kickoff) {
        val art = row?.art?.takeIf { row.twoSided }
        val title = when {
            item.isChannel -> SportsLiveDisplayText.of(item.meta.name).name
            art != null -> vs.replace("\u0000", art.first.shortName).replace("\u0001", art.second.shortName)
            else -> singleLineTitle(item)
        }
        if (row == null || item.isChannel) return@remember SportsLiveMoreHeading(title, tag.ifEmpty { null })
        val lines = scoreboardStatusLines(row, strings, kickoff)
        val score = (scoreboardScoresOf(row.chip) as? ScoreboardScores.Shown)?.let { "${it.first}–${it.second}" }
        val status = listOfNotNull(tag.ifEmpty { null }, lines.primary, lines.secondary, score).joinToString(" · ")
        SportsLiveMoreHeading(title, status.ifEmpty { null }, live = lines.live)
    }
}

/**
 * More… (#134; also Hold OK on a row): a 360 dp sheet over the stage, so the list keeps its place and the row its
 * white "selected" bar. Its clean [title] and one [status] line, then, in this order: **Watch with another game**
 * (always the picker, F13), **Choose stream…**, **Game stats** (a strict ESPN match only), **Follow ‹team›** for
 * each side matched to a real team ([sides]: ESPN's short name and whether it is followed), **Remind me**
 * (upcoming games), **Team pages…**, **Details** (the raw addon text lives only there) and **Hide from Live Now**
 * ([onHide]: unmatched items only; first, as **Hide this**, with [hideFirst]), and **Report a problem** last
 * ([onReport]). The first entry takes focus; it never re-orders under focus.
 */
@Composable
internal fun SportsLiveCardMenu(
    title: String,
    sides: List<Pair<String, Boolean>>,
    /** Follow / unfollow the side at this index of [sides]. */
    onToggleFollow: (Int) -> Unit,
    /** Details (the addon's page); null hides it (a Results row has none). */
    onDetails: (() -> Unit)?,
    onDismiss: () -> Unit,
    /** The one status line under the title ("NCAAM · 9:51 · 2nd half · 64–51"); null for none. */
    status: String? = null,
    /** [status] is a live game's: it starts with the red dot. */
    statusLive: Boolean = false,
    /** "Watch with another game"; null hides it. */
    onWatchWith: (() -> Unit)? = null,
    /** [onWatchWith]'s label: the in-player panel's says "Watch both" (#140). */
    @StringRes watchWithText: Int = R.string.sports_live_dual_watch_with,
    /** "Choose stream…": the card's streams to pick from; null hides it (live games and channels offer it). */
    onChooseStream: (() -> Unit)? = null,
    /** "Game stats": the Game page (#18); null hides it (only a game with a strict ESPN match offers it). */
    onGameStats: (() -> Unit)? = null,
    /** [onGameStats] opens an upcoming game: it reads "Game preview" (#171). */
    gamePreview: Boolean = false,
    /** "Following ‹team›" in place of "Unfollow ‹team›" (the Results / Game page wording). */
    followingLabel: Boolean = false,
    /** "Remind me" / "Cancel reminder" (#44) by [reminded]; null hides it (only upcoming games offer it). */
    onToggleReminder: (() -> Unit)? = null,
    reminded: Boolean = false,
    /**
     * The team pages (#62): each team's name and its opener. One is listed as "Team page ‹A›"; two sit behind
     * **Team pages…**, which lists them in the sheet (Back returns).
     */
    teamPages: List<Pair<String, () -> Unit>> = emptyList(),
    /** "Watch postgame" (#140): the in-player panel's Just ended game, whose OK opens its box score; null hides it. */
    onWatchPostgame: (() -> Unit)? = null,
    /** "Hide from Live Now" (#134): an unmatched item leaves Live Now; null hides it. */
    onHide: (() -> Unit)? = null,
    /** [onHide] first, as "Hide this" (live.20: a row no source gives a sport, "Not sure what sport? Hide this"). */
    hideFirst: Boolean = false,
    /**
     * "Remind me for every ‹team› game" (#154): each offered side's name and whether it is on the reminder-only
     * list ("Stop reminding for ‹team› games"); [onToggleTeamReminder] gets its index.
     */
    teamReminders: List<Pair<String, Boolean>> = emptyList(),
    onToggleTeamReminder: (Int) -> Unit = {},
    /** "Watch later (no spoilers)" (#151), or "Reveal result" when [savedForLater]; null hides it. */
    onWatchLater: (() -> Unit)? = null,
    savedForLater: Boolean = false,
    /** "Scores ahead? +15 s" (live.20 Q18, #85): in the player only; null hides it. */
    onScoresAhead: (() -> Unit)? = null,
    /** "Report a problem" (#172, TV QA): saves a report as the Play/Pause hold does; always last; null hides it. */
    onReport: (() -> Unit)? = null,
    /** "Add to favorites" / "Remove from favorites" (Channels phase 1) by [favorite]: first in a channel's menu; null hides it. */
    onToggleFavorite: (() -> Unit)? = null,
    favorite: Boolean = false
) {
    var teamsOpen by remember { mutableStateOf(false) }
    SportsLiveSheet(
        title = title,
        status = status,
        statusLive = statusLive,
        onDismiss = onDismiss,
        onBack = if (teamsOpen) ({ teamsOpen = false }) else null,
        page = teamsOpen
    ) { first ->
        if (teamsOpen) {
            teamPages.forEach { (name, open) ->
                SportsLiveMenuButton(text = stringResource(R.string.sports_live_team_page, name), onClick = open, modifier = first())
            }
            return@SportsLiveSheet
        }
        if (onToggleFavorite != null) {
            SportsLiveMenuButton(
                text = stringResource(if (favorite) R.string.sports_live_channel_favorite_remove else R.string.sports_live_channel_favorite_add),
                onClick = onToggleFavorite,
                modifier = first(),
                icon = if (favorite) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                iconTint = Colors.FollowedGold
            )
        }
        if (onHide != null && hideFirst) {
            SportsLiveMenuButton(
                text = stringResource(R.string.sports_live_hide_this),
                onClick = onHide,
                modifier = first(),
                icon = Icons.Rounded.Block,
                iconTint = Colors.TextSecondary
            )
        }
        if (onWatchPostgame != null) {
            SportsLiveMenuButton(text = stringResource(R.string.sports_live_watch_postgame), onClick = onWatchPostgame, modifier = first())
        }
        if (onWatchWith != null) {
            SportsLiveMenuButton(text = stringResource(watchWithText), onClick = onWatchWith, modifier = first())
        }
        if (onChooseStream != null) {
            SportsLiveMenuButton(text = stringResource(R.string.sports_live_choose_stream_menu), onClick = onChooseStream, modifier = first())
        }
        if (onGameStats != null) {
            SportsLiveMenuButton(
                text = stringResource(if (gamePreview) R.string.sports_live_game_preview else R.string.sports_live_game_stats),
                onClick = onGameStats,
                modifier = first()
            )
        }
        sides.forEachIndexed { index, (side, followed) ->
            val label = when {
                !followed -> R.string.sports_live_follow
                followingLabel -> R.string.sports_live_game_following
                else -> R.string.sports_live_unfollow
            }
            SportsLiveMenuButton(
                text = stringResource(label, side),
                onClick = { onToggleFollow(index) },
                modifier = first(),
                icon = if (followed) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                iconTint = Colors.FollowedGold,
                followed = followed
            )
        }
        if (onToggleReminder != null) {
            SportsLiveMenuButton(
                text = stringResource(if (reminded) R.string.sports_live_cancel_reminder else R.string.sports_live_remind_me),
                onClick = onToggleReminder,
                modifier = first()
            )
        }
        teamReminders.forEachIndexed { index, (team, on) ->
            SportsLiveMenuButton(
                text = stringResource(if (on) R.string.sports_live_stop_reminding_team else R.string.sports_live_remind_every_team, team),
                onClick = { onToggleTeamReminder(index) },
                modifier = first()
            )
        }
        if (onWatchLater != null) {
            SportsLiveMenuButton(
                text = stringResource(if (savedForLater) R.string.sports_live_reveal_result_menu else R.string.sports_live_watch_later),
                onClick = onWatchLater,
                modifier = first()
            )
        }
        when (teamPages.size) {
            0 -> Unit
            1 -> teamPages.single().let { (name, open) ->
                SportsLiveMenuButton(text = stringResource(R.string.sports_live_team_page, name), onClick = open, modifier = first())
            }
            else -> SportsLiveMenuButton(text = stringResource(R.string.sports_live_team_pages), onClick = { teamsOpen = true }, modifier = first())
        }
        if (onScoresAhead != null) {
            SportsLiveMenuButton(text = stringResource(R.string.sports_live_scores_ahead), onClick = onScoresAhead, modifier = first())
        }
        if (onDetails != null) {
            SportsLiveMenuButton(text = stringResource(R.string.sports_live_details), onClick = onDetails, modifier = first())
        }
        if (onHide != null && !hideFirst) {
            SportsLiveMenuButton(
                text = stringResource(R.string.sports_live_hide_from_live_now),
                onClick = onHide,
                modifier = first(),
                icon = Icons.Rounded.Block,
                iconTint = Colors.TextSecondary
            )
        }
        if (onReport != null) {
            SportsLiveMenuButton(text = stringResource(R.string.sports_live_setting_report_problem), onClick = onReport, modifier = first())
        }
    }
}

/**
 * The sheet's frame (#134): a borderless full-window dialog with no dim, whose 360 dp panel sits over the stage
 * (top right of the safe area, under the header), so the list stays visible beside it. [content] gets `first()`,
 * the modifier for the first focusable entry (it takes focus when the sheet opens, and again when [page] flips).
 * The OK held to open the sheet is swallowed until a fresh press. Back runs [onBack] while it is set, else closes.
 */
@Composable
internal fun SportsLiveSheet(
    title: String,
    status: String?,
    statusLive: Boolean,
    onDismiss: () -> Unit,
    onBack: (() -> Unit)?,
    page: Any? = null,
    content: @Composable (first: () -> Modifier) -> Unit
) {
    val firstFocusRequester = remember { FocusRequester() }
    var okReady by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        AmbientDialogEffect(onDismiss)
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.setWindowAnimations(0)
            window?.setDimAmount(0f)
        }
        LaunchedEffect(page) {
            withFrameNanos { }
            runCatching { firstFocusRequester.requestFocus() }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            val shape = RoundedCornerShape(SportsLiveTokens.Sheet.Radius)
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = SportsLiveTokens.Sheet.Top, end = SportsLiveTokens.Sheet.End, bottom = SportsLiveTokens.Sheet.Bottom)
                    .width(SportsLiveTokens.Sheet.Width)
                    .fillMaxHeight()
                    .background(Colors.SurfaceRaised, shape)
                    .border(1.dp, Colors.Outline, shape)
                    .onPreviewKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        if (sheetSelectKey(native.keyCode)) {
                            if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 0) okReady = true
                            if (!okReady) return@onPreviewKeyEvent true
                        }
                        if (native.keyCode == AndroidKeyEvent.KEYCODE_BACK && onBack != null) {
                            if (native.action == AndroidKeyEvent.ACTION_UP) onBack()
                            return@onPreviewKeyEvent true
                        }
                        false
                    }
                    .padding(SportsLiveTokens.Sheet.Padding)
            ) {
                Text(
                    text = title,
                    style = SportsLiveTokens.style(SportsLiveTokens.Sheet.Title),
                    color = Colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!status.isNullOrBlank()) {
                    Text(
                        text = buildAnnotatedString {
                            if (statusLive) withStyle(SpanStyle(color = Colors.LiveRed, fontSize = 9.sp)) { append("● ") }
                            withStyle(SpanStyle(color = if (statusLive) Colors.LiveText else Colors.TextSecondary)) {
                                append(status)
                            }
                        },
                        style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // The first entry shown takes the first focus (worked out on each pass of this content).
                    var firstTaken = false
                    content { if (firstTaken) Modifier else Modifier.focusRequester(firstFocusRequester).also { firstTaken = true } }
                }
            }
        }
    }
}

private fun sheetSelectKey(keyCode: Int): Boolean =
    keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER || keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == AndroidKeyEvent.KEYCODE_MENU

/**
 * A sheet entry in the one focus language (#93): 36 dp, plain text at rest, the white ring, lift and 8 % lighter
 * fill when focused; never a white slab. [icon] sits before the label in [iconTint] (Follow's ☆ / ★ in gold).
 */
@Composable
internal fun SportsLiveMenuButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    followed: Boolean = false,
    icon: ImageVector? = null,
    iconTint: Color = Colors.TextPrimary
) {
    val shape = SportsLiveTokens.Radius.ButtonShape
    val fill = SportsLiveFocus.focusedFill(Colors.SurfaceRaised)
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(SportsLiveTokens.Sheet.Entry),
        scale = ButtonDefaults.scale(focusedScale = SportsLiveTokens.Focus.LIFT),
        shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Colors.TextPrimary,
            focusedContainerColor = fill,
            focusedContentColor = Colors.TextPrimary,
            pressedContainerColor = fill,
            pressedContentColor = Colors.TextPrimary
        ),
        border = ButtonDefaults.border(
            border = Border.None,
            focusedBorder = SportsLiveFocus.ring(shape),
            pressedBorder = SportsLiveFocus.ring(shape)
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text,
            style = SportsLiveTokens.style(SportsLiveTokens.Type.Button),
            color = if (followed) Colors.FollowedGold else Colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
