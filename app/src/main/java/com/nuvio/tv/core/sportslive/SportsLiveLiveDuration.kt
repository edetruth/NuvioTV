package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.SportsLiveItem

/**
 * Quota-free cleanup of games an addon keeps marking live after they are over: a game whose known
 * kickoff is longer ago than its sport's longest plausible run is hidden from Live now without a
 * single stream request. The on-focus probe stays the exact check for everything younger, and a
 * game whose links were found recently is never hidden this way (the caller checks that).
 * The limits are generous on purpose: hiding a game still on is worse than showing a dead one
 * (a tennis or golf "start" is often the session's, not the match's).
 */
object SportsLiveLiveDuration {
    private const val HOUR_MS = 60 * 60 * 1000L

    /** For a sport it does not recognise (or none). */
    const val DEFAULT_MAX_MS = 3 * HOUR_MS + 30 * 60 * 1000L

    // Checked in order; the first sport keyword in the item's sport wins.
    private val bySport: List<Pair<Set<String>, Long>> = listOf(
        setOf("soccer") to 3 * HOUR_MS + 15 * 60 * 1000L,
        setOf("basketball", "nba", "wnba", "ncaab", "hockey", "nhl") to 3 * HOUR_MS,
        setOf("nfl", "ncaaf", "american football", "baseball", "mlb", "nascar", "indycar") to
            4 * HOUR_MS + 30 * 60 * 1000L,
        setOf("motogp", "boxing", "ufc", "mma", "wrestling", "wwe", "aew", "fight", "fights") to 6 * HOUR_MS,
        setOf("tennis") to 8 * HOUR_MS,
        setOf("cricket") to 9 * HOUR_MS,
        setOf("golf") to 12 * HOUR_MS
    )

    /** The longest a game of [sport] (an item's sport, genre or catalog name) plausibly stays live. */
    fun maxLiveMs(sport: String?): Long =
        bySport.firstOrNull { (keywords, _) -> SportsLiveKeywords.containsKeyword(sport, keywords) }?.second
            ?: DEFAULT_MAX_MS

    /**
     * #358: the longest [item] plausibly stays live, by the sport the app infers for it
     * ([SportsLiveMySportsScope.kindOf]: its sport word, title, genres, a single-sport catalog, its teams; a bare
     * "Football" is soccer), [card]'s when the source names none; the sport word's own limit ([maxLiveMs]) only when no
     * kind is known.
     */
    fun maxLiveMsOf(item: SportsLiveItem, card: SportsLiveItem = item): Long {
        val kind = SportsLiveMySportsScope.kindOf(item) ?: SportsLiveMySportsScope.kindOf(card)
        return kind?.let(byKind::get) ?: maxLiveMs(item.sport ?: card.sport)
    }

    // #358: the same limits by inferred kind. Motorsport takes the longest of its series (MotoGP's six hours).
    private val byKind: Map<SportsLiveSportKind, Long> = mapOf(
        SportsLiveSportKind.SOCCER to 3 * HOUR_MS + 15 * 60 * 1000L,
        SportsLiveSportKind.BASKETBALL to 3 * HOUR_MS,
        SportsLiveSportKind.HOCKEY to 3 * HOUR_MS,
        SportsLiveSportKind.FOOTBALL to 4 * HOUR_MS + 30 * 60 * 1000L,
        SportsLiveSportKind.BASEBALL to 4 * HOUR_MS + 30 * 60 * 1000L,
        SportsLiveSportKind.MOTORSPORT to 6 * HOUR_MS,
        SportsLiveSportKind.MMA to 6 * HOUR_MS,
        SportsLiveSportKind.BOXING to 6 * HOUR_MS,
        SportsLiveSportKind.WRESTLING to 6 * HOUR_MS,
        SportsLiveSportKind.TENNIS to 8 * HOUR_MS,
        SportsLiveSportKind.CRICKET to 9 * HOUR_MS,
        SportsLiveSportKind.GOLF to 12 * HOUR_MS
    )

    /**
     * True when every source of [item] (a merged card's alternates included) with a timed kickoff
     * started longer ago than its sport allows, and at least one has such a kickoff. Channels and
     * games without a known time of day are never hidden this way.
     */
    fun isOverdue(item: SportsLiveItem, nowMs: Long): Boolean {
        if (item.isChannel) return false
        val timed = (listOf(item) + item.alternates).filter { it.startMs != null && it.startHasTimeOfDay }
        return timed.isNotEmpty() && timed.all { source ->
            nowMs - source.startMs!! > maxLiveMsOf(source, item)
        }
    }
}
