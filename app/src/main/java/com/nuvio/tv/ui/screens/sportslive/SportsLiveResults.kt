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
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
import com.nuvio.tv.core.sportslive.scores.ResultRow
import com.nuvio.tv.core.sportslive.scores.ScoreTeam
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.LightweightCard
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.SourceStatusFilterChip
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.dpadRepeatThrottle
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Results tab: date chips (Yesterday … 7 days ago), the sport / ★ My teams filter chips, and the
 * day's finals. Chips select on focus like the section's tabs and filters; Down from a chip row lands
 * on the active chip below or the list's entry row; a new day or filter opens the list at the top.
 */
@Composable
internal fun SportsLiveResultsView(
    state: SportsLiveResultsUiState,
    /** The active tab: Up from the date chips, Back from the list. */
    tabFocusRequester: FocusRequester,
    /** On the selected date chip: the tab row's Down. */
    dateFocusRequester: FocusRequester,
    onSelectDay: (Int) -> Unit,
    onSelectFilter: (String?) -> Unit,
    onRowFocus: (String?) -> Unit,
    onOpen: (ResultRow) -> Unit,
    modifier: Modifier = Modifier
) {
    val filterFocusRequester = remember { FocusRequester() }
    val showFilterRow = state.status == ResultsStatus.READY && state.filters.size >= 2
    val rowFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    fun rowFocusRequester(row: ResultRow) = rowFocusRequesters.getOrPut(row.key) { FocusRequester() }

    // Saved with the back stack entry, so returning from a replay refocuses its row.
    var focusedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var listHasFocus by remember { mutableStateOf(false) }
    val listKey = "${state.daysAgo}|${state.selectedFilter.orEmpty()}"
    val listState = rememberSaveable(listKey, saver = LazyListState.Saver) { LazyListState() }
    val rows = if (state.status == ResultsStatus.READY) state.rows else emptyList()

    val currentKeys by rememberUpdatedState(rows.map { it.key })
    val entryKey by remember(listState) {
        derivedStateOf {
            sportsLiveListEntryKey(currentKeys, focusedKey, listState.layoutInfo.visibleItemsInfo.map { it.index })
        }
    }
    val entryRowFocusRequester = rows.firstOrNull { it.key == entryKey }?.let(::rowFocusRequester)
    val aboveListFocusRequester = if (showFilterRow) filterFocusRequester else dateFocusRequester
    val openList: () -> Unit = { runCatching { entryRowFocusRequester?.requestFocus() } }

    // A real day or filter change starts the new list at its top; the saved key survives navigation.
    var listedKey by rememberSaveable { mutableStateOf(listKey) }
    LaunchedEffect(listKey) {
        if (listKey == listedKey) return@LaunchedEffect
        listedKey = listKey
        focusedKey = null
        listState.scrollToItem(0)
    }
    // Back from a replay: focus returns to the row that opened it, once its day has loaded.
    var restored by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(rows) {
        if (restored) return@LaunchedEffect
        val key = focusedKey ?: return@LaunchedEffect
        val index = rows.indexOfFirst { it.key == key }
        if (index < 0) return@LaunchedEffect
        restored = true
        listState.scrollToItem(index)
        repeat(2) { withFrameNanos { } }
        runCatching { rowFocusRequesters[key]?.requestFocus() }
    }
    BackHandler(enabled = listHasFocus) {
        runCatching { tabFocusRequester.requestFocus() }
    }

    Column(modifier = modifier.fillMaxSize()) {
        ResultsDateRow(
            state = state,
            dateFocusRequester = dateFocusRequester,
            upFocusRequester = tabFocusRequester,
            downFocusRequester = if (showFilterRow) filterFocusRequester else entryRowFocusRequester,
            onSelectDay = onSelectDay,
            onOpen = openList,
            modifier = Modifier.padding(top = NuvioTheme.spacing.xs)
        )
        if (showFilterRow) {
            ResultsFilterRow(
                state = state,
                filterFocusRequester = filterFocusRequester,
                upFocusRequester = dateFocusRequester,
                downFocusRequester = entryRowFocusRequester,
                onSelectFilter = onSelectFilter,
                onOpen = openList,
                modifier = Modifier.padding(top = NuvioTheme.spacing.xs)
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
            rows.isEmpty() -> EmptyScreenState(title = stringResource(R.string.sports_live_results_empty_day))
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
                        .focusRestorer(entryRowFocusRequester ?: rowFocusRequester(rows.first()))
                        .dpadRepeatThrottle(),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
                ) {
                    itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
                        ResultRowCard(
                            row = row,
                            showScore = state.showsScore(row),
                            hasReplay = row.key in state.replays,
                            onClick = { onOpen(row) },
                            onFocus = {
                                focusedKey = row.key
                                onRowFocus(row.key)
                            },
                            modifier = Modifier
                                .focusRequester(rowFocusRequester(row))
                                .focusProperties {
                                    if (index == 0) up = aboveListFocusRequester
                                    // No pane to the right: Left/Right stay on the row.
                                    left = FocusRequester.Cancel
                                    right = FocusRequester.Cancel
                                }
                        )
                    }
                }
            }
        }
    }
}

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
        state.dates.forEach { (days, date) ->
            val isSelected = days == state.daysAgo
            SourceStatusFilterChip(
                name = if (days == 1) yesterday else date.dayLabel(dayFormat),
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

@Composable
private fun ResultsFilterRow(
    state: SportsLiveResultsUiState,
    filterFocusRequester: FocusRequester,
    upFocusRequester: FocusRequester,
    downFocusRequester: FocusRequester?,
    onSelectFilter: (String?) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    @Composable
    fun Chip(name: String, value: String?) {
        val isSelected = value == state.selectedFilter
        SourceStatusFilterChip(
            name = name,
            isSelected = isSelected,
            outlined = true,
            onFocusSelect = { onSelectFilter(value) },
            onClick = onOpen,
            modifier = Modifier
                .then(if (isSelected) Modifier.focusRequester(filterFocusRequester) else Modifier)
                .focusProperties {
                    up = upFocusRequester
                    down = downFocusRequester ?: FocusRequester.Cancel
                }
        )
    }
    ResultsChipRow(focusRequester = filterFocusRequester, modifier = modifier) {
        Chip(stringResource(R.string.sports_live_filter_all), null)
        state.filters.forEach { filter ->
            Chip(if (filter == SportsLiveUiState.MY_TEAMS_FILTER) stringResource(R.string.sports_live_filter_my_teams) else sportLabel(filter), filter)
        }
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
                modifier = Modifier.width(56.dp),
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
                if (hasReplay) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(R.string.sports_live_results_replay),
                        tint = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
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
