package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.SportsLiveGameLength
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
 * How long each game stays is [ReplayLifetime]'s (live.20 1c), from the days loaded (the team's next game), what
 * was played, the results known ([ResultSeen]) and Watch later (#151); it is checked again on each open.
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
    private val scope: CoroutineScope,
    /** The results known this process (final scores shown, games watched live a while). */
    private val resultSeen: ResultSeen = ResultSeen()
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
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        resultSeen = ResultSeen.shared
    )

    /** One (re)load of the row for [profileId]: its "since", and when it was asked for. */
    private data class Visit(val profileId: Int, val sinceMs: Long, val atMs: Long)

    private val visit = MutableStateFlow<Visit?>(null)

    /** When the lifetime was last checked: each open checks it again (time passes, replays play). */
    private val checkedAt = MutableStateFlow(0L)

    private data class Inputs(val on: Boolean, val followed: List<FollowedTeam>, val visit: Visit?)

    private data class Loaded(val days: List<CatchUpDay>, val sinceMs: Long)

    private val rowOn: Flow<Boolean> = combine(
        prefs.catchUpRow,
        prefs.scoreSettings.map { it.mode != ScoresMode.OFF },
        prefs.showHomeRow,
        sectionEnabled
    ) { row, scores, homeRow, section -> row && scores && homeRow && section }

    private val _loading = MutableStateFlow(false)

    /**
     * P14: the row is on and has teams to look for, but its days are not loaded yet (or Home has not asked yet):
     * Home keeps the row's place and title meanwhile, so the row under Continue Watching never changes under the user.
     */
    val loading: StateFlow<Boolean> = _loading

    // Reloaded on a visit and when the teams or the settings change (never on a timer, never on a
    // resubscription); marks and replays only re-select.
    private val loaded: StateFlow<Loaded?> = combine(rowOn, prefs.followedTeams, visit, prefs.activeProfileId) { on, followed, visit, profileId ->
        Inputs(on, followed, visit?.takeIf { it.profileId == profileId })
    }
        .distinctUntilChanged()
        .mapLatest { (on, followed, visit) ->
            val feeds = if (on) CatchUp.feeds(followed) else emptyList()
            _loading.value = feeds.isNotEmpty()
            if (feeds.isEmpty() || visit == null) return@mapLatest null
            val saved = prefs.spoilerProtection.first().games.filter { it.endedAtMs == null }.mapNotNull { it.startMs }
            val range = CatchUp.daysAgo(visit.sinceMs, prefs.catchUpMarks.first(), now(), CatchUp::easternDate, saved)
            // Days in parallel, two at a time; each feed and day is asked once at most.
            val gate = Semaphore(MAX_PARALLEL_DAYS)
            val days = coroutineScope {
                range.map { day -> async { gate.withPermit { results.catchUpDay(day, feeds) } } }.awaitAll()
            }
            Loaded(days.filterNotNull(), visit.sinceMs).also { _loading.value = false }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** The row, newest first; empty when off, nothing qualifies, or before the first open. */
    val items: StateFlow<List<CatchUpItem>> = loaded
        .flatMapLatest { loaded ->
            if (loaded == null) {
                flowOf(CatchUpSelection(emptyList(), emptyMap()))
            } else {
                combine(replays, prefs.followedTeams, prefs.catchUpMarks, prefs.spoilerProtection, checkedAt) { replays, followed, marks, protection, _ ->
                    val nowMs = now()
                    // Watch later (#151): saved and still protected.
                    val saved = protection.games.filter { it.endedAtMs == null }.associate { it.eventId to it.savedAtMs }
                    val selection = CatchUp.select(loaded.days, replays, followed, loaded.sinceMs, marks, nowMs, saved.keys)
                    val events = loaded.days.flatMap { it.events }
                    selection.copy(items = selection.items.filter { ReplayLifetime.keeps(factsOf(it, events, followed, marks, saved), nowMs) })
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
                resultSeen.clear()
                visit.value = null
            }
        }
        // A result now known (a final score shown, a game watched live a while) is remembered for the profile.
        scope.launch {
            resultSeen.known.collect { ids ->
                if (ids.isEmpty()) return@collect
                val fresh = ids - prefs.catchUpMarks.first().known.keys
                if (fresh.isEmpty()) return@collect
                val nowMs = now()
                prefs.updateCatchUpMarks(nowMs) { marks -> marks.copy(known = marks.known + fresh.associateWith { nowMs }) }
            }
        }
        // A listed replay played to its end is watched, however short it was (a condensed game).
        scope.launch {
            played.finished.collect { contentId ->
                val item = items.value.firstOrNull { item -> item.replays.any { it.id == contentId } } ?: return@collect
                val nowMs = now()
                prefs.updateCatchUpMarks(nowMs) { it.copy(seen = it.seen + (item.key to nowMs)) }
            }
        }
    }

    /** The lifetime's inputs for [item] ([ReplayLifetime]). */
    private fun factsOf(
        item: CatchUpItem,
        events: List<ScoreSnapshot>,
        followed: List<FollowedTeam>,
        marks: CatchUpMarks,
        saved: Map<String, Long>
    ): ReplayFacts {
        val event = item.event
        val start = event.startMs ?: 0L
        return ReplayFacts(
            endMs = CatchUp.estimatedEndMs(event, start),
            lengthMs = SportsLiveGameLength.ofEspnSport(event.league.sport),
            nextGameStartMs = ReplayLifetime.nextGameStartMs(event, events, followed),
            replayPlayedMs = item.replays.maxOf { played.playedMs(it.id) },
            liveWatchedMs = resultSeen.liveWatchedMs(event.eventId),
            resultSeen = event.eventId in marks.known,
            big = ReplayLifetime.big(event),
            savedAtMs = saved[event.eventId]
        )
    }

    /**
     * Home or Sports & Live is on screen (each resume): a listed replay that has really played past halfway
     * ([ReplayLifetime.replayWatched]: the player's playing time, wherever it was started) is marked watched; the
     * lifetime is checked again; "since" is worked out, and the days are loaded again unless they were within
     * [RELOAD_AFTER_MS].
     */
    fun onOpened() {
        scope.launch {
            val nowMs = now()
            markPlayed(nowMs)
            checkedAt.value = nowMs
            val since = prefs.openCatchUp(nowMs)
            val profileId = prefs.activeProfileId.value
            val last = visit.value
            if (last == null || last.profileId != profileId || nowMs - last.atMs >= RELOAD_AFTER_MS) {
                visit.value = Visit(profileId, since, nowMs)
            }
        }
    }

    /** "Mark as watched" (Home) or "Remove from Your teams" (Live Now): the item leaves the row and the strip. */
    fun markWatched(item: CatchUpItem) {
        scope.launch {
            val nowMs = now()
            prefs.updateCatchUpMarks(nowMs) { it.copy(seen = it.seen + (item.key to nowMs)) }
        }
    }

    private suspend fun markPlayed(nowMs: Long) {
        val watched = items.value.filter { item ->
            val lengthMs = SportsLiveGameLength.ofEspnSport(item.event.league.sport)
            item.replays.any { ReplayLifetime.replayWatched(played.playedMs(it.id), lengthMs) }
        }
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
