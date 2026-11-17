package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveItem
import java.time.ZoneId

/** How a final ended, for the Results row's note: full time, overtime, shootout, extra time, penalties. */
enum class FinalNote { FT, OT, SO, AET, PENS }

/** One finished game on the Results view; [followed] when a followed team played in it. */
data class ResultRow(val event: ScoreSnapshot, val followed: Boolean, val note: FinalNote)

/** The leagues Results fetches, and those whose games are listed only when a followed team played. */
data class RelevantLeagues(val leagues: List<EspnLeague>, val followedOnly: Set<EspnLeague>) {
    val isEmpty: Boolean get() = leagues.isEmpty()
}

/**
 * The pure rules of the Results view: which leagues are relevant, which events are listed and in what
 * order, and which replay a result opens.
 */
object SportsResults {
    /** Leagues seen on cards are relevant for 7 days after they were last seen. */
    const val RECENT_TTL_MS = 7 * 24 * 60 * 60 * 1000L

    /** At most this many recent leagues are kept (the most recently seen), bounding Results' requests. */
    const val MAX_RECENT = 12

    /** A league seen again is re-stamped at most this often, so a load every minute does not write prefs. */
    const val RESTAMP_MS = 6 * 60 * 60 * 1000L

    /** The past days Results offers: 1 (yesterday) to 7. */
    val DAYS_AGO = 1..7

    /**
     * [current] (league path → last seen ms) with [seen] stamped at [nowMs], expired entries dropped and
     * the most recent [MAX_RECENT] kept; null when nothing changed enough to be worth saving.
     * `soccer/all` is never recorded: it names every soccer game in the world, not a league.
     */
    fun recordSeen(current: Map<String, Long>, seen: Collection<EspnLeague>, nowMs: Long): Map<String, Long>? {
        val live = current.filterValues { nowMs - it <= RECENT_TTL_MS }
        val next = live.toMutableMap()
        var changed = live.size != current.size
        for (league in seen) {
            if (league == EspnLeagueMap.SOCCER_ALL) continue
            val last = next[league.path]
            if (last == null || nowMs - last >= RESTAMP_MS) {
                next[league.path] = nowMs
                changed = true
            }
        }
        val capped = next.entries.sortedByDescending { it.value }.take(MAX_RECENT).associate { it.key to it.value }
        if (capped.size != next.size) changed = true
        return if (changed) capped else null
    }

    /** The leagues [items] route to (their first source with a feed), for [recordSeen]. */
    fun leaguesOf(items: List<SportsLiveItem>): Set<EspnLeague> =
        items.asSequence()
            .filter { !it.isChannel }
            .mapNotNull { item ->
                (listOf(item) + item.alternates).asSequence().map(EspnLeagueMap::leaguesFor).firstOrNull { it.isNotEmpty() }
            }
            .flatten()
            .toSet()

    /**
     * Followed teams' leagues (by their sport, through [EspnLeagueMap]) and leagues seen on cards in the
     * last 7 days. A followed soccer team maps to `soccer/all`, whose games are listed only when a
     * followed team played ([RelevantLeagues.followedOnly]).
     */
    fun relevantLeagues(followed: List<FollowedTeam>, recent: Map<String, Long>, nowMs: Long): RelevantLeagues {
        val fromRecent = recent.entries
            .filter { nowMs - it.value <= RECENT_TTL_MS }
            .sortedByDescending { it.value }
            .mapNotNull { leagueOf(it.key) }
        val fromFollowed = followed.flatMap(::leaguesOfTeam)
        val leagues = (fromRecent + fromFollowed).distinct()
        return RelevantLeagues(leagues, followedOnly = leagues.filter { it == EspnLeagueMap.SOCCER_ALL }.toSet())
    }

    private fun leaguesOfTeam(team: FollowedTeam): List<EspnLeague> =
        team.sport?.let { EspnLeagueMap.leaguesFor(sport = it, hints = emptyList()) }.orEmpty()

