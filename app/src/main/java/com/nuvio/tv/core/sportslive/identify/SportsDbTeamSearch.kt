package com.nuvio.tv.core.sportslive.identify

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * TheSportsDB's team search (#168) on its free test key "123" (the old "3" is disabled): a team with its
 * sport, up to seven leagues, alternate names and badge. The free key answers one team per name, so a
 * multi-sport club comes back as one of its teams (see [TeamIdentityRules.hintSport]). Only the name is sent.
 */
class SportsDbTeamSearch internal constructor(
    private val client: OkHttpClient,
    private val moshi: Moshi,
    private val baseUrl: String
) : TeamLookupSource {

    constructor(okHttpClient: OkHttpClient, moshi: Moshi) : this(lookupClient(okHttpClient), moshi, BASE_URL)

    override suspend fun search(name: String): TeamSearchResult = withContext(Dispatchers.IO) {
        try {
            val url = baseUrl.toHttpUrl().newBuilder().addQueryParameter("t", name).build()
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                when {
                    // Over the free key's rate it answers 429 with a plain-text body.
                    response.code == 429 -> TeamSearchResult.Failed(rateLimited = true)
                    !response.isSuccessful -> TeamSearchResult.Failed()
                    else -> response.body?.string()?.let { parse(it, moshi) }?.let { TeamSearchResult.Found(it) }
                        ?: TeamSearchResult.Failed()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TeamSearchResult.Failed()
        }
    }

    companion object {
        private const val BASE_URL = "https://www.thesportsdb.com/api/v1/json/123/searchteams.php"

        /** [body] as the teams it lists (`"teams": null` is none); null when unreadable. */
        fun parse(body: String, moshi: Moshi): List<TeamCandidate>? {
            val dto = runCatching { moshi.adapter(SportsDbTeamsDto::class.java).fromJson(body) }.getOrNull() ?: return null
            return dto.teams.orEmpty().mapNotNull { team ->
                val name = team.strTeam?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val sport = team.strSport?.trim()?.takeIf { it.isNotEmpty() }?.let(TeamIdentityRules::sportKey)
                    ?: return@mapNotNull null
                val leagues = listOf(
                    team.strLeague, team.strLeague2, team.strLeague3, team.strLeague4,
                    team.strLeague5, team.strLeague6, team.strLeague7
                ).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.distinct()
                // "MTAFC, FCMTA, ..." : several alternate names in one field.
                val alternates = team.strTeamAlternate.orEmpty().split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.equals(name, ignoreCase = true) }
                    .distinct()
                TeamCandidate(
                    name = name,
                    alternates = alternates,
                    sport = sport,
                    leagues = leagues,
                    badge = team.strBadge?.trim()?.takeIf { it.startsWith("https://") }
                )
            }
        }
    }
}

// Tolerant DTOs for TheSportsDB's searchteams (#168): only what a candidate needs.

@JsonClass(generateAdapter = true)
data class SportsDbTeamsDto(val teams: List<SportsDbTeamDto>? = null)

@JsonClass(generateAdapter = true)
data class SportsDbTeamDto(
    val strTeam: String? = null,
    val strTeamAlternate: String? = null,
    val strSport: String? = null,
    val strLeague: String? = null,
    val strLeague2: String? = null,
    val strLeague3: String? = null,
    val strLeague4: String? = null,
    val strLeague5: String? = null,
    val strLeague6: String? = null,
    val strLeague7: String? = null,
    val strBadge: String? = null
)
