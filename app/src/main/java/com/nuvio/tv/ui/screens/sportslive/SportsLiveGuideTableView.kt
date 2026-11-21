package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.SportsLiveReadyMark
import com.nuvio.tv.core.sportslive.SportsLiveSportKind
import com.nuvio.tv.core.sportslive.SportsLiveTags
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.TeamLogoBook
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors
import kotlin.math.roundToInt

/**
 * The in-player guide, design B2 (live.21, docs/design/mockups/in-player-guide-B2-simple.html): the video sits in its
 * window at the top right ([sportsLiveGuideSqueeze]), the title, the sport chips and the pinned **Now playing** at the
 * top left, and a full-width table of [GUIDE_VISIBLE_ROWS] games below: the dimmed league tag, both teams with their
 * logos and full names (a shorter form when one does not fit, never "…"), a mark only when it matters, and "97–92 ·
 * Q4 2:31" in one column. No column or section headers. One footer line: "OK Watch · ◂ ▸ Sport · Back Close".
 *
 * The guide holds focus itself and moves its own selection ([SportsLiveGuideKeys]): ▲ ▼ rows, CH± a page, ◂ ▸ the
 * sport chip, OK watches the selected game, Hold OK its actions. Back is the player's (it closes the guide). [state]
 * is the panel's state, its rules unchanged (frozen order, chips, Ready, scores mode, spoilers); null while it loads.
 */
