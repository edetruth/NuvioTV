package com.nuvio.tv.ui.screens.sportslive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.sportslive.scores.MySport
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.ResultDay
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
import javax.inject.Inject

/** What the Results view shows below its date chips. */
enum class ResultsStatus { LOADING, OFF, NO_LEAGUES, FAILED, READY }

/** A league chip under the chosen sport: a league section present that day ([SportsResults.groupKey]). */
data class ResultLeagueChip(val key: String, val name: String?, val sport: String)

/** One line of the Results list: a league header, a result, or a collapsed long tail. */
sealed interface ResultListItem {
    val key: String

    /** A section header: the competition [name] (null: the sport's name), or YOUR TEAMS. */
    data class Header(val groupKey: String, val name: String?, val sport: String?, val yourTeams: Boolean) : ResultListItem {
        override val key: String get() = "h|$groupKey"
    }

    data class Row(val row: ResultRow, val hasReplay: Boolean) : ResultListItem {
        override val key: String get() = row.key
    }

    /** "+ [count] more ‹league› games"; OK lists them all, focus going to [firstHiddenKey]. */
    data class More(val groupKey: String, val count: Int, val name: String?, val sport: String, val firstHiddenKey: String) : ResultListItem {
        override val key: String get() = "m|$groupKey"
    }
}

data class SportsLiveResultsUiState(
    /** 1 = yesterday … 7. */
    val daysAgo: Int = 1,
    /** The date chips (ESPN days, counted back from today in US Eastern time). */
    val dates: List<ResultDay> = emptyList(),
    val status: ResultsStatus = ResultsStatus.LOADING,
    /** The sport chips after ★ My sports: the user's sports with results that day. */
    val sports: List<MySport> = emptyList(),
    /** The chosen sport; null is ★ My sports. */
    val selectedSport: MySport? = null,
    /** The chosen sport's league chips (after All): leagues present that day, when there are two or more. */
    val leagues: List<ResultLeagueChip> = emptyList(),
    /** The chosen league chip's key; null is All. */
    val selectedLeague: String? = null,
    /** The list: YOUR TEAMS first, then league sections, long tails collapsed. */
    val items: List<ResultListItem> = emptyList(),
    val mode: ScoresMode = ScoresMode.SHOW,
    /** False until the Scores setting has been read: the Results tab stays hidden until then. */
    val modeKnown: Boolean = false,
    /** Rows (event ids) focused long enough to reveal their score under Hide until focused. */
    val revealed: Set<String> = emptySet(),
    /** The replay each result opens, by event id (strict matches only). */
    val replays: Map<String, SportsLiveItem> = emptyMap()
) {
    fun showsScore(row: ResultRow): Boolean = ResultSpoilerReveal.showsScore(mode, revealed, row.event.eventId)
}

