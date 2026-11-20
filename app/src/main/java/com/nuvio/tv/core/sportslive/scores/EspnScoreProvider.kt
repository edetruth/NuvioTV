package com.nuvio.tv.core.sportslive.scores

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.time.Instant
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
    private val moshi: Moshi,
    private val baseUrl: String,
    /** ESPN's day now (US Eastern): which scoreboards are still live and which are past. */
    private val today: () -> LocalDate = { LocalDate.now(SportsResults.EASTERN) },
    /** Where each request's outcome is recorded (#70). */
    private val health: EspnFeedHealth = EspnFeedHealth(),
    /** ESPN's athlete API, a different host from the scoreboards (#63). */
    private val athleteBaseUrl: String = ATHLETE_BASE_URL,
    /** ESPN's standings API (#137), another path on the scoreboards' host. */
    private val standingsBaseUrl: String = STANDINGS_BASE_URL
) : ScoreProvider, GameSummarySource, TeamPageSource, PlayerCardSource, StandingsSource {

    @Inject
    constructor(okHttpClient: OkHttpClient, moshi: Moshi, health: EspnFeedHealth) :
        this(espnClient(okHttpClient), moshi, BASE_URL, health = health)

    private val adapter = moshi.adapter(EspnScoreboardDto::class.java)

    private fun scoreboardUrl(league: EspnLeague, date: LocalDate): String =
        "$baseUrl${league.path}/scoreboard?dates=${date.format(DateTimeFormatter.BASIC_ISO_DATE)}" +
            "&limit=$LIMIT${groupsFor(league)}"

    /**
     * The HTTP cache's copy of the scoreboard, when it was received less than [ScoreProvider.RECENT_MS]
     * ago (ESPN's max-age is about a second, so max-stale carries the rest); never a request.
     */
    override suspend fun recentScoreboard(league: EspnLeague, date: LocalDate): ScoreboardResult? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(scoreboardUrl(league, date))
                    .cacheControl(
                        CacheControl.Builder().onlyIfCached()
                            .maxStale((ScoreProvider.RECENT_MS / 1_000L - 1).toInt(), TimeUnit.SECONDS).build()
                    )
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    val cached = response.cacheResponse
                    if (!response.isSuccessful || cached == null || body == null) return@use null
                    val dto = adapter.fromJson(body) ?: return@use null
                    if (EspnIndividualResults.covers(league)) {
                        null
                    } else {
                        ScoreboardResult(toSnapshots(dto, league), receivedAtMs = cached.receivedResponseAtMillis)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    override suspend fun scoreboard(league: EspnLeague, date: LocalDate): ScoreboardResult =
        withContext(Dispatchers.IO) {
            val url = scoreboardUrl(league, date)
            val key = EspnFeedKey.scoreboard(league, date)
            try {
                val past = date.isBefore(today().minusDays(1))
                val request = Request.Builder().url(url).apply {
                    // Yesterday's late games and today's can still change: revalidate rather than reuse
                    // the HTTP cache. Older days are final: a cached copy up to 7 days old is used as is
                    // (ESPN sends max-age of seconds), so a past day already cached is not fetched again.
                    if (!past) {
                        header("Cache-Control", "no-cache")
                    } else {
                        cacheControl(CacheControl.Builder().maxStale(PAST_DAY_MAX_STALE_DAYS, TimeUnit.DAYS).build())
                    }
                }.build()
                var call = client.newCall(request).execute()
                // The stored copy stood in for a failed refetch: it says nothing about ESPN now.
                var storedStandIn = false
                // A copy stored while its day was still live (today's or yesterday's scoreboard, games in
                // progress) is never a final day (#67): the past day is fetched again.
                val stored = call.cacheResponse
                if (past && call.networkResponse == null && stored != null && !storedAsPast(date, stored.receivedResponseAtMillis)) {
                    val storedBody = call.use { it.body?.string() }
                    val fresh = try {
                        client.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build()).execute()
                    } catch (e: IOException) {
                        null
                    }
                    // Offline or refused: the stored copy is still better than nothing (only its finals are listed).
                    call = if (fresh != null && (fresh.isSuccessful || storedBody == null)) {
                        fresh
                    } else {
                        fresh?.close()
                        if (storedBody == null) throw IOException("no stored copy")
                        storedStandIn = true
                        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("stored")
                            .body(storedBody.toResponseBody(JSON)).build()
                    }
                }
                call.use { response ->
                    val body = response.body?.string()
                    // Only an answer from ESPN tells its health: an HTTP-cache hit (a past day offline) does not.
                    val fromNetwork = !storedStandIn && (response.networkResponse != null || response.cacheResponse == null)
                    if (!response.isSuccessful || body == null) {
                        debugLog("${league.path} $date: HTTP ${response.code}")
                        if (fromNetwork) health.recordFailure(key, FeedFailureKind.HTTP, response.code)
                        ScoreboardResult.FAILED.copy(retryAfterMs = retryAfterMs(response.header("Retry-After")))
                    } else {
                        val dto = adapter.fromJson(body)
                        val individual = EspnIndividualResults.covers(league)
                        // Read, but games lost their competitors: ESPN changed its data, not an empty day.
                        if (dto == null || (!individual && EspnFeedHealth.formatChanged(dto))) {
                            debugLog("${league.path} $date: format changed")
                            if (fromNetwork) health.recordFailure(key, FeedFailureKind.FORMAT)
                            ScoreboardResult.FAILED
                        } else {
                            if (fromNetwork) health.recordSuccess(key)
                            if (individual) ScoreboardResult(EspnIndividualResults.toSnapshots(dto, league, date) + EspnIndividualResults.tennisLive(dto, league, date))
                            else ScoreboardResult(toSnapshots(dto, league))
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                debugLog("${league.path} $date: ${e.javaClass.simpleName}")
                health.recordFailure(key, FeedFailureKind.of(e))
                ScoreboardResult.FAILED
            }
        }

    /**
     * The game summary of [eventId] on [league]'s feed path, as ESPN sent it; null on any failure. Never
     * stored in the HTTP cache (a summary is ~500 KB): finals have their own trimmed disk copy. A failure
     * is recorded in [health] here; a success once the body is read ([GameSummaryRepository]).
     */
    override suspend fun summary(league: EspnLeague, eventId: String): String? =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl${league.path}/summary?event=$eventId"
            try {
                val request = Request.Builder().url(url)
                    .cacheControl(CacheControl.Builder().noCache().noStore().build())
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    if (!response.isSuccessful || body == null) {
                        debugLog("${league.path} summary $eventId: HTTP ${response.code}")
                        health.recordFailure(EspnFeedKey.SUMMARY, FeedFailureKind.HTTP, response.code)
                        null
                    } else {
                        body
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                debugLog("${league.path} summary $eventId: ${e.javaClass.simpleName}")
                health.recordFailure(EspnFeedKey.SUMMARY, FeedFailureKind.of(e))
                null
            }
        }

    /** The athlete's bio and season summary (#63); like a team body, never stored in the HTTP cache. */
    override suspend fun athlete(league: EspnLeague, athleteId: String): String? =
        teamText("$athleteBaseUrl${league.path}/athletes/$athleteId", "athlete $athleteId")

    /** The athlete's game log (#63). */
    override suspend fun gameLog(league: EspnLeague, athleteId: String): String? =
        teamText("$athleteBaseUrl${league.path}/athletes/$athleteId/gamelog", "gamelog $athleteId")

    /** [league]'s standings (#137), ESPN's v2 API; like a team body, never stored in the HTTP cache (kept by [StandingsRepository]). */
    override suspend fun standings(league: EspnLeague): String? =
        teamText("$standingsBaseUrl${league.path}/standings", "${league.path} standings")

    override suspend fun team(league: EspnLeague, teamId: String): String? =
        teamText("$baseUrl${league.path}/teams/$teamId", "team $teamId")

    override suspend fun teamSchedule(league: EspnLeague, teamId: String, fixtures: Boolean): String? =
        teamText("$baseUrl${league.path}/teams/$teamId/schedule" + if (fixtures) "?fixture=true" else "", "schedule $teamId")

    /**
     * [league]'s team list (#82): every team with its names and logo, for the logo directory. Kept out of
     * the HTTP cache (a college list is ~1.5 MB; the directory keeps its own trimmed copy for a week).
     */
    override suspend fun teamList(league: EspnLeague): List<DirectoryTeam>? =
        teamText("$baseUrl${league.path}/teams?limit=$LIMIT${groupsFor(league)}", "${league.path} teams")
            ?.let { body -> withContext(Dispatchers.Default) { TeamDirectory.parse(body, moshi) } }

    /**
     * A Team page body (#62), as ESPN sent it; null on any failure. Kept out of the HTTP cache (an NBA
     * schedule is ~900 KB): the page has its own memory cache and a trimmed disk copy of the finals.
     */
    private suspend fun teamText(url: String, label: String): String? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url)
                    .cacheControl(CacheControl.Builder().noCache().noStore().build())
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    if (!response.isSuccessful || body == null) {
                        debugLog("$label: HTTP ${response.code}")
                        null
                    } else {
                        body
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                debugLog("$label: ${e.javaClass.simpleName}")
                null
            }
        }

    companion object {
        private const val TAG = "EspnScoreProvider"
        internal const val BASE_URL = "https://site.api.espn.com/apis/site/v2/sports/"
        internal const val ATHLETE_BASE_URL = "https://site.web.api.espn.com/apis/common/v3/sports/"
        internal const val STANDINGS_BASE_URL = "https://site.api.espn.com/apis/v2/sports/"
        // ".../gameId/401930175/league/fifa.friendly_u21"
        private val LEAGUE_IN_LINK = Regex("""/league/([a-z0-9._-]+)""")
        private const val PAST_DAY_MAX_STALE_DAYS = 7

        private val JSON = "application/json".toMediaType()

        /** True when ESPN day [date] was already past (before yesterday, US Eastern) at [receivedAtMs]. */
        internal fun storedAsPast(date: LocalDate, receivedAtMs: Long): Boolean =
            date.isBefore(Instant.ofEpochMilli(receivedAtMs).atZone(SportsResults.EASTERN).toLocalDate().minusDays(1))

        // Without a limit a busy soccer/all day can be cut short.
        private const val LIMIT = 1000

        /**
         * College scoreboards list only featured games unless a group is asked for: 80 = FBS football,
         * 50 = Division I men's (and, on the women's scoreboard, women's) basketball.
         */
        internal fun groupsFor(league: EspnLeague): String = when (league) {
            EspnLeagueMap.COLLEGE_FOOTBALL -> "&groups=80"
            EspnLeagueMap.COLLEGE_BASKETBALL, EspnLeagueMap.COLLEGE_BASKETBALL_WOMEN -> "&groups=50"
            else -> ""
        }

        // status.type.name of a game that is not being played: STATUS_POSTPONED, STATUS_CANCELED,
        // STATUS_SUSPENDED, STATUS_DELAYED, STATUS_FORFEIT, STATUS_ABANDONED. Adapted from OFF_STATUS
        // in SportsFeeds of homelessbrian/NuvioPlusIPTV (GPL-3.0, as this project).
        private val OFF_STATUS = Regex("POSTPONED|CANCEL|SUSPENDED|DELAYED|FORFEIT|ABANDON")
        private val RESUMABLE = Regex("SUSPENDED|DELAYED")

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
        internal fun toSnapshots(dto: EspnScoreboardDto, league: EspnLeague): List<ScoreSnapshot> {
            // The feed's own league names ("NBA", "Premier League"); soccer/all lists none.
            val names = dto.leagues.orEmpty().mapNotNull { dtoLeague ->
                val slug = dtoLeague.slug?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name = (dtoLeague.abbreviation ?: dtoLeague.name)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                slug to name
            }.toMap()
            val snapshots = dto.events.orEmpty().mapNotNull { event -> toSnapshot(event, league, names) }
            // Some soccer/all events carry no league link; a sibling of the same ESPN league names the
            // slug, so a women's or youth game without a link keeps its qualifier ("Liga F" → esp.w.1).
            val slugs = snapshots.asSequence()
                .filter { it.competitionId != null && it.leagueSlug != league.league }
                .associate { it.competitionId to it.leagueSlug }
            return snapshots.map { snapshot ->
                val slug = snapshot.competitionId?.let(slugs::get)
                if (snapshot.leagueSlug == league.league && slug != null) snapshot.copy(leagueSlug = slug) else snapshot
            }
        }

        // "s:600~l:2395~e:401861185" → "2395".
        private val LEAGUE_IN_UID = Regex("""(?:^|~)l:(\d+)""")

        /** "UEFA Nations League, Group A2" → "UEFA Nations League": the competition before its stage. */
        internal fun competitionName(altGameNote: String?): String? =
            altGameNote?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }

        private fun toSnapshot(event: EspnEventDto, league: EspnLeague, leagueNames: Map<String, String>): ScoreSnapshot? {
            val id = event.id?.takeIf { it.isNotBlank() } ?: return null
            val competition = event.competitions?.firstOrNull() ?: return null
            val competitors = competition.competitors.orEmpty()
            val home = competitors.firstOrNull { it.homeAway == "home" } ?: return null
            val away = competitors.firstOrNull { it.homeAway == "away" } ?: return null
            val homeTeam = toTeam(home) ?: return null
            val awayTeam = toTeam(away) ?: return null
            val type = event.status?.type
            val state = stateOf(type)
            // Before kick-off ESPN sends "0": a score shows only once a game is in play or over.
            val scored = state == ScoreState.IN || state == ScoreState.POST
            val leagueSlug = event.links.orEmpty().firstNotNullOfOrNull { link ->
                link.href?.let { LEAGUE_IN_LINK.find(it)?.groupValues?.get(1) }
            } ?: league.league
            return ScoreSnapshot(
                eventId = id,
                league = league,
                leagueSlug = leagueSlug,
                leagueName = leagueNames[leagueSlug] ?: competitionName(competition.altGameNote),
                competitionId = event.uid?.let { LEAGUE_IN_UID.find(it)?.groupValues?.get(1) },
                startMs = event.date?.let(::parseDate),
                timeValid = competition.timeValid != false,
                state = state,
                clockText = type?.shortDetail?.takeIf { it.isNotBlank() },
                isFinal = state != ScoreState.OFF && type?.completed == true,
                home = if (scored) homeTeam else homeTeam.copy(score = null, linescores = emptyList()),
                away = if (scored) awayTeam else awayTeam.copy(score = null, linescores = emptyList()),
                scorers = if (scored) scorers(competition.details.orEmpty(), homeTeam.id, awayTeam.id) else emptyList(),
                // Suspended or delayed and not over: play may resume. Postponed/cancelled will not.
                mayResume = state == ScoreState.OFF && type?.state != "post" && type?.completed != true &&
                    type?.name?.let { RESUMABLE.containsMatchIn(it.uppercase()) } == true,
                // Only while in play: a final's leftover last play is not a situation.
                situation = if (state == ScoreState.IN) situation(competition.situation, homeTeam.id, awayTeam.id) else null,
                seasonType = event.season?.type,
                broadcasts = broadcastsOf(competition),
                headline = competition.notes.orEmpty().firstNotNullOfOrNull { it.headline?.trim()?.takeIf(String::isNotEmpty) },
                spread = competition.odds.orEmpty().firstNotNullOfOrNull { it.spread }
            )
        }

        private const val LAST_PLAY_MAX = 80

        /** ESPN's `situation` as a [GameSituation]; null when it carries nothing this app shows. */
        internal fun situation(dto: EspnSituationDto?, homeId: String?, awayId: String?): GameSituation? {
            dto ?: return null
            val possession = dto.possession?.trim()?.takeIf { it.isNotEmpty() }?.let { id ->
                when (id) {
                    homeId -> ScoreSide.HOME
                    awayId -> ScoreSide.AWAY
                    else -> null
                }
            }
            val situation = GameSituation(
                possession = possession,
                downDistance = (dto.shortDownDistanceText ?: dto.downDistanceText)?.trim()?.takeIf { it.isNotEmpty() },
                yardLine = dto.possessionText?.trim()?.takeIf { it.isNotEmpty() },
                redZone = dto.isRedZone == true,
                balls = dto.balls?.takeIf { it in 0..4 },
                strikes = dto.strikes?.takeIf { it in 0..3 },
                outs = dto.outs?.takeIf { it in 0..3 },
                onFirst = occupied(dto.onFirst),
                onSecond = occupied(dto.onSecond),
                onThird = occupied(dto.onThird),
                batter = playerName(dto.batter),
                pitcher = playerName(dto.pitcher),
                lastPlay = dto.lastPlay?.text?.let(::shortText)
            )
            return situation.takeIf { it != GameSituation() }
        }

        // A base is `true`/`false` on the scoreboard; a runner object (or id) also means occupied.
        private fun occupied(base: Any?): Boolean = when (base) {
            null -> false
            is Boolean -> base
            is String -> base.isNotBlank() && base != "false"
            else -> true
        }

        private fun playerName(player: EspnSituationPlayerDto?): String? =
            player?.athlete?.let { it.shortName ?: it.displayName }?.trim()?.takeIf { it.isNotEmpty() }

        /** [text] on one line, cut at a word to at most [LAST_PLAY_MAX] characters with "…". */
        internal fun shortText(text: String): String? {
            val line = text.replace(WHITESPACE, " ").trim()
            if (line.isEmpty()) return null
            if (line.length <= LAST_PLAY_MAX) return line
            val cut = line.substring(0, LAST_PLAY_MAX - 1)
            val atWord = cut.substringBeforeLast(' ').takeIf { it.length >= LAST_PLAY_MAX / 2 } ?: cut
            return atWord.trimEnd(' ', ',', ';', '(') + "…"
        }

        private val WHITESPACE = Regex("""\s+""")

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
                linescores = competitor.linescores.orEmpty().mapNotNull { it.value?.toInt() },
                record = competitor.records.orEmpty().let { records -> records.firstOrNull { it.type == "total" } ?: records.firstOrNull() }
                    ?.summary?.trim()?.takeIf { it.isNotEmpty() },
                rank = rankOf(competitor.curatedRank),
                form = competitor.form?.trim()?.takeIf { it.isNotEmpty() }
            )
        }

        /** ESPN's `curatedRank.current`, or null when absent, not positive, or college's 99 ("unranked"). */
        internal fun rankOf(rank: EspnCuratedRankDto?): Int? = rank?.current?.takeIf { it in 1 until UNRANKED }

        /** ESPN's poll and ranking value meaning "not ranked". */
        internal const val UNRANKED = 99

        /** Every channel name of the competition's broadcasts, once each, in ESPN's order. */
        internal fun broadcastsOf(competition: EspnCompetitionDto): List<String> =
            competition.broadcasts.orEmpty().flatMap { it.names.orEmpty() }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

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

        /** A game's [ScoreState] from its ESPN status type (scoreboard and summary alike). */
        internal fun stateOf(type: EspnStatusTypeDto?): ScoreState = when {
            type?.name?.let { OFF_STATUS.containsMatchIn(it.uppercase()) } == true -> ScoreState.OFF
            type?.state == "pre" -> ScoreState.PRE
            type?.state == "in" -> ScoreState.IN
            type?.state == "post" -> ScoreState.POST
            else -> ScoreState.UNKNOWN
        }

        // ESPN writes "2026-11-15T18:00Z" (no seconds); OffsetDateTime reads that and full ISO forms.
        internal fun parseDate(date: String): Long? =
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
