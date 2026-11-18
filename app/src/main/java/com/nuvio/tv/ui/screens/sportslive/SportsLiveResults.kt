package com.nuvio.tv.ui.screens.sportslive

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.scores.TeamLogoUrls
import com.nuvio.tv.core.sportslive.scores.FinalNote
import com.nuvio.tv.core.sportslive.scores.GolfResult
import com.nuvio.tv.core.sportslive.scores.MySport
import com.nuvio.tv.core.sportslive.scores.ResultDay
import com.nuvio.tv.core.sportslive.scores.ResultRow
import com.nuvio.tv.core.sportslive.scores.ScoreSide
import com.nuvio.tv.core.sportslive.scores.ScoreTeam
import com.nuvio.tv.core.sportslive.scores.SportsResults
import com.nuvio.tv.core.sportslive.scores.TeamPages
import com.nuvio.tv.core.sportslive.scores.TeamRef
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.LightweightCard
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.SourceStatusFilterChip
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.dpadRepeatThrottle
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What an empty Today says (#78): until [LAST_NIGHT_UNTIL_HOUR] local, last night's games are under Yesterday. */
internal enum class ResultsTodayEmpty { LAST_NIGHT, NO_FINALS_YET }

/** Until this local hour an empty Today speaks of last night (#78). */
internal const val LAST_NIGHT_UNTIL_HOUR = 6

internal fun resultsTodayEmpty(localHour: Int): ResultsTodayEmpty =
    if (localHour < LAST_NIGHT_UNTIL_HOUR) ResultsTodayEmpty.LAST_NIGHT else ResultsTodayEmpty.NO_FINALS_YET

/** Whether the list offers "See yesterday's results" (#78): Today, with nothing listed. */
internal fun resultsOffersYesterday(daysAgo: Int?, hasRows: Boolean): Boolean = daysAgo == SportsResults.TODAY && !hasRows

/** An empty Today: its note and a button that opens Yesterday (#78). */
@Composable
private fun ResultsEmptyToday(
    empty: ResultsTodayEmpty,
    onSeeYesterday: () -> Unit,
    focusRequester: FocusRequester,
    upFocusRequester: FocusRequester
) {
    Column(
        modifier = Modifier.padding(horizontal = NuvioTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        Text(
            text = stringResource(
                when (empty) {
                    ResultsTodayEmpty.LAST_NIGHT -> R.string.sports_live_results_last_night
                    ResultsTodayEmpty.NO_FINALS_YET -> R.string.sports_live_results_no_finals_yet
                }
            ),
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextSecondary
        )
        SportsLivePaneButton(
            onClick = onSeeYesterday,
            modifier = Modifier
                .focusRequester(focusRequester)
                .focusProperties {
                    up = upFocusRequester
                    left = FocusRequester.Cancel
                    right = FocusRequester.Cancel
                    down = FocusRequester.Cancel
                }
        ) {
            PaneButtonLabel(stringResource(R.string.sports_live_results_see_yesterday))
        }
    }
}

/**
 * The Results tab: the date rail (Today · Yesterday · Earlier ▸, which opens the 5 older days), the
 * ★ My sports / sport chips, the chosen sport's league chips, on Today a "N games live now →" line, and
 * the day's finals under league headers (YOUR TEAMS first, long tails collapsed). Focus only highlights a chip; OK picks it (#79), and OK on the picked one opens the list. Down from a chip row lands on
 * the active chip below or the list's entry row; a new day, sport or league opens the list at the top.
 */
@Composable
internal fun SportsLiveResultsView(
    state: SportsLiveResultsUiState,
    /** The active tab: Up from the date chips, Back from the list. */
    tabFocusRequester: FocusRequester,
    /** On the selected date chip: the tab row's Down. */
    dateFocusRequester: FocusRequester,
    onSelectDay: (Int) -> Unit,
    /** OK on Earlier: opens or closes the older days. */
    onToggleEarlier: () -> Unit,
    /** OK on "N games live now →": the Live Now tab. */
    onOpenLiveNow: () -> Unit,
    onSelectSport: (MySport?) -> Unit,
    onSelectLeague: (String?) -> Unit,
    onExpandGroup: (String) -> Unit,
    /** "Edit my sports": Settings → Sports & Live → My sports. */
    onEditMySports: () -> Unit,
    onRowFocus: (String?) -> Unit,
    /** OK on a row: the Game page (#18). */
    onOpen: (ResultRow) -> Unit,
    /** Whether a followed team is [ScoreSide] of the row's game (the menu's Follow / Following). */
    isFollowed: (ResultRow, ScoreSide) -> Boolean = { _, _ -> false },
    /** Hold OK → Follow / Following ‹team› (#54). */
    onToggleFollow: (ResultRow, ScoreSide) -> Unit = { _, _ -> },
    /** Hold OK → Team page ‹team› (#62), for a pro team with ESPN's id. */
    onOpenTeam: (TeamRef) -> Unit = {},
    /** Live now's live games ([SportsLiveUiState.liveGameCount]): the "N games live now →" line's count. */
    liveGames: Int = 0,
    modifier: Modifier = Modifier
) {
    val sportFocusRequester = remember { FocusRequester() }
    val leagueFocusRequester = remember { FocusRequester() }
    val liveNowFocusRequester = remember { FocusRequester() }
    val ready = state.status == ResultsStatus.READY
    val showSportRow = ready && state.sports.isNotEmpty()
    val showLeagueRow = showSportRow && state.selectedSport != null && state.leagues.isNotEmpty()
    // Today: games being played are not listed, only counted on a line that jumps to Live Now.
    val showLiveNow = state.showsLiveNow(liveGames)
    val itemFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val scope = rememberCoroutineScope()
    fun itemFocusRequester(key: String) = itemFocusRequesters.getOrPut(key) { FocusRequester() }

    // Saved with the back stack entry, so returning from a replay refocuses its row.
    var focusedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var listHasFocus by remember { mutableStateOf(false) }
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
    // An empty Today offers Yesterday's results (#78); Down from the chips lands on that button.
    val yesterdayFocusRequester = remember { FocusRequester() }
    val offersYesterday = ready && resultsOffersYesterday(state.daysAgo, firstFocusableKey != null) &&
        !(state.selectedSport == null && state.sports.isNotEmpty())
    val entryFocusRequester = entryKey?.let(::itemFocusRequester) ?: yesterdayFocusRequester.takeIf { offersYesterday }
    val chipsAboveFocusRequester = when {
        showLeagueRow -> leagueFocusRequester
        showSportRow -> sportFocusRequester
        else -> dateFocusRequester
    }
    val aboveListFocusRequester = if (showLiveNow) liveNowFocusRequester else chipsAboveFocusRequester
    // Down from the lowest chip row: the live line when shown, else the list.
    val belowChipsFocusRequester = if (showLiveNow) liveNowFocusRequester else entryFocusRequester
    val openList: () -> Unit = { runCatching { entryFocusRequester?.requestFocus() } }

    // A real day, sport or league change starts the new list at its top; the saved key survives navigation.
    var listedKey by rememberSaveable { mutableStateOf(listKey) }
    LaunchedEffect(listKey) {
        if (listKey == listedKey) return@LaunchedEffect
        listedKey = listKey
        focusedKey = null
        listState.scrollToItem(0)
        // The list in focus was replaced under it (the day moved on at midnight): focus goes to the date rail.
        if (focusedListKey != null && focusedListKey != listKey) {
            focusedListKey = null
            withFrameNanos { }
            runCatching { dateFocusRequester.requestFocus() }
        }
    }
    // Back from a replay: focus returns to the row that opened it, once its day has loaded. Once per
    // entry (not saved), so every return restores, like the section's cards.
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
    // A reload or a new sport can drop a chip row while one of its chips holds focus: focus goes to
    // the row above instead of being lost. Losing focus because the row is being removed keeps the flag.
    var sportRowHasFocus by remember { mutableStateOf(false) }
    var leagueRowHasFocus by remember { mutableStateOf(false) }
    val currentShowSportRow by rememberUpdatedState(showSportRow)
    val currentShowLeagueRow by rememberUpdatedState(showLeagueRow)
    LaunchedEffect(showSportRow) {
        if (showSportRow || !sportRowHasFocus) return@LaunchedEffect
        sportRowHasFocus = false
        withFrameNanos { }
        runCatching { dateFocusRequester.requestFocus() }
    }
    LaunchedEffect(showLeagueRow) {
        if (showLeagueRow || !leagueRowHasFocus) return@LaunchedEffect
        leagueRowHasFocus = false
        withFrameNanos { }
        runCatching { (if (currentShowSportRow) sportFocusRequester else dateFocusRequester).requestFocus() }
    }
    // A refresh with no game left live drops the live line: focus goes to the chip row above it.
    var liveLineHasFocus by remember { mutableStateOf(false) }
    val currentShowLiveNow by rememberUpdatedState(showLiveNow)
    LaunchedEffect(showLiveNow) {
        if (showLiveNow || !liveLineHasFocus) return@LaunchedEffect
        liveLineHasFocus = false
        withFrameNanos { }
        runCatching { chipsAboveFocusRequester.requestFocus() }
    }
    BackHandler(enabled = listHasFocus) {
        runCatching { tabFocusRequester.requestFocus() }
    }
    // Hold OK on a row: Game stats and Follow / Following for each side (away first, as listed).
    var menuRowKey by remember { mutableStateOf<String?>(null) }
    // Golf rows whose top five OK has listed.
    var expandedGolf by remember { mutableStateOf<Set<String>>(emptySet()) }
    items.firstNotNullOfOrNull { (it as? ResultListItem.Row)?.row?.takeIf { row -> row.key == menuRowKey } }?.let { row ->
        val sides = listOf(ScoreSide.AWAY to row.event.away, ScoreSide.HOME to row.event.home)
        SportsLiveCardMenu(
            title = stringResource(R.string.sports_live_results_menu_title, row.event.away.listName, row.event.home.listName),
            sides = sides.map { (side, team) -> team.listName to isFollowed(row, side) },
            onToggleFollow = { name ->
                menuRowKey = null
                sides.firstOrNull { it.second.listName == name }?.let { (side, _) -> onToggleFollow(row, side) }
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

    Column(modifier = modifier.fillMaxSize()) {
        ResultsDateRow(
            state = state,
            dateFocusRequester = dateFocusRequester,
            upFocusRequester = tabFocusRequester,
            downFocusRequester = if (showSportRow) sportFocusRequester else belowChipsFocusRequester,
            onSelectDay = onSelectDay,
            onToggleEarlier = onToggleEarlier,
            onOpen = openList,
            modifier = Modifier.padding(top = NuvioTheme.spacing.xs)
        )
        if (showSportRow) {
            ResultsSportRow(
                state = state,
                sportFocusRequester = sportFocusRequester,
                upFocusRequester = dateFocusRequester,
                downFocusRequester = if (showLeagueRow) leagueFocusRequester else belowChipsFocusRequester,
                onSelectSport = onSelectSport,
                onEditMySports = onEditMySports,
                onOpen = openList,
                modifier = Modifier
                    .padding(top = NuvioTheme.spacing.xs)
                    .onFocusChanged { focus ->
                        if (focus.hasFocus) sportRowHasFocus = true else if (currentShowSportRow) sportRowHasFocus = false
                    }
            )
        }
        if (showLeagueRow) {
            ResultsLeagueRow(
                state = state,
                leagueFocusRequester = leagueFocusRequester,
                upFocusRequester = sportFocusRequester,
                downFocusRequester = belowChipsFocusRequester,
                onSelectLeague = onSelectLeague,
                onOpen = openList,
                modifier = Modifier
                    .padding(top = NuvioTheme.spacing.xs)
                    .onFocusChanged { focus ->
                        if (focus.hasFocus) leagueRowHasFocus = true else if (currentShowLeagueRow) leagueRowHasFocus = false
                    }
            )
        }
        if (showLiveNow) {
            ResultsLiveNowLine(
                count = liveGames,
                onClick = onOpenLiveNow,
                modifier = Modifier
                    .padding(start = NuvioTheme.spacing.sm, top = NuvioTheme.spacing.sm)
                    .onFocusChanged { focus ->
                        if (focus.hasFocus) liveLineHasFocus = true else if (currentShowLiveNow) liveLineHasFocus = false
                    }
                    .focusRequester(liveNowFocusRequester)
                    .focusProperties {
                        up = chipsAboveFocusRequester
                        down = entryFocusRequester ?: FocusRequester.Cancel
                        left = FocusRequester.Cancel
                        right = FocusRequester.Cancel
                    }
            )
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
        when {
            state.status == ResultsStatus.OFF -> Unit
            state.status == ResultsStatus.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
            state.status == ResultsStatus.NO_LEAGUES ->
                EmptyScreenState(title = stringResource(R.string.sports_live_results_no_leagues))
            state.status == ResultsStatus.FAILED -> Text(
                text = stringResource(R.string.sports_live_results_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = NuvioTheme.colors.TextTertiary,
                modifier = Modifier.padding(horizontal = NuvioTheme.spacing.sm)
            )
            // ★ My sports has nothing that day but other sports do: point at their chips.
            firstFocusableKey == null && state.selectedSport == null && state.sports.isNotEmpty() ->
                EmptyScreenState(title = stringResource(R.string.sports_live_results_my_sports_empty))
            // Today before its first final: after midnight, last night's games are under Yesterday (#78).
            offersYesterday -> ResultsEmptyToday(
                empty = resultsTodayEmpty(remember(selectedDate) { LocalTime.now().hour }),
                onSeeYesterday = { onSelectDay(SportsResults.YESTERDAY) },
                focusRequester = yesterdayFocusRequester,
                upFocusRequester = aboveListFocusRequester
            )
            firstFocusableKey == null -> EmptyScreenState(title = stringResource(R.string.sports_live_results_empty_day))
            else -> androidx.compose.runtime.key(listKey) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .widthIn(max = 880.dp)
                        .fillMaxWidth()
                        .onFocusChanged { focus ->
                            listHasFocus = focus.hasFocus
                            if (focus.hasFocus) focusedListKey = listKey else if (currentListKey == listKey) focusedListKey = null
                            if (!focus.hasFocus) onRowFocus(null)
                        }
                        .focusRestorer(entryFocusRequester ?: itemFocusRequester(firstFocusableKey))
                        .dpadRepeatThrottle(),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                ) {
                    itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                        val focusModifier = Modifier
                            .focusRequester(itemFocusRequester(item.key))
                            // Up from the first row: the live line when shown, never past it; else the chips.
                            .then(if (item.key == firstFocusableKey) Modifier.upTo(aboveListFocusRequester) else Modifier)
                            .focusProperties {
                                if (item.key == firstFocusableKey) up = aboveListFocusRequester
                                // No pane to the right: Left/Right stay on the row.
                                left = FocusRequester.Cancel
                                right = FocusRequester.Cancel
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
                                        focusedKey = item.key
                                        onRowFocus(item.key)
                                        if (item.key == firstFocusableKey) scope.launch { listState.scrollToItem(0) }
                                    },
                                    modifier = focusModifier
                                )
                            } else ResultRowCard(
                                row = item.row,
                                showScore = state.showsScore(item.row),
                                hasReplay = item.hasReplay,
                                onClick = { onOpen(item.row) },
                                onLongClick = { menuRowKey = item.key },
                                menuOpen = menuRowKey == item.key,
                                onFocus = {
                                    focusedKey = item.key
                                    onRowFocus(item.key)
                                    // The first row shows its section header too (Up from it leaves the list).
                                    if (item.key == firstFocusableKey) scope.launch { listState.scrollToItem(0) }
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

@Composable
private fun ResultsDateRow(
    state: SportsLiveResultsUiState,
    dateFocusRequester: FocusRequester,
    upFocusRequester: FocusRequester,
    downFocusRequester: FocusRequester?,
    onSelectDay: (Int) -> Unit,
    onToggleEarlier: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val today = stringResource(R.string.sports_live_results_today)
    val yesterday = stringResource(R.string.sports_live_results_yesterday)
    val dayFormat = remember { DateTimeFormatter.ofPattern("EEE d", Locale.getDefault()) }
    // Until the opening day is worked out, Today holds the rail's focus.
    val focusDay = state.daysAgo ?: SportsResults.TODAY
    val dayChip: @Composable (ResultDay) -> Unit = { day ->
        val days = day.daysAgo
        val isSelected = days == state.daysAgo
        SportsLiveFilterChip(
            // "Today" / "Yesterday" only when the ESPN (US Eastern) day is also the device's; else its date.
            name = when {
                day.isDeviceToday -> today
                day.isDeviceYesterday -> yesterday
                else -> day.date.dayLabel(dayFormat)
            },
            isSelected = isSelected,
            // #79: focus only highlights; OK picks the day, OK on the picked one opens the list.
            onClick = sportsLiveChipClick(isSelected, onSelect = { onSelectDay(days) }, onOpenList = onOpen),
            modifier = Modifier
                .then(if (days == focusDay) Modifier.focusRequester(dateFocusRequester) else Modifier)
                .focusProperties {
                    up = upFocusRequester
                    down = downFocusRequester ?: FocusRequester.Cancel
                }
        )
    }
    ResultsChipRow(focusRequester = dateFocusRequester, modifier = modifier) {
        state.dates.filter { it.daysAgo !in SportsResults.EARLIER }.forEach { dayChip(it) }
        // A toggle, not a day: OK opens the older days after it, in place.
        SportsLiveFilterChip(
            name = stringResource(if (state.earlierOpen) R.string.sports_live_results_earlier_open else R.string.sports_live_results_earlier),
            isSelected = false,
            onClick = onToggleEarlier,
            modifier = Modifier.focusProperties {
                up = upFocusRequester
                down = downFocusRequester ?: FocusRequester.Cancel
            }
        )
        state.dates.filter { it.daysAgo in SportsResults.EARLIER }.forEach { dayChip(it) }
    }
}

/** "3 games live now →" above Today's results: OK opens the Live Now tab. */
@Composable
private fun ResultsLiveNowLine(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    LightweightCard(
        onClick = onClick,
        modifier = modifier,
        shape = shape,
        containerColor = LIVE_NOW_LINE_BACKGROUND,
        focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm)
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(LIVE_NOW_DOT))
            Text(
                text = pluralStringResource(R.plurals.sports_live_results_live_now, count, count),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = LIVE_NOW_TEXT,
                maxLines = 1
            )
        }
    }
}

// The design's live line: dark red card, red dot, light red text.
private val LIVE_NOW_LINE_BACKGROUND = Color(0xFF1A1012)
private val LIVE_NOW_DOT = Color(0xFFFF4D57)
private val LIVE_NOW_TEXT = Color(0xFFFFB3B8)

/**
 * A chip of a Results chip row (#79): focus only highlights it; OK selects it, and OK on the selected
 * one opens the list. The selected one takes [activeFocusRequester]; [count] follows its name.
 */
@Composable
private fun ResultsChip(
    name: String,
    isSelected: Boolean,
    activeFocusRequester: FocusRequester,
    upFocusRequester: FocusRequester,
    downFocusRequester: FocusRequester?,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
    count: Int? = null,
    icon: SportsLiveChipIcon = SportsLiveChipIcon.NONE
) {
    SportsLiveFilterChip(
        name = name,
        isSelected = isSelected,
        count = count,
        icon = icon,
        onClick = sportsLiveChipClick(isSelected, onSelect = onSelect, onOpenList = onOpen),
        modifier = Modifier
            .then(if (isSelected) Modifier.focusRequester(activeFocusRequester) else Modifier)
            .focusProperties {
                up = upFocusRequester
                down = downFocusRequester ?: FocusRequester.Cancel
            }
    )
}

/** ★ My sports, then the user's sports with results that day. */
@Composable
private fun ResultsSportRow(
    state: SportsLiveResultsUiState,
    sportFocusRequester: FocusRequester,
    upFocusRequester: FocusRequester,
    downFocusRequester: FocusRequester?,
    onSelectSport: (MySport?) -> Unit,
    onEditMySports: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ResultsChipRow(focusRequester = sportFocusRequester, modifier = Modifier.weight(1f)) {
            ResultsChip(
                name = stringResource(R.string.sports_live_results_my_sports),
                isSelected = state.selectedSport == null,
                count = state.sportCounts[null],
                // A grid, not the star of ★ My teams (#79): these are sports, not followed teams.
                icon = SportsLiveChipIcon.MY_SPORTS,
                activeFocusRequester = sportFocusRequester,
                upFocusRequester = upFocusRequester,
                downFocusRequester = downFocusRequester,
                onSelect = { onSelectSport(null) },
                onOpen = onOpen
            )
            state.sports.forEach { sport ->
                ResultsChip(
                    name = mySportLabel(sport),
                    isSelected = sport == state.selectedSport,
                    count = state.sportCounts[sport],
                    activeFocusRequester = sportFocusRequester,
                    upFocusRequester = upFocusRequester,
                    downFocusRequester = downFocusRequester,
                    onSelect = { onSelectSport(sport) },
                    onOpen = onOpen
                )
            }
        }
        // A link, not a filter: OK opens My sports in Settings. Outside the
        // scrolling chips, so its label always shows whole.
        SourceStatusFilterChip(
            name = stringResource(R.string.sports_live_results_edit_my_sports),
            isSelected = false,
            onClick = onEditMySports,
            modifier = Modifier
                .padding(start = NuvioTheme.spacing.sm, end = NuvioTheme.spacing.sm)
                .focusProperties {
                    up = upFocusRequester
                    down = downFocusRequester ?: FocusRequester.Cancel
                }
        )
    }
}

/** The chosen sport's leagues present that day, after All (#52). */
@Composable
private fun ResultsLeagueRow(
    state: SportsLiveResultsUiState,
    leagueFocusRequester: FocusRequester,
    upFocusRequester: FocusRequester,
    downFocusRequester: FocusRequester?,
    onSelectLeague: (String?) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    ResultsChipRow(focusRequester = leagueFocusRequester, modifier = modifier) {
        ResultsChip(
            name = stringResource(R.string.sports_live_filter_all),
            isSelected = state.selectedLeague == null,
            count = state.leagueCounts[null],
            activeFocusRequester = leagueFocusRequester,
            upFocusRequester = upFocusRequester,
            downFocusRequester = downFocusRequester,
            onSelect = { onSelectLeague(null) },
            onOpen = onOpen
        )
        state.leagues.forEach { league ->
            ResultsChip(
                name = league.name ?: sportLabel(league.sport),
                isSelected = league.key == state.selectedLeague,
                count = state.leagueCounts[league.key],
                activeFocusRequester = leagueFocusRequester,
                upFocusRequester = upFocusRequester,
                downFocusRequester = downFocusRequester,
                onSelect = { onSelectLeague(league.key) },
                onOpen = onOpen
            )
        }
    }
}

/** A section header: YOUR TEAMS (gold) or the competition's name. */
@Composable
private fun ResultsHeader(item: ResultListItem.Header, first: Boolean) {
    val text = when {
        item.yourTeams -> stringResource(R.string.sports_live_results_your_teams)
        item.name != null -> item.name
        else -> sportLabel(item.sport.orEmpty())
    }
    Text(
        text = text.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        color = if (item.yourTeams) RESULT_STAR_COLOR else NuvioTheme.colors.TextSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(
            start = NuvioTheme.spacing.xs,
            top = if (first) 0.dp else NuvioTheme.spacing.md
        )
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
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    val dashColor = NuvioTheme.colors.TextTertiary
    val league = item.name ?: sportLabel(item.sport)
    LightweightCard(
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
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())))
                )
            },
        shape = shape,
        containerColor = Color.Transparent,
        focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
    ) {
        Text(
            text = pluralStringResource(R.plurals.sports_live_results_more, item.count, item.count, league),
            style = MaterialTheme.typography.titleSmall,
            color = NuvioTheme.colors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm)
        )
    }
}

/** A chip row that, entered from elsewhere, lands on its active chip ([focusRequester]), never the nearest. */
@Composable
private fun ResultsChipRow(focusRequester: FocusRequester, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusProperties { onEnter = { focusRequester.requestFocus() } }
            .focusGroup()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) { content() }
}

