package com.nuvio.tv.core.sportslive

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
}
