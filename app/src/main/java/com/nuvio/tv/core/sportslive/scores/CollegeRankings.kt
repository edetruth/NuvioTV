package com.nuvio.tv.core.sportslive.scores

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@JsonClass(generateAdapter = true)
data class EspnRankingsDto(val rankings: List<EspnPollDto>? = null)

@JsonClass(generateAdapter = true)
data class EspnPollDto(val type: String? = null, val ranks: List<EspnPollRankDto>? = null)

@JsonClass(generateAdapter = true)
data class EspnPollRankDto(val current: Int? = null, val team: EspnPollTeamDto? = null)

@JsonClass(generateAdapter = true)
data class EspnPollTeamDto(val id: String? = null)

/** ESPN's college polls for a league (`{sport}/{league}/rankings`), or null on any failure. Never throws. */
interface RankingsSource {
    suspend fun rankings(league: EspnLeague): String?
}

/**
 * College polls (live.20 BUG18, Q13): every ranked team's AP rank, by ESPN team id, for the Game page's standings
 * ("#3 Florida" on every row, not only the game's two teams). One request per league per ESPN (US Eastern) day,
 * kept in memory; a failed request is not retried that day (the rows simply show no rank).
 */
@Singleton
class CollegeRankingsRepository internal constructor(
    private val source: RankingsSource,
    private val moshi: Moshi,
    private val now: () -> Long
) {
    @Inject
    constructor(source: RankingsSource, moshi: Moshi) : this(source, moshi, System::currentTimeMillis)

    private val adapter by lazy { moshi.adapter(EspnRankingsDto::class.java) }
    private val lock = Mutex()
    private val byDay = HashMap<EspnLeague, Pair<LocalDate, Map<String, Int>>>()

    /** [league]'s AP ranks by team id (1..25); empty for a league without polls or on failure. */
    suspend fun ranks(league: EspnLeague): Map<String, Int> {
        if (!RankDisplay.isCollege(league)) return emptyMap()
        val today = Instant.ofEpochMilli(now()).atZone(SportsResults.EASTERN).toLocalDate()
        return lock.withLock {
            byDay[league]?.takeIf { it.first == today }?.let { return@withLock it.second }
            val ranks = source.rankings(league)?.let { json -> runCatching { adapter.fromJson(json) }.getOrNull() }?.let(::apRanks).orEmpty()
            byDay[league] = today to ranks
            ranks
        }
    }

    companion object {
        /** The AP poll's ranks (else the first poll's), by team id, within the poll's depth. */
        fun apRanks(dto: EspnRankingsDto): Map<String, Int> {
            val polls = dto.rankings.orEmpty()
            val poll = polls.firstOrNull { it.type == "ap" } ?: polls.firstOrNull() ?: return emptyMap()
            return poll.ranks.orEmpty().mapNotNull { rank ->
                val id = rank.team?.id?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val current = rank.current?.takeIf { it in 1..RankDisplay.COLLEGE_TOP } ?: return@mapNotNull null
                id to current
            }.toMap()
        }
    }
}
