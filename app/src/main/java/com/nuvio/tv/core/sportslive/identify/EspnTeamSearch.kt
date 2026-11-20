package com.nuvio.tv.core.sportslive.identify

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * ESPN's public site search (#168), teams only: a team ESPN covers comes with its sport and its league
 * ("Maccabi Tel-Aviv", soccer, Israeli Premier League). A name ESPN only has articles for is [TeamSearchResult.Found]
 * with no candidates. Only the name is sent.
 */
class EspnTeamSearch internal constructor(
    private val client: OkHttpClient,
    private val moshi: Moshi,
    private val baseUrl: String
) : TeamLookupSource {

    constructor(okHttpClient: OkHttpClient, moshi: Moshi) : this(lookupClient(okHttpClient), moshi, BASE_URL)

    override suspend fun search(name: String): TeamSearchResult = withContext(Dispatchers.IO) {
        try {
            val url = baseUrl.toHttpUrl().newBuilder()
                .addQueryParameter("query", name)
                .addQueryParameter("limit", LIMIT)
                .addQueryParameter("type", "team")
                .build()
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                when {
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
        private const val BASE_URL = "https://site.web.api.espn.com/apis/search/v2"
        private const val LIMIT = "5"

        /** [body] as the teams it lists; empty when it lists none, null when unreadable. */
        fun parse(body: String, moshi: Moshi): List<TeamCandidate>? {
            val dto = runCatching { moshi.adapter(EspnSearchDto::class.java).fromJson(body) }.getOrNull() ?: return null
            return dto.results.orEmpty()
                .filter { it.type == "team" }
                .flatMap { it.contents.orEmpty() }
                .filter { it.type == null || it.type == "team" }
                .mapNotNull { team ->
                    val name = team.displayName?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    val sport = team.sport?.trim()?.takeIf { it.isNotEmpty() }?.let(TeamIdentityRules::sportKey)
                        ?: return@mapNotNull null
                    TeamCandidate(
                        name = name,
                        alternates = emptyList(),
                        sport = sport,
                        leagues = listOfNotNull(team.subtitle?.trim()?.takeIf { it.isNotEmpty() }),
                        badge = team.image?.defaultUrl?.trim()?.takeIf { it.startsWith("https://") }
                    )
                }
        }
    }
}

/**
 * The app's client for a team lookup (#168): the network interceptor runs after the app's interceptors,
 * so the request goes with a generic User-Agent and no Accept-Language.
 */
internal fun lookupClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .addNetworkInterceptor { chain ->
        chain.proceed(
            chain.request().newBuilder()
                .header("User-Agent", LOOKUP_USER_AGENT)
                .removeHeader("Accept-Language")
                .build()
        )
    }
    .build()

private const val LOOKUP_USER_AGENT = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko)"

// Tolerant DTOs for ESPN's search (#168): only what a team result needs.

@JsonClass(generateAdapter = true)
data class EspnSearchDto(val results: List<EspnSearchGroupDto>? = null)

@JsonClass(generateAdapter = true)
data class EspnSearchGroupDto(val type: String? = null, val contents: List<EspnSearchContentDto>? = null)

@JsonClass(generateAdapter = true)
data class EspnSearchContentDto(
    val type: String? = null,
    val displayName: String? = null,
    val subtitle: String? = null,
    val sport: String? = null,
    val image: EspnSearchImageDto? = null
)

@JsonClass(generateAdapter = true)
data class EspnSearchImageDto(@Json(name = "default") val defaultUrl: String? = null)
