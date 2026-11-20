package com.nuvio.tv.core.sportslive.scores

import android.content.Context
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A Game page load: the summary (with whether it is final), or a failure. */
sealed interface GameSummaryLoad {
    data class Loaded(val summary: GameSummary, val final: Boolean) : GameSummaryLoad
    data object Failed : GameSummaryLoad
}

/**
 * Trimmed copies of finished games' summaries, one file per event; past [maxFiles] the oldest go.
 * Failure-silent: an unreadable or unwritable file is a miss.
 */
class GameSummaryDiskCache(private val dir: File, private val maxFiles: Int = MAX_FILES) {

    fun read(key: String): String? = runCatching { file(key).takeIf { it.isFile }?.readText() }.getOrNull()

    fun write(key: String, json: String) {
        runCatching {
            dir.mkdirs()
            val target = file(key)
            val temp = File(dir, target.name + ".tmp")
            temp.writeText(json)
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
            trim()
        }
    }

    private fun trim() {
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(SUFFIX) }.orEmpty()
        if (files.size <= maxFiles) return
        files.sortedBy { it.lastModified() }.take(files.size - maxFiles).forEach { it.delete() }
    }

    private fun file(key: String) = File(dir, key.replace(UNSAFE, "_") + SUFFIX)

    private companion object {
        const val MAX_FILES = 60
        const val SUFFIX = ".json"
        val UNSAFE = Regex("""[^A-Za-z0-9._-]""")
    }
}

/**
 * One game's ESPN summary for the Game page (#18). One request per opened game:
 * - a final is kept for good: in memory and in [disk] (a trimmed copy), so reopening it requests nothing;
 * - a game not final yet is reused for at most [LIVE_TTL_MS], so a live page refreshing every 30 s makes
 *   one request per 30 s, and reopening it within that time makes none.
 * The caller decides whether to load at all (Scores Off: not before Reveal) and polls only while the
 * page is visible. Failure-silent.
 */
@Singleton
class GameSummaryRepository internal constructor(
    private val source: GameSummarySource,
    private val disk: GameSummaryDiskCache?,
    moshi: Moshi,
    private val now: () -> Long,
    /** Where a fetched summary's read is recorded (#70); the source records request failures. */
    private val health: EspnFeedHealth = EspnFeedHealth(now)
) {
    @Inject
    constructor(source: GameSummarySource, @ApplicationContext context: Context, moshi: Moshi, health: EspnFeedHealth) : this(
        source = source,
        disk = GameSummaryDiskCache(File(context.cacheDir, "espn_game_summaries")),
        moshi = moshi,
        now = System::currentTimeMillis,
        health = health
    )

    private class Cached(val dto: EspnSummaryDto, val fetchedAt: Long, val final: Boolean)

    private val adapter = moshi.adapter(EspnSummaryDto::class.java)
    private val lock = Mutex()
    private val memory = LinkedHashMap<String, Cached>()

    suspend fun load(league: EspnLeague, eventId: String): GameSummaryLoad {
        val key = "${league.sport}_${league.league}_$eventId"
        lock.withLock {
            memory[key]?.takeIf { it.final || now() - it.fetchedAt < ttlOf(it) }?.let { return loaded(it, league, eventId) }
        }
        val diskKey = "v${DISK_FORMAT}_$key"
        disk?.let { cache ->
            val stored = withContext(Dispatchers.IO) { cache.read(diskKey)?.let(::parse) }
            if (stored != null && GameSummaryParser.isFinal(stored)) {
                val cached = Cached(stored, now(), final = true)
                remember(key, cached)
                return loaded(cached, league, eventId)
            }
        }
        val json = source.summary(league, eventId) ?: return GameSummaryLoad.Failed
        // Read, but without the game's two sides: ESPN changed its data format.
        val dto = parse(json)?.takeIf { it.header?.competitions?.firstOrNull()?.competitors.orEmpty().isNotEmpty() }
        if (dto == null) {
            health.recordFailure(EspnFeedKey.SUMMARY, FeedFailureKind.FORMAT)
            return GameSummaryLoad.Failed
        }
        health.recordSuccess(EspnFeedKey.SUMMARY)
        val final = GameSummaryParser.isFinal(dto)
        if (final) disk?.let { cache -> withContext(Dispatchers.IO) { cache.write(diskKey, adapter.toJson(dto)) } }
        val cached = Cached(dto, now(), final)
        remember(key, cached)
        return loaded(cached, league, eventId)
    }

    private suspend fun remember(key: String, cached: Cached) {
        lock.withLock {
            if (memory.size >= MAX_IN_MEMORY) memory.remove(memory.keys.first())
            memory[key] = cached
        }
    }

    private fun parse(json: String): EspnSummaryDto? = runCatching { adapter.fromJson(json) }.getOrNull()

    // Before tip-off a summary changes slowly (#171): the preview is reused for half an hour, never past the start.
    private fun ttlOf(cached: Cached): Long {
        val competition = cached.dto.header?.competitions?.firstOrNull()
        if (EspnScoreProvider.stateOf(competition?.status?.type) != ScoreState.PRE) return LIVE_TTL_MS
        val start = competition?.date?.let(EspnScoreProvider::parseDate) ?: return PRE_TTL_MS
        return if (now() < start) PRE_TTL_MS.coerceAtMost(start - cached.fetchedAt) else LIVE_TTL_MS
    }

    private fun loaded(cached: Cached, league: EspnLeague, eventId: String): GameSummaryLoad =
        GameSummaryParser.parse(cached.dto, league, eventId)?.let { GameSummaryLoad.Loaded(it, cached.final) }
            ?: GameSummaryLoad.Failed

    companion object {
        /** A game not final yet is fetched again after this long. */
        const val LIVE_TTL_MS = 30_000L

        /** An upcoming game's summary (its preview, #171) is fetched again after this long. */
        const val PRE_TTL_MS = 30 * 60_000L

        /**
         * The disk copy's format, part of its file name. A copy written in an older format is never read
         * (its game is fetched once more and saved again) and ages out with the oldest files.
         * 2: highlight clips keep their video links (#61); copies from before had none, so no Highlights.
         */
        internal const val DISK_FORMAT = 2
        private const val MAX_IN_MEMORY = 20
    }
}
