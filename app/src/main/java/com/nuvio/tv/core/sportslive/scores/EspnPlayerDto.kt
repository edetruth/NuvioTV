package com.nuvio.tv.core.sportslive.scores

import com.squareup.moshi.JsonClass

// ESPN's athlete endpoints for the Player card (#63), read tolerantly: every field may be missing.
// `.../common/v3/sports/{sport}/{league}/athletes/{id}` (bio + season summary) and `.../{id}/gamelog`.

@JsonClass(generateAdapter = true)
data class EspnAthleteResponseDto(val athlete: EspnPlayerDto? = null)

@JsonClass(generateAdapter = true)
data class EspnPlayerDto(
    val id: String? = null,
    val displayName: String? = null,
    val shortName: String? = null,
    val jersey: String? = null,
    val headshot: EspnHrefDto? = null,
    val position: EspnAthletePositionDto? = null,
    val team: EspnAthleteTeamDto? = null,
    /** "7' 0\"", "270 lbs", "11th Season": shown as ESPN gives them. */
    val displayHeight: String? = null,
    val displayWeight: String? = null,
    val displayExperience: String? = null,
    /** ESPN's own pick of the season's key stats for the player's position (four today). */
    val statsSummary: EspnAthleteStatsSummaryDto? = null
)

@JsonClass(generateAdapter = true)
data class EspnAthletePositionDto(val displayName: String? = null, val abbreviation: String? = null)

@JsonClass(generateAdapter = true)
data class EspnAthleteTeamDto(
    val id: String? = null,
    val abbreviation: String? = null,
    val displayName: String? = null,
    val logos: List<EspnHrefDto>? = null
)

@JsonClass(generateAdapter = true)
data class EspnAthleteStatsSummaryDto(
    /** "2026-27 regular season stats". */
    val displayName: String? = null,
    val statistics: List<EspnAthleteStatDto>? = null
)

@JsonClass(generateAdapter = true)
data class EspnAthleteStatDto(val name: String? = null, val abbreviation: String? = null, val displayValue: String? = null)

/** The game log: column [labels] and [names], each game's details in [events], its stat row in [seasonTypes]. */
@JsonClass(generateAdapter = true)
data class EspnGameLogDto(
    val labels: List<String>? = null,
    val names: List<String>? = null,
    val events: Map<String, EspnGameLogEventDto>? = null,
    val seasonTypes: List<EspnGameLogSeasonTypeDto>? = null
)

@JsonClass(generateAdapter = true)
data class EspnGameLogEventDto(
    val id: String? = null,
    /** "@" or "vs". */
    val atVs: String? = null,
    val gameDate: String? = null,
    /** Winner's score first: "135-123", "5-4 F/13". */
    val score: String? = null,
    /** "W", "L", "D" (soccer), "T". */
    val gameResult: String? = null,
    val opponent: EspnGameLogOpponentDto? = null
)

@JsonClass(generateAdapter = true)
data class EspnGameLogOpponentDto(
    val id: String? = null,
    val abbreviation: String? = null,
    val displayName: String? = null,
    val logo: String? = null
)

@JsonClass(generateAdapter = true)
data class EspnGameLogSeasonTypeDto(val displayName: String? = null, val categories: List<EspnGameLogCategoryDto>? = null)

@JsonClass(generateAdapter = true)
data class EspnGameLogCategoryDto(val events: List<EspnGameLogStatsDto>? = null)

@JsonClass(generateAdapter = true)
data class EspnGameLogStatsDto(val eventId: String? = null, val stats: List<String>? = null)
