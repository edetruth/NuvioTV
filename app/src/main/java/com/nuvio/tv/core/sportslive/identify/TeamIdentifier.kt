package com.nuvio.tv.core.sportslive.identify

import android.content.Context
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.domain.model.SportsLiveItem
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Identifies addon games whose sport is unknown by looking both teams up in the background (#168). A game
 * is offered while it is live or starts today; both side names are asked of ESPN's search, then of
 * TheSportsDB for a name ESPN has no team for, one request at a time through the [TeamLookupRateLimiter].
 * What the sources say is kept on the device ([TeamIdentityCache]). Once both sides are known, the game's
 * agreed identity ([TeamIdentityRules.agree]), or none, goes into the [book]. When the sides do not agree,
 * one sport-hinted look per side may follow ([TeamIdentityRules.hintSport]). Only team names are sent.
 *
 * The worker only works after an offer: when it is paused or blocked it stops, and the next offer (a list
 * rebuild) wakes it again.
 */
@Singleton
class TeamIdentifier internal constructor(
    private val espn: TeamLookupSource,
    private val sportsDb: TeamLookupSource,
    private val store: TeamIdentityStore?,
    private val book: TeamIdentityBook,
    private val now: () -> Long,
    dispatcher: CoroutineDispatcher,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val limiter: TeamLookupRateLimiter = TeamLookupRateLimiter(now)
) {

    @Inject
    constructor(okHttpClient: OkHttpClient, moshi: Moshi, @ApplicationContext context: Context) : this(
        espn = EspnTeamSearch(okHttpClient, moshi),
        sportsDb = SportsDbTeamSearch(okHttpClient, moshi),
        store = TeamIdentityStore(File(context.filesDir, STORE_PATH), moshi),
        book = TeamIdentityBook.shared,
        now = System::currentTimeMillis,
        dispatcher = Dispatchers.IO
    )

    private class Game(val first: String, val second: String)

    private val cache = TeamIdentityCache()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val started = AtomicBoolean(false)

    /** Offered games not answered yet, by [TeamIdentityBook.pairKey]; only the worker removes them. */
    private val pending = LinkedHashMap<String, Game>()

    // Worker-only state.
    private var loaded = false
    private var dirty = false

    /**
     * Queues the two-team games of [items] whose sport is unknown and that are live or start today, and soccer games
     * naming no competition (item 3) that are live or start within 3 h. Cheap
     * and non-blocking: the lookups run on the worker.
     */
    fun offer(items: List<SportsLiveItem>) {
        if (items.isEmpty()) return
        val at = now()
        val zoneId = zone()
        val day = Instant.ofEpochMilli(at).atZone(zoneId).toLocalDate()
        val dayStart = day.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val dayEnd = day.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        for (item in items) {
            if (item.isChannel || item.teams == null) continue
            val start = item.startMs
            if (!item.isLive && (start == null || start < dayStart || start >= dayEnd)) continue
            val unknownSport = SportsLiveTeamIdentity.sportUnknown(item)
            // A soccer game naming no competition (item 3): only while live or starting within 3 h.
            val soon = item.isLive || (start != null && start >= at && start - at <= SOCCER_LOOKUP_AHEAD_MS)
            if (!unknownSport && !(soon && SportsLiveTeamIdentity.soccerLeagueUnknown(item))) continue
            val (first, second) = SportsLiveTeamIdentity.sides(item.meta.name) ?: continue
            offerGame(first, second)
        }
        val any = synchronized(pending) { pending.isNotEmpty() }
        if (any) wakeWorker()
    }

    /** Queues the game between [first] and [second] (bounded; already answered or queued: nothing). */
    internal fun offerGame(first: String, second: String) {
        val a = TeamIdentityRules.nameKey(first)
        val b = TeamIdentityRules.nameKey(second)
        if (a.isEmpty() || b.isEmpty() || a == b) return
        if (book.has(first, second)) return
        val key = TeamIdentityBook.pairKey(a, b)
        synchronized(pending) {
            if (key in pending || pending.size >= MAX_PENDING) return
            pending[key] = Game(first, second)
        }
    }

    internal fun wakeWorker() {
        if (started.compareAndSet(false, true)) {
            scope.launch {
                for (signal in wake) {
                    // A surprise in one pass never ends the worker.
                    try {
                        drain()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The next offer tries again.
                    }
                }
            }
        }
        wake.trySend(Unit)
    }

    /** Passes over the queued games while they move on; stops when a pass gets nowhere. */
    private suspend fun drain() {
        if (!loaded) {
            loaded = true
            store?.read()?.let(cache::restore)
        }
        while (true) {
            val games = synchronized(pending) { pending.entries.map { it.key to it.value } }
            if (games.isEmpty()) break
            var progressed = false
            for ((key, game) in games) {
                val step = process(game)
                if (step.done) synchronized(pending) { pending.remove(key) }
                if (step.done || step.progressed) progressed = true
            }
            if (dirty) {
                dirty = false
                store?.write(cache.snapshot())
            }
            if (!progressed) break
        }
    }

    private class Step(val done: Boolean, val progressed: Boolean)

    private suspend fun process(game: Game): Step {
        val learnedBefore = learned
        val first = sideOf(game.first) ?: return Step(false, learned > learnedBefore)
        val second = sideOf(game.second) ?: return Step(false, learned > learnedBefore)
        var identity = TeamIdentityRules.agree(first, second)
        if (identity == null) {
            val sport = TeamIdentityRules.hintSport(game.first, first, game.second, second)
            if (sport != null) {
                for (side in listOf(game.first, game.second)) {
                    val own = cache.answer(side, now()).orEmpty()
                    if (own.any { it.sport == sport } || sport in cache.hinted(side)) continue
                    if (!hint(side, sport)) return Step(false, learned > learnedBefore)
                }
                identity = TeamIdentityRules.agree(
                    cache.answer(game.first, now()).orEmpty(),
                    cache.answer(game.second, now()).orEmpty()
                )
            }
        }
        book.put(game.first, game.second, identity)
        // Debug builds: what the lookup made of the game (team names and the answer only).
        if (BuildConfig.IS_DEBUG_BUILD) {
            Log.d(LOG_TAG, "\"${game.first}\" vs \"${game.second}\": ${identity?.let { "${it.sport} / ${it.league}" } ?: "unknown"}")
        }
        return Step(true, true)
    }

    /** Answers recorded so far: a pass that recorded one moved on (a request alone does not). */
    private var learned = 0

    /** Name keys ESPN had no team for this session: a retry after a failed TheSportsDB look asks that alone. */
    private val espnNoTeam = HashSet<String>()

    /** [name]'s teams, from the cache or looked up; null when not now (blocked or failed). */
    private suspend fun sideOf(name: String): List<TeamCandidate>? {
        cache.answer(name, now())?.let { return it }
        val key = TeamIdentityRules.nameKey(name)
        var found = emptyList<TeamCandidate>()
        if (key !in espnNoTeam) {
            val fromEspn = request { espn.search(name) } ?: return null
            found = fromEspn.filter { TeamIdentityRules.answersTo(it, key) }
            if (found.isEmpty()) {
                if (espnNoTeam.size >= MAX_ESPN_MISSES) espnNoTeam.clear()
                espnNoTeam += key
            }
        }
        if (found.isEmpty()) {
            val fromSportsDb = request { sportsDb.search(name) } ?: return null
            found = fromSportsDb.filter { TeamIdentityRules.answersTo(it, key) }
        }
        espnNoTeam -= key
        cache.record(name, found, now())
        dirty = true
        learned++
        return cache.answer(name, now()) ?: found
    }

    /**
     * One hinted look for [name] in [sport] on TheSportsDB ("Maccabi Tel Aviv" → "Maccabi Tel Aviv BC"):
     * true when it was answered (found or not), false when not now.
     */
    private suspend fun hint(name: String, sport: String): Boolean {
        val base = TeamIdentityRules.withoutMarkers(name)
        val baseKey = TeamIdentityRules.nameKey(base)
        var found = emptyList<TeamCandidate>()
        if (baseKey.isNotEmpty()) {
            for (suffix in TeamIdentityRules.HINT_SUFFIXES[sport].orEmpty()) {
                val answer = request { sportsDb.search("$base $suffix") } ?: return false
                found = answer.filter { it.sport == sport && TeamIdentityRules.answersTo(it, baseKey) }
                if (found.isNotEmpty()) break
            }
        }
        cache.recordHint(name, sport, found, now())
        dirty = true
        learned++
        return true
    }

    /** One request through the limiter; null when it may not go now or it failed. Short waits are waited out. */
    private suspend fun request(search: suspend () -> TeamSearchResult): List<TeamCandidate>? {
        var wait = limiter.waitMs()
        while (wait > 0) {
            if (wait > limiter.spacingMs) return null
            delay(wait)
            wait = limiter.waitMs()
        }
        limiter.onRequest()
        return when (val result = search()) {
            is TeamSearchResult.Found -> {
                limiter.onSuccess()
                result.candidates
            }
            is TeamSearchResult.Failed -> {
                limiter.onFailure(result.rateLimited)
                null
            }
        }
    }

    companion object {
        private const val STORE_PATH = "sportslive/team_identity.json"
        private const val LOG_TAG = "NuvioIdentify"
        /** Games queued at most; later offers come back on the next rebuild. */
        private const val MAX_PENDING = 128
        /** How far ahead a soccer game naming no competition is looked up (item 3). */
        private const val SOCCER_LOOKUP_AHEAD_MS = 3 * 60 * 60_000L
        private const val MAX_ESPN_MISSES = 512
    }
}
