package com.nuvio.tv.core.sportslive.scores

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.io.InterruptedIOException
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Which ESPN endpoint a request went to: a team scoreboard, an individual sport's scoreboard, or a game summary. */
enum class EspnFeedFamily { SCOREBOARD, INDIVIDUAL, SUMMARY }

/** One tracked ESPN feed: a scoreboard per league, or the game summary endpoint as a whole ([league] null). */
data class EspnFeedKey(val family: EspnFeedFamily, val league: EspnLeague?) {
    companion object {
        val SUMMARY = EspnFeedKey(EspnFeedFamily.SUMMARY, null)

        fun scoreboard(league: EspnLeague) = EspnFeedKey(
            if (EspnIndividualResults.covers(league)) EspnFeedFamily.INDIVIDUAL else EspnFeedFamily.SCOREBOARD,
            league
        )
    }
}

/** Why a request failed. */
enum class FeedFailureKind {
    /** ESPN answered with an HTTP error. */
    HTTP,
    /** ESPN did not answer in time. */
    TIMEOUT,
    /** No connection (host not found, connection refused or reset). */
    NETWORK,
    /** The body was unreadable, or read but missing what every event needs: ESPN changed its data. */
    FORMAT;

    companion object {
        /** The failure kind of an exception thrown while fetching or reading a feed. */
        fun of(e: Throwable): FeedFailureKind = when (e) {
            // Moshi's read errors (JsonEncodingException is an IOException: checked first).
            is JsonDataException, is JsonEncodingException -> FORMAT
            is InterruptedIOException -> TIMEOUT
            is IOException -> NETWORK
            else -> FORMAT
        }
    }
}

/** One feed's health. [failingSince] is when the current run of failures began; null while it works. */
data class FeedHealth(
    val key: EspnFeedKey,
    val lastAttemptAt: Long,
    val lastSuccessAt: Long? = null,
    val lastFailureAt: Long? = null,
    val lastFailure: FeedFailureKind? = null,
    /** The HTTP status of the last failure when it was [FeedFailureKind.HTTP]. */
    val lastHttpCode: Int? = null,
    val consecutiveFailures: Int = 0,
    val failingSince: Long? = null,
    /** A scoreboard's last requested ESPN day: what "Retry now" asks for again. */
    val lastDate: LocalDate? = null
) {
    val failing: Boolean get() = consecutiveFailures > 0
}

/**
 * Process-wide, in-memory health of ESPN's feeds (#70): every scoreboard and summary request reports its
 * outcome here. Read by Settings → Sports & Live → Scores status and the one-time outage toast.
 */
@Singleton
class EspnFeedHealth internal constructor(private val now: () -> Long) {
    @Inject
    constructor() : this(System::currentTimeMillis)

    private val state = MutableStateFlow<Map<EspnFeedKey, FeedHealth>>(emptyMap())

    /** Every feed asked for since the app started, by key. */
    val feeds: StateFlow<Map<EspnFeedKey, FeedHealth>> = state.asStateFlow()

    fun recordSuccess(key: EspnFeedKey, date: LocalDate? = null) {
        val at = now()
        state.update { all ->
            val old = all[key]
            all + (key to (old ?: FeedHealth(key, at)).copy(
                lastAttemptAt = at,
                lastSuccessAt = at,
                consecutiveFailures = 0,
                failingSince = null,
                lastDate = date ?: old?.lastDate
            ))
        }
    }

    fun recordFailure(key: EspnFeedKey, kind: FeedFailureKind, httpCode: Int? = null, date: LocalDate? = null) {
        val at = now()
        state.update { all ->
            val old = all[key] ?: FeedHealth(key, at)
            all + (key to old.copy(
                lastAttemptAt = at,
                lastFailureAt = at,
                lastFailure = kind,
                lastHttpCode = httpCode.takeIf { kind == FeedFailureKind.HTTP },
                consecutiveFailures = old.consecutiveFailures + 1,
                failingSince = old.failingSince ?: at,
                lastDate = date ?: old.lastDate
            ))
        }
    }

