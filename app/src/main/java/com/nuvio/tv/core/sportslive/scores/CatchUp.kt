package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.SportsLiveGameLength
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveItem
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A followed team's final with a strict replay match, for the Catch up row (#58). Never shows a score. */
data class CatchUpItem(val event: ScoreSnapshot, val replay: SportsLiveItem) {
    val key: String get() = event.eventId
}

/** One ESPN (US Eastern) day for the row: [events] all fetched (replays match against them all), [finals] those listed. */
data class CatchUpDay(val date: LocalDate, val events: List<ScoreSnapshot>, val finals: List<ScoreSnapshot>)

/**
 * Stored per profile, by ESPN event id: [seen] when a replay was watched (played 2+ min, or marked
 * watched); [surfaced] the start of each game the row has listed, so it stays until watched or too old
 * even after "since" moves on.
 */
data class CatchUpMarks(val seen: Map<String, Long> = emptyMap(), val surfaced: Map<String, Long> = emptyMap())

/**
 * The pure rules of the Catch up row (#58): followed teams' finals that ended since the user was last
 * here, up to [WINDOW_MS] back, and only those with a strict replay match ([SportsResults.replaysByEvent],
 * the Results Replay pill). A wrong replay is worse than none, so a game with no start time, an
 * individual sport, or no strict match is never listed.
 */
object CatchUp {
    private const val MINUTE_MS = 60_000L

    /** How far back the row looks, and how long an item stays: 3 days from its start. */
    const val WINDOW_MS = 3 * 24 * 60 * MINUTE_MS

    /** A replay played this long counts as watched. */
    const val WATCHED_AFTER_MS = 2 * MINUTE_MS

    /**
     * When [event] is taken to have ended: ESPN gives no end time, so its start plus its sport's length
     * with the late-start allowance ([SportsLiveGameLength.ofEspnSport], the #60 table).
     */
    fun estimatedEndMs(event: ScoreSnapshot, startMs: Long): Long = startMs + SportsLiveGameLength.ofEspnSport(event.league.sport)

    /** Opening again within this of the last visit is the same visit: "since" stays. */
    const val VISIT_GAP_MS = 30 * MINUTE_MS

    /**
     * The row's "since" when Home or Sports & Live opens at [nowMs]: the last visit ([lastOpenedMs]) when
     * it was over [VISIT_GAP_MS] ago, else the one kept ([keptSinceMs]). Never opened: [WINDOW_MS] back.
     */
    fun since(lastOpenedMs: Long?, keptSinceMs: Long?, nowMs: Long): Long = when {
        lastOpenedMs == null || lastOpenedMs > nowMs -> nowMs - WINDOW_MS
        nowMs - lastOpenedMs >= VISIT_GAP_MS -> lastOpenedMs
        else -> keptSinceMs ?: lastOpenedMs
    }

    /**
     * The feeds followed teams' games are on: each team's sport routed as [MySports.withFollowed] does
     * (the same feeds Results fetches for them, so the same caches). A team with no routed sport has none.
     */
    fun feeds(followed: List<FollowedTeam>): List<EspnLeague> =
        followed.flatMap { team -> team.sport?.let { EspnLeagueMap.leaguesFor(sport = it, hints = emptyList()) }.orEmpty() }
            .distinct()

    /**
     * The Results days to load (0 = today): back to the earliest of "since" (less the longest game) and the
     * surfaced games, never past [WINDOW_MS]. [easternDate] is the ESPN date of a moment.
     */
    fun daysAgo(sinceMs: Long, marks: CatchUpMarks, nowMs: Long, easternDate: (Long) -> LocalDate): IntRange {
        val windowStart = nowMs - WINDOW_MS
        val earliest = (listOf(sinceMs - SportsLiveGameLength.LONGEST_TEAM_GAME_MS) + marks.surfaced.values).min()
            .coerceIn(windowStart, nowMs)
        val days = ChronoUnit.DAYS.between(easternDate(earliest), easternDate(nowMs)).toInt()
        return 0..days.coerceIn(0, SportsResults.DAYS_AGO.last)
    }

    /**
     * The row, newest first: [days]' finals a followed team played in ([SportsResults.follows]), started
     * within [WINDOW_MS], that ended since [sinceMs] (or were listed before: [CatchUpMarks.surfaced]), not
     * watched ([CatchUpMarks.seen]), and that have a strict replay match among [replays].
     */
    fun select(
        days: List<CatchUpDay>,
        replays: List<SportsLiveItem>,
        followed: List<FollowedTeam>,
        sinceMs: Long,
        marks: CatchUpMarks,
        nowMs: Long
    ): List<CatchUpItem> {
        if (followed.isEmpty() || replays.isEmpty()) return emptyList()
        val items = ArrayList<CatchUpItem>()
        for (day in days) {
            val candidates = SportsResults.rows(day.finals, followed).filter { row ->
                val event = row.event
                val start = event.startMs
                row.followed && event.individual == null && start != null && event.timeValid &&
                    start <= nowMs && nowMs - start < WINDOW_MS &&
                    event.eventId !in marks.seen &&
                    (estimatedEndMs(event, start) > sinceMs || event.eventId in marks.surfaced)
            }
            if (candidates.isEmpty()) continue
            val matched = SportsResults.replaysByEvent(day.events, day.date, replays, nowMs)
            candidates.forEach { row -> matched[row.event.eventId]?.let { items += CatchUpItem(row.event, it) } }
        }
        return items.distinctBy { it.key }.sortedByDescending { it.event.startMs }
    }

    /** [marks] without the games older than [WINDOW_MS] (and their watched marks): they can never show again. */
    fun pruned(marks: CatchUpMarks, nowMs: Long): CatchUpMarks {
        val oldest = nowMs - WINDOW_MS - SportsLiveGameLength.LONGEST_TEAM_GAME_MS
        return CatchUpMarks(seen = marks.seen.filterValues { it >= oldest }, surfaced = marks.surfaced.filterValues { it >= oldest })
    }

    /** The ESPN (US Eastern) date of [ms]. */
    fun easternDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(SportsResults.EASTERN).toLocalDate()
}