@Composable
internal fun SportsLiveGuideTable(
    state: SportsLiveZapPanelState?,
    readiness: Map<String, SportsLiveReadyMark>,
    /** The guide is open (false while it fades out): only then is focus asked for. */
    open: Boolean,
    /** The Hold OK menu is up: focus is its own meanwhile. */
    menuOpen: Boolean,
    /** The marks' legend shows in the footer (the profile's first few openings, or Info). */
    keysHint: Boolean,
    onPick: (SportsLiveItem) -> Unit,
    onLongPick: (SportsLiveItem) -> Unit,
    onChip: (String?) -> Unit,
    onRowFocus: (String?) -> Unit,
    onKey: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val guide = state?.guide
    val lines = remember(guide) { guide?.let(::guideLines).orEmpty() }
    val keys = remember(lines) { lines.map { it.key } }
    val nowRow = guide?.nowPlaying ?: state?.nowPlayingRow
    // The row the user moved to, by key (null: the opening row, the game ▼ would zap to), and where it last was.
    var chosenKey by remember { mutableStateOf<String?>(null) }
    var lastIndex by remember { mutableIntStateOf(0) }
    var windowStart by remember { mutableIntStateOf(0) }
    val openingKey = remember(lines, guide) { guide?.let { guideOpeningKey(lines, it.rows, state?.currentKey) } }
    val index = guideReselect(keys, chosenKey ?: openingKey, lastIndex)
    val start = guideWindowStart(windowStart, index, keys.size)
    SideEffect {
        if (index >= 0) lastIndex = index
        windowStart = start
    }
    val selectedLine = lines.getOrNull(index)
    // Hide until focused and the look-ahead follow the selected game.
    val currentOnRowFocus by rememberUpdatedState(onRowFocus)
    val selectedGame = (selectedLine as? PanelGuideEntry.Game)?.key
    LaunchedEffect(selectedGame, open) { if (open) currentOnRowFocus(selectedGame) }

    // The guide keeps focus while open (a request in its first frames can come to nothing; a menu gives it back).
    val focusRequester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(open, menuOpen, hasFocus) {
        if (!open || menuOpen || hasFocus) return@LaunchedEffect
        repeat(GUIDE_FOCUS_FRAMES) {
            runCatching { focusRequester.requestFocus() }
            withFrameNanos { }
            if (hasFocus) return@LaunchedEffect
        }
    }
    val guideKeys = remember { SportsLiveGuideKeys() }
    fun select(to: Int) {
        keys.getOrNull(to)?.let {
            chosenKey = it
            lastIndex = to
        }
    }
    fun stepChip(delta: Int) {
        val shown = state ?: return
        val next = guideChipStep(shown.chips, shown.chip, delta) ?: return
        // A new list opens on its own opening row, from its top.
        chosenKey = null
        lastIndex = 0
        windowStart = 0
        onChip(shown.chips[next])
    }

    val feedFormat = stringResource(R.string.sports_live_panel_feed)
    val suffixes = remember(state?.rows, nowRow, feedFormat) {
        panelTitleSuffixes((listOfNotNull(nowRow) + state?.rows.orEmpty()).map { it.item }, { n -> String.format(feedFormat, n) })
    }
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // A solid backing everywhere but the video's window (B2: nothing is drawn over the picture).
            .drawBehind {
                val window = guideVideoWindow(size.width, size.height)
                clipRect(window.left, window.top, window.right, window.bottom, clipOp = ClipOp.Difference) {
                    drawRect(GUIDE_BACKGROUND)
                }
                drawRect(
                    color = GUIDE_WINDOW_OUTLINE,
                    topLeft = Offset(window.left, window.top),
                    size = Size(window.width, window.height),
                    style = Stroke(width = 2f)
                )
            }
            .onKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action == android.view.KeyEvent.ACTION_DOWN) onKey(native.keyCode)
                when (guideKeys.onKey(native.keyCode, native.action, native.repeatCount, native.eventTime)) {
                    SportsLiveGuideKey.PASS -> false
                    SportsLiveGuideKey.CONSUMED -> true
                    SportsLiveGuideKey.UP -> true.also { select(guideMove(index, -1, keys.size)) }
                    SportsLiveGuideKey.DOWN -> true.also { select(guideMove(index, 1, keys.size)) }
                    SportsLiveGuideKey.PAGE_UP -> true.also { select(guideMove(index, -GUIDE_VISIBLE_ROWS, keys.size)) }
                    SportsLiveGuideKey.PAGE_DOWN -> true.also { select(guideMove(index, GUIDE_VISIBLE_ROWS, keys.size)) }
                    SportsLiveGuideKey.PREVIOUS_SPORT -> true.also { stepChip(-1) }
                    SportsLiveGuideKey.NEXT_SPORT -> true.also { stepChip(1) }
                    SportsLiveGuideKey.WATCH -> true.also {
                        when (val line = selectedLine) {
                            is PanelGuideEntry.Game -> onPick(line.row.item)
                            is PanelGuideEntry.HiddenByMySports -> stepToAllSports(state, onChip)
                            else -> Unit
                        }
                    }
                    SportsLiveGuideKey.MORE -> true.also {
                        (selectedLine as? PanelGuideEntry.Game)?.let { onLongPick(it.row.item) }
                    }
                }
            }
            .focusRequester(focusRequester)
            .onFocusChanged { hasFocus = it.isFocused }
            .focusable()
    ) {
        val window = guideVideoWindow(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val windowLeft = with(density) { window.left.toDp() }
        val tableTop = with(density) { window.bottom.toDp() } + GUIDE_TABLE_GAP
        // Top left: the title, the sport chips and Now playing, clear of the video's window.
        Column(
            modifier = Modifier
                .padding(start = GUIDE_SAFE_START, top = SportsLiveTokens.Size.SafeTop)
                .width((windowLeft - GUIDE_SAFE_START - GUIDE_WINDOW_GAP).coerceAtLeast(0.dp))
        ) {
            Text(
                text = stringResource(R.string.sports_live_guide_title),
                style = SportsLiveTokens.style(GuideType.Title),
                color = Colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(14.dp))
            state?.let { GuideChips(it) }
            Spacer(modifier = Modifier.height(22.dp))
            nowRow?.let { row ->
                val board = boardOf(state, row)
                GuideNowPlaying(rememberGuideRow(board, ready = null, nowPlaying = true, suffix = suffixes[row.item.streamKey]), board.art)
            }
        }
        // The table: five rows under the video, the selected one ringed.
        Column(
            modifier = Modifier
                .padding(top = tableTop, start = GUIDE_TABLE_START, end = GUIDE_TABLE_START)
                .fillMaxWidth()
        ) {
            if (lines.isEmpty()) {
                Text(
                    text = stringResource(
                        if (state == null || state.loading) R.string.sports_live_zap_panel_loading else R.string.sports_live_zap_panel_empty
                    ),
                    style = SportsLiveTokens.style(GuideType.Name),
                    color = Colors.TextSecondary,
                    modifier = Modifier.padding(start = GUIDE_ROW_PADDING_START, top = 8.dp)
                )
            }
            val end = minOf(start + GUIDE_VISIBLE_ROWS, lines.size)
            for (at in start until end) {
                val selected = at == index
                when (val line = lines[at]) {
                    is PanelGuideEntry.Game -> {
                        val board = boardOf(state, line.row)
                        val row = rememberGuideRow(
                            board,
                            ready = readiness[line.key],
                            nowPlaying = false,
                            gone = line.row.gone,
                            isNew = line.row.isNew,
                            suffix = suffixes[line.key]
                        )
                        GuideTableRow(row, board.art, selected)
                    }
                    is PanelGuideEntry.HiddenByMySports -> GuideHiddenRow(line.count, selected)
                    is PanelGuideEntry.Header -> Unit
                }
                if (at < end - 1) GuideRowGap()
            }
        }
        GuideFooter(
            legend = keysHint,
            moreBelow = (lines.size - (start + GUIDE_VISIBLE_ROWS)).coerceAtLeast(0),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = GUIDE_TABLE_START + GUIDE_ROW_PADDING_START, end = GUIDE_TABLE_START + GUIDE_ROW_PADDING_END, bottom = SportsLiveTokens.Size.SafeBottom)
        )
    }
}

