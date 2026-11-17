package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.domain.model.FollowedTeam
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** One Results day, as the Results view shows it. */
sealed interface ResultsLoad {
    /** Scores are Off: nothing is shown and nothing was requested. */
    data object Off : ResultsLoad

    /** My sports (with followed teams' sports) gives no feed to fetch. */
    data object NoLeagues : ResultsLoad

    /** Every league's request failed. */
    data object Failed : ResultsLoad

    /**
     * [rows] to list; [events] are all the day's events fetched, for matching replays; [mySports] is
     * My sports with followed teams' sports, for the ★ My sports view and the sport chips. [heldUntil]:
     * when finals the score delay holds back are due (null: none held); load the day again then.
     */
    data class Loaded(
        val date: LocalDate,
        val rows: List<ResultRow>,
        val events: List<ScoreSnapshot>,
        val mySports: MySports = MySports.DEFAULT,
        val heldUntil: Long? = null
    ) : ResultsLoad
}

/** A Results date chip: [daysAgo] → ESPN [date]; [isDeviceYesterday] picks the "Yesterday" label. */
data class ResultDay(val daysAgo: Int, val date: LocalDate, val isDeviceYesterday: Boolean)

/**
 * Past days' finals (Yesterday … 7 days ago) for the Results view. Per day: one `soccer/all` request
 * (limit 1000: every soccer competition) when soccer is in My sports, plus one per non-soccer league in
 * effect ([SportsResults.relevantLeagues]); past days served from the provider's disk cache and
 * kept in memory for the session (yesterday for [YESTERDAY_TTL_MS], as late games may still finish).
 * No polling: a day is fetched when it is shown. Scores Off: no requests at all.
 * The score delay applies as on the cards ([ScoreDelay]): a final that may have just ended
 * ([ScoreDelay.mayHaveJustEnded]) is left out until the delay has passed since it was first seen.
 */
@Singleton
class ResultsRepository internal constructor(
    private val provider: ScoreProvider,
    private val settings: Flow<ScoreSettings>,
    private val followedTeams: Flow<List<FollowedTeam>>,
    private val mySports: Flow<MySports>,
    private val now: () -> Long,
    private val zone: () -> ZoneId
) {
    @Inject
    constructor(provider: ScoreProvider, prefs: SportsLivePreferencesDataStore) : this(
        provider = provider,
        settings = prefs.scoreSettings,
        followedTeams = prefs.followedTeams,
        mySports = prefs.mySports,
        now = System::currentTimeMillis,
        zone = ZoneId::systemDefault
    )

    private class Cached(val events: List<ScoreSnapshot>, val fetchedAt: Long)

    private val lock = Mutex()
    private val cache = HashMap<ScoreFeed, Cached>()
    private val requests = Semaphore(MAX_PARALLEL)
    /** Event id → when it was first seen final, for finals that may have just ended. Guarded by [lock]. */
    private val firstSeenFinal = HashMap<String, Long>()

    /**
     * ESPN's scoreboard date [daysAgo] days back (1 = yesterday), counted from today in US Eastern time,
     * so every day offered is a whole ESPN day (ESPN files games under their Eastern date).
     */
    fun dateFor(daysAgo: Int): LocalDate =
        Instant.ofEpochMilli(now()).atZone(SportsResults.EASTERN).toLocalDate().minusDays(daysAgo.toLong())

    /** The date chips: each day offered, labelled "Yesterday" only when it is also the device's yesterday. */
    fun days(): List<ResultDay> {
        val deviceYesterday = Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate().minusDays(1)
        return SportsResults.DAYS_AGO.map { days ->
            val date = dateFor(days)
            ResultDay(days, date, isDeviceYesterday = date == deviceYesterday)
        }
    }

    /**
     * The finals of [daysAgo] (1..7): ESPN's scoreboard date [dateFor], as ESPN files games (by US Eastern day).
     * Any failure is [ResultsLoad.Failed] only when every league failed; otherwise what loaded is listed.
     */
    suspend fun load(daysAgo: Int): ResultsLoad {
        val settings = settings.first()
        if (settings.mode == ScoresMode.OFF) return ResultsLoad.Off
        val followed = followedTeams.first()
        val relevant = SportsResults.relevantLeagues(mySports.first(), followed)
        if (relevant.isEmpty) return ResultsLoad.NoLeagues
        val date = dateFor(daysAgo.coerceIn(SportsResults.DAYS_AGO))
        val results = coroutineScope {
            relevant.leagues.map { league -> async { fetch(ScoreFeed(league, date)) } }.awaitAll()
        }
        if (results.all { it == null }) return ResultsLoad.Failed
        val events = results.filterNotNull().flatten()
        val held = held(events, settings.delaySeconds * 1_000L)
        val shown = if (held.isEmpty()) events else events.filter { it.eventId !in held }
        return ResultsLoad.Loaded(date, SportsResults.rows(shown, followed), events, relevant.mySports, held.values.minOrNull())
    }

    /** The finals of [events] the score delay still holds back: event id → when each is due. */
    private suspend fun held(events: List<ScoreSnapshot>, delayMs: Long): Map<String, Long> {
        if (delayMs <= 0) return emptyMap()
        val nowMs = now()
        return lock.withLock {
            if (firstSeenFinal.size >= MAX_CACHED * 10) firstSeenFinal.clear()
            events.filter { ScoreDelay.mayHaveJustEnded(it, nowMs) }
                .associate { it.eventId to firstSeenFinal.getOrPut(it.eventId) { nowMs } + delayMs }
                .filterValues { due -> due > nowMs }
        }
    }

    /** [feed]'s events from memory when still good, else from the provider (its disk cache first); null on failure. */
    private suspend fun fetch(feed: ScoreFeed): List<ScoreSnapshot>? {
        val yesterday = dateFor(1)
        lock.withLock {
            cache[feed]?.takeIf { feed.date.isBefore(yesterday) || now() - it.fetchedAt < YESTERDAY_TTL_MS }
                ?.let { return it.events }
        }
        val result = requests.withPermit { provider.scoreboard(feed.league, feed.date) }
        if (result.failed) return null
        lock.withLock {
            if (cache.size >= MAX_CACHED) cache.clear()
            cache[feed] = Cached(result.events, now())
        }
        return result.events
    }

    private companion object {
        const val YESTERDAY_TTL_MS = 15 * 60_000L
        const val MAX_PARALLEL = 4
        const val MAX_CACHED = 200
    }
}
