package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveItem
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

    /** No followed team routes to a league and no league was seen on cards in the last 7 days. */
    data object NoLeagues : ResultsLoad

    /** Every league's request failed. */
    data object Failed : ResultsLoad

    /** [rows] to list; [events] are all the day's events fetched, for matching replays. */
    data class Loaded(val date: LocalDate, val rows: List<ResultRow>, val events: List<ScoreSnapshot>) : ResultsLoad
}

/** A Results date chip: [daysAgo] → ESPN [date]; [isDeviceYesterday] picks the "Yesterday" label. */
data class ResultDay(val daysAgo: Int, val date: LocalDate, val isDeviceYesterday: Boolean)

/**
 * Past days' finals (Yesterday … 7 days ago) for the Results view: one scoreboard request per relevant
 * league per day ([SportsResults.relevantLeagues]), past days served from the provider's disk cache and
 * kept in memory for the session (yesterday for [YESTERDAY_TTL_MS], as late games may still finish).
 * No polling: a day is fetched when it is shown. Scores Off: no requests at all.
 */
@Singleton
class ResultsRepository internal constructor(
    private val provider: ScoreProvider,
    private val settings: Flow<ScoreSettings>,
    private val followedTeams: Flow<List<FollowedTeam>>,
    private val recentLeagues: Flow<Map<String, Long>>,
    private val saveRecentLeagues: suspend (Map<String, Long>) -> Unit,
    private val now: () -> Long,
    private val zone: () -> ZoneId
) {
    @Inject
    constructor(provider: ScoreProvider, prefs: SportsLivePreferencesDataStore) : this(
        provider = provider,
        settings = prefs.scoreSettings,
        followedTeams = prefs.followedTeams,
        recentLeagues = prefs.recentScoreLeagues,
        saveRecentLeagues = prefs::setRecentScoreLeagues,
        now = System::currentTimeMillis,
        zone = ZoneId::systemDefault
    )

    private class Cached(val events: List<ScoreSnapshot>, val fetchedAt: Long)

    private val lock = Mutex()
    private val cache = HashMap<ScoreFeed, Cached>()
    private val recordLock = Mutex()
    private val requests = Semaphore(MAX_PARALLEL)

    /** Records the leagues of [items] (cards on screen) as seen now, for [SportsResults.relevantLeagues]. */
    suspend fun noteSeen(items: List<SportsLiveItem>) {
        val leagues = SportsResults.leaguesOf(items)
        recordLock.withLock {
            val next = SportsResults.recordSeen(recentLeagues.first(), leagues, now()) ?: return
            saveRecentLeagues(next)
        }
    }

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
        val relevant = SportsResults.relevantLeagues(followed, recentLeagues.first(), now())
        if (relevant.isEmpty) return ResultsLoad.NoLeagues
        val date = dateFor(daysAgo.coerceIn(SportsResults.DAYS_AGO))
        val results = coroutineScope {
            relevant.leagues.map { league -> async { fetch(ScoreFeed(league, date)) } }.awaitAll()
        }
        if (results.all { it == null }) return ResultsLoad.Failed
        val events = results.filterNotNull().flatten()
        return ResultsLoad.Loaded(date, SportsResults.rows(events, followed, relevant.followedOnly), events)
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
