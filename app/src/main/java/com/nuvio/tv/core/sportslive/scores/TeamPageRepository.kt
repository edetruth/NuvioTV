package com.nuvio.tv.core.sportslive.scores

import android.content.Context
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** ESPN's team endpoints (the Team page, #62). Never throws: a failure is null. */
interface TeamPageSource {
    /** `{sport}/{league}/teams/{id}`: name, logos, record and standing. */
    suspend fun team(league: EspnLeague, teamId: String): String?

    /** `{sport}/{league}/teams/{id}/schedule`; soccer lists only played games unless [fixtures] (`?fixture=true`). */
    suspend fun teamSchedule(league: EspnLeague, teamId: String, fixtures: Boolean = false): String?
}

/** A Team page load: the page (from the disk copy of its finals when ESPN could not be reached: [stale]), or a failure. */
sealed interface TeamPageLoad {
    data class Loaded(val data: TeamPageData, val stale: Boolean = false) : TeamPageLoad
    data object Failed : TeamPageLoad
}

/**
 * One team's page (#62), requested only when the page asks (on open; Scores Off: not before Reveal):
 * the team, its schedule (soccer: also its fixtures) and, outside soccer, the next game's summary for the
 * season leaders, in parallel. A page is reused for [TTL_MS], so reopening it (back from a Game page)
 * requests nothing. The latest finals are kept on [disk], a trimmed copy, and shown when ESPN cannot be
 * reached. Failure-silent.
 */
@Singleton
class TeamPageRepository internal constructor(
    private val source: TeamPageSource,
    private val summaries: GameSummarySource,
    private val disk: GameSummaryDiskCache?,
    moshi: Moshi,
    private val now: () -> Long
) {
    @Inject
    constructor(source: TeamPageSource, summaries: GameSummarySource, @ApplicationContext context: Context, moshi: Moshi) : this(
        source = source,
        summaries = summaries,
        disk = GameSummaryDiskCache(File(context.cacheDir, "espn_team_pages")),
        moshi = moshi,
        now = System::currentTimeMillis
    )

    private class Cached(val data: TeamPageData, val fetchedAt: Long)

    private val espnMoshi = EspnJson.guarded(moshi)
    private val teamAdapter = espnMoshi.adapter(EspnTeamResponseDto::class.java)
    private val scheduleAdapter = espnMoshi.adapter(EspnTeamScheduleDto::class.java)
    private val summaryAdapter = espnMoshi.adapter(EspnSummaryDto::class.java)
    private val lock = Mutex()
    private val memory = LinkedHashMap<String, Cached>()

    suspend fun load(league: EspnLeague, teamId: String): TeamPageLoad {
        val key = "${league.sport}_${league.league}_$teamId"
        lock.withLock {
            memory[key]?.takeIf { now() - it.fetchedAt < TTL_MS }?.let { return TeamPageLoad.Loaded(it.data) }
        }
        val soccer = league.sport == "soccer"
        val (teamJson, scheduleJson, fixturesJson) = coroutineScope {
            val team = async { source.team(league, teamId) }
            val schedule = async { source.teamSchedule(league, teamId) }
            val fixtures = async { if (soccer) source.teamSchedule(league, teamId, fixtures = true) else null }
            Triple(team.await(), schedule.await(), fixtures.await())
        }
        val team = teamJson?.let { json -> parse { teamAdapter.fromJson(json) } }?.team
        val schedule = scheduleJson?.let { json -> parse { scheduleAdapter.fromJson(json) } }
        val fixtures = fixturesJson?.let { json -> parse { scheduleAdapter.fromJson(json) } }
        val diskKey = "team_v${DISK_FORMAT}_$key"

        val fromNetwork = schedule?.events != null
        // ESPN unreachable or changed: the finals (and the team line) saved last time, nothing upcoming.
        val stored = if (fromNetwork) {
            null
        } else {
            disk?.let { cache -> withContext(Dispatchers.IO) { cache.read(diskKey) } }?.let { json -> parse { scheduleAdapter.fromJson(json) } }
        }
        val events: List<EspnScheduleEventDto> = if (fromNetwork) {
            val played = schedule?.events.orEmpty()
            val kept = TeamPages.finalsToKeep(played, league)
            if (kept.isNotEmpty()) {
                val copy = EspnTeamScheduleDto(team = schedule?.team, events = kept)
                disk?.let { cache -> withContext(Dispatchers.IO) { cache.write(diskKey, scheduleAdapter.toJson(copy)) } }
            }
            played + fixtures?.events.orEmpty()
        } else {
            stored?.events.orEmpty()
        }
        val header = TeamPages.header(team, schedule?.team ?: stored?.team) ?: return TeamPageLoad.Failed
        if (!fromNetwork && events.isEmpty() && team == null) return TeamPageLoad.Failed

        val snapshots = TeamPages.snapshots(events, league)
        val upcoming = if (fromNetwork) TeamPages.upcoming(snapshots, now()) else emptyList()
        val leaders = TeamPages.leadersEvent(upcoming, league)?.let { next ->
            summaries.summary(league, next.eventId)?.let { json -> parse { summaryAdapter.fromJson(json) } }
        }.let { TeamPages.leaders(it, teamId) }
        val data = TeamPageData(header, TeamPages.recent(snapshots), upcoming, leaders)
        if (!fromNetwork) return TeamPageLoad.Loaded(data, stale = true)
        lock.withLock {
            if (memory.size >= MAX_IN_MEMORY) memory.remove(memory.keys.first())
            memory[key] = Cached(data, now())
        }
        return TeamPageLoad.Loaded(data)
    }

    private inline fun <T> parse(block: () -> T?): T? = runCatching(block).getOrNull()

    companion object {
        /** A page is requested again after this long. */
        const val TTL_MS = 10 * 60_000L

        /** The disk copy's format, part of its file name (see [GameSummaryRepository.DISK_FORMAT]). */
        internal const val DISK_FORMAT = 1
        private const val MAX_IN_MEMORY = 20
    }
}