@HiltViewModel
class SportsLiveResultsViewModel @Inject constructor(
    private val results: ResultsRepository,
    prefs: SportsLivePreferencesDataStore
) : ViewModel() {

    private data class Selection(val sport: MySport? = null, val league: String? = null, val expanded: Set<String> = emptySet())

    private val active = MutableStateFlow(false)
    private val daysAgo = MutableStateFlow(1)
    private val selection = MutableStateFlow(Selection())
    private val replayItems = MutableStateFlow<List<SportsLiveItem>>(emptyList())
    private val load = MutableStateFlow<ResultsLoad?>(null)
    private val reveal = ResultSpoilerReveal(viewModelScope)
    private val mode = prefs.scoreSettings.map { it.mode }.distinctUntilChanged()

    private val replayIndex = combine(load, replayItems) { load, replays ->
        val loaded = load as? ResultsLoad.Loaded ?: return@combine emptyMap()
        SportsResults.replaysByEvent(loaded.events, loaded.date, replays, System.currentTimeMillis())
    }.flowOn(Dispatchers.Default)

    val uiState: StateFlow<SportsLiveResultsUiState> = combine(
        combine(daysAgo, load, selection, ::Triple),
        mode,
        reveal.revealed,
        replayIndex
    ) { (days, load, selected), mode, revealed, replays ->
        val loaded = load as? ResultsLoad.Loaded
        val rows = loaded?.rows.orEmpty()
        val mySports = loaded?.mySports ?: MySports.DEFAULT
        val sports = resultSportChips(rows, mySports)
        val sport = selected.sport?.takeIf { it in sports }
        val leagues = resultLeagueChips(rows, sport)
        val league = selected.league?.takeIf { key -> leagues.any { it.key == key } }
        SportsLiveResultsUiState(
            daysAgo = days,
            dates = results.days(),
            status = if (mode == ScoresMode.OFF) ResultsStatus.OFF else statusOf(load),
            sports = sports,
            selectedSport = sport,
            leagues = leagues,
            selectedLeague = league,
            items = resultListItems(rows, mySports, sport, league, selected.expanded, replays.keys),
            mode = mode,
            modeKnown = true,
            revealed = revealed,
            replays = replays
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SportsLiveResultsUiState())

    init {
        // A day loads while Results is the selected tab, after the date chip settles (chips select on
        // focus, so moving across them does not request each day). Past days do not change: no polling.
        viewModelScope.launch {
            combine(active, daysAgo, mode, prefs.followedTeams, prefs.mySports) { isActive, days, _, _, _ -> if (isActive) days else null }
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

    /** A new day keeps the chosen sport and league (when that day has them); long tails collapse again. */
    fun selectDay(days: Int) {
        if (days == daysAgo.value) return
        daysAgo.value = days
        selection.update { it.copy(expanded = emptySet()) }
        reveal.onFocus(null)
    }

    /** ★ My sports (null) or a sport; its league chips start at All. */
    fun selectSport(sport: MySport?) {
        selection.update { if (it.sport == sport) it else Selection(sport = sport) }
    }

    /** A league chip of the chosen sport; null is All. */
    fun selectLeague(key: String?) {
        selection.update { if (it.league == key) it else it.copy(league = key, expanded = emptySet()) }
    }

    /** OK on "+ N more": the section lists all its games. */
    fun expandGroup(groupKey: String) {
        selection.update { it.copy(expanded = it.expanded + groupKey) }
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

/** A league section with more games than this collapses... */
internal const val RESULTS_COLLAPSE_ABOVE = 8

/** ...to its first games and a "+ N more" row. */
internal const val RESULTS_SHOWN_COLLAPSED = 5

/** The sport chips after ★ My sports: the user's sports (followed teams' included) with results that day. */
internal fun resultSportChips(rows: List<ResultRow>, mySports: MySports): List<MySport> {
    val present = rows.mapTo(HashSet()) { it.event.league.sport }
    return mySports.effectiveSports.filter { it.espnSport in present }
}

/** [sport]'s league chips: its league sections present that day, when there are two or more; none for ★ My sports. */
internal fun resultLeagueChips(rows: List<ResultRow>, sport: MySport?): List<ResultLeagueChip> {
    if (sport == null) return emptyList()
    val groups = SportsResults.groups(rows.filter { it.event.league.sport == sport.espnSport })
    return if (groups.size < 2) emptyList() else groups.map { ResultLeagueChip(it.key, it.name, it.sport) }
}

/**
 * The list for ★ My sports ([sport] null: followed teams' games and My sports' games) or a sport (all
 * its games fetched, or one league section): YOUR TEAMS first, then league sections in
 * [SportsResults.groups] order. A section of more than [RESULTS_COLLAPSE_ABOVE] games shows
 * [RESULTS_SHOWN_COLLAPSED] and a "+ N more" row until [expanded]. [replays] are the event ids with
 * a strict replay match: only they carry the Replay tag.
 */
internal fun resultListItems(
    rows: List<ResultRow>,
    mySports: MySports,
    sport: MySport?,
    league: String?,
    expanded: Set<String>,
    replays: Set<String>
): List<ResultListItem> {
    val shown = rows.filter { row ->
        when {
            sport == null -> row.followed || mySports.includes(row.event)
            row.event.league.sport != sport.espnSport -> false
            else -> league == null || SportsResults.groupKey(row.event) == league
        }
    }
    val items = ArrayList<ResultListItem>()
    fun add(row: ResultRow) {
        items += ResultListItem.Row(row, hasReplay = row.key in replays)
    }
    val yourTeams = shown.filter { it.followed }
    if (yourTeams.isNotEmpty()) {
        items += ResultListItem.Header(YOUR_TEAMS_KEY, name = null, sport = null, yourTeams = true)
        yourTeams.forEach(::add)
    }
    for (group in SportsResults.groups(shown.filterNot { it.followed })) {
        items += ResultListItem.Header(group.key, group.name, group.sport, yourTeams = false)
        if (group.rows.size <= RESULTS_COLLAPSE_ABOVE || group.key in expanded) {
            group.rows.forEach(::add)
            continue
        }
        group.rows.take(RESULTS_SHOWN_COLLAPSED).forEach(::add)
        items += ResultListItem.More(
            groupKey = group.key,
            count = group.rows.size - RESULTS_SHOWN_COLLAPSED,
            name = group.name,
            sport = group.sport,
            firstHiddenKey = group.rows[RESULTS_SHOWN_COLLAPSED].key
        )
    }
    return items
}

private const val YOUR_TEAMS_KEY = "your_teams"

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
