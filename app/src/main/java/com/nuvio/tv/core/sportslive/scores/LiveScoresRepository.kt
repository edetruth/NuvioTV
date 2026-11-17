package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.domain.model.SportsLiveItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Live and final scores for the cards on screen, keyed by card key (a merged card's PRIMARY
 * [SportsLiveItem.streamKey]; its alternates share that score).
 *
 * - Surfaces call [track] with the cards they show, and collect [scores]. Nothing is fetched while
 *   no one collects [scores] (collect it with the lifecycle, e.g. `collectAsStateWithLifecycle`), or
 *   while the Scores setting is Off.
 * - Cards are routed to ESPN feeds by [EspnLeagueMap] and grouped by (league, date): one poller per
 *   feed, shared by all its cards, at the [ScoreCadence] rules, with in-flight requests coalesced.
 * - The [ScoreDelay] is applied here, once, so every surface gets the delayed view.
 * - Failure-silent: a feed failing three times in a row is paused and its scores hidden until a success.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class LiveScoresRepository internal constructor(
    private val provider: ScoreProvider,
    /** The active profile's score settings. */
    val settings: Flow<ScoreSettings>,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val zone: () -> ZoneId,
    private val jitter: () -> Long
) {
    @Inject
    constructor(provider: ScoreProvider, prefs: SportsLivePreferencesDataStore) : this(
        provider = provider,
        settings = prefs.scoreSettings,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        now = System::currentTimeMillis,
        zone = ZoneId::systemDefault,
        jitter = { Random.nextLong(-ScoreCadence.JITTER_MS, ScoreCadence.JITTER_MS + 1) }
    )

    /** What the settings screen and surfaces read: Show / Hide until focused / Off. */
    val mode: Flow<ScoresMode> = settings.map { it.mode }.distinctUntilChanged()

    private val output = MutableStateFlow<Map<String, ScoreSnapshot>>(emptyMap())

    /** Delayed scores of the tracked cards that matched a game, by card key. Empty when Scores is Off. */
    val scores: StateFlow<Map<String, ScoreSnapshot>> = output.asStateFlow()

    private class TrackedCard(val card: ScoreCard, val leagues: List<EspnLeague>)

    private class FeedState {
        var events: List<ScoreSnapshot> = emptyList()
        var fetched = false
        var lastAttemptAt: Long? = null
        var jitterMs = 0L
        var failures = 0
        var retryAfterMs: Long? = null
        val paused: Boolean get() = failures >= ScoreCadence.PAUSE_AFTER_FAILURES
    }

    private val tracked = MutableStateFlow<List<TrackedCard>>(emptyList())

    // Guarded by [lock].
    private val lock = Mutex()
    private val feeds = HashMap<ScoreFeed, FeedState>()
    private val pollers = HashMap<ScoreFeed, Job>()
    private val inFlight = HashMap<ScoreFeed, Deferred<ScoreboardResult>>()
    /** A soccer/all card's own league, learned from its match, polled instead next time (by card key). */
    private val remembered = HashMap<String, EspnLeague>()
    private var raw: Map<String, ScoreSnapshot> = emptyMap()
    private var delayMs = 0L
    private val matcher = ScoreMatcher(zone)
    private val scoreDelay = ScoreDelay()
    private val nextRelease = MutableStateFlow<Long?>(null)

    init {
        scope.launch {
            output.subscriptionCount.map { it > 0 }.distinctUntilChanged().collectLatest { active ->
                if (active) run()
            }
        }
    }

    /**
     * The cards now on screen; replaces the previous set. Non-game cards and cards no feed covers are
     * ignored. A merged card is tracked by its primary and routed by the first source with a feed.
     */
    fun track(cards: List<SportsLiveItem>) {
        tracked.value = cards.mapNotNull { item ->
            val card = ScoreCard.from(item) ?: return@mapNotNull null
            val leagues = (listOf(item) + item.alternates).asSequence()
                .map(EspnLeagueMap::leaguesFor).firstOrNull { it.isNotEmpty() } ?: return@mapNotNull null
            TrackedCard(card, leagues)
        }.distinctBy { it.card.key }
    }

    // While someone collects [scores]: settings, then the tracked cards, drive the pollers.
    private suspend fun run() {
        settings.distinctUntilChanged().collectLatest { current ->
            if (current.mode == ScoresMode.OFF) {
                lock.withLock { publishLocked(off = true) }
                return@collectLatest
            }
            coroutineScope {
                val pollerScope = this
                lock.withLock { delayMs = current.delaySeconds * 1_000L }
                launch {
                    nextRelease.collectLatest { at ->
                        if (at == null) return@collectLatest
                        delay((at - now()).coerceAtLeast(0))
                        lock.withLock { publishLocked() }
                    }
                }
                tracked.collect { lock.withLock { refreshLocked(pollerScope, restartStopped = true) } }
            }
        }
    }

    private suspend fun poll(feed: ScoreFeed, pollerScope: CoroutineScope) {
        while (true) {
            val wait = lock.withLock { waitLocked(feed) } ?: return
            if (wait > 0) delay(wait)
            val request = lock.withLock {
                inFlight.getOrPut(feed) {
                    // In the repository scope: a request outlives a poller restart and is shared.
                    scope.async {
                        val result = provider.scoreboard(feed.league, feed.date)
                        lock.withLock {
                            inFlight.remove(feed)
                            feeds[feed]?.let { record(it, result) }
                        }
                        result
                    }
                }
            }
            request.await()
            lock.withLock { refreshLocked(pollerScope, restartStopped = false) }
        }
    }

    private fun record(state: FeedState, result: ScoreboardResult) {
        state.lastAttemptAt = now()
        state.jitterMs = jitter()
        if (result.failed) {
            state.failures++
            state.retryAfterMs = result.retryAfterMs
            if (state.paused) state.events = emptyList()
        } else {
            state.failures = 0
            state.retryAfterMs = null
            state.events = result.events
            state.fetched = true
        }
    }

    /** Millis to wait before [feed]'s next fetch, or null when it stops (or is no longer needed). */
    private fun waitLocked(feed: ScoreFeed): Long? {
        val state = feeds[feed] ?: return null
        val last = state.lastAttemptAt ?: return 0L
        val eventIds = state.events.mapTo(HashSet()) { it.eventId }
        val interval = ScoreCadence.nextIntervalMs(
            feed = feed,
            matched = raw.values.filter { it.eventId in eventIds }.distinctBy { it.eventId },
            events = state.events,
            failures = state.failures,
            retryAfterMs = state.retryAfterMs,
            today = today(),
            nowMs = now()
        ) ?: return null
        return (last + interval + state.jitterMs - now()).coerceAtLeast(0L)
    }

    /**
     * Routes and matches every tracked card, starts pollers for new feeds (and, after [track] or a
     * restart, re-evaluates stopped ones), cancels pollers no card needs, and publishes.
     */
    private fun refreshLocked(pollerScope: CoroutineScope, restartStopped: Boolean) {
        val today = today()
        val needed = HashSet<ScoreFeed>()
        val matched = HashMap<String, ScoreSnapshot>()
        for (entry in tracked.value) {
            val card = entry.card
            val dates = datesFor(card, today)
            val leagues = remembered[card.key]?.let(::listOf) ?: entry.leagues
            val cardFeeds = feedsOf(leagues, dates).toMutableList()
            // A remembered league keeps soccer/all until its own feed has data.
            if (card.key in remembered && cardFeeds.any { feeds[it]?.fetched != true }) {
                cardFeeds += feedsOf(listOf(EspnLeagueMap.SOCCER_ALL), dates)
            }
            var match = matchOn(card, cardFeeds)
            // A soccer competition feed without the game: try soccer/all (shared, at its own cadence).
            if (match == null && leagues.all { it.sport == "soccer" && it != EspnLeagueMap.SOCCER_ALL } &&
                cardFeeds.all { feed -> feeds[feed]?.let { it.fetched && !it.paused } == true }
            ) {
                cardFeeds += feedsOf(listOf(EspnLeagueMap.SOCCER_ALL), dates)
                match = matchOn(card, cardFeeds)
            }
            needed += cardFeeds
            if (match == null) continue
            matched[card.key] = match
            if (match.league == EspnLeagueMap.SOCCER_ALL && match.leagueSlug != EspnLeagueMap.SOCCER_ALL.league &&
                SLUG.matches(match.leagueSlug) && card.key !in remembered
            ) {
                if (remembered.size >= MAX_REMEMBERED) remembered.clear()
                val own = EspnLeague("soccer", match.leagueSlug)
                remembered[card.key] = own
                // Its own feed starts now; soccer/all stays for this card until that feed has data.
                needed += feedsOf(listOf(own), dates)
            }
        }
        raw = matched

        // Feeds no card needs: stop and forget.
        (feeds.keys - needed).forEach { feed ->
            feeds.remove(feed)
            pollers.remove(feed)?.cancel()
        }
        for (feed in needed) {
            val isNew = feed !in feeds
            if (isNew) feeds[feed] = FeedState()
            if (pollers[feed]?.isActive == true) continue
            if (isNew || restartStopped) pollers[feed] = pollerScope.launch { poll(feed, pollerScope) }
        }
        publishLocked()
    }

    private fun matchOn(card: ScoreCard, cardFeeds: List<ScoreFeed>): ScoreSnapshot? {
        val events = cardFeeds.flatMap { feeds[it]?.events.orEmpty() }.distinctBy { it.eventId }
        return if (events.isEmpty()) null else matcher.match(card, events, now())
    }

    private fun publishLocked(off: Boolean = false) {
        if (off) {
            scoreDelay.apply(emptyMap(), 0L, now())
            nextRelease.value = null
            output.value = emptyMap()
            return
        }
        output.value = scoreDelay.apply(raw, delayMs, now())
        nextRelease.value = scoreDelay.nextChangeAt
    }

    private fun today(): LocalDate = Instant.ofEpochMilli(now()).atZone(zone()).toLocalDate()

    /**
     * The scoreboard dates for [card]. ESPN files games under their US Eastern date, so a timed card
     * asks for the Eastern dates within ±3 h of its start (the neighbouring one near Eastern midnight).
     * A bare date asks for that date; no start: today and yesterday, by the device's and by Eastern days.
     * ESPN rejects `dates=A-B` ranges (HTTP 400, checked 2026-11-17), so each date is its own request.
     */
    internal fun datesFor(card: ScoreCard, today: LocalDate): List<LocalDate> {
        val start = card.startMs
        if (start == null) {
            val easternToday = Instant.ofEpochMilli(now()).atZone(EASTERN).toLocalDate()
            return listOf(today, today.minusDays(1), easternToday, easternToday.minusDays(1)).distinct()
        }
        if (!card.hasTimeOfDay) return listOf(Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate())
        return listOf(
            Instant.ofEpochMilli(start - WINDOW_MS).atZone(EASTERN).toLocalDate(),
            Instant.ofEpochMilli(start + WINDOW_MS).atZone(EASTERN).toLocalDate()
        ).distinct()
    }

    private fun feedsOf(leagues: List<EspnLeague>, dates: List<LocalDate>): List<ScoreFeed> =
        leagues.flatMap { league -> dates.map { ScoreFeed(league, it) } }

    private companion object {
        const val WINDOW_MS = 3 * 60 * 60 * 1000L
        const val MAX_REMEMBERED = 2_000
        val SLUG = Regex("""[a-z0-9._-]+""")
        val EASTERN: ZoneId = ZoneId.of("America/New_York")
    }
}
