package com.nuvio.tv.core.sportslive.alerts

import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.CrunchTime
import com.nuvio.tv.core.sportslive.scores.CrunchCandidate
import com.nuvio.tv.core.sportslive.scores.CrunchAlerts
import com.nuvio.tv.core.sportslive.scores.BigGame
import androidx.annotation.VisibleForTesting
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.sportslive.SportsLiveDisplayText
import com.nuvio.tv.core.sportslive.SportsLiveGameReadiness
import com.nuvio.tv.core.sportslive.SportsLiveItemBucketer
import com.nuvio.tv.core.sportslive.SportsLiveLiveCheck
import com.nuvio.tv.data.repository.SportsLiveKickoffProbe
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.LiveScoresRepository
import com.nuvio.tv.core.sportslive.scores.ResultSeen
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreSide
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreTeam
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.SpoilerProtection
import com.nuvio.tv.core.sportslive.scores.SpoilerProtectionStore
import com.nuvio.tv.core.sportslive.scores.TeamFollows
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.domain.model.ClaimedCatalog
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.repository.SportsLiveLoad
import com.nuvio.tv.domain.repository.SportsLiveRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import javax.inject.Inject
import javax.inject.Singleton

/** How an overlay was closed. */
enum class AlertAnswer { WATCH, LATER, DISMISS, TIMEOUT }

