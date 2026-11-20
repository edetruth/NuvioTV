package com.nuvio.tv.core.sportslive

import androidx.annotation.VisibleForTesting
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.domain.model.Stream
import kotlin.math.floor
import kotlin.math.pow

/**
 * Which links start fast (#147), learned on this device only. Each start, failure and probe is recorded for
 * its game and the addon that served the link ([Stream.addonName]), and for the addon across games, so a
 * new game starts from what the addon did lately. [order] puts the links that start first: start successes
 * (a smoothed success rate, in [TIERS] steps), then the median time to first frame. Evidence decays with a
 * [HALF_LIFE_MS] half-life, so an addon that was bad a few days ago can lead again. Nothing here makes a
 * request; no url is kept, only game ids and addon names.
 */
class SportsLiveLinkSpeed(internal var nowMs: () -> Long = { System.currentTimeMillis() }) {
    companion object {
        const val HALF_LIFE_MS = 3L * 24 * 60 * 60 * 1000

        /** A probe counts this much of a real start (it saw the manifest, not a frame). */
        const val PROBE_WEIGHT = 0.5

        /** The neutral prior: an addon with no history ranks between good and bad ones. */
        private const val PRIOR_RATE = 0.5
        private const val PRIOR_WEIGHT = 1.0

        /** Success rates are compared in steps of 1 / [TIERS]; within a step, the faster median first. */
        private const val TIERS = 5

        /** Times to first frame kept per key, the latest first. */
        private const val MAX_SAMPLES = 9

        /** A time to first frame older than this no longer counts toward the median. */
        private const val SAMPLE_MAX_AGE_MS = 3 * HALF_LIFE_MS
        const val MAX_ENTRIES = 400

        private const val ANY_GAME = "*"

        /** The device's book. */
        val shared = SportsLiveLinkSpeed()

        fun gameKey(gameId: String, addon: String): String = "$gameId|${addon.trim()}"
        fun addonKey(addon: String): String = gameKey(ANY_GAME, addon)

        private val gson = Gson()
        private val storedType = object : TypeToken<Map<String, List<Double>>>() {}.type

        /** A compact JSON form of [records]: key -> [successes, failures, atMs, then ttff/at pairs]. */
        fun encode(records: Map<String, Record>): String = gson.toJson(records.mapValues { (_, r) ->
            listOf(r.successes, r.failures, r.atMs.toDouble()) + r.samples.flatMap { listOf(it.ms.toDouble(), it.atMs.toDouble()) }
        })

        /** The records [json] holds; empty when blank or unreadable (a corrupt store starts over). */
        fun decode(json: String?): Map<String, Record> {
            if (json.isNullOrBlank()) return emptyMap()
            return try {
                val raw: Map<String, List<Double>>? = gson.fromJson(json, storedType)
                raw.orEmpty().mapNotNull { (key, v) ->
                    if (v.size < 3) return@mapNotNull null
                    val samples = v.drop(3).chunked(2).filter { it.size == 2 }.map { Sample(it[0].toLong(), it[1].toLong()) }
                    key to Record(v[0], v[1], v[2].toLong(), samples)
                }.toMap()
            } catch (_: Exception) {
                emptyMap()
            }
        }
    }

    /** A time to first frame [ms], recorded at [atMs]. */
    data class Sample(val ms: Long, val atMs: Long)

    /** One key's decayed success and failure weights as of [atMs], and its latest times to first frame. */
    data class Record(val successes: Double = 0.0, val failures: Double = 0.0, val atMs: Long = 0L, val samples: List<Sample> = emptyList()) {
        fun decayedTo(nowMs: Long): Record {
            val elapsed = nowMs - atMs
            if (elapsed <= 0) return this
            val factor = 0.5.pow(elapsed.toDouble() / HALF_LIFE_MS)
            return copy(successes = successes * factor, failures = failures * factor, atMs = nowMs)
        }
    }

    /** How a link of one addon is expected to start: its smoothed success [rate] and median time to first frame. */
    data class Rank(val rate: Double, val medianMs: Long?)