/** OK on "N more hidden by My sports · Show all": the guide switches to All sports. */
private fun stepToAllSports(state: SportsLiveZapPanelState?, onChip: (String?) -> Unit) {
    if (state != null) onChip(SportsLiveUiState.ALL_SPORTS_FILTER)
}

/** [row]'s Scoreboard row, as the panel draws it. */
private fun boardOf(state: SportsLiveZapPanelState?, row: ZapPanelRow): ScoreboardRow =
    state?.boards?.get(row.item.streamKey)?.board ?: panelBoards(listOf(row), emptyMap(), null, emptySet(), 0L).single().board

/** [board] as a guide row ([guideRowOf]) with the screen's words: status, kickoff, the short tag and the score's format. */
@Composable
private fun rememberGuideRow(
    board: ScoreboardRow,
    ready: SportsLiveReadyMark?,
    nowPlaying: Boolean,
    gone: Boolean = false,
    isNew: Boolean = false,
    suffix: String? = null
): GuideRow {
    val strings = rememberGameStatusStrings()
    val kickoff = board.art?.let { kickoffText(it) }
    val sport = remember(board) { board.sportTag ?: SportsLiveSportKind.plainTagOf(board.item) }
    val tag = SportsLiveTags.short(rowTagText(board), sport)
    val individual = sport in PANEL_INDIVIDUAL_SPORTS
    val scoreFormat = stringResource(R.string.sports_live_chip_score)
    return remember(board, strings, kickoff, tag, ready, nowPlaying, gone, isNew, suffix, individual, scoreFormat) {
        guideRowOf(
            board = board,
            strings = strings,
            kickoff = kickoff,
            tag = tag,
            readyMark = ready,
            nowPlaying = nowPlaying,
            gone = gone,
            isNew = isNew,
            suffix = suffix,
            individual = individual,
            codeOf = { name -> TeamLogoBook.shared.abbreviationAnywhere(name) },
            scoreText = { first, second -> String.format(scoreFormat, first, second) }
        )
    }
}

