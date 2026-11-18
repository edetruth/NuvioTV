package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.domain.model.SportsLiveItem
import java.time.Instant

/**
 * What opens a Team page (#62): the team's ESPN feed ([league], where its team page lives) and id, and
 * the game and [side] it was opened from. Follow / Following uses that game's side, exactly as the Game
 * page and Results do ([TeamFollows]).
 */
data class TeamRef(val league: EspnLeague, val teamId: String, val event: ScoreSnapshot, val side: ScoreSide) {
    val team: ScoreTeam get() = if (side == ScoreSide.HOME) event.home else event.away

    /** ESPN's full name ("Philadelphia 76ers"), else the shortest name it has. */
    val name: String get() = listOf(team.displayName, team.shortDisplayName, team.name, team.abbreviation)
        .firstOrNull { !it.isNullOrBlank() }.orEmpty()
}

/** The Team page header: everything may be missing. [streak] > 0 is wins in a row, < 0 losses. */
data class TeamHeader(
    val name: String,
    val abbreviation: String?,
    val logo: String?,
    val record: String?,
    val standing: String?,
    val streak: Int?,
    val homeRecord: String?
)

/** A season leader: "Points" · "T. Maxey" · "G" · "26.5". */
data class TeamLeader(val category: String, val player: String, val position: String?, val value: String)

enum class TeamOutcome { WIN, LOSS, DRAW }

/** A final from the team's side: its score first. */
data class TeamResult(val outcome: TeamOutcome, val teamScore: Int, val opponentScore: Int)

data class TeamPageData(
    val header: TeamHeader,
    /** The last finals, newest first, at most [TeamPages.RECENT]. */
    val recent: List<ScoreSnapshot>,
    /** A live game first, then the next games by start, at most [TeamPages.UPCOMING]. */
    val upcoming: List<ScoreSnapshot>,
    /** Season leaders, at most [TeamPages.LEADERS]; empty when ESPN gives none (soccer, no game ahead). */
    val leaders: List<TeamLeader>
)

/**
 * The Team page's pure rules (#62): which games offer it, what it shows from ESPN's team and schedule,
 * and what the Scores setting hides (decision 5).
 */
object TeamPages {
    const val RECENT = 5
    const val UPCOMING = 4
    const val LEADERS = 3

    /** A game that has not started this long after its start is stale (ESPN never updated it): left out. */
    private const val STALE_PRE_MS = 6 * 60 * 60_000L

    /** The pro leagues with team pages, and their short names; soccer by its own competition below. */
    private val PRO = mapOf(
        EspnLeagueMap.NBA to "NBA",
        EspnLeagueMap.NFL to "NFL",
        EspnLeagueMap.NHL to "NHL",
        EspnLeagueMap.MLB to "MLB"
    )

    /** The major soccer leagues ESPN has team pages for (a club's domestic league; cups have none of their own). */
    val SOCCER_LEAGUES = setOf("eng.1", "esp.1", "ita.1", "ger.1", "fra.1", "usa.1")

    private const val SOCCER = "soccer"

    /**
     * The feed of [event]'s team pages: NBA, NFL, NHL, MLB, or a major soccer league by the event's own
     * league slug (a `soccer/all` event too). Null for college, women's, cups, internationals and
     * individual sports: no Team page.
     */
    fun leagueOf(event: ScoreSnapshot): EspnLeague? {
        if (event.individual != null) return null
        if (event.league in PRO) return event.league
        if (event.league.sport == SOCCER && event.leagueSlug in SOCCER_LEAGUES) return EspnLeague(SOCCER, event.leagueSlug)
        return null
    }

    /** The Team page of [side] of [event], or null: not a pro league, or ESPN gave no team id. */
    fun refOf(event: ScoreSnapshot, side: ScoreSide): TeamRef? {
        val league = leagueOf(event) ?: return null
        val team = if (side == ScoreSide.HOME) event.home else event.away
        val id = team.id?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return TeamRef(league, id, event, side)
    }

    /** Both sides' Team pages in the order shown ([homeFirst]: the card names the home team first); only those offered. */
    fun refsOf(event: ScoreSnapshot, homeFirst: Boolean): List<TeamRef> {
        val sides = if (homeFirst) listOf(ScoreSide.HOME, ScoreSide.AWAY) else listOf(ScoreSide.AWAY, ScoreSide.HOME)
        return sides.mapNotNull { refOf(event, it) }
    }

    private fun leagueName(league: EspnLeague, event: EspnScheduleEventDto?): String? =
        PRO[league] ?: event?.league?.abbreviation?.takeIf { it.isNotBlank() } ?: event?.league?.name?.takeIf { it.isNotBlank() }

