package com.nuvio.tv.core.sportslive.scores

import android.util.Log
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Which ESPN endpoint a request went to: a team scoreboard, an individual sport's scoreboard, or a game summary. */
enum class EspnFeedFamily { SCOREBOARD, INDIVIDUAL, SUMMARY }

/**
 * One tracked ESPN feed: a scoreboard per league and ESPN day, or the game summary endpoint as a whole
 * ([league] and [date] null).
 */
data class EspnFeedKey(val family: EspnFeedFamily, val league: EspnLeague?, val date: LocalDate? = null) {
    /**
     * True for a feed that can still change on [today] (ESPN's day, US Eastern): a summary, or a scoreboard
     * of yesterday or later. An older day is final (Results' Earlier days): it never drives the status.
     */
    fun isLive(today: LocalDate): Boolean = date == null || !date.isBefore(today.minusDays(1))

    companion object {
        val SUMMARY = EspnFeedKey(EspnFeedFamily.SUMMARY, null)

        fun scoreboard(league: EspnLeague, date: LocalDate) = EspnFeedKey(
            if (EspnIndividualResults.covers(league)) EspnFeedFamily.INDIVIDUAL else EspnFeedFamily.SCOREBOARD,
            league,
            date
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
    val failingSince: Long? = null
) {
    val failing: Boolean get() = consecutiveFailures > 0
}

/**
 * A feed ESPN answered (HTTP success) whose data could not be read, whole or in part (live.20 TV report: one game's
 * `"odds": [null]` failed whole score feeds, silently). Kept apart from request failures: [what] it was the last time
 * ("unreadable (JsonDataException)", "2 entries skipped, first $.events[3]"), never a link.
 */
data class FeedDataError(val key: EspnFeedKey, val lastAt: Long, val times: Int, val what: String)

/**
 * Process-wide, in-memory health of ESPN's feeds (#70): every scoreboard and summary request that reached
 * the network reports its outcome here (an HTTP-cache hit proves nothing about ESPN). Read by Settings →
 * Sports & Live → Scores status and the one-time outage toast, both driven by the live feeds only.
 */
@Singleton
class EspnFeedHealth internal constructor(private val now: () -> Long) {
    @Inject
    constructor() : this(System::currentTimeMillis)

    private val state = MutableStateFlow<Map<EspnFeedKey, FeedHealth>>(emptyMap())

    /** Every feed asked for since the app started, by key. */
    val feeds: StateFlow<Map<EspnFeedKey, FeedHealth>> = state.asStateFlow()

    fun recordSuccess(key: EspnFeedKey) {
        val at = now()
        state.update { all ->
            all + (key to (all[key] ?: FeedHealth(key, at)).copy(
                lastAttemptAt = at,
                lastSuccessAt = at,
                consecutiveFailures = 0,
                failingSince = null
            ))
        }
    }

    fun recordFailure(key: EspnFeedKey, kind: FeedFailureKind, httpCode: Int? = null) {
        val at = now()
        state.update { all ->
            val old = all[key] ?: FeedHealth(key, at)
            all + (key to old.copy(
                lastAttemptAt = at,
                lastFailureAt = at,
                lastFailure = kind,
                lastHttpCode = httpCode.takeIf { kind == FeedFailureKind.HTTP },
                consecutiveFailures = old.consecutiveFailures + 1,
                failingSince = old.failingSince ?: at
            ))
        }
    }

    private val dataErrorState = MutableStateFlow<Map<EspnFeedKey, FeedDataError>>(emptyMap())

    /** The feeds whose data could not be read since the app started, by key (live.20). */
    val dataErrors: StateFlow<Map<EspnFeedKey, FeedDataError>> = dataErrorState.asStateFlow()

    /**
     * [key]'s answer could not be read, whole or in part ([what], no link): kept apart from [recordFailure] and logged
     * under [DATA_TAG]. A request that failed is not a data error.
     */
    fun recordDataError(key: EspnFeedKey, what: String) {
        val at = now()
        dataErrorState.update { all ->
            val times = (all[key]?.times ?: 0) + 1
            all + (key to FeedDataError(key, at, times, what))
        }
        Log.w(DATA_TAG, "${feedName(key)}: $what")
    }

    /** Report a problem's lines (#172), newest first: "scoreboard soccer/ger.1 2026-11-20 · 3x · 12 min ago · 1 entry skipped...". */
    fun dataErrorLines(nowMs: Long): List<String> = dataErrorState.value.values.sortedByDescending { it.lastAt }.map { error ->
        "${feedName(error.key)} · ${error.times}x · ${(nowMs - error.lastAt).coerceAtLeast(0L) / 60_000L} min ago · ${error.what}"
    }

    /** Live feeds asked for within [RELEVANT_MS] of [nowMs]: the ones in use now. */
    fun relevant(nowMs: Long): List<FeedHealth> = inUse(state.value.values, nowMs)

    /** The live scoreboards in use now (yesterday or later, never a past day): "Retry now" fetches each once. */
    fun scoreboardsInUse(nowMs: Long): List<ScoreFeed> = relevant(nowMs).mapNotNull { feed ->
        val league = feed.key.league ?: return@mapNotNull null
        val date = feed.key.date ?: return@mapNotNull null
        ScoreFeed(league, date)
    }

    /** True when every feed in use has failed without a break for at least [forMs]. */
    fun allFailingFor(nowMs: Long, forMs: Long): Boolean {
        val inUse = relevant(nowMs)
        if (inUse.isEmpty() || !inUse.all { it.failing }) return false
        return nowMs - inUse.maxOf { it.failingSince!! } >= forMs
    }

    companion object {
        /** The log tag of [recordDataError]. */
        const val DATA_TAG = "NuvioFeedData"

        /** "scoreboard soccer/ger.1 2026-11-20", "summary": a feed's name with no link. */
        fun feedName(key: EspnFeedKey): String =
            listOfNotNull(key.family.name.lowercase(), key.league?.path, key.date?.toString()).joinToString(" ")

        /** A feed not asked for in this long no longer counts (its screen closed, its day ended). */
        const val RELEVANT_MS = 20 * 60_000L

        /** ESPN's day (US Eastern) at [nowMs]. */
        fun espnToday(nowMs: Long): LocalDate = Instant.ofEpochMilli(nowMs).atZone(SportsResults.EASTERN).toLocalDate()

        /** Of [feeds], the live ones asked for within [RELEVANT_MS] of [nowMs]. */
        fun inUse(feeds: Collection<FeedHealth>, nowMs: Long): List<FeedHealth> {
            val today = espnToday(nowMs)
            return feeds.filter { nowMs - it.lastAttemptAt <= RELEVANT_MS && it.key.isLive(today) }
        }

        /**
         * True when a scoreboard read without error has lost what its events need: a game in progress or
         * final without competitors, or every event without competitors and none of them scheduled. A
         * scheduled game without them (a TBD playoff slot) is not a change, nor is a day without events.
         * Team scoreboards only: an individual sport's tournament may list no matches yet.
         */
        fun formatChanged(dto: EspnScoreboardDto): Boolean {
            val events = dto.events.orEmpty()
            if (events.isEmpty()) return false
            fun EspnEventDto.lacksSides() = competitions.orEmpty().none { !it.competitors.isNullOrEmpty() }
            fun EspnEventDto.state() = status?.type?.state
            if (events.any { it.lacksSides() && (it.state() == "in" || it.state() == "post") }) return true
            return events.all { it.lacksSides() && it.state() != "pre" }
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
            val inUse = EspnFeedHealth.inUse(feeds, nowMs)
            // Only past days asked for so far (Results' Earlier): nothing live has been checked.
            if (feeds.none { it.key.isLive(EspnFeedHealth.espnToday(nowMs)) }) return NotChecked
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
