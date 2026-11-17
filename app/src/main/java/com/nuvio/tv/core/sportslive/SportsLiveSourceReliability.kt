package com.nuvio.tv.core.sportslive

import androidx.annotation.VisibleForTesting
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.domain.model.SportsLiveItem
import kotlin.math.pow

/**
 * Remembers which Sports & Live stream sources work (#40), on this device only, and ranks a card's
 * sources by it so auto-play, failover and the Sources panel try the most reliable one first.
 *
 * A source is an addon's catalog/provider ([keyOf]: addonId plus catalog key), not the volatile item
 * id, so evidence carries over from one game to the next. Each play attempt adds one outcome
 * (first frame, failure, stall); counts decay with a [HALF_LIFE_MS] half-life, so a source that was
 * bad yesterday can win again. The score is the recent success rate with a neutral prior ([NEUTRAL]
 * for an unknown source, so a new one is not buried), less a small penalty for slow starts.
 *
 * Reliability only reorders: callers keep the user's filters and preferences as constraints
 * ([rank]), and nothing here makes a request.
 */
object SportsLiveSourceReliability {
    const val HALF_LIFE_MS = 3L * 24 * 60 * 60 * 1000
    const val MAX_ENTRIES = 500
    const val NEUTRAL = 0.5

    /** Prior weight: how many outcomes the neutral prior counts as. */
    private const val PRIOR_WEIGHT = 1.0

    /** A stall counts as this much of a failure (the source did start). */
    private const val STALL_WEIGHT = 0.5

    /** Starts slower than this begin to cost score, up to [MAX_SLOW_PENALTY] at [VERY_SLOW_START_MS]. */
    private const val SLOW_START_MS = 3_000.0
    private const val VERY_SLOW_START_MS = 12_000.0
    private const val MAX_SLOW_PENALTY = 0.1

    /** Weight of a new time-to-first-frame in the running average. */
    private const val TTFF_ALPHA = 0.3

    /** The primary (the user's addon order picked it) keeps first place unless another source is clearly better. */
    const val PRIMARY_BONUS = 0.1

    enum class Outcome { SUCCESS, FAILURE, STALL }

    /**
     * One source's decayed evidence as of [atMs]: success, failure and stall weights, and the running
     * time-to-first-frame average ([ttffMs], negative when none is known).
     */
    data class Record(
        val successes: Double = 0.0,
        val failures: Double = 0.0,
        val stalls: Double = 0.0,
        val ttffMs: Double = -1.0,
        val atMs: Long = 0L
    ) {
        fun decayedTo(nowMs: Long): Record {
            val factor = decayFactor(nowMs - atMs)
            return copy(successes = successes * factor, failures = failures * factor, stalls = stalls * factor, atMs = maxOf(atMs, nowMs))
        }
    }

    /** The source identity of [item]: its addon and catalog/provider, stable across games and loads. */
    fun keyOf(item: SportsLiveItem): String = "${item.addonId}|${item.catalogKey}"

    internal fun decayFactor(elapsedMs: Long): Double =
        if (elapsedMs <= 0) 1.0 else 0.5.pow(elapsedMs.toDouble() / HALF_LIFE_MS)

    /** [record] (null when the source is new) with [outcome] added at [nowMs]; [ttffMs] is a success's start time. */
    fun update(record: Record?, outcome: Outcome, nowMs: Long, ttffMs: Long? = null): Record {
        val base = (record ?: Record(atMs = nowMs)).decayedTo(nowMs)
        return when (outcome) {
            Outcome.SUCCESS -> base.copy(
                successes = base.successes + 1,
                ttffMs = when {
                    ttffMs == null || ttffMs < 0 -> base.ttffMs
                    base.ttffMs < 0 -> ttffMs.toDouble()
                    else -> base.ttffMs + TTFF_ALPHA * (ttffMs - base.ttffMs)
                }
            )
            Outcome.FAILURE -> base.copy(failures = base.failures + 1)
            Outcome.STALL -> base.copy(stalls = base.stalls + 1)
        }
    }

