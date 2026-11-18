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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
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
    /** The league rows' scroll, owned by the screen (its refocus after a removal scrolls it too). */
    rowState: LazyListState,
    selectedKey: String,
    menuKey: String?,
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

        // Keeps the focused block in the window, by the same span it is drawn with.
        fun reveal(block: TimelineBlock) {
            val target = SportsLiveTimeline.revealTarget(
                xOf(block.startMs), xOf(block.endMs), scroll.value, viewportPx, hourPx, minScroll, maxScroll
            ) ?: return
            scope.launch { scroll.animateTo(target, tween(SCROLL_MS)) }
        }

        // ← on a row's first block, → on its last: the window moves on by half its width, keeping a
        // part of the focused block on screen.
        fun scrollPast(block: TimelineBlock, move: TimelineMove) {
            val target = SportsLiveTimeline.edgeScroll(move, xOf(block.startMs), xOf(block.endMs), scroll.value, viewportPx)
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
        // The now tag's width, measured: an hour label it would cover is not drawn (N7).
        var nowTagWidth by remember { mutableFloatStateOf(0f) }
        fun nowTagLeft(): Float = SportsLiveTimeline.nowTagLeft(nowLineX, scroll.value, NOW_TAG_SHIFT_PX.toFloat(), nowTagWidth, viewportPx)
        // The window's time span, to the minute: only the edge hints read it, so scrolling recomposes them alone.
        val windowStart = remember(windowKey, pxPerMs) {
            derivedStateOf { originMs + (scroll.value / pxPerMs).toLong() / MINUTE_MS * MINUTE_MS }
        }

        // Fast-forward / rewind, or holding ← →: the window jumps 3 h and focus goes to the game nearest
        // that time (#77). A held key jumps once per press.
        var jumpHeld by remember { mutableStateOf(false) }
        fun jump(fromKey: String, forward: Boolean) {
            val target = SportsLiveTimeline.jumpScroll(scroll.value, forward, hourPx, minScroll, maxScroll)
            // The window moves first, so the new focus's reveal measures against where it now is.
            scope.launch {
                scroll.snapTo(target)
                SportsLiveTimeline.jumpTarget(currentModel, fromKey, forward)?.let(::focus)
            }
        }

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
                            // Read at draw time, so scrolling never recomposes the ruler.
                            .drawWithContent {
                                val left = tickXs[index] - scroll.value + TICK_LABEL_INSET_PX
                                val covered = nowTagWidth > 0f &&
                                    SportsLiveTimeline.spansOverlap(left, size.width, nowTagLeft(), nowTagWidth, NOW_TAG_GAP_PX)
                                if (!covered) drawContent()
                            }
                    )
                }
                Text(
                    text = nowLabel,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .onSizeChanged { nowTagWidth = it.width.toFloat() }
                        // Kept whole inside the ruler while the now line is in the window (N7).
                        .offset { IntOffset(nowTagLeft().roundToInt(), 0) }
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
                        // The "No time" strip is not on the ruler: no hour or now line crosses it (N8).
                        val bottom = rowState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.key == UNTIMED_KEY }
                            ?.offset?.toFloat()?.coerceIn(0f, size.height) ?: size.height
                        tickXs.forEach { x ->
                            val at = left + x - offset
                            if (at >= left && at <= size.width) drawLine(gridColor, Offset(at, 0f), Offset(at, bottom), 1f)
                        }
                        drawContent()
                        val now = left + nowLineX - offset
                        if (now >= left && now <= size.width) drawLine(ArtColors.LiveRed, Offset(now, 0f), Offset(now, bottom), NOW_LINE_PX)
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
                            if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount == 0) jumpHeld = false
                            when (val jumpKey = timelineJumpKey(native.keyCode, native.action, native.repeatCount, jumpHeld)) {
                                null -> Unit
                                TimelineJumpKey.CONSUME -> return@onPreviewKeyEvent true
                                TimelineJumpKey.RELEASE -> jumpHeld = false
                                TimelineJumpKey.FORWARD, TimelineJumpKey.BACK -> {
                                    val from = focusedKey ?: return@onPreviewKeyEvent false
                                    if (native.repeatCount > 0) jumpHeld = true
                                    jump(from, forward = jumpKey == TimelineJumpKey.FORWARD)
                                    return@onPreviewKeyEvent true
                                }
                            }
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
                                else -> currentModel.blocks[from]?.let { scrollPast(it, move) }
                            }
                            true
                        },
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
                ) {
                    itemsIndexed(model.leagues, key = { _, league -> "league|${league.id}" }) { _, league ->
                        val rowKey = "league|${league.id}"
                        Row(verticalAlignment = Alignment.Top) {
                            // Kept in view while a tall row's top is scrolled away (QA pass 3), within the row;
                            // read at layout, so scrolling never recomposes it.
                            LeagueLabel(
                                name = league.name,
                                followed = league.followed,
                                modifier = Modifier.offset {
                                    val info = rowState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == rowKey }
                                    val room = (info?.size ?: 0) - (LANE_HEIGHT - BLOCK_GAP).roundToPx()
                                    IntOffset(0, (-(info?.offset ?: 0)).coerceIn(0, maxOf(0, room)))
                                }
                            )
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(LANE_HEIGHT * league.lanes)
                                    .clipToBounds()
                            ) {
                                // Nothing of this row in the window: where its games are (#77).
                                TimelineRowHintChip(
                                    league = league,
                                    windowStartMs = { windowStart.value },
                                    windowEndMs = { windowStart.value + currentModel.viewMs },
                                    modifier = Modifier.fillMaxSize()
                                )
                                league.blocks.forEach { block ->
                                    val item = block.row.item
                                    val blockX = xOf(block.startMs)
                                    val blockRight = xOf(block.endMs)
                                    val widthPx = (blockRight - blockX).coerceAtLeast(1f)
                                    val laneY = with(density) { (LANE_HEIGHT * block.lane).roundToPx() }
                                    val blockHeight = with(density) { (LANE_HEIGHT - BLOCK_GAP).roundToPx() }
                                    TimelineBlockCard(
                                        block = block,
                                        selected = block.key == selectedKey,
                                        menuOpen = block.key == menuKey,
                                        wide = with(density) { widthPx.toDp() } >= WIDE_BLOCK,
                                        onClick = { onClick(item) },
                                        onLongClick = { onLongClick(item) },
                                        onFocus = {
                                            focusedKey = block.key
                                            reveal(block)
                                            onFocus(item)
                                        },
                                        modifier = Modifier
                                            // Laid out as the part inside the window (read at layout, so
                                            // scrolling never recomposes): a block longer than the window is
                                            // never squeezed to the row's width and pushed out of view, and
                                            // its words stay at the visible part's left.
                                            .layout { measurable, constraints ->
                                                val offset = scroll.value
                                                val (from, to) = SportsLiveTimeline.visibleSpan(blockX, blockRight, offset, viewportPx)
                                                val width = (to - from).roundToInt().coerceIn(1, maxOf(1, constraints.maxWidth))
                                                val placeable = measurable.measure(Constraints.fixed(width, blockHeight))
                                                layout(width, blockHeight) { placeable.place((from - offset).roundToInt(), laneY) }
                                            }
                                            .focusRequester(cardFocusRequester(item))
                                    )
                                }
                            }
                        }
                    }
                    if (model.untimed.isNotEmpty()) {
                        // A strip of its own under the ruler's rows (N8): no hour or now line crosses it,
                        // and its channels are tiles with a TV mark, not programmes on the clock.
                        item(key = UNTIMED_KEY) {
                            Row(
                                modifier = Modifier
                                    .padding(top = NuvioTheme.spacing.sm)
                                    .clip(RoundedCornerShape(NuvioTheme.radii.md))
                                    .background(ArtColors.Panel),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                LeagueLabel(stringResource(R.string.sports_live_timeline_no_time), followed = false)
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .horizontalScroll(rememberScrollState())
                                        // Room for the focused tile's ring at both ends of the scroll.
                                        .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xs),
                                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
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
private fun LeagueLabel(name: String, followed: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
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
    wide: Boolean,
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
                .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xxs),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs), verticalAlignment = Alignment.CenterVertically) {
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
        Row(
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xxs),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (row.item.isChannel) Icons.Rounded.LiveTv else Icons.Rounded.Event,
                contentDescription = null,
                tint = if (live) ArtColors.LiveText else ArtColors.Muted,
                modifier = Modifier.size(20.dp)
            )
            Column {
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
}

