package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.domain.model.FollowedTeam
import com.squareup.moshi.Moshi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Results' "‹Team› didn't play · next: Sun 12:00 PM" (results redesign): a followed team's next game, from ESPN's team
 * schedule (one request per team, kept [TTL_MS]; a failure is not kept). Only for a team followed from ESPN (its id
 * and its own league; never `soccer/all`, whose team schedule ESPN does not serve): a wrong next game is worse than
 * none. Never throws.
 */
@Singleton
class ResultsNextGames internal constructor(
    private val source: TeamPageSource,
    moshi: Moshi,
    private val now: () -> Long
) {
    @Inject
    constructor(source: TeamPageSource, moshi: Moshi) : this(source, moshi, System::currentTimeMillis)

    private class Cached(val startMs: Long?, val fetchedAt: Long)

    private val adapter = EspnJson.guarded(moshi).adapter(EspnTeamScheduleDto::class.java)
    private val lock = Mutex()
    private val memory = HashMap<String, Cached>()

    /** The start of [team]'s next game not started yet (a timed one), or null when not known. */
    suspend fun nextStartMs(team: FollowedTeam): Long? {
        val league = leagueOf(team) ?: return null
        val id = team.espnId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val key = "${league.path}/$id"
        lock.withLock { memory[key]?.takeIf { now() - it.fetchedAt < TTL_MS }?.let { return it.startMs } }
        val json = source.teamSchedule(league, id) ?: return null
        val schedule = runCatching { adapter.fromJson(json) }.getOrNull() ?: return null
        val next = TeamPages.upcoming(TeamPages.snapshots(schedule.events.orEmpty(), league), now())
            .firstOrNull { it.state == ScoreState.PRE && it.timeValid }?.startMs
        lock.withLock {
            if (memory.size >= MAX_KEPT) memory.clear()
            memory[key] = Cached(next, now())
        }
        return next
    }

    companion object {
        const val TTL_MS = 30 * 60_000L
        private const val MAX_KEPT = 64

        /** The ESPN league [team] was followed in ("basketball/nba"); null for soccer (club ids span competitions) or none. */
        internal fun leagueOf(team: FollowedTeam): EspnLeague? {
            val parts = team.league?.split('/') ?: return null
            if (parts.size != 2 || parts.any { it.isBlank() } || parts[0] == "soccer") return null
            return EspnLeague(parts[0], parts[1])
        }
    }
}