@Composable
private fun ResultRowCard(
    row: ResultRow,
    showScore: Boolean,
    hasReplay: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    menuOpen: Boolean,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    val event = row.event
    // The winner bold (name and score), never while the score is hidden.
    val homeWon = sportsLiveWinnerBold(showScore, event.homeScore, event.awayScore)
    val awayWon = sportsLiveWinnerBold(showScore, event.awayScore, event.homeScore)
    val (longPress, click) = rememberSportsLiveLongPress(menuOpen = menuOpen, onLongClick = onLongClick, onClick = onClick)
    LightweightCard(
        onClick = click,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocus() }
            .then(longPress),
        shape = shape,
        containerColor = NuvioTheme.colors.BackgroundCard,
        focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
    ) {
        // About 70 px at 1080p (ResultRowMetrics): the league is the section's header, so the row is the two
        // teams and their scores, with the note and the Replay pill beside them (QA pass 3).
        Row(
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = ResultRowMetrics.PADDING_DP.dp),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (row.followed) {
                Icon(
                    imageVector = Icons.Rounded.Star,
                    contentDescription = null,
                    tint = RESULT_STAR_COLOR,
                    modifier = Modifier.size(16.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                // Away first, as ESPN and US scoreboards list them.
                TeamLine(event.away, bold = awayWon)
                TeamLine(event.home, bold = homeWon)
            }
            Column(horizontalAlignment = Alignment.End) {
                if (showScore) {
                    Text(
                        text = event.awayScore?.toString() ?: "–",
                        style = resultLineStyle().let { if (awayWon) it.sportsLiveBold() else it },
                        color = NuvioTheme.colors.TextPrimary
                    )
                    Text(
                        text = event.homeScore?.toString() ?: "–",
                        style = resultLineStyle().let { if (homeWon) it.sportsLiveBold() else it },
                        color = NuvioTheme.colors.TextPrimary
                    )
                } else {
                    Text(
                        text = stringResource(R.string.sports_live_results_score_hidden),
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextTertiary
                    )
                }
            }
            Row(
                modifier = Modifier.width(RESULT_NOTE_WIDTH),
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // The note ("AET", "Pens", "OT") tells how the game ended, so it hides with the score.
                Text(
                    text = if (showScore) stringResource(row.note.labelRes()) else stringResource(R.string.sports_live_results_note_ft),
                    style = MaterialTheme.typography.labelMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1
                )
                // Only a strict replay match gets the tag (#51); it stays visible while a score is hidden.
                if (hasReplay) ReplayPill()
            }
        }
    }
}

