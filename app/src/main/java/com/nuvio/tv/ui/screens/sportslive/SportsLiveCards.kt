package com.nuvio.tv.ui.screens.sportslive

import androidx.annotation.StringRes
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveBadge
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButton
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButtonRole
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveFocus
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.core.sportslive.displayName
import com.nuvio.tv.core.sportslive.SportsLiveDisplayText
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.SportsLiveLocalTime
import com.nuvio.tv.core.sportslive.SportsLivePillTime
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.core.sportslive.scores.ScoreDetails
import com.nuvio.tv.core.sportslive.scores.WhyKind
import com.nuvio.tv.core.sportslive.scores.WhyTag
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.ui.components.LightweightCard
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.sportslive.preview.SportsLivePreviewLabel
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.rememberLongPressKeyTracker
import java.time.ZoneId
import java.util.Locale

/**
 * The state shown in a card's pill and the pane's meta line: `LIVE`, the local start (time only
 * today, "Tomorrow" + time, else weekday or date + time; just the date when the addon gave no time
 * of day), `Time TBA` for an untimed Upcoming item, else null.
 */
@Composable
internal fun rememberSportsLiveStateLabeler(tab: SportsLiveTab): (SportsLiveItem) -> String? {
    val live = stringResource(R.string.sports_live_live_pill)
    val tba = stringResource(R.string.sports_live_time_tba)
    val tomorrow = stringResource(R.string.sports_live_tomorrow)
    return remember(tab, live, tba, tomorrow) {
        val labeler: (SportsLiveItem) -> String? = { item ->
            val startMs = item.startMs
            when {
                item.isLive -> live
                startMs != null -> when (
                    val pill = SportsLivePillTime.of(
                        startMs = startMs,
                        hasTimeOfDay = item.startHasTimeOfDay,
                        nowMs = System.currentTimeMillis(),
                        zone = ZoneId.systemDefault(),
                        locale = Locale.getDefault()
                    )
                ) {
                    is SportsLivePillTime.Today -> pill.time
                    is SportsLivePillTime.Tomorrow -> String.format(tomorrow, pill.time)
                    is SportsLivePillTime.Other -> pill.text
                }
                tab == SportsLiveTab.UPCOMING -> tba
                else -> null
            }
        }
        labeler
    }
}

@Composable
internal fun SportsLiveStatePill(label: String, isLive: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = NuvioTheme.colors.TextPrimary,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(NuvioTheme.radii.sm))
            // Not SurfaceVariant: that is the selected card's container, so the pill vanished on it.
            .background(if (isLive) NuvioTheme.colors.Error else NuvioTheme.colors.Background.copy(alpha = 0.8f))
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xxs)
    )
}

