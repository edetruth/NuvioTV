package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.SportsLiveItem
import java.time.Instant
import java.time.ZoneId

/**
 * A calm Live now on opening (J1, user TV report 2026-11-21: "it shows 4 games, then 33 arrive and the rows reshuffle;
 * focus ends mid-list"). The list waits behind its loading rows until most addons have answered, at most [HOLD_MS]
 * from opening, and then shows at once; what arrives later waits behind "N new" instead of moving rows.
 *
 * - The first load publishes an early list once [mostAnswered] (three in four catalogs), so one slow addon (up to its
 *   15 s timeout) never holds the whole list; the full load follows.
 * - The saved list of an earlier visit shows only once [HOLD_MS] passed with no load of this visit yet.
 * - Once Live now has been shown this visit, every later build keeps its rows in place ([sportsLiveFreeze]): new rows
 *   wait behind "N new" whether or not the list holds focus.
 */
object SportsLiveCalmOpen {
    /** The longest Live now waits behind its loading rows from opening (user: "cap ~3 s"). */
    const val HOLD_MS = 3_000L

    /** The share of catalogs that is "most addons answered". */
    private const val MOST_NUMERATOR = 3
    private const val MOST_DENOMINATOR = 4

    /** An early list is worth publishing only when a load asks this many catalogs or more. */
    private const val EARLY_MIN_CATALOGS = 3

    /** True when a load asking [catalogs] catalogs may publish an early list. */
    fun offersEarlyList(catalogs: Int): Boolean = catalogs >= EARLY_MIN_CATALOGS

    /** True when [answered] of [total] catalogs is most of them, but not all (all: the full load is the list). */
    fun mostAnswered(answered: Int, total: Int): Boolean =
        offersEarlyList(total) && answered < total && answered * MOST_DENOMINATOR >= total * MOST_NUMERATOR

    /**
     * Whether Live now still shows its loading rows: no load of this visit has landed ([hasVisitLoad] is false) and
     * [HOLD_MS] has not passed since [openedAtMs].
     */
    fun holding(hasVisitLoad: Boolean, openedAtMs: Long, nowMs: Long): Boolean =
        !hasVisitLoad && nowMs - openedAtMs < HOLD_MS

    /** Settled screens (live.26, amendment 2 "show ready in 1 s"): a recent saved list shows this long after opening. */
    const val READY_MS = 1_000L

    /** A saved list this recent (one Live now refresh, as a kept list may be: brief question 1) counts as ready. */
    const val RECENT_LIST_MS = 3 * 60_000L

    /**
     * Whether Live now still shows its loading rows rather than the list it has: no load of this visit yet
     * ([hasVisitLoad] false) and [HOLD_MS] not passed ([holdOver] false), unless [READY_MS] passed ([readyOver]) and the
     * saved list is at most [RECENT_LIST_MS] old ([savedAgeMs]; null: none). An older saved list still waits for this
     * visit's load, at most [HOLD_MS], as J1.
     */
    fun holdsLiveNow(hasVisitLoad: Boolean, readyOver: Boolean, holdOver: Boolean, savedAgeMs: Long?): Boolean {
        if (hasVisitLoad || holdOver) return false
        val recent = savedAgeMs != null && savedAgeMs in 0..RECENT_LIST_MS
        return !(readyOver && recent)
    }

    /**
     * Settled screens (live.26 TV: an ACL game already over was listed LIVE for 1.4 s, then read FT in place): Live now's
     * first list of a visit waits behind its loading rows while any of its games ([items]) has a score feed that has not
     * answered yet ([answered]: matched, confirmed to have no game, or no feed at all), at most [HOLD_MS] from opening.
     * Channels never wait.
     */
    fun awaitsScores(items: List<SportsLiveItem>, answered: (SportsLiveItem) -> Boolean): Boolean =
        items.any { !it.isChannel && !answered(it) }

    /** O (live.25): Live now unwatched this long comes back as a fresh open ([longAbsence]). */
    const val LONG_ABSENCE_MS = 10 * 60_000L

    /**
     * O (live.25, user TV report 2026-11-22: last night's games still listed in the morning, until a tab switch): Live now
     * unwatched (hidden or under the screensaver) from [sinceMs] to [nowMs] for [LONG_ABSENCE_MS] or more, or into another
     * day in [zone], is a fresh open on return: the first board of a newer load shows as built, with no calm or browse hold.
     */
    fun longAbsence(sinceMs: Long, nowMs: Long, zone: ZoneId): Boolean =
        nowMs - sinceMs >= LONG_ABSENCE_MS ||
            Instant.ofEpochMilli(sinceMs).atZone(zone).toLocalDate() != Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
}
