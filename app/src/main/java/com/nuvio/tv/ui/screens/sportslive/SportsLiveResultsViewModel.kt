package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.debug.ShownGamesLog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.sportslive.scores.ResultsNextGames
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.ResultDay
import com.nuvio.tv.core.sportslive.scores.ResultRow
import com.nuvio.tv.core.sportslive.scores.ResultsLoad
import com.nuvio.tv.core.sportslive.scores.ResultsRepository
import com.nuvio.tv.core.sportslive.scores.ScoreSide
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.SpoilerContext
import com.nuvio.tv.core.sportslive.scores.SpoilerPolicy
import com.nuvio.tv.core.sportslive.scores.SpoilerProtection
import com.nuvio.tv.core.sportslive.scores.SpoilerSurface
import com.nuvio.tv.core.sportslive.scores.TeamFollows
import com.nuvio.tv.core.sportslive.scores.SportsResults
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.core.sportslive.scores.GameArtTeam
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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** What the Results view shows below its date chips. */
enum class ResultsStatus { LOADING, OFF, NO_LEAGUES, FAILED, READY }

/**
 * Jump to ▾ (results redesign, replacing the sport dropdown): what the list is narrowed to. Null (in the state) is
 * every league: YOUR TEAMS, then My sports' leagues. Back returns to it.
 */
sealed interface ResultsJump {
    /** Only the followed teams' games, with the teams that did not play. */
    data object YourTeams : ResultsJump

    /** Every game outside My sports that day (its sports and leagues fetched on demand). */
    data object OtherSports : ResultsJump

    /** One league section ([SportsResults.groupKey]), from My sports or another sport. */
    data class League(val key: String) : ResultsJump
}

/** One choice of Jump to ▾: [jump] (null: all leagues), its games [count], and a league's [name] (null: [sport]'s name). */
data class ResultsJumpEntry(val jump: ResultsJump?, val count: Int, val name: String? = null, val sport: String? = null)

/** What Results' actions do beyond opening things: Jump to ▾ and the explicit reveals (the screen wires the ViewModel). */
interface ResultsActions {
    /** Jump to ▾ picked [jump] (null: all leagues); the list's first game takes focus. */
    fun jumpTo(jump: ResultsJump?)

    /** Jump to › Other sports opened: the day's other sports are fetched (again, when they failed). */
    fun loadOtherSports()

    /** "Couldn't load other sports · Retry" (M3): the day's other sports are fetched again. */
    fun retryOtherSports()

    /** More… → Reveal result: [rows]' scores show, a followed team's or a protected game's for good. */
    fun reveal(rows: List<ResultRow>)

    /** More… → Reveal all for this day: every result of the day shown. */
    fun revealDay()
}

/** Why a result's score is hidden (the stage says it, with how to reveal; results redesign). */
enum class ResultHiddenReason {
    /** Watch later (no spoilers), #151. */
    SAVED,

    /** A followed team played: hidden until the viewer reveals or watches it. */
    YOUR_TEAM,

    /** Scores: Hide until I reveal. */
    SETTING
}

/** One line of the Results list: a league header, a result, a collapsed long tail, or a team that did not play. */
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

    /**
     * One compact line under YOUR TEAMS's results (live.24, TV report #19): up to [RESULTS_NOT_PLAYED_ROW] followed teams
     * with no game on the day, each as its logo, code and next game ("Tomorrow 6:00 PM") or a muted "No game"; not
     * focusable. [index]: the line's place when the list is expanded past one line.
     */
    data class NotPlayed(val teams: List<NotPlayedTeam>, val today: Boolean, val index: Int = 0) : ResultListItem {
        override val key: String get() = "np|$index"
    }

    /** "3 of your teams didn't play ›" in place of the lines while more than [RESULTS_NOT_PLAYED_ROW] teams did not play; OK expands. */
    data class NotPlayedMore(val count: Int) : ResultListItem {
        override val key: String get() = "np|more"
    }
}

/** A followed team that did not play, as the compact line draws it: the game identity's [code], its [logo], its next start. */
data class NotPlayedTeam(val name: String, val code: String, val logo: String?, val nextStartMs: Long?)

