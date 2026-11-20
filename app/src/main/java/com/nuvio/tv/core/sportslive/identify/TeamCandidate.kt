package com.nuvio.tv.core.sportslive.identify

import com.squareup.moshi.JsonClass

/**
 * One team a lookup source knows by a name (#168). [sport] is a lowercase ESPN-style key ("basketball",
 * "soccer", "hockey", "football" for American football, "baseball", "rugby", ...); [leagues] are display
 * names ("EuroLeague Basketball"); [badge] is the team's logo URL when the source has one.
 */
@JsonClass(generateAdapter = true)
data class TeamCandidate(
    val name: String,
    val alternates: List<String>,
    val sport: String,
    val leagues: List<String>,
    val badge: String?
)

/**
 * What both teams of a game agree on (#168): one sport and a league both play in. [badges] are the first
 * and second side's logos, in the order the game was asked about.
 */
data class GameIdentity(
    val sport: String,
    val league: String,
    val badges: Pair<String?, String?>
)

/** A lookup's outcome: [Found] (empty when the source knows no such team) or [Failed]. */
sealed interface TeamSearchResult {
    data class Found(val candidates: List<TeamCandidate>) : TeamSearchResult

    /** The request failed; [rateLimited] when the source said we asked too often (HTTP 429). */
    data class Failed(val rateLimited: Boolean = false) : TeamSearchResult
}

/** A public team search (#168). Only the team name is sent. Never throws, except to cancel. */
interface TeamLookupSource {
    suspend fun search(name: String): TeamSearchResult
}
