package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.runtime.Immutable
import com.nuvio.tv.core.sportslive.GameStatus
import com.nuvio.tv.core.sportslive.alerts.GameReminders
import com.nuvio.tv.core.sportslive.scores.GamesToWatch
import com.nuvio.tv.domain.model.SportsLiveItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * A day of the Agenda (V2 Upcoming, #132): Today, Tomorrow, a later date, or the closing "Any time" group;
 * on Replays (P4) Today, Yesterday, an earlier date, or the closing "Other replays" ([UNDATED]).
 */
enum class AgendaDayKind { TODAY, TOMORROW, YESTERDAY, DATE, ANY_TIME, UNDATED }

/** A day header: its [kind] and [date] (null for Any time). */
@Immutable
data class AgendaDay(val kind: AgendaDayKind, val date: LocalDate?)

/**
 * A time slot: the games starting at [startMs] ([count] of them) on day [day]; Any time is one slot with no time.
 * [picks]: the day's "Games to watch" group (#137) at its top, no time.
 */
@Immutable
data class AgendaSlot(val day: Int, val startMs: Long?, val count: Int, val picks: Boolean = false)

/**
 * A game row of the Agenda: its Scoreboard [row], the index of its [day] and its [slot]. [pick]: listed in its day's
 * Games to watch (#137); the same game is also listed in its time slot.
 */
@Immutable
data class AgendaGame(val row: ScoreboardRow, val day: Int, val slot: Int, val pick: Boolean = false)

/** What the Agenda list draws at one position: a sticky day header, or a game (its slot header rides on its first game). */
sealed interface AgendaEntry {
    data class Day(val day: Int) : AgendaEntry
    data class Game(val game: Int) : AgendaEntry
}

/**
 * Upcoming as an Agenda (V2, #132): its games in start order under sticky **day** headers (Today, Tomorrow,
 * then the date) and **time-slot** headers ("3:30 PM · in 2 h 21 m", "6:00 PM · 4 games"), so the list is
 * the ruler. Games with a date but no time, and channels, close the list in **Any time**. Followed teams'
 * games lead their slot. Each day opens with its **Games to watch** (#137: up to three big games, [GamesToWatch]),
 * which stay in their time slots too. ↑ ↓ move game by game (headers are never focused), ← → jump a slot ([slotJump]),
 * CH+ / CH− a day ([dayJump]).
 */
@Immutable
data class SportsLiveAgenda(val days: List<AgendaDay>, val slots: List<AgendaSlot>, val games: List<AgendaGame>) {

    /** The games' items in the order listed. */
    val items: List<SportsLiveItem> get() = games.map { it.row.item }

    /** The list's positions: each day header, then its games. */
    val entries: List<AgendaEntry> by lazy {
        buildList {
            games.forEachIndexed { index, game ->
                if (index == 0 || games[index - 1].day != game.day) add(AgendaEntry.Day(game.day))
                add(AgendaEntry.Game(index))
            }
        }
    }

    /** The list position of game [game]. */
    fun listIndexOf(game: Int): Int = if (game !in games.indices) -1 else game + games[game].day + 1

    /** The game at list position [listIndex]; null on a day header. */
    fun gameAt(listIndex: Int): Int? = (entries.getOrNull(listIndex) as? AgendaEntry.Game)?.game

    /** Game [game] opens its slot: its slot header goes above it. */
    fun opensSlot(game: Int): Boolean = game == 0 || games[game - 1].slot != games[game].slot

    /** The first game of the slot before (or, [forward], after) game [game]'s; null at either end. */
    fun slotJump(game: Int, forward: Boolean): Int? {
        val slot = games.getOrNull(game)?.slot ?: return null
        val target = if (forward) slot + 1 else slot - 1
        return games.indexOfFirst { it.slot == target }.takeIf { it >= 0 }
    }

    /** The first game of the day before (or, [forward], after) game [game]'s; null at either end. */
    fun dayJump(game: Int, forward: Boolean): Int? {
        val day = games.getOrNull(game)?.day ?: return null
        val target = if (forward) day + 1 else day - 1
        return games.indexOfFirst { it.day == target }.takeIf { it >= 0 }
    }

    companion object {
        /**
         * The Agenda of Upcoming's [rows] (the Scoreboard's, in their order) at [nowMs] in [zone]: timed games by
         * start (ESPN's when matched), followed teams first within a slot, then as listed; the rest under Any time.
         */
        fun of(rows: List<ScoreboardRow>, nowMs: Long, zone: ZoneId): SportsLiveAgenda {
            val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            val timed = ArrayList<Pair<ScoreboardRow, Long>>()
            val anyTime = ArrayList<ScoreboardRow>()
            rows.forEach { row -> agendaStartOf(row)?.let { timed += row to it } ?: anyTime.add(row) }
            val ordered = timed.withIndex()
                .sortedWith(compareBy<IndexedValue<Pair<ScoreboardRow, Long>>> { it.value.second / MINUTE_MS }
                    .thenByDescending { it.value.first.followed }
                    .thenBy { it.index })
                .map { it.value }
            val days = ArrayList<AgendaDay>()
            val slots = ArrayList<AgendaSlot>()
            val games = ArrayList<AgendaGame>()
            // A start already passed (a late game still waiting) stays under Today; dates follow the start order.
            val byDay = ordered.groupBy { (_, start) -> maxOf(Instant.ofEpochMilli(start).atZone(zone).toLocalDate(), today) }
            byDay.forEach { (date, dayGames) ->
                days += AgendaDay(dayKindOf(date, today), date)
                val picks = GamesToWatch.pick(dayGames.map { it.first }) { it.bigGame }
                if (picks.isNotEmpty()) {
                    slots += AgendaSlot(days.lastIndex, null, picks.size, picks = true)
                    picks.forEach { (row, _) -> games += AgendaGame(row, days.lastIndex, slots.lastIndex, pick = true) }
                }
                var lastMinute: Long? = null
                dayGames.forEach { (row, start) ->
                    val minute = start / MINUTE_MS
                    if (minute != lastMinute) {
                        slots += AgendaSlot(days.lastIndex, start, 0)
                        lastMinute = minute
                    }
                    slots[slots.lastIndex] = slots.last().let { it.copy(count = it.count + 1) }
                    games += AgendaGame(row, days.lastIndex, slots.lastIndex)
                }
            }
            if (anyTime.isNotEmpty()) {
                days += AgendaDay(AgendaDayKind.ANY_TIME, null)
                slots += AgendaSlot(days.lastIndex, null, anyTime.size)
                // Dated games first, by date; then the rest (channels) as listed.
                anyTime.withIndex().sortedWith(compareBy<IndexedValue<ScoreboardRow>> { it.value.item.startMs ?: Long.MAX_VALUE }.thenBy { it.index })
                    .forEach { games += AgendaGame(it.value, days.lastIndex, slots.lastIndex) }
            }
            return SportsLiveAgenda(days, slots, games)
        }

        /**
         * Replays by day (V2 P4, #90): Today, Yesterday, then each earlier date, newest first, and within a day
         * newest first (by time, then as listed); replays with no date close the list under Other replays.
         * One slot per day: replays have no time-slot headers.
         */
        fun ofReplays(rows: List<ScoreboardRow>, nowMs: Long, zone: ZoneId): SportsLiveAgenda {
            val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            val dated = rows.withIndex().filter { it.value.item.startMs != null }
                .sortedWith(compareByDescending<IndexedValue<ScoreboardRow>> { it.value.item.startMs }.thenBy { it.index })
                .map { it.value }
            val undated = rows.filter { it.item.startMs == null }
            val days = ArrayList<AgendaDay>()
            val slots = ArrayList<AgendaSlot>()
            val games = ArrayList<AgendaGame>()
            var lastDate: LocalDate? = null
            dated.forEach { row ->
                // A replay dated ahead (a clock or zone slip) still reads as Today.
                val date = minOf(Instant.ofEpochMilli(row.item.startMs!!).atZone(zone).toLocalDate(), today)
                if (date != lastDate) {
                    days += AgendaDay(replayDayKindOf(date, today), date)
                    slots += AgendaSlot(days.lastIndex, null, 0)
                    lastDate = date
                }
                slots[slots.lastIndex] = slots.last().let { it.copy(count = it.count + 1) }
                games += AgendaGame(row, days.lastIndex, slots.lastIndex)
            }
            if (undated.isNotEmpty()) {
                days += AgendaDay(AgendaDayKind.UNDATED, null)
                slots += AgendaSlot(days.lastIndex, null, undated.size)
                undated.forEach { games += AgendaGame(it, days.lastIndex, slots.lastIndex) }
            }
            return SportsLiveAgenda(days, slots, games)
        }

        private fun replayDayKindOf(date: LocalDate, today: LocalDate): AgendaDayKind = when (ChronoUnit.DAYS.between(date, today)) {
            0L -> AgendaDayKind.TODAY
            1L -> AgendaDayKind.YESTERDAY
            else -> AgendaDayKind.DATE
        }

        private fun dayKindOf(date: LocalDate, today: LocalDate): AgendaDayKind = when (ChronoUnit.DAYS.between(today, date)) {
            0L -> AgendaDayKind.TODAY
            1L -> AgendaDayKind.TOMORROW
            else -> AgendaDayKind.DATE
        }

        private const val MINUTE_MS = 60_000L
    }
}

/**
 * A row's timed start for the Agenda: ESPN's when matched and timed, else the card's when it has a time of
 * day; null (Any time) for a date alone, no start, or a channel.
 */
internal fun agendaStartOf(row: ScoreboardRow): Long? {
    if (row.item.isChannel) return null
    val art = row.art
    if (art != null) return art.startMs?.takeIf { art.hasTimeOfDay }
    return row.item.startMs?.takeIf { row.item.startHasTimeOfDay }
}

/**
 * (hours, minutes) to a slot's [startMs] for its header and the stage's "When", only inside the next day (a
 * countdown of days says nothing); null otherwise or once started.
 */
internal fun agendaCountdown(startMs: Long?, nowMs: Long): Pair<Int, Int>? =
    startMs?.let { GameStatus.startsIn(it, nowMs) }?.takeIf { it.first < COUNTDOWN_MAX_HOURS }

private const val COUNTDOWN_MAX_HOURS = 24

/** The Upcoming stage's buttons (V2): a primary and **More…**. */
internal enum class UpcomingStageButton { REMIND, WATCH, MORE }

/**
 * The Upcoming stage's buttons for [item] at [nowMs]: **Remind me** leads while its timed start is more than
 * [REMIND_LEAD_MS] away (and a reminder can be set); otherwise **Watch** does. **More…** always follows.
 */
internal fun upcomingStageButtons(item: SportsLiveItem, startMs: Long?, nowMs: Long): List<UpcomingStageButton> {
    val remindFirst = GameReminders.canRemind(item, nowMs) && startMs != null && startMs - nowMs > REMIND_LEAD_MS
    return listOf(if (remindFirst) UpcomingStageButton.REMIND else UpcomingStageButton.WATCH, UpcomingStageButton.MORE)
}

/** Inside this of its start, a game's stage leads with Watch, not Remind me. */
internal const val REMIND_LEAD_MS = 15 * 60_000L

/** The Agenda row's bell: outline when off, filled blue when set; none for a game that cannot be reminded. */
internal enum class AgendaBell { NONE, OFF, ON }

internal fun agendaBellOf(remindable: Boolean, reminded: Boolean): AgendaBell = when {
    !remindable -> AgendaBell.NONE
    reminded -> AgendaBell.ON
    else -> AgendaBell.OFF
}

/** A remote key on an Agenda row beyond ↑ ↓. */
internal enum class AgendaKey { SLOT_BACK, SLOT_FORWARD, DAY_BACK, DAY_FORWARD }

/** The game [key] moves focus to from game [game]; null leaves the key to the default (the stage, the rail). */
internal fun SportsLiveAgenda.target(game: Int, key: AgendaKey): Int? = when (key) {
    AgendaKey.SLOT_BACK -> slotJump(game, forward = false)
    AgendaKey.SLOT_FORWARD -> slotJump(game, forward = true)
    AgendaKey.DAY_BACK -> dayJump(game, forward = false)
    AgendaKey.DAY_FORWARD -> dayJump(game, forward = true)
}
