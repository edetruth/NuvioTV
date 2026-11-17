package com.nuvio.tv.ui.screens.sportslive

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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
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
import androidx.tv.material3.Border
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.scores.FinalNote
import com.nuvio.tv.core.sportslive.scores.MySport
import com.nuvio.tv.core.sportslive.scores.ResultRow
import com.nuvio.tv.core.sportslive.scores.ScoreTeam
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.LightweightCard
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.SourceStatusFilterChip
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.dpadRepeatThrottle
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Results tab: date chips (Yesterday … 7 days ago), the ★ My sports / sport chips, the chosen
 * sport's league chips, and the day's finals under league headers (YOUR TEAMS first, long tails
 * collapsed). Chips select on focus like the section's tabs and filters; Down from a chip row lands on
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
    onSelectSport: (MySport?) -> Unit,
    onSelectLeague: (String?) -> Unit,
    onExpandGroup: (String) -> Unit,
    /** "Edit my sports": Settings → Sports & Live → My sports. */
    onEditMySports: () -> Unit,
    onRowFocus: (String?) -> Unit,
    onOpen: (ResultRow) -> Unit,
    modifier: Modifier = Modifier
) {
    val sportFocusRequester = remember { FocusRequester() }
    val leagueFocusRequester = remember { FocusRequester() }
    val ready = state.status == ResultsStatus.READY
    val showSportRow = ready && state.sports.isNotEmpty()
    val showLeagueRow = showSportRow && state.selectedSport != null && state.leagues.isNotEmpty()
    val itemFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val scope = rememberCoroutineScope()
    fun itemFocusRequester(key: String) = itemFocusRequesters.getOrPut(key) { FocusRequester() }

    // Saved with the back stack entry, so returning from a replay refocuses its row.
    var focusedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var listHasFocus by remember { mutableStateOf(false) }
    val listKey = "${state.daysAgo}|${state.selectedSport?.id.orEmpty()}|${state.selectedLeague.orEmpty()}"
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
    val entryFocusRequester = entryKey?.let(::itemFocusRequester)
    val aboveListFocusRequester = when {
        showLeagueRow -> leagueFocusRequester
        showSportRow -> sportFocusRequester
        else -> dateFocusRequester
    }
    val openList: () -> Unit = { runCatching { entryFocusRequester?.requestFocus() } }

    // A real day, sport or league change starts the new list at its top; the saved key survives navigation.
    var listedKey by rememberSaveable { mutableStateOf(listKey) }
    LaunchedEffect(listKey) {
        if (listKey == listedKey) return@LaunchedEffect
        listedKey = listKey
        focusedKey = null
        listState.scrollToItem(0)
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
    BackHandler(enabled = listHasFocus) {
        runCatching { tabFocusRequester.requestFocus() }
    }

    Column(modifier = modifier.fillMaxSize()) {
        ResultsDateRow(
            state = state,
            dateFocusRequester = dateFocusRequester,
            upFocusRequester = tabFocusRequester,
            downFocusRequester = if (showSportRow) sportFocusRequester else entryFocusRequester,
            onSelectDay = onSelectDay,
            onOpen = openList,
            modifier = Modifier.padding(top = NuvioTheme.spacing.xs)
        )
        if (showSportRow) {
            ResultsSportRow(
                state = state,
                sportFocusRequester = sportFocusRequester,
                upFocusRequester = dateFocusRequester,
                downFocusRequester = if (showLeagueRow) leagueFocusRequester else entryFocusRequester,
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
                downFocusRequester = entryFocusRequester,
                onSelectLeague = onSelectLeague,
                onOpen = openList,
                modifier = Modifier
                    .padding(top = NuvioTheme.spacing.xs)
                    .onFocusChanged { focus ->
                        if (focus.hasFocus) leagueRowHasFocus = true else if (currentShowLeagueRow) leagueRowHasFocus = false
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
            firstFocusableKey == null -> EmptyScreenState(title = stringResource(R.string.sports_live_results_empty_day))
            else -> androidx.compose.runtime.key(listKey) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .widthIn(max = 880.dp)
                        .fillMaxWidth()
                        .onFocusChanged { focus ->
                            listHasFocus = focus.hasFocus
                            if (!focus.hasFocus) onRowFocus(null)
                        }
                        .focusRestorer(entryFocusRequester ?: itemFocusRequester(firstFocusableKey))
                        .dpadRepeatThrottle(),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                ) {
                    itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                        val focusModifier = Modifier
                            .focusRequester(itemFocusRequester(item.key))
                            .focusProperties {
                                if (item.key == firstFocusableKey) up = aboveListFocusRequester
                                // No pane to the right: Left/Right stay on the row.
                                left = FocusRequester.Cancel
                                right = FocusRequester.Cancel
                            }
                        when (item) {
                            is ResultListItem.Header -> ResultsHeader(item, first = index == 0)
                            is ResultListItem.Row -> ResultRowCard(
                                row = item.row,
                                showScore = state.showsScore(item.row),
                                hasReplay = item.hasReplay,
                                onClick = { onOpen(item.row) },
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

@Composable
private fun ResultsDateRow(
    state: SportsLiveResultsUiState,
    dateFocusRequester: FocusRequester,
    upFocusRequester: FocusRequester,
    downFocusRequester: FocusRequester?,
    onSelectDay: (Int) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val yesterday = stringResource(R.string.sports_live_results_yesterday)
    val dayFormat = remember { DateTimeFormatter.ofPattern("EEE d", Locale.getDefault()) }
    ResultsChipRow(focusRequester = dateFocusRequester, modifier = modifier) {
        state.dates.forEach { day ->
            val days = day.daysAgo
            val isSelected = days == state.daysAgo
            SourceStatusFilterChip(
                // "Yesterday" only when the ESPN (US Eastern) day is also the device's yesterday.
                name = if (day.isDeviceYesterday) yesterday else day.date.dayLabel(dayFormat),
                isSelected = isSelected,
                outlined = true,
                onFocusSelect = { onSelectDay(days) },
                onClick = onOpen,
                modifier = Modifier
                    .then(if (isSelected) Modifier.focusRequester(dateFocusRequester) else Modifier)
                    .focusProperties {
                        up = upFocusRequester
                        down = downFocusRequester ?: FocusRequester.Cancel
                    }
            )
        }
    }
}

/** A chip of a Results chip row: selects on focus, OK opens the list; the selected one takes [activeFocusRequester]. */
@Composable
private fun ResultsChip(
    name: String,
    isSelected: Boolean,
    activeFocusRequester: FocusRequester,
    upFocusRequester: FocusRequester,
    downFocusRequester: FocusRequester?,
    onSelect: () -> Unit,
    onOpen: () -> Unit
) {
    SourceStatusFilterChip(
        name = name,
        isSelected = isSelected,
        outlined = true,
        onFocusSelect = onSelect,
        onClick = onOpen,
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
    ResultsChipRow(focusRequester = sportFocusRequester, modifier = modifier) {
        ResultsChip(
            name = stringResource(R.string.sports_live_results_my_sports),
            isSelected = state.selectedSport == null,
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
                activeFocusRequester = sportFocusRequester,
                upFocusRequester = upFocusRequester,
                downFocusRequester = downFocusRequester,
                onSelect = { onSelectSport(sport) },
                onOpen = onOpen
            )
        }
        // A link, not a filter: focus does not select it; OK opens My sports in Settings.
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.lg))
        SourceStatusFilterChip(
            name = stringResource(R.string.sports_live_results_edit_my_sports),
            isSelected = false,
            onClick = onEditMySports,
            modifier = Modifier.focusProperties {
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
                drawRoundRect(
                    color = dashColor,
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())))
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
    onFocus: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    val event = row.event
    LightweightCard(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocus() },
        shape = shape,
        containerColor = NuvioTheme.colors.BackgroundCard,
        focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
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
                    Text(
                        text = event.leagueName ?: sportLabel(event.league.sport),
                        style = MaterialTheme.typography.labelMedium,
                        color = NuvioTheme.colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val homeWon = showScore && (event.homeScore ?: 0) > (event.awayScore ?: 0)
                val awayWon = showScore && (event.awayScore ?: 0) > (event.homeScore ?: 0)
                // Away first, as ESPN and US scoreboards list them.
                TeamLine(event.away, bold = awayWon)
                TeamLine(event.home, bold = homeWon)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)) {
                if (showScore) {
                    Text(
                        text = event.awayScore?.toString() ?: "–",
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioTheme.colors.TextPrimary
                    )
                    Text(
                        text = event.homeScore?.toString() ?: "–",
                        style = MaterialTheme.typography.titleMedium,
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
            Column(
                modifier = Modifier.width(72.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
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

/** The green "Replay" tag of a result with a replay. */
@Composable
private fun ReplayPill() {
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
                model = logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(24.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(NuvioTheme.colors.PosterFallback)
            )
        }
        Text(
            text = team.displayName ?: team.shortDisplayName ?: team.name ?: team.abbreviation.orEmpty(),
            style = MaterialTheme.typography.titleMedium.let { if (bold) it.copy(fontWeight = FontWeight.Bold) else it },
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** An ESPN sport key as a chip or row label. */
@Composable
private fun sportLabel(sport: String): String = when (sport) {
    "basketball" -> stringResource(R.string.sports_live_results_sport_basketball)
    "football" -> stringResource(R.string.sports_live_results_sport_football)
    "hockey" -> stringResource(R.string.sports_live_results_sport_hockey)
    "baseball" -> stringResource(R.string.sports_live_results_sport_baseball)
    "soccer" -> stringResource(R.string.sports_live_results_sport_soccer)
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
