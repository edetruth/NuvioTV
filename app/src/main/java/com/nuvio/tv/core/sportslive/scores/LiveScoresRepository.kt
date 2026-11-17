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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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
 * - Each surface calls [track] with its own screen id and the cards it shows, and collects [scores].
 *   The repository polls the union of every surface's cards, so Home and the section never replace
 *   each other's set. Nothing is fetched while
 *   no one collects [scores] (collect it with the lifecycle, e.g. `collectAsStateWithLifecycle`), or
 *   while the Scores setting is Off.
 * - Cards are routed to ESPN feeds by [EspnLeagueMap] and grouped by (league, date): one poller per
 *   feed, shared by all its cards, at the [ScoreCadence] rules, with in-flight requests coalesced.
 * - The [ScoreDelay] is applied here, once, so every surface gets the delayed view.
 * - Failure-silent: a feed failing three times in a row is paused and its scores hidden until a success.
 * - Scope: followed teams' games always; other games only when their league is in My sports (#55).
 *   At most [MAX_FEEDS] feeds poll at once, followed teams' games claiming theirs first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class LiveScoresRepository internal constructor(
    private val provider: ScoreProvider,
    /** The active profile's score settings. */
    val settings: Flow<ScoreSettings>,
    private val scope: CoroutineScope,
    /** My sports (#55) with followed teams' sports added: which leagues' games get scores. */
    private val mySports: Flow<MySports> = flowOf(MySports.DEFAULT),
    private val now: () -> Long,
    private val zone: () -> ZoneId,
    private val jitter: () -> Long
) : LiveScoreTracking {
    @Inject
    constructor(provider: ScoreProvider, prefs: SportsLivePreferencesDataStore) : this(
        provider = provider,
        settings = prefs.scoreSettings,
        mySports = combine(prefs.mySports, prefs.followedTeams) { sports, followed -> sports.withFollowed(followed) },
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

    private class TrackedCard(val card: ScoreCard, val leagues: List<EspnLeague>, val followed: Boolean)

    private class FeedState {
        var events: List<ScoreSnapshot> = emptyList()
        var fetched = false
        var lastAttemptAt: Long? = null
        /** When the last successful fetch landed. */
        var lastSuccessAt: Long? = null
        /** The polling interval last worked out for the feed; null once it stopped (all final, past day). */
        var intervalMs: Long? = null
        var jitterMs = 0L
        var failures = 0
        var retryAfterMs: Long? = null
        /** When no card needed it any more; it is kept a while so a re-track waits out its interval. */
        var retiredAt: Long? = null
        val paused: Boolean get() = failures >= ScoreCadence.PAUSE_AFTER_FAILURES
    }

    /** Each surface's tracked cards, by screen id; the pollers serve their union. */
    private val tracked = MutableStateFlow<Map<String, List<TrackedCard>>>(emptyMap())

    // Guarded by [lock].
    private val lock = Mutex()
    private val feeds = HashMap<ScoreFeed, FeedState>()
    private val pollers = HashMap<ScoreFeed, Job>()
    private val inFlight = HashMap<ScoreFeed, Deferred<ScoreboardResult>>()
    /** A soccer/all card's own league, learned from its match, polled instead next time (by card key). */
    private val remembered = HashMap<String, EspnLeague>()
    private var raw: Map<String, ScoreSnapshot> = emptyMap()
    private var delayMs = 0L
    private var sports: MySports = MySports.DEFAULT
    /** Matched cards whose game is outside My sports: skipped (no feeds) until My sports changes. */
    private val outOfScope = HashSet<String>()
    private val matcher = ScoreMatcher(zone)
    private val scoreDelay = ScoreDelay()
    private val nextRelease = MutableStateFlow<Long?>(null)

    init {
        scope.launch {
            output.subscriptionCount.map { it > 0 }.distinctUntilChanged().collectLatest { active ->
                if (active) {
                    run()
                } else {
                    // No one looks: drop the shown scores, so a screen coming back never shows the
                    // score from when polling stopped (fresh fetches bring them back).
                    lock.withLock {
                        raw = emptyMap()
                        nextRelease.value = null
                        output.value = emptyMap()
                    }
                }
            }
        }
    }

    /**
     * The cards now on [screenId] (e.g. [SCREEN_HOME], [SCREEN_SECTION]); replaces that screen's
     * previous set only. An empty list stops tracking for the screen. Non-game cards and cards no feed
     * covers are ignored. A merged card is tracked by its primary and routed by the first source with
     * a feed.
     */
    override fun track(screenId: String, cards: List<SportsLiveItem>) {
        val entries = cards.mapNotNull { item ->
            val card = ScoreCard.from(item) ?: return@mapNotNull null
            val leagues = (listOf(item) + item.alternates).asSequence()
                .map(EspnLeagueMap::leaguesFor).firstOrNull { it.isNotEmpty() } ?: return@mapNotNull null
            TrackedCard(card, leagues, item.followed)
        }.distinctBy { it.card.key }
        tracked.update { current -> if (entries.isEmpty()) current - screenId else current + (screenId to entries) }
    }

    /**
     * Stops tracking for [screenId]: call it when the screen is disposed (S4: from a DisposableEffect's
     * onDispose), so a closed screen's cards never keep a feed polling. The other screens are unaffected.
     */
    override fun untrack(screenId: String) {
        tracked.update { it - screenId }
    }

    /**
     * Every screen's tracked cards, one entry per card key (the same card on two screens is one),
     * followed teams' games first: they claim the feed budget before the rest.
     */
    private fun trackedUnion(): List<TrackedCard> =
        tracked.value.values.flatten().distinctBy { it.card.key }.sortedByDescending { it.followed }

    // While someone collects [scores]: settings, then the tracked cards, drive the pollers.
    private suspend fun run() {
        combine(settings.distinctUntilChanged(), mySports.distinctUntilChanged(), ::Pair).collectLatest { (current, picked) ->
            if (current.mode == ScoresMode.OFF) {
                lock.withLock { publishLocked(off = true) }
                return@collectLatest
            }
            coroutineScope {
                val pollerScope = this
                lock.withLock {
                    delayMs = current.delaySeconds * 1_000L
                    if (picked != sports) outOfScope.clear()
                    sports = picked
                }
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
            state.lastSuccessAt = state.lastAttemptAt
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
        )
        state.intervalMs = interval
        if (interval == null) return null
        return (last + interval + state.jitterMs - now()).coerceAtLeast(0L)
    }

    /**
     * Routes and matches every tracked card, starts pollers for new feeds (and, after [track] or a
     * restart, re-evaluates stopped ones), cancels pollers no card needs, and publishes.
     */
    private fun refreshLocked(pollerScope: CoroutineScope, restartStopped: Boolean) {
        val today = today()
        // In card order (followed first), so the budget goes to followed teams' feeds first.
        val needed = LinkedHashSet<ScoreFeed>()
        fun fits(extra: Collection<ScoreFeed>) = needed.size + extra.count { it !in needed } <= MAX_FEEDS
        val matched = HashMap<String, ScoreSnapshot>()
        for (entry in trackedUnion()) {
            val card = entry.card
            if (card.key in outOfScope && !entry.followed) continue
            val dates = datesFor(card, today)
            val leagues = remembered[card.key]?.let(::listOf)
                ?: if (entry.followed) entry.leagues else entry.leagues.filter(::inMySports)
            if (leagues.isEmpty()) continue
            val cardFeeds = feedsOf(leagues, dates).toMutableList()
            // A remembered league keeps soccer/all until its own feed has data.
            if (card.key in remembered && cardFeeds.any { feeds[it]?.fetched != true }) {
                cardFeeds += feedsOf(listOf(EspnLeagueMap.SOCCER_ALL), dates)
            }
            // Over the budget: a card whose feeds are not polled already gets no score (never a stale one).
            if (!fits(cardFeeds)) continue
            var match = matchOn(card, cardFeeds)
            // A soccer competition feed without the game: try soccer/all (shared, at its own cadence).
            if (match == null && leagues.all { it.sport == "soccer" && it != EspnLeagueMap.SOCCER_ALL } &&
                cardFeeds.all { feed -> feeds[feed]?.let { it.fetched && !it.paused } == true }
            ) {
                val fallback = feedsOf(listOf(EspnLeagueMap.SOCCER_ALL), dates)
                if (fits(cardFeeds + fallback)) {
                    cardFeeds += fallback
                    match = matchOn(card, cardFeeds)
                }
            }
            if (match == null) {
                needed += cardFeeds
                continue
            }
            // A game outside My sports (a soccer competition not picked) shows no score and stops polling.
            if (!entry.followed && !sports.includes(match)) {
                if (outOfScope.size >= MAX_REMEMBERED) outOfScope.clear()
                outOfScope += card.key
                continue
            }
            // Matched: only the feeds holding the game (and any not fetched yet) keep polling, so a
            // late game does not also poll the next Eastern day all night.
            needed += cardFeeds.filter { feed ->
                val state = feeds[feed]
                state == null || !state.fetched || state.events.any { it.eventId == match.eventId }
            }
            matched[card.key] = match
            if (match.league == EspnLeagueMap.SOCCER_ALL && match.leagueSlug != EspnLeagueMap.SOCCER_ALL.league &&
                SLUG.matches(match.leagueSlug) && card.key !in remembered
            ) {
                if (remembered.size >= MAX_REMEMBERED) remembered.clear()
                val own = EspnLeague("soccer", match.leagueSlug)
                remembered[card.key] = own
                // Its own feed starts now (budget allowing); soccer/all stays for this card until that feed has data.
                feedsOf(listOf(own), dates).let { if (fits(it)) needed += it }
            }
        }
        raw = matched

        // Feeds no card needs: stop polling, but keep their state (last fetch, failures, Retry-After)
        // for a while, so switching between Home and the section does not refetch at once.
        val nowMs = now()
        (feeds.keys - needed).forEach { feed ->
            pollers.remove(feed)?.cancel()
            feeds.getValue(feed).let { if (it.retiredAt == null) it.retiredAt = nowMs }
        }
        feeds.values.removeAll { state -> state.retiredAt?.let { nowMs - it > RETAIN_RETIRED_MS } == true }
        for (feed in needed) {
            val state = feeds[feed]
            val revived = state?.retiredAt != null
            if (state == null) feeds[feed] = FeedState() else state.retiredAt = null
            if (pollers[feed]?.isActive == true) continue
            if (state == null || revived || restartStopped) pollers[feed] = pollerScope.launch { poll(feed, pollerScope) }
        }
        publishLocked()
    }

    /** True when [league]'s games can be in My sports: its sport is in, and (beyond soccer) the league too. */
    private fun inMySports(league: EspnLeague): Boolean {
        val sport = MySport.ofEspn(league.sport) ?: return false
        if (sport !in sports.effectiveSports) return false
        if (sport == MySport.SOCCER) return true
        return MyLeague.ofFeed(league)?.let { it in sports.leaguesOf(sport) } ?: false
    }

    private fun matchOn(card: ScoreCard, cardFeeds: List<ScoreFeed>): ScoreSnapshot? {
        val nowMs = now()
        val events = cardFeeds.flatMap { feed -> feeds[feed]?.takeIf { it.isFresh(nowMs) }?.events.orEmpty() }
            .distinctBy { it.eventId }
        return if (events.isEmpty()) null else matcher.match(card, events, now())
    }

    /**
     * True when the feed's events may be shown: fetched no longer ago than twice its polling interval
     * (plus jitter). A feed that stopped polling (all final, a past day) keeps its events. After a pause
     * (the player, Home) a live feed is stale until it is fetched again, so no old score shows meanwhile.
     */
    private fun FeedState.isFresh(nowMs: Long): Boolean {
        val success = lastSuccessAt ?: return false
        val interval = intervalMs ?: return true
        return nowMs - success <= 2 * interval + ScoreCadence.JITTER_MS
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

    companion object {
        /** [track] screen ids. */
        const val SCREEN_HOME = "home"
        const val SCREEN_SECTION = "sports_live"

        private const val WINDOW_MS = 3 * 60 * 60 * 1000L
        private const val MAX_REMEMBERED = 2_000
        /** Feeds polled at once, at most; followed teams' games claim theirs first. */
        internal const val MAX_FEEDS = 16
        private const val RETAIN_RETIRED_MS = 10 * 60_000L
        private val SLUG = Regex("""[a-z0-9._-]+""")
        private val EASTERN: ZoneId = ZoneId.of("America/New_York")
    }
}

/** What a screen calls to have its cards scored: [track] the cards it shows, [untrack] when it leaves. */
interface LiveScoreTracking {
    fun track(screenId: String, cards: List<SportsLiveItem>)
    fun untrack(screenId: String)
}
