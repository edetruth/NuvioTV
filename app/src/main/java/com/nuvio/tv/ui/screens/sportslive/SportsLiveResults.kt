package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.GameStatusText
import com.nuvio.tv.core.sportslive.SportsLivePillTime
import com.nuvio.tv.core.sportslive.SportsLiveToastKind
import com.nuvio.tv.core.sportslive.SportsLiveToasts
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.platform.LocalContext
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
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.GolfResult
import com.nuvio.tv.core.sportslive.scores.ResultDay
import com.nuvio.tv.core.sportslive.scores.ResultRow
import com.nuvio.tv.core.sportslive.scores.ScoreSide
import com.nuvio.tv.core.sportslive.scores.RankDisplay
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
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
import java.time.ZoneId
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
 * The Results tab (V2 P4, results redesign phase 1): the day sits in the header's title ("Results · Yesterday ‹ ›", OK
 * on an arrow changes it) and **Jump to ▾** narrows the list to Your teams, a league or Other sports, so the list
 * starts right under the header. On Today a small "N games live now →" line leads. The list (400 dp, league section
 * headers, YOUR TEAMS first with the teams that did not play, long tails collapsed) uses fixed columns as in Live
 * Now, so a score sits right after its names; ◂ ▸ on a row jump to the previous / next league ([resultsLeagueJump]).
 * The stage on the right shows the focused game's drawn matchup, its final score (spoiler rules applied, or why it
 * is hidden), and **Watch replay** (a strict replay match) and **More…** (Game page, Reveal, Team page, Follow).
 * OK on a row opens the Game page. A followed team's score shows only once revealed; a row reveals under Hide until
 * focused only after a key press in the list, never on entry or when focus comes back ([ResultsRevealGuard], #86).
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
    /** Jump to, Back to all leagues, Reveal result and Reveal all for this day (the ViewModel). */
    actions: ResultsActions,
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
    /** More… → Watch later (no spoilers) (#151). */
    onSaveForLater: (ScoreSnapshot) -> Unit = {},
    /** More… → Report a problem (#172, TV QA), always last. */
    onReport: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
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
    val selectedDay = state.dates.firstOrNull { it.daysAgo == state.daysAgo }
    val selectedDate = selectedDay?.date
    val listKey = "${state.daysAgo}|$selectedDate|${state.jump?.let(::resultsJumpKey).orEmpty()}|${state.freshOpens}"
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
    // ◂ ▸ on a row: focus moves to the previous / next league's first game ([resultsLeagueJump]), its header in view.
    // At either end the key goes on as usual: ▸ into the stage, ◂ nowhere.
    val jumpLeague: (String, Boolean) -> Boolean = jump@{ key, forward ->
        if (!RESULTS_ARROWS_JUMP_LEAGUES) return@jump false
        val listed = currentItems
        val target = resultsLeagueJump(listed, key, forward) ?: return@jump false
        val index = listed.indexOfFirst { it.key == target }
        scope.launch {
            listState.scrollToItem((index - 1).coerceAtLeast(0))
            repeat(2) { withFrameNanos { } }
            runCatching { itemFocusRequester(target).requestFocus() }
        }
        true
    }
    // An empty Today offers Yesterday's results (#78).
    val yesterdayFocusRequester = remember { FocusRequester() }
    val offersYesterday = ready && state.jump == null && resultsOffersYesterday(state.daysAgo, firstFocusableKey != null)
    // An empty or failed day shows the state card (#134): its first button is the list's entry.
    val stateCardShown = state.status == ResultsStatus.NO_LEAGUES || state.status == ResultsStatus.FAILED ||
        (ready && firstFocusableKey == null)
    val entryFocusRequester = entryKey?.let(::itemFocusRequester) ?: yesterdayFocusRequester.takeIf { stateCardShown }
    // Up from the list's top: the live line when shown, else the header's date.
    val aboveListFocusRequester = if (showLiveNow) liveNowFocusRequester else headerFocusRequester

    // Jump to ▾ or Back to all leagues ([SportsLiveResultsUiState.jumpFocus]): the new list's first game takes focus.
    var handledJumpFocus by rememberSaveable { mutableStateOf(state.jumpFocus) }
    val currentFirstFocusableKey by rememberUpdatedState(firstFocusableKey)
    // A real day or jump change starts the new list at its top; the saved key survives navigation.
    var listedKey by rememberSaveable { mutableStateOf(listKey) }
    LaunchedEffect(listKey, state.jumpFocus) {
        val jumped = state.jumpFocus != handledJumpFocus
        handledJumpFocus = state.jumpFocus
        if (listKey == listedKey && !jumped) return@LaunchedEffect
        val newList = listKey != listedKey
        listedKey = listKey
        if (newList) {
            focusedKey = null
            listState.scrollToItem(0)
        }
        if (jumped) {
            // Entry focus, not a key press in the list: it reveals nothing (#86).
            focusedListKey = null
            repeat(2) { withFrameNanos { } }
            val first = currentFirstFocusableKey
            runCatching { (first?.let { itemFocusRequesters[it] } ?: yesterdayFocusRequester).requestFocus() }
            return@LaunchedEffect
        }
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
    // Narrowed by Jump to ▾: Back first returns to every league (registered after the one above, so it runs first).
    BackHandler(enabled = state.jump != null && (listHasFocus || stageHasFocus)) {
        actions.jumpTo(null)
    }
    // #86 / §4.3: Hide until focused reveals only after a key press inside the list.
    val revealGuard = remember { ResultsRevealGuard() }
    // Hold OK on a row, or More…: Game page, Team pages and Follow / Following for each side (away first, as listed).
    var menuRowKey by remember { mutableStateOf<String?>(null) }
    // Golf rows whose top five OK has listed.
    var expandedGolf by remember { mutableStateOf<Set<String>>(emptySet()) }
    // More… → Reveal all for this day: offered while a listed result is hidden by the Scores setting (review S3: your
    // teams' and saved games keep their own Reveal result).
    val dayHasHidden = items.any { it is ResultListItem.Row && state.hiddenReason(it.row) == ResultHiddenReason.SETTING }
    items.firstNotNullOfOrNull { (it as? ResultListItem.Row)?.row?.takeIf { row -> row.key == menuRowKey } }?.let { row ->
        val sides = listOf(ScoreSide.AWAY to row.event.away, ScoreSide.HOME to row.event.home)
        val rowHiddenForGood = state.hiddenReason(row).let { it == ResultHiddenReason.SAVED || it == ResultHiddenReason.YOUR_TEAM }
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
            // Watch later (no spoilers) (#151): the row's score hides at once, here and everywhere. A score hidden
            // for good (saved, or a followed team's) offers Reveal result in its place.
            onWatchLater = {
                menuRowKey = null
                if (rowHiddenForGood) {
                    actions.reveal(listOf(row))
                } else {
                    onSaveForLater(row.event)
                    SportsLiveToasts.show(
                        SportsLiveToastKind.INFO,
                        context.getString(R.string.sports_live_watch_later_toast, context.getString(R.string.sports_live_results_menu_title, row.event.away.listName, row.event.home.listName)),
                        context.getString(R.string.sports_live_watch_later_toast_line)
                    )
                }
            },
            savedForLater = rowHiddenForGood,
            onRevealDay = if (dayHasHidden) ({
                menuRowKey = null
                actions.revealDay()
            }) else null,
            // Away first, as the row lists them.
            teamPages = TeamPages.refsOf(row.event, homeFirst = false).map { ref ->
                ref.team.listName to {
                    menuRowKey = null
                    onOpenTeam(ref)
                }
            },
            onReport = {
                menuRowKey = null
                onReport()
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
                    // Jump to ▾ narrowed to nothing (Other sports with no games): back to every league.
                    firstFocusableKey == null && state.jump != null -> SportsLiveStateCard(
                        title = stringResource(R.string.sports_live_state_filter_empty),
                        line = when {
                            state.otherLoading -> stringResource(R.string.sports_live_results_other_loading)
                            state.otherFailed -> stringResource(R.string.sports_live_results_other_failed)
                            else -> null
                        },
                        primary = SportsLiveStateAction(stringResource(R.string.sports_live_results_jump_all), { actions.jumpTo(null) }),
                        modifier = cardModifier,
                        primaryModifier = cardButton
                    )
                    // Nothing from My sports on a past day and other sports could not be loaded (M3): say so, with Retry.
                    firstFocusableKey == null && !offersYesterday && state.otherFailed -> SportsLiveStateCard(
                        title = stringResource(R.string.sports_live_results_my_sports_empty_on, selectedDay?.let { resultsDayText(it) }.orEmpty()),
                        line = stringResource(R.string.sports_live_results_other_failed),
                        primary = SportsLiveStateAction(stringResource(R.string.action_retry), { actions.retryOtherSports() }),
                        modifier = cardModifier,
                        primaryModifier = cardButton
                    )
                    // Nothing from My sports on a past day, but other sports played: a next step, not a dead end.
                    firstFocusableKey == null && !offersYesterday && (state.otherLoading || (state.otherCount ?: 0) > 0) -> SportsLiveStateCard(
                        title = stringResource(R.string.sports_live_results_my_sports_empty_on, selectedDay?.let { resultsDayText(it) }.orEmpty()),
                        line = if (state.otherLoading) stringResource(R.string.sports_live_results_other_loading) else null,
                        primary = state.otherCount?.takeIf { it > 0 }?.let { count ->
                            SportsLiveStateAction(
                                pluralStringResource(R.plurals.sports_live_results_see_other, count, count),
                                { actions.jumpTo(ResultsJump.OtherSports) }
                            )
                        } ?: seeLiveNow,
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
                                        if (!focus.hasFocus) {
                                            revealGuard.onLeave()
                                            onRowFocus(null)
                                        }
                                    }
                                    // A key pressed in the list arms the reveal (before focus moves on).
                                    .onPreviewKeyEvent { event ->
                                        if (event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_DOWN) revealGuard.onKey()
                                        false
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
                                        // ◂ ▸ jump to the previous / next league's first game (results redesign).
                                        .then(if (item.isFocusable) Modifier.leagueJumps { forward -> jumpLeague(item.key, forward) } else Modifier)
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
                                        is ResultListItem.NotPlayed -> ResultsNotPlayedRow(item)
                                        // More than one line of teams that did not play: OK lists them, focus moving on to
                                        // the next game (the lines themselves take no focus).
                                        is ResultListItem.NotPlayedMore -> ResultsNotPlayedMoreRow(
                                            count = item.count,
                                            onClick = {
                                                val next = items.drop(index + 1).firstOrNull { it.isFocusable }?.key
                                                expandFocusKey = next
                                                focusedKey = next
                                                onExpandGroup(RESULTS_NOT_PLAYED_KEY)
                                            },
                                            onFocus = {
                                                focusedKey = item.key
                                                onRowFocus(null)
                                            },
                                            modifier = focusModifier
                                        )
                                        // Tennis, UFC, F1, golf: the Game page cannot show them. OK on golf lists
                                        // its top five; on the others OK plays its replay, else says why nothing opens.
                                        is ResultListItem.Row -> if (item.row.event.individual != null) {
                                            val individual = item.row.event.individual
                                            val noPage = stringResource(R.string.sports_live_results_no_game_page, sportLabel(item.row.event.league.sport))
                                            IndividualResultCard(
                                                row = item.row,
                                                result = individual,
                                                showScore = state.showsScore(item.row),
                                                expanded = item.key in expandedGolf,
                                                onClick = {
                                                    when (resultsIndividualOk(individual, state.replays[item.key])) {
                                                        ResultsIndividualOk.TOGGLE_GOLF ->
                                                            expandedGolf = toggleGolf(expandedGolf, item.key, showScore = state.showsScore(item.row))
                                                        ResultsIndividualOk.WATCH_REPLAY ->
                                                            state.replays[item.key]?.let { onWatchReplay(item.row, it) }
                                                        ResultsIndividualOk.NO_PAGE -> SportsLiveToasts.show(SportsLiveToastKind.INFO, noPage)
                                                    }
                                                },
                                                onFocus = {
                                                    rowFocused(item.key)
                                                    onRowFocus(revealGuard.focusTarget(item.key))
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
                                                onRowFocus(revealGuard.focusTarget(item.key))
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
                    hidden = state.hiddenReason(stageItem.row),
                    buttons = stageButtons,
                    dayName = selectedDay?.let { resultsDayText(it) },
                    onWatchReplay = { state.replays[stageItem.key]?.let { onWatchReplay(stageItem.row, it) } },
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

private val ResultListItem.isFocusable: Boolean get() =
    this is ResultListItem.Row || this is ResultListItem.More || this is ResultListItem.NotPlayedMore

/** ◂ (false) or ▸ (true) pressed on a row: [jump] moves focus and returns true, or false to let focus move as usual. */
private fun Modifier.leagueJumps(jump: (forward: Boolean) -> Boolean): Modifier = onPreviewKeyEvent { event ->
    val native = event.nativeKeyEvent
    val forward = when (native.keyCode) {
        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> false
        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> true
        else -> return@onPreviewKeyEvent false
    }
    if (native.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
    jump(forward)
}

/**
 * ◂ ▸ on a list row (results redesign §4 point 3): the first game of the previous / next league section (YOUR TEAMS
 * counts as one), or null at either end, where ▸ still enters the stage and ◂ does nothing. One press per league
 * instead of a ▼ per game.
 */
internal fun resultsLeagueJump(items: List<ResultListItem>, fromKey: String, forward: Boolean): String? {
    val from = items.indexOfFirst { it.key == fromKey }.takeIf { it >= 0 } ?: return null
    // Each item's section: the index of the header above it.
    val sectionStarts = items.indices.filter { items[it] is ResultListItem.Header }
    val section = sectionStarts.lastOrNull { it <= from } ?: return null
    val order = sectionStarts.indexOf(section)
    val targets = if (forward) sectionStarts.drop(order + 1) else sectionStarts.take(order).reversed()
    for (start in targets) {
        val end = sectionStarts.firstOrNull { it > start } ?: items.size
        val first = (start + 1 until end).firstOrNull { items[it].isFocusable } ?: continue
        return items[first].key
    }
    return null
}

/**
 * Hide until focused's key-press guard (#86, results redesign §4.3): a row focused by itself (entry into the list,
 * focus coming back from a replay or the Game page, a jump) never starts the reveal; only after a key press inside
 * the list does a newly focused row count. Leaving the list disarms it again.
 */
internal class ResultsRevealGuard {
    var armed: Boolean = false
        private set

    /** A key pressed inside the list, before focus moves. */
    fun onKey() {
        armed = true
    }

    /** Focus left the list. */
    fun onLeave() {
        armed = false
    }

    /** The row whose reveal may start on focus: [key] once armed, else none. */
    fun focusTarget(key: String): String? = key.takeIf { armed }
}

/** What OK does on an individual sport's row (results redesign): golf its top five, else its replay, else a toast. */
internal enum class ResultsIndividualOk { TOGGLE_GOLF, WATCH_REPLAY, NO_PAGE }

internal fun resultsIndividualOk(result: com.nuvio.tv.core.sportslive.scores.IndividualResult, replay: SportsLiveItem?): ResultsIndividualOk = when {
    result is GolfResult -> ResultsIndividualOk.TOGGLE_GOLF
    replay != null -> ResultsIndividualOk.WATCH_REPLAY
    else -> ResultsIndividualOk.NO_PAGE
}

/** A jump's part of the list key: a new jump is a new list (it starts at its top). */
internal fun resultsJumpKey(jump: ResultsJump): String = when (jump) {
    ResultsJump.YourTeams -> "teams"
    ResultsJump.OtherSports -> "other"
    is ResultsJump.League -> "league:${jump.key}"
}

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

/** The Results stage's buttons (V2 P4; results redesign: two). */
internal enum class ResultsStageButton { WATCH_REPLAY, MORE }

/**
 * The Results stage's buttons for [row], the first the primary: **Watch replay** when it has a strict [replay]
 * match, then **More…** (Game page, Reveal, Team pages, Follow); none for an individual sport (the Game page cannot
 * show it). No Game stats: OK on the row opens the same page.
 */
internal fun resultsStageButtons(row: ResultRow, replay: SportsLiveItem?): List<ResultsStageButton> = when {
    row.event.individual != null -> emptyList()
    replay != null -> listOf(ResultsStageButton.WATCH_REPLAY, ResultsStageButton.MORE)
    else -> listOf(ResultsStageButton.MORE)
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

/**
 * The followed teams that did not play, as one compact line under YOUR TEAMS's results (live.24, TV report #19: three
 * full-width sentences read unfinished and pushed the results down): per team its logo, its code (the game identity's,
 * never a cut name) and its next game ("Tomorrow 6:00 PM", "Mon 7:30 PM"), else a muted "No game" ("Didn't play" on a
 * past day). Not focusable. A team whose last result is still hidden shows no next game ([resultsNotPlayedShown]).
 */
@Composable
private fun ResultsNotPlayedRow(item: ResultListItem.NotPlayed) {
    val context = LocalContext.current
    val none = stringResource(if (item.today) R.string.sports_live_results_no_game else R.string.sports_live_results_did_not_play_short)
    Row(
        horizontalArrangement = Arrangement.spacedBy(NOT_PLAYED_GAP),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp)
    ) {
        item.teams.forEach { team ->
            val next = remember(team.nextStartMs, context) {
                team.nextStartMs?.let { start ->
                    when (val pill = SportsLivePillTime.of(start, hasTimeOfDay = true, System.currentTimeMillis(), ZoneId.systemDefault(), Locale.getDefault())) {
                        is SportsLivePillTime.Today -> context.getString(R.string.sports_live_results_next_today, pill.time)
                        is SportsLivePillTime.Tomorrow -> context.getString(R.string.sports_live_tomorrow, pill.time)
                        is SportsLivePillTime.Other -> pill.text
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                rememberTeamLogo(team.logo)?.let { logo -> TeamLogoSlot(logo.painter, logo.shown, NOT_PLAYED_LOGO) }
                Text(
                    text = team.code,
                    style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta).copy(fontWeight = FontWeight.SemiBold),
                    color = Colors.TextPrimary,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = next ?: none,
                    style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
                    color = if (next != null) Colors.TextSecondary else Colors.TextMuted,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

private val NOT_PLAYED_GAP = 20.dp
private val NOT_PLAYED_LOGO = 20.dp

/** "5 of your teams didn't play ›": the collapsed lines (more than one line's worth); OK lists them. */
@Composable
private fun ResultsNotPlayedMoreRow(count: Int, onClick: () -> Unit, onFocus: () -> Unit, modifier: Modifier = Modifier) {
    val shape = SportsLiveTokens.Radius.RowShape
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocus() },
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
            text = pluralStringResource(R.plurals.sports_live_results_not_played_more, count, count),
            style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
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
    // A followed team's final score focused (not merely on screen): its result is known, its replay leaves Your teams (live.20 1c).
    if (showScore && row.followed && focused) LaunchedEffect(event.eventId) { com.nuvio.tv.core.sportslive.scores.ResultSeen.shared.finalScoreShown(event.eventId) }
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
                    // Both lines step down together to the first names that fit (P10: "Maryland Eastern Shore Ha…").
                    // The one game identity's sides (live.24): the same names, codes and ranks as Live Now's rows.
                    val sides = remember(event.away, event.home, event.league) { resultSidesOf(event) }
                    val awayChoices = sides.first.names
                    val homeChoices = sides.second.names
                    var nameLevel by remember(awayChoices, homeChoices) { mutableStateOf(0) }
                    val maxLevel = maxOf(awayChoices.lastIndex, homeChoices.lastIndex)
                    val tooLong: (Int) -> Unit = { from -> nameLevel = nextNameLevel(nameLevel, from, maxLevel) }
                    TeamLine(awayChoices, nameLevel, tooLong, logos?.first, logos?.shown == true, bold = awayWon, rank = sides.first.rank)
                    TeamLine(homeChoices, nameLevel, tooLong, logos?.second, logos?.shown == true, bold = homeWon, rank = sides.second.rank)
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
                        // The note ("AET", "Pens", "OT") tells how the game ended, so it hides with the score; the game
                        // grammar's final words (live.24): "Final · OT", "FT · Pens"; a plain end per sport (#102).
                        text = if (showScore) {
                            resultsFinalWords(event)
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
 * a poster); the final score ("BOS 91–88 NYK") or "Score hidden" under spoiler rules, with why and how to reveal
 * ([hidden], results redesign); "Final · NBA · Yesterday"; then **Watch replay** (a strict match) and **More…**.
 */
@Composable
private fun ResultsStage(
    row: ResultRow,
    hidden: ResultHiddenReason?,
    buttons: List<ResultsStageButton>,
    dayName: String?,
    onWatchReplay: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    firstModifier: Modifier = Modifier
) {
    val event = row.event
    val showScore = hidden == null
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
                // Records move with the result: none while the score is hidden (Watch later, spoiler rules), as the Game page.
                MatchupStageArt(GameArt.ofEvent(event, records = showScore), top = league, at = event.league.sport != "soccer")
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
        // live.20: the info takes the height it needs, so the buttons sit right under it.
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = SportsLiveStageLayout.INFO_MAX_DP.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val away = event.away.abbreviation ?: event.away.listName
            val home = event.home.abbreviation ?: event.home.listName
            val awayScore = event.awayScore
            val homeScore = event.homeScore
            val headline = when {
                // A match or a fight names its sides (BUG17); the round stays in the media box above.
                event.individual != null -> individualSides(event.individual)?.let { (first, second) ->
                    stringResource(R.string.sports_live_matchup, first, second)
                } ?: event.individual.title ?: event.leagueName.orEmpty()
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
            val note = if (showScore) resultsFinalWords(event) else stringResource(R.string.sports_live_results_score_hidden)
            Text(
                text = listOfNotNull(note, event.leagueName, dayName).joinToString(" · "),
                style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
                color = Colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Why it is hidden and how to see it (NN/g: visibility of system status).
            if (hidden != null) {
                Text(
                    text = stringResource(
                        when (hidden) {
                            ResultHiddenReason.SAVED -> R.string.sports_live_results_hidden_saved
                            ResultHiddenReason.YOUR_TEAM -> R.string.sports_live_results_hidden_your_team
                            ResultHiddenReason.SETTING -> R.string.sports_live_results_hidden_setting
                        }
                    ),
                    style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
                    color = Colors.TextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
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
                        ResultsStageButton.MORE -> SportsLiveButton(onClick = onMore, role = role, modifier = buttonModifier.widthIn(min = 84.dp)) {
                            Text(stringResource(R.string.sports_live_more), style = SportsLiveTokens.style(SportsLiveTokens.Type.Button), maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}

/**
 * ◂ ▸ on a row jump between leagues (user decision 2026-11-21, results redesign §4 point 3); ▸ enters the stage from
 * the last league only. False restores ▸ = stage on every row, the brief's alternative if the emulator check says so.
 */
internal const val RESULTS_ARROWS_JUMP_LEAGUES = true

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
private fun TeamLine(
    choices: List<String>,
    level: Int,
    onTooLong: (Int) -> Unit,
    logo: Painter?,
    logoShown: Boolean,
    bold: Boolean,
    rank: Int? = null
) {
    val index = level.coerceAtMost(choices.lastIndex)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The logo slot keeps its room without logos, so names line up row to row (#130).
        Box(modifier = Modifier.size(ResultRowMetrics.LOGO_DP.dp), contentAlignment = Alignment.Center) {
            if (logo != null) TeamLogoSlot(logo, logoShown, ResultRowMetrics.LOGO_DP.dp)
        }
        // A ranked college team (#137): a small muted "#3" before the name (the poll's, never the result's).
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
            style = resultLineStyle().let { if (bold) it.sportsLiveBold() else it },
            color = Colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout ->
                if (index < choices.lastIndex && (layout.hasVisualOverflow || layout.isLineEllipsized(0))) onTooLong(level)
            }
        )
    }
}

/** A Results team line's name: ESPN's full name, else a shorter one. */
private val ScoreTeam.listName: String get() = displayName ?: shortDisplayName ?: name ?: abbreviation.orEmpty()

/**
 * A Results row's two sides, away first (P10, as the Scoreboard's N1): the one game identity's ([GameIdentities.side],
 * live.24) of ESPN's teams: full name, short name, then a code that is never cut, and a ranked college team's rank.
 */
internal fun resultSidesOf(event: ScoreSnapshot, codeOf: (String) -> String? = GameIdentities::knownCode): Pair<GameSideIdentity, GameSideIdentity> {
    val art = GameArt.ofEvent(event, records = false)
    return GameIdentities.side(art.first, art.ranksOnRows, codeOf = codeOf) to GameIdentities.side(art.second, art.ranksOnRows, codeOf = codeOf)
}

/** An ESPN sport key as a chip or row label. */
@Composable
internal fun sportLabel(sport: String): String = when (sport) {
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

/** A result's final words in the game grammar ([ScoreChips.finalWords]): "Final · OT", "FT · Pens", "Final", "FT". */
@Composable
private fun resultsFinalWords(event: ScoreSnapshot): String = ScoreChips.finalWords(
    note = event.clockText,
    final = stringResource(R.string.sports_live_chip_final_note),
    fullTime = stringResource(R.string.sports_live_results_note_ft),
    fullTimeSport = GameStatusText.endsAtFullTime(event.league.sport)
)

private val REPLAY_PILL_COLOR = Color(0xFF7EE0A1)
private val REPLAY_PILL_TEXT = Color(0xFF0B1F12)
