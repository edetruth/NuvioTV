package com.nuvio.tv.core.sportslive.scores

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Games whose result the user knows (live.20 1c rule 2), by ESPN event id, this process: a final score shown on
 * screen ([finalScoreShown]: a Results row, a Full time alert, the Game page) or a followed team's game watched live
 * for [ReplayLifetime.LIVE_WATCHED_MS] ([onLiveTick]). [CatchUpRepository] remembers them ([CatchUpMarks.known]), so
 * their replay leaves Your teams and the Catch up row.
 */
class ResultSeen {
    private val knownFlow = MutableStateFlow<Set<String>>(emptySet())

    /** The games whose result is known, oldest first. */
    val known: StateFlow<Set<String>> = knownFlow.asStateFlow()

    private val watchedMs = HashMap<String, Long>()
    private val lastTickMs = HashMap<String, Long>()

    /** [eventId]'s final score was drawn on screen (never while hidden: the caller only calls when it shows). */
    fun finalScoreShown(eventId: String) = add(eventId)

    /**
     * The alerts' tick (about every 5 s while a followed team's game is around): [playing] are the followed teams'
     * games playing live now. Time between two ticks that both saw a game counts, up to [MAX_GAP_MS].
     */
    @Synchronized
    fun onLiveTick(playing: Collection<String>, nowMs: Long) {
        val now = playing.toSet()
        lastTickMs.keys.retainAll(now)
        for (id in now) {
            val last = lastTickMs[id]
            if (last != null && nowMs > last && nowMs - last <= MAX_GAP_MS) watchedMs[id] = (watchedMs[id] ?: 0L) + (nowMs - last)
            lastTickMs[id] = nowMs
            if ((watchedMs[id] ?: 0L) >= ReplayLifetime.LIVE_WATCHED_MS) add(id)
        }
        if (watchedMs.size > MAX_GAMES) watchedMs.keys.retainAll(now)
    }

    /** How long [eventId] was watched live this process. */
    @Synchronized
    fun liveWatchedMs(eventId: String): Long = watchedMs[eventId] ?: 0L

    /** A profile switch: what was seen belongs to the profile before. */
    @Synchronized
    fun clear() {
        watchedMs.clear()
        lastTickMs.clear()
        knownFlow.value = emptySet()
    }

    private fun add(eventId: String) {
        knownFlow.update { known ->
            if (eventId in known) known else (known + eventId).let { if (it.size > MAX_GAMES) it.drop(it.size - MAX_GAMES).toSet() else it }
        }
    }

    companion object {
        /** Ticks further apart than this (the alerts paused, the app in the background) do not count. */
        const val MAX_GAP_MS = 30_000L

        private const val MAX_GAMES = 200

        /** The app's one log. */
        val shared = ResultSeen()
    }
}
