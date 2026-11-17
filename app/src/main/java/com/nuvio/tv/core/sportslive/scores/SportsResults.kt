package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

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
     * [current] (league path → last seen ms) with [seen] stamped at [nowMs]; null when there is nothing
     * to save. Leagues already stored keep their slot and are re-stamped at most every [RESTAMP_MS] (so
     * a league watched daily never expires); expired leagues free their slot; new leagues fill free
     * slots only, in path order, up to [MAX_RECENT]. A full set is never reshuffled by what is on screen.
     * `soccer/all` is never recorded: it names every soccer game in the world, not a league.
     */
    fun recordSeen(current: Map<String, Long>, seen: Collection<EspnLeague>, nowMs: Long): Map<String, Long>? {
        val next = current.filterValues { nowMs - it <= RECENT_TTL_MS }.toMutableMap()
        var changed = next.size != current.size
        val paths = seen.asSequence().filter { it != EspnLeagueMap.SOCCER_ALL }.map { it.path }.distinct().sorted()
        for (path in paths) {
            val last = next[path]
            when {
                last != null -> if (nowMs - last >= RESTAMP_MS) {
                    next[path] = nowMs
                    changed = true
                }
                next.size < MAX_RECENT -> {
                    next[path] = nowMs
                    changed = true
                }
            }
        }
        return if (changed) next else null
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
     * True when [team] played in [event]: its name is either side by strong equality with the same
     * (W)/U21 qualifier ([ScoreMatcher.isStrongSide]; never a city or suffix match), and, when its sport routes to a specific ESPN feed, the event is in that sport. "Football"
     * routes to `soccer/all` but may be American football on an addon, so it does not rule a sport out.
     */
    fun follows(team: FollowedTeam, event: ScoreSnapshot): Boolean {
        val sports = leaguesOfTeam(team).filter { it != EspnLeagueMap.SOCCER_ALL }.mapTo(HashSet()) { it.sport }
        if (sports.isNotEmpty() && event.league.sport !in sports) return false
        return listOf(event.home, event.away).any { side -> ScoreMatcher.isStrongSide(team.name, side, event.leagueSlug) }
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
     * The replay each result of ESPN day [date] opens, by event id: a replay whose BOTH teams match by
     * strong equality ([ScoreMatcher.BOTH_STRONG]) and whose date matches, through [ScoreMatcher] against
     * all of [events] (a tie between two events gives none). A timed replay must start within ±3 h of
     * the game; a bare-date replay must be dated [date] itself, never the day after (a back-to-back
     * series' game 2 must not open game 1). Days are ESPN's (US Eastern). Replays with no date,
     * channels and team folders never match. A wrong replay is worse than none.
     */
    fun replaysByEvent(events: List<ScoreSnapshot>, date: LocalDate, replays: List<SportsLiveItem>, nowMs: Long): Map<String, SportsLiveItem> {
        if (events.isEmpty() || replays.isEmpty()) return emptyMap()
        val matcher = ScoreMatcher(zone = { EASTERN }, minScore = ScoreMatcher.BOTH_STRONG, previousDayFallback = false)
        val found = LinkedHashMap<String, SportsLiveItem>()
        for (replay in replays) {
            val start = replay.startMs ?: continue
            if (replay.isChannel) continue
            if (!replay.startHasTimeOfDay && Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate() != date) continue
            val card = ScoreCard.from(replay) ?: continue
            val event = matcher.match(card, events, nowMs) ?: continue
            found.putIfAbsent(event.eventId, replay)
        }
        return found
    }

    /** ESPN files games under their US Eastern date. */
    val EASTERN: ZoneId = ZoneId.of("America/New_York")
}