/** The sport chips (B2: "★ My sports", "All 25", then each sport): ◂ ▸ step through them; the picked one is filled. */
@Composable
private fun GuideChips(state: SportsLiveZapPanelState) {
    val chips = state.chips
    if (chips.size <= 1) return
    val selected = chips.indexOf(state.chip).coerceAtLeast(0)
    val window = guideChipWindow(chips.size, selected)
    val scoped = SportsLiveUiState.ALL_SPORTS_FILTER in chips
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        if (window.first > 0) GuideChipArrow("◂")
        for (at in window) {
            val chip = chips[at]
            val name = when (chip) {
                null -> if (scoped) "★ " + stringResource(R.string.sports_live_filter_my_sports) else stringResource(R.string.sports_live_filter_all)
                SportsLiveUiState.MY_TEAMS_FILTER -> "★ " + stringResource(R.string.sports_live_filter_my_teams)
                SportsLiveUiState.ALL_SPORTS_FILTER -> stringResource(R.string.sports_live_filter_all_sports)
                else -> sportFilterLabel(chip)
            }
            // My sports reads best bare (the mockup); every other chip carries its count of live games.
            val count = state.counts[chip]?.takeIf { !(chip == null && scoped) }
            GuideChip(
                text = if (count != null) "$name $count" else name,
                selected = at == selected,
                gold = chip == SportsLiveUiState.MY_TEAMS_FILTER
            )
        }
        if (window.last < chips.lastIndex) GuideChipArrow("▸")
    }
}

@Composable
private fun GuideChip(text: String, selected: Boolean, gold: Boolean) {
    val shape = RoundedCornerShape(GUIDE_CHIP_HEIGHT / 2)
    Box(
        modifier = Modifier
            .height(GUIDE_CHIP_HEIGHT)
            .clip(shape)
            .background(if (selected) Colors.TextPrimary else GUIDE_CHIP_FILL)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = SportsLiveTokens.style(GuideType.Chip),
            color = when {
                selected -> Colors.Background
                gold -> Colors.FollowedGold
                else -> Colors.TextPrimary
            },
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun GuideChipArrow(text: String) {
    Text(text = text, style = SportsLiveTokens.style(GuideType.Chip), color = Colors.TextMuted, maxLines = 1)
}

/** NOW PLAYING: the game playing on two lines (one team each, or its title), its marks and score · time at the end. */
@Composable
private fun GuideNowPlaying(row: GuideRow, art: GameArt?) {
    Text(
        text = stringResource(R.string.sports_live_panel_now_playing),
        style = SportsLiveTokens.style(GuideType.Label),
        color = Colors.TextMuted,
        maxLines = 1
    )
    Spacer(modifier = Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(GUIDE_NOW_HEIGHT)
                .background(Colors.FocusWhite, RoundedCornerShape(2.dp))
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            when (row.kind) {
                GuideRowKind.GAME -> {
                    val logos = rememberTeamLogoPair(art)?.takeIf { !it.failed }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GuideTeam(logos?.first, logos?.shown == true, row.firstNames, GuideType.NowName, GUIDE_NOW_LOGO, Modifier.weight(1f, fill = false))
                        GuideMarks(row.marks, isNew = false)
                    }
                    GuideTeam(logos?.second, logos?.shown == true, row.secondNames, GuideType.NowName, GUIDE_NOW_LOGO)
                }
                GuideRowKind.TITLE -> Row(verticalAlignment = Alignment.CenterVertically) {
                    GuideTitle(row, GuideType.NowName, maxLines = 2, modifier = Modifier.weight(1f, fill = false))
                    GuideMarks(row.marks, isNew = false)
                }
            }
        }
        GuideScoreTime(row, GuideType.NowScore, Modifier.padding(start = 12.dp))
    }
}