/**
 * A Results row's size (QA pass 3, `Results.dc.html`): two team lines of [LINE_SP] and [PADDING_DP] above
 * and below, about 70 px at 1080p (2 px a dp), so about nine rows fit as drawn.
 */
internal object ResultRowMetrics {
    const val LINE_SP = 16
    const val FONT_SP = 14
    const val LOGO_DP = 16
    const val PADDING_DP = 2

    /** The row's height in dp. */
    const val HEIGHT_DP = 2 * LINE_SP + 2 * PADDING_DP
}

@Composable
private fun resultLineStyle() =
    MaterialTheme.typography.titleSmall.copy(fontSize = ResultRowMetrics.FONT_SP.sp, lineHeight = ResultRowMetrics.LINE_SP.sp)

private val RESULT_NOTE_WIDTH = 120.dp

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
private fun TeamLine(team: ScoreTeam, bold: Boolean) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val logo = team.logo
        if (logo != null) {
            AsyncImage(
                model = rememberSizedImage(TeamLogoUrls.scaled(logo), ResultRowMetrics.LOGO_DP.dp, ResultRowMetrics.LOGO_DP.dp),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(ResultRowMetrics.LOGO_DP.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(ResultRowMetrics.LOGO_DP.dp)
                    .clip(CircleShape)
                    .background(NuvioTheme.colors.PosterFallback)
            )
        }
        Text(
            text = team.listName,
            style = resultLineStyle().let { if (bold) it.sportsLiveBold() else it },
            color = NuvioTheme.colors.TextPrimary,
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

private fun FinalNote.labelRes(): Int = when (this) {
    FinalNote.FT -> R.string.sports_live_results_note_ft
    FinalNote.OT -> R.string.sports_live_results_note_ot
    FinalNote.SO -> R.string.sports_live_results_note_so
    FinalNote.AET -> R.string.sports_live_results_note_aet
    FinalNote.PENS -> R.string.sports_live_results_note_pens
}

/** "Mon 15" in the device locale. */
private fun LocalDate.dayLabel(format: DateTimeFormatter): String = format.format(this)

private val RESULT_STAR_COLOR = Color(0xFFFFC107)
private val REPLAY_PILL_COLOR = Color(0xFF7EE0A1)
private val REPLAY_PILL_TEXT = Color(0xFF0B1F12)