/**
 * A followed team with no game on the day shown, and its next game's start when known. [lastFinal]: the team's last
 * final ([ResultsNextGames.TeamNext.lastFinal]); while it is hidden the next start is left out ([resultsNotPlayedShown]).
 */
data class ResultsNotPlayed(
    val team: String,
    val nextStartMs: Long?,
    val lastFinal: ScoreSnapshot? = null,
    /** The game identity's code for the team ([GameIdentities.code]); empty: made from the name when drawn. */
    val code: String = "",
    val logo: String? = null
)

/**
 * [notPlayed] as Results shows it (live.23 review S4): a team whose last final is still hidden for the profile (Scores
 * Off, or with Hide my teams' results on a followed team's result not revealed or watched, as
 * [SportsLiveResultsUiState.hiddenReason]) loses its
 * "next" part, which could tell a knockout result (a next game: they won; none: they lost).
 */
internal fun resultsNotPlayedShown(notPlayed: List<ResultsNotPlayed>, mode: ScoresMode, protection: SpoilerProtection): List<ResultsNotPlayed> =
    notPlayed.map { line ->
        val last = line.lastFinal ?: return@map line
        // Hidden: Scores Off, a protected final, or (Hide my teams' results on, M2's own key) one not revealed or watched.
        val hidden = mode == ScoresMode.OFF || protection.protects(last) ||
            (protection.myTeamsResults && !protection.revealed(last.eventId))
        if (hidden) line.copy(nextStartMs = null) else line
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
    /** Jump to ▾'s choice; null: all leagues. */
    val jump: ResultsJump? = null,
    /** Jump to ▾'s first page: all leagues, ★ Your teams (when they played), then My sports' leagues of the day. */
    val jumpEntries: List<ResultsJumpEntry> = emptyList(),
    /** Jump to › Other sports: all of them, then each league; null until fetched ([otherLoading] while it is). */
    val otherEntries: List<ResultsJumpEntry>? = null,
    val otherLoading: Boolean = false,
    /** Every other-sports feed of the day failed (M3): "Couldn't load other sports · Retry", never "No games". */
    val otherFailed: Boolean = false,
    /** Bumped by each [ResultsActions.jumpTo]: the list's first game takes focus once the new list is drawn. */
    val jumpFocus: Int = 0,
    /** The list: YOUR TEAMS first, then league sections, long tails collapsed. */
    val items: List<ResultListItem> = emptyList(),
    val mode: ScoresMode = ScoresMode.SHOW,
    /** False until the Scores setting has been read: the Results tab stays hidden until then. */
    val modeKnown: Boolean = false,
    /** Rows (event ids) focused long enough to reveal their score under Hide until focused, or revealed by choice. */
    val revealed: Set<String> = emptySet(),
    /** The replay each result opens, by event id (strict matches only). */
    val replays: Map<String, SportsLiveItem> = emptyMap(),
    /** Spoiler protection (#151): a protected game's row hides its score whatever the setting. */
    val protection: SpoilerProtection = SpoilerProtection()
) {
    fun showsScore(row: ResultRow): Boolean = hiddenReason(row) == null

    /**
     * Why [row]'s score is hidden, or null when it shows (results redesign §4.3): a protected game (#151) never shows;
     * with Hide my teams' results on ([SpoilerProtection.myTeamsResults]), a followed team's game shows only once revealed
     * or watched ([SpoilerProtection.revealed]), never on focus; the
     * rest follow the Scores setting ([ResultSpoilerReveal.showsScore]).
     */
    fun hiddenReason(row: ResultRow): ResultHiddenReason? {
        val id = row.event.eventId
        return when {
            protection.protects(row.event) -> if (protection.isSaved(id)) ResultHiddenReason.SAVED else ResultHiddenReason.YOUR_TEAM
            mode == ScoresMode.OFF -> ResultHiddenReason.SETTING
            // "Hide my teams' results until I reveal" (M2: its own key, on until set) holds a followed row; off, it follows
            // the Scores setting like any other.
            row.followed && protection.myTeamsResults -> if (protection.revealed(id)) null else ResultHiddenReason.YOUR_TEAM
            ResultSpoilerReveal.showsScore(mode, revealed, id) -> null
            else -> ResultHiddenReason.SETTING
        }
    }

    /**
     * The "N games live now" line: on Today, once loaded, while [liveGames] (Live now's own live games,
     * [SportsLiveUiState.liveGameCount]) are on. It counts what Live now shows, ended-games rules and delay
     * applied, so following it never lands on a tab without that game (QA pass 3).
     */
    fun showsLiveNow(liveGames: Int): Boolean = daysAgo == SportsResults.TODAY && status == ResultsStatus.READY && liveGames > 0

    /** Jump to ▾'s other-sports games, once fetched (the "See N games in other sports" button); null: not known yet. */
    val otherCount: Int? get() = otherEntries?.firstOrNull { it.jump == ResultsJump.OtherSports }?.count ?: otherEntries?.let { 0 }
}

