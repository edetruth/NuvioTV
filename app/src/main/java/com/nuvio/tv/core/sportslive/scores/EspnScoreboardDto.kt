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
    val details: List<EspnDetailDto>? = null
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
