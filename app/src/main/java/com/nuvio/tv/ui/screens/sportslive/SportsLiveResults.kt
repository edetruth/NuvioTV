package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.GameStatusText
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.scores.FinalNote
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.GolfResult
import com.nuvio.tv.core.sportslive.scores.ResultDay
import com.nuvio.tv.core.sportslive.scores.ResultRow
import com.nuvio.tv.core.sportslive.scores.ScoreSide
import com.nuvio.tv.core.sportslive.scores.ScoreTeam
import com.nuvio.tv.core.sportslive.scores.SportsResults
import com.nuvio.tv.core.sportslive.scores.TeamPages
import com.nuvio.tv.core.sportslive.scores.TeamRef
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButton
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButtonRole
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveFocus
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.dpadRepeatThrottle
import kotlinx.coroutines.launch
import java.time.LocalTime
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniPlayers
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniDock
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniFocus
import com.nuvio.tv.ui.screens.sportslive.mini.rememberSportsLiveMiniDock
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniState
import androidx.compose.runtime.collectAsState
import java.util.Locale

/** What an empty Today says (#78): until [LAST_NIGHT_UNTIL_HOUR] local, last night's games are under Yesterday. */
internal enum class ResultsTodayEmpty { LAST_NIGHT, NO_FINALS_YET }

/** Until this local hour an empty Today speaks of last night (#78). */
internal const val LAST_NIGHT_UNTIL_HOUR = 6

internal fun resultsTodayEmpty(localHour: Int): ResultsTodayEmpty =
    if (localHour < LAST_NIGHT_UNTIL_HOUR) ResultsTodayEmpty.LAST_NIGHT else ResultsTodayEmpty.NO_FINALS_YET

/** Whether the list offers "See yesterday's results" (#78): Today, with nothing listed. */
internal fun resultsOffersYesterday(daysAgo: Int?, hasRows: Boolean): Boolean = daysAgo == SportsResults.TODAY && !hasRows

/**
 * The Results tab (V2 P4): the day sits in the header's title ("Results · Yesterday ‹ ›", OK on an arrow changes it) and
 * the sport in its one dropdown, so the list starts right under the header. On Today a small "N games live
 * now →" line leads. The list (400 dp, league section headers, YOUR TEAMS first, long tails collapsed) uses
 * fixed columns as in Live Now, so a score sits right after its names; the stage on the right shows the
 * focused game's drawn matchup, its final score (spoiler rules applied), and **Watch replay** (a strict replay
 * match) and **More…** (Game page, Team page, Follow). OK on a row opens the Game page.
 */
