package com.nuvio.tv.core.sportslive.scores

import com.squareup.moshi.JsonClass

// Tolerant DTOs for ESPN's site API scoreboard: only the fields scores v1 reads, every one optional.

@JsonClass(generateAdapter = true)
data class EspnScoreboardDto(val events: List<EspnEventDto>? = null, val leagues: List<EspnLeagueDto>? = null)

/** A feed's league: `nba` / "NBA" / "National Basketball Association". Empty on `soccer/all`. */
@JsonClass(generateAdapter = true)
data class EspnLeagueDto(val slug: String? = null, val abbreviation: String? = null, val name: String? = null)

@JsonClass(generateAdapter = true)
data class EspnEventDto(
    val id: String? = null,
    /** "s:600~l:2395~e:401861185": sport, ESPN league id and event id. */
    val uid: String? = null,
    val date: String? = null,
    val status: EspnStatusDto? = null,
    val competitions: List<EspnCompetitionDto>? = null,
    val links: List<EspnLinkDto>? = null,
    /** Individual sports: the tournament, card or Grand Prix ("Nitto ATP Finals", "UFC 334: Gane vs. Hokit"). */
    val name: String? = null,
    /** Golf: the final round's day (US Eastern midnight); tennis and F1: the last day. */
    val endDate: String? = null,
    /** Tennis: the tournament's draws, each with every match of the tournament. */
    val groupings: List<EspnGroupingDto>? = null
)

/** A tennis draw ("mens-singles") and its matches. */
@JsonClass(generateAdapter = true)
data class EspnGroupingDto(val grouping: EspnGroupingInfoDto? = null, val competitions: List<EspnCompetitionDto>? = null)

@JsonClass(generateAdapter = true)
data class EspnGroupingInfoDto(val slug: String? = null, val displayName: String? = null)

@JsonClass(generateAdapter = true)
data class EspnLinkDto(val href: String? = null)

@JsonClass(generateAdapter = true)
data class EspnStatusDto(
    val type: EspnStatusTypeDto? = null,
    /** UFC: the round a fight ended in. */
    val period: Int? = null,
    /** UFC: the time in that round ("3:14"). */
    val displayClock: String? = null
)

@JsonClass(generateAdapter = true)
data class EspnStatusTypeDto(
    /** "STATUS_SCHEDULED", "STATUS_FINAL", "STATUS_POSTPONED", "STATUS_CANCELED"... */
    val name: String? = null,
    val state: String? = null,
    val completed: Boolean? = null,
    val shortDetail: String? = null
)

@JsonClass(generateAdapter = true)
data class EspnCompetitionDto(
    /** Individual sports: the match, fight or session id; its own [date] and [status]. */
    val id: String? = null,
    val date: String? = null,
    val status: EspnStatusDto? = null,
    /** UFC: the weight class ("Lightweight"); F1: the session ("FP1", "Qual", "Race"). */
    val type: EspnCompetitionTypeDto? = null,
    /** Tennis: "Group Stage", "Round 1", "Final"; qualifying: "Qualifying 1st Round". */
    val round: EspnRoundDto? = null,
    val competitors: List<EspnCompetitorDto>? = null,
    /** "UEFA Nations League, Group A2", "Men's International Friendly": the competition, then the stage. */
    val altGameNote: String? = null,
    /** False when the start time is not set yet (ESPN "TBD"); the date still is. */
    val timeValid: Boolean? = null,
    val details: List<EspnDetailDto>? = null,
    /** What is happening now in a live game (Scoreboard SB1); absent before and mostly after play. */
    val situation: EspnSituationDto? = null
)

@JsonClass(generateAdapter = true)
data class EspnCompetitorDto(
    val homeAway: String? = null,
    val score: String? = null,
    val team: EspnTeamDto? = null,
    val linescores: List<EspnLinescoreDto>? = null,
    /** Individual sports: the player, fighter or driver. */
    val athlete: EspnAthleteDto? = null,
    /** Tennis doubles: the pair. */
    val roster: EspnRosterDto? = null,
    val winner: Boolean? = null,
    /** Tennis/UFC: the listing order (not the result); F1 and golf: the finishing position. */
    val order: Int? = null
)

@JsonClass(generateAdapter = true)
data class EspnRosterDto(val displayName: String? = null, val shortDisplayName: String? = null)

@JsonClass(generateAdapter = true)
data class EspnCompetitionTypeDto(
    val abbreviation: String? = null,
    val text: String? = null,
    /** Tennis: the draw ("mens-singles"); a qualifying draw names it ("mens-singles-qualifying"). */
    val slug: String? = null
)

@JsonClass(generateAdapter = true)
data class EspnRoundDto(val displayName: String? = null)

@JsonClass(generateAdapter = true)
data class EspnTeamDto(
    val id: String? = null,
    val abbreviation: String? = null,
    val displayName: String? = null,
    val shortDisplayName: String? = null,
    val name: String? = null,
    val location: String? = null,
    val logo: String? = null
)

@JsonClass(generateAdapter = true)
data class EspnLinescoreDto(
    val value: Double? = null,
    /** Tennis: this side's tiebreak points in the set. */
    val tiebreak: Int? = null
)

@JsonClass(generateAdapter = true)
data class EspnDetailDto(
    /** UFC: "Unofficial Winner Kotko" / "... Submission" / "... Decision" gives the method. */
    val type: EspnDetailTypeDto? = null,
    val clock: EspnDetailClockDto? = null,
    val team: EspnDetailTeamDto? = null,
    val scoringPlay: Boolean? = null,
    val penaltyKick: Boolean? = null,
    val ownGoal: Boolean? = null,
    val shootout: Boolean? = null,
    val athletesInvolved: List<EspnAthleteDto>? = null
)

@JsonClass(generateAdapter = true)
data class EspnDetailTypeDto(val text: String? = null)

@JsonClass(generateAdapter = true)
data class EspnDetailClockDto(val displayValue: String? = null)

@JsonClass(generateAdapter = true)
data class EspnDetailTeamDto(val id: String? = null)

@JsonClass(generateAdapter = true)
data class EspnAthleteDto(val displayName: String? = null, val shortName: String? = null)

/**
 * ESPN's live `situation`. Football: possession (team id), down and distance, yard line, red zone.
 * Baseball: count, outs, runners, batter and pitcher. Basketball and others: usually `lastPlay` only.
 */
@JsonClass(generateAdapter = true)
data class EspnSituationDto(
    val lastPlay: EspnLastPlayDto? = null,
    /** The id of the team with the ball. */
    val possession: String? = null,
    /** "2nd & 6" */
    val shortDownDistanceText: String? = null,
    /** "2nd & 6 at LV 34" */
    val downDistanceText: String? = null,
    /** "LV 34" */
    val possessionText: String? = null,
    val isRedZone: Boolean? = null,
    val balls: Int? = null,
    val strikes: Int? = null,
    val outs: Int? = null,
    /** A boolean on the scoreboard; read loosely so a runner object also counts as occupied. */
    val onFirst: Any? = null,
    val onSecond: Any? = null,
    val onThird: Any? = null,
    val batter: EspnSituationPlayerDto? = null,
    val pitcher: EspnSituationPlayerDto? = null
)

@JsonClass(generateAdapter = true)
data class EspnLastPlayDto(val text: String? = null)

@JsonClass(generateAdapter = true)
data class EspnSituationPlayerDto(val athlete: EspnAthleteDto? = null)