@HiltViewModel
class SportsLiveResultsViewModel internal constructor(
    private val results: ResultsRepository,
    private val prefs: SportsLivePreferencesDataStore,
    // Where the replays and the list are built; tests pass their dispatcher, so it runs in virtual time.
    computeDispatcher: CoroutineDispatcher,
    /** A followed team's next game start and last final ([ResultsNextGames]); null when not known. */
    private val nextGame: suspend (FollowedTeam) -> ResultsNextGames.TeamNext? = { null }
) : ViewModel(), ResultsActions {

    @Inject constructor(
        results: ResultsRepository,
        prefs: SportsLivePreferencesDataStore,
        nextGames: ResultsNextGames
    ) : this(results, prefs, Dispatchers.Default, nextGames::next)

    /** Results is on screen, showing [days] (null: the opening day is still to be worked out). */
    private data class Shown(val days: Int?)

    /** [focusRequests] counts Jump to picks: each sends focus to the new list's first game (with the list it built). */
    private data class Selection(val jump: ResultsJump? = null, val expanded: Set<String> = emptySet(), val focusRequests: Int = 0)

    /** The other sports' finals of ESPN day [date] ([ResultsRepository.loadOther]); [rows] null: every feed failed. */
    private data class OtherDay(val date: LocalDate, val rows: List<ResultRow>?)

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
    /** The ESPN day Jump to › Other sports was opened on: its other sports are fetched. */
    private val otherAsked = MutableStateFlow<LocalDate?>(null)
    private val other = MutableStateFlow<OtherDay?>(null)
    /** Bumped by [retryOtherSports]: a failed day's other sports are fetched again. */
    private val otherRetry = MutableStateFlow(0)
    private val replayItems = MutableStateFlow<List<SportsLiveItem>>(emptyList())
    private val load = MutableStateFlow<ResultsLoad?>(null)

    /** The day's load on screen (null: loading, nothing shown). */
    internal val shownLoad: StateFlow<ResultsLoad?> = load.asStateFlow()
    private val revealer = ResultSpoilerReveal(viewModelScope)
    private val mode = prefs.scoreSettings.map { it.mode }.distinctUntilChanged()

    /** The other sports' rows of the day on screen; null while not fetched for it. */
    private val otherRows = combine(load, other) { load, other ->
        val loaded = load as? ResultsLoad.Loaded
        // A failed fetch (rows null) is not "no other sports" (M3): nothing is listed for it.
        if (loaded == null || other == null || other.date != loaded.date) null else other.rows
    }

    private val replayIndex = combine(load, otherRows, replayItems) { load, otherRows, replays ->
        val loaded = load as? ResultsLoad.Loaded ?: return@combine emptyMap()
        val events = if (otherRows.isNullOrEmpty()) loaded.events else loaded.events + otherRows.map { it.event }
        SportsResults.replaysByEvent(events, loaded.date, replays, System.currentTimeMillis())
    }.flowOn(computeDispatcher)

    /** The followed teams (followed from ESPN) with no game on the day loaded, each with its next game when known. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val notPlayed = combine(load, prefs.followedTeams) { load, followed ->
        (load as? ResultsLoad.Loaded)?.let { resultsNotPlayedTeams(followed, it.events) }.orEmpty()
    }
        .distinctUntilChanged()
        .mapLatest { teams ->
            teams.map { team ->
                runCatching { nextGame(team) }.getOrNull().let {
                    ResultsNotPlayed(team.name, it?.startMs, it?.lastFinal, code = resultsNotPlayedCode(team), logo = team.logo)
                }
            }
        }
        .onStart { emit(emptyList()) }
        .let { lines -> combine(lines, mode, prefs.spoilerProtection, ::resultsNotPlayedShown) }
        .distinctUntilChanged()
        .flowOn(computeDispatcher)

    /** Jump to ▾ and the list: built off the main thread, and only when the day, selection or replays change. */
    private class Listing(
        val jump: ResultsJump?,
        val focusRequests: Int,
        val entries: List<ResultsJumpEntry>,
        val otherEntries: List<ResultsJumpEntry>?,
        val items: List<ResultListItem>,
        val replays: Map<String, SportsLiveItem>
    )

    private val listing = combine(load, selection, replayIndex, otherRows, notPlayed) { load, selected, replays, otherRows, notPlayed ->
        val loaded = load as? ResultsLoad.Loaded
        val rows = loaded?.rows.orEmpty()
        val mySports = loaded?.mySports ?: MySports.DEFAULT
        val others = resultsOtherRows(rows, otherRows.orEmpty(), mySports)
        val jump = selected.jump?.takeIf { resultsJumpValid(it, rows, others) }
        val today = loaded != null && loaded.date == results.dateFor(SportsResults.TODAY)
        val items = resultListItems(rows, mySports, jump, selected.expanded, replays.keys, others, notPlayed, today)
        Listing(jump, selected.focusRequests, resultsJumpEntries(rows, mySports), otherRows?.let { resultsOtherEntries(others) }, items, replays)
    }.flowOn(computeDispatcher)

    private data class DayShown(val days: Int?, val load: ResultsLoad?, val earlier: Boolean, val freshOpens: Int)

    val uiState: StateFlow<SportsLiveResultsUiState> = combine(
        combine(daysAgo, load, earlierOpen, freshOpens, ::DayShown),
        combine(mode, prefs.spoilerProtection, ::Pair),
        revealer.revealed,
        listing,
        combine(otherAsked, other, selection) { asked, other, selected -> Triple(asked, other, selected.jump) }
    ) { (days, load, earlier, fresh), (mode, protection), revealed, listing, (asked, other, jump) ->
        val loaded = load as? ResultsLoad.Loaded
        ShownGamesLog.noteEvents(ShownGamesLog.RESULTS, listing.items.mapNotNull { (it as? ResultListItem.Row)?.row?.event })
        SportsLiveResultsUiState(
            daysAgo = days,
            freshOpens = fresh,
            // Every day (P4): the title's ‹ › steps through them, so no Earlier toggle hides any.
            dates = results.days(),
            earlierOpen = earlier,
            liveNow = loaded?.liveNow ?: 0,
            status = if (mode == ScoresMode.OFF) ResultsStatus.OFF else statusOf(load),
            jump = listing.jump,
            jumpEntries = listing.entries,
            otherEntries = listing.otherEntries,
            otherLoading = loaded != null && other?.date != loaded.date && otherWanted(loaded, asked, jump),
            otherFailed = loaded != null && other != null && other.date == loaded.date && other.rows == null,
            jumpFocus = listing.focusRequests,
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
        // Other sports are fetched only when wanted (results redesign): Jump to › Other sports opened, a jump that
        // needs them, or a past day with nothing from My sports (its "See N games in other sports").
        viewModelScope.launch {
            combine(active, load, otherAsked, selection, otherRetry) { isActive, load, asked, selected, retry ->
                val loaded = load as? ResultsLoad.Loaded
                if (!isActive || loaded == null || !otherWanted(loaded, asked, selected.jump)) null else loaded.date to retry
            }
                .distinctUntilChanged()
                .collectLatest { wanted ->
                    val date = wanted?.first ?: return@collectLatest
                    // A day fetched is kept; a failed one is not remembered (M3): Retry, or reopening Other sports, asks again.
                    if (other.value?.let { it.date == date && it.rows != null } == true) return@collectLatest
                    val days = results.days().firstOrNull { it.date == date }?.daysAgo ?: return@collectLatest
                    other.value = OtherDay(date, results.loadOther(days))
                }
        }
    }

    private fun otherWanted(loaded: ResultsLoad.Loaded, asked: LocalDate?, jump: ResultsJump?): Boolean =
        resultsOtherWanted(asked == loaded.date, jump, loaded.rows, loaded.hasMyFinals, today = loaded.date == results.dateFor(SportsResults.TODAY))

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
        if (!isActive) revealer.onFocus(null)
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
        selection.update { Selection(focusRequests = it.focusRequests) }
        // A fresh open hides again what Hide until focused revealed.
        revealer.clear()
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
        revealer.onFocus(null)
    }

    /** OK on Earlier: opens the 5 older days after it, or closes them (an older day shown goes back to Yesterday). */
    fun toggleEarlier() {
        val open = !earlierOpen.value
        earlierOpen.value = open
        if (!open && daysAgo.value in SportsResults.EARLIER) selectDay(SportsResults.YESTERDAY)
    }

    /** Jump to ▾ (results redesign): the list narrows to [jump], or every league again (null, also Back). */
    override fun jumpTo(jump: ResultsJump?) {
        selection.update { Selection(jump = jump, expanded = if (it.jump == jump) it.expanded else emptySet(), focusRequests = it.focusRequests + 1) }
    }

    /** Jump to › Other sports opened: the day on screen fetches its other sports ([ResultsRepository.loadOther]). */
    override fun loadOtherSports() {
        val loaded = load.value as? ResultsLoad.Loaded ?: return
        otherAsked.value = loaded.date
        if (other.value?.let { it.date == loaded.date && it.rows == null } == true) retryOtherSports()
    }

    override fun retryOtherSports() {
        // Loading shows again while it is asked for.
        other.update { current -> current?.takeIf { it.rows != null } }
        otherRetry.update { it + 1 }
    }

    /**
     * More… → Reveal result (results redesign): [rows]' scores show now; a followed team's game or a protected one
     * ends its protection for good ([SpoilerProtection.ended], as the Game page's Reveal result), so it stays shown
     * on every surface and after a fresh open.
     */
    override fun reveal(rows: List<ResultRow>) {
        if (rows.isEmpty()) return
        revealer.add(rows.map { it.key })
        viewModelScope.launch {
            val protection = prefs.spoilerProtection.first()
            val lasting = rows.filter { it.followed || protection.protects(it.event) }.map { it.event }
            if (lasting.isEmpty()) return@launch
            val now = results.nowMs()
            prefs.updateProtectedGames { games -> lasting.fold(games) { acc, event -> SpoilerProtection.ended(acc, event, now) } }
        }
    }

    /**
     * More… → Reveal all for this day: the results of the day on screen hidden by the Scores setting (Hide until
     * focused), other sports' included once fetched, for this visit. A followed team's or a saved game's result keeps
     * its own Reveal result: this never ends a protection (live.23 review S3).
     */
    override fun revealDay() {
        val loaded = load.value as? ResultsLoad.Loaded ?: return
        val others = other.value?.takeIf { it.date == loaded.date }?.rows.orEmpty()
        val state = uiState.value
        val rows = (loaded.rows + others.filter { row -> loaded.rows.none { it.key == row.key } })
            .filter { state.hiddenReason(it) == ResultHiddenReason.SETTING }
        if (rows.isNotEmpty()) revealer.add(rows.map { it.key })
    }

    /** OK on "+ N more": the section lists all its games. */
    fun expandGroup(groupKey: String) {
        selection.update { it.copy(expanded = it.expanded + groupKey) }
    }

    /** The row now focused (null when none): under Hide until focused its score shows after 1 s. */
    fun onRowFocus(eventId: String?) = revealer.onFocus(eventId)

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
/** True when [row] is listed under every league (null jump): a followed team's game or one of My sports. */
private fun ResultRow.isMine(mySports: MySports): Boolean = followed || mySports.includes(event)

/**
 * Jump to ▾'s first page (results redesign): all leagues (every game listed under it), ★ Your teams when a followed
 * team played, then each of My sports' league sections that day with its games, in [SportsResults.groups] order.
 * A count is no score: it shows under every Scores setting.
 */
internal fun resultsJumpEntries(rows: List<ResultRow>, mySports: MySports): List<ResultsJumpEntry> {
    val mine = rows.filter { it.isMine(mySports) }
    return buildList {
        add(ResultsJumpEntry(null, mine.size))
        mine.count { it.followed }.takeIf { it > 0 }?.let { add(ResultsJumpEntry(ResultsJump.YourTeams, it)) }
        SportsResults.groups(mine).forEach { add(ResultsJumpEntry(ResultsJump.League(it.key), it.rows.size, it.name, it.sport)) }
    }
}

/**
 * The day's games outside My sports: the fetched rows My sports leaves out (another soccer competition) and
 * [other] ([ResultsRepository.loadOther]), each game once.
 */
internal fun resultsOtherRows(rows: List<ResultRow>, other: List<ResultRow>, mySports: MySports): List<ResultRow> {
    val listed = rows.mapTo(HashSet()) { it.key }
    return rows.filterNot { it.isMine(mySports) } + other.filterNot { it.key in listed || it.isMine(mySports) }
}

/** Jump to › Other sports: all of them ([others]), then each league section. */
internal fun resultsOtherEntries(others: List<ResultRow>): List<ResultsJumpEntry> =
    if (others.isEmpty()) emptyList()
    else listOf(ResultsJumpEntry(ResultsJump.OtherSports, others.size)) +
        SportsResults.groups(others).map { ResultsJumpEntry(ResultsJump.League(it.key), it.rows.size, it.name, it.sport) }

/** Whether [jump] still names something that day (a new day keeps the jump when it has that league). */
internal fun resultsJumpValid(jump: ResultsJump, rows: List<ResultRow>, others: List<ResultRow>): Boolean = when (jump) {
    ResultsJump.YourTeams -> true
    ResultsJump.OtherSports -> true
    is ResultsJump.League -> (rows.asSequence() + others.asSequence()).any { SportsResults.groupKey(it.event) == jump.key }
}

/**
 * Whether the day's other sports are fetched (results redesign): Jump to › Other sports was opened ([asked]), the
 * jump needs them, or a past day has nothing from My sports ([hasMyFinals] false; Today offers Yesterday instead).
 */
internal fun resultsOtherWanted(asked: Boolean, jump: ResultsJump?, rows: List<ResultRow>, hasMyFinals: Boolean, today: Boolean): Boolean = when {
    asked -> true
    jump == ResultsJump.OtherSports -> true
    jump is ResultsJump.League -> rows.none { SportsResults.groupKey(it.event) == jump.key }
    jump == ResultsJump.YourTeams -> false
    else -> !hasMyFinals && !today
}

/** At most this many followed teams that did not play under YOUR TEAMS. */
internal const val RESULTS_NOT_PLAYED_MAX = 9

/** Teams on one compact "didn't play" line; more than this collapse to "N of your teams didn't play ›" until expanded. */
internal const val RESULTS_NOT_PLAYED_ROW = 3

/** The expand key of the collapsed "didn't play" line ([ResultListItem.NotPlayedMore]). */
internal const val RESULTS_NOT_PLAYED_KEY = "np|teams"

/** [team]'s code on the "didn't play" line: the game identity's rule ([GameIdentities.code]), never a cut name. */
internal fun resultsNotPlayedCode(team: FollowedTeam, codeOf: (String) -> String? = GameIdentities::knownCode): String =
    GameIdentities.code(GameArtTeam(name = team.name, short = team.abbreviation ?: team.name, logo = team.logo), codeOf)

/**
 * The followed teams with no game at all in [events] (the day's events, live and upcoming included): only teams
 * followed from ESPN (by id), which match ESPN's games exactly, so a name-only follow never reads "didn't play" by
 * mistake. At most [RESULTS_NOT_PLAYED_MAX], one per name.
 */
internal fun resultsNotPlayedTeams(followed: List<FollowedTeam>, events: List<ScoreSnapshot>): List<FollowedTeam> =
    followed.asSequence()
        .filter { !it.espnId.isNullOrBlank() && !it.league.isNullOrBlank() }
        .filter { team -> events.none { it.individual == null && SportsResults.follows(team, it) } }
        .distinctBy { it.name.lowercase() }
        .take(RESULTS_NOT_PLAYED_MAX)
        .toList()

/**
 * The list (results redesign): every league ([jump] null: YOUR TEAMS with the [notPlayed] lines, then My sports'
 * league sections, long tails collapsed), ★ Your teams only, Other sports ([others], in league sections), or one
 * league section listed whole (from My sports or [others]). A section of more than [RESULTS_COLLAPSE_ABOVE] games
 * shows [RESULTS_SHOWN_COLLAPSED] and a "+ N more" row until [expanded]. [replays] are the event ids with a strict
 * replay match: only they carry the Replay tag. [today]: the not-played lines say "no game today".
 */
internal fun resultListItems(
    rows: List<ResultRow>,
    mySports: MySports,
    jump: ResultsJump?,
    expanded: Set<String>,
    replays: Set<String>,
    others: List<ResultRow> = emptyList(),
    notPlayed: List<ResultsNotPlayed> = emptyList(),
    today: Boolean = false
): List<ResultListItem> {
    val items = ArrayList<ResultListItem>()
    fun add(row: ResultRow) {
        items += ResultListItem.Row(row, hasReplay = row.key in replays)
    }
    fun yourTeams(listed: List<ResultRow>) {
        val teams = listed.filter { it.followed }
        if (teams.isEmpty() && notPlayed.isEmpty()) return
        items += ResultListItem.Header(YOUR_TEAMS_KEY, name = null, sport = null, yourTeams = true)
        teams.forEach(::add)
        // One compact line (live.24): more than one line's worth collapses to "N of your teams didn't play ›".
        val lines = notPlayed.map { NotPlayedTeam(it.team, it.code.ifEmpty { panelShortCode(it.team) }, it.logo, it.nextStartMs) }
        if (lines.size > RESULTS_NOT_PLAYED_ROW && RESULTS_NOT_PLAYED_KEY !in expanded) {
            items += ResultListItem.NotPlayedMore(lines.size)
        } else {
            lines.chunked(RESULTS_NOT_PLAYED_ROW).forEachIndexed { index, chunk -> items += ResultListItem.NotPlayed(chunk, today, index) }
        }
    }
    fun sections(listed: List<ResultRow>, whole: Boolean) {
        for (group in SportsResults.groups(listed)) {
            items += ResultListItem.Header(group.key, group.name, group.sport, yourTeams = false)
            if (whole || group.rows.size <= RESULTS_COLLAPSE_ABOVE || group.key in expanded) {
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
    }
    when (jump) {
        null -> {
            val mine = rows.filter { it.isMine(mySports) }
            yourTeams(mine)
            sections(mine.filterNot { it.followed }, whole = false)
        }
        ResultsJump.YourTeams -> yourTeams(rows)
        ResultsJump.OtherSports -> sections(others, whole = false)
        // One league: its games whole, a followed team's among them (gold bar), in the day's order.
        is ResultsJump.League -> sections((rows + others).filter { SportsResults.groupKey(it.event) == jump.key }, whole = true)
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

    /** Reveals [ids] at once (Reveal result, Reveal all for this day). */
    fun add(ids: Collection<String>) {
        _revealed.update { it + ids }
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
        fun showsScore(mode: ScoresMode, revealed: Set<String>, eventId: String, spoilerProtected: Boolean = false): Boolean =
            SpoilerPolicy.visibility(mode, eventId in revealed, SpoilerContext(SpoilerSurface.RESULTS, spoilerProtected = spoilerProtected)).showsScore
    }
}

/**
 * OK on a Results row: the Game page for its event (#18), carrying the row's strict replay match (Watch
 * replay) and whether its score already shows here (Hide until focused: a revealed row opens revealed). A
 * game hidden here for the viewer's teams or a protection opens spoiler-safe, so the page hides it under Show too.
 */
internal fun SportsLiveResultsUiState.gameRequestFor(row: ResultRow): SportsLiveGameRequest {
    // Spoiler-safe whenever it is hidden for the viewer's teams or protection (M2, #151), not only for a followed row: a
    // protected final opened from another row stays hidden on the Game page under Show too.
    val reason = hiddenReason(row)
    return SportsLiveGameRequest(
        event = row.event,
        replay = replays[row.key],
        revealed = reason == null,
        spoilerSafe = reason == ResultHiddenReason.YOUR_TEAM || reason == ResultHiddenReason.SAVED
    )
}

/** A result row's list key. */
internal val ResultRow.key: String get() = event.eventId