@Composable
internal fun SportsLiveResultsView(
    state: SportsLiveResultsUiState,
    /** The active tab: Back from the list. */
    tabFocusRequester: FocusRequester,
    /** The header's date (the title): Up from the top of the content. */
    headerFocusRequester: FocusRequester,
    /** Where the header's Down lands: the live line, the list's entry row, or "See yesterday's results". */
    topFocusRequester: FocusRequester,
    onSelectDay: (Int) -> Unit,
    /** OK on "N games live now →": the Live Now tab. */
    onOpenLiveNow: () -> Unit,
    onExpandGroup: (String) -> Unit,
    onRowFocus: (String?) -> Unit,
    /** OK on a row: the Game page (#18). */
    onOpen: (ResultRow) -> Unit,
    /** The stage's Game stats: the Game page on its Box score. */
    onGameStats: (ResultRow) -> Unit,
    /** The stage's Watch replay: the row's strict replay match, played spoiler-safe. */
    onWatchReplay: (ResultRow, SportsLiveItem) -> Unit,
    /** Whether a followed team is [ScoreSide] of the row's game (the menu's Follow / Following). */
    isFollowed: (ResultRow, ScoreSide) -> Boolean = { _, _ -> false },
    /** Hold OK or More… → Follow / Following ‹team› (#54). */
    onToggleFollow: (ResultRow, ScoreSide) -> Unit = { _, _ -> },
    /** Hold OK or More… → Team page ‹team› (#62), for a pro team with ESPN's id. */
    onOpenTeam: (TeamRef) -> Unit = {},
    /** Live now's live games ([SportsLiveUiState.liveGameCount]): the "N games live now →" line's count. */
    liveGames: Int = 0,
    /** No sports picked: the state card's "My sports" (#134); null offers See Live Now instead. */
    onEditMySports: (() -> Unit)? = null,
    /** The header control over the stage: the stage's Up lands on it (#170). */
    controlsFocusRequester: FocusRequester = remember { FocusRequester() },
    /** The stage's first button: Right from a row, and the header controls' Down while [onStageShown] says it is drawn. */
    stageFocusRequester: FocusRequester = remember { FocusRequester() },
    onStageShown: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val liveNowFocusRequester = remember { FocusRequester() }
    val ready = state.status == ResultsStatus.READY
    // Today: games being played are not listed, only counted on a line that jumps to Live Now.
    val showLiveNow = state.showsLiveNow(liveGames)
    val itemFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val scope = rememberCoroutineScope()
    fun itemFocusRequester(key: String) = itemFocusRequesters.getOrPut(key) { FocusRequester() }

    // Saved with the back stack entry, so returning from a replay refocuses its row.
    var focusedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var listHasFocus by remember { mutableStateOf(false) }
    var stageHasFocus by remember { mutableStateOf(false) }
    // The date too: at US Eastern midnight Today becomes a new day, a new list.
    val selectedDate = state.dates.firstOrNull { it.daysAgo == state.daysAgo }?.date
    val listKey = "${state.daysAgo}|$selectedDate|${state.selectedSport?.id.orEmpty()}|${state.selectedLeague.orEmpty()}"
    val currentListKey by rememberUpdatedState(listKey)
    // The list (by its key) that holds focus; kept when that list goes because its key changed.
    var focusedListKey by remember { mutableStateOf<String?>(null) }
    val listState = rememberSaveable(listKey, saver = LazyListState.Saver) { LazyListState() }
    val items = if (ready) state.items else emptyList()

    // Headers never take focus: the list's entry is a result or a "+ N more" row.
    val currentItems by rememberUpdatedState(items)
    val entryKey by remember(listState) {
        derivedStateOf {
            val listed = currentItems
            val focusable = listed.filter { it.isFocusable }.map { it.key }
            val visible = listState.layoutInfo.visibleItemsInfo
                .mapNotNull { info -> listed.getOrNull(info.index)?.takeIf { it.isFocusable }?.key }
                .map(focusable::indexOf)
            sportsLiveListEntryKey(focusable, focusedKey, visible)
        }
    }
    val firstFocusableKey = items.firstOrNull { it.isFocusable }?.key
    // An empty Today offers Yesterday's results (#78).
    val yesterdayFocusRequester = remember { FocusRequester() }
    val offersYesterday = ready && resultsOffersYesterday(state.daysAgo, firstFocusableKey != null) &&
        !(state.selectedSport == null && state.sports.isNotEmpty())
    // An empty or failed day shows the state card (#134): its first button is the list's entry.
    val stateCardShown = state.status == ResultsStatus.NO_LEAGUES || state.status == ResultsStatus.FAILED ||
        (ready && firstFocusableKey == null)
    val entryFocusRequester = entryKey?.let(::itemFocusRequester) ?: yesterdayFocusRequester.takeIf { stateCardShown }
    // Up from the list's top: the live line when shown, else the header's date.
    val aboveListFocusRequester = if (showLiveNow) liveNowFocusRequester else headerFocusRequester

    // A real day, sport or league change starts the new list at its top; the saved key survives navigation.
    var listedKey by rememberSaveable { mutableStateOf(listKey) }
    LaunchedEffect(listKey) {
        if (listKey == listedKey) return@LaunchedEffect
        listedKey = listKey
        focusedKey = null
        listState.scrollToItem(0)
        // The list in focus was replaced under it (the day moved on at midnight): focus goes to the date.
        if (focusedListKey != null && focusedListKey != listKey) {
            focusedListKey = null
            withFrameNanos { }
            runCatching { headerFocusRequester.requestFocus() }
        }
    }
    // Back from a replay or the Game page: focus returns to the row that opened it, once its day has loaded.
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(items) {
        if (restored) return@LaunchedEffect
        val key = focusedKey ?: return@LaunchedEffect
        val index = items.indexOfFirst { it.key == key }
        if (index < 0) return@LaunchedEffect
        restored = true
        listState.scrollToItem(index)
        repeat(2) { withFrameNanos { } }
        runCatching { itemFocusRequesters[key]?.requestFocus() }
    }
    // OK on "+ N more": the row goes and focus moves to the first game it held.
    var expandFocusKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(items, expandFocusKey) {
        val key = expandFocusKey ?: return@LaunchedEffect
        if (items.none { it.key == key }) return@LaunchedEffect
        expandFocusKey = null
        withFrameNanos { }
        runCatching { itemFocusRequesters[key]?.requestFocus() }
    }
    // A refresh with no game left live drops the live line: focus goes to the header's date.
    var liveLineHasFocus by remember { mutableStateOf(false) }
    val currentShowLiveNow by rememberUpdatedState(showLiveNow)
    LaunchedEffect(showLiveNow) {
        if (showLiveNow || !liveLineHasFocus) return@LaunchedEffect
        liveLineHasFocus = false
        withFrameNanos { }
        runCatching { headerFocusRequester.requestFocus() }
    }
    BackHandler(enabled = listHasFocus || stageHasFocus) {
        runCatching { tabFocusRequester.requestFocus() }
    }
    // Hold OK on a row, or More…: Game page, Team pages and Follow / Following for each side (away first, as listed).
    var menuRowKey by remember { mutableStateOf<String?>(null) }
    // Golf rows whose top five OK has listed.
    var expandedGolf by remember { mutableStateOf<Set<String>>(emptySet()) }
    items.firstNotNullOfOrNull { (it as? ResultListItem.Row)?.row?.takeIf { row -> row.key == menuRowKey } }?.let { row ->
        val sides = listOf(ScoreSide.AWAY to row.event.away, ScoreSide.HOME to row.event.home)
        SportsLiveCardMenu(
            title = stringResource(R.string.sports_live_results_menu_title, row.event.away.listName, row.event.home.listName),
            sides = sides.map { (side, team) -> team.listName to isFollowed(row, side) },
            onToggleFollow = { index ->
                menuRowKey = null
                sides.getOrNull(index)?.let { (side, _) -> onToggleFollow(row, side) }
            },
            onDetails = null,
            onDismiss = { menuRowKey = null },
            onGameStats = {
                menuRowKey = null
                onOpen(row)
            },
            followingLabel = true,
            // Away first, as the row lists them.
            teamPages = TeamPages.refsOf(row.event, homeFirst = false).map { ref ->
                ref.team.listName to {
                    menuRowKey = null
                    onOpenTeam(ref)
                }
            }
        )
    }
    // The stage follows the focused row; before any, the list's first game.
    val rows = items.mapNotNull { it as? ResultListItem.Row }
    val stageItem = rows.firstOrNull { it.key == focusedKey } ?: rows.firstOrNull()
    val stageButtons = stageItem?.let { resultsStageButtons(it.row, state.replays[it.key]) }.orEmpty()
    // The header controls' Down needs the stage's first button drawn (#170).
    val stageButtonShown = stageItem != null && firstFocusableKey != null && stageButtons.isNotEmpty()
    val currentOnStageShown by rememberUpdatedState(onStageShown)
    LaunchedEffect(stageButtonShown) { currentOnStageShown(stageButtonShown) }
    DisposableEffect(Unit) { onDispose { currentOnStageShown(false) } }

    Column(modifier = modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .width(SportsLiveTokens.Size.ListWidth + RESULTS_FOCUS_ROOM * 2)
                    .offset(x = -RESULTS_FOCUS_ROOM)
                    .fillMaxHeight()
            ) {
                // The live line sits at the top of the list's column, so the stage keeps its place (V2).
                if (showLiveNow) {
                    ResultsLiveNowLine(
                        count = liveGames,
                        onClick = onOpenLiveNow,
                        modifier = Modifier
                            .padding(start = RESULTS_FOCUS_ROOM, top = RESULTS_FOCUS_ROOM, bottom = 2.dp)
                            .onFocusChanged { focus ->
                                if (focus.hasFocus) liveLineHasFocus = true else if (currentShowLiveNow) liveLineHasFocus = false
                            }
                            .focusRequester(liveNowFocusRequester)
                            .focusRequester(topFocusRequester)
                            .focusProperties {
                                up = headerFocusRequester
                                down = entryFocusRequester ?: FocusRequester.Cancel
                                left = FocusRequester.Cancel
                                right = FocusRequester.Cancel
                            }
                    )
                }
                // The state card's first button (#134): the header's Down target when nothing sits above it.
                val cardButton = Modifier
                    .focusRequester(yesterdayFocusRequester)
                    .then(if (showLiveNow) Modifier else Modifier.focusRequester(topFocusRequester))
                    .focusProperties {
                        up = aboveListFocusRequester
                        left = FocusRequester.Cancel
                        down = FocusRequester.Cancel
                    }
                val cardModifier = Modifier.padding(start = RESULTS_FOCUS_ROOM, top = RESULTS_FOCUS_ROOM)
                val seeLiveNow = SportsLiveStateAction(stringResource(R.string.sports_live_state_see_live_now), onOpenLiveNow)
                when {
                    state.status == ResultsStatus.OFF -> Unit
                    // Skeleton rows (#134), never a lone spinner.
                    state.status == ResultsStatus.LOADING -> SportsLiveSkeletonRows(modifier = cardModifier)
                    state.status == ResultsStatus.NO_LEAGUES -> SportsLiveStateCard(
                        title = stringResource(R.string.sports_live_state_no_sports),
                        line = stringResource(R.string.sports_live_results_no_leagues),
                        primary = onEditMySports?.let { SportsLiveStateAction(stringResource(R.string.sports_live_state_my_sports), it) } ?: seeLiveNow,
                        modifier = cardModifier,
                        primaryModifier = cardButton
                    )
                    state.status == ResultsStatus.FAILED -> SportsLiveStateCard(
                        title = stringResource(R.string.sports_live_results_unavailable),
                        line = stringResource(R.string.sports_live_state_results_failed_line),
                        primary = seeLiveNow,
                        problem = true,
                        modifier = cardModifier,
                        primaryModifier = cardButton
                    )
                    // ★ My sports has nothing that day but other sports do: point at the dropdown.
                    firstFocusableKey == null && state.selectedSport == null && state.sports.isNotEmpty() -> SportsLiveStateCard(
                        title = stringResource(R.string.sports_live_state_my_sports_empty),
                        line = stringResource(R.string.sports_live_state_my_sports_empty_line),
                        primary = seeLiveNow,
                        modifier = cardModifier,
                        primaryModifier = cardButton
                    )
                    // Today before its first final: after midnight, last night's games are under Yesterday (#78).
                    offersYesterday -> SportsLiveStateCard(
                        title = stringResource(R.string.sports_live_results_no_finals_yet),
                        line = when (resultsTodayEmpty(remember(selectedDate) { LocalTime.now().hour })) {
                            ResultsTodayEmpty.LAST_NIGHT -> stringResource(R.string.sports_live_state_last_night_line)
                            ResultsTodayEmpty.NO_FINALS_YET -> stringResource(R.string.sports_live_state_no_finals_line)
                        },
                        primary = SportsLiveStateAction(stringResource(R.string.sports_live_results_see_yesterday), { onSelectDay(SportsResults.YESTERDAY) }),
                        modifier = cardModifier,
                        primaryModifier = cardButton
                    )
                    firstFocusableKey == null -> SportsLiveStateCard(
                        title = stringResource(R.string.sports_live_results_empty_day),
                        line = stringResource(R.string.sports_live_state_empty_day_line),
                        primary = seeLiveNow,
                        modifier = cardModifier,
                        primaryModifier = cardButton
                    )
                    else -> androidx.compose.runtime.key(listKey) {
                        SportsLiveKeepFromEdge(margin = RESULTS_EDGE_KEEP) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .sportsLiveFadingEdges(listState, RESULTS_FADE, top = false)
                                    .onFocusChanged { focus ->
                                        listHasFocus = focus.hasFocus
                                        if (focus.hasFocus) focusedListKey = listKey else if (currentListKey == listKey) focusedListKey = null
                                        if (!focus.hasFocus) onRowFocus(null)
                                    }
                                    .focusRestorer(entryFocusRequester ?: itemFocusRequester(firstFocusableKey))
                                    .dpadRepeatThrottle(),
                                contentPadding = PaddingValues(horizontal = RESULTS_FOCUS_ROOM, vertical = RESULTS_FOCUS_ROOM),
                                verticalArrangement = Arrangement.spacedBy(SportsLiveTokens.Size.RowGap)
                            ) {
                                itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                                    val isRow = item is ResultListItem.Row
                                    val hasStage = isRow && resultsStageButtons((item as ResultListItem.Row).row, state.replays[item.key]).isNotEmpty()
                                    val focusModifier = Modifier
                                        .focusRequester(itemFocusRequester(item.key))
                                        .then(if (item.key == entryKey && !showLiveNow) Modifier.focusRequester(topFocusRequester) else Modifier)
                                        // Up from the first row: the live line when shown, never past it; else the header.
                                        .then(if (item.key == firstFocusableKey) Modifier.upTo(aboveListFocusRequester) else Modifier)
                                        .focusProperties {
                                            if (item.key == firstFocusableKey) up = aboveListFocusRequester
                                            left = FocusRequester.Cancel
                                            // Right goes into the stage's first button, when the game has one.
                                            right = if (hasStage) stageFocusRequester else FocusRequester.Cancel
                                        }
                                    val rowFocused: (String) -> Unit = { key ->
                                        focusedKey = key
                                        if (key == firstFocusableKey) scope.launch { listState.scrollToItem(0) }
                                    }
                                    when (item) {
                                        is ResultListItem.Header -> ResultsHeader(item, first = index == 0)
                                        // Tennis, UFC, F1, golf: the Game page cannot show them. OK on golf lists
                                        // its top five; on the others OK does nothing (no toast). No Follow menu.
                                        is ResultListItem.Row -> if (item.row.event.individual != null) {
                                            val individual = item.row.event.individual
                                            IndividualResultCard(
                                                row = item.row,
                                                result = individual,
                                                showScore = state.showsScore(item.row),
                                                expanded = item.key in expandedGolf,
                                                onClick = {
                                                    if (individual is GolfResult) {
                                                        expandedGolf = toggleGolf(expandedGolf, item.key, showScore = state.showsScore(item.row))
                                                    }
                                                },
                                                onFocus = {
                                                    rowFocused(item.key)
                                                    onRowFocus(item.key)
                                                },
                                                modifier = focusModifier
                                            )
                                        } else ResultBoardRow(
                                            row = item.row,
                                            showScore = state.showsScore(item.row),
                                            hasReplay = item.hasReplay,
                                            selected = stageHasFocus && item.key == stageItem?.key,
                                            onClick = { onOpen(item.row) },
                                            onLongClick = { menuRowKey = item.key },
                                            menuOpen = menuRowKey == item.key,
                                            onFocus = {
                                                rowFocused(item.key)
                                                onRowFocus(item.key)
                                            },
                                            modifier = focusModifier
                                        )
                                        is ResultListItem.More -> ResultsMoreRow(
                                            item = item,
                                            onClick = {
                                                expandFocusKey = item.firstHiddenKey
                                                focusedKey = item.firstHiddenKey
                                                onExpandGroup(item.groupKey)
                                            },
                                            onFocus = {
                                                focusedKey = item.key
                                                onRowFocus(null)
                                            },
                                            modifier = focusModifier
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.width(SportsLiveTokens.Size.StageGap - RESULTS_FOCUS_ROOM * 2))
            val miniDock = rememberSportsLiveMiniDock()
            val miniDocked = SportsLiveMiniPlayers.controller.state.collectAsState().value is SportsLiveMiniState.Playing &&
                miniDock is SportsLiveMiniDock.Docked
            if (stageItem != null && firstFocusableKey != null) {
                ResultsStage(
                    row = stageItem.row,
                    showScore = state.showsScore(stageItem.row),
                    buttons = stageButtons,
                    dayName = state.dates.firstOrNull { it.daysAgo == state.daysAgo }?.let { resultsDayText(it) },
                    onWatchReplay = { state.replays[stageItem.key]?.let { onWatchReplay(stageItem.row, it) } },
                    onGameStats = { onGameStats(stageItem.row) },
                    onMore = { menuRowKey = stageItem.key },
                    modifier = Modifier
                        .padding(top = RESULTS_FOCUS_ROOM)
                        .onFocusChanged { stageHasFocus = it.hasFocus }
                        .sportsLiveStageColumn(controlsFocusRequester, SportsLiveMiniFocus.requester.takeIf { miniDocked }),
                    firstModifier = Modifier
                        .focusRequester(stageFocusRequester)
                        .focusProperties {
                            left = itemFocusRequesters[stageItem.key] ?: FocusRequester.Default
                            // Up reaches the docked mini player (#157), whose Back stops it.
                            if (miniDocked) up = SportsLiveMiniFocus.requester
                        }
                )
            }
        }
    }
}

private val ResultListItem.isFocusable: Boolean get() = this !is ResultListItem.Header

/** Up moves focus to [target] itself, before any focus search inside the list can pick another stop. */
private fun Modifier.upTo(target: FocusRequester): Modifier = onPreviewKeyEvent { event ->
    val native = event.nativeKeyEvent
    if (native.keyCode != AndroidKeyEvent.KEYCODE_DPAD_UP) return@onPreviewKeyEvent false
    if (native.action == AndroidKeyEvent.ACTION_DOWN) runCatching { target.requestFocus() }
    true
}

/** OK on a golf row lists its top five, or folds it again; never while its result is hidden. */
internal fun toggleGolf(expanded: Set<String>, key: String, showScore: Boolean): Set<String> = when {
    !showScore -> expanded
    key in expanded -> expanded - key
    else -> expanded + key
}

/** The Results stage's buttons (V2 P4). */
internal enum class ResultsStageButton { WATCH_REPLAY, GAME_STATS, MORE }

/**
 * The Results stage's buttons for [row], the first the primary: **Watch replay** when it has a strict [replay]
 * match, **Game stats** (the Game page on its Box score, what OK on the row opens too), then **More…** (Game page,
 * Team pages, Follow); none for an individual sport (the Game page cannot show it).
 */
internal fun resultsStageButtons(row: ResultRow, replay: SportsLiveItem?): List<ResultsStageButton> = when {
    row.event.individual != null -> emptyList()
    replay != null -> listOf(ResultsStageButton.WATCH_REPLAY, ResultsStageButton.GAME_STATS, ResultsStageButton.MORE)
    else -> listOf(ResultsStageButton.GAME_STATS, ResultsStageButton.MORE)
}

/** The title's day name: "Today", "Yesterday", else its date ("Sun 15 Nov", the device's locale). */
@Composable
internal fun resultsDayText(day: ResultDay): String = when (resultsDayName(day)) {
    ResultsDayName.TODAY -> stringResource(R.string.sports_live_results_today)
    ResultsDayName.YESTERDAY -> stringResource(R.string.sports_live_results_yesterday)
    ResultsDayName.DATE -> agendaDateText(day.date)
}

/** "● 3 games live now →" under the header on Today: a small line; OK opens the Live Now tab. */
@Composable
private fun ResultsLiveNowLine(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = SportsLiveTokens.Radius.PillShape
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = SportsLiveTokens.Focus.LIFT),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Colors.LiveText,
            focusedContainerColor = SportsLiveFocus.focusedFill(Colors.Background),
            focusedContentColor = Colors.LiveText
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = SportsLiveFocus.ring(shape))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(Colors.LiveRed))
            Text(
                text = pluralStringResource(R.plurals.sports_live_results_live_now, count, count),
                style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
                maxLines = 1
            )
        }
    }
}

