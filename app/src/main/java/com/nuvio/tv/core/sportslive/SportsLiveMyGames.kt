package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.alerts.GameReminders
import com.nuvio.tv.core.sportslive.alerts.RemindersState
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import java.time.Instant
import java.time.ZoneId

/**
 * "My games" (live.20 1b): the only games whose streams are checked ahead ("▶ Ready"). A followed team's game, a
 * game of a team on "Remind me for every ‹team› game" (#154), or a game with Remind me (#44). Never a game saved
 * for later (#151, its result must not be met by accident), a channel or a replay; never any other game.
 */
object SportsLiveMyGames {

    /** Whether [item] is one of my games. [event]: its strict ESPN game, when known (reminder-only teams by ESPN id). */
    fun isMine(
        item: SportsLiveItem,
        reminders: RemindersState,
        savedForLater: (SportsLiveItem) -> Boolean,
        event: ScoreSnapshot? = null
    ): Boolean {
        if (item.isChannel || item.kind == SportsLiveKind.REPLAY) return false
        val mine = item.followed ||
            reminders.reminders.any { GameReminders.matches(it, item) } ||
            GameReminders.remindsTeamOf(reminders.remindedTeams, item, event)
        return mine && !savedForLater(item)
    }

    /** [cards] that are my games, once each ([SportsLiveItem.streamKey]). */
    fun of(
        cards: List<SportsLiveItem>,
        reminders: RemindersState,
        savedForLater: (SportsLiveItem) -> Boolean,
        eventOf: (SportsLiveItem) -> ScoreSnapshot? = { null }
    ): List<SportsLiveItem> =
        cards.filter { isMine(it, reminders, savedForLater, eventOf(it)) }.distinctBy { it.streamKey }

    /**
     * Home's quiet line (1b extra a): of my games [mine], those still to come or on today in [zone] (live now, or a
     * start on today's date) and not over ([isOver]), and how many of them are "▶ Ready" ([isReady]). Null on a day
     * with none: the line shows only on days with my games.
     */
    fun today(
        mine: List<SportsLiveItem>,
        nowMs: Long,
        isOver: (SportsLiveItem) -> Boolean,
        isReady: (SportsLiveItem) -> Boolean,
        zone: ZoneId = ZoneId.systemDefault()
    ): SportsLiveMyGamesToday? {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val games = mine.filter { item ->
            if (isOver(item)) return@filter false
            item.isLive || item.startMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == today } == true
        }
        if (games.isEmpty()) return null
        return SportsLiveMyGamesToday(games = games.size, ready = games.count(isReady))
    }
}

/** Home's "2 games tonight · 1 ready" (1b): my games today not over yet, and how many hold a verified link. */
data class SportsLiveMyGamesToday(val games: Int, val ready: Int)

/**
 * When my games are checked (1b): from [SportsLiveStreamThresholds.READY_LEAD_MS] before kickoff while not over. A
 * game holding a verified link is verified again before the hold lapses ([SportsLiveStreamThresholds.READY_REFRESH_MS],
 * a single probe of that link); any other is checked again every [SportsLiveStreamThresholds.READY_RECHECK_MS].
 */
object SportsLiveReadySchedule {

    enum class Action {
        /** Nothing due. */
        NONE,

        /** Verify the held link again (one probe, no search). */
        REFRESH,

        /** A full check: the game's search (or what is cached) and one probe per addon. */
        CHECK
    }

    /**
     * Whether [item] is checked at [nowMs]: live, or from the lead before its timed start, until [over] (ESPN's
     * final, or [SportsLiveStreamThresholds.READY_MAX_GAME_MS] after the start with nothing saying so).
     */
    fun inWindow(item: SportsLiveItem, nowMs: Long, over: Boolean): Boolean {
        if (over) return false
        val start = item.startMs?.takeIf { item.startHasTimeOfDay }
        if (start != null && nowMs - start > SportsLiveStreamThresholds.READY_MAX_GAME_MS) return false
        if (item.isLive) return true
        return start != null && nowMs >= start - SportsLiveStreamThresholds.READY_LEAD_MS
    }

    /** What is due for [item] at [nowMs]: [heldAtMs] when its held link was last verified, [lastCheckAtMs] its last check. */
    fun next(item: SportsLiveItem, nowMs: Long, over: Boolean, heldAtMs: Long?, lastCheckAtMs: Long?): Action {
        if (!inWindow(item, nowMs, over)) return Action.NONE
        if (heldAtMs != null) {
            return if (nowMs - heldAtMs >= SportsLiveStreamThresholds.READY_REFRESH_MS) Action.REFRESH else Action.NONE
        }
        return if (lastCheckAtMs == null || nowMs - lastCheckAtMs >= SportsLiveStreamThresholds.READY_RECHECK_MS) Action.CHECK else Action.NONE
    }
}