    /** The header from the team endpoint, else from the schedule's team; null when neither names the team. */
    fun header(team: EspnTeamInfoDto?, scheduleTeam: EspnTeamInfoDto?): TeamHeader? {
        val name = listOf(team?.displayName, scheduleTeam?.displayName, team?.shortDisplayName, team?.name, team?.abbreviation)
            .firstOrNull { !it.isNullOrBlank() }?.trim() ?: return null
        val items = team?.record?.items.orEmpty()
        val total = items.firstOrNull { it.type == "total" }
        return TeamHeader(
            name = name,
            abbreviation = (team?.abbreviation ?: scheduleTeam?.abbreviation)?.takeIf { it.isNotBlank() },
            logo = logoOf(team) ?: logoOf(scheduleTeam),
            record = (total?.summary ?: scheduleTeam?.recordSummary)?.takeIf { it.isNotBlank() },
            standing = (team?.standingSummary ?: scheduleTeam?.standingSummary)?.takeIf { it.isNotBlank() },
            streak = total?.stats.orEmpty().firstOrNull { it.name == "streak" }?.value?.toInt()?.takeIf { it != 0 },
            homeRecord = items.firstOrNull { it.type == "home" }?.summary?.takeIf { it.isNotBlank() }
        )
    }

    private fun logoOf(team: EspnTeamInfoDto?): String? =
        team?.logo?.takeIf { it.isNotBlank() } ?: team?.logos.orEmpty().firstNotNullOfOrNull { it.href?.takeIf(String::isNotBlank) }

    /**
     * [events] as score events on [league]'s feed (so the Game page loads their summaries there). An event
     * without an id or two named sides is skipped. Scores show only once a game is in play or over.
     */
    fun snapshots(events: List<EspnScheduleEventDto>, league: EspnLeague): List<ScoreSnapshot> =
        events.mapNotNull { snapshot(it, league) }.distinctBy { it.eventId }

    internal fun snapshot(event: EspnScheduleEventDto, league: EspnLeague): ScoreSnapshot? {
        val id = event.id?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val competition = event.competitions?.firstOrNull() ?: return null
        val competitors = competition.competitors.orEmpty()
        val home = competitors.firstOrNull { it.homeAway == "home" }?.let(::team) ?: return null
        val away = competitors.firstOrNull { it.homeAway == "away" }?.let(::team) ?: return null
        val type = competition.status?.type
        val state = EspnScoreProvider.stateOf(type)
        val scored = state == ScoreState.IN || state == ScoreState.POST
        return ScoreSnapshot(
            eventId = id,
            league = league,
            leagueSlug = event.league?.slug?.takeIf { it.isNotBlank() } ?: league.league,
            leagueName = leagueName(league, event),
            startMs = (competition.date ?: event.date)?.let(EspnScoreProvider::parseDate),
            timeValid = (competition.timeValid ?: event.timeValid) != false,
            state = state,
            clockText = type?.shortDetail?.takeIf { it.isNotBlank() },
            isFinal = state != ScoreState.OFF && type?.completed == true,
            home = if (scored) home else home.copy(score = null),
            away = if (scored) away else away.copy(score = null)
        )
    }

    private fun team(competitor: EspnScheduleCompetitorDto): ScoreTeam? {
        val team = competitor.team ?: return null
        if (listOf(team.abbreviation, team.displayName, team.shortDisplayName, team.name, team.location).all { it.isNullOrBlank() }) return null
        return ScoreTeam(
            id = team.id?.takeIf { it.isNotBlank() },
            abbreviation = team.abbreviation?.takeIf { it.isNotBlank() },
            displayName = team.displayName?.takeIf { it.isNotBlank() },
            shortDisplayName = team.shortDisplayName?.takeIf { it.isNotBlank() },
            name = team.name?.takeIf { it.isNotBlank() },
            location = team.location?.takeIf { it.isNotBlank() },
            logo = logoOf(team),
            score = scoreOf(competitor.score),
            linescores = emptyList()
        )
    }

    /** `{"value": 131.0, "displayValue": "131"}`, "131" or 131. */
    internal fun scoreOf(score: Any?): Int? = when (score) {
        null -> null
        is Number -> score.toInt()
        is String -> score.trim().toIntOrNull()
        is Map<*, *> -> (score["value"] as? Number)?.toInt() ?: (score["displayValue"] as? String)?.trim()?.toIntOrNull()
        else -> null
    }

    /** The last [RECENT] finals with a score, newest first. */
    fun recent(events: List<ScoreSnapshot>): List<ScoreSnapshot> =
        events.filter { it.state == ScoreState.POST && it.isFinal && it.homeScore != null && it.awayScore != null && it.startMs != null }
            .sortedByDescending { it.startMs }
            .take(RECENT)