@Composable
internal fun SportsLiveListCard(
    item: SportsLiveItem,
    stateLabel: String?,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
    menuOpen: Boolean = false,
    /** A game's drawn card, in place of the addon's poster; null keeps the addon's art (channels). */
    art: GameArt? = null,
    /** The live / final score chip, beside the state pill; null shows none. */
    chip: ScoreChip? = null,
    /** In the Just ended strip (#60): dimmed, and its pill never red. */
    ended: Boolean = false,
    /** ESPN says the game is over (C3), in the postgame grace too: its pill is never red. */
    final: Boolean = false,
    /** A strict replay match exists: the Replay tag beside the chip. */
    hasReplay: Boolean = false,
    /** An upcoming game with a reminder (#44): the blue bell chip beside the start. */
    reminded: Boolean = false
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    var longPressTriggered by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val longPressKeyTracker = rememberLongPressKeyTracker()
    // The menu takes the key-up of the long press, so the flag would swallow the next short OK.
    LaunchedEffect(menuOpen) { longPressTriggered = false }

    LightweightCard(
        onClick = {
            if (longPressTriggered) longPressTriggered = false else onClick()
        },
        modifier = modifier
            .fillMaxWidth()
            // Dimmed until focused, so the focus ring stays clear.
            .then(if (ended && !focused) Modifier.alpha(ENDED_CARD_ALPHA) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus() else longPressTriggered = false
            }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action == AndroidKeyEvent.ACTION_DOWN && native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                    longPressTriggered = true
                    onLongClick()
                    return@onPreviewKeyEvent true
                }
                if (longPressKeyTracker.handle(native, ::isSelectKey) {
                        longPressTriggered = true
                        onLongClick()
                    }
                ) {
                    if (native.action == AndroidKeyEvent.ACTION_UP) longPressTriggered = false
                    return@onPreviewKeyEvent true
                }
                if (native.action == AndroidKeyEvent.ACTION_UP && longPressTriggered &&
                    (isSelectKey(native.keyCode) || native.keyCode == AndroidKeyEvent.KEYCODE_MENU)
                ) {
                    longPressTriggered = false
                    return@onPreviewKeyEvent true
                }
                false
            },
        shape = shape,
        containerColor = if (selected) NuvioTheme.colors.SurfaceVariant else NuvioTheme.colors.BackgroundCard,
        focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
    ) {
        Row(
            modifier = Modifier.padding(NuvioTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val thumbnail = Modifier
                .width(LIST_THUMB_WIDTH)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(NuvioTheme.radii.sm))
            if (art != null) {
                SportsLiveGameArt(art = art, compact = true, modifier = thumbnail)
            } else {
                AsyncImage(
                    model = rememberSizedImage(item.meta.poster ?: item.meta.backdropUrl, LIST_THUMB_WIDTH, LIST_THUMB_HEIGHT),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = thumbnail.background(NuvioTheme.colors.PosterFallback)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
                if (stateLabel != null || chip != null || hasReplay || reminded) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (stateLabel != null) SportsLiveStatePill(label = stateLabel, isLive = item.isLive && !ended && !final)
                        if (chip != null) SportsLiveScoreChip(chip)
                        if (hasReplay) ReplayPill()
                        if (reminded) ReminderPill()
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // A followed team plays (or is named on this channel).
                    if (item.followed) {
                        Icon(
                            imageVector = Icons.Rounded.Star,
                            contentDescription = null,
                            tint = FOLLOWED_STAR_COLOR,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        text = item.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    // The addon's quality marker, cut from the name (#100), as a badge.
                    SportsLiveDisplayText.of(item.meta.name).quality?.let { SportsLiveBadge(it) }
                }
                // A merged game names its primary addon and how many addons carry it.
                val sources = if (item.alternates.isEmpty()) {
                    null
                } else {
                    pluralStringResource(R.plurals.sports_live_sources_count, item.sourceCount, item.sourceCount)
                }
                Text(
                    text = listOfNotNull(item.sport ?: item.catalogName, item.addonName, sources).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun SportsLiveDetailsPane(
    item: SportsLiveItem,
    stateLabel: String?,
    onWatch: () -> Unit,
    onDetails: () -> Unit,
    modifier: Modifier = Modifier,
    watchModifier: Modifier = Modifier,
    /** "Choose stream" beside Watch (live games and channels); null hides it. */
    onChooseStream: (() -> Unit)? = null,
    /** "Game stats" beside Choose stream: the Game page, for a game with a strict ESPN match; null hides it. */
    onGameStats: (() -> Unit)? = null,
    /** "Watch with another game" beside Watch (#57): live games on Live now; null hides it. */
    onWatchWith: (() -> Unit)? = null,
    /** The hover preview's video, composed under the art while a preview plays; null when none does. */
    preview: (@Composable () -> Unit)? = null,
    /** The preview's first frame is on screen: the art gives way to the video, and the state line gets a label. */
    previewShowing: Boolean = false,
    /** The lookup for this card found nothing to play: "No streams right now" shows and Watch is dimmed. */
    noStream: Boolean = false,
    /** A game's drawn card, in place of the addon's backdrop; null keeps the addon's art. */
    art: GameArt? = null,
    /** The score chip drawn on [art]. */
    chip: ScoreChip? = null,
    /** A matched game's scoreline, linescore and scorers (spoiler setting applied); null shows none. */
    scoreDetails: ScoreDetails? = null,
    /** The game is over (Just ended, or an ESPN final): the addon's "LIVE NOW" line is left out (C3). */
    over: Boolean = false,
    /** "Remind me" / "Cancel reminder" (#44) by [reminded]; null hides it (only upcoming games offer it). */
    onToggleReminder: (() -> Unit)? = null,
    reminded: Boolean = false
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        Box(
            // Measured last and shrunk to the space left, so a long title never pushes the buttons off screen.
            modifier = Modifier
                .weight(1f, fill = false)
                .aspectRatio(16f / 9f, matchHeightConstraintsFirst = true)
                .clip(RoundedCornerShape(NuvioTheme.radii.md))
                .background(NuvioTheme.colors.PosterFallback)
        ) {
            preview?.invoke()
            if (!previewShowing) {
                if (art != null) {
                    SportsLiveGameArt(art = art, compact = false, chip = chip, modifier = Modifier.fillMaxSize())
                } else {
                    AsyncImage(
                        model = rememberSizedImage(item.meta.backdropUrl, PANE_ART_WIDTH, PANE_ART_HEIGHT),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        Text(
            text = item.displayName,
            style = MaterialTheme.typography.headlineSmall,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        // The preview's label leads the state line, off the video, so it never covers the stream's
        // own score graphics.
        Row(
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (previewShowing) SportsLivePreviewLabel()
            Text(
                text = listOfNotNull(
                    item.sport,
                    stateLabel,
                    item.addonName,
                    item.alternates.takeIf { it.isNotEmpty() }?.let {
                        pluralStringResource(R.plurals.sports_live_sources_count, item.sourceCount, item.sourceCount)
                    }
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        scoreDetails?.let { SportsLiveScoreDetails(it) }
        val tomorrow = stringResource(R.string.sports_live_tomorrow)
        val description = remember(item.meta.id, item.meta.description, tomorrow, over) {
            SportsLiveDisplayText.description(item.meta.description)?.let { if (over) withoutLiveMarkers(it) else it }?.let {
                SportsLiveLocalTime.localizeUtcStamps(
                    text = it,
                    nowMs = System.currentTimeMillis(),
                    zone = ZoneId.systemDefault(),
                    locale = Locale.getDefault()
                ) { time -> String.format(tomorrow, time) }
            }
        }
        description?.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (noStream) {
            Text(
                text = stringResource(R.string.sports_live_no_streams_now),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // Each button keeps its label on one line at its own width: the row wraps rather than squeeze one.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
        ) {
            val button = Modifier.widthIn(min = PANE_BUTTON_MIN_WIDTH)
            // Still focusable and pressable when dimmed: OK looks the streams up again.
            SportsLivePaneButton(
                onClick = onWatch,
                modifier = watchModifier.then(button).then(if (noStream) Modifier.alpha(0.5f) else Modifier),
                primary = true
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.xs))
                PaneButtonLabel(stringResource(R.string.sports_live_watch))
            }
            if (onWatchWith != null) {
                SportsLivePaneButton(onClick = onWatchWith, modifier = button) {
                    PaneButtonLabel(stringResource(R.string.sports_live_dual_watch_with))
                }
            }
            if (onChooseStream != null) {
                SportsLivePaneButton(onClick = onChooseStream, modifier = button) {
                    PaneButtonLabel(stringResource(R.string.sports_live_choose_stream))
                }
            }
            if (onGameStats != null) {
                SportsLivePaneButton(onClick = onGameStats, modifier = button) {
                    PaneButtonLabel(stringResource(R.string.sports_live_game_stats))
                }
            }
            if (onToggleReminder != null) {
                SportsLivePaneButton(onClick = onToggleReminder, modifier = button) {
                    Icon(imageVector = Icons.Rounded.Notifications, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(NuvioTheme.spacing.xs))
                    PaneButtonLabel(stringResource(if (reminded) R.string.sports_live_cancel_reminder else R.string.sports_live_remind_me))
                }
            }
            SportsLivePaneButton(onClick = onDetails, modifier = button) {
                PaneButtonLabel(stringResource(R.string.sports_live_details))
            }
        }
    }
}

/**
 * The More… menu (V2, #132; also Hold OK on a row): **Watch with another game** (always the picker, F13),
 * **Choose stream**, **Game stats** (a strict ESPN match only), **Details** (the raw addon text lives only
 * there), **Follow ‹team›** for each side ([sides] holds each side's display name and whether a followed
 * team is that side), the team pages, then **Remind me** (upcoming games only). The first entry shown takes
 * focus. A Results row uses it too, with Game stats and the sides only.
 */
@Composable
internal fun SportsLiveCardMenu(
    title: String,
    sides: List<Pair<String, Boolean>>,
    onToggleFollow: (String) -> Unit,
    /** Details (the addon's page); null hides it (a Results row has none). */
    onDetails: (() -> Unit)?,
    onDismiss: () -> Unit,
    /** "Watch with another game"; null hides it. */
    onWatchWith: (() -> Unit)? = null,
    /** [onWatchWith]'s label: the in-player panel's says "Watch both" (#140). */
    @StringRes watchWithText: Int = R.string.sports_live_dual_watch_with,
    /** "Choose stream": the card's streams to pick from; null hides it (live games and channels offer it). */
    onChooseStream: (() -> Unit)? = null,
    /** "Game stats": the Game page (#18); null hides it (only a game with a strict ESPN match offers it). */
    onGameStats: (() -> Unit)? = null,
    /** "Following ‹team›" in place of "Unfollow ‹team›" (the Results / Game page wording). */
    followingLabel: Boolean = false,
    /** "Remind me" / "Cancel reminder" (#44) by [reminded]; null hides it (only upcoming games offer it). */
    onToggleReminder: (() -> Unit)? = null,
    reminded: Boolean = false,
    /**
     * "Team page ‹A›" / "Team page ‹B›" (#62): each team's name and its opener; only with [onGameStats]
     * (a strict ESPN match) and a pro team with ESPN's id ([com.nuvio.tv.core.sportslive.scores.TeamPages.refsOf]).
     */
    teamPages: List<Pair<String, () -> Unit>> = emptyList(),
    /** "Watch postgame" (#140): the in-player panel's Just ended game, whose OK opens its box score; null hides it. */
    onWatchPostgame: (() -> Unit)? = null
) {
    val firstFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocusRequester.requestFocus() } }
    NuvioDialog(onDismiss = onDismiss, title = title) {
        // The first entry shown takes the first focus (worked out on each pass of this content).
        var firstTaken = false
        fun first(): Modifier = if (firstTaken) Modifier else Modifier.focusRequester(firstFocusRequester).also { firstTaken = true }
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
            SportsLiveMenuButton(text = stringResource(R.string.sports_live_game_stats), onClick = onGameStats, modifier = first())
        }
        if (onDetails != null) {
            SportsLiveMenuButton(text = stringResource(R.string.sports_live_details), onClick = onDetails, modifier = first())
        }
        sides.forEach { (side, followed) ->
            val label = when {
                !followed -> R.string.sports_live_follow
                followingLabel -> R.string.sports_live_game_following
                else -> R.string.sports_live_unfollow
            }
            SportsLiveMenuButton(
                text = stringResource(label, side),
                onClick = { onToggleFollow(side) },
                modifier = first(),
                followed = followed
            )
        }
        teamPages.forEach { (name, open) ->
            SportsLiveMenuButton(text = stringResource(R.string.sports_live_team_page, name), onClick = open, modifier = first())
        }
        if (onToggleReminder != null) {
            SportsLiveMenuButton(
                text = stringResource(if (reminded) R.string.sports_live_cancel_reminder else R.string.sports_live_remind_me),
                onClick = onToggleReminder,
                modifier = first()
            )
        }
    }
}

/**
 * A menu entry in the one focus language (#93): plain text at rest, the white ring, lift and 8 % lighter
 * fill when focused; never a white slab. [followed] sets a Follow entry's ★ in gold.
 */
@Composable
internal fun SportsLiveMenuButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, followed: Boolean = false) {
    val shape = SportsLiveTokens.Radius.ButtonShape
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        scale = ButtonDefaults.scale(focusedScale = SportsLiveTokens.Focus.LIFT),
        shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (followed) SportsLiveTokens.Colors.FollowedGold else SportsLiveTokens.Colors.TextPrimary,
            focusedContainerColor = SportsLiveFocus.focusedFill(SportsLiveTokens.Colors.Surface),
            focusedContentColor = if (followed) SportsLiveTokens.Colors.FollowedGold else SportsLiveTokens.Colors.TextPrimary,
            pressedContainerColor = SportsLiveFocus.focusedFill(SportsLiveTokens.Colors.Surface),
            pressedContentColor = SportsLiveTokens.Colors.TextPrimary
        ),
        border = ButtonDefaults.border(
            border = Border.None,
            focusedBorder = SportsLiveFocus.ring(shape),
            pressedBorder = SportsLiveFocus.ring(shape)
        )
    ) {
        Text(text, style = SportsLiveTokens.style(SportsLiveTokens.Type.RowName), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A pane button's label: one line at its full width, never wrapped. */
@Composable
internal fun PaneButtonLabel(text: String) {
    Text(text, maxLines = 1, softWrap = false)
}

internal val PANE_BUTTON_MIN_WIDTH = 96.dp

/** A list card's picture (#71: also the size its addon art is decoded at). */
private val LIST_THUMB_WIDTH = 144.dp
private val LIST_THUMB_HEIGHT = 81.dp

/** The size the details pane's addon art is decoded at (#71): the pane's picture at its largest. */
internal val PANE_ART_WIDTH = 480.dp
internal val PANE_ART_HEIGHT = 270.dp

/** Styled like the retry button in `ErrorState`; focused, the design's white fill with dark text. */
@Composable
internal fun SportsLivePaneButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** The pane's main action (Watch): filled white; the rest are outlined (#93). */
    primary: Boolean = false,
    content: @Composable RowScope.() -> Unit
) {
    SportsLiveButton(
        onClick = onClick,
        modifier = modifier,
        role = if (primary) SportsLiveButtonRole.PRIMARY else SportsLiveButtonRole.SECONDARY,
        content = content
    )
}

private val LIVE_MARKER_LINE = Regex("""^\W*live(\s+now)?\W*$""", RegexOption.IGNORE_CASE)

/** [description] without the addon's own "LIVE" / "LIVE NOW" lines: for a game that is over (C3). */
internal fun withoutLiveMarkers(description: String): String =
    description.lines().filterNot { LIVE_MARKER_LINE.matches(it.trim()) }.joinToString("\n").trim()

/** The gold star of a followed team's game (cards, the player's live games panel). */
internal val FOLLOWED_STAR_COLOR = SportsLiveTokens.Colors.FollowedGold

/** The reminder chip (#44): a bell and "Reminder on", blue like the why tags so it never reads as live or followed. */
@Composable
internal fun ReminderPill() {
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(WHY_TAG_BACKGROUND)
            .border(1.dp, WHY_TAG_BORDER, shape)
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = Icons.Rounded.Notifications, contentDescription = null, tint = WHY_TAG_TEXT, modifier = Modifier.size(14.dp))
        Text(
            text = stringResource(R.string.sports_live_reminder_on),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = WHY_TAG_TEXT,
            maxLines = 1
        )
    }
}

/** [tag]'s words (the excitement badge, #43): "Close · 2 min left", "OT", "Lead change"... */
@Composable
internal fun whyTagText(tag: WhyTag): String = when (tag.kind) {
    WhyKind.PENALTIES -> stringResource(R.string.sports_live_why_penalties)
    WhyKind.SHOOTOUT -> stringResource(R.string.sports_live_why_shootout)
    WhyKind.OVERTIME -> stringResource(R.string.sports_live_why_overtime)
    WhyKind.EXTRA_TIME -> stringResource(R.string.sports_live_why_extra_time)
    WhyKind.EXTRA_INNINGS -> stringResource(R.string.sports_live_why_extra_innings)
    WhyKind.CLOSE_LATE -> tag.minutesLeft?.let { stringResource(R.string.sports_live_why_close_minutes, it) }
        ?: stringResource(R.string.sports_live_why_close_late)
    WhyKind.LEAD_CHANGE -> stringResource(R.string.sports_live_why_lead_change)
    WhyKind.JUST_SCORED -> stringResource(R.string.sports_live_why_just_scored)
    WhyKind.RED_ZONE -> stringResource(R.string.sports_live_why_red_zone)
    WhyKind.BASES_LOADED -> stringResource(R.string.sports_live_why_bases_loaded)
    WhyKind.SCORING_POSITION -> stringResource(R.string.sports_live_why_scoring_position)
    WhyKind.CLOSE -> stringResource(R.string.sports_live_why_close)
}

private val WHY_TAG_TEXT = SportsLiveTokens.Colors.WhyTagText
private val WHY_TAG_BACKGROUND = SportsLiveTokens.Colors.WhyTagBackground
private val WHY_TAG_BORDER = SportsLiveTokens.Colors.WhyTagBorder

/** How a Just ended card (#60) is dimmed. */
private const val ENDED_CARD_ALPHA = 0.55f


/**
 * Hold OK (or press Menu) on a card that is not a [SportsLiveListCard] (a Results row): [onLongClick],
 * with the release and the short OK that follow the hold swallowed. Pass the result's [onClick] wrapper
 * to the card. [menuOpen] resets the hold once the menu takes the key-up.
 */
@Composable
internal fun rememberSportsLiveLongPress(menuOpen: Boolean, onLongClick: () -> Unit, onClick: () -> Unit): Pair<Modifier, () -> Unit> {
    var longPressTriggered by remember { mutableStateOf(false) }
    val tracker = rememberLongPressKeyTracker()
    val currentOnLongClick by rememberUpdatedState(onLongClick)
    val currentOnClick by rememberUpdatedState(onClick)
    LaunchedEffect(menuOpen) { longPressTriggered = false }
    val modifier = Modifier
        .onFocusChanged { if (!it.isFocused) longPressTriggered = false }
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (native.action == AndroidKeyEvent.ACTION_DOWN && native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                longPressTriggered = true
                currentOnLongClick()
                return@onPreviewKeyEvent true
            }
            if (tracker.handle(native, ::isSelectKey) {
                    longPressTriggered = true
                    currentOnLongClick()
                }
            ) {
                if (native.action == AndroidKeyEvent.ACTION_UP) longPressTriggered = false
                return@onPreviewKeyEvent true
            }
            if (native.action == AndroidKeyEvent.ACTION_UP && longPressTriggered &&
                (isSelectKey(native.keyCode) || native.keyCode == AndroidKeyEvent.KEYCODE_MENU)
            ) {
                longPressTriggered = false
                return@onPreviewKeyEvent true
            }
            false
        }
    return modifier to { if (longPressTriggered) longPressTriggered = false else currentOnClick() }
}

private fun isSelectKey(keyCode: Int): Boolean =
    keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
