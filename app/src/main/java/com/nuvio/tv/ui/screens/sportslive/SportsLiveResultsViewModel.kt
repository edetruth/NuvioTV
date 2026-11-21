package com.nuvio.tv.ui.screens.sportslive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.sportslive.scores.MySport
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.ResultDay
import com.nuvio.tv.core.sportslive.scores.ResultRow
import com.nuvio.tv.core.sportslive.scores.ResultsLoad
import com.nuvio.tv.core.sportslive.scores.ResultsRepository
import com.nuvio.tv.core.sportslive.scores.ScoreSide
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.SpoilerProtection
import com.nuvio.tv.core.sportslive.scores.TeamFollows
import com.nuvio.tv.core.sportslive.scores.SportsResults
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.domain.model.SportsLiveItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
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
    /** 0 = today, 1 = yesterday … 6; null until the opening day is worked out ([defaultResultsDay]). */
    val daysAgo: Int? = null,
    /** Counts the times Results opened afresh ([resultsDayExpired]): each starts the list at its top. */
    val freshOpens: Int = 0,
    /** The days the title's ‹ › steps through (ESPN days, counted back from today in US Eastern time): Today, Yesterday and the 5 before. */
    val dates: List<ResultDay> = emptyList(),
    /** Earlier ▸ is open: the 5 older days follow it. */
    val earlierOpen: Boolean = false,
    /** Today: games of My sports and followed teams being played now ("N games live now →"). */
    val liveNow: Int = 0,
    val status: ResultsStatus = ResultsStatus.LOADING,
    /** The sport chips after ★ My sports: the user's sports with results that day. */
    val sports: List<MySport> = emptyList(),
    /** The chosen sport; null is ★ My sports. */
    val selectedSport: MySport? = null,
    /** The chosen sport's league chips (after All): leagues present that day, when there are two or more. */
    val leagues: List<ResultLeagueChip> = emptyList(),
    /** The chosen league chip's key; null is All. */
    val selectedLeague: String? = null,
    /** Each sport chip's game count that day (#79); null is ★ My sports. */
    val sportCounts: Map<MySport?, Int> = emptyMap(),
    /** Each league chip's game count (#79), by key; null is All (the chosen sport's games). */
    val leagueCounts: Map<String?, Int> = emptyMap(),
    /** The list: YOUR TEAMS first, then league sections, long tails collapsed. */
    val items: List<ResultListItem> = emptyList(),
    val mode: ScoresMode = ScoresMode.SHOW,
    /** False until the Scores setting has been read: the Results tab stays hidden until then. */
    val modeKnown: Boolean = false,
    /** Rows (event ids) focused long enough to reveal their score under Hide until focused. */
    val revealed: Set<String> = emptySet(),
    /** The replay each result opens, by event id (strict matches only). */
    val replays: Map<String, SportsLiveItem> = emptyMap(),
    /** Spoiler protection (#151): a protected game's row hides its score whatever the setting. */
    val protection: SpoilerProtection = SpoilerProtection()
) {
    fun showsScore(row: ResultRow): Boolean =
        ResultSpoilerReveal.showsScore(mode, revealed, row.event.eventId, spoilerProtected = protection.protects(row.event))

    /**
     * The "N games live now" line: on Today, once loaded, while [liveGames] (Live now's own live games,
     * [SportsLiveUiState.liveGameCount]) are on. It counts what Live now shows, ended-games rules and delay
     * applied, so following it never lands on a tab without that game (QA pass 3).
     */
    fun showsLiveNow(liveGames: Int): Boolean = daysAgo == SportsResults.TODAY && status == ResultsStatus.READY && liveGames > 0
}

