package com.nuvio.tv.core.sportslive.scores

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scoreboards from ESPN's public site API (keyless, unofficial). A request carries only the league and
 * the date, with a generic User-Agent and no Accept-Language. Failure-silent: any HTTP error, timeout or
 * unreadable body is [ScoreboardResult.FAILED], never a throw.
 */
@Singleton
class EspnScoreProvider internal constructor(
    private val client: OkHttpClient,
    moshi: Moshi,
    private val baseUrl: String,
    private val today: () -> LocalDate = { LocalDate.now() }
) : ScoreProvider {

    @Inject
    constructor(okHttpClient: OkHttpClient, moshi: Moshi) : this(espnClient(okHttpClient), moshi, BASE_URL)

    private val adapter = moshi.adapter(EspnScoreboardDto::class.java)

    override suspend fun scoreboard(league: EspnLeague, date: LocalDate): ScoreboardResult =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl${league.path}/scoreboard?dates=${date.format(DateTimeFormatter.BASIC_ISO_DATE)}"
            try {
                val request = Request.Builder().url(url).apply {
                    // Yesterday's late games and today's can still change: revalidate rather than reuse
                    // the HTTP cache. Older days are final: a cached copy up to 7 days old is used as is
                    // (ESPN sends max-age of seconds), so a past day already cached is not fetched again.
                    if (!date.isBefore(today().minusDays(1))) {
                        header("Cache-Control", "no-cache")
                    } else {
                        cacheControl(CacheControl.Builder().maxStale(PAST_DAY_MAX_STALE_DAYS, TimeUnit.DAYS).build())
                    }
                }.build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    if (!response.isSuccessful || body == null) {
                        debugLog("${league.path} $date: HTTP ${response.code}")
                        ScoreboardResult.FAILED.copy(retryAfterMs = retryAfterMs(response.header("Retry-After")))
                    } else {
                        val dto = adapter.fromJson(body)
                        if (dto == null) ScoreboardResult.FAILED else ScoreboardResult(toSnapshots(dto, league))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                debugLog("${league.path} $date: ${e.javaClass.simpleName}")
                ScoreboardResult.FAILED
            }
        }

    companion object {
        private const val TAG = "EspnScoreProvider"
        internal const val BASE_URL = "https://site.api.espn.com/apis/site/v2/sports/"
        // ".../gameId/401930175/league/fifa.friendly_u21"
        private val LEAGUE_IN_LINK = Regex("""/league/([a-z0-9._-]+)""")
        private const val PAST_DAY_MAX_STALE_DAYS = 7

        /** Retry-After in seconds (the HTTP-date form is ignored), capped at 15 minutes. */
        internal fun retryAfterMs(header: String?): Long? =
            header?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.coerceAtMost(15 * 60L)?.times(1_000L)

        internal const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko)"

        /**
         * The app's client with the request reduced to league + date: the network interceptor runs after
         * the app's interceptors, so it replaces the app User-Agent and drops Accept-Language.
         */
        private fun espnClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", USER_AGENT)
                        .removeHeader("Accept-Language")
                        .build()
                )
            }
            .build()

        /** Maps a parsed scoreboard to snapshots; an event without an id or two named sides is skipped. */
        internal fun toSnapshots(dto: EspnScoreboardDto, league: EspnLeague): List<ScoreSnapshot> =
            dto.events.orEmpty().mapNotNull { event -> toSnapshot(event, league) }

        private fun toSnapshot(event: EspnEventDto, league: EspnLeague): ScoreSnapshot? {
            val id = event.id?.takeIf { it.isNotBlank() } ?: return null
            val competition = event.competitions?.firstOrNull() ?: return null
            val competitors = competition.competitors.orEmpty()
            val home = competitors.firstOrNull { it.homeAway == "home" } ?: return null
            val away = competitors.firstOrNull { it.homeAway == "away" } ?: return null
            val homeTeam = toTeam(home) ?: return null
            val awayTeam = toTeam(away) ?: return null
            val type = event.status?.type
            val state = when (type?.state) {
                "pre" -> ScoreState.PRE
                "in" -> ScoreState.IN
                "post" -> ScoreState.POST
                else -> ScoreState.UNKNOWN
            }
            return ScoreSnapshot(
                eventId = id,
                league = league,
                leagueSlug = event.links.orEmpty().firstNotNullOfOrNull { link ->
                    link.href?.let { LEAGUE_IN_LINK.find(it)?.groupValues?.get(1) }
                } ?: league.league,
                startMs = event.date?.let(::parseDate),
                state = state,
                clockText = type?.shortDetail?.takeIf { it.isNotBlank() },
                isFinal = type?.completed == true,
                home = homeTeam,
                away = awayTeam,
                scorers = scorers(competition.details.orEmpty(), homeTeam.id, awayTeam.id)
            )
        }

        private fun toTeam(competitor: EspnCompetitorDto): ScoreTeam? {
            val team = competitor.team ?: return null
            val names = listOf(team.abbreviation, team.displayName, team.shortDisplayName, team.name, team.location)
            if (names.all { it.isNullOrBlank() }) return null
            return ScoreTeam(
                id = team.id,
                abbreviation = team.abbreviation?.takeIf { it.isNotBlank() },
                displayName = team.displayName?.takeIf { it.isNotBlank() },
                shortDisplayName = team.shortDisplayName?.takeIf { it.isNotBlank() },
                name = team.name?.takeIf { it.isNotBlank() },
                location = team.location?.takeIf { it.isNotBlank() },
                logo = team.logo?.takeIf { it.isNotBlank() },
                score = competitor.score?.trim()?.toIntOrNull(),
                linescores = competitor.linescores.orEmpty().mapNotNull { it.value?.toInt() }
            )
        }

        private fun scorers(details: List<EspnDetailDto>, homeId: String?, awayId: String?): List<ScoreScorer> =
            details.filter { it.scoringPlay == true && it.shootout != true }.mapNotNull { detail ->
                val athlete = detail.athletesInvolved?.firstOrNull()
                val name = (athlete?.shortName ?: athlete?.displayName)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val teamId = detail.team?.id
                ScoreScorer(
                    side = when {
                        teamId == null -> null
                        teamId == homeId -> ScoreSide.HOME
                        teamId == awayId -> ScoreSide.AWAY
                        else -> null
                    },
                    name = name,
                    clock = detail.clock?.displayValue?.takeIf { it.isNotBlank() },
                    penalty = detail.penaltyKick == true,
                    ownGoal = detail.ownGoal == true
                )
            }

        // ESPN writes "2026-11-15T18:00Z" (no seconds); OffsetDateTime reads that and full ISO forms.
        private fun parseDate(date: String): Long? =
            try {
                OffsetDateTime.parse(date.trim()).toInstant().toEpochMilli()
            } catch (e: Exception) {
                null
            }

        private fun debugLog(message: String) {
            if (BuildConfig.DEBUG) Log.d(TAG, message)
        }
    }
}
