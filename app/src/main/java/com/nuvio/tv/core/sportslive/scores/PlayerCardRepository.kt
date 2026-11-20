package com.nuvio.tv.core.sportslive.scores

import com.squareup.moshi.Moshi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** ESPN's athlete endpoints (#63). Never throws: a failure is null. */
interface PlayerCardSource {
    /** The athlete JSON (bio and season summary) of [athleteId] on [league], or null. */
    suspend fun athlete(league: EspnLeague, athleteId: String): String?

    /** The athlete's game log JSON, or null. */
    suspend fun gameLog(league: EspnLeague, athleteId: String): String?
}

/**
 * Player cards, fetched only when a card opens (the caller decides: under Scores Off not before Reveal),
 * the athlete and the game log in parallel, then kept in memory for [TTL_MS]. A failed bio is not kept,
 * so the next open tries again; a failed game log leaves the card without last games.
 */
@Singleton
class PlayerCardRepository internal constructor(
    private val source: PlayerCardSource,
    moshi: Moshi,
    private val now: () -> Long
) {
    @Inject
    constructor(source: PlayerCardSource, moshi: Moshi) : this(source, moshi, System::currentTimeMillis)

    private class Cached(val card: PlayerCard, val fetchedAt: Long)

    private val espnMoshi = EspnJson.guarded(moshi)
    private val athleteAdapter = espnMoshi.adapter(EspnAthleteResponseDto::class.java)
    private val logAdapter = espnMoshi.adapter(EspnGameLogDto::class.java)
    private val lock = Mutex()
    private val memory = LinkedHashMap<String, Cached>()

    suspend fun load(league: EspnLeague, athleteId: String): PlayerCard? {
        val key = "${league.path}/$athleteId"
        lock.withLock {
            memory[key]?.let { cached ->
                if (now() - cached.fetchedAt < TTL_MS) return cached.card
                memory.remove(key)
            }
        }
        val (athleteJson, logJson) = coroutineScope {
            val athlete = async { source.athlete(league, athleteId) }
            val log = async { source.gameLog(league, athleteId) }
            athlete.await() to log.await()
        }
        val athlete = athleteJson?.let { json -> runCatching { athleteAdapter.fromJson(json) }.getOrNull() } ?: return null
        val log = logJson?.let { json -> runCatching { logAdapter.fromJson(json) }.getOrNull() }
        val card = PlayerCards.parse(athlete, log, league.sport) ?: return null
        lock.withLock {
            if (memory.size >= MAX_IN_MEMORY) memory.remove(memory.keys.first())
            memory[key] = Cached(card, now())
        }
        return card
    }

    companion object {
        /** A card is reused for this long. */
        const val TTL_MS = 10 * 60_000L
        private const val MAX_IN_MEMORY = 20
    }
}
