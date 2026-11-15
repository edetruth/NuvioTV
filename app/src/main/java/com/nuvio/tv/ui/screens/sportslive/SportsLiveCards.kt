package com.nuvio.tv.ui.screens.sportslive

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
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
import com.nuvio.tv.core.sportslive.SportsLivePillTime
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.ui.components.LightweightCard
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
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    var longPressTriggered by remember { mutableStateOf(false) }
    val longPressKeyTracker = rememberLongPressKeyTracker()

    LightweightCard(
        onClick = {
            if (longPressTriggered) longPressTriggered = false else onClick()
        },
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocus() }
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
            AsyncImage(
                model = item.meta.poster ?: item.meta.backdropUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(144.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(NuvioTheme.radii.sm))
                    .background(NuvioTheme.colors.PosterFallback)
            )
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
                if (stateLabel != null) {
                    SportsLiveStatePill(label = stateLabel, isLive = item.isLive)
                }
                Text(
                    text = item.meta.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${item.sport ?: item.catalogName} · ${item.addonName}",
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
    watchModifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        AsyncImage(
            model = item.meta.backdropUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            // Measured last and shrunk to the space left, so a long title never pushes the buttons off screen.
            modifier = Modifier
                .weight(1f, fill = false)
                .aspectRatio(16f / 9f, matchHeightConstraintsFirst = true)
                .clip(RoundedCornerShape(NuvioTheme.radii.md))
                .background(NuvioTheme.colors.PosterFallback)
        )
        Text(
            text = item.meta.name,
            style = MaterialTheme.typography.headlineSmall,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = listOfNotNull(item.sport, stateLabel, item.addonName).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        item.meta.description?.takeIf { it.isNotBlank() }?.let { description ->
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextSecondary,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
            SportsLivePaneButton(onClick = onWatch, modifier = watchModifier) {
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

private fun isSelectKey(keyCode: Int): Boolean =
    keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
        keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
