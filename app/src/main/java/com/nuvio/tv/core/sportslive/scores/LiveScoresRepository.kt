package com.nuvio.tv.core.sportslive.scores

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.SportsLiveEspnStarts
import com.nuvio.tv.core.sportslive.SportsLiveNoEspnGames
import com.nuvio.tv.core.sportslive.SportsLiveSportInference
import com.nuvio.tv.core.sportslive.SportsLiveSportKind
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.SportsLiveToastKind
import com.nuvio.tv.core.sportslive.SportsLiveToasts
import com.nuvio.tv.core.sportslive.SportsLiveMatchedEvents
import com.nuvio.tv.core.sportslive.SportsLiveWomenGames
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import com.nuvio.tv.domain.model.SportsLiveItem
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
 *   while the Scores setting is Off, but the start of games an addon lists live ([checkSchedules], never a score).
 * - Cards are routed to ESPN feeds by [EspnLeagueMap] and grouped by (league, date): one poller per
 *   feed, shared by all its cards, at the [ScoreCadence] rules, with in-flight requests coalesced.
 * - The [ScoreDelay] is applied here, once, so every surface gets the delayed view.
 * - Failure-silent: a feed failing three times in a row is paused and its scores hidden until a success.
 * - Scope (#66): every tracked card on screen, whatever My sports says; My sports only orders the
 *   feed budget. At most [MAX_FEEDS] feeds poll at once (in parallel): followed teams' live games claim
 *   theirs first, then every other live game (My sports first), then games not live yet (followed teams', then
 *   My sports): a game later in the week never takes a live game's score (live.23 RC). A card routed by a
 *   bare "Basketball" / "Football" that its pro feed does not list also tries the college feed ([EspnLeagueMap.collegeFallbackFor]), only while
 *   it is tracked.
 * - Logos (#66): each matched side's logo is learned into [logos] (kept in app storage) so cards draw
 *   it on the next launch before any feed answers, and tracked games' logos are prefetched.
 * - Warm start (#66): a feed's first fetch after a restart is preceded by the provider's copy of it
 *   from the last two minutes, if any ([ScoreProvider.recentScoreboard]); the network fetch follows at once.
 *   live.23: when scores are first collected (Home's first show), today's feeds of followed teams' leagues and
 *   My sports are fetched before any card asks ([prewarmFeeds]); and the scores last shown are kept on disk
 *   ([KeptScores]), drawn on a reopen until each card's own feed answers, at most one poll ([ScoreCadence.LIVE_MS]).
 *   So is a card's last score when its screen stops tracking it (the player's guide closing): a reopen draws it
 *   at once until the feed answers again ([keptLocked]).
 * - Logo directory (#82): while Scores is not Off and someone collects [scores], the team lists of the
 *   leagues on screen, in My sports and of followed teams are fetched in the background, one league at a
 *   time after a short wait, at most weekly ([TeamDirectorySync]); never on the poll path or under its lock.
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
    private val notifyOutage: () -> Unit = {},
    /** Where the logo directory's lists are kept (#82); null: memory only. */
    directoryStore: TeamDirectoryStore? = null,
    /** Followed teams' ESPN leagues ("soccer/eng.1"): their lists are kept too (#82). */
    private val followedLeagues: Flow<List<EspnLeague>> = flowOf(emptyList()),
    /** Spoiler protection (#151): every score served is stamped with it ([SpoilerProtection.stamp]). */
    protectionSource: Flow<SpoilerProtection> = flowOf(SpoilerProtection()),
    /** What plays now (live.20 Q18, #85): its games are served masked, the others delayed ([ScoreWatch]). */
    private val watching: WatchingNow = WatchingNow(),
    /** Each stream source's "Scores ahead? +15 s" seconds, by addon id. */
    private val nudges: Flow<Map<String, Int>> = flowOf(emptyMap()),
    /** Where ESPN's start goes for a game an addon lists live that ESPN schedules later (live.20, a wrong LIVE). */
    private val espnStarts: SportsLiveEspnStarts = SportsLiveEspnStarts.shared,
    /** Where the scores last shown are kept (live.23, [KeptScores]); null: not kept. */
    private val keptFile: TeamLogoFile? = null,
    /** Fetch followed teams' and My sports' feeds when scores are first collected (live.23, [prewarmFeeds]). */
    private val prewarm: Boolean = false,
    /** The active profile's id: kept scores belong to it, and its spoiler settings are read again on a switch (live.23). */
    private val profile: Flow<Int?> = flowOf(null),
    /** Where live listings ESPN has no game for go (live.27, [SportsLiveOtherStreams]): team channels, replays, passes. */
    private val noEspnGames: SportsLiveNoEspnGames = SportsLiveNoEspnGames.shared
) : LiveScoreTracking {
    @Inject
    constructor(
        provider: ScoreProvider,
        prefs: SportsLivePreferencesDataStore,
        @ApplicationContext context: Context,
        health: EspnFeedHealth,
        profiles: ProfileManager
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
            SportsLiveToasts.show(
                SportsLiveToastKind.PROBLEM,
                context.getString(R.string.sports_live_scores_outage_toast),
                context.getString(R.string.sports_live_scores_outage_line)
            )
        },
        directoryStore = TeamDirectoryStore(File(context.filesDir, "sportslive/team_directory")),
        followedLeagues = prefs.followedTeams.map { teams -> teams.mapNotNull { leagueOfPath(it.league) } },
        protectionSource = prefs.spoilerProtection,
        watching = WatchingNow.shared,
        nudges = prefs.scoreNudges,
        keptFile = TeamLogoFile(File(context.filesDir, "sportslive/last_scores.json")),
        prewarm = true,
        profile = profiles.activeProfileId
    )

    /** What the settings screen and surfaces read: Show / Hide until focused / Off. */
    val mode: Flow<ScoresMode> = settings.map { it.mode }.distinctUntilChanged()

    private val output = MutableStateFlow<Map<String, ScoreSnapshot>>(emptyMap())

    /**
     * Delayed scores of the tracked cards that matched a game, by card key. Empty when Scores is Off. Each is
     * stamped [ScoreSnapshot.spoilerProtected] (#151), so every surface hides a protected game's score.
     */
    val scores: StateFlow<Map<String, ScoreSnapshot>> = output.asStateFlow()

    private val watchedOutput = MutableStateFlow<Map<String, ScoreSnapshot>>(emptyMap())

    /**
     * The games being watched (Q18) as [scores] would serve them unmasked: their delayed score and clock, by card key,
     * stamped as [scores] are. Only for the in-player guide's Now playing (#213), drawn beside the picture; every other
     * surface keeps [scores]' masked reading.
     */
    val watchedScores: StateFlow<Map<String, ScoreSnapshot>> = watchedOutput.asStateFlow()

    private val holdOutput = MutableStateFlow<Long?>(null)

    /**
     * How long every score is held back now for the stream playing ([ScoreWatch.delayMs], TV report #14: the guide's
     * scores trail ESPN by this on purpose, and say so); null while nothing plays, under Real time or Scores Off.
     */
    val scoreHoldMs: StateFlow<Long?> = holdOutput.asStateFlow()

    private val protectionState = MutableStateFlow(SpoilerProtection())

    /** The active profile's spoiler protection (#151), as the scores were last stamped with it. */
    val protection: StateFlow<SpoilerProtection> = protectionState.asStateFlow()

    private val unmatchedOutput = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Card keys confirmed to get no score (#60): every feed they route to has answered afresh (never a stale or
     * paused feed, live.23 review H1, nor a card asked on part of its feeds, M4) without their game (its college feed too, for a bare "Basketball" / "Football" card). Remembered
     * while the card is not tracked; a card that later matches leaves it. Only these cards (and cards
     * no feed covers, or every card while Scores is Off) may be estimated as ended.
     */
    val unmatched: StateFlow<Set<String>> = unmatchedOutput.asStateFlow()

    /**
     * #462: ESPN is known unreachable for [item]: every league it routes to has its latest scoreboard request failed
     * ([EspnFeedHealth.failingLeagues]). Live now, Home and the panel then skip the "not LIVE until ESPN answers" wait for
     * it (an answer is not coming); the wait comes back once a request of its league answers.
     */
    fun espnUnreachable(item: SportsLiveItem): Boolean {
        val failing = health.failingLeagues()
        if (failing.isEmpty()) return false
        val leagues = (listOf(item) + item.alternates).flatMap(EspnLeagueMap::leaguesFor).distinct()
        return leagues.isNotEmpty() && leagues.all { it in failing }
    }

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
        /** An addon lists the game live (not a channel): it claims the feed budget before any game not live yet. */
        val live: Boolean,
        /** The college feed to try when [leagues] answer without the game (#66); null for most cards. */
        val fallback: EspnLeague?,
        /** A tennis card (#160): matched by surname on its tour's live copy ([TennisMatcher]). */
        val tennis: Boolean = false,
        /**
         * The source keys of a game its addon lists live (not a channel), else empty: when ESPN schedules its game
         * later, they go to [espnStarts] (live.20).
         */
        val liveKeys: List<String> = emptyList(),
        /**
         * A two-team card that names no sport and no feed covers (#206: "Troyes vs Le Havre", untagged): matched, both
         * sides STRONG, only on the feeds other cards already poll, so it never asks for a feed of its own.
         */
        val piggyback: Boolean = false,
        /** A [piggyback] card's ESPN sports when a source names one no feed covers; null: told by its teams. */
        val piggybackSports: Set<String>? = null,
        /**
         * The college feed that is one of a generic card's own feeds (live.24, TV report #18): a card whose only sport is
         * "American Football", or a "Basketball" card whose two teams the loaded lists name as schools. Its games are
         * matched with both sides STRONG, the pro feed's as usual. Null for every other card.
         */
        val strictLeague: EspnLeague? = null
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
    /**
     * Failures (and ESPN's Retry-After) of feeds fetched before any poller had them ([prewarmFeeds]), by feed: the feed's
     * state starts from it, so its poller waits out the backoff instead of asking again at once (L7, live.23 review).
     */
    private val earlyFailures = HashMap<ScoreFeed, ScoreboardResult>()
    private val earlyFailureAt = HashMap<ScoreFeed, Long>()
    /** A soccer/all card's own league, learned from its match, polled instead next time (by card key). */
    private val remembered = HashMap<String, EspnLeague>()
    private var raw: Map<String, ScoreSnapshot> = emptyMap()
    /** The profile's Score delay, and Real-time scores (Q18): the delay while no video plays. */
    private var settingDelayMs = 0L
    private var realTime = false
    /** The players playing now and the sources' nudges (Q18, #85): masked games, the delay while video plays. */
    private var playing: Collection<WatchingNow.Session> = emptyList()
    private var nudgeSeconds: Map<String, Int> = emptyMap()
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
    /** Live card key to when ESPN's today and tomorrow last answered for it: asked again only after [SCHEDULE_CHECK_MS]. */
    private val laterLookups = HashMap<String, Long>()
    @Volatile
    private var logosDirty = false
    private val scoreDelay = ScoreDelay()
    private val nextRelease = MutableStateFlow<Long?>(null)
    /**
     * The matched games' states by event id, as of the last refresh: what a feed's polling pace hangs on.
     * A poller waiting out its interval is woken when it changes, so a game matched live after the feed's
     * own fetch (an undated card's other day landing, a live card tracked onto a feed at the upcoming
     * pace) is polled at the live pace at once, not after the 5-minute wait (#81).
     */
    private val matchedStates = MutableStateFlow<Map<String, ScoreState>>(emptyMap())
    private val outage = ScoresOutageNotice(health)
    private val directorySync = TeamDirectorySync(logos.directory, provider::teamList, directoryStore, now)
    /** The running pollers' scope while someone collects [scores] and Scores is not Off. */
    @Volatile
    private var activePollerScope: CoroutineScope? = null
    /** The Scores setting as the pollers last saw it; OFF until read. */
    private var scoresMode = ScoresMode.OFF
    /** When "Retry now" last fetched. */
    private var lastRetryAt: Long? = null
    /** A card's last score shown ([at]), kept for a re-track (live.23); [servedAt]: when it was first drawn again. */
    private class KeptCard(val snapshot: ScoreSnapshot, val at: Long, var servedAt: Long? = null)

    /**
     * The scores kept for a re-track (live.23), by card key: read back from [keptFile] at start, and each card's last
     * delayed score when no screen tracks it any more. A card's entry goes once its own feed answers after [KeptCard.at].
     */
    private val kept = HashMap<String, KeptCard>()
    /** The [kept] scores of tracked cards no feed has answered for since ([refreshLocked]), by card key. */
    private var keptShown: Map<String, KeptCard> = emptyMap()
    /** The delayed scores last published, by card key: what an untracked card keeps. */
    private var lastDelayed: Map<String, ScoreSnapshot> = emptyMap()
    /** The active profile's spoiler protection has been read since it became active: until then no score is served. */
    private var protectionLoaded = false
    /** The profile the [kept] scores (and the file's next copy) belong to. */
    private var keptProfile: Int? = null
    /** The scores to write to [keptFile] next: when they were shown, and to which profile. */
    private class KeptWrite(val games: Map<String, ScoreSnapshot>, val at: Long, val profile: Int?)
    private var keptPending: KeptWrite? = null
    private var keptWrite: Job? = null
    /** [prewarmFeeds] ran this app session. */
    private var prewarmed = false

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
        // Reading the kept lists is no request: they show once Scores is known not to be Off.
        scope.launch(io) { directorySync.load() }
        // No learned logo is drawn before the Scores setting is known, nor while it is Off.
        scope.launch { mode.collect { logos.active = it != ScoresMode.OFF } }
        // A player starting or stopping, its distance behind live, or a nudge: served again at once (Q18, #85).
        scope.launch {
            combine(watching.state, nudges) { sessions, perSource -> sessions.values.toList() to perSource }
                .distinctUntilChanged()
                .collect { (sessions, perSource) ->
                    lock.withLock {
                        playing = sessions
                        nudgeSeconds = perSource
                        if (scoresMode != ScoresMode.OFF && raw.isNotEmpty()) publishLocked()
                    }
                }
        }
        // Spoilers first (live.23): no score is served until the active profile's spoiler protection has been read, on
        // start and after a profile switch, so a protected game (my teams', Watch later) never flashes. Kept scores
        // belong to the profile that saw them: a switch drops them, and the file's copy is read for its own profile only.
        // A game saved for later (or revealed) is stamped again at once, not at the next fetch.
        scope.launch {
            profile.distinctUntilChanged()
                .flatMapLatest { pid -> protectionOrDefaults(protectionSource).map<SpoilerProtection, SpoilerProtection?> { it }.onStart { emit(null) }.map { pid to it } }
                .collect { (pid, latest) ->
                    if (latest == null) {
                        val read = keptFile?.let { file -> withContext(io) { file.read() }?.let { KeptScores.decode(it, now()) } }
                        lock.withLock {
                            protectionLoaded = false
                            keptProfile = pid
                            kept.clear()
                            keptShown = emptyMap()
                            lastDelayed = emptyMap()
                            keptPending = null
                            read?.takeIf { it.profile == pid }?.let { copy ->
                                copy.games.forEach { (key, snapshot) -> kept[key] = KeptCard(snapshot, copy.savedAt) }
                            }
                            output.value = emptyMap()
                            watchedOutput.value = emptyMap()
                        }
                        return@collect
                    }
                    lock.withLock {
                        // A write still waiting was made under the old protection (live.23 review L4): it may hold a game
                        // protected now. It goes; the next publish writes what is keepable under the new one.
                        if (latest != protectionState.value) keptPending = null
                        protectionState.value = latest
                        if (!protectionLoaded) {
                            protectionLoaded = true
                            activePollerScope?.let { refreshLocked(it, restartStopped = false) }
                        } else {
                            if (output.value.isNotEmpty()) output.value = latest.stampAll(output.value)
                            if (watchedOutput.value.isNotEmpty()) watchedOutput.value = latest.stampAll(watchedOutput.value)
                        }
                    }
                }
        }
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
                        watchedOutput.value = emptyMap()
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
            val live = item.isLive && !item.isChannel
            val sources = listOf(item) + item.alternates
            val routed = sources.asSequence().map { it to EspnLeagueMap.leaguesFor(it) }.firstOrNull { it.second.isNotEmpty() }
            if (routed == null) {
                // A tennis match (#160): its tour's feeds, matched by the players' surnames.
                val tennis = sources.asSequence().map(EspnLeagueMap::tennisLeaguesFor).firstOrNull { it.isNotEmpty() }
                if (tennis != null) return@mapNotNull TrackedCard(card, tennis, item.followed, live, fallback = null, tennis = true)
                // No sport anywhere on the card (#206): only the feeds already polled for others.
                if (item.isChannel || item.teams == null || sources.any { SportsLiveSportKind.tagOf(it) != null }) return@mapNotNull null
                val liveKeys = if (item.isLive) sources.filter { !it.isChannel }.map { it.streamKey } else emptyList()
                val sports = sources.firstNotNullOfOrNull { source -> SportsLiveTeams.knownSport(source)?.let(EspnLeagueMap::espnSportsOf) }
                return@mapNotNull TrackedCard(
                    card, emptyList(), item.followed, live, fallback = null, liveKeys = liveKeys, piggyback = true, piggybackSports = sports
                )
            }
            val (source, leagues) = routed
            val liveKeys = if (item.isLive && !item.isChannel) sources.filter { !it.isChannel }.map { it.streamKey } else emptyList()
            val college = EspnLeagueMap.collegeFallbackFor(source)
            // A card that says only "American Football" (live.24, TV report #18: "Illinois vs Iowa" on a busy Saturday): the
            // NFL and college football are both its own feeds, budgeted as any live game's, college football's games matched
            // with both sides STRONG. As a fallback, college football came after every other game's feeds and found no slot.
            // A "Basketball" card goes so only when the loaded lists name both sides as schools, not as NBA teams: addons
            // tag most NBA games "Basketball", which would otherwise poll college basketball for each. So does a bare
            // "Football" card (live.25, TV report #21: FOOTBALL rows among NCAAF ones, no score) whose two sides the college
            // football list names as schools: most such cards are soccer, whose clubs that list never holds.
            val bothOwn = (leagues == listOf(EspnLeagueMap.NFL) && college == EspnLeagueMap.COLLEGE_FOOTBALL) ||
                (leagues == listOf(EspnLeagueMap.NBA) && college == EspnLeagueMap.COLLEGE_BASKETBALL &&
                    schoolsOnly(EspnLeagueMap.COLLEGE_BASKETBALL, EspnLeagueMap.NBA, card.teams)) ||
                (leagues == listOf(EspnLeagueMap.SOCCER_ALL) && college == EspnLeagueMap.COLLEGE_FOOTBALL &&
                    schoolsOnly(EspnLeagueMap.COLLEGE_FOOTBALL, null, card.teams))
            if (bothOwn && college != null) {
                return@mapNotNull TrackedCard(card, leagues + college, item.followed, live, fallback = null, liveKeys = liveKeys, strictLeague = college)
            }
            // A bare sport word's college feed (#66), else a men's soccer league's women's counterpart (live.23).
            val fallback = (college ?: EspnLeagueMap.womenFallbackFor(source))?.takeIf { it !in leagues }
            TrackedCard(card, leagues, item.followed, live, fallback, liveKeys = liveKeys)
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
     * Every screen's tracked cards, one entry per card key (the same card on two screens is one), in the order they
     * claim the feed budget: followed teams' live games, the other live games, then games not live yet; followed teams'
     * games, then games in My sports within each. Live before not live (live.23 RC, TV: Live now also tracks followed
     * teams' Upcoming games, and a week of them took every feed, so Serie A, Bundesliga, La Liga and college football
     * games in play showed no score): a later game only costs its logos, a live one its score.
     */
    private fun trackedUnion(): List<TrackedCard> =
        tracked.value.values.flatten().distinctBy { it.card.key }
            .sortedWith(
                compareByDescending<TrackedCard> { it.followed && it.live }
                    .thenByDescending { it.live }
                    .thenByDescending { it.followed }
                    .thenByDescending { entry -> entry.leagues.any(::inMySports) }
            )

    // While someone collects [scores]: settings, then the tracked cards, drive the pollers.
    private suspend fun run() {
        combine(settings.distinctUntilChanged(), mySports.distinctUntilChanged(), ::Pair).collectLatest { (current, picked) ->
            lock.withLock { scoresMode = current.mode }
            if (current.mode == ScoresMode.OFF) {
                lock.withLock { publishLocked(off = true) }
                checkSchedules()
                return@collectLatest
            }
            coroutineScope {
                val pollerScope = this
                lock.withLock {
                    settingDelayMs = current.delaySeconds * 1_000L
                    realTime = current.realTime
                    sports = picked
                    activePollerScope = pollerScope
                }
                if (prewarm && !prewarmed) {
                    prewarmed = true
                    launch { prewarmFeeds(picked) }
                }
                try {
                    // The logo directory (#82): low priority, off the poll path, one league at a time.
                    launch(io) {
                        delay(DIRECTORY_START_DELAY_MS)
                        combine(tracked, followedLeagues) { cards, followed -> directoryLeagues(cards, picked, followed) }
                            .distinctUntilChanged()
                            .conflate()
                            .collect { directorySync.refresh(it) }
                    }
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

    /**
     * Warm start (live.23): once per app session, when scores are first collected (Home's first show) and Scores is not
     * Off, today's (ESPN's Eastern day) scoreboards of followed teams' leagues, then My sports' team leagues, at most
     * [PREWARM_FEEDS] and none a poller already has: the cards tracked a moment later find them in the provider's
     * recent copy ([warmStart]) instead of waiting on the network. Shared with any request in flight for the feed.
     */
    private suspend fun prewarmFeeds(picked: MySports) {
        try {
            prewarmFeedsUnguarded(picked)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A settings read or a record() error never stops the poller this runs beside (L7).
        }
    }

    private suspend fun prewarmFeedsUnguarded(picked: MySports) {
        val date = Instant.ofEpochMilli(now()).atZone(EASTERN).toLocalDate()
        val leagues = (followedLeagues.first() + picked.feeds())
            .filter { it != EspnLeagueMap.SOCCER_ALL && !EspnIndividualResults.covers(it) }
            .distinct()
        val requests = lock.withLock {
            leagues.map { ScoreFeed(it, date) }.filter { it !in feeds && it !in earlyFailures }.take(PREWARM_FEEDS).map { fetchLocked(it) }
        }
        if (requests.isEmpty()) return
        requests.awaitAll()
        // A card tracked meanwhile has its answer recorded: shown now, not at its next poll.
        lock.withLock { activePollerScope?.let { refreshLocked(it, restartStopped = false) } }
    }

    /**
     * Scores Off (live.20, a wrong LIVE): ESPN's start for the games an addon lists live, so one listed live too early
     * still waits on Upcoming. Only the start is kept ([espnStarts]); no score, state or logo is kept or served, and
     * nothing is published. Each live card's own days and ESPN's today and tomorrow, within [MAX_FEEDS], asked when the
     * tracked cards change and every [SCHEDULE_CHECK_MS]; a feed is asked at most once in that time.
     */
    private suspend fun checkSchedules() {
        // Feed to (when it answered, its games stripped to their schedule).
        val answered = HashMap<ScoreFeed, Pair<Long, List<ScoreSnapshot>>>()
        tracked.collectLatest {
            while (true) {
                checkSchedulesOnce(answered)
                delay(SCHEDULE_CHECK_MS)
            }
        }
    }

    private suspend fun checkSchedulesOnce(answered: HashMap<ScoreFeed, Pair<Long, List<ScoreSnapshot>>>) {
        val nowMs = now()
        answered.values.removeAll { (at, _) -> nowMs - at >= SCHEDULE_CHECK_MS }
        val needed = LinkedHashSet<ScoreFeed>()
        val cards = ArrayList<Pair<TrackedCard, List<ScoreFeed>>>()
        for (entry in trackedUnion()) {
            if (entry.liveKeys.isEmpty() || entry.tennis) continue
            val cardFeeds = feedsOf(entry.leagues, (datesFor(entry.card) + laterDates(nowMs)).distinct())
            if (needed.size + cardFeeds.count { it !in needed } > MAX_FEEDS) continue
            needed += cardFeeds
            cards += entry to cardFeeds
        }
        val results = coroutineScope {
            needed.filter { it !in answered }.map { feed -> async { feed to provider.scheduleScoreboard(feed.league, feed.date) } }.awaitAll()
        }
        for ((feed, result) in results) if (!result.failed) answered[feed] = nowMs to result.events.filter { it.individual == null }.map(::scheduleOnly)
        lock.withLock {
            for ((entry, cardFeeds) in cards) {
                val events = cardFeeds.flatMap { answered[it]?.second.orEmpty() }.distinctBy { it.eventId }
                if (events.isEmpty()) continue
                val match = matcher.match(entry.card, events, nowMs)
                val later = if (match != null) SportsLiveEspnStarts.laterStart(match, nowMs) else matcher.laterMeeting(entry.card, events, nowMs)?.startMs
                when {
                    later != null -> espnStarts.record(entry.liveKeys, later, nowMs)
                    // A game moved before: kept on ESPN's start as it changes (as with Scores on).
                    match != null && match.timeValid && match.startMs != null && espnStarts.has(entry.card.key) ->
                        espnStarts.record(listOf(entry.card.key), match.startMs, nowMs)
                }
            }
        }
    }

    private suspend fun poll(feed: ScoreFeed, pollerScope: CoroutineScope) {
        warmStart(feed, pollerScope)
        while (true) {
            val (wait, seen, staleIn) = lock.withLock {
                waitLocked(feed)?.let { Triple(it, matchedStates.value, liveStaleInLocked(feed)) }
            } ?: return
            // Tier 1 H1: a failing feed's live score goes once it is too old to stand for now, not at the next fetch a
            // long backoff or Retry-After away. Nothing is fetched for it.
            if (staleIn != null && staleIn < wait) {
                if (withTimeoutOrNull(staleIn) { matchedStates.first { it != seen } } == null) {
                    lock.withLock { refreshLocked(pollerScope, restartStopped = false) }
                }
                continue
            }
            // Woken when the matched games change: the wait is worked out again from the last fetch (#81).
            if (wait > 0 && withTimeoutOrNull(wait) { matchedStates.first { it != seen } } != null) continue
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
                if (!retry || !result.failed) {
                    val state = feeds[feed]
                    if (state != null) {
                        record(state, result)
                    } else if (result.failed && !retry) {
                        earlyFailures[feed] = result
                        earlyFailureAt[feed] = now()
                    } else {
                        earlyFailures.remove(feed)
                        earlyFailureAt.remove(feed)
                    }
                }
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

    /** When each feed was last asked for by [prefetchUpcoming] (#143). */
    private val upcomingPrefetchedAt = HashMap<ScoreFeed, Long>()

    /**
     * Upcoming's scoreboards asked for ahead (#143): while Live now shows, My sports' feeds for ESPN's (US Eastern) today
     * and tomorrow are fetched once, so opening Upcoming matches its games (times, logos) from the HTTP cache's recent
     * copy ([ScoreProvider.recentScoreboard], the pollers' warm start) instead of waiting on each feed. Capped: each feed
     * at most once per [UPCOMING_PREFETCH_EVERY_MS] (and not when anything asked for it within that time), never inside
     * its Retry-After or after a failure, never under Scores Off; shared with a request in flight. At most
     * [PREWARM_FEEDS] per call (live.23 review F4), followed teams' leagues first. Returns how many requests it made.
     */
    suspend fun prefetchUpcoming(): Int {
        val followed = try {
            followedLeagues.first().toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptySet()
        }
        val requests = lock.withLock {
            if (scoresMode == ScoresMode.OFF) return@withLock emptyList()
            val at = now()
            val lastAsked = health.feeds.value
            val leagues = sports.feeds().distinct().sortedBy { it !in followed }
            feedsOf(leagues, laterDates(at)).filter { feed ->
                val prefetched = upcomingPrefetchedAt[feed]
                if (prefetched != null && at - prefetched < UPCOMING_PREFETCH_EVERY_MS) return@filter false
                val asked = lastAsked[EspnFeedKey.scoreboard(feed.league, feed.date)]?.lastAttemptAt
                if (asked != null && at - asked < UPCOMING_PREFETCH_EVERY_MS) return@filter false
                val state = feeds[feed]
                val retryAfter = state?.retryAfterMs?.let { wait -> state.lastAttemptAt?.let { at - it < wait } } == true
                !retryAfter && (state?.failures ?: 0) == 0 && feed !in earlyFailures
            }.take(PREWARM_FEEDS).map { feed ->
                upcomingPrefetchedAt[feed] = at
                fetchLocked(feed)
            }
        }
        for (request in requests) {
            try {
                request.await()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        return requests.size
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

    /** A new [state] for [feed] picks up a failure of an early fetch ([earlyFailures]) still inside its backoff window. */
    private fun seedEarlyFailureLocked(feed: ScoreFeed, state: FeedState) {
        val result = earlyFailures.remove(feed) ?: return
        val at = earlyFailureAt.remove(feed) ?: return
        if (now() - at > RETAIN_RETIRED_MS) return
        state.lastAttemptAt = at
        state.jitterMs = jitter()
        state.failures = 1
        state.retryAfterMs = result.retryAfterMs
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

    /** The matched games of followed teams' cards, by event id: their feeds poll at [ScoreCadence.FOLLOWED_LIVE_MS]. */
    private fun followedEventIds(): Set<String> =
        trackedUnion().filter { it.followed }.mapNotNullTo(HashSet()) { raw[it.card.key]?.eventId }

    /** Millis to wait before [feed]'s next fetch, or null when it stops (or is no longer needed). */
    private fun waitLocked(feed: ScoreFeed): Long? {
        val state = feeds[feed] ?: return null
        val last = state.lastAttemptAt ?: return 0L
        val eventIds = state.events.mapTo(HashSet()) { it.eventId }
        // A tracked card drawing its kept score counts as matched (live.23): its game is polled at its own pace, so a
        // guide reopened after a minute is asked again at once, not at the 5-minute pace of a feed with no match.
        val matched = (raw.values.asSequence() + keptShown.values.asSequence().map { it.snapshot })
            .filter { it.eventId in eventIds }.distinctBy { it.eventId }.toList()
        val interval = ScoreCadence.nextIntervalMs(
            feed = feed,
            matched = matched,
            events = state.events,
            failures = state.failures,
            retryAfterMs = state.retryAfterMs,
            today = today(),
            nowMs = now(),
            followed = followedEventIds()
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
        // Cards asked on only some of their feeds this round, over the budget (live.23 review M4): never confirmed unmatched.
        val partial = HashSet<String>()

        // live.20 (a wrong LIVE is worse than none): a game its addon lists live that ESPN schedules more than half an
        // hour from now leaves Live now for Upcoming at ESPN's start. Its own match says so; with none on its own days,
        // ESPN's today and tomorrow are asked too (budget allowing), read for the start only, never for a score.
        fun noteStart(entry: TrackedCard, cardFeeds: List<ScoreFeed>, match: ScoreSnapshot?) {
            val keys = entry.liveKeys
            if (match != null) {
                val later = SportsLiveEspnStarts.laterStart(match, nowMs)
                when {
                    later != null && keys.isNotEmpty() -> espnStarts.record(keys, later, nowMs)
                    // A game moved before: kept on ESPN's start as it changes.
                    match.timeValid && match.startMs != null && espnStarts.has(entry.card.key) ->
                        espnStarts.record(listOf(entry.card.key), match.startMs, nowMs)
                }
                return
            }
            if (keys.isEmpty() || entry.tennis) return
            // Only once its own feeds have answered (fresh, not paused) without it, and at most once per SCHEDULE_CHECK_MS: a matched or
            // still-loading card costs no extra request, and the extra days are not polled while it stays unmatched.
            if (!cardFeeds.all { feed -> feeds[feed]?.let { it.fetched && !it.paused && it.isFresh(nowMs) } == true }) return
            val key = entry.card.key
            if (laterLookups[key]?.let { nowMs - it < SCHEDULE_CHECK_MS } == true) return
            val leagues = remembered[key]?.let(::listOf) ?: entry.leagues
            val extra = feedsOf(leagues, laterDates(nowMs)).filter { it !in cardFeeds }
            if (extra.isEmpty() || !fits(extra)) return
            needed += extra
            // Read once every extra day has answered (or paused) since this lookup was due.
            if (!extra.all { feed -> feeds[feed]?.let { it.paused || it.fetched && it.isFresh(nowMs) } == true }) return
            if (laterLookups.size >= MAX_REMEMBERED) laterLookups.clear()
            laterLookups[key] = nowMs
            val events = (cardFeeds + extra).flatMap { feed -> feeds[feed]?.takeIf { it.isFresh(nowMs) && !it.paused }?.events.orEmpty() }
                .distinctBy { it.eventId }
            matcher.laterMeeting(entry.card, events, nowMs)?.let { espnStarts.record(keys, it.startMs!!, nowMs) }
        }

        // A card's outcome: its feeds polled, and its match published, or it confirmed unmatched.
        fun settle(entry: TrackedCard, dates: List<LocalDate>, cardFeeds: List<ScoreFeed>, match: ScoreSnapshot?) {
            val card = entry.card
            noteStart(entry, cardFeeds, match)
            if (match == null) {
                needed += cardFeeds
                // Unmatched only once every feed it routes to has answered afresh without it (live.23 review H1): a feed
                // fetched before but stale (a re-track after minutes away) or paused (failing) says nothing about the game,
                // so the card keeps its last state, and no "no score" mark or ended estimate follows from it. Nor a card
                // asked on only some of its feeds over the budget (M4).
                if (card.key !in partial &&
                    cardFeeds.all { feed -> feeds[feed]?.let { it.fetched && !it.paused && it.isFresh(nowMs) } == true }
                ) {
                    markUnmatched(card.key)
                    noteNoGame(entry, cardFeeds, nowMs)
                }
                return
            }
            if (entry.liveKeys.isNotEmpty()) noEspnGames.forget(entry.liveKeys)
            unmatchedKeys -= card.key
            learnLogos(match)
            // Matched: only the feeds holding the game (and any not fetched yet) keep polling, so a
            // late game does not also poll the next Eastern day all night.
            needed += cardFeeds.filter { feed ->
                val state = feeds[feed]
                state == null || !state.fetched || state.events.any { it.eventId == match.eventId }
            }
            matched[card.key] = match
            // Copies matched to one ESPN game merge on the next list build; a women's soccer game is never a men's
            // club's "my team" (live.23, TV report #10).
            val sources = listOf(card.key) + entry.liveKeys
            SportsLiveMatchedEvents.shared.record(sources, match, nowMs)
            if (match.league.sport == "soccer" && ScoreMatcher.isWomenGame(match)) SportsLiveWomenGames.shared.record(sources, nowMs)
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

        val union = trackedUnion()
        // Each card's own feeds (its leagues on its days; a matched card's only where its game is) are budgeted first, in
        // card order, before any card's extras (soccer/all, the college or women's league, ESPN's later days). live.23 RC7,
        // TV: those extras of the games ranked first took the slots of later live games' own feeds, so Bundesliga, La Liga
        // and Ligue 1 games in play were never polled while obscure leagues' fallbacks were.
        for (entry in union) {
            if (entry.piggyback) continue
            val own = feedsOf(remembered[entry.card.key]?.let(::listOf) ?: entry.leagues, datesFor(entry.card))
            val shown = raw[entry.card.key]
            // A card with no start whose game is in play keeps only the feeds that hold it (live.25 #226): after midnight
            // Eastern its game sits on yesterday's feed while today's, not asked yet, joins its days; on a full budget the
            // two no longer fitted and the score went though the game was still on. An in-play game is tonight's game, never
            // last night's final of a back-to-back pair (that one is POST): the R1 rule below still holds for any other.
            val playing = if (entry.card.startMs == null && shown?.state == ScoreState.IN) {
                own.filter { feed -> feeds[feed]?.events?.any { it.eventId == shown.eventId } == true }
            } else {
                emptyList()
            }
            val kept = when {
                shown == null -> own
                playing.isNotEmpty() -> playing
                else -> own.filter { feed ->
                    feeds[feed]?.let { !it.fetched || it.events.any { event -> event.eventId == shown.eventId } } ?: true
                }
            }
            if (fits(kept)) needed += kept
        }

        val piggybacks = ArrayList<TrackedCard>()
        for (entry in union) {
            if (entry.piggyback) {
                piggybacks += entry
                continue
            }
            val card = entry.card
            val dates = datesFor(card)
            val leagues = remembered[card.key]?.let(::listOf) ?: entry.leagues
            val cardFeeds = feedsOf(leagues, dates).toMutableList()
            // A remembered soccer league keeps soccer/all until its own feed has data.
            if (card.key in remembered && leagues.single().sport == "soccer" && cardFeeds.any { feeds[it]?.fetched != true }) {
                cardFeeds += feedsOf(listOf(EspnLeagueMap.SOCCER_ALL), dates)
            }
            // Over the budget: the feeds already budgeted for it (its own, above), else no score (never a stale one).
            if (!fits(cardFeeds)) {
                cardFeeds.retainAll { it in needed }
                // A card whose shown game sits in the feeds it kept is fully answered (live.24 RC review H1): an unrelated
                // day or fallback feed that did not fit never takes away a score it already has. A card with no start holds
                // its game only while it is in play or filed under ESPN's (Eastern) today (live.24 RC2 review R1): last night's
                // final of a back-to-back pair, on yesterday's feed alone, never stays on today's card.
                val shown = raw[card.key]
                val easternToday = Instant.ofEpochMilli(nowMs).atZone(EASTERN).toLocalDate()
                val holds = shown != null && cardFeeds.any { feed ->
                    feeds[feed]?.events?.any { it.eventId == shown.eventId } == true &&
                        (card.startMs != null || shown.state == ScoreState.IN || feed.date == easternToday)
                }
                if (!holds && feedsOf(leagues, dates).any { it !in cardFeeds }) {
                    partial += card.key
                    // A card with no start is matched across a league's days only with every day in (live.23 review M4:
                    // yesterday's feed alone would put last night's final of the pair on today's card): no score this round.
                    if (card.startMs == null) continue
                }
            }
            if (cardFeeds.isEmpty()) {
                // Debug builds name the skipped league so a TV run shows a full feed budget (live.23 RC6 missing scores).
                if (com.nuvio.tv.BuildConfig.IS_DEBUG_BUILD) {
                    android.util.Log.d("LiveScores", "no feed slot: ${leagues.joinToString { it.path }} live=${entry.live}")
                }
                continue
            }
            // A card matched on its college fallback stays held to both sides STRONG.
            val strict = entry.fallback != null && remembered[card.key] == entry.fallback
            var match = when {
                entry.tennis -> matchOn(card, cardFeeds, tennis = true)
                // A generic American football or basketball card (live.24): the pro feed's games as any card's, the college
                // feed's with both sides STRONG, as the college fallback always was.
                entry.strictLeague != null -> matchOn(card, cardFeeds.filter { it.league != entry.strictLeague }, matcher)
                    ?: matchOn(card, cardFeeds.filter { it.league == entry.strictLeague }, strictMatcher)
                else -> matchOn(card, cardFeeds, if (strict) strictMatcher else matcher)
            }
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
                // Never the women's game of a pair its men's league plays today (live.23).
                if (EspnLeagueMap.isWomenFallback(fallback) && matcher.meets(card, cardFeeds.flatMap { feeds[it]?.events.orEmpty() })) {
                    settle(entry, dates, cardFeeds, null)
                    continue
                }
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
            // Both sides STRONG, on the college feed alone. The women's league only for its game within 3 h of the card's
            // own start (live.23 review B1): never another meeting of the pair, nor for an undated card.
            val match = matchOn(wait.entry.card, college, strictMatcher)
                ?.takeIf { !EspnLeagueMap.isWomenFallback(wait.fallback) || nearCardStart(wait.entry.card, it) }
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
        // Cards that name no sport (#206): the polled feeds' games, both sides STRONG; no feed is asked for them.
        val polled = needed.toList()
        for (entry in piggybacks) {
            val match = piggybackMatch(entry, polled) ?: continue
            noteStart(entry, emptyList(), match)
            unmatchedKeys -= entry.card.key
            learnLogos(match)
            matched[entry.card.key] = match
        }
        // A card no screen tracks any more keeps its last score, drawn at once if it is tracked again (live.23).
        val trackedKeys = tracked.value.values.flatten().mapTo(HashSet()) { it.card.key }
        for (key in raw.keys) {
            if (key in matched || key in trackedKeys) continue
            val last = lastDelayed[key] ?: continue
            if (kept.size >= MAX_REMEMBERED) kept.clear()
            kept[key] = KeptCard(last, nowMs)
        }
        raw = matched
        keptShown = if (kept.isEmpty()) emptyMap() else keptLocked(matched, nowMs)
        matchedStates.value = matched.values.associate { it.eventId to it.state }
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
            if (state == null) feeds[feed] = FeedState().also { fresh -> seedEarlyFailureLocked(feed, fresh) } else state.retiredAt = null
            if (pollers[feed]?.isActive == true) continue
            if (state == null || revived || restartStopped) pollers[feed] = pollerScope.launch { poll(feed, pollerScope) }
        }
        publishLocked()
    }

    /**
     * The leagues whose team lists are kept ([directoryLeagueOrder]): the inference lists, then those of the cards on
     * screen, then My sports, then followed teams'.
     */
    private fun directoryLeagues(
        cards: Map<String, List<TrackedCard>>,
        picked: MySports,
        followed: List<EspnLeague>
    ): List<EspnLeague> {
        // A bare "Basketball" card's women's college list too (item 4): two schools are told men's or women's by both lists.
        val onScreen = cards.values.flatten().flatMap { card ->
            card.leagues + listOfNotNull(
                card.fallback?.takeUnless(EspnLeagueMap::isWomenFallback),
                EspnLeagueMap.COLLEGE_BASKETBALL_WOMEN.takeIf { card.fallback == EspnLeagueMap.COLLEGE_BASKETBALL }
            )
        }
        // The named My sports leagues' lists (#159, soccer competitions included) tell games with no league apart.
        return directoryLeagueOrder(onScreen + picked.feeds() + picked.teamListLeagues() + followed)
    }

    /**
     * The [kept] scores to draw (live.23): a tracked card's, while no feed it routes to has answered since it was kept,
     * and the matcher still pairs the card with that game (a wrong score is worse than none). A card matched, one of
     * whose feeds has answered (or failed) since, or one kept longer than [KeptScores.MAX_AGE_MS] ago loses its kept
     * score for good. Cards that name no sport and tennis get none.
     */
    private fun keptLocked(matched: Map<String, ScoreSnapshot>, nowMs: Long): Map<String, KeptCard> {
        kept.values.removeAll { nowMs - it.at > KeptScores.MAX_AGE_MS || it.servedAt?.let { at -> nowMs - at > ScoreCadence.LIVE_MS } == true }
        val shown = HashMap<String, KeptCard>()
        for (entry in trackedUnion()) {
            val key = entry.card.key
            val keptCard = kept[key] ?: continue
            val leagues = remembered[key]?.let(::listOf) ?: entry.leagues
            if (entry.piggyback || entry.tennis || key in matched ||
                feedsOf(leagues, datesFor(entry.card)).any { feed -> feeds[feed]?.lastAttemptAt?.let { it > keptCard.at } == true }
            ) {
                kept -= key
                continue
            }
            if (matcher.match(entry.card, listOf(keptCard.snapshot), nowMs) != null) shown[key] = keptCard
        }
        return shown
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
                    logos.directory.resolve(league, side)?.let { urls += TeamLogoUrls.scaled(it.logo) }
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

    /**
     * live.27 (TV reports #29-#36: team channels, replays and passes listed live as "A vs B"): a live card confirmed
     * unmatched on [cardFeeds] (every one answered afresh) is no game ESPN has when [hasNoEspnGame] says so; its sources go
     * to [noEspnGames], else leave it. Never a tennis card (matched by surname), never one its addon does not list live.
     */
    private fun noteNoGame(entry: TrackedCard, cardFeeds: List<ScoreFeed>, nowMs: Long) {
        if (entry.liveKeys.isEmpty() || entry.tennis) return
        if (hasNoEspnGame(entry.card, cardFeeds, nowMs)) {
            noEspnGames.record(entry.liveKeys, entry.card.teams, nowMs)
        } else {
            noEspnGames.forget(entry.liveKeys)
        }
    }

    /**
     * True when ESPN has no game today for [card] (live.27). Fails open (false) unless all of:
     * - one of its leagues (never soccer/all, which has no team list) has ESPN's (US Eastern) today among [cardFeeds], and
     *   the loaded team list of that league names each of its two sides as one team, two different teams;
     * - ESPN gives no later start for it ([espnStarts]: a game listed live too early is Upcoming's, not this);
     * - neither team plays on any fresh scoreboard at hand of the card's leagues, its own days (yesterday's too, for an
     *   undated card: a late game across Eastern midnight) and any other day polled (ESPN's tomorrow, for a later
     *   start): not by ESPN id, nor by any name the matcher reads ([ScoreMatcher.touches], even WEAK).
     * The caller has checked that every one of [cardFeeds] answered afresh (no stale, failed or paused feed). An untagged
     * soccer card ([NoEspnGame.isUntaggedSoccer], live.28) is read against every soccer scoreboard polled (no extra
     * request), and fails open while any of ESPN's today among them has not answered afresh.
     */
    private fun hasNoEspnGame(card: ScoreCard, cardFeeds: List<ScoreFeed>, nowMs: Long): Boolean {
        if (espnStarts.startOf(card.key, nowMs) != null) return false
        val easternToday = Instant.ofEpochMilli(nowMs).atZone(EASTERN).toLocalDate()
        val leagues = cardFeeds.map { it.league }.distinct()
        val untagged = NoEspnGame.isUntaggedSoccer(leagues)
        val boards = feeds.entries.filter { (feed, _) -> feed.league in leagues || untagged && feed.league.sport == "soccer" }
        fun FeedState.answered() = fetched && !paused && isFresh(nowMs)
        if (untagged && boards.any { (feed, state) -> state.retiredAt == null && feed.date == easternToday && !state.answered() }) return false
        val events = boards
            .filter { (_, state) -> state.answered() }
            .flatMap { it.value.events }
            .distinctBy { it.eventId }
        // The rule itself is NoEspnGame's, shared with the nightly accuracy run.
        return NoEspnGame.hasNone(card, leagues, { league -> cardFeeds.any { it.league == league && it.date == easternToday } }, logos.directory, events, matcher)
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

    /**
     * A [TrackedCard.piggyback] card's game among the [polled] feeds (#206, release review): only for a card with a
     * start time, only on the feeds of its sport (the one a source names, else the sport whose loaded team list holds
     * both its sides), and only a game starting within [PIGGYBACK_NEAR_MS] of the card. A wrong sport's score is
     * worse than none, so a card no sport can be told for gets nothing.
     */
    private fun piggybackMatch(entry: TrackedCard, polled: List<ScoreFeed>): ScoreSnapshot? {
        val card = entry.card
        val start = card.startMs?.takeIf { card.hasTimeOfDay } ?: return null
        val sports = entry.piggybackSports
            ?: logos.directory.leaguesHolding(card.teams.home, card.teams.away).mapTo(HashSet()) { it.sport }
        if (sports.isEmpty()) return null
        val feeds = polled.filter { it.league.sport in sports }
        if (feeds.isEmpty()) return null
        return matchOn(card, feeds, strictMatcher)?.takeIf { match ->
            match.startMs != null && kotlin.math.abs(match.startMs - start) <= PIGGYBACK_NEAR_MS
        }
    }

    /** True when [match] starts within [WOMEN_FALLBACK_NEAR_MS] of [card]'s timed start (none: false). */
    private fun nearCardStart(card: ScoreCard, match: ScoreSnapshot): Boolean {
        val start = card.startMs?.takeIf { card.hasTimeOfDay } ?: return false
        val at = match.startMs ?: return false
        return kotlin.math.abs(at - start) <= WOMEN_FALLBACK_NEAR_MS
    }

    /**
     * True when the loaded [college] list holds both of [teams] as two schools and the [pro] list (if any) does not hold
     * both: a "Basketball" card of two schools (live.24), or a bare "Football" card of two schools (live.25, [pro] null:
     * soccer has no team list here). False while the lists are not loaded.
     */
    private fun schoolsOnly(college: EspnLeague, pro: EspnLeague?, teams: com.nuvio.tv.core.sportslive.TeamPair): Boolean {
        val directory = logos.directory
        val home = directory.resolve(college, teams.home) ?: return false
        val away = directory.resolve(college, teams.away) ?: return false
        if (home.id == away.id) return false
        if (pro == null) return true
        return directory.resolve(pro, teams.home) == null || directory.resolve(pro, teams.away) == null
    }

    private fun matchOn(card: ScoreCard, cardFeeds: List<ScoreFeed>, using: ScoreMatcher = matcher, tennis: Boolean = false): ScoreSnapshot? {
        val nowMs = now()
        // A card with no start is matched across ESPN's days: a league counts only once all its days are
        // in, so yesterday's final never stands alone while today's feed is loading, failing or paused.
        val usable = if (card.startMs != null) cardFeeds else cardFeeds.groupBy { it.league }.values
            .filter { days -> days.all { feed -> feeds[feed]?.let { it.isFresh(nowMs) && !it.paused } == true } }
            .flatten()
        val events = usable.flatMap { feed -> feeds[feed]?.takeIf { it.isFresh(nowMs) }?.events.orEmpty() }
            .distinctBy { it.eventId }
        return when {
            events.isEmpty() -> null
            tennis -> TennisMatcher.match(card, events)
            else -> using.match(card, events, now())
        }
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
        // Tier 1 H1: after a failure the interval is the backoff (up to Retry-After); a live score is cut sooner.
        return ScoreCadence.fresh(nowMs - success, intervalMs, failures, live = events.any { it.state == ScoreState.IN })
    }

    /** Millis until [feed]'s last live answer is too old to show after a failure ([ScoreCadence.LIVE_STALE_MS]); null: none. */
    private fun liveStaleInLocked(feed: ScoreFeed): Long? {
        val state = feeds[feed] ?: return null
        val success = state.lastSuccessAt ?: return null
        return ScoreCadence.liveStaleInMs(now() - success, state.failures, live = state.events.any { it.state == ScoreState.IN })
    }

    private fun publishLocked(off: Boolean = false) {
        if (off) {
            scoreDelay.apply(emptyMap(), 0L, now())
            nextRelease.value = null
            output.value = emptyMap()
            watchedOutput.value = emptyMap()
            holdOutput.value = null
            return
        }
        // Nothing before the profile's spoiler settings are known (live.23): a protected score must never flash.
        if (!protectionLoaded) {
            output.value = emptyMap()
            watchedOutput.value = emptyMap()
            return
        }
        // Read against the clock now, so a protection's 7 days run out without a settings change.
        val delay = ScoreWatch.delayMs(settingDelayMs, realTime, playing, nudgeSeconds)
        holdOutput.value = delay.takeIf { playing.isNotEmpty() && it > 0L }
        // A game first seen shows its score at once, except the games being watched (their picture runs behind).
        val delayed = scoreDelay.apply(raw, delay, now(), holdFirstSeen = ScoreWatch.watchedEvents(playing, raw))
        val keepable = keepable(delayed)
        // Scores, none keepable now (all protected or watched): an earlier write still waiting never goes to disk (L4).
        if (delayed.isNotEmpty() && keepable.isEmpty()) keptPending = null
        saveKept(keepable)
        lastDelayed = delayed
        val keptNow = keptServedLocked()
        val shown = if (keptNow.isEmpty()) delayed else keptNow + delayed
        // The games being watched: no score and no clock from us; the broadcast shows its own (Q18).
        val watched = ScoreWatch.watchedEvents(playing, shown)
        val served = if (watched.isEmpty()) shown else shown.mapValues { (_, event) -> if (event.eventId in watched) ScoreWatch.masked(event) else event }
        val stamping = protectionState.value.copy(nowMs = now())
        output.value = stamping.stampAll(served)
        watchedOutput.value = if (watched.isEmpty()) emptyMap() else stamping.stampAll(shown.filterValues { it.eventId in watched })
        // A kept score leaves one poll after it was first served, even with no answer to replace it.
        val keptUntil = keptShown.values.mapNotNull { it.servedAt }.minOrNull()?.takeIf { keptNow.isNotEmpty() }?.let { it + ScoreCadence.LIVE_MS + 1 }
        nextRelease.value = listOfNotNull(scoreDelay.nextChangeAt, keptUntil).minOrNull()
    }

    /**
     * The kept scores to serve now (live.23), flagged [ScoreSnapshot.kept]: each for one poll at most from when it was
     * first served, and none of a game being watched (its picture runs behind; it keeps the delay's first-seen hold).
     * A live game's kept score is drawn without its clock, scorers and situation (live.23 review A4): it may be minutes
     * old, so only the score shows until the first fresh answer.
     */
    private fun keptServedLocked(): Map<String, ScoreSnapshot> {
        if (keptShown.isEmpty()) return emptyMap()
        val at = now()
        val watched = ScoreWatch.watchedEvents(playing, keptShown.mapValues { it.value.snapshot } + raw)
        val served = HashMap<String, ScoreSnapshot>()
        for ((key, keptCard) in keptShown) {
            if (keptCard.snapshot.eventId in watched) continue
            val since = keptCard.servedAt ?: at.also { keptCard.servedAt = it }
            if (at - since > ScoreCadence.LIVE_MS) {
                kept -= key
                continue
            }
            served[key] = keptCard.snapshot.let { s ->
                if (s.state == ScoreState.IN) s.copy(kept = true, clockText = null, scorers = emptyList(), situation = null) else s.copy(kept = true)
            }
        }
        return served
    }

    /**
     * The [delayed] scores fit to keep on disk: never the games being watched (their picture runs behind) nor a
     * protected game (live.23 review): a hidden score is never written where it could be read back.
     */
    private fun keepable(delayed: Map<String, ScoreSnapshot>): Map<String, ScoreSnapshot> {
        if (delayed.isEmpty()) return delayed
        val watched = ScoreWatch.watchedEvents(playing, delayed)
        val protection = protectionState.value.copy(nowMs = now())
        return delayed.filterValues { it.eventId !in watched && !protection.protects(it) }
    }

    /** Writes [shown] (the delayed scores, unmasked) to [keptFile] a moment after the last change, off the poll path. */
    private fun saveKept(shown: Map<String, ScoreSnapshot>) {
        val file = keptFile ?: return
        if (shown.isEmpty()) return
        keptPending = KeptWrite(shown, now(), keptProfile)
        if (keptWrite?.isActive == true) return
        keptWrite = scope.launch(io) {
            while (true) {
                delay(KEPT_WRITE_DELAY_MS)
                val pending = lock.withLock { keptPending.also { keptPending = null } } ?: break
                file.write(KeptScores.encode(pending.games, pending.at, pending.profile))
            }
        }
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

    /** ESPN's (US Eastern) today and tomorrow at [nowMs]: where a game listed live too early is found (live.20). */
    private fun laterDates(nowMs: Long): List<LocalDate> {
        val today = Instant.ofEpochMilli(nowMs).atZone(EASTERN).toLocalDate()
        return listOf(today, today.plusDays(1))
    }

    private fun feedsOf(leagues: List<EspnLeague>, dates: List<LocalDate>): List<ScoreFeed> =
        leagues.flatMap { league -> dates.map { ScoreFeed(league, it) } }

    companion object {
        /**
         * Whether [track] would follow [item] for a score at all (live.25 #226): a game a feed covers by its league, a
         * tennis match, or two sides with no sport anywhere (#206). False for a race, a fight card or any sport with no
         * score feed, which therefore never gets a score: it reads just "Live", never "Live · No score yet". Mirrors
         * [track]'s choices without its directory work.
         */
        fun hasScoreFeed(item: SportsLiveItem): Boolean {
            if (ScoreCard.from(item) == null) return false
            val sources = listOf(item) + item.alternates
            if (sources.any { EspnLeagueMap.leaguesFor(it).isNotEmpty() || EspnLeagueMap.tennisLeaguesFor(it).isNotEmpty() }) return true
            return !item.isChannel && item.teams != null && sources.none { SportsLiveSportKind.tagOf(it) != null }
        }

        /** [track] screen ids. */
        const val SCREEN_HOME = "home"
        const val SCREEN_SECTION = "sports_live"
        /** The player's live games panel, tracked only while it is open. */
        const val SCREEN_PLAYER_PANEL = "player_panel"
        /** Followed teams' live games, tracked for alerts (#42) while the app is in the foreground. */
        const val SCREEN_ALERTS = "alerts"
        /** The game a player plays, tracked while that player is started, for its full-time toast (#60). */
        const val SCREEN_PLAYER_NOW = "player_now"

        private const val WINDOW_MS = 3 * 60 * 60 * 1000L
        /** The women's-league fallback's game is at most this far from the card's start (live.23 review B1). */
        private const val WOMEN_FALLBACK_NEAR_MS = 3 * 60 * 60 * 1000L
        private const val MAX_REMEMBERED = 2_000
        /**
         * Feeds polled at once, at most; followed teams' games claim theirs first (and poll faster while live). 32, not 16
         * (live.23 TV, Saturday 2026-11-21: 82 live games across ~20 leagues; at 16, Serie A, Bundesliga, La Liga, Ligue 2
         * and college football got no slot). Feeds without a live game poll every 5 min, so the extra load is mostly live.
         */
        internal const val MAX_FEEDS = 32
        private const val RETAIN_RETIRED_MS = 10 * 60_000L
        private const val LOGO_WRITE_DELAY_MS = 2_000L
        /** The kept scores are written at most this often (live.23). */
        internal const val KEPT_WRITE_DELAY_MS = 10_000L
        /** Feeds fetched ahead when scores are first collected (live.23). */
        internal const val PREWARM_FEEDS = 6
        private const val COLLEGE_MAX_MISSES = 2
        /** The logo directory waits this long after polling starts, so the first scoreboards go first. */
        internal const val DIRECTORY_START_DELAY_MS = 2_000L
        /** "Retry now" at most this often, and never on a feed asked for within this long. */
        internal const val RETRY_MIN_INTERVAL_MS = 30_000L

        /** [prefetchUpcoming]: each feed at most once in this time (#143). */
        internal const val UPCOMING_PREFETCH_EVERY_MS = 15 * 60_000L
        /** Scores Off: how often a live card's ESPN start is read again ([checkSchedules]). */
        internal const val SCHEDULE_CHECK_MS = 15 * 60_000L

        /** How far from a feed-less card's start its game may start ([piggybackMatch]): the matcher's own window. */
        internal const val PIGGYBACK_NEAR_MS = 3 * 60 * 60_000L
        private val SLUG = Regex("""[a-z0-9._-]+""")

        /** [event] with no score: no points, clock, scorers, situation or record (Scores Off reads starts only). */
        internal fun scheduleOnly(event: ScoreSnapshot): ScoreSnapshot = event.copy(
            clockText = null, isFinal = false, scorers = emptyList(), situation = null,
            home = event.home.copy(score = null, linescores = emptyList(), record = null, form = null),
            away = event.away.copy(score = null, linescores = emptyList(), record = null, form = null)
        )
        private val EASTERN: ZoneId = ZoneId.of("America/New_York")

        /**
         * The team lists asked for, in order: the leagues an unrouted two-team card may be inferred into (#132 P4) first,
         * small lists kept a week, so a pass bounded to [TeamDirectorySync.MAX_PER_PASS] never leaves inference without
         * one (live.20); then [others] in their order. Only leagues the directory covers, each once.
         */
        internal fun directoryLeagueOrder(others: List<EspnLeague>): List<EspnLeague> =
            (SportsLiveSportInference.leagues + others).filter(TeamDirectory::covers).distinct()

        /**
         * [source], kept alive by a settings read error (L8, live.23 review): an error before the first value gives the
         * profile defaults (protective: "Hide my teams' results until I reveal" on), so scores still show; a later error keeps the last value.
         */
        internal fun protectionOrDefaults(source: Flow<SpoilerProtection>): Flow<SpoilerProtection> {
            var last: SpoilerProtection? = null
            return source.onEach { last = it }.catch { error ->
                if (error is CancellationException) throw error
                emit(last ?: SpoilerProtection(myTeamsResults = SportsLivePreferencesDataStore.MY_TEAMS_RESULTS_HIDDEN_DEFAULT))
            }
        }

        /** A followed team's stored "sport/league", as a league; null when it has none. */
        internal fun leagueOfPath(path: String?): EspnLeague? =
            path?.split('/')?.takeIf { it.size == 2 && it.all(String::isNotBlank) }?.let { EspnLeague(it[0], it[1]) }
    }
}

/** What a screen calls to have its cards scored: [track] the cards it shows, [untrack] when it leaves. */
interface LiveScoreTracking {
    fun track(screenId: String, cards: List<SportsLiveItem>)
    fun untrack(screenId: String)
}
