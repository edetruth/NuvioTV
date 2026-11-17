package com.nuvio.tv.ui.screens.sportslive

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    /** The first game of the section's pick-second mode: a "1" badge before the title. */
    pickedFirst: Boolean = false,
    /** A game's drawn card, in place of the addon's poster; null keeps the addon's art (channels). */
    art: GameArt? = null,
    /** The live / final score chip, beside the state pill; null shows none. */
    chip: ScoreChip? = null
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    var longPressTriggered by remember { mutableStateOf(false) }
    val longPressKeyTracker = rememberLongPressKeyTracker()
    // The menu takes the key-up of the long press, so the flag would swallow the next short OK.
    LaunchedEffect(menuOpen) { longPressTriggered = false }

    LightweightCard(
        onClick = {
            if (longPressTriggered) longPressTriggered = false else onClick()
        },
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocus() else longPressTriggered = false }
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
                .width(144.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(NuvioTheme.radii.sm))
            if (art != null) {
                SportsLiveGameArt(art = art, compact = true, modifier = thumbnail)
            } else {
                AsyncImage(
                    model = item.meta.poster ?: item.meta.backdropUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = thumbnail.background(NuvioTheme.colors.PosterFallback)
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
                if (stateLabel != null || chip != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (stateLabel != null) SportsLiveStatePill(label = stateLabel, isLive = item.isLive)
                        if (chip != null) SportsLiveScoreChip(chip)
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (pickedFirst) {
                        Text(
                            text = "1",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = NuvioTheme.colors.OnPrimary,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(NuvioTheme.radii.sm))
                                .background(NuvioTheme.colors.Primary)
                                .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xxs)
                        )
                    }
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
                        text = item.meta.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
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
    /** The hover preview's video, composed under the art while a preview plays; null when none does. */
    preview: (@Composable () -> Unit)? = null,
    /** The preview's first frame is on screen: the art gives way to the video, and the state line gets a label. */
    previewShowing: Boolean = false,
    /** The lookup for this card found nothing to play: "No streams right now" shows and Watch is dimmed. */
    noStream: Boolean = false,
    /** A game's drawn card, in place of the addon's backdrop; null keeps the addon's art. */
    art: GameArt? = null,
    /** The score chip drawn on [art]. */
    chip: ScoreChip? = null
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
                        model = item.meta.backdropUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        Text(
            text = item.meta.name,
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
        val tomorrow = stringResource(R.string.sports_live_tomorrow)
        val description = remember(item.meta.id, item.meta.description, tomorrow) {
            item.meta.description?.let {
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
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
            // Still focusable and pressable when dimmed: OK looks the streams up again.
            SportsLivePaneButton(
                onClick = onWatch,
                modifier = watchModifier.then(if (noStream) Modifier.alpha(0.5f) else Modifier)
            ) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(NuvioTheme.spacing.xs))
                Text(stringResource(R.string.sports_live_watch))
            }
            SportsLivePaneButton(onClick = onDetails) {
                Text(stringResource(R.string.sports_live_details))
            }
        }
    }
}

/**
 * The long-press menu of a card: "Watch with another game" first when offered (it takes the first
 * focus), then Follow / Unfollow for each side ([sides] holds each side's display name and whether
 * a followed team is that side), then Details. Laid out like `PosterOptionsDialog`.
 */
@Composable
internal fun SportsLiveCardMenu(
    title: String,
    sides: List<Pair<String, Boolean>>,
    onToggleFollow: (String) -> Unit,
    onDetails: () -> Unit,
    onDismiss: () -> Unit,
    /** "Watch with another game"; null hides it (only live game cards offer it). */
    onWatchWith: (() -> Unit)? = null
) {
    val firstFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocusRequester.requestFocus() } }

    NuvioDialog(onDismiss = onDismiss, title = title) {
        if (onWatchWith != null) {
            SportsLiveMenuButton(
                text = stringResource(R.string.sports_live_dual_watch_with),
                onClick = onWatchWith,
                modifier = Modifier.focusRequester(firstFocusRequester)
            )
        }
        sides.forEachIndexed { index, (side, followed) ->
            SportsLiveMenuButton(
                text = stringResource(if (followed) R.string.sports_live_unfollow else R.string.sports_live_follow, side),
                onClick = { onToggleFollow(side) },
                modifier = if (index == 0 && onWatchWith == null) Modifier.focusRequester(firstFocusRequester) else Modifier
            )
        }
        SportsLiveMenuButton(
            text = stringResource(R.string.sports_live_details),
            onClick = onDetails,
            modifier = if (sides.isEmpty() && onWatchWith == null) Modifier.focusRequester(firstFocusRequester) else Modifier
        )
    }
}

@Composable
private fun SportsLiveMenuButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            contentColor = NuvioTheme.colors.TextPrimary
        )
    ) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Styled like the retry button in `ErrorState`. */
@Composable
internal fun SportsLivePaneButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = NuvioTheme.colors.BackgroundCard,
            contentColor = NuvioTheme.colors.TextPrimary,
            focusedContainerColor = NuvioTheme.colors.FocusBackground,
            focusedContentColor = NuvioTheme.colors.Primary
        ),
        shape = ButtonDefaults.shape(RoundedCornerShape(NuvioTheme.radii.md)),
        content = content
    )
}

private val FOLLOWED_STAR_COLOR = Color(0xFFFFC107)

private fun isSelectKey(keyCode: Int): Boolean =
    keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
