package com.nuvio.tv.core.sportslive.scores

import java.time.Instant
import java.time.LocalDate

/**
 * ESPN scoreboards of individual sports (#55 part 2) as Results snapshots of ESPN day [LocalDate] (US
 * Eastern, as ESPN files team games). A feed's `?dates=` lists every tournament, card or Grand Prix
 * running that day, with all its matches or sessions, so each result is kept only on its own day:
 * - tennis: one snapshot per singles match that started that day (ATP men's draws, WTA women's);
 * - UFC: one per fight of a card dated that day, main event first;
 * - F1: the race (not practice, qualifying or sprint) of that day, its podium;
 * - golf: a finished tournament whose final round was that day, its top five.
 * Each has no sides ([ScoreSnapshot.home]/[ScoreSnapshot.away] are empty) and an [IndividualResult].
 */
internal object EspnIndividualResults {
    private val SPORTS = setOf("tennis", "mma", "racing", "golf")

    /** True when [league] is an individual sport's feed, parsed here rather than as a team scoreboard. */
    fun covers(league: EspnLeague): Boolean = league.sport in SPORTS

    // A Grand Slam lists both tours' draws in each feed: each tour keeps its own singles draw.
    private val SINGLES_DRAW = mapOf("atp" to "mens-singles", "wta" to "womens-singles")

    private val NO_SIDE = ScoreTeam(null, null, null, null, null, null, null, null, emptyList())

    fun toSnapshots(dto: EspnScoreboardDto, league: EspnLeague, day: LocalDate): List<ScoreSnapshot> {
        val leagueName = dto.leagues.orEmpty()
            .firstOrNull { it.slug == league.league }
            ?.let { (it.abbreviation ?: it.name)?.takeIf(String::isNotBlank) }
        val events = dto.events.orEmpty()
        return when (league.sport) {
            "tennis" -> events.flatMap { tennis(it, league, day) }
            "mma" -> events.flatMap { fights(it, league, day) }
            "racing" -> events.flatMap { race(it, league, leagueName, day) }
            "golf" -> events.mapNotNull { golf(it, league, leagueName, day) }
            else -> emptyList()
        }
    }

    private fun tennis(event: EspnEventDto, league: EspnLeague, day: LocalDate): List<ScoreSnapshot> {
        val draw = SINGLES_DRAW[league.league] ?: return emptyList()
        val tournament = event.name?.takeIf { it.isNotBlank() }
        return event.groupings.orEmpty()
            .filter { it.grouping?.slug == draw }
            .flatMap { grouping ->
                val section = listOfNotNull(tournament, grouping.grouping?.displayName?.takeIf { it.isNotBlank() })
                    .joinToString(" · ").ifEmpty { null }
                grouping.competitions.orEmpty().mapNotNull { match ->
                    if (!onDay(match.date, day)) return@mapNotNull null
                    val players = sides(match).map { competitor ->
                        val name = competitor.athlete?.displayName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        TennisPlayer(
                            name = name,
                            winner = competitor.winner == true,
                            sets = competitor.linescores.orEmpty().mapNotNull { set ->
                                set.value?.toInt()?.let { TennisSet(it, set.tiebreak) }
                            }
                        )
                    }
                    if (players.size != 2) return@mapNotNull null
                    val statusName = match.status?.type?.name.orEmpty().uppercase()
                    val ending = when {
                        "RETIRED" in statusName -> TennisEnding.RETIRED
                        "WALKOVER" in statusName -> TennisEnding.WALKOVER
                        else -> TennisEnding.COMPLETED
                    }
                    snapshot(
                        sport = "tennis",
                        id = match.id,
                        league = league,
                        leagueName = section,
                        competitionId = event.id?.let { "$it/${grouping.grouping?.slug}" },
                        date = match.date,
                        status = match.status,
                        result = TennisMatch(match.round?.displayName?.takeIf { it.isNotBlank() }, players, ending)
                    )
                }
            }
    }

    private fun fights(event: EspnEventDto, league: EspnLeague, day: LocalDate): List<ScoreSnapshot> {
        // The whole card on its own day, so a card running past midnight is not split.
        if (!onDay(event.date, day)) return emptyList()
        val card = event.name?.takeIf { it.isNotBlank() }
        val bouts = event.competitions.orEmpty()
        return bouts.mapIndexedNotNull { index, fight ->
            val fighters = sides(fight).map { competitor ->
                val name = competitor.athlete?.displayName?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                Fighter(name, winner = competitor.winner == true)
            }
            if (fighters.size != 2) return@mapIndexedNotNull null
            val status = fight.status
            snapshot(
                sport = "mma",
                id = fight.id,
                league = league,
                leagueName = card,
                competitionId = event.id,
                date = fight.date ?: event.date,
                status = status,
                result = Fight(
                    title = fight.type?.abbreviation?.takeIf { it.isNotBlank() },
                    fighters = fighters,
                    method = method(fight.details.orEmpty()),
                    round = status?.period?.takeIf { it > 0 },
                    clock = status?.displayClock?.trim()?.takeIf { it.isNotEmpty() && it != "-" },
                    // ESPN lists prelims first: the main event, listed last, comes first.
                    bout = bouts.size - 1 - index
                )
            )
        }
    }

