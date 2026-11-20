package com.nuvio.tv.core.sportslive

import androidx.annotation.VisibleForTesting
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Where a followed team's replay was left (coordinator 2026-11-20): by ESPN event id, never an addon url or id. */
data class ReplayPosition(val positionMs: Long, val durationMs: Long, val atMs: Long)

/**
 * Replay resume for Your teams and Home's Catch up (coordinator 2026-11-20). Sports stays out of upstream Continue
 * Watching (BUG15), so a replay's place is kept here instead, per profile ([SportsLiveReplayPositions]). A card's OK
 * [register]s its replays (the addon content ids the player will report) with their ESPN event and the place to start
 * from; the full player then starts there ([startFor]) and reports its progress ([onProgress]). Live streams are never
 * registered and have no duration, so they store nothing.
 */
object SportsLiveReplayResume {
    /** A place this early is not worth offering: the replay starts over. */
    const val MIN_RESUME_MS = 60_000L

    /** Played this far, the replay is done: nothing to resume. */
    const val DONE_SHARE = 0.95

    /** How long a place is kept (a replay leaves Your teams within 7 days of being saved for later). */
    const val KEEP_MS = 14L * 24 * 60 * 60 * 1000

    /** At most this many places per profile, the newest. */
    const val MAX_ENTRIES = 50

    /** A report this far before the start asked for is the player before its seek: ignored. */
    private const val SEEK_SLACK_MS = 10_000L
    private const val MAX_IDS = 200

    private val eventOf = LinkedHashMap<String, String>()
    private val startOf = HashMap<String, Long>()

    /** Saves a replay's place: set by [SportsLiveReplayPositions]. */
    @Volatile
    var sink: ((eventId: String, positionMs: Long, durationMs: Long) -> Unit)? = null

    /** OK on a replay card: its [contentIds] belong to [eventId], and start at [startMs] (null: from the beginning). */
    @Synchronized
    fun register(contentIds: Collection<String>, eventId: String, startMs: Long?) {
        contentIds.forEach { id ->
            eventOf.remove(id)
            eventOf[id] = eventId
            if (startMs != null && startMs > 0L) startOf[id] = startMs else startOf.remove(id)
        }
        while (eventOf.size > MAX_IDS) {
            val oldest = eventOf.keys.first()
            eventOf.remove(oldest)
            startOf.remove(oldest)
        }
    }

    /** Where the player starts [contentId] (until it has reported a place past it); null: as it would anyway. */
    @Synchronized
    fun startFor(contentId: String?): Long? = contentId?.let(startOf::get)

    @Synchronized
    fun eventFor(contentId: String?): String? = contentId?.let(eventOf::get)

    /** The full player's progress on [contentId]: a registered replay's place is saved. */
    fun onProgress(contentId: String?, positionMs: Long, durationMs: Long) {
        if (contentId == null || durationMs <= 0L || positionMs <= 0L) return
        val event = synchronized(this) {
            val event = eventOf[contentId] ?: return
            val start = startOf[contentId]
            if (start != null) {
                if (positionMs + SEEK_SLACK_MS < start) return
                startOf.remove(contentId)
            }
            event
        }
        sink?.invoke(event, positionMs, durationMs)
    }

    /** Where a card offers to resume [position]; null: start over (too early, or done). */
    fun resumeAt(position: ReplayPosition?): Long? = position?.takeIf {
        it.positionMs >= MIN_RESUME_MS && (it.durationMs <= 0L || it.positionMs < it.durationMs * DONE_SHARE)
    }?.positionMs

    /** "1:12:30", or "12:30" under an hour. */
    fun clock(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** [positions] with [eventId] at [position] (null: forgotten), older than [KEEP_MS] dropped, the newest [MAX_ENTRIES]. */
    fun updated(positions: Map<String, ReplayPosition>, eventId: String, position: ReplayPosition?, nowMs: Long): Map<String, ReplayPosition> {
        val next = if (position == null) positions - eventId else positions + (eventId to position)
        return next.filterValues { nowMs - it.atMs < KEEP_MS }
            .entries.sortedByDescending { it.value.atMs }
            .take(MAX_ENTRIES)
            .associate { it.key to it.value }
    }

    /** Stored as "position:duration:at" strings (plain types survive minification). */
    fun encode(positions: Map<String, ReplayPosition>): Map<String, String> =
        positions.mapValues { (_, p) -> "${p.positionMs}:${p.durationMs}:${p.atMs}" }

    fun decode(stored: Map<String, String>): Map<String, ReplayPosition> = stored.mapNotNull { (event, value) ->
        val parts = value.split(':').mapNotNull { it.toLongOrNull() }
        if (parts.size != 3) null else event to ReplayPosition(parts[0], parts[1], parts[2])
    }.toMap()

    @VisibleForTesting
    @Synchronized
    internal fun reset() {
        eventOf.clear()
        startOf.clear()
        sink = null
    }
}

/** The active profile's replay places ([SportsLiveReplayResume]); it saves what the player reports. */
@Singleton
class SportsLiveReplayPositions @Inject constructor(private val prefs: SportsLivePreferencesDataStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val positions: Flow<Map<String, ReplayPosition>> = prefs.replayPositions

    init {
        SportsLiveReplayResume.sink = { event, position, duration ->
            scope.launch { save(event, ReplayPosition(position, duration, System.currentTimeMillis())) }
        }
        // Played to its end: nothing left to resume.
        scope.launch {
            SportsLivePlayedTime.shared.finished.collect { contentId ->
                SportsLiveReplayResume.eventFor(contentId)?.let { forget(it) }
            }
        }
    }

    private suspend fun save(eventId: String, position: ReplayPosition?) {
        val nowMs = System.currentTimeMillis()
        prefs.updateReplayPositions { SportsLiveReplayResume.updated(it, eventId, position, nowMs) }
    }

    /** Start over: the place is forgotten. */
    fun forget(eventId: String) {
        scope.launch { save(eventId, null) }
    }
}
