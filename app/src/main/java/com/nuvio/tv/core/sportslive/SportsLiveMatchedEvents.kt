package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The ESPN game each source ([com.nuvio.tv.domain.model.SportsLiveItem.streamKey]) was matched to, by the score repository
 * (live.23, TV report #10: the WSL game Chelsea at Liverpool showed as three rows, two labelled Premier League). Copies
 * matched to one ESPN event are one game whatever their addons call it: the next list build merges them
 * ([SportsLiveItemBucketer.merge]), shown as the copy that names the event's league. Bounded, thread-safe.
 *
 * A match lasts while its source is listed ([retain], live.23 review B3) and until ESPN's day changes: an addon that
 * re-uses an item id for a later game must never merge it by yesterday's match.
 */
class SportsLiveMatchedEvents {

    private class Match(val eventId: String, val league: EspnLeague)

    private val matches = LinkedHashMap<String, Match>()
    private val revisionFlow = MutableStateFlow(0L)
    /** ESPN's (US Eastern) day of the matches kept. */
    private var day: LocalDate? = null

    /**
     * Bumped when a source joins an ESPN game another source already holds (copies that now merge): lists are built
     * again ([SportsLiveSharedBuckets.matchChanges], debounced). A poll that matches the same game again changes nothing.
     */
    val revision: StateFlow<Long> = revisionFlow.asStateFlow()

    @Synchronized
    fun record(sourceKeys: Collection<String>, event: ScoreSnapshot, nowMs: Long = System.currentTimeMillis()) {
        val today = espnDay(nowMs)
        if (day != today) matches.clear()
        day = today
        var changed = false
        // Soccer/all's games keep their own competition: the league a copy would name.
        val league = if (event.league == com.nuvio.tv.core.sportslive.scores.EspnLeagueMap.SOCCER_ALL &&
            event.leagueSlug != event.league.league
        ) EspnLeague("soccer", event.leagueSlug) else event.league
        for (key in sourceKeys) {
            if (matches[key]?.eventId == event.eventId) continue
            matches.remove(key)
            if (matches.size >= MAX_KEPT) matches.remove(matches.keys.first())
            matches[key] = Match(event.eventId, league)
            changed = true
        }
        if (changed && matches.values.count { it.eventId == event.eventId } > 1) revisionFlow.value += 1
    }

    /** True when the sources [a] and [b] were matched to the same ESPN event. */
    @Synchronized
    fun sameEvent(a: String, b: String): Boolean {
        val x = matches[a] ?: return false
        val y = matches[b] ?: return false
        return x.eventId == y.eventId
    }

    /** The league of the ESPN event source [key] was matched to; null when none. */
    @Synchronized
    fun leagueOf(key: String): EspnLeague? = matches[key]?.league

    /** Forgets the matches of sources no longer listed: [listed] are the source keys of the newest load. */
    @Synchronized
    fun retain(listed: Set<String>) {
        matches.keys.retainAll(listed)
    }

    @Synchronized
    internal fun clear() = matches.clear()

    companion object {
        private const val MAX_KEPT = 2_000

        private val EASTERN: ZoneId = ZoneId.of("America/New_York")

        /** ESPN's day at [nowMs]: its scoreboards are filed by US Eastern date. */
        internal fun espnDay(nowMs: Long): LocalDate = Instant.ofEpochMilli(nowMs).atZone(EASTERN).toLocalDate()

        /** What every list uses. */
        val shared = SportsLiveMatchedEvents()
    }
}
