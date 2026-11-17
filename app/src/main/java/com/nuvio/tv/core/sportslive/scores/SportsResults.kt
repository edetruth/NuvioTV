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

/** The feeds a Results day fetches, and the My sports (with followed teams' sports) they come from. */
data class RelevantLeagues(val leagues: List<EspnLeague>, val mySports: MySports) {
    val isEmpty: Boolean get() = leagues.isEmpty()
}

/** A league section of a Results day: its rows under the competition's [name] (null: none given). */
data class ResultGroup(val key: String, val sport: String, val name: String?, val rows: List<ResultRow>)

/**
 * The pure rules of the Results view: which leagues are relevant, which events are listed and in what
 * order, and which replay a result opens.
 */
object SportsResults {
    /** The past days Results offers: 1 (yesterday) to 7. */
    val DAYS_AGO = 1..7

    /**
     * The feeds of [mySports] with followed teams' sports added ([MySports.withFollowed]): one
     * `soccer/all` (every soccer competition) and each non-soccer league in effect.
     */
    fun relevantLeagues(mySports: MySports, followed: List<FollowedTeam>): RelevantLeagues {
        val effective = mySports.withFollowed(followed)
        return RelevantLeagues(effective.feeds().distinct(), effective)
    }

    /**
     * The rows for one day: finished games only (completed `post`; postponed, cancelled, live and
     * upcoming games are left out), each event once (a competition feed's copy before `soccer/all`'s);
     * followed teams' games first, then by league and start.
     */
    fun rows(events: List<ScoreSnapshot>, followed: List<FollowedTeam>): List<ResultRow> =
        events.asSequence()
            .filter { it.state == ScoreState.POST && it.isFinal }
            .sortedBy { if (it.league == EspnLeagueMap.SOCCER_ALL) 1 else 0 }
            .distinctBy { it.eventId }
            .map { ResultRow(it, followed = followed.any { team -> follows(team, it) }, note = finalNote(it)) }
            .sortedWith(
                compareByDescending<ResultRow> { it.followed }
                    .thenBy { it.event.leagueSlug }
                    .thenBy { it.event.startMs ?: Long.MAX_VALUE }
                    .thenBy { it.event.eventId }
            )
            .toList()

    /**
     * True when [team] played in [event]: its name is either side by strong equality with the same
     * (W)/U21 qualifier ([ScoreMatcher.isStrongSide]; never a city or suffix match; the competition
     * name stands in for an unknown slug), and the event is in one of the team's sports
     * ([sportsOf]). A followed soccer "Rangers" never stars the NHL or MLB Rangers. A team followed from
     * ESPN matches by its ESPN id ([TeamFollows.followsSide]).
     */
    fun follows(team: FollowedTeam, event: ScoreSnapshot): Boolean =
        TeamFollows.followsSide(team, event, event.home) || TeamFollows.followsSide(team, event, event.away)

    /**
     * The ESPN sports [team] can play: those of the feeds its sport routes to. A team routed only to
     * `soccer/all` plays soccer, or soccer and American football when its sport says "Football" (an
     * addon's "Football" may be either). Null when its sport routes nowhere (no sport given).
     */
    internal fun sportsOf(team: FollowedTeam): Set<String>? = team.sport?.let(EspnLeagueMap::espnSportsOf)

    /**
     * A row's league section: the ESPN league id (one per competition, so men's, women's and youth
     * competitions never share one), else its league slug.
     */
    fun groupKey(event: ScoreSnapshot): String = "${event.league.sport}:${event.competitionId ?: event.leagueSlug}"

    /**
     * [rows] in league sections: by sport ([MySport] order), then the picker's league order (NBA before
     * college, internationals and the big soccer leagues before the rest), then name. Rows keep their order.
     */
    fun groups(rows: List<ResultRow>): List<ResultGroup> =
        rows.groupBy { groupKey(it.event) }
            .map { (key, groupRows) ->
                val first = groupRows.first().event
                val group = ResultGroup(key, first.league.sport, groupRows.firstNotNullOfOrNull { it.event.leagueName }, groupRows)
                // Sort keys computed once per section, not in the comparator.
                val sportRank = MySport.ofEspn(group.sport)?.ordinal ?: Int.MAX_VALUE
                val leagueRank = MyLeague.entries.indexOfFirst { it.covers(first) }.let { if (it < 0) Int.MAX_VALUE else it }
                GroupSort(group, sportRank, leagueRank, group.name?.lowercase() ?: "\uffff")
            }
            .sortedWith(compareBy<GroupSort>({ it.sportRank }, { it.leagueRank }, { it.name }, { it.group.key }))
            .map { it.group }

    private class GroupSort(val group: ResultGroup, val sportRank: Int, val leagueRank: Int, val name: String)

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