/** A game row of the table (B2): tag · first team · second team and its marks · score · time. */
@Composable
private fun GuideTableRow(row: GuideRow, art: GameArt?, selected: Boolean) {
    val shape = RoundedCornerShape(GUIDE_ROW_RADIUS)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(GUIDE_ROW_HEIGHT)
            .then(if (selected) Modifier.background(GUIDE_SELECTED_FILL, shape).border(2.dp, Colors.FocusWhite, shape) else Modifier)
            .alpha(if (row.dimmedAtRest && !selected) GUIDE_DIMMED_ALPHA else 1f)
            .padding(start = GUIDE_ROW_PADDING_START, end = GUIDE_ROW_PADDING_END),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = row.tag,
            style = SportsLiveTokens.style(GuideType.Tag),
            color = Colors.TextMuted,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(GUIDE_TAG_WIDTH)
        )
        when (row.kind) {
            GuideRowKind.GAME -> {
                val logos = rememberTeamLogoPair(art)?.takeIf { !it.failed }
                GuideTeam(logos?.first, logos?.shown == true, row.firstNames, GuideType.Name, GUIDE_LOGO, Modifier.width(GUIDE_TEAM_WIDTH))
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    GuideTeam(logos?.second, logos?.shown == true, row.secondNames, GuideType.Name, GUIDE_LOGO, Modifier.weight(1f, fill = false))
                    GuideMarks(row.marks, row.isNew)
                }
            }
            GuideRowKind.TITLE -> Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                // An event or a channel starts where the team names do.
                Spacer(modifier = Modifier.width(GUIDE_LOGO + GUIDE_LOGO_GAP))
                GuideTitle(row, GuideType.Name, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                GuideMarks(row.marks, row.isNew)
            }
        }
        GuideScoreTime(row, GuideType.Score, Modifier.padding(start = 12.dp))
    }
}

/** "N more hidden by My sports · Show all" as the table's last line: OK switches to All sports. */
@Composable
private fun GuideHiddenRow(count: Int, selected: Boolean) {
    val shape = RoundedCornerShape(GUIDE_ROW_RADIUS)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(GUIDE_ROW_HEIGHT)
            .then(if (selected) Modifier.background(GUIDE_SELECTED_FILL, shape).border(2.dp, Colors.FocusWhite, shape) else Modifier)
            .padding(start = GUIDE_ROW_PADDING_START + GUIDE_TAG_WIDTH, end = GUIDE_ROW_PADDING_END),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = stringResource(R.string.sports_live_hidden_by_my_sports, count),
            style = SportsLiveTokens.style(GuideType.Name),
            color = if (selected) Colors.TextPrimary else Colors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** The gap between rows, with a hairline. */
@Composable
private fun GuideRowGap() {
    Box(modifier = Modifier.fillMaxWidth().height(GUIDE_ROW_GAP), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .padding(horizontal = GUIDE_ROW_PADDING_START)
                .fillMaxWidth()
                .height(1.dp)
                .background(GUIDE_DIVIDER)
        )
    }
}

/**
 * A team: its logo (the slot kept when there is none, so every name starts at one x) and the longest of its [names]
 * that fits ([PanelFirstFit]): the full name, the short name, then the code.
 */
