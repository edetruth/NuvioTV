package com.nuvio.tv.core.sportslive.alerts

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoreTeam
import com.nuvio.tv.core.sportslive.scores.TeamFollows
import com.nuvio.tv.domain.model.FollowedTeam
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
    val remindMyTeams: Boolean = true,
    /**
     * Reminder-only teams (#154): "Remind me for every ‹team› game". Each of their games gets the kickoff
     * prompt, with no follow and no score alerts. Stored as a follow is ([TeamFollows.teamToFollow]).
     */
    val remindedTeams: List<FollowedTeam> = emptyList()
) {
    /**
     * Whether [item] gets a "Watch now?" prompt at kickoff: reminded by hand, or (unless opted out) a followed
     * team's game, or a reminder-only team's ([event]: its strict ESPN game, matched by ESPN id; null: by name).
     */
    fun isReminded(item: SportsLiveItem, event: ScoreSnapshot? = null): Boolean {
        if (reminders.any { GameReminders.matches(it, item) }) return true
        if (optOuts.any { GameReminders.matches(it, item) }) return false
        return (remindMyTeams && item.followed) || GameReminders.remindsTeamOf(remindedTeams, item, event)
    }

    /** Whether an ESPN game with no card (a Team page's next game) is reminded: by hand, or a reminder-only team's. */
    fun isReminded(event: ScoreSnapshot): Boolean =
        reminders.any { GameReminders.matches(it, event) } || GameReminders.remindsTeamOf(remindedTeams, event)
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

    /**
     * Whether "Remind me" is offered on an ESPN game no card plays yet (a Team page's next game): a team
     * game ESPN has not started, with a kickoff time still ahead and both sides named. At kickoff the
     * reminder matches whichever card then lists the game, by its two sides.
     */
    fun canRemind(event: ScoreSnapshot, nowMs: Long): Boolean {
        val start = event.startMs ?: return false
        return event.individual == null && event.timeValid && event.state == ScoreState.PRE && start > nowMs &&
            sideName(event.home) != null && sideName(event.away) != null
    }

    /** What is stored for [event] (no source keys: it matches cards by its sides); null when it cannot be reminded. */
    fun of(event: ScoreSnapshot): GameReminder? {
        val start = event.startMs?.takeIf { event.timeValid } ?: return null
        val home = sideName(event.home) ?: return null
        val away = sideName(event.away) ?: return null
        return GameReminder(keys = emptySet(), title = "$away at $home", home = home, away = away, startMs = start)
    }

    /** True when [reminder] is [event]'s game: the same start (within 30 minutes) and the same two sides, either way round. */
    fun matches(reminder: GameReminder, event: ScoreSnapshot): Boolean {
        val start = event.startMs ?: return false
        if (abs(start - reminder.startMs) > SAME_GAME_WINDOW_MS) return false
        val home = reminder.home ?: return false
        val away = reminder.away ?: return false
        val eventHome = sideName(event.home) ?: return false
        val eventAway = sideName(event.away) ?: return false
        return (SportsLiveTeams.matches(home, eventHome) && SportsLiveTeams.matches(away, eventAway)) ||
            (SportsLiveTeams.matches(home, eventAway) && SportsLiveTeams.matches(away, eventHome))
    }

    /** [state] after the bell on [event] is pressed: reminded by hand, or its reminders gone. */
    fun toggle(state: RemindersState, event: ScoreSnapshot, nowMs: Long): RemindersState {
        val reminder = of(event) ?: return state
        val on = state.isReminded(event)
        val reminders = state.reminders.filterNot { matches(it, event) }
        return state.copy(reminders = prune(if (on) reminders else reminders + reminder, nowMs))
    }

    /**
     * Whether [teams] (reminder-only) play in [item]: on its strict ESPN [event] by ESPN id
     * ([TeamFollows.followsSide]); with no event, by name, as followed teams are matched to cards.
     */
    fun remindsTeamOf(teams: List<FollowedTeam>, item: SportsLiveItem, event: ScoreSnapshot?): Boolean {
        if (teams.isEmpty() || item.isChannel) return false
        if (event != null) return remindsTeamOf(teams, event)
        return SportsLiveTeams.isFollowed(item, teams) || item.alternates.any { SportsLiveTeams.isFollowed(it, teams) }
    }

    /** Whether [teams] (reminder-only) play in ESPN [event]. */
    fun remindsTeamOf(teams: List<FollowedTeam>, event: ScoreSnapshot): Boolean =
        teams.isNotEmpty() && event.individual == null &&
            (TeamFollows.followersOf(teams, event, event.home).isNotEmpty() || TeamFollows.followersOf(teams, event, event.away).isNotEmpty())

    /** Whether [side] of [event] is on the reminder-only list of [state]. */
    fun isTeamReminded(state: RemindersState, event: ScoreSnapshot, side: ScoreTeam): Boolean =
        TeamFollows.followersOf(state.remindedTeams, event, side).isNotEmpty()

    /**
     * Whether More… offers "Remind me for every ‹team› game" for [side] of [event] (#154): not for a team
     * already followed while "Remind me for my teams" is On (those games are reminded already), unless it is
     * on the list anyway (so it can be taken off).
     */
    fun offersTeamReminder(state: RemindersState, followed: List<FollowedTeam>, event: ScoreSnapshot, side: ScoreTeam): Boolean {
        if (isTeamReminded(state, event, side)) return true
        if (TeamFollows.teamToFollow(event, side) == null) return false
        return !(state.remindMyTeams && TeamFollows.followersOf(followed, event, side).isNotEmpty())
    }

    /** [state] after "Remind me for every ‹team› game" (or "Stop reminding…") on [side] of [event]. */
    fun toggleTeam(state: RemindersState, event: ScoreSnapshot, side: ScoreTeam): RemindersState {
        val on = TeamFollows.followersOf(state.remindedTeams, event, side)
        if (on.isNotEmpty()) return state.copy(remindedTeams = state.remindedTeams - on.toSet())
        val team = TeamFollows.teamToFollow(event, side) ?: return state
        return state.copy(remindedTeams = state.remindedTeams + team)
    }

    /** [state] without [team] (Settings → Alerts → "Teams I'm reminded about", OK removes). */
    fun removeTeam(state: RemindersState, team: FollowedTeam): RemindersState =
        state.copy(remindedTeams = state.remindedTeams.filterNot { it == team })

    private fun sideName(team: ScoreTeam): String? =
        listOf(team.displayName, team.shortDisplayName, team.name).firstOrNull { !it.isNullOrBlank() }?.trim()

    /** [entries] without those started over [KEEP_AFTER_START_MS] ago, at most [MAX_STORED]. */
    fun prune(entries: List<GameReminder>, nowMs: Long): List<GameReminder> =
        current(entries, nowMs).sortedBy { it.startMs }.take(MAX_STORED)

    /** [entries] as read: those started over [KEEP_AFTER_START_MS] ago are gone, the rest keep their order. */
    fun current(entries: List<GameReminder>, nowMs: Long): List<GameReminder> =
        entries.filter { nowMs - it.startMs <= KEEP_AFTER_START_MS }

    private fun sourceKeys(item: SportsLiveItem): Set<String> =
        (listOf(item) + item.alternates).mapTo(LinkedHashSet()) { it.streamKey }
}
