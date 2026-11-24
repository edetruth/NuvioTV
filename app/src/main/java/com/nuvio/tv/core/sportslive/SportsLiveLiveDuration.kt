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
    fun maxLiveMs(sport: String?): Long {
        val table = bySport.firstOrNull { (keywords, _) -> SportsLiveKeywords.containsKeyword(sport, keywords) }?.second ?: DEFAULT_MAX_MS
        // A bare "Football" may be American football: the longer floor (unsure means longer).
        val kind = if (isBareFootball(sport)) SportsLiveSportKind.FOOTBALL else SportsLiveSportKind.of(sport)
        return limit(table, kind)
    }

    /** #454: past a game's expected length and late start ([SportsLiveGameLength]), this much more before it is overdue. */
    const val OVERDUE_BUFFER_MS = 30 * 60 * 1000L

    /**
     * #454, the one formula: a sport's overdue limit is never below its expected length plus the late start plus
     * [OVERDUE_BUFFER_MS] (basketball and hockey 3 h 50, soccer 3 h 45, an unknown sport 4 h 50); a longer [table] value
     * (tennis, golf, fight cards) stands.
     */
    fun limit(table: Long, kind: SportsLiveSportKind?): Long = maxOf(table, floorOf(kind))

    /** #454: expected length + late start + [OVERDUE_BUFFER_MS] for [kind] (an unknown sport's length when no team sport). */
    fun floorOf(kind: SportsLiveSportKind?): Long = SportsLiveGameLength.ofEspnSport(espnSportOf(kind)) + OVERDUE_BUFFER_MS

    private fun espnSportOf(kind: SportsLiveSportKind?): String = when (kind) {
        SportsLiveSportKind.SOCCER -> "soccer"
        SportsLiveSportKind.BASKETBALL -> "basketball"
        SportsLiveSportKind.HOCKEY -> "hockey"
        SportsLiveSportKind.FOOTBALL -> "football"
        SportsLiveSportKind.BASEBALL -> "baseball"
        else -> ""
    }

    /**
     * #358: the longest [item] plausibly stays live, by the sport the app infers for it
     * ([SportsLiveMySportsScope.kindOf]: its sport word, title, genres, a single-sport catalog, its teams; a bare
     * "Football" is soccer), [card]'s when the source names none; the sport word's own limit ([maxLiveMs]) only when no
     * kind is known.
     */
    fun maxLiveMsOf(item: SportsLiveItem, card: SportsLiveItem = item): Long {
        val kind = (SportsLiveMySportsScope.kindOf(item) ?: SportsLiveMySportsScope.kindOf(card))?.let { kind ->
            // L2: a bare "Football" is read as soccer, but a guess must never cut an American football game short: with
            // nothing else saying soccer, the longer (American football) limit.
            if (kind == SportsLiveSportKind.SOCCER && saysBareFootball(item, card) && !saysSoccer(item, card)) SportsLiveSportKind.FOOTBALL else kind
        }
        return kind?.let { limit(byKind[it] ?: DEFAULT_MAX_MS, it) } ?: maxLiveMs(item.sport ?: card.sport)
    }

    // "Football" itself is no soccer clue (it is the ambiguous word); "Football League" counts only by its other words.
    private val FOOTBALL_WORD = Regex("(?i)football")

    private fun isBareFootball(text: String?): Boolean = text?.trim()?.lowercase() == "football"

    private fun saysBareFootball(item: SportsLiveItem, card: SportsLiveItem): Boolean =
        listOf(item, card).any { source -> isBareFootball(source.sport) || source.meta.genres.any(::isBareFootball) }

    /** Some text of [item] or [card], the word "football" aside, names soccer ("Soccer", "Premier League", "MLS"). */
    private fun saysSoccer(item: SportsLiveItem, card: SportsLiveItem): Boolean = listOf(item, card).any { source ->
        (listOf(source.sport, source.meta.name, source.meta.description, source.catalogName) + source.meta.genres)
            .any { text -> text != null && SportsLiveSportKind.of(text.replace(FOOTBALL_WORD, " ")) == SportsLiveSportKind.SOCCER }
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
        if (timed.isEmpty()) return false
        val limit = cardMaxLiveMs(item)
        return timed.all { source -> nowMs - source.startMs!! > limit }
    }

    /**
     * L6: the longest [card] plausibly stays live: the longest of its sources' limits ([maxLiveMsOf]), so a merged card
     * whose sport only an alternate names (a golf catalog's copy) keeps that sport's run; unsure means longer.
     */
    fun cardMaxLiveMs(card: SportsLiveItem): Long = (listOf(card) + card.alternates).maxOf { maxLiveMsOf(it, card) }
}
