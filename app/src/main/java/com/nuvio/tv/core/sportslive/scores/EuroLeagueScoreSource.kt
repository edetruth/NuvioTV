package com.nuvio.tv.core.sportslive.scores

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * EuroLeague and EuroCup scores (#169) from the competitions' own public feed (the one euroleague.net reads;
 * ESPN lists no games for them). Not a documented API: any change fails quietly (no score) and shows in
 * Scores status ([EspnFeedHealth], under the league's key). A request carries only the competition, season
 * and round.
 *
 * The full season (all games, ~700 KB) is read at most every [SCHEDULE_MS] only to learn which rounds fall
 * on a day; a scoreboard then fetches just those rounds (~20 KB each), so the 30 s live polling stays small.
 * Polled like any feed: only while a card routed to it is on screen ([LiveScoresRepository]).
 */
class EuroLeagueScoreSource internal constructor(
    private val client: OkHttpClient,
    moshi: Moshi,
    private val baseUrl: String = BASE_URL,
    private val now: () -> Long = System::currentTimeMillis,
    private val health: EspnFeedHealth? = null
) : LeagueScoreSource {

    constructor(base: OkHttpClient, moshi: Moshi, health: EspnFeedHealth) : this(feedClient(base), moshi, BASE_URL, health = health)

    private val adapter = moshi.adapter(EuroLeagueGamesDto::class.java)

    /** A season's rounds by ESPN day, as last read. */
    private class Schedule(val fetchedAt: Long, val rounds: Map<LocalDate, Set<Int>>)

    private val scheduleLock = Mutex()
    private val schedules = HashMap<String, Schedule>()

    /** A round's games as last fetched, so two days' feeds polled together share one request. */
    private class RoundGames(val fetchedAt: Long, val games: List<EuroLeagueGameDto>)

    private val roundLock = Mutex()
    private val rounds = HashMap<String, RoundGames>()

    override fun handles(league: EspnLeague): Boolean = league == EspnLeagueMap.EUROLEAGUE || league == EspnLeagueMap.EUROCUP

    override suspend fun scoreboard(league: EspnLeague, date: LocalDate): ScoreboardResult {
        val competition = competitionOf(league) ?: return ScoreboardResult.FAILED
        val season = seasonCode(competition, date)
        val key = EspnFeedKey.scoreboard(league, date)
        return try {
            val schedule = schedule(competition, season)
            val games = schedule.rounds[date].orEmpty().sorted().flatMap { number -> round(competition, season, number) }
            health?.recordSuccess(key)
            ScoreboardResult(toSnapshots(games, league).filter { it.startMs?.let(::espnDay) == date })
        } catch (e: CancellationException) {
            throw e
        } catch (e: FeedException) {
            health?.recordFailure(key, e.kind, e.httpCode)
            ScoreboardResult.FAILED
        } catch (e: Exception) {
            health?.recordFailure(key, FeedFailureKind.of(e))
            ScoreboardResult.FAILED
        }
    }

    private suspend fun schedule(competition: String, season: String): Schedule = scheduleLock.withLock {
        val id = "$competition/$season"
        schedules[id]?.takeIf { now() - it.fetchedAt in 0 until SCHEDULE_MS }?.let { return@withLock it }
        val games = get("${gamesUrl(competition, season)}?limit=$SEASON_LIMIT")
        val byDay = HashMap<LocalDate, MutableSet<Int>>()
        for (game in games) {
            val round = game.round?.round ?: continue
            val start = game.date?.let(::parseDate) ?: continue
            byDay.getOrPut(espnDay(start)) { HashSet() } += round
        }
        Schedule(now(), byDay).also { schedules[id] = it }
    }

    private suspend fun round(competition: String, season: String, round: Int): List<EuroLeagueGameDto> = roundLock.withLock {
        val id = "$competition/$season/$round"
        rounds[id]?.takeIf { now() - it.fetchedAt in 0 until ROUND_REUSE_MS }?.let { return@withLock it.games }
        val games = get("${gamesUrl(competition, season)}?roundNumber=$round")
        if (rounds.size >= MAX_ROUNDS_KEPT) rounds.clear()
        rounds[id] = RoundGames(now(), games)
        games
    }

    private fun gamesUrl(competition: String, season: String) = "$baseUrl$competition/seasons/$season/games"

    private suspend fun get(url: String): List<EuroLeagueGameDto> = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful || body == null) throw FeedException(FeedFailureKind.HTTP, response.code)
            val dto = adapter.fromJson(body) ?: throw FeedException(FeedFailureKind.FORMAT)
            dto.data ?: throw FeedException(FeedFailureKind.FORMAT)
        }
    }

    private class FeedException(val kind: FeedFailureKind, val httpCode: Int? = null) : Exception()

    companion object {
        const val BASE_URL = "https://feeds.incrowdsports.com/provider/euroleague-feeds/v2/competitions/"

        /** How long a season's round calendar is trusted. */
        const val SCHEDULE_MS = 12 * 60 * 60_000L
        /** A round fetched this recently is reused (the other day's feed polled at the same time). */
        const val ROUND_REUSE_MS = 10_000L
        private const val SEASON_LIMIT = 500
        private const val MAX_ROUNDS_KEPT = 32
        private const val REGULATION_MINUTES = 40
        private const val QUARTER_SECONDS = 600
        private const val OT_SECONDS = 300

        private fun feedClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", EspnScoreProvider.USER_AGENT)
                        .removeHeader("Accept-Language")
                        .build()
                )
            }
            .build()

        /** The feed's competition code: E for EuroLeague, U for EuroCup. */
        internal fun competitionOf(league: EspnLeague): String? = when (league) {
            EspnLeagueMap.EUROLEAGUE -> "E"
            EspnLeagueMap.EUROCUP -> "U"
            else -> null
        }

        /** The season a day belongs to: "E2026" for 2026-27 (a season starts in the autumn). */
        internal fun seasonCode(competition: String, date: LocalDate): String =
            competition + (if (date.monthValue >= 7) date.year else date.year - 1)

        private fun espnDay(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(SportsResults.EASTERN).toLocalDate()

        private fun parseDate(text: String): Long? = runCatching { Instant.parse(text.trim()).toEpochMilli() }.getOrNull()

        /** The feed's games as snapshots of [league]; a game without an id, a start or two named sides is skipped. */
        internal fun toSnapshots(games: List<EuroLeagueGameDto>, league: EspnLeague): List<ScoreSnapshot> = games.mapNotNull { game ->
            val id = game.identifier?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val start = game.date?.let(::parseDate) ?: return@mapNotNull null
            val state = stateOf(game)
            val played = state == ScoreState.IN || state == ScoreState.POST
            val period = periodOf(game)
            val home = game.home?.let { teamOf(it, played, period, state) } ?: return@mapNotNull null
            val away = game.away?.let { teamOf(it, played, period, state) } ?: return@mapNotNull null
            ScoreSnapshot(
                eventId = "euroleague:$id",
                league = league,
                leagueName = if (league == EspnLeagueMap.EUROCUP) "EuroCup" else "EuroLeague",
                startMs = start,
                timeValid = game.confirmedTime != false,
                state = state,
                clockText = clockOf(game, state),
                isFinal = state == ScoreState.POST,
                home = home,
                away = away,
                mayResume = state == ScoreState.OFF && game.status.orEmpty().lowercase().let { it.contains("suspend") || it.contains("delay") }
            )
        }

        /**
         * The game's state from the feed's `status`: "confirmed" / "scheduled" before, "result" after; postponed,
         * cancelled, suspended or delayed is off. Any other status (the feed's live ones) is in play once a minute
         * or a point is on the board.
         */
        internal fun stateOf(game: EuroLeagueGameDto): ScoreState {
            val status = game.status?.trim()?.lowercase().orEmpty()
            return when {
                status == "result" || status == "finished" || status == "final" -> ScoreState.POST
                status == "confirmed" || status == "scheduled" || status == "pending" || status == "notstarted" -> ScoreState.PRE
                OFF_WORDS.any { status.contains(it) } -> ScoreState.OFF
                status.isEmpty() -> ScoreState.UNKNOWN
                game.minuteText != null || game.period != null || (game.home?.score ?: 0) + (game.away?.score ?: 0) > 0 -> ScoreState.IN
                else -> ScoreState.UNKNOWN
            }
        }

        private val OFF_WORDS = listOf("postpone", "cancel", "suspend", "delay", "abandon")

        /** "Q3 7:12", "OT 2:05", "Half", "Final", "Final/OT"; null before the game. */
        internal fun clockOf(game: EuroLeagueGameDto, state: ScoreState): String? = when (state) {
            ScoreState.POST -> if (overtime(game)) "Final/OT" else "Final"
            ScoreState.IN -> {
                val status = game.status?.lowercase().orEmpty()
                val quarter = periodOf(game)
                val left = (game.remainingText ?: leftFromMinute(game))?.let(::shortClock)
                when {
                    status.contains("half") -> "Half"
                    quarter == null -> "Live"
                    quarter > 4 -> listOfNotNull("OT", left).joinToString(" ")
                    else -> listOfNotNull("Q$quarter", left).joinToString(" ")
                }
            }
            else -> null
        }

        /** The period in play: the feed's, else worked out from the minutes played ("23:00": the 3rd quarter). */
        internal fun periodOf(game: EuroLeagueGameDto): Int? {
            game.period?.let { return it }
            val played = playedSeconds(game) ?: return null
            return if (played < REGULATION_MINUTES * 60) played / QUARTER_SECONDS + 1 else 5 + (played - REGULATION_MINUTES * 60) / OT_SECONDS
        }

        // The time left in the period from the minutes played, when the feed gives no clock.
        private fun leftFromMinute(game: EuroLeagueGameDto): String? {
            if (game.period != null) return null
            val played = playedSeconds(game) ?: return null
            val left = if (played < REGULATION_MINUTES * 60) {
                QUARTER_SECONDS - played % QUARTER_SECONDS
            } else {
                OT_SECONDS - (played - REGULATION_MINUTES * 60) % OT_SECONDS
            }
            return String.format(Locale.ROOT, "%d:%02d", left / 60, left % 60)
        }

        // "23:00" / "23" played → seconds; null when unreadable.
        private fun playedSeconds(game: EuroLeagueGameDto): Int? {
            val text = game.minuteText ?: return null
            val minutes = text.substringBefore(':').trim().toIntOrNull() ?: return null
            val seconds = if (':' in text) text.substringAfter(':').trim().toIntOrNull() ?: 0 else 0
            return (minutes * 60 + seconds).takeIf { it >= 0 }
        }

        // "07:12" → "7:12", "00:45" → "0:45".
        private fun shortClock(text: String): String = if (text.length == 5 && text[0] == '0' && text[2] == ':') text.substring(1) else text

        // "45:00" played: past regulation.
        private fun overtime(game: EuroLeagueGameDto): Boolean {
            if ((game.period ?: 0) > 4) return true
            if (listOfNotNull(game.home, game.away).any { it.quarters?.ot1 != null }) return true
            val minutes = game.minuteText?.substringBefore(':')?.trim()?.toIntOrNull() ?: return false
            return minutes > REGULATION_MINUTES
        }

        private fun teamOf(team: EuroLeagueTeamDto, played: Boolean, quarter: Int?, state: ScoreState): ScoreTeam? {
            val name = team.name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val short = team.abbreviatedName?.trim()?.takeIf { it.isNotEmpty() }
            val editorial = team.editorialName?.trim()?.takeIf { it.isNotEmpty() }
            return ScoreTeam(
                id = team.code?.trim()?.takeIf { it.isNotEmpty() }?.let { "euroleague:$it" },
                abbreviation = team.tla?.trim()?.takeIf { it.isNotEmpty() },
                displayName = name,
                shortDisplayName = short,
                name = editorial,
                location = null,
                logo = team.imageUrls?.crest?.trim()?.takeIf { it.startsWith("https://") },
                score = if (played) team.score else null,
                linescores = if (played) linescoresOf(team, quarter, state) else emptyList(),
                alternateNames = alternateNames(name, listOfNotNull(short, editorial))
            )
        }

        private fun linescoresOf(team: EuroLeagueTeamDto, quarter: Int?, state: ScoreState): List<Int> {
            val q = team.quarters ?: return emptyList()
            val all = listOf(q.q1, q.q2, q.q3, q.q4, q.ot1, q.ot2, q.ot3, q.ot4, q.ot5)
            // Live: only the periods begun; after the game every period played.
            val upTo = if (state == ScoreState.IN) (quarter ?: 0) else all.indexOfLast { it != null } + 1
            return all.take(upTo.coerceIn(0, all.size)).map { it ?: 0 }
        }

        /**
         * The names a card may use for a club whose full name carries a sponsor (#169): its short and editorial
         * names, each followed by the full name's last one or two words, its city ("Maccabi Rapyd Tel Aviv":
         * "Maccabi Tel Aviv"; "Panathinaikos AKTOR Athens": "Panathinaikos Athens").
         */
        internal fun alternateNames(full: String, clubs: List<String>): List<String> {
            val words = full.split(' ').filter { it.isNotEmpty() }
            val folded = full.lowercase()
            return buildList {
                for (club in clubs) {
                    if (club.lowercase() != folded) add(club)
                    for (n in 1..2) {
                        if (words.size <= n) continue
                        val city = words.takeLast(n).joinToString(" ")
                        if (club.lowercase().endsWith(city.lowercase())) continue
                        add("$club $city")
                    }
                }
            }.distinctBy { it.lowercase() }.filter { it.lowercase() != folded }
        }
    }
}

