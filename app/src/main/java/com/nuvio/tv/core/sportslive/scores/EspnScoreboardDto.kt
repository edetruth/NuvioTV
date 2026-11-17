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
    val links: List<EspnLinkDto>? = null
)

@JsonClass(generateAdapter = true)
data class EspnLinkDto(val href: String? = null)

@JsonClass(generateAdapter = true)
data class EspnStatusDto(val type: EspnStatusTypeDto? = null)

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
    val linescores: List<EspnLinescoreDto>? = null
)

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
data class EspnLinescoreDto(val value: Double? = null)

@JsonClass(generateAdapter = true)
data class EspnDetailDto(
    val clock: EspnDetailClockDto? = null,
    val team: EspnDetailTeamDto? = null,
    val scoringPlay: Boolean? = null,
    val penaltyKick: Boolean? = null,
    val ownGoal: Boolean? = null,
    val shootout: Boolean? = null,
    val athletesInvolved: List<EspnAthleteDto>? = null
)

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