    /** [record]'s score in 0..1 at [nowMs]; [NEUTRAL] for an unknown source. */
    fun score(record: Record?, nowMs: Long): Double {
        if (record == null) return NEUTRAL
        val r = record.decayedTo(nowMs)
        val rate = (r.successes + NEUTRAL * PRIOR_WEIGHT) /
            (r.successes + r.failures + STALL_WEIGHT * r.stalls + PRIOR_WEIGHT)
        val slowness = if (r.ttffMs < 0) 0.0 else ((r.ttffMs - SLOW_START_MS) / (VERY_SLOW_START_MS - SLOW_START_MS)).coerceIn(0.0, 1.0)
        return (rate - slowness * MAX_SLOW_PENALTY).coerceIn(0.0, 1.0)
    }

    /**
     * [candidates] (their original order is the user's preference, the primary at [primaryIndex])
     * ordered by reliability, best first. Only [allowed] candidates are reordered and they all stay
     * ahead of the rest, which keep their order: reliability never promotes what the user's filters
     * rule out. The primary gets [PRIMARY_BONUS]; ties keep the original order.
     */
    fun <T> rank(
        candidates: List<T>,
        scoreOf: (T) -> Double,
        allowed: (T) -> Boolean = { true },
        primaryIndex: Int = 0
    ): List<T> {
        if (candidates.size < 2) return candidates
        val (ok, rest) = candidates.withIndex().partition { allowed(it.value) }
        val ranked = ok.sortedWith(
            compareByDescending<IndexedValue<T>> { scoreOf(it.value) + if (it.index == primaryIndex) PRIMARY_BONUS else 0.0 }
                .thenBy { it.index }
        )
        return (ranked + rest).map { it.value }
    }

    /** [records] cut to the [max] most recently updated (least recently used dropped first). */
    fun capped(records: Map<String, Record>, max: Int = MAX_ENTRIES): Map<String, Record> =
        if (records.size <= max) records
        else records.entries.sortedByDescending { it.value.atMs }.take(max).associate { it.key to it.value }

    private val gson = Gson()

    // key -> [successes, failures, stalls, ttffMs, atMs]; plain lists, so minification cannot rename fields.
    private val storedType = object : TypeToken<Map<String, List<Double>>>() {}.type

    /** A compact JSON form of [records], for on-device storage. */
    fun encode(records: Map<String, Record>): String =
        gson.toJson(records.mapValues { (_, r) -> listOf(r.successes, r.failures, r.stalls, r.ttffMs, r.atMs.toDouble()) })

    /** The records [json] holds; empty when it is blank or unreadable (a corrupt store just starts over). */
    fun decode(json: String?): Map<String, Record> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val raw: Map<String, List<Double>>? = gson.fromJson(json, storedType)
            raw.orEmpty().mapNotNull { (key, v) ->
                if (v.size < 5) null else key to Record(v[0], v[1], v[2], v[3], v[4].toLong())
            }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    // ---- The device's book: in memory, persisted by the installed writer. ----

    @Volatile
    private var records: Map<String, Record> = emptyMap()

    @Volatile
    private var persist: ((Map<String, Record>) -> Unit)? = null

    @VisibleForTesting
    internal var nowMs: () -> Long = { System.currentTimeMillis() }

    /** Loads the stored [stored] records (merged under any recorded since start) and sets how changes are saved. */
    fun install(stored: Map<String, Record>, save: (Map<String, Record>) -> Unit) {
        synchronized(this) { records = capped(stored + records) }
        persist = save
    }

    /** Records one play attempt's [outcome] for source [key]; [ttffMs] is a success's time to first frame. */
    fun record(key: String?, outcome: Outcome, ttffMs: Long? = null) {
        if (key.isNullOrBlank()) return
        val snapshot = synchronized(this) {
            records = capped(records + (key to update(records[key], outcome, nowMs(), ttffMs)))
            records
        }
        persist?.invoke(snapshot)
    }

    /** The current score of source [key]; [NEUTRAL] when unknown or null. */
    fun scoreOf(key: String?): Double = if (key == null) NEUTRAL else score(records[key], nowMs())

    /** Forgets everything recorded (Settings → Sports & Live → Reset stream memory). */
    fun clear() {
        synchronized(this) { records = emptyMap() }
        persist?.invoke(emptyMap())
    }

    @VisibleForTesting
    internal fun snapshot(): Map<String, Record> = records

    @VisibleForTesting
    internal fun reset() {
        synchronized(this) { records = emptyMap() }
        persist = null
        nowMs = { System.currentTimeMillis() }
    }
}
