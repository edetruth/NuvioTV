package com.nuvio.tv.core.sportslive.scores

import android.content.Context
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

// Tolerant DTOs for ESPN's standings (`apis/v2/sports/<sport>/<league>/standings`, #137): a tree of groups
// (league → conference → division), each leaf with its table. Re-serialised as is for the device copy.

@JsonClass(generateAdapter = true)
data class EspnStandingsNodeDto(
    val name: String? = null,
    val abbreviation: String? = null,
    val children: List<EspnStandingsNodeDto>? = null,
    val standings: EspnStandingsLeafDto? = null
)

@JsonClass(generateAdapter = true)
data class EspnStandingsLeafDto(val entries: List<EspnStandingsLeafEntryDto>? = null)

@JsonClass(generateAdapter = true)
data class EspnStandingsLeafEntryDto(val team: EspnTeamDto? = null, val stats: List<EspnStandingsLeafStatDto>? = null)

@JsonClass(generateAdapter = true)
data class EspnStandingsLeafStatDto(val name: String? = null, val value: Double? = null, val displayValue: String? = null)

/** A source of standings: ESPN's JSON for [league], or null on any failure. Never throws. */
interface StandingsSource {
    suspend fun standings(league: EspnLeague): String?
}

/** Reads ESPN's standings tree into each team's place in its group, by ESPN team id. */
object StandingsParser {
    /**
     * Every leaf group's teams: [TeamStanding.position] from ESPN's `playoffSeed` (pro) or `rank` (soccer), else
     * the team's place in the listed order; [TeamStanding.groupSize] the group's team count. A team listed twice
     * keeps its first place.
     */
    fun parse(root: EspnStandingsNodeDto): Map<String, TeamStanding> {
        val out = LinkedHashMap<String, TeamStanding>()
        fun walk(node: EspnStandingsNodeDto) {
            val entries = node.standings?.entries.orEmpty().filter { !it.team?.id.isNullOrBlank() }
            if (entries.isNotEmpty()) {
                val group = (node.abbreviation ?: node.name)?.takeIf { it.isNotBlank() }
                entries.forEachIndexed { index, entry ->
                    val stats = entry.stats.orEmpty()
                    val position = (stats.firstOrNull { it.name == "playoffSeed" } ?: stats.firstOrNull { it.name == "rank" })
                        ?.let { it.value?.toInt() ?: it.displayValue?.trim()?.toIntOrNull() }
                        ?.takeIf { it > 0 }
                        ?: (index + 1)
                    out.putIfAbsent(entry.team!!.id!!, TeamStanding(position, entries.size, group))
                }
            }
            node.children.orEmpty().forEach(::walk)
        }
        walk(root)
        return out
    }
}

/**
 * Standings for the big-game score (#137), by league: fetched at most every [TTL_MS] per league and kept on the
 * device, only for the leagues a caller asks for (the precision rule: leagues in My sports with games on screen).
 * [books] holds what is known, from the device copy first. Failure-silent.
 */
@Singleton
class StandingsRepository internal constructor(
    private val source: StandingsSource,
    private val moshi: Moshi,
    private val dir: File?,
    private val now: () -> Long,
    private val scope: CoroutineScope
) {
    @Inject
    constructor(source: StandingsSource, moshi: Moshi, @ApplicationContext context: Context) : this(
        source = source,
        moshi = moshi,
        dir = File(context.filesDir, "sportslive/standings"),
        now = System::currentTimeMillis,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )

    private val adapter by lazy { EspnJson.guarded(moshi).adapter(EspnStandingsNodeDto::class.java) }
    private val lock = Mutex()
    private val fetchedAt = HashMap<EspnLeague, Long>()
    private val asked = HashSet<EspnLeague>()
    private val booksFlow = MutableStateFlow<Map<EspnLeague, Map<String, TeamStanding>>>(emptyMap())

    /** Each league's teams' places, by ESPN team id. */
    val books: StateFlow<Map<EspnLeague, Map<String, TeamStanding>>> = booksFlow.asStateFlow()

    /** Loads [leagues] not fresh yet (the device copy, then ESPN when it is older than [TTL_MS]), in the background. */
    fun ensure(leagues: Collection<EspnLeague>) {
        val wanted = leagues.filter(::covers).distinct().take(MAX_PER_ASK)
        if (wanted.isEmpty()) return
        scope.launch {
            for (league in wanted) refresh(league)
        }
    }

    private suspend fun refresh(league: EspnLeague) {
        val claimed = lock.withLock {
            val at = fetchedAt[league]
            if (at != null && now() - at < TTL_MS) return
            if (!asked.add(league)) return
            true
        }
        if (!claimed) return
        try {
            val file = dir?.let { File(it, fileName(league)) }
            // The device copy first: shown at once, and used as it is while still fresh.
            val stored = file?.takeIf { it.isFile }?.let { stored ->
                runCatching { adapter.fromJson(stored.readText()) }.getOrNull()?.let { it to stored.lastModified() }
            }
            stored?.let { (dto, modified) -> publish(league, dto, modified) }
            if (stored != null && now() - stored.second < TTL_MS) return
            val json = source.standings(league) ?: return
            val dto = runCatching { adapter.fromJson(json) }.getOrNull() ?: return
            if (StandingsParser.parse(dto).isEmpty()) return
            publish(league, dto, now())
            file?.let { target ->
                withContext(Dispatchers.IO) {
                    runCatching {
                        target.parentFile?.mkdirs()
                        target.writeText(adapter.toJson(dto))
                    }
                }
            }
        } finally {
            lock.withLock { asked.remove(league) }
        }
    }

    private suspend fun publish(league: EspnLeague, dto: EspnStandingsNodeDto, at: Long) {
        val book = StandingsParser.parse(dto)
        if (book.isEmpty()) return
        lock.withLock { fetchedAt[league] = at }
        booksFlow.update { it + (league to book) }
    }

    companion object {
        /** A league's standings are fetched again after this long. */
        const val TTL_MS = 3 * 60 * 60_000L
        private const val MAX_PER_ASK = 6
        private val SLUG = Regex("""[a-z0-9._-]+""")

        /** Leagues with a table worth asking for: the pro leagues, and soccer competitions (never `soccer/all`). */
        fun covers(league: EspnLeague): Boolean = when (league.sport) {
            "soccer" -> league != EspnLeagueMap.SOCCER_ALL && SLUG.matches(league.league)
            "basketball", "football", "hockey", "baseball" -> !RankDisplay.isCollege(league)
            else -> false
        }

        /** The standings league of [event]: a soccer game's own competition (its slug), else its feed. */
        fun leagueOf(event: ScoreSnapshot): EspnLeague =
            if (event.league.sport == "soccer") EspnLeague("soccer", event.leagueSlug) else event.league

        private val UNSAFE = Regex("""[^A-Za-z0-9._-]""")

        private fun fileName(league: EspnLeague): String = "${league.sport}_${league.league}.json".replace(UNSAFE, "_")
    }
}
