package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.SportsLivePlayedTime
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * A game saved with "Watch later (no spoilers)" (#151), or one whose protection ended: by ESPN [eventId],
 * with ESPN's side names and start (what a Replays card is matched by). [endedAtMs] is set once protection
 * ended (Reveal result, the replay finished, or watched live); the entry is kept a while so "Hide scores of
 * my teams' games" does not protect the game again and so the end syncs to the other TVs.
 */
data class ProtectedGame(
    val eventId: String,
    val home: String?,
    val away: String?,
    val startMs: Long?,
    val savedAtMs: Long,
    val endedAtMs: Long? = null
)

/**
 * Spoiler protection (#151): which games hide their score everywhere. A game is protected when it was saved
 * for later within [PROTECT_MS] and not ended, or, with [myTeams] on ("Hide scores of my teams' games until
 * I've watched"), when a followed team plays in it, it started within [PROTECT_MS] and it was not watched or
 * revealed; or, with [myTeamsResults] on ("Hide my teams' results until I reveal", M2, live.23), the same for a
 * followed team's finished game only (its live score is left to [myTeams]). Protection wins over the Scores setting and over Hide until focused's focus reveal; only the Game
 * page's Reveal result lifts it. Every surface reads it through [ScoreSnapshot.spoilerProtected], stamped by
 * [stamp] where the scores are served.
 */
data class SpoilerProtection(
    val games: List<ProtectedGame> = emptyList(),
    val myTeams: Boolean = false,
    val followed: List<FollowedTeam> = emptyList(),
    val nowMs: Long = 0L,
    val myTeamsResults: Boolean = false
) {
    private val byId: Map<String, ProtectedGame> by lazy { games.associateBy { it.eventId } }

    /** True when [event]'s score must stay hidden. */
    fun protects(event: ScoreSnapshot): Boolean {
        val entry = byId[event.eventId]
        if (entry != null) return entry.endedAtMs == null && nowMs - entry.savedAtMs < PROTECT_MS
        if (followed.isEmpty() || event.state == ScoreState.PRE) return false
        val finished = event.state == ScoreState.POST || event.isFinal
        if (!myTeams && !(myTeamsResults && finished)) return false
        val start = event.startMs ?: return false
        if (nowMs - start >= PROTECT_MS) return false
        return TeamFollows.followersOf(followed, event, event.home).isNotEmpty() ||
            TeamFollows.followersOf(followed, event, event.away).isNotEmpty()
    }

    /** True when [eventId] was saved for later and is still protected (the More… entry reads "Reveal result"). */
    fun isSaved(eventId: String): Boolean = byId[eventId]?.let { it.endedAtMs == null && nowMs - it.savedAtMs < PROTECT_MS } == true

    /**
     * True when the viewer chose to see [eventId]'s result, or watched it: its protection ended (Reveal result, the
     * replay played to its end, watched live). Results shows a followed team's score only then (results redesign).
     */
    fun revealed(eventId: String): Boolean = byId[eventId]?.endedAtMs != null

    /** The games saved for later and still protected, the newest first: Replays' "Saved for later". */
    val saved: List<ProtectedGame> get() = games.filter { isSaved(it.eventId) }.sortedByDescending { it.savedAtMs }

    /** [scores] with each snapshot's [ScoreSnapshot.spoilerProtected] set from these rules ([stamp]). */
    fun <K> stampAll(scores: Map<K, ScoreSnapshot>): Map<K, ScoreSnapshot> =
        scores.mapValues { (_, event) -> stamp(event) }

    /** [event] with [ScoreSnapshot.spoilerProtected] set from these rules. */
    fun stamp(event: ScoreSnapshot): ScoreSnapshot {
        val hidden = protects(event)
        return if (event.spoilerProtected == hidden) event else event.copy(spoilerProtected = hidden)
    }

    /**
     * The Replays cards of saved games ([saved]), by card key: a card strictly matched to the game
     * ([matches], Replays' own ESPN matches), else one naming the same two sides, either way round, within
     * [REPLAY_WINDOW_MS] of its start.
     */
    fun savedReplayKeys(replays: List<SportsLiveItem>, matches: Map<String, ScoreSnapshot>): Set<String> {
        val saved = saved
        if (saved.isEmpty()) return emptySet()
        val ids = saved.mapTo(HashSet()) { it.eventId }
        return replays.filterTo(LinkedHashSet()) { item ->
            matches[item.streamKey]?.eventId?.let { it in ids } == true || saved.any { sameGame(it, item) }
        }.mapTo(LinkedHashSet()) { it.streamKey }
    }

    companion object {
        /** Protection ends on its own this long after the game was saved (or, for my teams, started). */
        const val PROTECT_MS = 7 * 24 * 60 * 60_000L

        /** Ended entries are kept this long (so they sync and are not protected again), then dropped. */
        const val KEEP_ENDED_MS = PROTECT_MS + 24 * 60 * 60_000L

        /** A replay is listed up to a day and a half after its game started. */
        const val REPLAY_WINDOW_MS = 36 * 60 * 60_000L

        /** True when [item] (a replay card) is [game]: the same two sides, either way round, near its start. */
        fun sameGame(game: ProtectedGame, item: SportsLiveItem): Boolean {
            val home = game.home ?: return false
            val away = game.away ?: return false
            val teams = item.teams ?: return false
            val start = game.startMs
            val itemStart = item.startMs
            if (start != null && itemStart != null && abs(itemStart - start) > REPLAY_WINDOW_MS) return false
            return (SportsLiveTeams.matches(home, teams.home) && SportsLiveTeams.matches(away, teams.away)) ||
                (SportsLiveTeams.matches(home, teams.away) && SportsLiveTeams.matches(away, teams.home))
        }

        /** What is stored for [event] saved at [nowMs]. */
        fun of(event: ScoreSnapshot, nowMs: Long): ProtectedGame = ProtectedGame(
            eventId = event.eventId,
            home = sideName(event.home),
            away = sideName(event.away),
            startMs = event.startMs,
            savedAtMs = nowMs
        )

        /** [games] with [event] saved (again) at [nowMs]. */
        fun saved(games: List<ProtectedGame>, event: ScoreSnapshot, nowMs: Long): List<ProtectedGame> =
            pruned(games.filterNot { it.eventId == event.eventId } + of(event, nowMs), nowMs)

        /**
         * [games] with protection of [event] ended at [nowMs]: a saved entry is marked ended; a game protected
         * only by "my teams" gets an ended entry, so it is not protected again.
         */
        fun ended(games: List<ProtectedGame>, event: ScoreSnapshot, nowMs: Long): List<ProtectedGame> {
            val current = games.firstOrNull { it.eventId == event.eventId }
            if (current?.endedAtMs != null) return pruned(games, nowMs)
            val entry = (current ?: of(event, nowMs)).copy(endedAtMs = nowMs)
            return pruned(games.filterNot { it.eventId == event.eventId } + entry, nowMs)
        }

        /** [games] with the saved entries [eventIds] ended at [nowMs] (a finished replay knows only the id). */
        fun endedIds(games: List<ProtectedGame>, eventIds: Set<String>, nowMs: Long): List<ProtectedGame> =
            pruned(games.map { if (it.eventId in eventIds && it.endedAtMs == null) it.copy(endedAtMs = nowMs) else it }, nowMs)

        /** [games] without entries saved or ended over [KEEP_ENDED_MS] ago. */
        fun pruned(games: List<ProtectedGame>, nowMs: Long): List<ProtectedGame> =
            games.filter { nowMs - maxOf(it.savedAtMs, it.endedAtMs ?: 0L) < KEEP_ENDED_MS }

        private fun sideName(team: ScoreTeam): String? =
            listOf(team.displayName, team.shortDisplayName, team.name).firstOrNull { !it.isNullOrBlank() }?.trim()
    }
}

/**
 * The active profile's spoiler protection (#151) and its changes: save a game for later, reveal it, and the
 * ends that happen on their own (a saved game's replay played to its end, a followed team's game watched
 * live). The list lives in the profile's Sports & Live settings, so it syncs with them (#141).
 */
@Singleton
class SpoilerProtectionStore internal constructor(
    private val prefs: SportsLivePreferencesDataStore,
    played: SportsLivePlayedTime,
    private val now: () -> Long,
    scope: CoroutineScope
) {
    @Inject
    constructor(prefs: SportsLivePreferencesDataStore) : this(
        prefs = prefs,
        played = SportsLivePlayedTime.shared,
        now = System::currentTimeMillis,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    )

    /** Replays started for saved games: content id to ESPN event id (this process only). */
    private val replayGames = ConcurrentHashMap<String, String>()

    val protection: Flow<SpoilerProtection> = prefs.spoilerProtection

    init {
        scope.launch {
            played.finished.collect { contentId -> replayGames.remove(contentId)?.let { onReplayFinished(it) } }
        }
    }

    /** More… → Watch later (no spoilers). */
    suspend fun saveForLater(event: ScoreSnapshot) {
        prefs.updateProtectedGames { SpoilerProtection.saved(it, event, now()) }
    }

    /** Reveal result: [event]'s protection ends. */
    suspend fun reveal(event: ScoreSnapshot) {
        prefs.updateProtectedGames { SpoilerProtection.ended(it, event, now()) }
    }

    /** [replays] start playing: those of a saved game end its protection when one plays to its end. */
    suspend fun onReplayStarted(replays: List<SportsLiveItem>, matched: ScoreSnapshot? = null) {
        val protection = prefs.spoilerProtection.first()
        val saved = protection.saved
        if (saved.isEmpty()) return
        replays.forEach { replay ->
            val game = matched?.eventId?.takeIf { protection.isSaved(it) }
                ?: saved.firstOrNull { SpoilerProtection.sameGame(it, replay) }?.eventId
                ?: return@forEach
            replayGames[replay.id] = game
        }
    }

    /** Followed teams' games played live (#151 setting): not protected afterwards. */
    suspend fun onWatchedLive(events: List<ScoreSnapshot>) {
        if (events.isEmpty()) return
        val current = prefs.spoilerProtection.first()
        val fresh = events.filter { event -> current.games.none { it.eventId == event.eventId && it.endedAtMs != null } }
        if (fresh.isEmpty()) return
        prefs.updateProtectedGames { games -> fresh.fold(games) { acc, event -> SpoilerProtection.ended(acc, event, now()) } }
    }

    private suspend fun onReplayFinished(eventId: String) {
        prefs.updateProtectedGames { SpoilerProtection.endedIds(it, setOf(eventId), now()) }
    }
}
