package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveGameReadiness
import com.nuvio.tv.core.sportslive.SportsLiveMyGames
import com.nuvio.tv.core.sportslive.SportsLiveMyGamesToday
import com.nuvio.tv.core.sportslive.SportsLiveReadyMark
import com.nuvio.tv.core.sportslive.SportsLiveReadySchedule
import com.nuvio.tv.core.sportslive.SportsLiveStreamThresholds
import com.nuvio.tv.core.sportslive.alerts.RemindersState
import com.nuvio.tv.core.sportslive.alerts.SportsLiveCandidatesMemo
import com.nuvio.tv.core.sportslive.scores.LiveScoresRepository
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.repository.SportsLiveRepository
import com.nuvio.tv.ui.screens.sportslive.dual.sportsLiveSavedForLater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext

/**
 * Keeps my games "▶ Ready" (live.20 1b): the proactive readiness checks, for my games only ([SportsLiveMyGames]:
 * followed teams', every-game reminder teams' (#154) and Remind me (#44) games, never one saved for later, #151).
 * No other game is ever checked ahead; a first frame still marks any game it plays (no probe).
 *
 * Where it runs: [runInForeground], from the app-wide alerts host ([SportsLiveAlertsHost]) while the app is started,
 * so on every screen while the app is in the foreground, and never in the background or once the app is closed.
 *
 * When ([SportsLiveReadySchedule]): from 10 minutes before kickoff until the game is over; a held link is verified
 * again before it lapses (one probe of that link, [SportsLiveZapResolver.refreshReady]); a game with none is checked
 * every 5 minutes ([SportsLiveZapResolver.checkAhead]: the game's search, or what a lookup already holds, then one
 * probe per addon, the DRM rule included). One game at a time, [SportsLiveStreamThresholds.READY_GAP_MS] apart; a
 * game whose every source is on a rate-limited or slow addon is left alone.
 */
