package com.nuvio.tv.core.sportslive.alerts

import com.nuvio.tv.core.sportslive.SportsLiveItemBucketer
import com.nuvio.tv.core.sportslive.scores.LiveScoresRepository
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreSide
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
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
import kotlinx.coroutines.flow.flowOn
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
    private val currentFlow = MutableStateFlow<SportsLiveAlert?>(null)
    private val watching = MutableStateFlow(SportsLiveWatching())

    /** The overlay to show now; null: none. */
    val current: StateFlow<SportsLiveAlert?> = currentFlow.asStateFlow()

    /** What plays now, as the host last said. */
    val watchingNow: StateFlow<SportsLiveWatching> = watching.asStateFlow()

    /** The active profile's reminders and opt-outs. */
    val reminders: Flow<RemindersState> = prefs.reminders

    /** Bell on [item] pressed: reminded now? */
    suspend fun toggleReminder(item: SportsLiveItem): Boolean =
        prefs.updateReminders { GameReminders.toggle(it, item, System.currentTimeMillis()) }.isReminded(item)

    fun setWatching(now: SportsLiveWatching) {
        watching.value = now
    }

    /** Runs until cancelled (the app left the foreground): tracks, decides and queues alerts. */
    suspend fun runInForeground() = coroutineScope {
        val cards = combine(repo.latest, repo.claimedCatalogs, repo.followedTeams) { load, claimed, followed ->
            candidates(load, claimed, followed)
        }.flowOn(Dispatchers.Default)
        val tracked = MutableStateFlow<List<SportsLiveItem>>(emptyList())
        try {
            // The tracked cards are polled only while someone collects the scores: this, while any are tracked.
            launch {
                tracked.map { it.isNotEmpty() }.distinctUntilChanged().collectLatest { any ->
                    if (any) liveScores.scores.collect { }
                }
            }
            combine(
                cards,
                combine(prefs.reminders, prefs.alertSettings, repo.followedTeams, ::Triple),
                liveScores.mode,
                watching,
                ticks()
            ) { shown, (reminded, settings, followed), mode, now, _ -> Inputs(shown, reminded, settings, followed, mode, now) }
                .collect { inputs ->
                    val nowMs = System.currentTimeMillis()
                    val toTrack = SportsLiveAlertScope.cardsToTrack(inputs.cards, inputs.followed, nowMs)
                    if (toTrack != tracked.value) {
                        tracked.value = toTrack
                        liveScores.track(LiveScoresRepository.SCREEN_ALERTS, toTrack)
                    }
                    evaluate(inputs, liveScores.scores.value, nowMs)
                }
        } finally {
            liveScores.untrack(LiveScoresRepository.SCREEN_ALERTS)
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

    private fun enqueue(alerts: List<SportsLiveAlert>, nowMs: Long) = synchronized(lock) {
        for (alert in alerts) {
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

    /** The overlay showing [alert] closed with [answer]; the next one shows. */
    fun close(alert: SportsLiveAlert, answer: AlertAnswer) = synchronized(lock) {
        val nowMs = System.currentTimeMillis()
        if (alert is SportsLiveAlert.Kickoff) {
            when (answer) {
                AlertAnswer.WATCH, AlertAnswer.DISMISS -> engine.answered(alert.item)
                AlertAnswer.LATER, AlertAnswer.TIMEOUT -> engine.later(alert.item, nowMs)
            }
        }
        if (currentFlow.value == alert) currentFlow.value = null
        showNextLocked(nowMs)
    }

    private fun showNextLocked(nowMs: Long) {
        val now = watching.value
        while (queue.isNotEmpty()) {
            val (next, raisedAt) = queue.removeAt(0)
            // A toast that waited too long is old news; nothing about the game now playing.
            if (next !is SportsLiveAlert.Kickoff && nowMs - raisedAt > TOAST_STALE_MS) continue
            if (SportsLiveAlertEngine.isPlaying(next.item, now)) {
                if (next is SportsLiveAlert.Kickoff) engine.answered(next.item)
                continue
            }
            currentFlow.value = next
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
