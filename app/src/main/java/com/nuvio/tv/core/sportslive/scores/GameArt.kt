package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.SportsLivePillTime
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import java.time.ZoneId
import java.util.Locale

/** One side of a drawn game card: [name] for large cards, [short] (an abbreviation when known) for small ones. */
data class GameArtTeam(val name: String, val short: String, val logo: String?)

/**
 * The card Nuvio draws for a game in place of the addon's poster (whose painted-in "13:00 UTC" never
 * matches the viewer's zone): both teams in the title's order, the league and the kickoff, shown in
 * local time. Logos, ESPN names and abbreviations come from the matched score event; an unmatched game
 * keeps the card's own names and start, with no logos.
 */
data class GameArt(
    val first: GameArtTeam,
    val second: GameArtTeam,
    val league: String?,
    val startMs: Long?,
    val hasTimeOfDay: Boolean
) {
    /** Logos are drawn only when both teams have one; otherwise the names are set in large type. */
    val hasLogos: Boolean get() = first.logo != null && second.logo != null

    companion object {
        /**
         * The drawn card for [item], or null when it is not a game (channels, replays and items that
         * name no two sides keep the addon's art). [snapshot] is its matched score event, if any; it is
         * used only when the card's sides sit on it one way round ([ScoreMatcher.firstIsHome]).
         */
        fun of(item: SportsLiveItem, snapshot: ScoreSnapshot?): GameArt? {
            if (item.isChannel || item.kind != SportsLiveKind.EVENTS) return null
            val (first, second) = ScoreChips.orderedSides(item) ?: return null
            val firstIsHome = snapshot?.let { ScoreMatcher.firstIsHome(first, second, it) }
            val event = snapshot?.takeIf { firstIsHome != null }
            val firstTeam = event?.let { if (firstIsHome == true) it.home else it.away }
            val secondTeam = event?.let { if (firstIsHome == true) it.away else it.home }
            // ESPN's own time beats an addon's, unless ESPN has none yet (TBD).
            val eventStart = event?.takeIf { it.timeValid }?.startMs
            return GameArt(
                first = team(first, firstTeam),
                second = team(second, secondTeam),
                league = event?.leagueName?.takeIf { it.isNotBlank() } ?: item.sport?.takeIf { it.isNotBlank() },
                startMs = eventStart ?: item.startMs,
                hasTimeOfDay = if (eventStart != null) true else item.startHasTimeOfDay
            )
        }

        /** The kickoff in the viewer's [zone] (time only today, "Tomorrow", weekday, else date), or null. */
        fun kickoff(art: GameArt, nowMs: Long, zone: ZoneId, locale: Locale): SportsLivePillTime? =
            art.startMs?.let { SportsLivePillTime.of(it, art.hasTimeOfDay, nowMs, zone, locale) }

        private fun team(cardName: String, espn: ScoreTeam?): GameArtTeam {
            val own = displayName(cardName)
            val name = espn?.displayName?.takeIf { it.isNotBlank() } ?: own
            val short = espn?.abbreviation?.takeIf { it.isNotBlank() } ?: espn?.shortDisplayName?.takeIf { it.isNotBlank() } ?: own
            return GameArtTeam(name = name, short = short, logo = espn?.logo?.takeIf { it.isNotBlank() })
        }

        // "NBA: Boston Celtics" → "Boston Celtics"; a league or channel tag before the first team is not its name.
        private val LEADING_TAG = Regex("""^.*?(?::|\|)\s+""")

        private fun displayName(side: String): String = side.replace(LEADING_TAG, "").trim().ifEmpty { side.trim() }
    }
}
