package com.nuvio.tv.core.sportslive.alerts

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import kotlin.math.abs

/**
 * A game the user asked to be reminded of (#44), or opted out of for an automatic reminder: the card's
 * source keys (`addonId|id` of it and its alternates), its title, its two sides and its start. Stored
 * per profile.
 */
data class GameReminder(
    val keys: Set<String>,
    val title: String,
    val home: String?,
    val away: String?,
    val startMs: Long
)

/** The active profile's reminders and opt-outs, and whether followed teams' games are reminded automatically. */
data class RemindersState(
    val reminders: List<GameReminder> = emptyList(),
    val optOuts: List<GameReminder> = emptyList(),
    /** Settings → Sports & Live → Alerts → "Remind me for my teams". */
    val remindMyTeams: Boolean = true
) {
    /** Whether [item] gets a "Watch now?" prompt at kickoff: reminded by hand, or a followed team's game not opted out. */
    fun isReminded(item: SportsLiveItem): Boolean =
        reminders.any { GameReminders.matches(it, item) } ||
            (remindMyTeams && item.followed && optOuts.none { GameReminders.matches(it, item) })
}

object GameReminders {
    /** A card copy listed again by an addon under another id still matches within this of the stored start. */
    private const val SAME_GAME_WINDOW_MS = 30 * 60_000L

    /** Kept this long after the start: dropped when read, and from storage on the next change. */
    const val KEEP_AFTER_START_MS = 6 * 60 * 60_000L

    /** Reminders are kept to this many (the soonest first); a long list is pruned on the next change. */
    private const val MAX_STORED = 200

    /**
     * Whether "Remind me" is offered on [item]: a game (not a channel or replay) not live yet, with a
     * timed start still ahead.
     */
    fun canRemind(item: SportsLiveItem, nowMs: Long): Boolean {
        val start = item.startMs ?: return false
        return !item.isChannel && item.kind != SportsLiveKind.REPLAY && !item.isLive && item.startHasTimeOfDay && start > nowMs
    }

    /** What is stored for [item]; null when it has no timed start. */
    fun of(item: SportsLiveItem): GameReminder? {
        val start = item.startMs?.takeIf { item.startHasTimeOfDay } ?: return null
        return GameReminder(
            keys = sourceKeys(item),
            title = item.meta.name,
            home = item.teams?.home,
            away = item.teams?.away,
            startMs = start
        )
    }

    /**
     * True when [reminder] is [item]'s game: it starts within 30 minutes of the stored start (an addon
     * reusing an id for a later rematch is another game), and has one of the stored sources' keys or
     * (an addon re-listed it under another id) the same two sides, either way round, as
     * one-card-per-game merges copies.
     */
    fun matches(reminder: GameReminder, item: SportsLiveItem): Boolean {
        val start = item.startMs ?: return false
        if (abs(start - reminder.startMs) > SAME_GAME_WINDOW_MS) return false
        if (reminder.keys.isNotEmpty() && sourceKeys(item).any { it in reminder.keys }) return true
        val home = reminder.home ?: return false
        val away = reminder.away ?: return false
        val teams = item.teams ?: return false
        return (SportsLiveTeams.matches(home, teams.home) && SportsLiveTeams.matches(away, teams.away)) ||
            (SportsLiveTeams.matches(home, teams.away) && SportsLiveTeams.matches(away, teams.home))
    }

    /**
     * [state] after the bell on [item] is pressed. On: any opt-out for it goes and it is reminded by hand.
     * Off: its reminders go and, so a followed team's game is not reminded automatically, it is opted out.
     * Entries over [KEEP_AFTER_START_MS] old are dropped either way.
     */
    fun toggle(state: RemindersState, item: SportsLiveItem, nowMs: Long): RemindersState {
        val reminder = of(item) ?: return state
        val on = state.isReminded(item)
        val reminders = state.reminders.filterNot { matches(it, item) }
        val optOuts = state.optOuts.filterNot { matches(it, item) }
        return state.copy(
            reminders = prune(if (on) reminders else reminders + reminder, nowMs),
            optOuts = prune(if (on) optOuts + reminder else optOuts, nowMs)
        )
    }

    /** [entries] without those started over [KEEP_AFTER_START_MS] ago, at most [MAX_STORED]. */
    fun prune(entries: List<GameReminder>, nowMs: Long): List<GameReminder> =
        current(entries, nowMs).sortedBy { it.startMs }.take(MAX_STORED)

    /** [entries] as read: those started over [KEEP_AFTER_START_MS] ago are gone, the rest keep their order. */
    fun current(entries: List<GameReminder>, nowMs: Long): List<GameReminder> =
        entries.filter { nowMs - it.startMs <= KEEP_AFTER_START_MS }

    private fun sourceKeys(item: SportsLiveItem): Set<String> =
        (listOf(item) + item.alternates).mapTo(LinkedHashSet()) { it.streamKey }
}
