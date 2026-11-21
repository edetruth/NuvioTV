package com.nuvio.tv.core.sportslive

import com.nuvio.tv.core.sportslive.identify.TeamIdentityRules
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind

/**
 * Live listings that are neither a two-sided game nor a known event (H, user report #6, live.23): one addon's live
 * events catalogs list "ATP 250 vs Stockholm Open" (a tournament as the opponent, in November), a bare "ATP", "WTA" or
 * "Tennis", and promotions, leagues or shows alone ("BKFC", "ISL", "NPL", "Raw", "Dynamite", "Friday Fights",
 * "Gromda"). Live now and the player's panel fold them into one quiet bottom row, "Other events (N) ›", which opens
 * them; they say LIVE only when ESPN confirms the event is on.
 *
 * Rules, on the row's clean title ([displayName]), for an events listing (never a channel, never a replay) that names
 * no two sides:
 * - a part of it is a tour designation alone ("ATP 250 · Stockholm Open": [TOUR]);
 * - once stream words are left out ([FILLER]), it is a plain sport word, a tour, a promotion, league or show from
 *   [PROMOTIONS], or one short word of at most [LONE_WORD_MAX] letters ("BKFC", "ISL", "Raw": a promotion's or
 *   league's initials; no event is that short).
 * A titled event ("MotoGP", "Formula 1 · Brazil", "UFC 310", "ATP Finals", "Wimbledon · Day 6") stays a row. A real
 * channel ("Tennis Channel", "Sky Sports Tennis") is a channel already and never comes here. Data-driven: add a
 * promotion to [PROMOTIONS] when the "Titles not placed" report shows one.
 */
object SportsLiveOtherEvents {

    private val otherTitle = Memo(4_096) { title: String -> otherTitleUncached(title) }

    /** True when [item] is folded under "Other events"; see the class doc. */
    fun isOtherEvent(item: SportsLiveItem): Boolean =
        !item.isChannel && item.kind == SportsLiveKind.EVENTS && item.teams == null && otherTitle(item.displayName)

    /** True when a teamless listing titled [title] (its clean title) is no event of its own. */
    internal fun isOtherTitle(title: String): Boolean = otherTitle(title)

    /**
     * [items] split into the games and the other events (each kept in its order), the other events with their
     * copies folded into one ([collapse]).
     */
    fun split(items: List<SportsLiveItem>): Pair<List<SportsLiveItem>, List<SportsLiveItem>> {
        val (others, games) = items.partition(::isOtherEvent)
        return games to collapse(others)
    }

    /**
     * Copies of one listing (the same clean title, case, accents and spacing aside) as one row: the first, carrying the
     * others and their sources as alternates. One addon lists the same entry in several catalogs (the TV photo's two
     * "ATP 250 · Stockholm Open" rows).
     */
    fun collapse(others: List<SportsLiveItem>): List<SportsLiveItem> {
        if (others.size < 2) return others
        val byKey = LinkedHashMap<String, MutableList<SportsLiveItem>>()
        for (item in others) byKey.getOrPut(keyOf(item)) { mutableListOf() } += item
        return byKey.values.map { copies ->
            val first = copies.first()
            if (copies.size == 1) {
                first
            } else {
                val seen = HashSet<String>().apply { add(first.streamKey); first.alternates.forEach { add(it.streamKey) } }
                val extra = copies.drop(1).flatMap { listOf(it) + it.alternates }.filter { seen.add(it.streamKey) }
                first.copy(alternates = first.alternates + extra.map { it.copy(alternates = emptyList()) })
            }
        }
    }

    private fun keyOf(item: SportsLiveItem): String = TeamIdentityRules.nameKey(item.displayName)

    private fun otherTitleUncached(title: String): Boolean {
        val parts = title.split(" · ").map { TeamIdentityRules.nameKey(it) }.filter { it.isNotEmpty() }
        // "ATP 250 · Stockholm Open": a tour written as one side of a game.
        if (parts.size >= 2 && parts.any { TOUR.matches(it) }) return true
        val words = TeamIdentityRules.nameKey(title).split(' ').filter { it.isNotEmpty() && it !in FILLER }
        if (words.isEmpty()) return false
        val core = words.joinToString(" ")
        if (TOUR.matches(core) || core in PROMOTIONS || SportsLiveSportKind.isPlainSportWord(core)) return true
        val word = words.singleOrNull() ?: return false
        return word.length in 2..LONE_WORD_MAX && word.all { it in 'a'..'z' }
    }

    /** A tour alone, with at most its tier: "ATP", "WTA 1000", "ATP Challenger", "ITF". */
    private val TOUR = Regex("""(atp|wta|itf)( tour| challenger)?( \d{2,4})?|challenger( \d{2,4})?""")

    /** The longest single word read as initials ("BKFC"); a five-letter title may be an event's name. */
    private const val LONE_WORD_MAX = 4

    /** Words a listing's title carries beside what it names: a stream's, a quality's or a status's. */
    private val FILLER = setOf(
        "live", "now", "hd", "fhd", "uhd", "sd", "4k", "stream", "streams", "feed", "feeds", "tv", "watch"
    )

    /** Promotions, leagues and weekly shows named alone (name keys); four letters or fewer need no entry. */
    private val PROMOTIONS = setOf(
        "gromda", "dynamite", "aew dynamite", "smackdown", "wwe smackdown", "collision", "aew collision", "rampage",
        "aew rampage", "wwe raw", "wwe nxt", "friday fights", "one friday fights", "one championship", "bellator",
        "oktagon", "cage warriors", "glory", "top rank", "italia t10", "indian super league", "national premier leagues"
    )
}
