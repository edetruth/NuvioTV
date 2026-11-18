package com.nuvio.tv.ui.screens.sportslive

import android.text.format.DateFormat
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreDetails
import com.nuvio.tv.core.sportslive.scores.ScoreDetailsBuilder
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.ui.components.LightweightCard
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.dpadRepeatThrottle
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * The Matchday timeline (#24): an hour ruler in local time with the red now line, one row per league
 * with its games as blocks placed by start and estimated length (overlaps in lanes), then a "No time"
 * row for channels and date-only games. ← → move through a row in time (the window follows), ↑ ↓ change
 * row; ↑ from the first row goes to [upFocusRequester], ↓ from the last to [watchFocusRequester].
 *
 * Scrolling reads the window's offset in the layout and draw phases only, so moving the window never
 * recomposes the rows; the model changes on the view model's clock.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SportsLiveTimelineView(
    model: TimelineModel,
    /** The tab, filter and layout: a new value opens the window afresh. */
    windowKey: String,
    selectedKey: String,
    menuKey: String?,
    pickedFirstKey: String?,
    cardFocusRequester: (SportsLiveItem) -> FocusRequester,
    upFocusRequester: FocusRequester,
    watchFocusRequester: FocusRequester,
    onClick: (SportsLiveItem) -> Unit,
    onLongClick: (SportsLiveItem) -> Unit,
    onFocus: (SportsLiveItem) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentModel by rememberUpdatedState(model)
    val scope = rememberCoroutineScope()
    val rowState = rememberSaveable(windowKey, saver = LazyListState.Saver) { LazyListState() }
    val itemsByKey = remember(model) { model.items.associateBy { it.timelineKey } }
    val selectedRequester = itemsByKey[selectedKey]?.let(cardFocusRequester)
    var focusedKey by remember { mutableStateOf<String?>(null) }

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val viewportPx = with(density) { (maxWidth - LABEL_WIDTH).toPx() }.coerceAtLeast(1f)
        val pxPerMs = viewportPx / model.viewMs
        // Positions are measured from the opening window's start, kept per window so a clock tick (which
        // can widen the ruler) never moves what is on screen.
        val originMs = remember(windowKey) { model.viewStartMs }
        fun xOf(ms: Long): Float = (ms - originMs) * pxPerMs
        val scroll = remember(windowKey) { Animatable(0f) }
        val minScroll = xOf(model.startMs)
        val maxScroll = (xOf(model.endMs) - viewportPx).coerceAtLeast(minScroll)
        LaunchedEffect(minScroll, maxScroll) { scroll.updateBounds(minScroll, maxScroll) }
        val hourPx = pxPerMs * HOUR_MS

        // Keeps the focused block in the window: enough of it shown, else its start (or, for a block
        // longer than the window, its latest part) brought in with half an hour before it.
        fun reveal(block: TimelineBlock) {
            val left = xOf(block.startMs)
            val right = xOf(block.endMs)
            val from = scroll.value
            val shown = minOf(right, from + viewportPx) - maxOf(left, from)
            if (shown >= minOf(right - left, viewportPx * 0.25f)) return
            val target = if (right - left > viewportPx * 0.75f && right <= from) right - viewportPx * 0.75f else left - hourPx * 0.5f
            scope.launch { scroll.animateTo(target.coerceIn(minScroll, maxScroll), tween(SCROLL_MS)) }
        }

        // Moves focus to [key]'s card, bringing its row on screen first when it is scrolled away.
        fun focus(key: String) {
            val item = itemsByKey[key] ?: return
            val rowIndex = currentModel.navRows.indexOfFirst { key in it }
            scope.launch {
                if (rowIndex >= 0 && rowState.layoutInfo.visibleItemsInfo.none { it.index == rowIndex }) {
                    rowState.scrollToItem(rowIndex)
                    repeat(2) { withFrameNanos { } }
                }
                runCatching { cardFocusRequester(item).requestFocus() }
            }
        }

        // Coming back to the Timeline (or a refresh): the focused game's row on screen and the window on it.
        LaunchedEffect(windowKey) {
            val block = model.blocks[selectedKey] ?: return@LaunchedEffect
            val rowIndex = model.navRows.indexOfFirst { selectedKey in it }
            if (rowIndex >= 0 && rowState.layoutInfo.visibleItemsInfo.none { it.index == rowIndex }) rowState.scrollToItem(rowIndex)
            reveal(block)
        }

        val locale = Locale.getDefault()
        val hourLabels = remember(model.ticks, locale) {
            val hour = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "j"), locale)
            val day = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEd"), locale)
            model.ticks.map { tick -> (if (tick.midnight) day else hour).format(Date(tick.ms)) }
        }
        val nowLabel = remember(model.nowMs, locale) {
            SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "jmm"), locale).format(Date(model.nowMs))
        }
        val gridColor = NuvioTheme.colors.Border.copy(alpha = 0.35f)
        val nowLineX = xOf(model.nowMs)
        val tickXs = remember(model.ticks, originMs, pxPerMs) { model.ticks.map { xOf(it.ms) } }

        Column(modifier = Modifier.fillMaxSize()) {
            // The ruler: hour labels and the now tag, sliding with the window.
            Box(
                modifier = Modifier
                    .padding(start = LABEL_WIDTH)
                    .fillMaxWidth()
                    .height(RULER_HEIGHT)
                    .clipToBounds()
            ) {
                model.ticks.forEachIndexed { index, tick ->
                    Text(
                        text = hourLabels[index],
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (tick.midnight) FontWeight.Bold else FontWeight.Normal),
                        color = if (tick.midnight) NuvioTheme.colors.TextPrimary else ArtColors.Muted,
                        maxLines = 1,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .offset { IntOffset((tickXs[index] - scroll.value).roundToInt() + TICK_LABEL_INSET_PX, 0) }
                    )
                }
                Text(
                    text = nowLabel,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset { IntOffset((nowLineX - scroll.value).roundToInt() - NOW_TAG_SHIFT_PX, 0) }
                        .clip(RoundedCornerShape(NuvioTheme.radii.sm))
                        .background(ArtColors.LiveRed)
                        .padding(horizontal = NuvioTheme.spacing.xs)
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // Hour lines behind the rows and the now line over them, drawn at the window's offset.
                    .drawWithContent {
                        val left = LABEL_WIDTH.toPx()
                        val offset = scroll.value
                        tickXs.forEach { x ->
                            val at = left + x - offset
                            if (at >= left && at <= size.width) drawLine(gridColor, Offset(at, 0f), Offset(at, size.height), 1f)
                        }
                        drawContent()
                        val now = left + nowLineX - offset
                        if (now >= left && now <= size.width) drawLine(ArtColors.LiveRed, Offset(now, 0f), Offset(now, size.height), NOW_LINE_PX)
                    }
            ) {
                LazyColumn(
                    state = rowState,
                    modifier = Modifier
                        .fillMaxSize()
                        .onFocusChanged {
                            onFocusChanged(it.hasFocus)
                            if (!it.hasFocus) focusedKey = null
                        }
                        .then(if (selectedRequester != null) Modifier.focusRestorer(selectedRequester) else Modifier)
                        .dpadRepeatThrottle()
                        .onPreviewKeyEvent { event ->
                            val native = event.nativeKeyEvent
                            val move = when (native.keyCode) {
                                AndroidKeyEvent.KEYCODE_DPAD_LEFT -> TimelineMove.LEFT
                                AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> TimelineMove.RIGHT
                                AndroidKeyEvent.KEYCODE_DPAD_UP -> TimelineMove.UP
                                AndroidKeyEvent.KEYCODE_DPAD_DOWN -> TimelineMove.DOWN
                                else -> return@onPreviewKeyEvent false
                            }
                            if (native.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent true
                            val from = focusedKey ?: return@onPreviewKeyEvent false
                            val target = SportsLiveTimeline.moveTarget(currentModel, from, move)
                            when {
                                target != null -> focus(target)
                                move == TimelineMove.UP -> runCatching { upFocusRequester.requestFocus() }
                                move == TimelineMove.DOWN -> runCatching { watchFocusRequester.requestFocus() }
                            }
                            true
                        },
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
                ) {
                    itemsIndexed(model.leagues, key = { _, league -> "league|${league.name}" }) { _, league ->
                        Row(verticalAlignment = Alignment.Top) {
                            LeagueLabel(league.name, league.followed)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(LANE_HEIGHT * league.lanes)
                                    .clipToBounds()
                            ) {
                                league.blocks.forEach { block ->
                                    val item = block.row.item
                                    val blockX = xOf(block.startMs)
                                    val widthPx = (xOf(block.endMs) - blockX).coerceAtLeast(1f)
                                    val laneY = with(density) { (LANE_HEIGHT * block.lane).roundToPx() }
                                    TimelineBlockCard(
                                        block = block,
                                        selected = block.key == selectedKey,
                                        menuOpen = block.key == menuKey,
                                        pickedFirst = block.key == pickedFirstKey,
                                        wide = with(density) { widthPx.toDp() } >= WIDE_BLOCK,
                                        // Keeps the block's words on screen when its start is left of the window.
                                        contentShiftPx = {
                                            (scroll.value - blockX).coerceIn(0f, (widthPx - with(density) { MIN_STICKY_WIDTH.toPx() }).coerceAtLeast(0f))
                                        },
                                        onClick = { onClick(item) },
                                        onLongClick = { onLongClick(item) },
                                        onFocus = {
                                            focusedKey = block.key
                                            reveal(block)
                                            onFocus(item)
                                        },
                                        modifier = Modifier
                                            .offset { IntOffset((blockX - scroll.value).roundToInt(), laneY) }
                                            .width(with(density) { widthPx.toDp() })
                                            .height(LANE_HEIGHT - BLOCK_GAP)
                                            .focusRequester(cardFocusRequester(item))
                                    )
                                }
                            }
                        }
                    }
                    if (model.untimed.isNotEmpty()) {
                        item(key = "untimed") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LeagueLabel(stringResource(R.string.sports_live_timeline_no_time), followed = false)
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .horizontalScroll(rememberScrollState())
                                        .padding(vertical = NuvioTheme.spacing.xxs),
                                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                                ) {
                                    model.untimed.forEach { row ->
                                        val item = row.item
                                        TimelineUntimedCard(
                                            row = row,
                                            selected = item.timelineKey == selectedKey,
                                            menuOpen = item.timelineKey == menuKey,
                                            onClick = { onClick(item) },
                                            onLongClick = { onLongClick(item) },
                                            onFocus = {
                                                focusedKey = item.timelineKey
                                                onFocus(item)
                                            },
                                            modifier = Modifier
                                                .width(UNTIMED_WIDTH)
                                                .height(LANE_HEIGHT - BLOCK_GAP)
                                                .focusRequester(cardFocusRequester(item))
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LeagueLabel(name: String, followed: Boolean) {
    Row(
        modifier = Modifier
            .width(LABEL_WIDTH)
            .height(LANE_HEIGHT - BLOCK_GAP)
            .padding(end = NuvioTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = ArtColors.Secondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (followed) Icon(imageVector = Icons.Rounded.Star, contentDescription = null, tint = FOLLOWED_GOLD, modifier = Modifier.size(16.dp))
    }
}

/** The block's two sides ("LV v SEA", full names on a wide block), or the card's title. */
@Composable
private fun matchupOf(row: ScoreboardRow, wide: Boolean): String {
    val art = row.art ?: return row.item.meta.name
    return if (wide) {
        stringResource(R.string.sports_live_timeline_matchup, art.first.name, art.second.name)
    } else {
        stringResource(R.string.sports_live_timeline_matchup, art.first.short, art.second.short)
    }
}

/**
 * A game block: live in red with "LIVE · clock" and the score (or "Score hidden"), upcoming outlined with
 * the local kickoff and a countdown, over dimmed with the final chip ("FT 2–1"), "Score hidden" or
 * "Probably ended". Hold OK / Menu opens the card menu.
 */
@Composable
private fun TimelineBlockCard(
    block: TimelineBlock,
    selected: Boolean,
    menuOpen: Boolean,
    pickedFirst: Boolean,
    wide: Boolean,
    contentShiftPx: () -> Float,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.sm)
    var focused by remember { mutableStateOf(false) }
    val (longPress, click) = rememberSportsLiveLongPress(menuOpen, onLongClick, onClick)
    val row = block.row
    val chip = row.chip
    val chipStrings = rememberScoreChipStrings()
    val liveLabel = stringResource(R.string.sports_live_live_pill)
    val (status, score) = when (block.kind) {
        TimelineBlockKind.LIVE -> {
            val live = chip as? ScoreChip.Live
            val status = live?.clock?.let { stringResource(R.string.sports_live_timeline_live_clock, it) } ?: liveLabel
            val score = when (chip) {
                is ScoreChip.Live -> chip.score?.let { String.format(chipStrings.score, it.first, it.second) }
                is ScoreChip.Hidden -> chipStrings.hidden
                else -> null
            }
            status to score
        }
        TimelineBlockKind.UPCOMING -> {
            val kickoff = rememberLocalTime(block.startMs)
            val countdown = block.startsIn?.let { (h, m) ->
                if (h > 0) stringResource(R.string.sports_live_scoreboard_in_hm, h, m) else stringResource(R.string.sports_live_scoreboard_in_m, m)
            }
            val status = (chip as? ScoreChip.Status)?.let { ScoreChips.text(it, chipStrings) }
            (status ?: listOfNotNull(kickoff, countdown).joinToString(" · ")) to null
        }
        TimelineBlockKind.FINAL -> {
            val text = when {
                row.probablyEnded -> stringResource(R.string.sports_live_probably_ended)
                chip != null -> ScoreChips.text(chip, chipStrings)
                else -> chipStrings.finalNote
            }
            text to null
        }
    }
    val live = block.kind == TimelineBlockKind.LIVE
    val final = block.kind == TimelineBlockKind.FINAL
    LightweightCard(
        onClick = click,
        modifier = modifier
            .then(if (final && !focused) Modifier.alpha(FINAL_ALPHA) else Modifier)
            .then(
                when {
                    focused -> Modifier
                    live -> Modifier.border(1.dp, ArtColors.LiveRed, shape)
                    block.kind == TimelineBlockKind.UPCOMING -> Modifier.border(2.dp, UPCOMING_OUTLINE, shape)
                    else -> Modifier
                }
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .then(longPress),
        shape = shape,
        containerColor = when {
            live -> if (selected) LIVE_BLOCK_SELECTED else ArtColors.LiveBlock
            final -> ArtColors.FinalBlock
            selected -> NuvioTheme.colors.SurfaceVariant
            else -> ArtColors.Background
        },
        focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(contentShiftPx().roundToInt(), 0) }
                .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xxs),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs), verticalAlignment = Alignment.CenterVertically) {
                    if (pickedFirst) {
                        Text(
                            text = "1",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = NuvioTheme.colors.OnPrimary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(NuvioTheme.radii.sm))
                                .background(NuvioTheme.colors.Primary)
                                .padding(horizontal = NuvioTheme.spacing.xs)
                        )
                    }
                    if (row.firstFollowed || row.secondFollowed) {
                        Icon(imageVector = Icons.Rounded.Star, contentDescription = null, tint = FOLLOWED_GOLD, modifier = Modifier.size(14.dp))
                    }
                    Text(
                        text = matchupOf(row, wide),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (live) FontWeight.Bold else FontWeight.Normal, fontFeatureSettings = "tnum"),
                    color = if (live) ArtColors.LiveText else ArtColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (score != null) {
                Text(
                    text = score,
                    style = if (chip is ScoreChip.Hidden) {
                        MaterialTheme.typography.bodySmall
                    } else {
                        MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
                    },
                    color = if (chip is ScoreChip.Hidden) ArtColors.Muted else NuvioTheme.colors.TextPrimary,
                    maxLines = 1
                )
            }
        }
    }
}

/** A "No time" card: a channel or a game with a date only, with its state (live, final, date). */
@Composable
private fun TimelineUntimedCard(
    row: ScoreboardRow,
    selected: Boolean,
    menuOpen: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.sm)
    val (longPress, click) = rememberSportsLiveLongPress(menuOpen, onLongClick, onClick)
    val chipStrings = rememberScoreChipStrings()
    val live = row.group == ScoreboardGroup.LIVE
    val status = when {
        row.probablyEnded -> stringResource(R.string.sports_live_probably_ended)
        row.chip != null -> ScoreChips.text(row.chip, chipStrings)
        live -> stringResource(R.string.sports_live_live_pill)
        row.group == ScoreboardGroup.FINAL -> chipStrings.finalNote
        else -> row.art?.let { kickoffText(it) } ?: row.item.sport ?: row.item.catalogName
    }
    LightweightCard(
        onClick = click,
        modifier = modifier
            .onFocusChanged { if (it.isFocused) onFocus() }
            .then(longPress),
        shape = shape,
        containerColor = when {
            selected -> NuvioTheme.colors.SurfaceVariant
            live -> ArtColors.LiveBlock
            else -> ArtColors.FinalBlock
        },
        focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
    ) {
        Column(modifier = Modifier.padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xxs)) {
            Text(
                text = matchupOf(row, wide = true),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = if (live) ArtColors.LiveText else ArtColors.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * The focused block's panel under the Timeline: the muted preview (or the drawn card), then the LIVE
 * tag, the big scoreline and clock, the S5 linescore and scorers, the situation line, and the buttons
 * (Watch, Watch with another game, Game stats), as on the Scoreboard.
 */
@Composable
internal fun SportsLiveTimelinePanel(
    row: ScoreboardRow,
    buttons: ScoreboardButtons,
    onWatch: () -> Unit,
    onWatchWith: () -> Unit,
    onGameStats: () -> Unit,
    preview: (@Composable () -> Unit)?,
    previewShowing: Boolean,
    noStream: Boolean,
    details: ScoreDetails?,
    situation: String,
    watchFocusRequester: FocusRequester,
    /** Where ↑ and ← from the buttons go: the focused block. */
    blockFocusRequester: FocusRequester?,
    modifier: Modifier = Modifier
) {
    val item = row.item
    val chipStrings = rememberScoreChipStrings()
    val detailStrings = rememberScoreDetailsStrings()
    val back = Modifier.focusProperties {
        blockFocusRequester?.let {
            left = it
            up = it
        }
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(NuvioTheme.radii.md))
            .background(ArtColors.Panel)
            .padding(NuvioTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(NuvioTheme.radii.sm))
                .background(NuvioTheme.colors.PosterFallback)
        ) {
            preview?.invoke()
            if (!previewShowing) {
                val art = row.art
                if (art != null) {
                    SportsLiveGameArt(art = art, compact = true, chip = row.chip, modifier = Modifier.fillMaxSize())
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
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)) {
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                if (row.group == ScoreboardGroup.LIVE) SportsLiveStatePill(label = stringResource(R.string.sports_live_live_pill), isLive = true)
                if (previewShowing) {
                    Text(
                        text = stringResource(R.string.sports_live_scoreboard_preview_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1
                    )
                }
            }
            val shown = details as? ScoreDetails.Shown
            Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when (details) {
                        is ScoreDetails.Shown -> ScoreDetailsBuilder.scorelineText(details, detailStrings)
                        is ScoreDetails.Hidden -> chipStrings.hidden
                        is ScoreDetails.Status -> details.text ?: chipStrings.postponed
                        null -> item.meta.name
                    },
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                val clock = when {
                    row.probablyEnded -> stringResource(R.string.sports_live_probably_ended)
                    shown != null -> shown.status ?: chipStrings.finalNote.takeIf { !shown.live }
                    else -> null
                }
                if (clock != null) {
                    Text(
                        text = clock,
                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                        color = if (shown?.live == true) ArtColors.LiveText else NuvioTheme.colors.TextSecondary,
                        maxLines = 1
                    )
                }
            }
            // The S5 block's linescore and soccer scorers (the scoreline is the big line above).
            if (shown != null) SportsLiveScoreDetails(details = shown, showScoreline = false)
            if (situation.isNotEmpty()) {
                Text(
                    text = situation,
                    style = MaterialTheme.typography.titleSmall,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (noStream) {
                Text(
                    text = stringResource(R.string.sports_live_no_streams_now),
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 1
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
            SportsLiveBoardButtons(
                buttons = buttons,
                onWatch = onWatch,
                onWatchWith = onWatchWith,
                onGameStats = onGameStats,
                noStream = noStream,
                watchFocusRequester = watchFocusRequester,
                watchModifier = Modifier
                    .focusRequester(watchFocusRequester)
                    .then(back),
                otherModifier = Modifier.focusProperties { blockFocusRequester?.let { left = it } }
            )
        }
    }
}

/** [ms] as a local time of day ("6:00 PM"), in the device's 12/24-hour style. */
@Composable
private fun rememberLocalTime(ms: Long): String {
    val locale = Locale.getDefault()
    return remember(ms, locale) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
    }
}

private const val HOUR_MS = 3_600_000L
private const val SCROLL_MS = 250
private const val FINAL_ALPHA = 0.6f
private const val NOW_LINE_PX = 4f
private const val TICK_LABEL_INSET_PX = 6
private const val NOW_TAG_SHIFT_PX = 24
/** 220 px at 1080p. */
private val LABEL_WIDTH: Dp = 110.dp
private val RULER_HEIGHT: Dp = 24.dp
private val LANE_HEIGHT: Dp = 52.dp
private val BLOCK_GAP: Dp = 6.dp
private val UNTIMED_WIDTH: Dp = 200.dp
/** A block this wide shows full team names; a narrower one their abbreviations. */
private val WIDE_BLOCK: Dp = 240.dp
/** Room the block's words keep when they slide right to stay on screen. */
private val MIN_STICKY_WIDTH: Dp = 120.dp
private val FOLLOWED_GOLD = Color(0xFFFFC107)
private val UPCOMING_OUTLINE = Color(0xFF2A3442)
private val LIVE_BLOCK_SELECTED = Color(0xFF3A171B)