    /** Feeds asked for within [RELEVANT_MS] of [nowMs]: the ones in use now. */
    fun relevant(nowMs: Long): List<FeedHealth> =
        state.value.values.filter { nowMs - it.lastAttemptAt <= RELEVANT_MS }

    /** The scoreboards in use now, at their last requested day: "Retry now" fetches each once. */
    fun scoreboardsInUse(nowMs: Long): List<ScoreFeed> = relevant(nowMs).mapNotNull { feed ->
        val league = feed.key.league ?: return@mapNotNull null
        val date = feed.lastDate ?: return@mapNotNull null
        ScoreFeed(league, date)
    }

    /** True when every feed in use has failed without a break for at least [forMs]. */
    fun allFailingFor(nowMs: Long, forMs: Long): Boolean {
        val inUse = relevant(nowMs)
        if (inUse.isEmpty() || !inUse.all { it.failing }) return false
        return nowMs - inUse.maxOf { it.failingSince!! } >= forMs
    }

    companion object {
        /** A feed not asked for in this long no longer counts (its screen closed, its day ended). */
        const val RELEVANT_MS = 20 * 60_000L

        /**
         * True when a scoreboard read without error has lost what every event needs: events are listed but
         * none has a competition with competitors. Such a day is a format change, not an empty day. Team
         * scoreboards only: an individual sport's tournament may list no matches yet.
         */
        fun formatChanged(dto: EspnScoreboardDto): Boolean {
            val events = dto.events.orEmpty()
            if (events.isEmpty()) return false
            return events.none { event -> event.competitions.orEmpty().any { !it.competitors.isNullOrEmpty() } }
        }
    }
}

/** What Settings → Sports & Live → Scores status shows. */
sealed interface ScoresStatus {
    /** Scores is Off: nothing is fetched. */
    data object Off : ScoresStatus

    /** No feed asked for yet in this session. */
    data object NotChecked : ScoresStatus

    data object Working : ScoresStatus

    /** Some feeds in use fail, since the earliest of their failures began. */
    data class SomeFailing(val sinceMs: Long, val failing: List<FeedHealth>) : ScoresStatus

    /** Every feed in use fails, since the last of them began failing. */
    data class NotResponding(val sinceMs: Long, val failing: List<FeedHealth>) : ScoresStatus

    companion object {
        fun of(mode: ScoresMode, feeds: Collection<FeedHealth>, nowMs: Long): ScoresStatus {
            if (mode == ScoresMode.OFF) return Off
            if (feeds.isEmpty()) return NotChecked
            val inUse = feeds.filter { nowMs - it.lastAttemptAt <= EspnFeedHealth.RELEVANT_MS }
            val failing = inUse.filter { it.failing }.sortedBy { it.failingSince }
            return when {
                failing.isEmpty() -> Working
                failing.size == inUse.size -> NotResponding(failing.maxOf { it.failingSince!! }, failing)
                else -> SomeFailing(failing.minOf { it.failingSince!! }, failing)
            }
        }
    }
}

/**
 * The one-time "Live scores aren't available right now" toast (#70): shown once per app session, when every
 * feed in use has failed for [AFTER_MS] while Sports & Live or the player panel is in use, never while
 * Scores is Off.
 */
class ScoresOutageNotice(private val health: EspnFeedHealth) {
    @Volatile
    var shown = false
        private set

    /** True (once) when the toast should show now; the caller shows it. */
    fun check(nowMs: Long, mode: ScoresMode, inUse: Boolean): Boolean {
        if (shown || mode == ScoresMode.OFF || !inUse) return false
        if (!health.allFailingFor(nowMs, AFTER_MS)) return false
        shown = true
        return true
    }

    companion object {
        const val AFTER_MS = 10 * 60_000L
    }
}