    private var records: Map<String, Record> = emptyMap()
    private var persist: ((Map<String, Record>) -> Unit)? = null

    /** A link of [addon] reached its first frame for game [gameId] after [ttffMs]. */
    fun recordStart(gameId: String?, addon: String?, ttffMs: Long?) =
        record(gameId, addon) { it.copy(successes = it.successes + 1, samples = withSample(it.samples, ttffMs)) }

    /** A link of [addon] failed to start, or failed, for game [gameId]. */
    fun recordFailure(gameId: String?, addon: String?) = record(gameId, addon) { it.copy(failures = it.failures + 1) }

    /** A probe of a link of [addon] for game [gameId] found it [ok] (a live media playlist) or dead (#147). */
    fun recordProbe(gameId: String?, addon: String?, ok: Boolean) = record(gameId, addon) {
        if (ok) it.copy(successes = it.successes + PROBE_WEIGHT) else it.copy(failures = it.failures + PROBE_WEIGHT)
    }

    /** How a link of [addon] is expected to start for game [gameId]: the game's own history, else the addon's. */
    fun rank(gameId: String, addon: String): Rank = synchronized(this) {
        val now = nowMs()
        val record = records[gameKey(gameId, addon)]?.takeIf { it.successes + it.failures > 0 } ?: records[addonKey(addon)]
        val decayed = record?.decayedTo(now)
        val rate = if (decayed == null) PRIOR_RATE else {
            (decayed.successes + PRIOR_RATE * PRIOR_WEIGHT) / (decayed.successes + decayed.failures + PRIOR_WEIGHT)
        }
        val recent = record?.samples.orEmpty().filter { now - it.atMs < SAMPLE_MAX_AGE_MS }.map { it.ms }.sorted()
        Rank(rate, recent.takeIf { it.isNotEmpty() }?.let { it[it.size / 2] })
    }

    /**
     * [streams] with the links expected to start first ahead (#147): by success tier, then by median time to
     * first frame (unknown after known); ties, and every link of one addon, keep their order.
     */
    fun order(gameId: String, streams: List<Stream>): List<Stream> {
        if (streams.size < 2) return streams
        val ranks = streams.map { it.addonName }.distinct().associateWith { rank(gameId, it) }
        return streams.sortedWith(
            compareByDescending<Stream> { floor(ranks.getValue(it.addonName).rate * TIERS) }
                .thenBy { ranks.getValue(it.addonName).medianMs ?: Long.MAX_VALUE }
        )
    }

    private fun withSample(samples: List<Sample>, ttffMs: Long?): List<Sample> =
        if (ttffMs == null || ttffMs < 0) samples else (listOf(Sample(ttffMs, nowMs())) + samples).take(MAX_SAMPLES)

    private fun record(gameId: String?, addon: String?, change: (Record) -> Record) {
        if (gameId.isNullOrBlank() || addon.isNullOrBlank()) return
        synchronized(this) {
            val now = nowMs()
            val updated = records.toMutableMap()
            for (key in listOf(gameKey(gameId, addon), addonKey(addon))) {
                updated[key] = change((updated[key] ?: Record(atMs = now)).decayedTo(now))
            }
            records = capped(updated)
            persist?.invoke(records)
        }
    }

    private fun capped(all: Map<String, Record>): Map<String, Record> =
        if (all.size <= MAX_ENTRIES) all else all.entries.sortedByDescending { it.value.atMs }.take(MAX_ENTRIES).associate { it.key to it.value }

    /** Loads [stored] (what was recorded since start wins for a key) and saves every change with [save] from now on. */
    fun install(stored: Map<String, Record>, save: (Map<String, Record>) -> Unit) {
        synchronized(this) {
            records = capped(stored + records)
            persist = save
            save(records)
        }
    }

    /** Forgets everything (Settings → Reset stream memory). */
    fun clear() {
        synchronized(this) {
            records = emptyMap()
            persist?.invoke(records)
        }
    }

    @VisibleForTesting
    internal fun snapshot(): Map<String, Record> = synchronized(this) { records }
}