    private const val PODIUM = 3

    private fun race(event: EspnEventDto, league: EspnLeague, leagueName: String?, day: LocalDate): List<ScoreSnapshot> =
        event.competitions.orEmpty()
            .filter { it.type?.abbreviation.equals("Race", ignoreCase = true) && onDay(it.date, day) }
            .mapNotNull { race ->
                val podium = race.competitors.orEmpty()
                    .filter { (it.order ?: 0) in 1..PODIUM }
                    .sortedBy { it.order }
                    .distinctBy { it.order }
                    .map { driver ->
                        val name = driver.athlete?.displayName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        RaceFinisher(driver.order!!, name, driver.athlete?.shortName?.takeIf { it.isNotBlank() })
                    }
                // A podium missing a place is not shown (a wrong result is worse than none).
                if (podium.map { it.position } != (1..PODIUM).toList()) return@mapNotNull null
                snapshot("racing", race.id, league, leagueName, null, race.date, race.status, RaceResult(event.name?.takeIf { it.isNotBlank() }, podium))
            }

    private const val GOLF_TOP = 5

    private fun golf(event: EspnEventDto, league: EspnLeague, leagueName: String?, day: LocalDate): ScoreSnapshot? {
        // endDate is the final round's day (a Monday finish included), at US Eastern midnight.
        if (!onDay(event.endDate, day)) return null
        val competition = event.competitions?.firstOrNull() ?: return null
        val field = competition.competitors.orEmpty()
            .filter { it.order != null && !it.athlete?.displayName.isNullOrBlank() && !it.score.isNullOrBlank() }
            .sortedBy { it.order }
        if (field.isEmpty()) return null
        val leaders = field.take(GOLF_TOP).mapIndexed { index, player ->
            val score = player.score!!.trim()
            // The winner is first, alone; a playoff loser on the same score is 2nd. Others share a place on equal scores.
            val sharing = if (index == 0) emptyList() else field.drop(1).withIndex().filter { it.value.score!!.trim() == score }
            GolfLeader(
                position = if (index == 0) 1 else sharing.first().index + 2,
                tied = sharing.size > 1,
                name = player.athlete!!.displayName!!,
                toPar = score
            )
        }
        return snapshot("golf", event.id, league, leagueName, null, event.date, competition.status ?: event.status, GolfResult(event.name?.takeIf { it.isNotBlank() }, leaders))
    }

    /** The two sides of a match or fight in ESPN's listing order (`order`, which never gives the result). */
    private fun sides(competition: EspnCompetitionDto): List<EspnCompetitorDto> {
        val competitors = competition.competitors.orEmpty()
        return if (competitors.size == 2) competitors.sortedBy { it.order ?: Int.MAX_VALUE } else emptyList()
    }

    private val WINNER_DETAIL = Regex("""unofficial winner\s+(\w+)""", RegexOption.IGNORE_CASE)

    /** "Unofficial Winner Kotko" → KO/TKO, "... Submission", "... Decision"; null when ESPN names none. */
    internal fun method(details: List<EspnDetailDto>): FightMethod? {
        val word = details.firstNotNullOfOrNull { detail -> detail.type?.text?.let { WINNER_DETAIL.find(it)?.groupValues?.get(1) } }
            ?.lowercase() ?: return null
        return when {
            word.startsWith("ko") || word.startsWith("tko") -> FightMethod.KO_TKO
            word.startsWith("sub") -> FightMethod.SUBMISSION
            word.startsWith("dec") -> FightMethod.DECISION
            else -> null
        }
    }

    private fun snapshot(
        sport: String,
        id: String?,
        league: EspnLeague,
        leagueName: String?,
        competitionId: String?,
        date: String?,
        status: EspnStatusDto?,
        result: IndividualResult
    ): ScoreSnapshot? {
        val eventId = id?.takeIf { it.isNotBlank() } ?: return null
        val type = status?.type
        val state = EspnScoreProvider.stateOf(type)
        return ScoreSnapshot(
            // Prefixed: a tennis match id never meets a team game's id in one Results day.
            eventId = "$sport:$eventId",
            league = league,
            leagueName = leagueName,
            competitionId = competitionId,
            startMs = date?.let(EspnScoreProvider::parseDate),
            state = state,
            clockText = type?.shortDetail?.takeIf { it.isNotBlank() },
            isFinal = state != ScoreState.OFF && type?.completed == true,
            home = NO_SIDE,
            away = NO_SIDE,
            individual = result
        )
    }

    /** True when [date] (ESPN's ISO time) falls on ESPN day [day] (US Eastern). */
    private fun onDay(date: String?, day: LocalDate): Boolean =
        date?.let(EspnScoreProvider::parseDate)
            ?.let { Instant.ofEpochMilli(it).atZone(SportsResults.EASTERN).toLocalDate() == day } == true
}
