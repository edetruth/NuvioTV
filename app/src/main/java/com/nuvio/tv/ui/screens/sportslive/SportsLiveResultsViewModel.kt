package com.nuvio.tv.ui.screens.sportslive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.sportslive.scores.ResultRow
import com.nuvio.tv.core.sportslive.scores.ResultsLoad
import com.nuvio.tv.core.sportslive.scores.ResultsRepository
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.SportsResults
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.domain.model.SportsLiveItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** What the Results view shows below its date chips. */
enum class ResultsStatus { LOADING, OFF, NO_LEAGUES, FAILED, READY }

data class SportsLiveResultsUiState(
    /** 1 = yesterday … 7. */
    val daysAgo: Int = 1,
    /** The date chips: days ago → the device date it names. */
    val dates: List<Pair<Int, LocalDate>> = emptyList(),
    val status: ResultsStatus = ResultsStatus.LOADING,
    /** [SportsLiveUiState.MY_TEAMS_FILTER] when a followed team played, then the ESPN sports present. */
    val filters: List<String> = emptyList(),
    val selectedFilter: String? = null,
    /** The day's rows under [selectedFilter], followed teams' games first. */
    val rows: List<ResultRow> = emptyList(),
    val mode: ScoresMode = ScoresMode.SHOW,
    /** Rows (event ids) focused long enough to reveal their score under Hide until focused. */
    val revealed: Set<String> = emptySet(),
    /** The replay each result opens, by event id. */
    val replays: Map<String, SportsLiveItem> = emptyMap()
) {
    fun showsScore(row: ResultRow): Boolean = ResultSpoilerReveal.showsScore(mode, revealed, row.event.eventId)
}

@HiltViewModel
class SportsLiveResultsViewModel @Inject constructor(
    private val results: ResultsRepository,
    prefs: SportsLivePreferencesDataStore
) : ViewModel() {

    private val active = MutableStateFlow(false)
    private val daysAgo = MutableStateFlow(1)
    private val filter = MutableStateFlow<String?>(null)
    private val replayItems = MutableStateFlow<List<SportsLiveItem>>(emptyList())
    private val load = MutableStateFlow<ResultsLoad?>(null)
    private val reveal = ResultSpoilerReveal(viewModelScope)
    private val mode = prefs.scoreSettings.map { it.mode }.distinctUntilChanged()

    private val replayIndex = combine(load, replayItems) { load, replays ->
        val loaded = load as? ResultsLoad.Loaded ?: return@combine emptyMap()
        SportsResults.replaysByEvent(loaded.events, replays, ZoneId.systemDefault(), System.currentTimeMillis())
    }.flowOn(Dispatchers.Default)

    val uiState: StateFlow<SportsLiveResultsUiState> = combine(
        combine(daysAgo, load, filter, ::Triple),
        mode,
        reveal.revealed,
        replayIndex
    ) { (days, load, selectedFilter), mode, revealed, replays ->
        val rows = (load as? ResultsLoad.Loaded)?.rows.orEmpty()
        val filters = resultFilters(rows)
        val applied = selectedFilter?.takeIf { it in filters }
        SportsLiveResultsUiState(
            daysAgo = days,
            dates = SportsResults.DAYS_AGO.map { it to results.dateFor(it) },
            status = if (mode == ScoresMode.OFF) ResultsStatus.OFF else statusOf(load),
            filters = filters,
            selectedFilter = applied,
            rows = filterResults(rows, applied),
            mode = mode,
            revealed = revealed,
            replays = replays
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SportsLiveResultsUiState())

    init {
        // A day loads while Results is the selected tab, after the date chip settles (chips select on
        // focus, so moving across them does not request each day). Past days do not change: no polling.
        viewModelScope.launch {
            combine(active, daysAgo, mode, prefs.followedTeams) { isActive, days, _, _ -> if (isActive) days else null }
                .distinctUntilChanged()
                .collectLatest { days ->
                    if (days == null) return@collectLatest
                    load.value = null
                    delay(DAY_SETTLE_MS)
                    load.value = results.load(days)
                }
        }
    }

    /** True while Results is the selected tab. */
    fun setActive(isActive: Boolean) {
        active.value = isActive
        if (!isActive) reveal.onFocus(null)
    }

    fun selectDay(days: Int) {
        if (days == daysAgo.value) return
        daysAgo.value = days
        filter.value = null
        reveal.onFocus(null)
    }

    fun selectFilter(value: String?) {
        filter.value = value
    }

    /** The row now focused (null when none): under Hide until focused its score shows after 1 s. */
    fun onRowFocus(eventId: String?) = reveal.onFocus(eventId)

    /** The section's Replays tab items, for opening a result's replay. */
    fun setReplays(items: List<SportsLiveItem>) {
        replayItems.value = items
    }

    /** Cards the section shows: their leagues count as seen for 7 days. */
    fun noteSeen(items: List<SportsLiveItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch(Dispatchers.Default) { runCatching { results.noteSeen(items) } }
    }

    private fun statusOf(load: ResultsLoad?): ResultsStatus = when (load) {
        null -> ResultsStatus.LOADING
        ResultsLoad.Off -> ResultsStatus.OFF
        ResultsLoad.NoLeagues -> ResultsStatus.NO_LEAGUES
        ResultsLoad.Failed -> ResultsStatus.FAILED
        is ResultsLoad.Loaded -> ResultsStatus.READY
    }

    private companion object {
        const val DAY_SETTLE_MS = 300L
    }
}

/** The Results filter chips: "★ My teams" when a followed team played, then the sports present (ESPN keys). */
internal fun resultFilters(rows: List<ResultRow>): List<String> {
    val sports = rows.map { it.event.league.sport }.distinct().sorted()
    return (if (rows.any { it.followed }) listOf(SportsLiveUiState.MY_TEAMS_FILTER) else emptyList()) + sports
}

/** [rows] under [filter] (null = All). */
internal fun filterResults(rows: List<ResultRow>, filter: String?): List<ResultRow> = when (filter) {
    null -> rows
    SportsLiveUiState.MY_TEAMS_FILTER -> rows.filter { it.followed }
    else -> rows.filter { it.event.league.sport == filter }
}

/**
 * Hide until focused: a row's score shows once the row has stayed focused for [revealAfterMs], and
 * stays shown while the Results view is open. Moving on before then reveals nothing.
 */
internal class ResultSpoilerReveal(private val scope: CoroutineScope, private val revealAfterMs: Long = REVEAL_AFTER_MS) {
    private val _revealed = MutableStateFlow<Set<String>>(emptySet())
    val revealed: StateFlow<Set<String>> = _revealed.asStateFlow()
    private var focused: String? = null
    private var job: Job? = null

    fun onFocus(id: String?) {
        if (id == focused) return
        focused = id
        job?.cancel()
        job = null
        if (id == null || id in _revealed.value) return
        job = scope.launch {
            delay(revealAfterMs)
            _revealed.update { it + id }
        }
    }

    companion object {
        const val REVEAL_AFTER_MS = 1_000L

        /** Show: always; Hide until focused: once revealed; Off: never. */
        fun showsScore(mode: ScoresMode, revealed: Set<String>, eventId: String): Boolean = when (mode) {
            ScoresMode.SHOW -> true
            ScoresMode.HIDE_UNTIL_FOCUSED -> eventId in revealed
            ScoresMode.OFF -> false
        }
    }
}

/** A result row's list key. */
internal val ResultRow.key: String get() = event.eventId
