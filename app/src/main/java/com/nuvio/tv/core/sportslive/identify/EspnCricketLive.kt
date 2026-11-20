package com.nuvio.tv.core.sportslive.identify

import com.nuvio.tv.core.sportslive.SportsLiveNations
import com.nuvio.tv.core.sportslive.scores.EspnJson
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** The cricket games live right now, as [TeamIdentityBook.pairKey]s of [SportsLiveNations.key]s (#204). */
interface CricketLiveSource {
    suspend fun livePairs(): CricketLiveResult
}

sealed interface CricketLiveResult {
    data class Found(val pairs: Set<String>) : CricketLiveResult
    data class Failed(val rateLimited: Boolean = false) : CricketLiveResult
}

/**
 * ESPN's cricket scorepanel (#204): every cricket game it covers today, with its state. Only the pairings of the
 * games in progress are kept; no scores. Nothing about the user is sent.
 */
class EspnCricketLive internal constructor(
    private val client: OkHttpClient,
    private val moshi: Moshi,
    private val url: String
) : CricketLiveSource {

    constructor(okHttpClient: OkHttpClient, moshi: Moshi) : this(lookupClient(okHttpClient), moshi, URL)

    override suspend fun livePairs(): CricketLiveResult = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                when {
                    response.code == 429 -> CricketLiveResult.Failed(rateLimited = true)
                    !response.isSuccessful -> CricketLiveResult.Failed()
                    else -> response.body?.string()?.let { parse(it, moshi) }?.let { CricketLiveResult.Found(it) }
                        ?: CricketLiveResult.Failed()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CricketLiveResult.Failed()
        }
    }

    companion object {
        private const val URL = "https://site.web.api.espn.com/apis/site/v2/sports/cricket/scorepanel"
        private const val IN_PROGRESS = "in"

        /** [body]'s games in progress as pair keys; null when unreadable. */
        fun parse(body: String, moshi: Moshi): Set<String>? {
            val dto = runCatching { EspnJson.guarded(moshi).adapter(CricketPanelDto::class.java).fromJson(body) }.getOrNull() ?: return null
            return dto.scores.orEmpty().flatMap { it.events.orEmpty() }.flatMap { it.competitions.orEmpty() }
                .filter { it.status?.type?.state == IN_PROGRESS }
                .mapNotNullTo(HashSet()) { game ->
                    val names = game.competitors.orEmpty().mapNotNull { it.team?.displayName?.trim()?.takeIf(String::isNotEmpty) }
                    if (names.size != 2) return@mapNotNullTo null
                    TeamIdentityBook.pairKey(SportsLiveNations.key(names[0]), SportsLiveNations.key(names[1]))
                }
        }
    }
}

// Tolerant DTOs for the scorepanel (#204): only a game's state and its two teams' names.

@JsonClass(generateAdapter = true)
data class CricketPanelDto(val scores: List<CricketPanelScoreDto>? = null)

@JsonClass(generateAdapter = true)
data class CricketPanelScoreDto(val events: List<CricketPanelEventDto>? = null)

@JsonClass(generateAdapter = true)
data class CricketPanelEventDto(val competitions: List<CricketPanelCompetitionDto>? = null)

@JsonClass(generateAdapter = true)
data class CricketPanelCompetitionDto(
    val status: CricketPanelStatusDto? = null,
    val competitors: List<CricketPanelCompetitorDto>? = null
)

@JsonClass(generateAdapter = true)
data class CricketPanelStatusDto(val type: CricketPanelStatusTypeDto? = null)

@JsonClass(generateAdapter = true)
data class CricketPanelStatusTypeDto(val state: String? = null)

@JsonClass(generateAdapter = true)
data class CricketPanelCompetitorDto(val team: CricketPanelTeamDto? = null)

@JsonClass(generateAdapter = true)
data class CricketPanelTeamDto(val displayName: String? = null)