@HiltViewModel
class SportsLiveResultsViewModel internal constructor(
    private val results: ResultsRepository,
    private val prefs: SportsLivePreferencesDataStore,
    // Where the replays and the list are built; tests pass their dispatcher, so it runs in virtual time.
    computeDispatcher: CoroutineDispatcher
) : ViewModel() {

    @Inject constructor(
        results: ResultsRepository,
        prefs: SportsLivePreferencesDataStore
    ) : this(results, prefs, Dispatchers.Default)

    /** Results is on screen, showing [days] (null: the opening day is still to be worked out). */
    private data class Shown(val days: Int?)

    private data class Selection(val sport: MySport? = null, val league: String? = null, val expanded: Set<String> = emptySet())

    private val active = MutableStateFlow(false)
    /** The day shown; null until worked out on first open, unless one was chosen earlier in this process. */
    private val daysAgo = MutableStateFlow(results.chosenDay)
    private val earlierOpen = MutableStateFlow(results.chosenDay?.let { it in SportsResults.EARLIER } ?: false)
    /** Today's load made to work out the opening day, shown at once when Today is that day. */
    private var openingLoad: ResultsLoad? = null
    /** The day [load] holds. */
    private var loadedDays: Int? = null

    /** The day shown (null: being worked out). */
    internal val selectedDay: StateFlow<Int?> = daysAgo.asStateFlow()

    /** Earlier is open. */
    internal val earlierShown: StateFlow<Boolean> = earlierOpen.asStateFlow()
    private val selection = MutableStateFlow(Selection())
    private val freshOpens = MutableStateFlow(0)
    private val replayItems = MutableStateFlow<List<SportsLiveItem>>(emptyList())
    private val load = MutableStateFlow<ResultsLoad?>(null)

    /** The day's load on screen (null: loading, nothing shown). */
    internal val shownLoad: StateFlow<ResultsLoad?> = load.asStateFlow()
    private val reveal = ResultSpoilerReveal(viewModelScope)
    private val mode = prefs.scoreSettings.map { it.mode }.distinctUntilChanged()

    private val replayIndex = combine(load, replayItems) { load, replays ->
        val loaded = load as? ResultsLoad.Loaded ?: return@combine emptyMap()
        SportsResults.replaysByEvent(loaded.events, loaded.date, replays, System.currentTimeMillis())
    }.flowOn(computeDispatcher)

    /** The chips and the list: built off the main thread, and only when the day, selection or replays change. */
    private class Listing(
        val sports: List<MySport>,
        val sport: MySport?,
        val leagues: List<ResultLeagueChip>,
        val league: String?,
        val items: List<ResultListItem>,
        val replays: Map<String, SportsLiveItem>,
        val counts: ResultChipCounts
    )

    private val listing = combine(load, selection, replayIndex) { load, selected, replays ->
        val loaded = load as? ResultsLoad.Loaded
        val rows = loaded?.rows.orEmpty()
        val mySports = loaded?.mySports ?: MySports.DEFAULT
        val sports = resultSportChips(rows, mySports)
        val sport = selected.sport?.takeIf { it in sports }
        val leagues = resultLeagueChips(rows, sport)
        val league = selected.league?.takeIf { key -> leagues.any { it.key == key } }
        val items = resultListItems(rows, mySports, sport, league, selected.expanded, replays.keys)
        Listing(sports, sport, leagues, league, items, replays, resultChipCounts(rows, mySports, sports, sport))
    }.flowOn(computeDispatcher)

    private data class DayShown(val days: Int?, val load: ResultsLoad?, val earlier: Boolean, val freshOpens: Int)

    val uiState: StateFlow<SportsLiveResultsUiState> = combine(
        combine(daysAgo, load, earlierOpen, freshOpens, ::DayShown),
        mode,
        reveal.revealed,
        listing,
        prefs.spoilerProtection
    ) { (days, load, earlier, fresh), mode, revealed, listing, protection ->
        SportsLiveResultsUiState(
            daysAgo = days,
            freshOpens = fresh,
            // Every day (P4): the title's ‹ › steps through them, so no Earlier toggle hides any.
            dates = results.days(),
            earlierOpen = earlier,
            liveNow = (load as? ResultsLoad.Loaded)?.liveNow ?: 0,
            status = if (mode == ScoresMode.OFF) ResultsStatus.OFF else statusOf(load),
            sports = listing.sports,
            selectedSport = listing.sport,
            leagues = listing.leagues,
            selectedLeague = listing.league,
            sportCounts = listing.counts.sports,
            leagueCounts = listing.counts.leagues,
            items = listing.items,
            mode = mode,
            modeKnown = true,
            revealed = revealed,
            replays = listing.replays,
            protection = protection
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SportsLiveResultsUiState())

    private val replaysShown = MutableStateFlow(false)

    /**
     * Replays' strict ESPN matches (V2 P4, #90): each replay's game by its stream key, from Today's and
     * Yesterday's finals (the days a replay is most often wanted), worked out while Replays is the selected tab
     * and Scores is not Off. Never a score on Replays: only "Game stats" and the Team pages read it.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val replayMatches: StateFlow<Map<String, ScoreSnapshot>> =
        combine(replaysShown, mode, replayItems, ::Triple)
            .distinctUntilChanged()
            .mapLatest { (shown, mode, replays) ->
                if (!shown || mode == ScoresMode.OFF || replays.isEmpty()) return@mapLatest emptyMap()
                val now = System.currentTimeMillis()
                buildMap {
                    for (days in listOf(SportsResults.TODAY, SportsResults.YESTERDAY)) {
                        val loaded = results.load(days) as? ResultsLoad.Loaded ?: continue
                        putAll(replayMatchesOf(loaded.events, loaded.date, replays, now))
                    }
                }
            }
            .flowOn(computeDispatcher)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** True while Replays is the selected tab and on screen: its replays' games are matched then. */
    fun setReplaysActive(isActive: Boolean) {
        replaysShown.value = isActive
    }

    init {
        // A day loads while Results is the selected tab and on screen, after the date chip settles (quick
        // picks in a row do not request each day). Past days do not change: no
        // polling. Today refreshes at most every 2 min, only while shown (#67).
        viewModelScope.launch {
            combine(active, daysAgo, mode, prefs.followedTeams, prefs.mySports) { isActive, days, _, _, _ ->
                if (isActive) Shown(days) else null
            }
                .distinctUntilChanged()
                .collectLatest { shown ->
                    if (shown == null) return@collectLatest
                    val days = shown.days
                    if (days == null) {
                        // First open: Today when it has a final, else Yesterday. Not remembered as chosen.
                        load.value = null
                        val today = results.load(SportsResults.TODAY)
                        val opening = defaultResultsDay(today)
                        if (opening == SportsResults.TODAY) openingLoad = today
                        daysAgo.value = opening
                        return@collectLatest
                    }
                    var loaded = openingLoad?.takeIf { days == SportsResults.TODAY }
                    openingLoad = null
                    if (loaded == null) {
                        // The same day back on screen (from a replay, the Game page, another tab) keeps its
                        // list while it reloads, so focus stays on its row; a new day starts empty.
                        val onScreen = load.value as? ResultsLoad.Loaded
                        if (onScreen == null || loadedDays != days || onScreen.date != results.dateFor(days)) {
                            load.value = null
                            delay(DAY_SETTLE_MS)
                        }
                        loaded = results.load(days)
                    }
                    loadedDays = days
                    load.value = loaded
                    logReady(loaded)
                    // Finals the score delay holds back: the day loads again (from memory) once they are
                    // due. Today, and a day with games still live: again every 2 min (the repository asks
                    // ESPN at most that often). At US Eastern midnight the days move on: loaded again.
                    while (loaded is ResultsLoad.Loaded) {
                        val current: ResultsLoad.Loaded = loaded
                        val wait = listOfNotNull(
                            current.heldUntil?.let { it - results.nowMs() },
                            ResultsRepository.TODAY_TTL_MS.takeIf { days == SportsResults.TODAY || current.liveNow > 0 },
                            results.msToNextDay() + DAY_ROLLOVER_MARGIN_MS
                        ).min()
                        delay(wait.coerceAtLeast(0))
                        loaded = results.load(days)
                        load.value = loaded
                    }
                }
        }
    }

    /**
     * True while Results is the selected tab. Back on it after [SportsLiveRefreshPlan.OPEN_STALE_MS] away or on a new day
     * ([resultsDayExpired]), it opens afresh: always Today (an empty Today offers Yesterday), ★ My sports, the
     * list at its top. A quick hop keeps the chosen day. The first open still uses [defaultResultsDay].
     */
    fun setActive(isActive: Boolean) {
        if (isActive == active.value) return
        val now = results.nowMs()
        if (isActive) {
            val back = results.resultsAway.back(now)
            if (back != null && resultsDayExpired(back.leftAtMs, now, back.awayMs)) openAfresh()
        } else {
            results.resultsAway.leave(now)
        }
        active.value = isActive
        if (!isActive) reveal.onFocus(null)
        // Debug timing (#143): back on the day it shows, its list is kept (ready at once); else when the load lands.
        openedAtNs = if (isActive) System.nanoTime() else null
        if (isActive && loadedDays == daysAgo.value) logReady(load.value)
    }

    /** Debug timing (#143): when Results was last selected, until its list shows. */
    @Volatile
    private var openedAtNs: Long? = null

    private fun logReady(shown: ResultsLoad?) {
        val opened = openedAtNs ?: return
        if (shown !is ResultsLoad.Loaded) return
        openedAtNs = null
        com.nuvio.tv.core.sportslive.SportsLiveDebugTiming.log {
            "tab RESULTS ready in ${(System.nanoTime() - opened) / 1_000_000} ms rows=${shown.rows.size}"
        }
    }

    private fun openAfresh() {
        results.chosenDay = SportsResults.TODAY
        daysAgo.value = SportsResults.TODAY
        earlierOpen.value = false
        selection.value = Selection()
        // A fresh open hides again what Hide until focused revealed.
        reveal.clear()
        freshOpens.update { it + 1 }
    }

    override fun onCleared() {
        // Left with the screen itself: the next Results remembers when.
        if (active.value) results.resultsAway.leave(results.nowMs())
    }

    /** A Game page opened from Results is on screen ([shown]) or not: its time does not count as away. */
    fun onGamePageShown(shown: Boolean) = results.resultsAway.gamePageShown(shown, results.nowMs())

    /**
     * A new day keeps the chosen sport and league (when that day has them); long tails collapse again.
     * The day is remembered while the app process lives.
     */
    fun selectDay(days: Int) {
        if (days !in SportsResults.DAYS_AGO || days == daysAgo.value) return
        results.chosenDay = days
        daysAgo.value = days
        selection.update { it.copy(expanded = emptySet()) }
        reveal.onFocus(null)
    }

    /** OK on Earlier: opens the 5 older days after it, or closes them (an older day shown goes back to Yesterday). */
    fun toggleEarlier() {
        val open = !earlierOpen.value
        earlierOpen.value = open
        if (!open && daysAgo.value in SportsResults.EARLIER) selectDay(SportsResults.YESTERDAY)
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

    /**
     * Hold OK on a row → Follow / Following ‹team› (#54): follows [side]'s team with ESPN's name, a sport
     * word and its ESPN id ([TeamFollows.teamToFollow]), or unfollows every followed team that is that
     * side. The day reloads (from memory) with the row under YOUR TEAMS, and Live Now / Upcoming star it.
     */
    fun toggleFollow(row: ResultRow, side: ScoreSide) {
        val event = row.event
        val team = if (side == ScoreSide.HOME) event.home else event.away
        viewModelScope.launch {
            val matching = TeamFollows.followersOf(prefs.followedTeams.first(), event, team)
            if (matching.isEmpty()) {
                TeamFollows.teamToFollow(event, team)?.let { prefs.follow(it) }
            } else {
                matching.forEach { prefs.unfollow(it) }
            }
        }
    }

    /** The section's Replays tab items, for opening a result's replay. */
    fun setReplays(items: List<SportsLiveItem>) {
        replayItems.value = items
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
        /** A moment after US Eastern midnight, so the new day is ESPN's. */
        const val DAY_ROLLOVER_MARGIN_MS = 1_000L
    }
}

/** Each replay's strictly matched game ([SportsResults.replayCandidatesByEvent]), by the replay's stream key. */
internal fun replayMatchesOf(events: List<ScoreSnapshot>, date: java.time.LocalDate, replays: List<SportsLiveItem>, nowMs: Long): Map<String, ScoreSnapshot> {
    val byId = events.associateBy { it.eventId }
    return buildMap {
        SportsResults.replayCandidatesByEvent(events, date, replays, nowMs).forEach { (eventId, matched) ->
            val event = byId[eventId] ?: return@forEach
            matched.forEach { putIfAbsent(it.streamKey, event) }
        }
    }
}

/**
 * The day Results opens on when none was chosen (#67): Today once ★ My sports has a final today, else
 * Yesterday (Today then reads "No finals yet").
 */
internal fun defaultResultsDay(today: ResultsLoad): Int =
    if ((today as? ResultsLoad.Loaded)?.hasMyFinals == true) SportsResults.TODAY else SportsResults.YESTERDAY

/**
 * Back on Results [nowMs], left at [leftAtMs] and away for [awayMs] (Game page time from Results left out):
 * it opens afresh once [SportsLiveRefreshPlan.OPEN_STALE_MS] have counted or ESPN's day (US Eastern, what "Yesterday"
 * counts from) has moved on.
 */
internal fun resultsDayExpired(leftAtMs: Long, nowMs: Long, awayMs: Long = nowMs - leftAtMs): Boolean {
    if (awayMs >= SportsLiveRefreshPlan.OPEN_STALE_MS) return true
    fun espnDay(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(SportsResults.EASTERN).toLocalDate()
    return espnDay(leftAtMs) != espnDay(nowMs)
}

/**
 * The day ‹ (older) or › ([newer]) steps to from [current] among [days] (V2 P4: ← → on the title's date);
 * null at either end, so the key moves focus on instead.
 */
internal fun resultsDayStep(days: List<ResultDay>, current: Int?, newer: Boolean): Int? {
    val ordered = days.map { it.daysAgo }.sorted()
    val index = ordered.indexOf(current ?: return null).takeIf { it >= 0 } ?: return null
    return ordered.getOrNull(if (newer) index - 1 else index + 1)
}

/** How the title names a day (P4): Today and Yesterday only when the ESPN day is also the device's, else its date. */
internal enum class ResultsDayName { TODAY, YESTERDAY, DATE }

internal fun resultsDayName(day: ResultDay): ResultsDayName = when {
    day.isDeviceToday -> ResultsDayName.TODAY
    day.isDeviceYesterday -> ResultsDayName.YESTERDAY
    else -> ResultsDayName.DATE
}

/** The date rail's days: Today and Yesterday, then the older days while Earlier is open. */
internal fun resultRailDays(days: List<ResultDay>, earlierOpen: Boolean): List<ResultDay> =
    days.filter { earlierOpen || it.daysAgo !in SportsResults.EARLIER }

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

/** The Results chips' game counts (#79): [sports] by sport (null: ★ My sports), [leagues] by key (null: All). */
internal data class ResultChipCounts(val sports: Map<MySport?, Int>, val leagues: Map<String?, Int>)

/**
 * The Results chips' counts over the day's [rows] (#79): ★ My sports counts what it lists (followed
 * teams' games and My sports' games), each of [sports] its games, and the chosen [sport]'s league chips
 * their sections' games (All: the sport's). A count is no score: Hide until focused shows it too.
 */
internal fun resultChipCounts(rows: List<ResultRow>, mySports: MySports, sports: List<MySport>, sport: MySport?): ResultChipCounts {
    val bySport = HashMap<MySport?, Int>()
    bySport[null] = rows.count { it.followed || mySports.includes(it.event) }
    sports.forEach { chip -> bySport[chip] = rows.count { it.event.league.sport == chip.espnSport } }
    val leagues = HashMap<String?, Int>()
    if (sport != null) {
        val sportRows = rows.filter { it.event.league.sport == sport.espnSport }
        leagues[null] = sportRows.size
        sportRows.forEach { row -> SportsResults.groupKey(row.event).let { leagues[it] = (leagues[it] ?: 0) + 1 } }
    }
    return ResultChipCounts(bySport, leagues)
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

    /** Hides every revealed score again (Results opened afresh). */
    fun clear() {
        focused = null
        job?.cancel()
        job = null
        _revealed.value = emptySet()
    }

    companion object {
        const val REVEAL_AFTER_MS = 1_000L

        /** Show: always; Hide until focused: once revealed; Off: never. A [spoilerProtected] game (#151): never. */
        fun showsScore(mode: ScoresMode, revealed: Set<String>, eventId: String, spoilerProtected: Boolean = false): Boolean = when {
            spoilerProtected -> false
            else -> byMode(mode, revealed, eventId)
        }

        private fun byMode(mode: ScoresMode, revealed: Set<String>, eventId: String): Boolean = when (mode) {
            ScoresMode.SHOW -> true
            ScoresMode.HIDE_UNTIL_FOCUSED -> eventId in revealed
            ScoresMode.OFF -> false
        }
    }
}

/**
 * OK on a Results row: the Game page for its event (#18), carrying the row's strict replay match (Watch
 * replay) and whether its score already shows here (Hide until focused: a revealed row opens revealed).
 */
internal fun SportsLiveResultsUiState.gameRequestFor(row: ResultRow): SportsLiveGameRequest =
    SportsLiveGameRequest(event = row.event, replay = replays[row.key], revealed = showsScore(row))

/** A result row's list key. */
internal val ResultRow.key: String get() = event.eventId
