package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.SportsLiveItemBucketer
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.repository.SportsLiveRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Catch up row (#58), shared by Home and Live Now: followed teams' finals since the last visit that
 * have a strict replay match ([CatchUp]). Days are loaded through [ResultsRepository.catchUpDay] (its
 * caches: one request per feed and day at most, past days from disk) only when Home or Sports & Live
 * opens ([onOpened]) or the followed teams or the settings change; never on a timer. Nothing is loaded
 * and the row is empty while it is off, Scores is Off (no score data at all), or no team is followed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class CatchUpRepository internal constructor(
    private val results: ResultsRepository,
    private val prefs: SportsLivePreferencesDataStore,
    /** The section's Replays tab items. */
    replays: Flow<List<SportsLiveItem>>,
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
        now = System::currentTimeMillis,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    )

    /** One open of Home or Sports & Live: the row's "since" then. */
    private data class Visit(val sinceMs: Long, val count: Long)

    private val visit = MutableStateFlow<Visit?>(null)

    /**
     * A catch-up replay started from the row: event id → when. "Played 2+ min" is approximated as the
     * time until Home or Sports & Live resumes (the time spent away in the player), not read from the player.
     */
    @Volatile
    private var playing: Pair<String, Long>? = null

    private data class Loaded(val days: List<CatchUpDay>, val sinceMs: Long)

    // Reloaded on an open and when the teams or the setting change (never on a timer, never on a
    // resubscription); marks and replays only re-select.
    private val loaded: StateFlow<Loaded?> = combine(
        prefs.catchUpRow,
        prefs.scoreSettings.map { it.mode != ScoresMode.OFF },
        prefs.followedTeams,
        visit
    ) { rowOn, scoresOn, followed, visit -> Triple(rowOn && scoresOn, followed, visit) }
        .distinctUntilChanged()
        .mapLatest { (on, followed, visit) ->
            if (!on || followed.isEmpty() || visit == null) return@mapLatest null
            val feeds = CatchUp.feeds(followed)
            if (feeds.isEmpty()) return@mapLatest null
            val nowMs = now()
            val range = CatchUp.daysAgo(visit.sinceMs, prefs.catchUpMarks.first(), nowMs, CatchUp::easternDate)
            // One day after another: each feed and day is asked once at most.
            Loaded(range.mapNotNull { results.catchUpDay(it, feeds) }, visit.sinceMs)
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** The row, newest first; empty when off, nothing qualifies, or before the first open. */
    val items: StateFlow<List<CatchUpItem>> = loaded
        .flatMapLatest { loaded ->
            if (loaded == null) {
                flowOf(emptyList())
            } else {
                combine(replays, prefs.followedTeams, prefs.catchUpMarks) { replays, followed, marks ->
                    CatchUp.select(loaded.days, replays, followed, loaded.sinceMs, marks, now())
                }
            }
        }
        .distinctUntilChanged()
        .onEach(::rememberSurfaced)
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_AFTER_MS), emptyList())

    /**
     * Home or Sports & Live is on screen (each resume): a replay started from the row that played
     * [CatchUp.WATCHED_AFTER_MS] or more is marked watched; "since" is worked out and the days loaded.
     */
    fun onOpened() {
        scope.launch {
            val nowMs = now()
            playing?.let { (eventId, startedAt) ->
                playing = null
                if (nowMs - startedAt >= CatchUp.WATCHED_AFTER_MS) mark(eventId, nowMs)
            }
            val since = prefs.openCatchUp(nowMs)
            visit.value = Visit(since, (visit.value?.count ?: 0) + 1)
        }
    }

    /** OK on a row item: its replay is starting. */
    fun onPlay(item: CatchUpItem) {
        playing = item.key to now()
    }

    /** "Mark as watched": the item leaves the row. */
    fun markWatched(item: CatchUpItem) {
        scope.launch { mark(item.key, now()) }
    }

    private suspend fun mark(eventId: String, nowMs: Long) {
        prefs.updateCatchUpMarks(nowMs) { it.copy(seen = it.seen + (eventId to nowMs)) }
    }

    private suspend fun rememberSurfaced(items: List<CatchUpItem>) {
        val marks = prefs.catchUpMarks.first()
        val fresh = items.filter { it.key !in marks.surfaced }
        if (fresh.isEmpty()) return
        prefs.updateCatchUpMarks(now()) { current ->
            current.copy(surfaced = current.surfaced + fresh.associate { it.key to (it.event.startMs ?: now()) })
        }
    }

    private companion object {
        const val STOP_AFTER_MS = 5_000L
    }
}
