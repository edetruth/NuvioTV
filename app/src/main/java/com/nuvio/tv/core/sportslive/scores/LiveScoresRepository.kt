package com.nuvio.tv.core.sportslive.scores

import android.content.Context
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.nuvio.tv.R
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import com.nuvio.tv.domain.model.SportsLiveItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext
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
 * - Scope (#66): every tracked card on screen, whatever My sports says; My sports only orders the
 *   feed budget. At most [MAX_FEEDS] feeds poll at once (in parallel): followed teams' games claim
 *   theirs first, then games in My sports. A card routed by a bare "Basketball" / "Football" that its
 *   pro feed does not list also tries the college feed ([EspnLeagueMap.collegeFallbackFor]), only while
 *   it is tracked.
 * - Logos (#66): each matched side's logo is learned into [logos] (kept in app storage) so cards draw
 *   it on the next launch before any feed answers, and tracked games' logos are prefetched.
 * - Warm start (#66): a feed's first fetch after a restart is preceded by the provider's copy of it
 *   from the last two minutes, if any ([ScoreProvider.recentScoreboard]); the network fetch follows at once.
 * - Health (#70): after a failed fetch, if every ESPN feed in use has failed for 10 minutes while the
 *   section or the player panel is tracked, [notifyOutage] is called, once per app session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class LiveScoresRepository internal constructor(
    private val provider: ScoreProvider,
    /** The active profile's score settings. */
    val settings: Flow<ScoreSettings>,
    private val scope: CoroutineScope,
    /** My sports (#55) with followed teams' sports added: which games claim the feed budget first. */
    private val mySports: Flow<MySports> = flowOf(MySports.DEFAULT),
    private val now: () -> Long,
    private val zone: () -> ZoneId,
    private val jitter: () -> Long,
    /** Team logos learned from matches (#66). */
    val logos: TeamLogoBook = TeamLogoBook(),
    /** Where [logos] is kept; null: memory only. */
    private val logoFile: TeamLogoFile? = null,
    /** Loads logo URLs into the image disk cache. */
    private val prefetch: (List<String>) -> Unit = {},
    /** Where the logo file is read and written. */
    private val io: CoroutineContext = Dispatchers.IO,
    /** ESPN feed health (#70): read for the outage toast and "Retry now". */
    private val health: EspnFeedHealth = EspnFeedHealth(now),
    /** Shows the one-time "Live scores aren't available right now" toast. */
    private val notifyOutage: () -> Unit = {}
) : LiveScoreTracking {
    @Inject
    constructor(
        provider: ScoreProvider,
        prefs: SportsLivePreferencesDataStore,
        @ApplicationContext context: Context,
        health: EspnFeedHealth
    ) : this(
        provider = provider,
        settings = prefs.scoreSettings,
        mySports = combine(prefs.mySports, prefs.followedTeams) { sports, followed -> sports.withFollowed(followed) },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        now = System::currentTimeMillis,
        zone = ZoneId::systemDefault,
        jitter = { Random.nextLong(-ScoreCadence.JITTER_MS, ScoreCadence.JITTER_MS + 1) },
        logos = TeamLogoBook.shared,
        logoFile = TeamLogoFile(File(context.filesDir, "sportslive/team_logos.tsv")),
        prefetch = { urls -> TeamLogoPrefetch.enqueue(context, urls) },
        health = health,
        notifyOutage = {
            ContextCompat.getMainExecutor(context).execute {
                Toast.makeText(context, R.string.sports_live_scores_outage_toast, Toast.LENGTH_LONG).show()
            }
        }
    )

    /** What the settings screen and surfaces read: Show / Hide until focused / Off. */
    val mode: Flow<ScoresMode> = settings.map { it.mode }.distinctUntilChanged()

    private val output = MutableStateFlow<Map<String, ScoreSnapshot>>(emptyMap())

    /** Delayed scores of the tracked cards that matched a game, by card key. Empty when Scores is Off. */
    val scores: StateFlow<Map<String, ScoreSnapshot>> = output.asStateFlow()

    private val unmatchedOutput = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Card keys confirmed to get no score (#60): every feed they route to has answered (or is paused)
     * without their game (its college feed too, for a bare "Basketball" / "Football" card). Remembered
     * while the card is not tracked; a card that later matches leaves it. Only these cards (and cards
     * no feed covers, or every card while Scores is Off) may be estimated as ended.
     */
    val unmatched: StateFlow<Set<String>> = unmatchedOutput.asStateFlow()

    private val logosLoadedFlow = MutableStateFlow(logoFile == null)

    /**
     * True once the kept logo map has been read into [logos] (at once with no file): the player panel
     * waits for it, so its rows never draw letters for teams known from an earlier run (I3).
     */
    val logosLoaded: StateFlow<Boolean> = logosLoadedFlow.asStateFlow()

    /** A card waiting for its college fallback until every card's own feeds are budgeted. */
    private class Deferral(val entry: TrackedCard, val dates: List<LocalDate>, val cardFeeds: List<ScoreFeed>, val fallback: EspnLeague)

    private class TrackedCard(
        val card: ScoreCard,
        val leagues: List<EspnLeague>,
        val followed: Boolean,
        /** The college feed to try when [leagues] answer without the game (#66); null for most cards. */
        val fallback: EspnLeague?
    )

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
        /** The warm-start copy was looked for (once per feed state). */
        var warmTried = false
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
    /** Logo URLs already handed to [prefetch]. */
    private val prefetched = HashSet<String>()
    private var logoWrite: Job? = null
    /** See [unmatched]. */
    private val unmatchedKeys = HashSet<String>()
    private val matcher = ScoreMatcher(zone)
    /** For the college fallback: both sides must match STRONG (a plain card's sport is only a guess). */
    private val strictMatcher = ScoreMatcher(zone, minScore = ScoreMatcher.BOTH_STRONG)
    /**
     * Card key to (the college fetch last counted, consecutive college fetches without the game): after
     * [COLLEGE_MAX_MISSES] the card's college feed is dropped until the tracked cards change.
     */
    private val collegeMisses = HashMap<String, Pair<Long, Int>>()
    @Volatile
    private var logosDirty = false
    private val scoreDelay = ScoreDelay()
    private val nextRelease = MutableStateFlow<Long?>(null)
    private val outage = ScoresOutageNotice(health)
    /** The running pollers' scope while someone collects [scores] and Scores is not Off. */
    @Volatile
    private var activePollerScope: CoroutineScope? = null
    /** The Scores setting as the pollers last saw it; OFF until read. */
    private var scoresMode = ScoresMode.OFF
    /** When "Retry now" last fetched. */
    private var lastRetryAt: Long? = null

    init {
        logoFile?.let { file ->
            scope.launch(io) {
                try {
                    file.read()?.let(logos::load)
                } finally {
                    logosLoadedFlow.value = true
                }
            }
        }
        // No learned logo is drawn before the Scores setting is known, nor while it is Off.
        scope.launch { mode.collect { logos.active = it != ScoresMode.OFF } }
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
            val (source, leagues) = (listOf(item) + item.alternates).asSequence()
                .map { it to EspnLeagueMap.leaguesFor(it) }.firstOrNull { it.second.isNotEmpty() } ?: return@mapNotNull null
            TrackedCard(card, leagues, item.followed, EspnLeagueMap.collegeFallbackFor(source)?.takeIf { it !in leagues })
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
     * [feed]'s events as last fetched, when that fetch answered within [maxAgeMs] and the feed is not
     * paused; else null (#67: Results' Today reuses them instead of asking again). Never a request; a
     * warm-start copy does not count.
     */
    suspend fun recentEvents(feed: ScoreFeed, maxAgeMs: Long): List<ScoreSnapshot>? = lock.withLock {
        val state = feeds[feed] ?: return@withLock null
        val at = state.lastSuccessAt
        if (!state.fetched || state.paused || at == null || now() - at >= maxAgeMs) null else state.events
    }

    /**
     * Every screen's tracked cards, one entry per card key (the same card on two screens is one),
     * followed teams' games first, then games in My sports: they claim the feed budget before the rest.
     */
    private fun trackedUnion(): List<TrackedCard> =
        tracked.value.values.flatten().distinctBy { it.card.key }
            .sortedWith(compareByDescending<TrackedCard> { it.followed }.thenByDescending { entry -> entry.leagues.any(::inMySports) })

    // While someone collects [scores]: settings, then the tracked cards, drive the pollers.
    private suspend fun run() {
        combine(settings.distinctUntilChanged(), mySports.distinctUntilChanged(), ::Pair).collectLatest { (current, picked) ->
            lock.withLock { scoresMode = current.mode }
            if (current.mode == ScoresMode.OFF) {
                lock.withLock { publishLocked(off = true) }
                return@collectLatest
            }
            coroutineScope {
                val pollerScope = this
                lock.withLock {
                    delayMs = current.delaySeconds * 1_000L
                    sports = picked
                    activePollerScope = pollerScope
                }
                try {
                    launch {
                        nextRelease.collectLatest { at ->
                            if (at == null) return@collectLatest
                            delay((at - now()).coerceAtLeast(0))
                            lock.withLock { publishLocked() }
                        }
                    }
                    tracked.collect {
                        lock.withLock {
                            collegeMisses.clear()
                            refreshLocked(pollerScope, restartStopped = true)
                        }
                    }
                } finally {
                    if (activePollerScope === pollerScope) activePollerScope = null
                }
            }
        }
    }

    private suspend fun poll(feed: ScoreFeed, pollerScope: CoroutineScope) {
        warmStart(feed, pollerScope)
        while (true) {
            val wait = lock.withLock { waitLocked(feed) } ?: return
            if (wait > 0) delay(wait)
            val request = lock.withLock { fetchLocked(feed) }
            request.await()
            lock.withLock { refreshLocked(pollerScope, restartStopped = false) }
        }
    }

    /**
     * [feed]'s request in flight, or a new one: in the repository scope, it outlives a poller restart and is
     * shared. A failed [retry] leaves the feed's failures and backoff as they were: the provider has counted
     * it once in [health], and the poller keeps its own schedule.
     */
    private fun fetchLocked(feed: ScoreFeed, retry: Boolean = false): Deferred<ScoreboardResult> = inFlight.getOrPut(feed) {
        scope.async {
            val result = provider.scoreboard(feed.league, feed.date)
            lock.withLock {
                inFlight.remove(feed)
                if (!retry || !result.failed) feeds[feed]?.let { record(it, result) }
                if (result.failed) checkOutageLocked()
            }
            result
        }
    }

    // The section or the player panel in use, and every feed failing for 10 minutes: the toast, once.
    private fun checkOutageLocked() {
        val inUse = tracked.value.keys.any { it == SCREEN_SECTION || it == SCREEN_PLAYER_PANEL }
        if (outage.check(now(), scoresMode, inUse)) notifyOutage()
    }

    /**
     * Settings → Scores status → Retry now (#70): one immediate fetch of each live scoreboard in use (today's
     * and yesterday's, never a past day), shared with any request in flight. At most one per
     * [RETRY_MIN_INTERVAL_MS]; a feed asked for within that time, or inside ESPN's Retry-After, is skipped.
     * Nothing while Scores is Off.
     */
    fun retryNow(): Job = scope.launch {
        if (settings.first().mode == ScoresMode.OFF) return@launch
        val requests = lock.withLock {
            val at = now()
            if (lastRetryAt?.let { at - it < RETRY_MIN_INTERVAL_MS } == true) return@withLock emptyList()
            lastRetryAt = at
            val lastAsked = health.feeds.value
            health.scoreboardsInUse(at).distinct().filter { feed ->
                val asked = lastAsked[EspnFeedKey.scoreboard(feed.league, feed.date)]?.lastAttemptAt
                val state = feeds[feed]
                val retryAfter = state?.retryAfterMs?.let { wait -> state.lastAttemptAt?.let { at - it < wait } } == true
                !retryAfter && (asked == null || at - asked >= RETRY_MIN_INTERVAL_MS)
            }.map { fetchLocked(it, retry = true) }
        }
        if (requests.isEmpty()) return@launch
        requests.awaitAll()
        lock.withLock { activePollerScope?.let { refreshLocked(it, restartStopped = false) } }
    }

    /**
     * A feed polled for the first time since the app started: the provider's copy from the last two
     * minutes, if it has one, shows while the network fetch (which follows at once) is in flight. It
     * never counts as an answer ([FeedState.fetched] stays false), so no card is confirmed unmatched
     * and no fallback is tried on it. The score delay applies to it as to any fetch.
     */
    private suspend fun warmStart(feed: ScoreFeed, pollerScope: CoroutineScope) {
        val first = lock.withLock {
            val state = feeds[feed]
            val untried = state != null && !state.warmTried && state.lastAttemptAt == null
            if (untried) state!!.warmTried = true
            untried
        }
        if (!first) return
        val warm = provider.recentScoreboard(feed.league, feed.date) ?: return
        if (warm.failed || warm.events.isEmpty()) return
        lock.withLock {
            val state = feeds[feed] ?: return
            if (state.lastSuccessAt != null) return
            // Stamped when ESPN's copy arrived, not now: shown only while younger than RECENT_MS.
            state.lastSuccessAt = warm.receivedAtMs?.coerceAtMost(now()) ?: return
            state.events = warm.events
            refreshLocked(pollerScope, restartStopped = false)
        }
    }

    private fun record(state: FeedState, result: ScoreboardResult) {
        state.lastAttemptAt = now()
        state.jitterMs = jitter()
        if (result.failed) {
            state.failures++
            state.retryAfterMs = result.retryAfterMs
            if (state.paused) state.events = emptyList()
            // A warm copy past its limit never lasts through the backoff.
            if (!state.fetched && state.lastSuccessAt?.let { now() - it > ScoreProvider.RECENT_MS } == true) {
                state.events = emptyList()
                state.lastSuccessAt = null
            }
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
        // In card order (followed first), so the budget goes to followed teams' feeds first.
        val needed = LinkedHashSet<ScoreFeed>()
        fun fits(extra: Collection<ScoreFeed>) = needed.size + extra.count { it !in needed } <= MAX_FEEDS
        val matched = HashMap<String, ScoreSnapshot>()
        val learnedBefore = logos.revision.value
        val nowMs = now()
        val deferred = ArrayList<Deferral>()

        // A card's outcome: its feeds polled, and its match published, or it confirmed unmatched.
        fun settle(entry: TrackedCard, dates: List<LocalDate>, cardFeeds: List<ScoreFeed>, match: ScoreSnapshot?) {
            val card = entry.card
            if (match == null) {
                needed += cardFeeds
                // Unmatched only once every feed it routes to has answered (or is paused).
                if (cardFeeds.all { feed -> feeds[feed]?.let { it.fetched || it.paused } == true }) markUnmatched(card.key)
                return
            }
            unmatchedKeys -= card.key
            learnLogos(match)
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

        for (entry in trackedUnion()) {
            val card = entry.card
            val dates = datesFor(card)
            val leagues = remembered[card.key]?.let(::listOf) ?: entry.leagues
            val cardFeeds = feedsOf(leagues, dates).toMutableList()
            // A remembered soccer league keeps soccer/all until its own feed has data.
            if (card.key in remembered && leagues.single().sport == "soccer" && cardFeeds.any { feeds[it]?.fetched != true }) {
                cardFeeds += feedsOf(listOf(EspnLeagueMap.SOCCER_ALL), dates)
            }
            // Over the budget: a card whose feeds are not polled already gets no score (never a stale one).
            if (!fits(cardFeeds)) continue
            // A card matched on its college fallback stays held to both sides STRONG.
            val strict = entry.fallback != null && remembered[card.key] == entry.fallback
            var match = matchOn(card, cardFeeds, if (strict) strictMatcher else matcher)
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
            // A bare "Basketball" / "Football" card its pro feed has answered without: the college feed
            // (#66), after every card's own feeds have their budget, while such a card is tracked.
            val fallback = entry.fallback
            if (match == null && fallback != null && card.key !in remembered &&
                cardFeeds.all { feed -> feeds[feed]?.let { it.fetched && !it.paused && it.isFresh(nowMs) } == true }
            ) {
                needed += cardFeeds
                deferred += Deferral(entry, dates, cardFeeds, fallback)
                continue
            }
            settle(entry, dates, cardFeeds, match)
        }
        for (wait in deferred) {
            val key = wait.entry.card.key
            // Its college feed answered twice without it: dropped until the tracked cards change.
            if ((collegeMisses[key]?.second ?: 0) >= COLLEGE_MAX_MISSES) {
                settle(wait.entry, wait.dates, wait.cardFeeds, null)
                continue
            }
            val college = feedsOf(listOf(wait.fallback), wait.dates)
            // No budget left: no score for it this time, and not confirmed unmatched either.
            if (!fits(college)) continue
            // Both sides STRONG, on the college feed alone.
            val match = matchOn(wait.entry.card, college, strictMatcher)
            if (match != null) {
                // Matched there: the card is polled on its college feed alone from now on.
                if (remembered.size >= MAX_REMEMBERED) remembered.clear()
                remembered[key] = wait.fallback
                collegeMisses -= key
            } else {
                val answered = college.mapNotNull { feed -> feeds[feed]?.takeIf { it.fetched && it.isFresh(nowMs) }?.lastSuccessAt }
                if (answered.size == college.size) {
                    val fetch = answered.max()
                    val prior = collegeMisses[key]
                    if (prior?.first != fetch) {
                        if (collegeMisses.size >= MAX_REMEMBERED) collegeMisses.clear()
                        collegeMisses[key] = fetch to (prior?.second ?: 0) + 1
                    }
                }
            }
            settle(wait.entry, wait.dates, wait.cardFeeds + college, match)
        }
        raw = matched
        unmatchedOutput.value = unmatchedKeys.toSet()
        if (logos.revision.value != learnedBefore) saveLogos()
        prefetchLogos(matched.values)

        // Feeds no card needs: stop polling, but keep their state (last fetch, failures, Retry-After)
        // for a while, so switching between Home and the section does not refetch at once.
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

    /** Both ESPN teams of a matched game: their logos, abbreviations and names, by ESPN identity. */
    private fun learnLogos(match: ScoreSnapshot) {
        logos.learn(match.league, match.home)
        logos.learn(match.league, match.away)
    }

    /** Matched games' logos and the known logos of tracked cards, each URL handed over once. */
    private fun prefetchLogos(matched: Collection<ScoreSnapshot>) {
        val urls = LinkedHashSet<String>()
        matched.forEach { event -> listOfNotNull(event.home.logo, event.away.logo).forEach { urls += TeamLogoUrls.scaled(it) } }
        for (entry in trackedUnion()) {
            val leagues = remembered[entry.card.key]?.let(::listOf) ?: (entry.leagues + listOfNotNull(entry.fallback))
            for (league in leagues) {
                for (side in listOf(entry.card.teams.home, entry.card.teams.away)) {
                    logos.resolve(league, side)?.let { urls += TeamLogoUrls.scaled(it.logo) }
                }
            }
        }
        val fresh = urls.filter { it !in prefetched }
        if (fresh.isEmpty()) return
        if (prefetched.size + fresh.size > MAX_REMEMBERED) prefetched.clear()
        prefetched += fresh
        prefetch(fresh)
    }

    /**
     * Writes [logos] to its file a moment after the last change, off the poll path; a change made while
     * a write runs is written after it.
     */
    private fun saveLogos() {
        val file = logoFile ?: return
        logosDirty = true
        if (logoWrite?.isActive == true) return
        logoWrite = scope.launch(io) {
            while (logosDirty) {
                delay(LOGO_WRITE_DELAY_MS)
                logosDirty = false
                file.write(logos.encode())
            }
        }
    }

    private fun markUnmatched(key: String) {
        if (unmatchedKeys.size >= MAX_REMEMBERED) unmatchedKeys.clear()
        unmatchedKeys += key
    }

    /** True when [league]'s games can be in My sports: its sport is in, and (beyond soccer) the league too. */
    private fun inMySports(league: EspnLeague): Boolean {
        val sport = MySport.ofEspn(league.sport) ?: return false
        if (sport !in sports.effectiveSports) return false
        if (sport == MySport.SOCCER) return true
        return MyLeague.ofFeed(league)?.let { it in sports.leaguesOf(sport) } ?: false
    }

    private fun matchOn(card: ScoreCard, cardFeeds: List<ScoreFeed>, using: ScoreMatcher = matcher): ScoreSnapshot? {
        val nowMs = now()
        // A card with no start is matched across ESPN's days: a league counts only once all its days are
        // in, so yesterday's final never stands alone while today's feed is loading, failing or paused.
        val usable = if (card.startMs != null) cardFeeds else cardFeeds.groupBy { it.league }.values
            .filter { days -> days.all { feed -> feeds[feed]?.let { it.isFresh(nowMs) && !it.paused } == true } }
            .flatten()
        val events = usable.flatMap { feed -> feeds[feed]?.takeIf { it.isFresh(nowMs) }?.events.orEmpty() }
            .distinctBy { it.eventId }
        return if (events.isEmpty()) null else using.match(card, events, now())
    }

    /**
     * True when the feed's events may be shown: fetched no longer ago than twice its polling interval
     * (plus jitter). A feed that stopped polling (all final, a past day) keeps its events. After a pause
     * (the player, Home) a live feed is stale until it is fetched again, so no old score shows meanwhile.
     */
    private fun FeedState.isFresh(nowMs: Long): Boolean {
        val success = lastSuccessAt ?: return false
        // Only a warm copy so far: shown while younger than its limit.
        if (!fetched && nowMs - success > ScoreProvider.RECENT_MS) return false
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
     * A bare date asks for that date; no start: Eastern today and yesterday (the device's day plays no
     * part, see [ScoreMatcher]).
     * ESPN rejects `dates=A-B` ranges (HTTP 400, checked 2026-11-17), so each date is its own request.
     */
    internal fun datesFor(card: ScoreCard): List<LocalDate> {
        val start = card.startMs
        if (start == null) {
            val easternToday = Instant.ofEpochMilli(now()).atZone(EASTERN).toLocalDate()
            return listOf(easternToday, easternToday.minusDays(1))
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
        /** The player's live games panel, tracked only while it is open. */
        const val SCREEN_PLAYER_PANEL = "player_panel"
        /** Followed teams' live games, tracked for alerts (#42) while the app is in the foreground. */
        const val SCREEN_ALERTS = "alerts"

        private const val WINDOW_MS = 3 * 60 * 60 * 1000L
        private const val MAX_REMEMBERED = 2_000
        /** Feeds polled at once, at most; followed teams' games claim theirs first. */
        internal const val MAX_FEEDS = 16
        private const val RETAIN_RETIRED_MS = 10 * 60_000L
        private const val LOGO_WRITE_DELAY_MS = 2_000L
        private const val COLLEGE_MAX_MISSES = 2
        /** "Retry now" at most this often, and never on a feed asked for within this long. */
        internal const val RETRY_MIN_INTERVAL_MS = 30_000L
        private val SLUG = Regex("""[a-z0-9._-]+""")
        private val EASTERN: ZoneId = ZoneId.of("America/New_York")
    }
}

/** What a screen calls to have its cards scored: [track] the cards it shows, [untrack] when it leaves. */
interface LiveScoreTracking {
    fun track(screenId: String, cards: List<SportsLiveItem>)
    fun untrack(screenId: String)
}
