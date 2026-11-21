package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Upcoming's working set (live.24, user-approved): of the ~2,400 games the addons list ahead, a load sorts (merges and
 * later infers) only today's, tomorrow's and the day after's ([days]), every followed team's game whatever its day, and
 * every game with no time of day (the Agenda's Any time); a later day is sorted when the viewer goes to it (its day tab,
 * Later ›, the list's end), once per load. With [mySports] set, a game whose own texts name a sport outside My sports
 * waits too, until All sports is picked (a followed team's never does).
 */
data class SportsLiveUpcomingWindow(
    val zone: ZoneId,
    /** Days sorted from today; never fewer than [NEAR_DAYS]. */
    val days: Int = NEAR_DAYS,
    /** My sports for the wait by sport; null: every sport (All sports picked, or nothing to scope). */
    val mySports: MySports? = null,
    val followed: List<FollowedTeam> = emptyList()
) {
    companion object {
        /** Today, tomorrow and the day after. */
        const val NEAR_DAYS = 3
    }
}

/** Upcoming's rows for a [SportsLiveUpcomingWindow], and what still waits. */
class SportsLiveUpcomingBucket(
    val rows: List<SportsLiveItem>,
    /** Dates after the sorted days that have games waiting, in order: their day tabs show before they are sorted. */
    val pendingDates: List<LocalDate>,
    /** Games on the sorted days that wait for All sports (each listing counted: a cheap count, copies not merged). */
    val waitingBySport: Int
)

/** The pieces of a windowed Upcoming ([SportsLiveSharedBuckets.upcoming]): who is in, and which days each chunk sorts. */
internal object SportsLiveUpcomingChunks {
    /**
     * How far either side of a chunk's days its copies are looked for: timed copies of one game merge only within half an
     * hour of their primary ([SportsLiveItemBucketer.merge]), so a few hours hold any chain of them.
     */
    const val MARGIN_MS = 3 * 60 * 60 * 1000L

    /** A chunk: the days [from] until [until] (null: no end), or only followed teams' games ([followedOnly]). */
    data class Key(val from: LocalDate, val until: LocalDate?, val followedOnly: Boolean = false) {
        /** Where its merge is kept for the next load (a followed chunk's days move as days are asked). */
        val slot: String get() = if (followedOnly) "followed" else from.toString()
    }

    /** The listed games a window lets in, and which of them a followed team plays in. */
    class Scope(val inScope: List<SportsLiveItem>, val followedKeys: Set<String>, val waiting: List<SportsLiveItem>)

    fun timed(item: SportsLiveItem): Boolean = item.startMs != null && item.startHasTimeOfDay

    fun dateOf(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    fun startOf(date: LocalDate, zone: ZoneId): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * [listed] split for [window]: a game whose own texts name a sport outside My sports waits (the cheap, certain part of
     * [SportsLiveMySportsScope]: an unknown sport or one My sports holds is sorted, and the full scope still applies after),
     * unless a followed team plays in it.
     */
    fun scope(listed: List<SportsLiveItem>, window: SportsLiveUpcomingWindow): Scope {
        val followedKeys = if (window.followed.isEmpty()) {
            emptySet()
        } else {
            SportsLiveItemBucketer.markFollowed(listed, window.followed).filter { it.followed }.mapTo(HashSet()) { it.streamKey }
        }
        val mySports = window.mySports
        if (mySports == null || mySports.isDefault) return Scope(listed, followedKeys, emptyList())
        val (waiting, inScope) = listed.partition { item ->
            item.streamKey !in followedKeys && SportsLiveMySportsScope.kindOf(item) != null &&
                SportsLiveMySportsScope.sportsOf(item).none { it in mySports.effectiveSports }
        }
        return Scope(inScope, followedKeys, waiting)
    }

    /** The games chunk [key] merges: its days' timed games and a [MARGIN_MS] either side; the first chunk, every untimed one. */
    fun population(scope: Scope, key: Key, near: Key, zone: ZoneId): List<SportsLiveItem> {
        val fromMs = startOf(key.from, zone) - MARGIN_MS
        val untilMs = key.until?.let { startOf(it, zone) + MARGIN_MS } ?: Long.MAX_VALUE
        return scope.inScope.filter { item ->
            when {
                key.followedOnly && item.streamKey !in scope.followedKeys -> false
                !timed(item) -> key == near
                key == near -> item.startMs!! < untilMs
                else -> item.startMs!! in fromMs until untilMs
            }
        }
    }

    /** True when chunk [key] lists [row]: a timed row on its days; an untimed one only in the first chunk ([near]). */
    fun keeps(key: Key, near: Key, row: SportsLiveItem, zone: ZoneId): Boolean {
        if (!timed(row)) return key == near
        val date = dateOf(row.startMs!!, zone)
        if (key != near && date < key.from) return false
        return key.until == null || date < key.until
    }
}