/** A section header: YOUR TEAMS (gold) or the competition's name, in the Scoreboard's group style. */
@Composable
private fun ResultsHeader(item: ResultListItem.Header, first: Boolean) {
    val text = when {
        item.yourTeams -> stringResource(R.string.sports_live_results_your_teams)
        item.name != null -> item.name
        else -> sportLabel(item.sport.orEmpty())
    }
    Text(
        text = text.uppercase(Locale.getDefault()),
        style = SportsLiveTokens.style(SportsLiveTokens.Type.SectionHeader),
        color = if (item.yourTeams) Colors.FollowedGold else Colors.TextPrimary.copy(alpha = 0.6f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = if (first) 0.dp else 8.dp, bottom = 2.dp)
    )
}

/** "+ 39 more NCAAM games": a dashed row; OK lists the rest of the section. */
@Composable
private fun ResultsMoreRow(
    item: ResultListItem.More,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = SportsLiveTokens.Radius.RowShape
    val dashColor = Colors.TextMuted
    val league = item.name ?: sportLabel(item.sport)
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocus() }
            .drawBehind {
                // Inset by half the stroke: a stroke on the bounds is half outside, clipped by the list.
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    color = dashColor,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(10.dp.toPx()),
                    style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())))
                )
            },
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = SportsLiveTokens.Focus.LIFT),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Colors.TextSecondary,
            focusedContainerColor = SportsLiveFocus.focusedFill(Color.Transparent),
            focusedContentColor = Colors.TextPrimary
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = SportsLiveFocus.ring(shape))
    ) {
        Text(
            text = pluralStringResource(R.plurals.sports_live_results_more, item.count, item.count, league),
            style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

/**
 * A Results row (V2 P4) in fixed columns as in Live Now ([ResultRowColumns]): the two teams (logos, the winner
 * in real bold) in a names column, the stacked scores right after it, then the note ("Final", "FT", "OT") and
 * the green Replay pill. Under spoiler rules "Score hidden" takes the scores and the note. A followed team's
 * game gets the gold left bar; [selected] (focus in the stage) a white one.
 */
@Composable
private fun ResultBoardRow(
    row: ResultRow,
    showScore: Boolean,
    hasReplay: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    menuOpen: Boolean,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = SportsLiveTokens.Radius.RowShape
    val event = row.event
    // The winner bold (name and score), never while the score is hidden.
    val homeWon = sportsLiveWinnerBold(showScore, event.homeScore, event.awayScore)
    val awayWon = sportsLiveWinnerBold(showScore, event.awayScore, event.homeScore)
    val (longPress, click) = rememberSportsLiveLongPress(menuOpen = menuOpen, onLongClick = onLongClick, onClick = onClick)
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = click,
        modifier = modifier
            .fillMaxWidth()
            .height(ResultRowMetrics.HEIGHT_DP.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .then(longPress),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = SportsLiveTokens.Focus.LIFT),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Colors.Row,
            contentColor = Colors.TextPrimary,
            focusedContainerColor = SportsLiveFocus.focusedFill(Colors.Row),
            focusedContentColor = Colors.TextPrimary,
            pressedContainerColor = SportsLiveFocus.focusedFill(Colors.Row),
            pressedContentColor = Colors.TextPrimary
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = SportsLiveFocus.ring(shape), pressedBorder = SportsLiveFocus.ring(shape))
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = ResultRowColumns.PAD_DP.dp),
                horizontalArrangement = Arrangement.spacedBy(ResultRowColumns.GAP_DP.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.width(ResultRowColumns.NAMES_DP.dp), verticalArrangement = Arrangement.Center) {
                    // Away first, as ESPN and US scoreboards list them.
                    // Logos only when both teams have one and both loaded (#130); never an empty circle.
                    val logos = rememberTeamLogoPair(event.away.logo, event.home.logo)
                    TeamLine(event.away, logos?.first, logos?.shown == true, bold = awayWon)
                    TeamLine(event.home, logos?.second, logos?.shown == true, bold = homeWon)
                }
                if (showScore) {
                    Column(modifier = Modifier.width(ResultRowColumns.SCORES_DP.dp), horizontalAlignment = Alignment.End) {
                        val style = SportsLiveTokens.style(SportsLiveTokens.Type.RowScore)
                        Text(
                            text = event.awayScore?.toString() ?: "–",
                            style = if (awayWon) style.sportsLiveBold() else style.copy(fontWeight = FontWeight.Normal),
                            color = if (homeWon) Colors.TextMuted else Colors.TextPrimary,
                            maxLines = 1
                        )
                        Text(
                            text = event.homeScore?.toString() ?: "–",
                            style = if (homeWon) style.sportsLiveBold() else style.copy(fontWeight = FontWeight.Normal),
                            color = if (awayWon) Colors.TextMuted else Colors.TextPrimary,
                            maxLines = 1
                        )
                    }
                }
                Column(
                    modifier = Modifier.width(
                        if (showScore) ResultRowColumns.NOTE_DP.dp else (ResultRowColumns.SCORES_DP + ResultRowColumns.GAP_DP + ResultRowColumns.NOTE_DP).dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
                ) {
                    Text(
                        // The note ("AET", "Pens", "OT") tells how the game ended, so it hides with the score; a
                        // plain end reads per sport (#102): "FT" for soccer, "Final" for the US sports.
                        text = if (showScore) {
                            stringResource(row.note.labelRes(event.league.sport))
                        } else {
                            stringResource(R.string.sports_live_results_score_hidden)
                        },
                        style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag).copy(fontWeight = FontWeight.Medium),
                        color = Colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // Only a strict replay match gets the tag (#51); it stays visible while a score is hidden.
                    if (hasReplay) ReplayPill()
                }
            }
            val bar = when {
                selected && !focused -> Colors.FocusWhite
                row.followed -> Colors.FollowedGold
                else -> null
            }
            if (bar != null) {
                Box(modifier = Modifier.align(Alignment.CenterStart).width(2.dp).fillMaxHeight().background(bar))
            }
        }
    }
}

