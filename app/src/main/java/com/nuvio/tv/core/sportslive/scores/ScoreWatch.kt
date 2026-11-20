package com.nuvio.tv.core.sportslive.scores

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What is playing right now, for the scores (live.20 Q18, #85). A player (full screen, each dual pane, the mini
 * player) registers the cards it plays under its own [owner] key, the stream's source (the addon's id, never a
 * URL) and, when the player knows it, how far behind its live edge it is. The scores read it to hide the score
 * and clock of the games being watched and to delay every other game's score while video plays ([ScoreWatch]).
 *
 * The seam for player code: [watch] when a stream starts, [behindLive] from the player's live window
 * (`ExoPlayer.liveWindow().behindEdgeMs`), [stop] when it ends or the player leaves.
 */
class WatchingNow {
    data class Session(val cardKeys: Set<String>, val sourceId: String?, val behindLiveMs: Long? = null)

    private val sessions = MutableStateFlow<Map<String, Session>>(emptyMap())

    /** The players playing now, by owner. */
    val state: StateFlow<Map<String, Session>> = sessions.asStateFlow()

    /** [owner] plays [cardKeys] (the cards' stream keys) from [sourceId]. Empty [cardKeys] stops it. */
    fun watch(owner: String, cardKeys: Set<String>, sourceId: String?) {
        if (cardKeys.isEmpty()) return stop(owner)
        sessions.update { all ->
            val behind = all[owner]?.takeIf { it.cardKeys == cardKeys }?.behindLiveMs
            all + (owner to Session(cardKeys, sourceId, behind))
        }
    }

    /** How far behind its live edge [owner]'s player is (null: not known); ignored while it plays nothing. */
    fun behindLive(owner: String, ms: Long?) {
        sessions.update { all ->
            val current = all[owner] ?: return@update all
            // Seconds are enough: half-second jitter would republish the scores twice a second.
            val rounded = ms?.coerceAtLeast(0L)?.let { it / 1_000L * 1_000L }
            if (current.behindLiveMs == rounded) all else all + (owner to current.copy(behindLiveMs = rounded))
        }
    }

    fun stop(owner: String) {
        sessions.update { if (owner in it) it - owner else it }
    }

    companion object {
        val shared = WatchingNow()
    }
}

/** The rules the scores follow while video plays (live.20 Q18, #85; research `tasks/research-live20-q17-19.md`). */
object ScoreWatch {
    /** Other games' scores wait at least this long while video plays (streams run 48 to 62 s behind, and more). */
    const val MIN_PLAYING_DELAY_MS = 60_000L

    /** Added to the player's measured distance behind live: it cannot see the source's own restream lag. */
    const val MARGIN_MS = 15_000L

    /** One "Scores ahead? +15 s" press, and the most a source can be nudged. */
    const val NUDGE_STEP_SECONDS = 15
    const val NUDGE_MAX_SECONDS = 180

    /** The player shows "Go live" from this far behind its live edge. */
    const val GO_LIVE_FROM_MS = 10_000L

    /**
     * The delay every game not being watched gets: none under Real-time scores; the profile's Score delay
     * ([settingMs]) while nothing plays; while video plays, the longest player's distance behind live plus
     * [MARGIN_MS], at least [MIN_PLAYING_DELAY_MS], plus the largest nudge of the sources playing ([nudges], in
     * seconds by source id).
     */
    fun delayMs(settingMs: Long, realTime: Boolean, playing: Collection<WatchingNow.Session>, nudges: Map<String, Int>): Long {
        if (realTime) return 0L
        if (playing.isEmpty()) return settingMs
        val measured = (playing.mapNotNull { it.behindLiveMs }.maxOrNull() ?: 0L) + MARGIN_MS
        val nudge = playing.mapNotNull { session -> session.sourceId?.let(nudges::get) }.maxOrNull() ?: 0
        return maxOf(MIN_PLAYING_DELAY_MS, measured) + nudgeCapped(nudge) * 1_000L
    }

    /** [seconds] within 0..[NUDGE_MAX_SECONDS]. */
    fun nudgeCapped(seconds: Int): Int = seconds.coerceIn(0, NUDGE_MAX_SECONDS)

    /** One more press on [current] seconds. */
    fun nudged(current: Int): Int = nudgeCapped(current + NUDGE_STEP_SECONDS)

    /** The ESPN games being watched: those of the cards the players play, as [scores] (by card key) matched them. */
    fun watchedEvents(playing: Collection<WatchingNow.Session>, scores: Map<String, ScoreSnapshot>): Set<String> =
        playing.flatMap { it.cardKeys }.mapNotNullTo(HashSet()) { key -> scores[key]?.eventId }

    /**
     * [event] as a game being watched shows it: no score, no clock, no scorers or situation (the broadcast's own
     * scorebug is in step with the picture; ours runs ahead). Its state stays, so an end card still comes, delayed.
     */
    fun masked(event: ScoreSnapshot): ScoreSnapshot = event.copy(
        clockText = null,
        home = event.home.copy(score = null, linescores = emptyList()),
        away = event.away.copy(score = null, linescores = emptyList()),
        scorers = emptyList(),
        situation = null,
        watched = true
    )

    /** What the player's live label says (`LIVE`, `LIVE · −0:45`, and from 10 s behind a Go live action). */
    data class LiveLabel(val behindMs: Long?, val offersGoLive: Boolean)

    fun liveLabel(behindLiveMs: Long?): LiveLabel {
        val behind = behindLiveMs?.takeIf { it >= 1_000L }
        return LiveLabel(behind, offersGoLive = behind != null && behind > GO_LIVE_FROM_MS)
    }
}