@Singleton
class SportsLiveReadyKeeper internal constructor(
    /** The listed games my games are picked from (Live now and Upcoming of the last load). */
    private val cards: suspend () -> List<SportsLiveItem>,
    private val reminders: suspend () -> RemindersState,
    private val scores: () -> Map<String, ScoreSnapshot>,
    private val savedForLater: () -> (SportsLiveItem) -> Boolean,
    private val check: suspend (SportsLiveItem) -> Unit,
    private val refresh: suspend (SportsLiveItem) -> Unit,
    private val skips: (SportsLiveItem) -> Boolean,
    /** Loads the games once when nothing was loaded this session and there are my games to look for. */
    private val loadIfNone: suspend () -> Unit = {},
    private val readiness: SportsLiveGameReadiness = SportsLiveGameReadiness.shared,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    /**
     * Where the keeper's passes run (#207): never the main thread the host's effect starts it on. Each pass reads every
     * listed game; on Main it froze the UI for over a second every [TICK_MS] on a slow box.
     */
    private val compute: CoroutineContext = Dispatchers.Default
) {
    @Inject constructor(
        repo: SportsLiveRepository,
        prefs: SportsLivePreferencesDataStore,
        liveScores: LiveScoresRepository,
        resolver: SportsLiveZapResolver
    ) : this(
        cards = listedGames(repo, SportsLiveCandidatesMemo()),
        reminders = { prefs.reminders.first() },
        scores = { liveScores.scores.value },
        savedForLater = { sportsLiveSavedForLater(liveScores.protection.value, liveScores.scores.value) },
        check = resolver::checkAhead,
        refresh = resolver::refreshReady,
        skips = resolver::checkAheadSkips,
        loadIfNone = {
            if (repo.latest.value == null) {
                val reminded = prefs.reminders.first()
                if (repo.followedTeams.first().isNotEmpty() || reminded.reminders.isNotEmpty() || reminded.remindedTeams.isNotEmpty()) {
                    repo.load(force = false, includeChannels = false)
                }
            }
        }
    )

    /** My games as of the last tick, and which of them are over. */
    private class MyGamesNow(val mine: List<SportsLiveItem>, val overKeys: Set<String>)

    private val now = MutableStateFlow(MyGamesNow(emptyList(), emptySet()))

    /** Game id -> when its last check (or refresh) started. Only the keeper's loop touches it. */
    private val lastCheckAt = HashMap<String, Long>()

    @Volatile
    private var loadAsked = false

    /**
     * Home's quiet line (1b extra a, "2 games tonight · 1 ready"): my games today not over, and how many are
     * "▶ Ready"; null on a day with none. Fresh while the keeper runs (the app in the foreground).
     */
    val today: Flow<SportsLiveMyGamesToday?> = combine(now, readiness.marks) { games, _ ->
        SportsLiveMyGames.today(
            mine = games.mine,
            nowMs = nowMs(),
            isOver = { it.streamKey in games.overKeys },
            isReady = { readiness.of(it) == SportsLiveReadyMark.READY }
        )
    }.distinctUntilChanged()

    /** Runs until cancelled (the app left the foreground), on [compute]. */
    suspend fun runInForeground() = withContext(compute) {
        if (!loadAsked) {
            loadAsked = true
            quietly { loadIfNone() }
        }
        while (true) {
            tick()
            delay(TICK_MS)
        }
    }

    /** One pass: my games now, then each one due checked in turn (kickoff soonest first). */
    internal suspend fun tick() {
        readiness.prune()
        val scores = scores()
        val eventOf: (SportsLiveItem) -> ScoreSnapshot? = { ScoreChips.matchedEvent(it, scores[it.streamKey]) }
        val mine = SportsLiveMyGames.of(cards(), reminders(), savedForLater(), eventOf)
        val overKeys = mine.filter { eventOf(it)?.state == ScoreState.POST }.mapTo(HashSet()) { it.streamKey }
        now.value = MyGamesNow(mine, overKeys)
        // BUG-F: only my games' rows say "▶ Ready" (never one ESPN calls over: BUG-A2), merged sources included.
        readiness.setMine(mine.filter { it.streamKey !in overKeys }.flatMap { listOf(it) + it.alternates }.mapTo(HashSet()) { it.id })
        val at = nowMs()
        val due = mine.mapNotNull { item ->
            val action = SportsLiveReadySchedule.next(
                item = item,
                nowMs = at,
                over = item.streamKey in overKeys,
                heldAtMs = readiness.heldAt(item.id),
                lastCheckAtMs = lastCheckAt[item.id]
            )
            if (action == SportsLiveReadySchedule.Action.NONE) null else item to action
        }.sortedBy { it.first.startMs ?: Long.MAX_VALUE }
        for ((index, entry) in due.withIndex()) {
            val (item, action) = entry
            if (skips(item)) continue
            if (index > 0) delay(SportsLiveStreamThresholds.READY_GAP_MS)
            lastCheckAt[item.id] = nowMs()
            quietly {
                // Rows show the check running (live.20), so an unchecked game reads apart from one with no stream.
                readiness.whileChecking(item.id) {
                    when (action) {
                        SportsLiveReadySchedule.Action.REFRESH -> refresh(item)
                        else -> check(item)
                    }
                }
            }
        }
        if (lastCheckAt.size > MAX_GAMES) lastCheckAt.keys.retainAll(mine.mapTo(HashSet()) { it.id })
    }

    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // No verdict: the game is checked again when its turn comes.
        }
    }

    companion object {
        /** The listed games, sorted again only when the load, catalogs, follows or team directory changed (#207). */
        private fun listedGames(repo: SportsLiveRepository, memo: SportsLiveCandidatesMemo): suspend () -> List<SportsLiveItem> =
            { memo.of(repo.latest.value, repo.claimedCatalogs.first(), repo.followedTeams.first()) }

        /** The keeper looks at my games this often; the checks themselves are minutes apart. */
        const val TICK_MS = 30_000L
        private const val MAX_GAMES = 200
    }
}
