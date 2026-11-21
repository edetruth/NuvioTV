package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.MySport

/**
 * "You watched Cricket · Add to My sports?" (coordinator decision 2026-11-21, after More live): one quiet card at the top
 * of Live now's list, the next time Live now shows after a More live game was played ([SportsLiveMoreLive.promptFor]).
 * Add puts the sport in My sports; Not now is remembered for that sport; leaving the card unanswered (Back, moving
 * past it, leaving Live now) keeps it for the next time once: after [MAX_IGNORES] it stops asking for that sport.
 * Per profile, in the sports & live preferences.
 */
object SportsLiveAddSportPrompt {
    /** Unanswered showings after which a sport is not asked about again. */
    const val MAX_IGNORES = 2

    /** What the profile said: sports declined ("Not now"), and how often each sport's card was left unanswered. */
    data class Memory(val declined: Set<String> = emptySet(), val ignored: Map<String, Int> = emptyMap()) {
        /** The ids [SportsLiveMoreLive.promptFor] must not ask about: declined, or ignored [MAX_IGNORES] times. */
        val notAsked: Set<String> get() = declined + ignored.filterValues { it >= MAX_IGNORES }.keys

        /** True when [sport] may be asked about. */
        fun asks(sport: MySport): Boolean = sport.id !in notAsked

        fun decline(sport: MySport): Memory = copy(declined = declined + sport.id)

        fun ignore(sport: MySport): Memory = copy(ignored = ignored + (sport.id to (ignored[sport.id] ?: 0) + 1))

        /** As stored: the declined ids, and "id:count" per ignored sport. */
        fun encodeIgnored(): Set<String> = ignored.mapTo(LinkedHashSet()) { (id, count) -> "$id:$count" }
    }

    /** Stored "id:count" entries ([Memory.encodeIgnored]) as counts; unreadable entries are skipped. */
    fun decodeIgnored(stored: Set<String>?): Map<String, Int> =
        stored.orEmpty().mapNotNull { entry ->
            val id = entry.substringBeforeLast(':', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val count = entry.substringAfterLast(':').toIntOrNull() ?: return@mapNotNull null
            id to count
        }.toMap()
}