@Composable
private fun GuideTeam(logo: Painter?, shown: Boolean, names: List<String>, style: TextStyle, logoSize: Dp, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(logoSize), contentAlignment = Alignment.Center) {
            if (logo != null) TeamLogoSlot(logo, shown, logoSize)
        }
        Spacer(modifier = Modifier.width(GUIDE_LOGO_GAP))
        PanelFirstFit(modifier = Modifier.weight(1f, fill = false)) {
            names.ifEmpty { listOf("") }.forEach { name ->
                Text(
                    text = name,
                    style = SportsLiveTokens.style(style),
                    color = Colors.TextPrimary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** An event's or a channel's title, then (muted) what tells it from a row of the same title. */
@Composable
private fun GuideTitle(row: GuideRow, style: TextStyle, maxLines: Int, modifier: Modifier = Modifier) {
    Text(
        text = buildAnnotatedString {
            append(row.title.orEmpty())
            row.suffix?.let { suffix ->
                withStyle(SpanStyle(color = Colors.TextMuted)) { append(PANEL_SUFFIX_SEPARATOR + suffix) }
            }
        },
        style = SportsLiveTokens.style(style),
        color = Colors.TextPrimary,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/** The marks after the names (B2): amber stopwatch, gold ★, the green Ready dot; then "New" for a row added since opening. */
@Composable
private fun GuideMarks(marks: List<GuideMark>, isNew: Boolean) {
    if (marks.isEmpty() && !isNew) return
    Row(
        modifier = Modifier.padding(start = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        marks.forEach { mark -> GuideMarkIcon(mark) }
        if (isNew) {
            Text(
                text = stringResource(R.string.sports_live_panel_new),
                style = SportsLiveTokens.style(GuideType.Tag),
                color = Colors.TextSecondary,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun GuideMarkIcon(mark: GuideMark) {
    when (mark) {
        GuideMark.CLOSE_LATE -> Icon(
            imageVector = Icons.Filled.Timer,
            contentDescription = stringResource(R.string.sports_live_guide_legend_close),
            tint = GUIDE_CLOSE_AMBER,
            modifier = Modifier.size(15.dp)
        )
        GuideMark.MY_TEAM -> Icon(
            imageVector = Icons.Rounded.Star,
            contentDescription = stringResource(R.string.sports_live_guide_legend_my_team),
            tint = Colors.FollowedGold,
            modifier = Modifier.size(15.dp)
        )
        GuideMark.READY -> Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(GUIDE_READY_GREEN))
    }
}

/** "97–92 · Q4 2:31": the score in bold, the time dimmed; the time alone when no score shows. Never cut. */
@Composable
private fun GuideScoreTime(row: GuideRow, style: TextStyle, modifier: Modifier = Modifier) {
    if (row.score == null && row.time == null) return
    Text(
        text = buildAnnotatedString {
            row.score?.let { score ->
                withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold, color = Colors.TextPrimary)) { append(score) }
            }
            row.time?.let { time ->
                withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = Colors.TextMuted)) {
                    if (row.score != null) append(GUIDE_SCORE_TIME_SEPARATOR)
                    append(time)
                }
            }
        },
        style = SportsLiveTokens.style(style).copy(fontFeatureSettings = "tnum"),
        maxLines = 1,
        softWrap = false,
        modifier = modifier
    )
}

/** The one footer line: the keys; the marks' legend while the keys hint shows; how many rows wait below. */
@Composable
private fun GuideFooter(legend: Boolean, moreBelow: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.sports_live_guide_footer),
            style = SportsLiveTokens.style(GuideType.Footer),
            color = Colors.TextMuted,
            maxLines = 1,
            softWrap = false
        )
        if (legend) {
            Spacer(modifier = Modifier.width(40.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                GuideLegendItem(GuideMark.CLOSE_LATE, R.string.sports_live_guide_legend_close)
                GuideLegendItem(GuideMark.MY_TEAM, R.string.sports_live_guide_legend_my_team)
                GuideLegendItem(GuideMark.READY, R.string.sports_live_guide_legend_ready)
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        if (moreBelow > 0) {
            Text(
                text = stringResource(R.string.sports_live_guide_more_below, moreBelow),
                style = SportsLiveTokens.style(GuideType.Footer),
                color = Colors.TextMuted,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun GuideLegendItem(mark: GuideMark, text: Int) {
    GuideMarkIcon(mark)
    Text(
        text = stringResource(text),
        style = SportsLiveTokens.style(GuideType.Footer),
        color = Colors.TextMuted,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.padding(end = 10.dp)
    )
}

/**
 * The video container's squeeze while the new guide is open (B2), for the player's video box (one seam in
 * PlayerScreen): the same player and surface, never re-attached. [GuideSqueezeMode.SCALE] animates a graphics-layer
 * scale about the top-right corner and a move into the safe area over [GUIDE_SQUEEZE_MS], with no layout change, so
 * the surface keeps its buffer size; [GuideSqueezeMode.JUMP] lays the box out in the window at once (one resize).
 * Nothing for a player with no zap session.
 */
@Composable
fun sportsLiveGuideSqueeze(zap: SportsLiveZapViewModel): Modifier {
    if (!zap.hasSession) return Modifier
    val style by zap.guideStyle.collectAsState()
    val open by zap.panelOpen.collectAsState()
    // The setting may change between openings; the animation state below is kept for either.
    val mode = guideSqueezeModeOf(style)
    val squeezed = mode != null && open
    val progress = remember { Animatable(0f) }
    return when (mode) {
        null -> Modifier
        GuideSqueezeMode.SCALE -> {
            LaunchedEffect(squeezed) {
                progress.animateTo(if (squeezed) 1f else 0f, tween(GUIDE_SQUEEZE_MS, easing = FastOutSlowInEasing))
            }
            // The layer only while squeezed or on its way back: full-screen playback keeps no extra layer.
            val layered by remember { derivedStateOf { progress.value > 0f } }
            if (squeezed || layered) {
                Modifier.graphicsLayer {
                    val transform = guideSqueezeTransform(progress.value, size.width, size.height)
                    scaleX = transform.scale
                    scaleY = transform.scale
                    translationX = transform.translationX
                    translationY = transform.translationY
                    transformOrigin = TransformOrigin(1f, 0f)
                }
            } else {
                Modifier
            }
        }
        GuideSqueezeMode.JUMP -> if (squeezed) {
            Modifier.layout { measurable, constraints ->
                if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
                    val placeable = measurable.measure(constraints)
                    return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                val window = guideVideoWindow(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
                val placeable = measurable.measure(Constraints.fixed(window.width.roundToInt(), window.height.roundToInt()))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(window.left.roundToInt(), window.top.roundToInt())
                }
            }
        } else {
            Modifier
        }
    }
}

/** The guide's type (1080p px ÷ 2, B2 mockup): nothing under 26 px, names 32 px, Now playing 34 px. */
private object GuideType {
    val Title = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
    val Chip = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
    val Label = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    val NowName = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val NowScore = TextStyle(fontSize = 17.sp, lineHeight = 22.sp)
    val Name = TextStyle(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val Score = TextStyle(fontSize = 15.sp, lineHeight = 20.sp)
    val Tag = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold)
    val Footer = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
}

private val GUIDE_BACKGROUND = Colors.Background
private val GUIDE_WINDOW_OUTLINE = Color.White.copy(alpha = 0.19f)
private val GUIDE_SELECTED_FILL = Color(0xFF26303E)
private val GUIDE_CHIP_FILL = Color.White.copy(alpha = 0.07f)
private val GUIDE_DIVIDER = Color.White.copy(alpha = 0.06f)
private val GUIDE_CLOSE_AMBER = Color(0xFFF5A524)
private val GUIDE_READY_GREEN = Color(0xFF2EBD6B)
private const val GUIDE_DIMMED_ALPHA = 0.6f
private const val GUIDE_SCORE_TIME_SEPARATOR = "  ·  "
private const val GUIDE_FOCUS_FRAMES = 30

/** The safe area's left edge (5 %, 96 px) and the gap kept before the video's window. */
private val GUIDE_SAFE_START = 48.dp
private val GUIDE_WINDOW_GAP = 24.dp

/** The table: 9 dp under the window, 42 dp from the edges, rows 40 dp on a 46 dp pitch (B2: 80 px rows, 92 px pitch). */
private val GUIDE_TABLE_GAP = 9.dp
private val GUIDE_TABLE_START = 42.dp
private val GUIDE_ROW_HEIGHT = 40.dp
private val GUIDE_ROW_GAP = 6.dp
private val GUIDE_ROW_RADIUS = 7.dp
private val GUIDE_ROW_PADDING_START = 13.dp
private val GUIDE_ROW_PADDING_END = 15.dp

/** The tag column (152 px) and the first team's column (538 px: logo, gap and a ~460 px name). */
private val GUIDE_TAG_WIDTH = 76.dp
private val GUIDE_TEAM_WIDTH = 269.dp
private val GUIDE_LOGO = 24.dp
private val GUIDE_LOGO_GAP = 7.dp
private val GUIDE_NOW_LOGO = 26.dp
private val GUIDE_NOW_HEIGHT = 50.dp
private val GUIDE_CHIP_HEIGHT = 28.dp
