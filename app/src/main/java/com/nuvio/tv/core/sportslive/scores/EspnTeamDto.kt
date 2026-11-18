package com.nuvio.tv.core.sportslive.scores

import com.squareup.moshi.JsonClass

// Tolerant DTOs for ESPN's site API team endpoints (the Team page, #62): `{sport}/{league}/teams/{id}`
// and `{sport}/{league}/teams/{id}/schedule` (soccer's upcoming games: `?fixture=true`). Only the fields
// the page reads, every one optional. A schedule is re-serialised, trimmed to past finals, for the disk copy.

@JsonClass(generateAdapter = true)
data class EspnTeamResponseDto(val team: EspnTeamInfoDto? = null)

@JsonClass(generateAdapter = true)
data class EspnTeamInfoDto(
    val id: String? = null,
    val abbreviation: String? = null,
    val displayName: String? = null,
    val shortDisplayName: String? = null,
    val name: String? = null,
    val location: String? = null,
    /** The schedule's team has one logo link; the team endpoint has [logos]. */
    val logo: String? = null,
    val logos: List<EspnLogoDto>? = null,
    /** "1st in Atlantic Division", "4th in NFC East", "1st in English Premier League". */
    val standingSummary: String? = null,
    /** The schedule's team: "12-3". */
    val recordSummary: String? = null,
    val record: EspnTeamRecordDto? = null
)

@JsonClass(generateAdapter = true)
data class EspnTeamRecordDto(val items: List<EspnTeamRecordItemDto>? = null)

/** One record: "total" ("12-3"), "home" ("6-2"), "road" ("6-1"). */
@JsonClass(generateAdapter = true)
data class EspnTeamRecordItemDto(
    val type: String? = null,
    val summary: String? = null,
    val stats: List<EspnTeamRecordStatDto>? = null
)

/** "streak": positive for wins in a row, negative for losses. */
@JsonClass(generateAdapter = true)
data class EspnTeamRecordStatDto(val name: String? = null, val value: Double? = null)

@JsonClass(generateAdapter = true)
data class EspnTeamScheduleDto(
    val team: EspnTeamInfoDto? = null,
    val events: List<EspnScheduleEventDto>? = null
)

@JsonClass(generateAdapter = true)
data class EspnScheduleEventDto(
    val id: String? = null,
    val date: String? = null,
    val timeValid: Boolean? = null,
    /** Soccer: the event's competition (`eng.1`, "Premier League"). */
    val league: EspnScheduleLeagueDto? = null,
    val competitions: List<EspnScheduleCompetitionDto>? = null
)

@JsonClass(generateAdapter = true)
data class EspnScheduleLeagueDto(val slug: String? = null, val abbreviation: String? = null, val name: String? = null)

@JsonClass(generateAdapter = true)
data class EspnScheduleCompetitionDto(
    val date: String? = null,
    val timeValid: Boolean? = null,
    /** On the competition here, not on the event as on the scoreboard. */
    val status: EspnStatusDto? = null,
    val competitors: List<EspnScheduleCompetitorDto>? = null
)

@JsonClass(generateAdapter = true)
data class EspnScheduleCompetitorDto(
    val homeAway: String? = null,
    val winner: Boolean? = null,
    /** An object here (`{"value": 131.0, "displayValue": "131"}`), a string on the scoreboard: read loosely. */
    val score: Any? = null,
    val team: EspnTeamInfoDto? = null
)