/**
 * The Results row's fixed columns (V2 P4, §3.8): inside the 400 dp list, a names column, the scores right
 * after it (about 520 px from the row's start at 1080p, not 1 200 px), then the note. In dp.
 */
internal object ResultRowColumns {
    const val PAD_DP = 8
    const val GAP_DP = 6
    const val SCORES_DP = 40
    const val NOTE_DP = 75

    /** The row's width: the list's. */
    const val ROW_DP = 400

    /** The names column: what is left of the row. */
    const val NAMES_DP = ROW_DP - 2 * PAD_DP - SCORES_DP - NOTE_DP - 2 * GAP_DP

    /** Where the scores column starts, from the row's left edge. */
    const val SCORES_START_DP = PAD_DP + NAMES_DP + GAP_DP

    /** Where the note column starts. */
    const val NOTE_START_DP = SCORES_START_DP + SCORES_DP + GAP_DP
}

/**
 * The Results stage (V2 P4): the same fixed 360 dp as the other tabs'. The drawn matchup (logos, league, never
 * a poster); the final score ("BOS 91–88 NYK") or "Score hidden" under spoiler rules; "Final · NBA ·
 * Yesterday"; then **Watch replay** (a strict match) and **More…**.
 */
@Composable
private fun ResultsStage(
    row: ResultRow,
    showScore: Boolean,
    buttons: List<ResultsStageButton>,
    dayName: String?,
    onWatchReplay: () -> Unit,
    onGameStats: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    firstModifier: Modifier = Modifier
) {
    val event = row.event
    // A mini player plays (#157): it docks in the media box, and the result's details read as Selected.
    val miniPlaying = SportsLiveMiniPlayers.controller.state.collectAsState().value is SportsLiveMiniState.Playing
    Column(
        modifier = modifier.width(SportsLiveStageLayout.WIDTH_DP.dp),
        verticalArrangement = Arrangement.spacedBy(SportsLiveStageLayout.GAP_DP.dp)
    ) {
        Box(
            modifier = Modifier
                .width(SportsLiveStageLayout.WIDTH_DP.dp)
                .height(SportsLiveStageLayout.MEDIA_HEIGHT_DP.dp)
                .sportsLiveMiniSlot(miniPlaying)
                .clip(SportsLiveTokens.Radius.RowShape)
                .background(Colors.Surface)
        ) {
            val league = event.leagueName?.uppercase(Locale.getDefault()).orEmpty()
            if (event.individual == null) {
                MatchupStageArt(GameArt.ofEvent(event), top = league, at = event.league.sport != "soccer")
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
                ) {
                    Text(text = league, style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag), color = Colors.TextMuted, maxLines = 1)
                    Text(
                        text = event.individual.title ?: event.leagueName.orEmpty(),
                        style = SportsLiveTokens.style(SportsLiveTokens.Type.RowName).sportsLiveBold(),
                        color = Colors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (miniPlaying) SportsLiveStageSelectedHeading()
        Column(
            modifier = Modifier.fillMaxWidth().height(SportsLiveStageLayout.SCORE_BLOCK_HEIGHT_DP.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val away = event.away.abbreviation ?: event.away.listName
            val home = event.home.abbreviation ?: event.home.listName
            val awayScore = event.awayScore
            val homeScore = event.homeScore
            val headline = when {
                event.individual != null -> event.individual.title ?: event.leagueName.orEmpty()
                showScore && awayScore != null && homeScore != null ->
                    stringResource(R.string.sports_live_details_scoreline, away, awayScore, homeScore, home)
                else -> stringResource(R.string.sports_live_matchup_at, event.away.shortDisplayName ?: away, event.home.shortDisplayName ?: home)
            }
            BasicText(
                text = headline,
                style = SportsLiveTokens.style(SportsLiveTokens.Type.StageScore).sportsLiveBold().copy(color = Colors.TextPrimary),
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = SportsLiveTokens.Type.StageScore.fontSize, stepSize = 1.sp)
            )
            val note = if (showScore) stringResource(row.note.labelRes(event.league.sport)) else stringResource(R.string.sports_live_results_score_hidden)
            Text(
                text = listOfNotNull(note, event.leagueName, dayName).joinToString(" · "),
                style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
                color = Colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (buttons.isNotEmpty()) {
            Row(
                modifier = Modifier.height(SportsLiveStageLayout.BUTTON_ROW_HEIGHT_DP.dp),
                horizontalArrangement = Arrangement.spacedBy(SportsLiveTokens.Size.ButtonGap),
                verticalAlignment = Alignment.CenterVertically
            ) {
                buttons.forEachIndexed { index, button ->
                    val role = if (index == 0) SportsLiveButtonRole.PRIMARY else SportsLiveButtonRole.SECONDARY
                    val buttonModifier = if (index == 0) firstModifier else Modifier
                    when (button) {
                        ResultsStageButton.WATCH_REPLAY -> SportsLiveButton(onClick = onWatchReplay, role = role, modifier = buttonModifier.widthIn(min = 110.dp)) {
                            Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.sports_live_game_watch_replay), style = SportsLiveTokens.style(SportsLiveTokens.Type.Button), maxLines = 1, softWrap = false)
                        }
                        ResultsStageButton.GAME_STATS -> SportsLiveButton(onClick = onGameStats, role = role, modifier = buttonModifier.widthIn(min = 104.dp)) {
                            Text(stringResource(R.string.sports_live_game_stats), style = SportsLiveTokens.style(SportsLiveTokens.Type.Button), maxLines = 1, softWrap = false)
                        }
                        ResultsStageButton.MORE -> SportsLiveButton(onClick = onMore, role = role, modifier = buttonModifier.widthIn(min = 84.dp)) {
                            Text(stringResource(R.string.sports_live_more), style = SportsLiveTokens.style(SportsLiveTokens.Type.Button), maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}

private val RESULTS_FOCUS_ROOM = 8.dp
private val RESULTS_FADE = 40.dp
private val RESULTS_EDGE_KEEP = 100.dp

/**
 * A Results row's size (V2 P4): two team lines of [LINE_SP] at the V2 row-name size, and [PADDING_DP] above
 * and below: 96 px at 1080p (2 px a dp), as Live Now's two-team rows.
 */
internal object ResultRowMetrics {
    const val LINE_SP = 20
    const val FONT_SP = 15
    const val LOGO_DP = 18
    const val PADDING_DP = 4

    /** The row's height in dp. */
    const val HEIGHT_DP = 2 * LINE_SP + 2 * PADDING_DP
}

@Composable
private fun resultLineStyle() =
    SportsLiveTokens.style(SportsLiveTokens.Type.RowName).copy(fontSize = ResultRowMetrics.FONT_SP.sp, lineHeight = ResultRowMetrics.LINE_SP.sp)

/** The green "Replay" tag of a result with a replay. */
@Composable
internal fun ReplayPill() {
    Text(
        text = stringResource(R.string.sports_live_results_replay),
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = REPLAY_PILL_TEXT,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(REPLAY_PILL_COLOR)
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = 2.dp)
    )
}

@Composable
private fun TeamLine(team: ScoreTeam, logo: Painter?, logoShown: Boolean, bold: Boolean) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The logo slot keeps its room without logos, so names line up row to row (#130).
        Box(modifier = Modifier.size(ResultRowMetrics.LOGO_DP.dp), contentAlignment = Alignment.Center) {
            if (logo != null) TeamLogoSlot(logo, logoShown, ResultRowMetrics.LOGO_DP.dp)
        }
        Text(
            text = team.listName,
            style = resultLineStyle().let { if (bold) it.sportsLiveBold() else it },
            color = Colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** A Results team line's name: ESPN's full name, else a shorter one. */
private val ScoreTeam.listName: String get() = displayName ?: shortDisplayName ?: name ?: abbreviation.orEmpty()

/** An ESPN sport key as a chip or row label. */
@Composable
private fun sportLabel(sport: String): String = when (sport) {
    "basketball" -> stringResource(R.string.sports_live_results_sport_basketball)
    "football" -> stringResource(R.string.sports_live_results_sport_football)
    "hockey" -> stringResource(R.string.sports_live_results_sport_hockey)
    "baseball" -> stringResource(R.string.sports_live_results_sport_baseball)
    "soccer" -> stringResource(R.string.sports_live_results_sport_soccer)
    "tennis" -> stringResource(R.string.sports_live_results_sport_tennis)
    "mma" -> stringResource(R.string.sports_live_results_sport_mma)
    "racing" -> stringResource(R.string.sports_live_results_sport_motorsport)
    "golf" -> stringResource(R.string.sports_live_results_sport_golf)
    else -> sport.replaceFirstChar { it.titlecase(Locale.getDefault()) }
}

private fun FinalNote.labelRes(sport: String): Int = when (this) {
    FinalNote.FT -> if (GameStatusText.endsAtFullTime(sport)) R.string.sports_live_results_note_ft else R.string.sports_live_chip_final_note
    FinalNote.OT -> R.string.sports_live_results_note_ot
    FinalNote.SO -> R.string.sports_live_results_note_so
    FinalNote.AET -> R.string.sports_live_results_note_aet
    FinalNote.PENS -> R.string.sports_live_results_note_pens
}

private val REPLAY_PILL_COLOR = Color(0xFF7EE0A1)
private val REPLAY_PILL_TEXT = Color(0xFF0B1F12)
