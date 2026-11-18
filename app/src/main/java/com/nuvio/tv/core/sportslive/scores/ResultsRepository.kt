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
     * [liveNow] (Today only): games of My sports and followed teams being played now, finals the delay
     * still holds included (they are not listed yet).
     */
    data class Loaded(
        val date: LocalDate,
        val rows: List<ResultRow>,
        val events: List<ScoreSnapshot>,
        val mySports: MySports = MySports.DEFAULT,
        val heldUntil: Long? = null,
        val liveNow: Int = 0
    ) : ResultsLoad {
        /** True when ★ My sports lists at least one final: a followed team's game or one in My sports. */
        val hasMyFinals: Boolean get() = rows.any { it.followed || mySports.includes(it.event) }
    }
}

/**
 * A Results date chip: [daysAgo] → ESPN [date]. Day 0 is "Today" (ESPN's current day); for the others
 * [isDeviceYesterday] picks the "Yesterday" label.
 */
data class ResultDay(val daysAgo: Int, val date: LocalDate, val isDeviceYesterday: Boolean)

/**
 * Finals of Today, Yesterday and the 5 days before (#67) for the Results view. Per day: one `soccer/all`
 * request (limit 1000: every soccer competition) when soccer is in My sports, plus one per non-soccer
 * league in effect ([SportsResults.relevantLeagues]); past days served from the provider's disk cache and
 * kept in memory for the session (yesterday for [YESTERDAY_TTL_MS], as late games may still finish).
 * Today is still being played: a today feed the live scores fetched within [TODAY_TTL_MS] is reused
 * ([liveFeed], no request); otherwise it is fetched and kept in memory for [TODAY_TTL_MS] only. A copy
 * fetched while its day was not over is never kept as a final day.
 * No polling here: a day is fetched when it is shown. Scores Off: no requests at all.
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
    private val zone: () -> ZoneId,
    /** The live scores' copy of a feed, when fetched within the given millis; null otherwise. Never a request. */
    private val liveFeed: suspend (ScoreFeed, Long) -> List<ScoreSnapshot>? = { _, _ -> null }
) {
    @Inject
    constructor(provider: ScoreProvider, prefs: SportsLivePreferencesDataStore, live: LiveScoresRepository) : this(
        provider = provider,
        settings = prefs.scoreSettings,
        followedTeams = prefs.followedTeams,
        mySports = prefs.mySports,
        now = System::currentTimeMillis,
        zone = ZoneId::systemDefault,
        liveFeed = live::recentEvents
    )

    private class Cached(val events: List<ScoreSnapshot>, val fetchedAt: Long)

    private val lock = Mutex()
    private val cache = HashMap<ScoreFeed, Cached>()
    private val requests = Semaphore(MAX_PARALLEL)
    /** Event id → when it was first seen final, for finals that may have just ended. Guarded by [lock]. */
    private val firstSeenFinal = HashMap<String, Long>()

    /** The day last chosen on the date rail, kept while the app process lives (#67); null: none yet. */
    @Volatile
    var chosenDay: Int? = null

    /** The clock the days and the score delay go by. */
    fun nowMs(): Long = now()

    /**
     * ESPN's scoreboard date [daysAgo] days back (0 = today, 1 = yesterday), counted from today in US
     * Eastern time, so every day offered is a whole ESPN day (ESPN files games under their Eastern date).
     */
    fun dateFor(daysAgo: Int): LocalDate =
        Instant.ofEpochMilli(now()).atZone(SportsResults.EASTERN).toLocalDate().minusDays(daysAgo.toLong())

    /** The date chips: each day offered; "Yesterday" only when it is also the device's yesterday. */
    fun days(): List<ResultDay> {
        val deviceYesterday = Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate().minusDays(1)
        return SportsResults.DAYS_AGO.map { days ->
            val date = dateFor(days)
            ResultDay(days, date, isDeviceYesterday = date == deviceYesterday)
        }
    }

    /**
     * The finals of [daysAgo] (0..6; 0 = today so far): ESPN's scoreboard date [dateFor], as ESPN files
     * games (by US Eastern day). Any failure is [ResultsLoad.Failed] only when every league failed;
     * otherwise what loaded is listed.
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
        // Today: games being played (and finals still held) are counted, never listed.
        val liveNow = if (date != dateFor(SportsResults.TODAY)) 0 else events.asSequence()
            .filter { it.state == ScoreState.IN || it.eventId in held }
            .distinctBy { it.eventId }
            .count { event -> relevant.mySports.includes(event) || followed.any { SportsResults.follows(it, event) } }
        return ResultsLoad.Loaded(date, SportsResults.rows(shown, followed), events, relevant.mySports, held.values.minOrNull(), liveNow)
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

    /**
     * [feed]'s events: a today feed from the live scores when they fetched it within [TODAY_TTL_MS];
     * else from memory while still good, else from the provider (past days: its disk cache first);
     * null on failure.
     */
    private suspend fun fetch(feed: ScoreFeed): List<ScoreSnapshot>? {
        val today = dateFor(SportsResults.TODAY)
        if (feed.date == today) liveFeed(feed, TODAY_TTL_MS)?.let { return it }
        lock.withLock {
            cache[feed]?.takeIf { cached ->
                val age = now() - cached.fetchedAt
                when {
                    // Kept for good only when fetched once its day was over (before that day's yesterday).
                    feed.date.isBefore(easternDate(cached.fetchedAt).minusDays(1)) -> true
                    feed.date == today -> age < TODAY_TTL_MS
                    else -> age < YESTERDAY_TTL_MS
                }
            }?.let { return it.events }
        }
        val result = requests.withPermit { provider.scoreboard(feed.league, feed.date) }
        if (result.failed) return null
        lock.withLock {
            if (cache.size >= MAX_CACHED) cache.clear()
            cache[feed] = Cached(result.events, now())
        }
        return result.events
    }

    private fun easternDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(SportsResults.EASTERN).toLocalDate()

    companion object {
        /** Today's finals so far are fetched again at most this often (#67). */
        const val TODAY_TTL_MS = 2 * 60_000L
        private const val YESTERDAY_TTL_MS = 15 * 60_000L
        private const val MAX_PARALLEL = 4
        private const val MAX_CACHED = 200
    }
}
