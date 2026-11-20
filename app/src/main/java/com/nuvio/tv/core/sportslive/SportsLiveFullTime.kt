package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.ChipScore
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.SportsLiveItem

/**
 * The ESPN events whose full time has been announced, by event id: the one key every full-time toast
 * claims before it shows, so one game's end is announced once, whichever toast gets there first (this
 * one for the game playing, #60; the followed-team alerts' for a game watched from elsewhere, #42).
 * In memory, for the process.
 */
object SportsLiveFullTimeAnnouncements {
    private const val MAX_REMEMBERED = 500
    private val announced = LinkedHashSet<String>()

    /** True when [eventId]'s full time was not announced yet: the caller announces it now. */
    @Synchronized
    fun claim(eventId: String): Boolean {
        if (eventId.isBlank() || eventId in announced) return false
        if (announced.size >= MAX_REMEMBERED) announced.remove(announced.first())
        announced += eventId
        return true
    }

    @Synchronized
    fun isAnnounced(eventId: String): Boolean = eventId in announced

    @Synchronized
    internal fun clear() = announced.clear()
}

/**
 * "Ended while you're watching" (#60): the game playing is over. [score] in the card's team order
 * under Scores Show; null under Hide until focused ("Full time" alone).
 */
data class FullTimeToast(val item: SportsLiveItem, val eventId: String, val score: ChipScore?)

/** The toast's words, from string resources (tests pass the English ones). */
data class FullTimeToastStrings(
    /** "Full time" */
    val title: String,
    /** "Full time · %1$s": the score. */
    val withScore: String,
    /** "%1$d–%2$d" */
    val score: String
)

object SportsLiveFullTimeToasts {

    /**
     * The toast for [item], the game playing, given ESPN's score-delayed [snapshot] and the Scores
     * [mode]: only on a strict match, ESPN final with its score out, once [seenLive] (ESPN showed it in
     * play while it was playing here, so a game opened already over never says it ended). None under
     * Off, or before the setting is read.
     */
    fun of(item: SportsLiveItem, snapshot: ScoreSnapshot?, mode: ScoresMode?, seenLive: Boolean): FullTimeToast? {
        if (mode == null || mode == ScoresMode.OFF || !seenLive) return null
        // A spoiler-protected game (#151) is never announced.
        if (snapshot?.spoilerProtected == true) return null
        val event = ScoreChips.matchedEvent(item, snapshot) ?: return null
        if (!SportsLiveEndedGames.isFinalWithScore(event)) return null
        val score = if (mode == ScoresMode.SHOW) (ScoreChips.of(item, event, mode, revealed = true) as? ScoreChip.Final)?.score else null
        return FullTimeToast(item, event.eventId, score)
    }

    /** True when ESPN's delayed [snapshot] shows [item]'s strict match in play. */
    fun isLive(item: SportsLiveItem, snapshot: ScoreSnapshot?): Boolean =
        ScoreChips.matchedEvent(item, snapshot)?.state == ScoreState.IN

    /** "Full time · 2–1" with a score, "Full time" without. */
    fun text(toast: FullTimeToast, strings: FullTimeToastStrings): String {
        val score = toast.score ?: return strings.title
        return String.format(strings.withScore, String.format(strings.score, score.first, score.second))
    }
}