    /** "basketball/nba" → that league; null for anything else. */
    fun leagueOf(path: String): EspnLeague? {
        val slash = path.indexOf('/')
        if (slash <= 0 || slash == path.lastIndex) return null
        return EspnLeague(path.substring(0, slash), path.substring(slash + 1))
    }

    /**
     * The rows for one day: finished games only (completed `post`; postponed, cancelled, live and
     * upcoming games are left out), each event once (a competition feed's copy before `soccer/all`'s),
     * games of [followedOnly] leagues only when a followed team played; followed teams' games first,
     * then by league and start.
     */
    fun rows(events: List<ScoreSnapshot>, followed: List<FollowedTeam>, followedOnly: Set<EspnLeague> = emptySet()): List<ResultRow> =
        events.asSequence()
            .filter { it.state == ScoreState.POST && it.isFinal }
            .sortedBy { if (it.league == EspnLeagueMap.SOCCER_ALL) 1 else 0 }
            .distinctBy { it.eventId }
            .map { ResultRow(it, followed = followed.any { team -> follows(team, it) }, note = finalNote(it)) }
            .filter { it.followed || it.event.league !in followedOnly }
            .sortedWith(
                compareByDescending<ResultRow> { it.followed }
                    .thenBy { it.event.leagueSlug }
                    .thenBy { it.event.startMs ?: Long.MAX_VALUE }
                    .thenBy { it.event.eventId }
            )
            .toList()

    /**
     * True when [team] played in [event]: its name matches either side ([SportsLiveTeams.matches], as on
     * cards) and, when its sport routes to a specific ESPN feed, the event is in that sport. "Football"
     * routes to `soccer/all` but may be American football on an addon, so it does not rule a sport out.
     */
    fun follows(team: FollowedTeam, event: ScoreSnapshot): Boolean {
        val sports = leaguesOfTeam(team).filter { it != EspnLeagueMap.SOCCER_ALL }.mapTo(HashSet()) { it.sport }
        if (sports.isNotEmpty() && event.league.sport !in sports) return false
        return listOf(event.home, event.away).any { side ->
            listOfNotNull(side.displayName, side.shortDisplayName).any { SportsLiveTeams.matches(team.name, it) }
        }
    }

    private val OVERTIME = Regex("""\b\d*OT\b""", RegexOption.IGNORE_CASE)
    private val SHOOTOUT = Regex("""\bSO\b""", RegexOption.IGNORE_CASE)

    /** From ESPN's final `shortDetail`: "FT", "AET", "FT-Pens", "Final/OT", "Final/2OT", "Final/SO". */
    fun finalNote(event: ScoreSnapshot): FinalNote {
        val detail = event.clockText.orEmpty()
        return when {
            detail.contains("pen", ignoreCase = true) -> FinalNote.PENS
            detail.contains("aet", ignoreCase = true) -> FinalNote.AET
            SHOOTOUT.containsMatchIn(detail) -> FinalNote.SO
            OVERTIME.containsMatchIn(detail) -> FinalNote.OT
            else -> FinalNote.FT
        }
    }

    /**
     * The replay each result opens, by event id: a replay whose teams AND date match the result through
     * the strict [ScoreMatcher] (both sides, ±3 h or the same day, a tie between two events gives none),
     * matched against all of [events] so a replay of another game never lands on this one. Replays with
     * no date, channels and team folders (no "A vs B" name) never match. A wrong replay is worse than none.
     */
    fun replaysByEvent(events: List<ScoreSnapshot>, replays: List<SportsLiveItem>, zone: ZoneId, nowMs: Long): Map<String, SportsLiveItem> {
        if (events.isEmpty() || replays.isEmpty()) return emptyMap()
        val matcher = ScoreMatcher { zone }
        val found = LinkedHashMap<String, SportsLiveItem>()
        for (replay in replays) {
            if (replay.isChannel || replay.startMs == null) continue
            val card = ScoreCard.from(replay) ?: continue
            val event = matcher.match(card, events, nowMs) ?: continue
            found.putIfAbsent(event.eventId, replay)
        }
        return found
    }
}
