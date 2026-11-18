package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.ScoresMode

/**
 * Live now's order (#43): by start time (default), or Most exciting (close games late, overtime,
 * recent lead changes first). Remembered per profile; it applies to the Cards and Scoreboard layouts.
 */
enum class LiveNowSort {
    START_TIME,
    MOST_EXCITING;

    companion object {
        /** The sort control shows only while scores are on (not Off, setting read) and not on the Timeline, which stays by time. */
        fun offered(mode: ScoresMode?, layout: SportsLiveLayout): Boolean =
            mode != null && mode != ScoresMode.OFF && layout != SportsLiveLayout.TIMELINE

        /** The sort in force: [stored] while [offered], else Start time (a stored Most exciting falls back, kept for later). */
        fun effective(stored: LiveNowSort, mode: ScoresMode?, layout: SportsLiveLayout): LiveNowSort =
            if (offered(mode, layout)) stored else START_TIME

        /** Why tags show only with scores shown; Hide until focused keeps the order but no tags (decision 1). */
        fun showsWhyTags(mode: ScoresMode?): Boolean = mode == ScoresMode.SHOW

        fun decode(stored: String?): LiveNowSort = entries.firstOrNull { it.name == stored } ?: START_TIME
    }
}
