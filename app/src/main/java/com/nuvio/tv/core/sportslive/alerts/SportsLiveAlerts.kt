package com.nuvio.tv.core.sportslive.alerts

import androidx.annotation.VisibleForTesting
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.sportslive.SportsLiveDisplayText
import com.nuvio.tv.core.sportslive.SportsLiveItemBucketer
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.LiveScoresRepository
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreSide
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreTeam
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.TeamFollows
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.domain.model.ClaimedCatalog
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.repository.SportsLiveLoad
import com.nuvio.tv.domain.repository.SportsLiveRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
    private val liveScores: LiveScoresRepository
) {
    private val engine = SportsLiveAlertEngine()
    private val lock = Any()

    /** Waiting overlays with when they were raised; guarded by [lock]. */
    private val queue = ArrayList<Pair<SportsLiveAlert, Long>>()
    private val currentFlow = MutableStateFlow<AlertCard?>(null)
    private val watching = MutableStateFlow(SportsLiveWatching())
    private var cardCount = 0L

    /**
     * The overlay to show now; null: none. Toasts raised close together share one card that cycles
     * through them (#136, [SportsLiveAlertCards]); a kickoff prompt is a card of its own.
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

    fun setWatching(now: SportsLiveWatching) {
        watching.value = now
    }

    /** The Scores setting and alert switches as last read: a queued toast is checked against them before it shows. */
    @Volatile
    private var lastMode: ScoresMode = ScoresMode.SHOW

    @Volatile
    private var lastSettings = SportsLiveAlertSettings()

    /** Games ESPN said are over (by [SportsLiveAlertEngine.gameKey]): no longer tracked. Guarded by [lock]. */
    private val endedGames = HashSet<String>()

    /** A load asked for a due reminder this session (once). */
    @Volatile
    private var reminderLoadAsked = false

    /** Runs until cancelled (the app left the foreground): tracks, decides and queues alerts. */
    suspend fun runInForeground() = coroutineScope {
        val cards = combine(repo.latest, repo.claimedCatalogs, repo.followedTeams) { load, claimed, followed ->
            candidates(load, claimed, followed)
        }.flowOn(Dispatchers.Default)
        val base = combine(cards, prefs.reminders, prefs.alertSettings, repo.followedTeams) { shown, reminded, settings, followed ->
            Base(shown, reminded, settings, followed)
        }.stateIn(this, SharingStarted.Eagerly, null)
        // The 5 s clock runs only while there is something to time: a reminder, or a reminded or followed game.
        val clock = base.map { it?.hasWork == true }.distinctUntilChanged().flatMapLatest { busy -> if (busy) ticks() else flowOf(Unit) }
        val tracked = MutableStateFlow<List<SportsLiveItem>>(emptyList())
        try {
            // The tracked cards are polled only while someone collects the scores: this, while any are tracked.
            launch {
                tracked.map { it.isNotEmpty() }.distinctUntilChanged().collectLatest { any ->
                    if (any) liveScores.scores.collect { }
                }
            }
            combine(base.filterNotNull(), liveScores.mode, watching, clock) { b, mode, now, _ ->
                Inputs(b.cards, b.reminders, b.settings, b.followed, mode, now)
            }.collect { inputs ->
                val nowMs = System.currentTimeMillis()
                lastMode = inputs.mode
                lastSettings = inputs.settings
                val scores = liveScores.scores.value
                val ended = synchronized(lock) {
                    inputs.cards.forEach { card ->
                        val state = scores[card.streamKey]?.state
                        if (state == ScoreState.POST) endedGames += SportsLiveAlertEngine.gameKey(card)
                    }
                    if (endedGames.size > MAX_ENDED) endedGames.clear()
                    endedGames.toSet()
                }
                val toTrack = SportsLiveAlertScope.cardsToTrack(inputs.cards, inputs.followed, nowMs, ended)
                if (toTrack != tracked.value) {
                    tracked.value = toTrack
                    liveScores.track(LiveScoresRepository.SCREEN_ALERTS, toTrack)
                }
                // A reminder due with no load this session: Live now loads as on a normal open (once).
                if (!reminderLoadAsked && SportsLiveAlertScope.needsLoad(inputs.reminders.reminders, repo.latest.value != null, nowMs)) {
                    reminderLoadAsked = true
                    launch { loadQuietly() }
                }
                evaluate(inputs, scores, nowMs)
            }
        } finally {
            liveScores.untrack(LiveScoresRepository.SCREEN_ALERTS)
        }
    }

    private class Base(
        val cards: List<SportsLiveItem>,
        val reminders: RemindersState,
        val settings: SportsLiveAlertSettings,
        val followed: List<FollowedTeam>
    ) {
        val hasWork: Boolean get() = reminders.reminders.isNotEmpty() || cards.any { it.followed || reminders.isReminded(it) }
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
        val watching: SportsLiveWatching
    )

    private fun evaluate(inputs: Inputs, scores: Map<String, ScoreSnapshot>, nowMs: Long) {
        val reminders = inputs.reminders.copy(remindMyTeams = inputs.settings.remindMyTeams)
        val games = inputs.cards.mapNotNull { item ->
            val reminded = reminders.isReminded(item)
            if (!reminded && !item.followed) return@mapNotNull null
            val event = if (inputs.mode == ScoresMode.OFF) null else ScoreChips.matchedEvent(item, scores[item.streamKey])
            AlertGame(item, event, reminded, event?.let { followedSides(inputs.followed, it) }.orEmpty())
        }
        val raised = synchronized(lock) { engine.update(nowMs, games, inputs.settings, inputs.mode, inputs.watching) }
        if (raised.isNotEmpty()) enqueue(raised, nowMs)
    }

    @VisibleForTesting
    internal fun enqueue(alerts: List<SportsLiveAlert>, nowMs: Long) = synchronized(lock) {
        for (alert in alerts) {
            // A toast arriving while a toast card shows joins it (#136), when it may show at all.
            val card = currentFlow.value
            if (card != null && SportsLiveAlertCards.canJoin(card, alert, nowMs)) {
                showable(alert, nowMs, raisedAt = nowMs)?.let { currentFlow.value = SportsLiveAlertCards.join(card, it, nowMs) }
                continue
            }
            if (alert is SportsLiveAlert.Kickoff) {
                // Kickoff prompts go before toasts, in the order raised.
                val at = queue.indexOfFirst { it.first !is SportsLiveAlert.Kickoff }.let { if (it < 0) queue.size else it }
                queue.add(at, alert to nowMs)
            } else {
                queue.add(alert to nowMs)
            }
        }
        if (currentFlow.value == null) showNextLocked(nowMs)
    }

    /** The overlay showing [card] closed with [answer]; the next one shows. */
    fun close(card: AlertCard, answer: AlertAnswer) = synchronized(lock) {
        val nowMs = System.currentTimeMillis()
        val alert = card.first
        if (alert is SportsLiveAlert.Kickoff) {
            when (answer) {
                AlertAnswer.WATCH, AlertAnswer.DISMISS -> engine.answered(alert.item)
                AlertAnswer.LATER, AlertAnswer.TIMEOUT -> engine.later(alert.item, nowMs)
            }
        }
        // By id: alerts may have joined the card since the host read it.
        if (currentFlow.value?.id == card.id) currentFlow.value = null
        showNextLocked(nowMs)
    }

    /**
     * [queued] as it may show now, or null: the Scores setting or a switch may have changed while it
     * waited; a toast that waited too long is old news; nothing about the game now playing; a full time
     * already announced (the toast for the game playing, #60) is dropped.
     */
    private fun showable(queued: SportsLiveAlert, nowMs: Long, raisedAt: Long): SportsLiveAlert? {
        val next = SportsLiveAlertEngine.stillShowable(queued, lastMode, lastSettings) ?: return null
        if (next !is SportsLiveAlert.Kickoff && nowMs - raisedAt > TOAST_STALE_MS) return null
        if (SportsLiveAlertEngine.isPlaying(next.item, watching.value)) {
            if (next is SportsLiveAlert.Kickoff) engine.answered(next.item)
            return null
        }
        if (!SportsLiveAlertEngine.claimToShow(next)) return null
        return next
    }

    private fun showNextLocked(nowMs: Long) {
        while (queue.isNotEmpty()) {
            val (queued, raisedAt) = queue.removeAt(0)
            val next = showable(queued, nowMs, raisedAt) ?: continue
            var card = AlertCard(++cardCount, listOf(next), nowMs)
            // Toasts that waited together show together (#136): the ones raised within the window of the last taken.
            var lastRaisedAt = raisedAt
            while (SportsLiveAlertCards.groupable(next) && queue.isNotEmpty()) {
                val (waiting, waitingRaisedAt) = queue.first()
                if (!SportsLiveAlertCards.groupable(waiting) || waitingRaisedAt - lastRaisedAt > SportsLiveAlertCards.GROUP_WINDOW_MS) break
                queue.removeAt(0)
                lastRaisedAt = waitingRaisedAt
                showable(waiting, nowMs, waitingRaisedAt)?.let { card = SportsLiveAlertCards.join(card, it, nowMs) }
            }
            currentFlow.value = card
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
        val fresh = candidates(load, repo.claimedCatalogs.first(), repo.followedTeams.first()).firstOrNull { card ->
            card.streamKey == item.streamKey || (reminder != null && GameReminders.matches(reminder, card))
        }
        val card = fresh ?: item
        return if (card.isLive) card else card.copy(isLive = true)
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

    private fun ticks(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(TICK_MS)
        }
    }

    companion object {
        private const val TICK_MS = 5_000L

        /** A score or full-time toast not shown within this long is dropped. */
        private const val TOAST_STALE_MS = 30_000L
        private const val MAX_ENDED = 500

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
