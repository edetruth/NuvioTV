package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot

/**
 * The ESPN game each source ([com.nuvio.tv.domain.model.SportsLiveItem.streamKey]) was matched to, by the score repository
 * (live.23, TV report #10: the WSL game Chelsea at Liverpool showed as three rows, two labelled Premier League). Copies
 * matched to one ESPN event are one game whatever their addons call it: the next list build merges them
 * ([SportsLiveItemBucketer.merge]), shown as the copy that names the event's league. Bounded, thread-safe.
 */
class SportsLiveMatchedEvents {

    private class Match(val eventId: String, val league: EspnLeague)

    private val matches = LinkedHashMap<String, Match>()

    @Synchronized
    fun record(sourceKeys: Collection<String>, event: ScoreSnapshot) {
        // Soccer/all's games keep their own competition: the league a copy would name.
        val league = if (event.league == com.nuvio.tv.core.sportslive.scores.EspnLeagueMap.SOCCER_ALL &&
            event.leagueSlug != event.league.league
        ) EspnLeague("soccer", event.leagueSlug) else event.league
        for (key in sourceKeys) {
            matches.remove(key)
            if (matches.size >= MAX_KEPT) matches.remove(matches.keys.first())
            matches[key] = Match(event.eventId, league)
        }
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

    @Synchronized
    internal fun clear() = matches.clear()

    companion object {
        private const val MAX_KEPT = 2_000

        /** What every list uses. */
        val shared = SportsLiveMatchedEvents()
    }
}