/**
 * Alerts for followed teams (#42) and game reminders (#44), while the app is in the foreground: the
 * host runs [runInForeground] while started, tells [setWatching] what plays, shows [current] and
 * reports each overlay's end to [close]. One overlay at a time, kickoff prompts first.
 *
 * Alerts come only from [LiveScoresRepository]'s delayed scores. Beyond what screens already track,
 * only followed teams' live (or kicking-off) games in the followed teams' leagues are tracked
 * ([SportsLiveAlertScope]); a reminded game nobody tracks is prompted at its start time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SportsLiveAlerts @Inject constructor(
    private val repo: SportsLiveRepository,
    private val prefs: SportsLivePreferencesDataStore,
    private val liveScores: LiveScoresRepository,
    /** Followed teams' games watched live are not spoiler-protected afterwards (#151). */
    private val spoilers: SpoilerProtectionStore,
    /** Probes a game's links at kickoff (#162); null (tests): every kickoff fires unchecked. */
    private val kickoffProbe: SportsLiveKickoffProbe? = null
) {
    @VisibleForTesting
    internal val engine = SportsLiveAlertEngine()
    /** Crunch-time alerts' limits (#137); guarded by [lock]. */
    private val crunch = CrunchAlerts()
    private val lock = Any()

    /** Kickoff prompts waiting for a live link (#162); guarded by [holdsLock], probed while holding it. */
    private val holds = SportsLiveKickoffHolds(
        probe = { item ->
            if (System.currentTimeMillis() < debugNotLiveUntilMs) {
                android.util.Log.d("SportsLiveAlerts", "kickoff hold: no live link yet (debug)")
                SportsLiveLiveCheck.Outcome(live = null, checkable = true)
            } else {
                kickoffProbe?.probe(item) ?: SportsLiveLiveCheck.Outcome(live = null, checkable = false)
            }
        },
        // 1b: a link the my-games checks verified fires the prompt at once ("Stream ready · Watch now?").
        heldLink = { item -> if (System.currentTimeMillis() < debugNotLiveUntilMs) null else SportsLiveGameReadiness.shared.heldLink(item) }
    )

    /** Debug builds only: until then the kickoff probe finds no live link (the #162 emulator check). */
    @Volatile
    private var debugNotLiveUntilMs = 0L
    private val holdsLock = Mutex()
    private val holdsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Waiting overlays with when they were raised; guarded by [lock]. */
    private val queue = ArrayList<Pair<SportsLiveAlert, Long>>()
    private val currentFlow = MutableStateFlow<AlertCard?>(null)
    private val watching = MutableStateFlow(SportsLiveWatching())
    private var cardCount = 0L

    /**
     * The overlay to show now; null: none. One alert per card (live.24): when several wait, the highest priority one
     * shows ([SportsLiveAlertCards.priority]) and the others wait their turn ([SportsLiveAlertCards.addWaiting]).
     */
    val current: StateFlow<AlertCard?> = currentFlow.asStateFlow()

    /** What plays now, as the host last said. */
    val watchingNow: StateFlow<SportsLiveWatching> = watching.asStateFlow()

    /** The active profile's reminders and opt-outs. */
    val reminders: Flow<RemindersState> = prefs.reminders

    /** Bell on [item] pressed: reminded now? */
    suspend fun toggleReminder(item: SportsLiveItem): Boolean =
        prefs.updateReminders { GameReminders.toggle(it, item, System.currentTimeMillis()) }.isReminded(item)

    /** Bell on an ESPN game with no card pressed (a Team page's next game): reminded now? */
    suspend fun toggleReminder(event: ScoreSnapshot): Boolean =
        prefs.updateReminders { GameReminders.toggle(it, event, System.currentTimeMillis()) }.isReminded(event)

    /** "Remind me for every ‹team› game" (or Stop reminding…) on [side] of [event] (#154): on the list now? */
    suspend fun toggleTeamReminder(event: ScoreSnapshot, side: ScoreTeam): Boolean =
        GameReminders.isTeamReminded(prefs.updateReminders { GameReminders.toggleTeam(it, event, side) }, event, side)

    /** Settings → Alerts → "Teams I'm reminded about": OK takes [team] off (#154). */
    suspend fun removeTeamReminder(team: FollowedTeam) {
        prefs.updateReminders { GameReminders.removeTeam(it, team) }
    }

    private val reminderNoticeFlow = MutableStateFlow<ReminderNotice?>(null)
    private var reminderNoticeCount = 0L

    /** "Reminder set" / "Reminder removed" after the last bell press, as the app's toast card; null: none. */
    val reminderNotice: StateFlow<ReminderNotice?> = reminderNoticeFlow.asStateFlow()

    /** Shows "Reminder set" ([on]) or "Reminder removed" for [item]'s game. */
    fun noticeReminder(item: SportsLiveItem, on: Boolean) {
        reminderNoticeFlow.value = ReminderNotice(++reminderNoticeCount, on, item.startMs, title = SportsLiveDisplayText.name(item.meta.name), item = item)
    }

    /** Shows "Reminder set" ([on]) or "Reminder removed" for an ESPN game no card plays yet. */
    fun noticeReminder(event: ScoreSnapshot, on: Boolean) {
        fun name(team: ScoreTeam) = listOf(team.shortDisplayName, team.displayName, team.name, team.abbreviation).firstOrNull { !it.isNullOrBlank() }
        reminderNoticeFlow.value = ReminderNotice(++reminderNoticeCount, on, event.startMs, away = name(event.away), home = name(event.home))
    }

    /** [notice] has been shown its time: it goes, unless a newer one took its place. */
    fun closeReminderNotice(notice: ReminderNotice) {
        reminderNoticeFlow.compareAndSet(notice, null)
    }

    /** "At tip-off" as last read (research Q17): Remind me, or Switch automatically. */
    val tipOff: com.nuvio.tv.core.sportslive.SportsLiveTipOff get() = lastSettings.tipOff

    fun setWatching(now: SportsLiveWatching) {
        watching.value = now
        synchronized(lock) {
            val card = currentFlow.value ?: return@synchronized
            val alert = card.alert as? SportsLiveAlert.Kickoff ?: return@synchronized
            if (alert.noStreamYet) return@synchronized
            val nowMs = System.currentTimeMillis()
            when {
                // Review R6: the viewer left the movie into this very game: nothing to ask, the game is answered.
                alert.passive && SportsLiveAlertEngine.isPlaying(alert.item, now, alert.snapshot) -> {
                    engine.answered(alert.item)
                    currentFlow.value = null
                    showNextLocked(nowMs)
                }
                // Review R6: into dual screen, which holds kickoffs: the offer waits until it closes.
                alert.passive && now.dual -> {
                    engine.deferOffer(alert.item)
                    currentFlow.value = null
                    showNextLocked(nowMs)
                }
                // The passive kickoff card over a movie (#91): the viewer paused or left it, so the card asks now, as a new
                // card. It is the game's one deferred offer.
                alert.passive && SportsLiveAlertEngine.viewerFree(now) ->
                    currentFlow.value = AlertCard(++cardCount, alert.copy(passive = false, deferred = true), nowMs)
                // The movie plays again while the card asks (review R1): OK is the movie's again, so the card goes. The
                // deferred offer was the one offer (answered); a first kickoff keeps its one offer for the next pause.
                !alert.passive && SportsLiveAlertEngine.moviePlays(now) -> {
                    if (alert.deferred) engine.answered(alert.item) else engine.deferOffer(alert.item)
                    currentFlow.value = null
                    showNextLocked(nowMs)
                }
            }
        }
    }

    /** The Scores setting and alert switches as last read: a queued toast is checked against them before it shows. */
    @Volatile
    private var lastMode: ScoresMode = ScoresMode.SHOW

    @Volatile
    private var lastSettings = SportsLiveAlertSettings()

    /**
     * Spoiler protection as last read (live.24 RC2 review R2): a waiting score or full time is checked against it
     * before it shows, as Watch later or "Hide my teams' results" may have changed while it waited.
     */
    @Volatile
    private var lastProtection = SpoilerProtection()

    /** The profile the waiting alerts were raised under; guarded by [lock]. */
    private var lastProfile: Int? = null

    /**
     * The ESPN games a card showed a live score of (a score, or a close finish with its score), this profile: their
     * Full time card shows the score even when "Hide my teams' results" hides it (live.28, user decision: option 1).
     * Guarded by [lock].
     */
    private val liveScoreShown = LinkedHashSet<String>()

    /** Games ESPN said are over (by [SportsLiveAlertEngine.gameKey]): no longer tracked. Guarded by [lock]. */
    private val endedGames = HashSet<String>()

    /** A load asked for a due reminder this session (once). */
    @Volatile
    private var reminderLoadAsked = false

    /**
     * Where the alerts' work runs (#207): the host starts [runInForeground] from a composition effect, on the main
     * thread, and its pass over every listed game each 5 s froze the UI on a slow box. Tests swap it.
     */
    @VisibleForTesting
    internal var compute: CoroutineContext = Dispatchers.Default

    /** Runs until cancelled (the app left the foreground): tracks, decides and queues alerts, on [compute]. */
    suspend fun runInForeground() = withContext(compute) {
        val cards = combine(repo.latest, repo.claimedCatalogs, repo.followedTeams) { load, claimed, followed ->
            candidates(load, claimed, followed)
        }.flowOn(Dispatchers.Default)
        val sports = combine(prefs.mySports, repo.followedTeams) { picked, followed -> picked.withFollowed(followed) }
        val base = combine(cards, prefs.reminders, prefs.alertSettings, repo.followedTeams, sports) { shown, reminded, settings, followed, mySports ->
            Base(shown, reminded, settings, followed, mySports)
        }.stateIn(this, SharingStarted.Eagerly, null)
        // The 5 s clock runs only while there is something to time: a reminder, or a reminded or followed game.
        val clock = base.map { it?.hasWork == true }.distinctUntilChanged().flatMapLatest { busy -> if (busy) ticks() else flowOf(Unit) }
        val tracked = MutableStateFlow<List<SportsLiveItem>>(emptyList())
        try {
            launch { liveScores.protection.collect { onProtection(it) } }
            launch { prefs.activeProfileId.collect { onProfile(it) } }
            // The tracked cards are polled only while someone collects the scores: this, while any are tracked.
            launch {
                tracked.map { it.isNotEmpty() }.distinctUntilChanged().collectLatest { any ->
                    if (any) liveScores.scores.collect { }
                }
            }
            // Kickoff prompts held for a live link (#162): probed when due, outside the alerts' lock.
            launch {
                while (true) {
                    val events = holdsLock.withLock {
                        if (holds.isEmpty) {
                            emptyList()
                        } else {
                            holds.tick { alert -> SportsLiveAlertEngine.isPlaying(alert.item, watching.value, alert.snapshot) }
                        }
                    }
                    for (event in events) {
                        val alert = when (event) {
                            is SportsLiveKickoffHolds.Event.Fire -> event.alert
                            is SportsLiveKickoffHolds.Event.GiveUp -> event.alert.copy(noStreamYet = true)
                            // No "No working stream yet" heads-up any more (live.24): the game is tagged "Started" in
                            // Your teams, and the card follows once a stream is up.
                            is SportsLiveKickoffHolds.Event.HeadsUp -> null
                        }
                        if (alert != null) enqueue(listOf(alert), System.currentTimeMillis())
                    }
                    delay(TICK_MS)
                }
            }
            combine(base.filterNotNull(), liveScores.mode, watching, clock) { b, mode, now, _ ->
                Inputs(b.cards, b.reminders, b.settings, b.followed, mode, now, b.mySports)
            }.collect { inputs ->
                val nowMs = System.currentTimeMillis()
                onRules(inputs.mode, inputs.settings)
                val scores = liveScores.scores.value
                val ended = synchronized(lock) {
                    inputs.cards.forEach { card ->
                        val state = scores[card.streamKey]?.state
                        if (state == ScoreState.POST) endedGames += SportsLiveAlertEngine.gameKey(card)
                    }
                    if (endedGames.size > MAX_ENDED) endedGames.clear()
                    endedGames.toSet()
                }
                // Followed teams' games, then (#137) live games in My sports for crunch time, while those alerts are on.
                val crunchCards = if (inputs.settings.closeGameAlerts && inputs.mode != ScoresMode.OFF) {
                    SportsLiveAlertScope.crunchCardsToTrack(inputs.cards, inputs.mySports, nowMs, ended)
                } else {
                    emptyList()
                }
                val toTrack = (SportsLiveAlertScope.cardsToTrack(inputs.cards, inputs.followed, nowMs, ended) + crunchCards).distinctBy { it.streamKey }
                if (toTrack != tracked.value) {
                    tracked.value = toTrack
                    liveScores.track(LiveScoresRepository.SCREEN_ALERTS, toTrack)
                }
                // A reminder due with no load this session: Live now loads as on a normal open (once).
                if (!reminderLoadAsked && SportsLiveAlertScope.needsLoad(inputs.reminders.reminders, repo.latest.value != null, nowMs)) {
                    reminderLoadAsked = true
                    launch { loadQuietly() }
                }
                val watchedLive = evaluate(inputs, scores, nowMs)
                ResultSeen.shared.onLiveTick(watchedLive.map { it.eventId }, nowMs) // live.20 1c: watched live a while
                // Review S5: zapping past a followed team's game no longer ends its protection; a stretch of play does.
                val watchedAWhile = watchedLongEnough(watchedLive, ResultSeen.shared::liveWatchedMs)
                if (watchedAWhile.isNotEmpty()) launch { spoilers.onWatchedLive(watchedAWhile) }
                evaluateCrunch(inputs, crunchCards, scores, nowMs)
            }
        } finally {
            liveScores.untrack(LiveScoresRepository.SCREEN_ALERTS)
        }
    }

    private class Base(
        val cards: List<SportsLiveItem>,
        val reminders: RemindersState,
        val settings: SportsLiveAlertSettings,
        val followed: List<FollowedTeam>,
        val mySports: MySports
    ) {
        // A live game is work too while crunch-time alerts are on (#137).
        val hasWork: Boolean
            get() = reminders.reminders.isNotEmpty() || cards.any { it.followed || reminders.isReminded(it) } ||
                (settings.closeGameAlerts && cards.any { it.isLive })
    }

    /** The repository's normal load (a minute-old one is reused; channels carried over); a failure is ignored. */
    private suspend fun loadQuietly() {
        try {
            repo.load(force = false, includeChannels = false)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private class Inputs(
        val cards: List<SportsLiveItem>,
        val reminders: RemindersState,
        val settings: SportsLiveAlertSettings,
        val followed: List<FollowedTeam>,
        val mode: ScoresMode,
        val watching: SportsLiveWatching,
        val mySports: MySports
    )

    /** Queues the alerts due; returns the followed teams' live games playing now (watched live, #151). */
    private fun evaluate(inputs: Inputs, scores: Map<String, ScoreSnapshot>, nowMs: Long): List<ScoreSnapshot> {
        val reminders = inputs.reminders.copy(remindMyTeams = inputs.settings.remindMyTeams)
        val games = inputs.cards.mapNotNull { item ->
            val event = if (inputs.mode == ScoresMode.OFF) null else ScoreChips.matchedEvent(item, scores[item.streamKey])
            // Reminder-only teams (#154) by ESPN id on the strict game, else by name: one game, one prompt.
            val reminded = reminders.isReminded(item, event)
            if (!reminded && !item.followed) return@mapNotNull null
            AlertGame(item, event, reminded, event?.let { followedSides(inputs.followed, it) }.orEmpty())
        }
        val raised = synchronized(lock) { engine.update(nowMs, games, inputs.settings, inputs.mode, inputs.watching) }
        if (raised.isNotEmpty()) {
            // A first kickoff prompt waits for a live link (#162); a re-ask after Later shows as it comes.
            val (held, shown) = raised.partition { it is SportsLiveAlert.Kickoff && SportsLiveAlerts.holdsFirst(it) }
            if (held.isNotEmpty()) holdsScope.launch { holdsLock.withLock { held.forEach { holds.hold(it as SportsLiveAlert.Kickoff) } } }
            if (shown.isNotEmpty()) enqueue(shown, nowMs)
        }
        return games.mapNotNull { game ->
            game.snapshot?.takeIf {
                it.state == ScoreState.IN && game.followedSides.isNotEmpty() && SportsLiveAlertEngine.isPlaying(game.item, inputs.watching)
            }
        }
    }

    /**
     * Crunch time (#137): of the live games in My sports ([tracked]), the one to alert now, at most one per game and
     * one every three minutes, never the game playing, the biggest first ([CrunchAlerts]). Not over a movie or show,
     * nor in a stream's first seconds; the score under Show only, none under Off.
     */
    private fun evaluateCrunch(inputs: Inputs, tracked: List<SportsLiveItem>, scores: Map<String, ScoreSnapshot>, nowMs: Long) {
        if (!inputs.settings.closeGameAlerts || inputs.mode == ScoresMode.OFF || tracked.isEmpty()) return
        val watching = inputs.watching
        val watchingFollowed = inputs.cards.any { it.followed && SportsLiveAlertEngine.isPlaying(it, watching) }
        if (!crunchMayShow(watching, watchingFollowed)) return
        if (watching.streamStartedAtMs?.let { nowMs - it < SportsLiveAlertEngine.STREAM_SETTLE_MS } == true) return
        val byKey = HashMap<String, SportsLiveItem>()
        val candidates = tracked.mapNotNull { item ->
            val event = ScoreChips.matchedEvent(item, scores[item.streamKey])?.takeIf { it.individual == null && !it.spoilerProtected && inputs.mySports.includes(it) }
                ?: return@mapNotNull null
            byKey[item.streamKey] = item
            CrunchCandidate(item.streamKey, event, BigGame.rate(event, followed = item.followed).score, SportsLiveAlertEngine.isPlaying(item, watching))
        }
        val pick = synchronized(lock) { crunch.next(nowMs, bigGamesOnly(candidates)) } ?: return
        val moment = CrunchTime.of(pick.event) ?: return
        val item = byKey[pick.key] ?: return
        enqueue(listOf(SportsLiveAlert.Crunch(item, pick.event, moment, namesOnly = inputs.mode == ScoresMode.HIDE_UNTIL_FOCUSED)), nowMs)
    }

    /**
     * Debug builds only (the #137 emulator check): one crunch-time alert for a live game now listed and not playing,
     * its strict ESPN match when known, else a made-up NCAAM 75–72 with 0:40 left. Nothing in a release build.
     */
    suspend fun debugCrunch() {
        if (!BuildConfig.IS_DEBUG_BUILD) return
        val card = candidates(repo.latest.value, repo.claimedCatalogs.first(), repo.followedTeams.first())
            .firstOrNull { it.isLive && it.teams != null && !SportsLiveAlertEngine.isPlaying(it, watching.value) } ?: return
        val teams = checkNotNull(card.teams)
        fun side(name: String, score: Int) = ScoreTeam(null, null, name, name, name, null, null, score, emptyList())
        val snapshot = ScoreChips.matchedEvent(card, liveScores.scores.value[card.streamKey])?.takeIf { CrunchTime.isCrunch(it) } ?: ScoreSnapshot(
            eventId = "debug-crunch-${card.streamKey}", league = EspnLeague("basketball", "mens-college-basketball"), startMs = card.startMs,
            state = ScoreState.IN, clockText = "0:40 - 2nd Half", isFinal = false, home = side(teams.home, 75), away = side(teams.away, 72)
        )
        val moment = CrunchTime.of(snapshot) ?: return
        val mode = liveScores.mode.first()
        synchronized(lock) { crunch.force(System.currentTimeMillis(), CrunchCandidate(card.streamKey, snapshot, 0.0, watching = false)) }
        android.util.Log.d("SportsLiveAlerts", "debugCrunch: ${snapshot.eventId}")
        enqueue(listOf(SportsLiveAlert.Crunch(card, snapshot, moment, namesOnly = mode == ScoresMode.HIDE_UNTIL_FOCUSED)), System.currentTimeMillis())
    }

    /** The spoiler protection now in force; read when a waiting alert is about to show. */
    @VisibleForTesting
    internal fun onProtection(protection: SpoilerProtection) {
        lastProtection = protection
        recheckCurrent()
    }

    /** The Scores setting and alert switches now; the card up is read again against them (tier 1 C6). */
    @VisibleForTesting
    internal fun onRules(mode: ScoresMode, settings: SportsLiveAlertSettings) {
        lastMode = mode
        lastSettings = settings
        recheckCurrent()
    }

    /**
     * The card up under the Scores setting, switches and spoiler protection as they are now (tier 1 C6): a card may wait
     * up under an overlay while Scores turns to Hide, so its score goes (a score or close finish card with it, a full
     * time to names only). The same id is kept, so the host keeps its time on screen.
     */
    private fun recheckCurrent() {
        synchronized(lock) {
            val card = currentFlow.value ?: return
            val nowMs = System.currentTimeMillis()
            val next = underProtection(card.alert, lastProtection.copy(nowMs = nowMs), liveScoreShown::contains)
                ?.let { SportsLiveAlertEngine.stillShowable(it, lastMode, lastSettings) }
            if (next == null) {
                currentFlow.value = null
                showNextLocked(nowMs)
            } else if (next != card.alert) {
                currentFlow.value = card.copy(alert = next)
            }
        }
    }

    /**
     * The active profile is [profileId]: after a switch the alerts still waiting belong to the profile before, whose
     * spoiler protection may differ (live.24 RC2 review R2), so they go, with the card up.
     */
    @VisibleForTesting
    internal fun onProfile(profileId: Int) = synchronized(lock) {
        if (lastProfile != null && lastProfile != profileId) {
            queue.clear()
            liveScoreShown.clear()
            // The card up belongs to the profile before too (live.24 RC3 review L1).
            currentFlow.value = null
        }
        lastProfile = profileId
    }

    @VisibleForTesting
    internal fun enqueue(alerts: List<SportsLiveAlert>, nowMs: Long) = synchronized(lock) {
        // One alert per card (live.24); the others wait their turn, one per game and kind, a few at most (live.24 RC
        // review M1: they were dropped while a card was up or waiting, a full time for good).
        for (alert in alerts) SportsLiveAlertCards.addWaiting(queue, alert, nowMs)
        if (currentFlow.value == null) showNextLocked(nowMs)
    }

    /** The overlay showing [card] closed with [answer]; the next one shows. */
    fun close(card: AlertCard, answer: AlertAnswer) = synchronized(lock) {
        val nowMs = System.currentTimeMillis()
        val alert = card.first
        if (alert is SportsLiveAlert.Kickoff && alert.passive) {
            // Over a movie that plays (#91): not answered; its one offer waits for the viewer to pause or leave.
            engine.deferOffer(alert.item)
        } else if (alert is SportsLiveAlert.Kickoff && alert.deferred && answer == AlertAnswer.LATER) {
            // The deferred offer went stale unseen (a panel over the paused movie, review R8): it waits for the next pause,
            // never as Later's re-ask, which would come over the playing movie.
            engine.deferOffer(alert.item)
        } else if (alert is SportsLiveAlert.Kickoff && alert.noStreamYet) {
            // The note (#162): Remind me in 10 min probes once more then; anything else, done.
            engine.answered(alert.item)
            if (answer == AlertAnswer.LATER) holdsScope.launch { holdsLock.withLock { holds.remindLater(alert) } }
        } else if (alert is SportsLiveAlert.Kickoff) {
            when (answer) {
                // The kickoff toast timing out is answered too (research Q17: one alert per game; the game stays
                // in Your teams as "Started").
                AlertAnswer.WATCH, AlertAnswer.DISMISS, AlertAnswer.TIMEOUT -> engine.answered(alert.item)
                AlertAnswer.LATER -> engine.later(alert.item, nowMs)
            }
        }
        // By id: only the card the host closed goes.
        if (currentFlow.value?.id != card.id) return@synchronized
        currentFlow.value = null
        showNextLocked(nowMs)
    }

    /**
     * [queued] as it may show now, or null: the Scores setting or a switch may have changed while it
     * waited; a toast that waited too long is old news; nothing about the game now playing; a full time
     * already announced (the toast for the game playing, #60) is dropped; spoiler protection is read again
     * ([underProtection]).
     */
    private fun showable(queued: SportsLiveAlert, nowMs: Long, raisedAt: Long): SportsLiveAlert? {
        // Protection first (it may show a hidden final's score, live.28 option 1), then the Scores setting and switches.
        val protectedNow = underProtection(queued, lastProtection.copy(nowMs = nowMs), liveScoreShown::contains) ?: return null
        val next = SportsLiveAlertEngine.stillShowable(protectedNow, lastMode, lastSettings) ?: return null
        if (SportsLiveAlertCards.expired(next, raisedAt, nowMs)) return null
        if (!mayShowOver(next, watching.value)) return null
        if (SportsLiveAlertEngine.isPlaying(next.item, watching.value, SportsLiveAlertEngine.snapshotOf(next))) {
            // A reminder for a game on screen is satisfied: it never fires later either.
            if (next is SportsLiveAlert.Kickoff) engine.answered(next.item)
            return null
        }
        // The deferred offer never shows over a movie that plays (review R1): it waits for the next pause.
        if (next is SportsLiveAlert.Kickoff && next.deferred && SportsLiveAlertEngine.moviePlays(watching.value)) {
            engine.deferOffer(next.item)
            return null
        }
        if (!SportsLiveAlertEngine.claimToShow(next)) return null
        return next
    }

    /**
     * The next card (live.24): of the alerts waiting, the highest priority one that may show; the others keep waiting
     * (live.24 RC review M1), a score or close finish until it is old news ([SportsLiveAlertCards.expired]).
     */
    private fun showNextLocked(nowMs: Long) {
        while (queue.isNotEmpty()) {
            val at = SportsLiveAlertCards.next(queue) ?: break
            val (queued, raisedAt) = queue.removeAt(at)
            val next = showable(queued, nowMs, raisedAt) ?: continue
            // A kickoff or full time that waited behind another card gets its own time to show from now; a score stays
            // dated from when it was raised.
            val from = if (next is SportsLiveAlert.Kickoff || next is SportsLiveAlert.FullTime) nowMs else raisedAt
            // Over a movie that plays the kickoff card shows passive (#91), decided now, as it shows.
            val shown = if (next is SportsLiveAlert.Kickoff && SportsLiveAlertEngine.kickoffPassive(next, watching.value)) next.copy(passive = true) else next
            currentFlow.value = AlertCard(++cardCount, shown, from)
            // A Full time card showing its score: the result is known (live.20 1c). Recorded as it shows, never as it is
            // raised (l24 polish): a waiting one may still show as "result hidden" or not at all, and a known result
            // takes its replay out of Catch up and Your teams.
            if (next is SportsLiveAlert.FullTime && !next.namesOnly) ResultSeen.shared.finalScoreShown(next.snapshot.eventId)
            val liveScoreOf = SportsLiveAlertEngine.snapshotOf(next)?.eventId?.takeIf { showsLiveScore(next) }
            if (liveScoreOf != null) {
                if (liveScoreShown.size >= MAX_ENDED) liveScoreShown.remove(liveScoreShown.first())
                liveScoreShown += liveScoreOf
            }
            return
        }
        currentFlow.value = null
    }

    /**
     * The card to play for Watch on [item]'s prompt: the newest load's copy of the game (non-forced,
     * so a load under a minute old is reused), else [item]; marked live, as it has kicked off, so it
     * plays its best stream (#56).
     */
    suspend fun cardToWatch(item: SportsLiveItem): SportsLiveItem {
        val load = try {
            repo.load()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            repo.latest.value
        }
        val reminder = GameReminders.of(item)
        val claimed = repo.claimedCatalogs.first()
        val followed = repo.followedTeams.first()
        // Two whole-tab sortings: never on the main thread a prompt's Watch is pressed on (#207).
        val fresh = withContext(compute) { candidates(load, claimed, followed) }.firstOrNull { card ->
            card.streamKey == item.streamKey || (reminder != null && GameReminders.matches(reminder, card))
        }
        val card = fresh ?: item
        return if (card.isLive) card else card.copy(isLive = true)
    }

    /**
     * The replay to play for [alert] (review M1: OK on "‹Team› game is over · result hidden"), from the newest load's
     * Replays matched to its ESPN game; null when none is listed yet. It plays spoiler-safe, from the start.
     */
    suspend fun replayFor(alert: SportsLiveAlert.FullTime): SportsLiveItem? = withContext(compute) {
        val load = repo.latest.value ?: return@withContext null
        val claimed = repo.claimedCatalogs.first()
        val keys = claimed.mapTo(HashSet()) { it.key }
        val items = load.items.filter { it.catalogKey in keys }
        val replays = SportsLiveItemBucketer.bucketTab(items, SportsLiveTab.REPLAYS, load.loadedAtMs, claimed.map { it.addon.id }.distinct())
        com.nuvio.tv.core.sportslive.SportsLiveEndedGames.replayFor(alert.snapshot, replays, System.currentTimeMillis())
    }

    /**
     * Debug builds only (the #136 emulator check, when no followed team is scoring): [count] score toasts,
     * close together, for live games now listed and not playing, the second a lead change. Each game's
     * strict ESPN match is used when one is known, else a made-up 1–0. Nothing in a release build.
     */
    suspend fun debugRaise(count: Int) {
        if (!BuildConfig.IS_DEBUG_BUILD) return
        val cards = candidates(repo.latest.value, repo.claimedCatalogs.first(), repo.followedTeams.first())
            .filter { it.isLive && it.teams != null && !SportsLiveAlertEngine.isPlaying(it, watching.value) }
            .take(count)
        val scores = liveScores.scores.value
        val alerts = cards.mapIndexed { index, card ->
            val teams = checkNotNull(card.teams)
            fun side(name: String, score: Int) = ScoreTeam(null, null, name, name, name, null, null, score, emptyList())
            val snapshot = ScoreChips.matchedEvent(card, scores[card.streamKey]) ?: ScoreSnapshot(
                eventId = "debug-${card.streamKey}", league = EspnLeague("debug", "debug"), startMs = card.startMs,
                state = ScoreState.IN, clockText = null, isFinal = false, home = side(teams.home, 1), away = side(teams.away, 0)
            )
            SportsLiveAlert.ScoreChange(card, snapshot, ScoreSide.HOME, leadChange = index == 1)
        }
        android.util.Log.d("SportsLiveAlerts", "debugRaise: ${alerts.size} of ${cards.size} cards")
        if (alerts.isNotEmpty()) enqueue(alerts, System.currentTimeMillis())
    }

    /**
     * Debug builds only (the #173 emulator check): a kickoff prompt ("Watch now?") for a live game now listed
     * and not playing, as if its reminder fired. Nothing in a release build.
     */
    suspend fun debugKickoff() {
        if (!BuildConfig.IS_DEBUG_BUILD) return
        val card = candidates(repo.latest.value, repo.claimedCatalogs.first(), repo.followedTeams.first())
            .firstOrNull { it.isLive && it.teams != null && !SportsLiveAlertEngine.isPlaying(it, watching.value) }
        android.util.Log.d("SportsLiveAlerts", "debugKickoff: ${card != null}")
        if (card != null) enqueue(listOf(SportsLiveAlert.Kickoff(card, null)), System.currentTimeMillis())
    }

    /**
     * Debug builds only (the #162 emulator check): a kickoff prompt for a live game now listed and not playing,
     * held as at a real kickoff, its probe finding nothing live for [notLiveMs]; then the real probe decides.
     * [giveUp]: the hold gives up at once (the "no stream yet" note).
     */
    suspend fun debugKickoffHold(notLiveMs: Long, giveUp: Boolean) {
        if (!BuildConfig.IS_DEBUG_BUILD) return
        val card = candidates(repo.latest.value, repo.claimedCatalogs.first(), repo.followedTeams.first())
            .firstOrNull { it.isLive && it.teams != null && !SportsLiveAlertEngine.isPlaying(it, watching.value) } ?: return
        debugNotLiveUntilMs = System.currentTimeMillis() + notLiveMs
        val alert = SportsLiveAlert.Kickoff(card, null)
        android.util.Log.d("SportsLiveAlerts", "debugKickoffHold: held, not live for ${notLiveMs / 1000} s")
        if (giveUp) enqueue(listOf(alert.copy(noStreamYet = true)), System.currentTimeMillis())
        else holdsLock.withLock { holds.hold(alert) }
    }

    /**
     * Debug builds only (the on-screen check): a score toast for the game playing now, through a listed copy of it
     * (another addon's, when one is listed, matched by game identity or ESPN event, never by title). It must be
     * dropped: the log says whether it was.
     */
    suspend fun debugRaiseForPlaying() {
        if (!BuildConfig.IS_DEBUG_BUILD) return
        val now = watching.value
        val cards = candidates(repo.latest.value, repo.claimedCatalogs.first(), emptyList())
        val onScreen = cards.filter { it.teams != null && SportsLiveAlertEngine.isPlaying(it, now) }
        val card = onScreen.firstOrNull { it.id !in now.playingIds } ?: onScreen.firstOrNull()
        if (card == null) {
            android.util.Log.d("SportsLiveAlerts", "debugRaiseForPlaying: no listed copy of the game on screen")
            return
        }
        val teams = checkNotNull(card.teams)
        fun side(name: String, score: Int) = ScoreTeam(null, null, name, name, name, null, null, score, emptyList())
        val snapshot = ScoreSnapshot(
            eventId = "debug-${card.streamKey}", league = EspnLeague("debug", "debug"), startMs = card.startMs,
            state = ScoreState.IN, clockText = null, isFinal = false, home = side(teams.home, 1), away = side(teams.away, 0)
        )
        val sameCard = card.id in now.playingIds
        enqueue(listOf(SportsLiveAlert.ScoreChange(card, snapshot, ScoreSide.HOME)), System.currentTimeMillis())
        val shown = currentFlow.value?.alert?.let { SportsLiveAlertEngine.gameKey(it.item) == SportsLiveAlertEngine.gameKey(card) } == true
        android.util.Log.d("SportsLiveAlerts", "debugRaiseForPlaying: otherCopy=${!sameCard} shown=$shown")
    }

    private fun ticks(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(TICK_MS)
        }
    }

    companion object {
        private const val TICK_MS = 5_000L

        /**
         * A followed team's live game ends its spoiler protection ("until I've watched") only once it has played this
         * long ([ResultSeen.liveWatchedMs]): zapping past it for a few seconds does not (live.23 review S5).
         */
        const val PROTECTION_WATCHED_MS = 2 * 60_000L

        /** Of [playing] (followed teams' live games playing now), those played for [PROTECTION_WATCHED_MS] so far. */
        internal fun watchedLongEnough(playing: List<ScoreSnapshot>, watchedMs: (String) -> Long): List<ScoreSnapshot> =
            playing.filter { watchedMs(it.eventId) >= PROTECTION_WATCHED_MS }

        /**
         * Over a movie or show only a kickoff shows (live.24: nothing else interrupts a film); a close finish never on a
         * Sports & Live screen, whose rows already say "Close late".
         */
        internal fun mayShowOver(alert: SportsLiveAlert, watching: SportsLiveWatching): Boolean = when {
            alert is SportsLiveAlert.Kickoff -> true
            watching.inPlayer && !watching.sportsPlayback -> false
            alert is SportsLiveAlert.Crunch -> !watching.sportsLiveScreen
            else -> true
        }

        /**
         * Close finish may be looked for now (live.24): never over a movie or show, never on a Sports & Live screen, and
         * never while a followed team's game plays ([watchingFollowed]: the viewer is where they want to be).
         */
        internal fun crunchMayShow(watching: SportsLiveWatching, watchingFollowed: Boolean): Boolean =
            !(watching.inPlayer && !watching.sportsPlayback) && !watching.sportsLiveScreen && !watchingFollowed

        /** Close finish for big games only (live.24): those that clear the big-game bar ([BigGame.BIG]). */
        internal fun bigGamesOnly(candidates: List<CrunchCandidate>): List<CrunchCandidate> =
            candidates.filter { it.bigScore >= BigGame.BIG }

        /** [alert]'s card shows a live score: a score card, or a close finish under Show. */
        internal fun showsLiveScore(alert: SportsLiveAlert): Boolean =
            alert is SportsLiveAlert.ScoreChange || (alert is SportsLiveAlert.Crunch && !alert.namesOnly)

        /** A first kickoff prompt waits for a live link (#162); a re-ask after Later or the note does not. */
        fun holdsFirst(alert: SportsLiveAlert): Boolean = alert is SportsLiveAlert.Kickoff && !alert.again && !alert.noStreamYet

        private const val MAX_ENDED = 500

        /**
         * [alert] under spoiler [protection] as it is now (live.24 RC2 review R2): a score or close finish of a game now
         * protected (saved for Watch later, my teams' scores hidden) is dropped; a full time of one becomes the "result
         * hidden" card, as when it is raised protected. A kickoff carries no score.
         *
         * Live.28 option 1 (user decision): a full time whose game had a live score shown on a card ([liveScoreShown], by
         * ESPN event id) shows its score after all, when only its result is protected ("Hide my teams' results"): its
         * live score is not, so the viewer followed the score all game. A game saved for later or with its live scores
         * hidden stays "result hidden".
         */
        fun underProtection(
            alert: SportsLiveAlert,
            protection: SpoilerProtection,
            liveScoreShown: (String) -> Boolean = { false }
        ): SportsLiveAlert? = when (alert) {
            is SportsLiveAlert.Kickoff -> alert
            is SportsLiveAlert.ScoreChange -> alert.takeUnless { protection.protects(it.snapshot) }
            is SportsLiveAlert.Crunch -> alert.takeUnless { protection.protects(it.snapshot) }
            is SportsLiveAlert.FullTime -> if (!alert.resultHidden && !protection.protects(alert.snapshot)) {
                alert
            } else if (liveScoreShown(alert.snapshot.eventId) && !protectsLiveScore(protection, alert.snapshot)) {
                alert.copy(namesOnly = false, resultHidden = false, team = null)
            } else if (alert.resultHidden) {
                alert
            } else {
                val event = alert.snapshot
                val side = followedSides(protection.followed, event).firstOrNull()?.let { if (it == ScoreSide.HOME) event.home else event.away }
                val team = side?.let { listOf(it.shortDisplayName, it.displayName, it.name).firstOrNull { name -> !name.isNullOrBlank() } }
                alert.copy(namesOnly = true, resultHidden = true, team = team)
            }
        }

        /** Whether [protection] hides [event]'s game while it is live too (saved for later, my teams' live scores). */
        private fun protectsLiveScore(protection: SpoilerProtection, event: ScoreSnapshot): Boolean =
            protection.protects(event.copy(state = ScoreState.IN, isFinal = false))

        /** The sides of [event] a followed team is, by ESPN data (strict). */
        fun followedSides(followed: List<FollowedTeam>, event: ScoreSnapshot): Set<ScoreSide> = buildSet {
            if (event.individual != null) return@buildSet
            if (TeamFollows.followersOf(followed, event, event.home).isNotEmpty()) add(ScoreSide.HOME)
            if (TeamFollows.followersOf(followed, event, event.away).isNotEmpty()) add(ScoreSide.AWAY)
        }

        /**
         * The games [load] lists in Live now and Upcoming (as at load time, so a game that kicked off
         * since is still here), merged and marked followed as the section shows them.
         */
        fun candidates(load: SportsLiveLoad?, claimed: List<ClaimedCatalog>, followed: List<FollowedTeam>): List<SportsLiveItem> {
            if (load == null) return emptyList()
            val keys = claimed.mapTo(HashSet()) { it.key }
            val items = load.items.filter { it.catalogKey in keys }
            if (items.isEmpty()) return emptyList()
            val order = claimed.map { it.addon.id }.distinct()
            val games = SportsLiveItemBucketer.bucketTab(items, SportsLiveTab.LIVE_NOW, load.loadedAtMs, order) +
                SportsLiveItemBucketer.bucketTab(items, SportsLiveTab.UPCOMING, load.loadedAtMs, order)
            return SportsLiveItemBucketer.markFollowed(games.filterNot { it.isChannel }, followed).distinctBy { it.streamKey }
        }
    }
}

/**
 * The toast card after a bell press (#44, `Prompts.dc.html`): "Reminder set" ([on]) or "Reminder removed",
 * the game (a card's [title], else ESPN's [away] at [home]) and its kickoff ([item]'s label when a card was
 * pressed, else [startMs]). [id] tells two presses on the same game apart.
 */
data class ReminderNotice(
    val id: Long,
    val on: Boolean,
    val startMs: Long?,
    val title: String? = null,
    val away: String? = null,
    val home: String? = null,
    val item: SportsLiveItem? = null
)