// Tolerant DTOs for the EuroLeague feed's `games` (#169): only what the scores read.

@JsonClass(generateAdapter = true)
data class EuroLeagueGamesDto(val data: List<EuroLeagueGameDto>? = null)

@JsonClass(generateAdapter = true)
data class EuroLeagueGameDto(
    val identifier: String? = null,
    val date: String? = null,
    val status: String? = null,
    // Text ("23:00") or a number: read either way.
    val minute: Any? = null,
    val remainingTime: Any? = null,
    val quarter: Any? = null,
    val confirmedTime: Boolean? = null,
    val round: EuroLeagueRoundDto? = null,
    val home: EuroLeagueTeamDto? = null,
    val away: EuroLeagueTeamDto? = null
)

/** The period in play (5 and up: overtime); null when not given. */
internal val EuroLeagueGameDto.period: Int?
    get() = when (val q = quarter) {
        is Number -> q.toInt()
        is String -> q.trim().toIntOrNull()
        else -> null
    }?.takeIf { it > 0 }

internal val EuroLeagueGameDto.minuteText: String? get() = textOf(minute)
internal val EuroLeagueGameDto.remainingText: String? get() = textOf(remainingTime)

private fun textOf(value: Any?): String? = when (value) {
    is String -> value.trim().takeIf { it.isNotEmpty() }
    is Number -> value.toInt().toString()
    else -> null
}

@JsonClass(generateAdapter = true)
data class EuroLeagueRoundDto(val round: Int? = null)

@JsonClass(generateAdapter = true)
data class EuroLeagueTeamDto(
    val code: String? = null,
    val name: String? = null,
    val abbreviatedName: String? = null,
    val editorialName: String? = null,
    val tla: String? = null,
    val score: Int? = null,
    val quarters: EuroLeagueQuartersDto? = null,
    val imageUrls: EuroLeagueImagesDto? = null
)

@JsonClass(generateAdapter = true)
data class EuroLeagueQuartersDto(
    val q1: Int? = null,
    val q2: Int? = null,
    val q3: Int? = null,
    val q4: Int? = null,
    val ot1: Int? = null,
    val ot2: Int? = null,
    val ot3: Int? = null,
    val ot4: Int? = null,
    val ot5: Int? = null
)

@JsonClass(generateAdapter = true)
data class EuroLeagueImagesDto(val crest: String? = null)