/**
 * "+2 later · 7:30 PM" at a league row's right end (or "3 earlier · 1:00 PM" at its left) while none of
 * its games is in the window (#77). Reads the window lazily, so only the hint recomposes as it moves.
 */
@Composable
private fun TimelineRowHintChip(
    league: TimelineLeague,
    windowStartMs: () -> Long,
    windowEndMs: () -> Long,
    modifier: Modifier = Modifier
) {
    val hint = SportsLiveTimeline.rowHint(league, windowStartMs(), windowEndMs()) ?: return
    val time = rememberLocalTime(hint.atMs)
    Box(modifier = modifier, contentAlignment = if (hint.later) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(
            text = stringResource(
                if (hint.later) R.string.sports_live_timeline_later else R.string.sports_live_timeline_earlier,
                hint.count,
                time
            ),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
            color = ArtColors.Secondary,
            maxLines = 1,
            modifier = Modifier
                .padding(horizontal = NuvioTheme.spacing.sm)
                .clip(RoundedCornerShape(NuvioTheme.radii.sm))
                .background(ArtColors.Panel)
                .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xxs)
        )
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
    modifier: Modifier = Modifier,
    /**
     * → from the buttons reaches the mini player beside the panel when it plays; with nothing there it
     * stays put rather than jump to whatever lies furthest that way (N3).
     */
    miniPlayerBeside: Boolean = false,
    /** "Remind me" / "Cancel reminder" (#44) by [reminded]; null hides it (only upcoming games offer it). */
    onToggleReminder: (() -> Unit)? = null,
    reminded: Boolean = false
) {
    val item = row.item
    val chipStrings = rememberScoreChipStrings()
    val detailStrings = rememberScoreDetailsStrings()
    val back = Modifier.focusProperties {
        blockFocusRequester?.let {
            left = it
            up = it
        }
        if (!miniPlayerBeside) right = FocusRequester.Cancel
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
                        model = rememberSizedImage(item.meta.backdropUrl, PANE_ART_WIDTH / 2, PANE_ART_HEIGHT / 2),
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
                otherModifier = Modifier.focusProperties {
                    blockFocusRequester?.let { left = it }
                    if (!miniPlayerBeside) right = FocusRequester.Cancel
                }
            )
            if (onToggleReminder != null) {
                SportsLivePaneButton(
                    onClick = onToggleReminder,
                    modifier = Modifier
                        .widthIn(min = PANE_BUTTON_MIN_WIDTH)
                        .focusProperties {
                            blockFocusRequester?.let { left = it }
                            if (!miniPlayerBeside) right = FocusRequester.Cancel
                        }
                ) {
                    Icon(
                        imageVector = if (reminded) Icons.Rounded.NotificationsActive else Icons.Rounded.Notifications,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(NuvioTheme.spacing.xs))
                    PaneButtonLabel(stringResource(if (reminded) R.string.sports_live_cancel_reminder else R.string.sports_live_remind_me))
                }
            }
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
/** An hour label closer than this to the now tag is not drawn (N7). */
private const val NOW_TAG_GAP_PX = 4f
private const val MINUTE_MS = 60_000L
/** The "No time" strip's list key. */
private const val UNTIMED_KEY = "untimed"
/** 220 px at 1080p. */
private val LABEL_WIDTH: Dp = 110.dp
private val RULER_HEIGHT: Dp = 24.dp
private val LANE_HEIGHT: Dp = 52.dp
private val BLOCK_GAP: Dp = 6.dp
private val UNTIMED_WIDTH: Dp = 200.dp
/** A block this wide shows full team names; a narrower one their abbreviations. */
private val WIDE_BLOCK: Dp = 240.dp
private val FOLLOWED_GOLD = Color(0xFFFFC107)
private val UPCOMING_OUTLINE = Color(0xFF2A3442)
private val LIVE_BLOCK_SELECTED = Color(0xFF3A171B)
