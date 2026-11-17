package com.nuvio.tv.core.sportslive.scores

import com.squareup.moshi.JsonClass

// Tolerant DTOs for ESPN's site API game summary (`{sport}/{league}/summary?event=<id>`): only the fields
// the Game page reads, every one optional. Re-serialised as is for the disk cache of finals, so the
// cached copy holds only these fields.

@JsonClass(generateAdapter = true)
data class EspnSummaryDto(
    val header: EspnSummaryHeaderDto? = null,
    val gameInfo: EspnGameInfoDto? = null,
    val boxscore: EspnBoxscoreDto? = null,
    val leaders: List<EspnTeamLeadersDto>? = null,
    val standings: EspnStandingsDto? = null,
    val videos: List<EspnVideoDto>? = null
)

@JsonClass(generateAdapter = true)
data class EspnSummaryHeaderDto(val id: String? = null, val competitions: List<EspnSummaryCompetitionDto>? = null)

@JsonClass(generateAdapter = true)
data class EspnSummaryCompetitionDto(
    val date: String? = null,
    val status: EspnStatusDto? = null,
    val competitors: List<EspnSummaryCompetitorDto>? = null,
    /** Soccer: goals and cards; `participants[0]` is the scorer. */
    val details: List<EspnSummaryDetailDto>? = null
)

@JsonClass(generateAdapter = true)
data class EspnSummaryCompetitorDto(
    val homeAway: String? = null,
    val winner: Boolean? = null,
    val score: String? = null,
    val team: EspnSummaryTeamDto? = null,
    val linescores: List<EspnSummaryLinescoreDto>? = null,
    /** "total" ("11-3"), "home", "road"...; soccer also "points". */
    val record: List<EspnRecordDto>? = null
)

@JsonClass(generateAdapter = true)
data class EspnSummaryTeamDto(
    val id: String? = null,
    val abbreviation: String? = null,
    val displayName: String? = null,
    val shortDisplayName: String? = null,
    val name: String? = null,
    val location: String? = null,
    val logo: String? = null,
    val logos: List<EspnLogoDto>? = null
)

@JsonClass(generateAdapter = true)
data class EspnLogoDto(val href: String? = null)

@JsonClass(generateAdapter = true)
data class EspnSummaryLinescoreDto(val displayValue: String? = null, val value: Double? = null)

@JsonClass(generateAdapter = true)
data class EspnRecordDto(val type: String? = null, val summary: String? = null)

@JsonClass(generateAdapter = true)
data class EspnSummaryDetailDto(
    val clock: EspnDetailClockDto? = null,
    val team: EspnDetailTeamDto? = null,
    val scoringPlay: Boolean? = null,
    val penaltyKick: Boolean? = null,
    val ownGoal: Boolean? = null,
    val shootout: Boolean? = null,
    val participants: List<EspnParticipantDto>? = null
)

@JsonClass(generateAdapter = true)
data class EspnParticipantDto(val athlete: EspnSummaryAthleteDto? = null)

@JsonClass(generateAdapter = true)
data class EspnSummaryAthleteDto(
    val id: String? = null,
    val displayName: String? = null,
    val shortName: String? = null,
    val position: EspnPositionDto? = null
)

@JsonClass(generateAdapter = true)
data class EspnPositionDto(val abbreviation: String? = null)

@JsonClass(generateAdapter = true)
data class EspnGameInfoDto(val venue: EspnVenueDto? = null)

@JsonClass(generateAdapter = true)
data class EspnVenueDto(val fullName: String? = null)

@JsonClass(generateAdapter = true)
data class EspnBoxscoreDto(
    val teams: List<EspnBoxscoreTeamDto>? = null,
    val players: List<EspnBoxscorePlayersDto>? = null
)

@JsonClass(generateAdapter = true)
data class EspnBoxscoreTeamDto(
    val homeAway: String? = null,
    val team: EspnSummaryTeamDto? = null,
    val statistics: List<EspnTeamStatDto>? = null
)

/** `fieldGoalPct` / "Field Goal %" / "50". */
@JsonClass(generateAdapter = true)
data class EspnTeamStatDto(val name: String? = null, val label: String? = null, val displayValue: String? = null)

@JsonClass(generateAdapter = true)
data class EspnBoxscorePlayersDto(val team: EspnSummaryTeamDto? = null, val statistics: List<EspnPlayerGroupDto>? = null)

/** A player table: NBA has one; the NFL one per unit ("passing"...); the NHL forwards / defenses / goalies. */
@JsonClass(generateAdapter = true)
data class EspnPlayerGroupDto(
    val name: String? = null,
    val text: String? = null,
    val labels: List<String>? = null,
    val totals: List<String>? = null,
    val athletes: List<EspnPlayerLineDto>? = null
)

@JsonClass(generateAdapter = true)
data class EspnPlayerLineDto(
    val starter: Boolean? = null,
    val didNotPlay: Boolean? = null,
    val athlete: EspnSummaryAthleteDto? = null,
    val stats: List<String>? = null
)

@JsonClass(generateAdapter = true)
data class EspnTeamLeadersDto(val team: EspnSummaryTeamDto? = null, val leaders: List<EspnLeaderCategoryDto>? = null)

@JsonClass(generateAdapter = true)
data class EspnLeaderCategoryDto(
    val name: String? = null,
    val displayName: String? = null,
    val abbreviation: String? = null,
    val leaders: List<EspnLeaderDto>? = null
)

@JsonClass(generateAdapter = true)
data class EspnLeaderDto(
    /** "24", or a whole line for football ("17/27, 220 YDS, 1 TD, 1 INT"). */
    val displayValue: String? = null,
    val mainStat: EspnMainStatDto? = null,
    val athlete: EspnSummaryAthleteDto? = null
)

@JsonClass(generateAdapter = true)
data class EspnMainStatDto(val value: String? = null, val label: String? = null)

@JsonClass(generateAdapter = true)
data class EspnStandingsDto(val header: String? = null, val groups: List<EspnStandingsGroupDto>? = null)

@JsonClass(generateAdapter = true)
data class EspnStandingsGroupDto(val header: String? = null, val standings: EspnStandingsTableDto? = null)

@JsonClass(generateAdapter = true)
data class EspnStandingsTableDto(val entries: List<EspnStandingsEntryDto>? = null)

@JsonClass(generateAdapter = true)
data class EspnStandingsEntryDto(val team: String? = null, val id: String? = null, val stats: List<EspnStandingsStatDto>? = null)

@JsonClass(generateAdapter = true)
data class EspnStandingsStatDto(val name: String? = null, val abbreviation: String? = null, val displayValue: String? = null)

/** A highlight clip: only its title, thumbnail and length are read; the video itself is never played. */
@JsonClass(generateAdapter = true)
data class EspnVideoDto(
    val id: String? = null,
    val headline: String? = null,
    val thumbnail: String? = null,
    val duration: Double? = null
)
