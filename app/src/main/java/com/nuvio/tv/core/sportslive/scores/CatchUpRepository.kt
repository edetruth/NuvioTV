package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.SportsLiveItemBucketer
import com.nuvio.tv.core.sportslive.SportsLivePlayedTime
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.repository.SportsLiveRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Catch up row (#58), shared by Home and Live Now: followed teams' finals since the last visit that
 * have a strict replay match ([CatchUp]). Days are loaded through [ResultsRepository.catchUpDay] (its
 * caches: one request per feed and day at most, past days from disk, at most [MAX_PARALLEL_DAYS] days at
 * a time) when Home or Sports & Live opens ([onOpened], at most once per [RELOAD_AFTER_MS]) or the followed
 * teams or the settings change; never on a timer. Nothing is loaded and the row is empty while it is off,
 * Scores is Off (no score data at all), Sports & Live or its Home row is off, or no team is followed.
 * The visit and the played replays belong to the active profile; a profile switch starts over.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class CatchUpRepository internal constructor(
    private val results: ResultsRepository,
    private val prefs: SportsLivePreferencesDataStore,
    /** The section's Replays tab items. */
    replays: Flow<List<SportsLiveItem>>,
    /** The Sports & Live master switch. */
    sectionEnabled: Flow<Boolean>,
    /** Real playing time per content id, from the full player. */
    private val played: SportsLivePlayedTime,
    private val now: () -> Long,
    private val scope: CoroutineScope
) {
    @Inject
    constructor(results: ResultsRepository, prefs: SportsLivePreferencesDataStore, sportsLive: SportsLiveRepository) : this(
        results = results,
        prefs = prefs,
        replays = combine(sportsLive.latest, sportsLive.claimedCatalogs) { load, claimed ->
            if (load == null) {
                emptyList()
            } else {
                val keys = claimed.mapTo(HashSet()) { it.key }
                SportsLiveItemBucketer.bucketTab(load.items.filter { it.catalogKey in keys }, SportsLiveTab.REPLAYS, System.currentTimeMillis())
            }
        },
        sectionEnabled = sportsLive.enabled,
        played = SportsLivePlayedTime.shared,
        now = System::currentTimeMillis,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    )

    /** One (re)load of the row for [profileId]: its "since", and when it was asked for. */
    private data class Visit(val profileId: Int, val sinceMs: Long, val atMs: Long)

    private val visit = MutableStateFlow<Visit?>(null)

    private data class Inputs(val on: Boolean, val followed: List<FollowedTeam>, val visit: Visit?)

    private data class Loaded(val days: List<CatchUpDay>, val sinceMs: Long)

    private val rowOn: Flow<Boolean> = combine(
        prefs.catchUpRow,
        prefs.scoreSettings.map { it.mode != ScoresMode.OFF },
        prefs.showHomeRow,
        sectionEnabled
    ) { row, scores, homeRow, section -> row && scores && homeRow && section }

    // Reloaded on a visit and when the teams or the settings change (never on a timer, never on a
    // resubscription); marks and replays only re-select.
    private val loaded: StateFlow<Loaded?> = combine(rowOn, prefs.followedTeams, visit, prefs.activeProfileId) { on, followed, visit, profileId ->
        Inputs(on, followed, visit?.takeIf { it.profileId == profileId })
    }
        .distinctUntilChanged()
        .mapLatest { (on, followed, visit) ->
            if (!on || followed.isEmpty() || visit == null) return@mapLatest null
            val feeds = CatchUp.feeds(followed)
            if (feeds.isEmpty()) return@mapLatest null
            val range = CatchUp.daysAgo(visit.sinceMs, prefs.catchUpMarks.first(), now(), CatchUp::easternDate)
            // Days in parallel, two at a time; each feed and day is asked once at most.
            val gate = Semaphore(MAX_PARALLEL_DAYS)
            val days = coroutineScope {
                range.map { day -> async { gate.withPermit { results.catchUpDay(day, feeds) } } }.awaitAll()
            }
            Loaded(days.filterNotNull(), visit.sinceMs)
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** The row, newest first; empty when off, nothing qualifies, or before the first open. */
    val items: StateFlow<List<CatchUpItem>> = loaded
        .flatMapLatest { loaded ->
            if (loaded == null) {
                flowOf(CatchUpSelection(emptyList(), emptyMap()))
            } else {
                combine(replays, prefs.followedTeams, prefs.catchUpMarks) { replays, followed, marks ->
                    CatchUp.select(loaded.days, replays, followed, loaded.sinceMs, marks, now())
                }
            }
        }
        .onEach(::remember)
        .map { it.items }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), emptyList())

    init {
        // A profile switch: the last visit and what was played belong to the profile before.
        scope.launch {
            prefs.activeProfileId.drop(1).collect {
                played.clear()
                visit.value = null
            }
        }
    }

    /**
     * Home or Sports & Live is on screen (each resume): a listed replay that has really played
     * [CatchUp.WATCHED_AFTER_MS] or more (the player's playing time, wherever it was started) is marked
     * watched; "since" is worked out, and the days are loaded again unless they were within [RELOAD_AFTER_MS].
     */
    fun onOpened() {
        scope.launch {
            val nowMs = now()
            markPlayed(nowMs)
            val since = prefs.openCatchUp(nowMs)
            val profileId = prefs.activeProfileId.value
            val last = visit.value
            if (last == null || last.profileId != profileId || nowMs - last.atMs >= RELOAD_AFTER_MS) {
                visit.value = Visit(profileId, since, nowMs)
            }
        }
    }

    /** "Mark as watched": the item leaves the row. */
    fun markWatched(item: CatchUpItem) {
        scope.launch {
            val nowMs = now()
            prefs.updateCatchUpMarks(nowMs) { it.copy(seen = it.seen + (item.key to nowMs)) }
        }
    }

    private suspend fun markPlayed(nowMs: Long) {
        val watched = items.value.filter { item -> item.replays.any { played.playedMs(it.id) >= CatchUp.WATCHED_AFTER_MS } }
        if (watched.isEmpty()) return
        prefs.updateCatchUpMarks(nowMs) { marks -> marks.copy(seen = marks.seen + watched.associate { it.key to nowMs }) }
        watched.forEach { item -> item.replays.forEach { played.forget(it.id) } }
    }

    /** Listed games are remembered as surfaced, games with no replay yet as pending. */
    private suspend fun remember(selection: CatchUpSelection) {
        val marks = prefs.catchUpMarks.first()
        if (CatchUp.remembered(marks, selection.items, selection.pending) == marks) return
        prefs.updateCatchUpMarks(now()) { CatchUp.remembered(it, selection.items, selection.pending) }
    }

    internal companion object {
        const val STOP_AFTER_MS = 5_000L

        /** A resume reloads the days at most this often. */
        const val RELOAD_AFTER_MS = 10 * 60_000L

        const val MAX_PARALLEL_DAYS = 2
    }
}