    /**
     * A live game first, then the games not started yet by start; at most [UPCOMING]. A scheduled game
     * more than 6 h past its start (ESPN never moved it on) and a postponed one are left out.
     */
    fun upcoming(events: List<ScoreSnapshot>, nowMs: Long): List<ScoreSnapshot> {
        val live = events.filter { it.state == ScoreState.IN }.sortedBy { it.startMs ?: Long.MAX_VALUE }
        val next = events.filter { it.state == ScoreState.PRE && (it.startMs ?: return@filter false) > nowMs - STALE_PRE_MS }
            .sortedBy { it.startMs }
        return (live + next).take(UPCOMING)
    }

    /** The finals worth keeping on disk: the latest [RECENT] * 2, as ESPN sent them. */
    internal fun finalsToKeep(events: List<EspnScheduleEventDto>, league: EspnLeague): List<EspnScheduleEventDto> {
        val keep = recentIds(events, league, RECENT * 2)
        return events.filter { it.id?.trim() in keep }
    }

    private fun recentIds(events: List<EspnScheduleEventDto>, league: EspnLeague, count: Int): Set<String> =
        events.mapNotNull { snapshot(it, league) }
            .filter { it.state == ScoreState.POST && it.isFinal && it.startMs != null }
            .sortedByDescending { it.startMs }
            .take(count)
            .mapTo(HashSet()) { it.eventId }

    /** [event] from the side of team [teamId]: win, loss or draw, its score first; null when not its final. */
    fun resultOf(event: ScoreSnapshot, teamId: String): TeamResult? {
        if (event.state != ScoreState.POST || !event.isFinal) return null
        val home = event.home.id == teamId
        if (!home && event.away.id != teamId) return null
        val own = (if (home) event.homeScore else event.awayScore) ?: return null
        val other = (if (home) event.awayScore else event.homeScore) ?: return null
        val outcome = when {
            own > other -> TeamOutcome.WIN
            own < other -> TeamOutcome.LOSS
            else -> TeamOutcome.DRAW
        }
        return TeamResult(outcome, own, other)
    }

    /** True when team [teamId] is [event]'s home side. */
    fun isHome(event: ScoreSnapshot, teamId: String): Boolean = event.home.id == teamId

    /** The other side of [event] from team [teamId]. */
    fun opponentOf(event: ScoreSnapshot, teamId: String): ScoreTeam = if (isHome(event, teamId)) event.away else event.home

    /** The next game's summary holds the season leaders only before it starts: its id, or null. */
    fun leadersEvent(upcoming: List<ScoreSnapshot>, league: EspnLeague): ScoreSnapshot? =
        if (league.sport == SOCCER) null else upcoming.firstOrNull { it.state == ScoreState.PRE }

    /**
     * Team [teamId]'s season leaders from a summary of a game not started yet: per category its first
     * leader, at most [LEADERS]. Matched by team id only (never a name).
     */
    fun leaders(summary: EspnSummaryDto?, teamId: String): List<TeamLeader> {
        val entry = summary?.leaders.orEmpty().firstOrNull { it.team?.id == teamId } ?: return emptyList()
        return entry.leaders.orEmpty().mapNotNull { category ->
            val leader = category.leaders?.firstOrNull() ?: return@mapNotNull null
            val label = listOf(category.displayName, category.abbreviation, category.name).firstOrNull { !it.isNullOrBlank() }
                ?: return@mapNotNull null
            val player = (leader.athlete?.shortName ?: leader.athlete?.displayName)?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val value = leader.displayValue?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            TeamLeader(label.trim(), player, leader.athlete?.position?.abbreviation?.takeIf { it.isNotBlank() }, value)
        }.take(LEADERS)
    }

    /**
     * The replay each of [recent] opens, by event id: the Results rule ([SportsResults.replaysByEvent],
     * both teams by strong equality and the date), per ESPN (US Eastern) day. A wrong replay is worse than none.
     */
    fun replaysFor(recent: List<ScoreSnapshot>, replays: List<SportsLiveItem>, nowMs: Long): Map<String, SportsLiveItem> =
        recent.filter { it.startMs != null }
            .groupBy { Instant.ofEpochMilli(it.startMs!!).atZone(SportsResults.EASTERN).toLocalDate() }
            .flatMap { (day, events) -> SportsResults.replaysByEvent(events, day, replays, nowMs).entries }
            .associate { it.key to it.value }
}

/**
 * What the Scores setting lets the Team page show (decision 5). Show: everything. Hide until focused:
 * record and standings show; the last results wait for Reveal. Off: nothing is requested and nothing
 * shows until Reveal, as on the Game page.
 */
object TeamPageSpoilers {
    fun mayLoad(mode: ScoresMode, revealed: Boolean): Boolean = mode != ScoresMode.OFF || revealed

    fun showsRecord(mode: ScoresMode, revealed: Boolean): Boolean = mode != ScoresMode.OFF || revealed

    fun showsResults(mode: ScoresMode, revealed: Boolean): Boolean = mode == ScoresMode.SHOW || revealed
}
