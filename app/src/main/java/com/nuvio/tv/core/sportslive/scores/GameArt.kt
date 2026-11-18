package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.SportsLivePillTime
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import java.time.ZoneId
import java.util.Locale

/**
 * One side of a drawn game card: [name] for large cards, [short] (an abbreviation when known) for small
 * ones, and [shortName] (ESPN's short name, "Auburn" for "Auburn Tigers") between the two, for rows.
 */
data class GameArtTeam(val name: String, val short: String, val logo: String?, val shortName: String = name)

/**
 * The card Nuvio draws for a game in place of the addon's poster (whose painted-in "13:00 UTC" never
 * matches the viewer's zone): both teams in the title's order, the league and the kickoff, shown in
 * local time. Logos, ESPN names and abbreviations come from the matched score event; an unmatched game
 * keeps the card's own names and start, with the logos (and abbreviations) its sides matched before
 * ([TeamLogoBook], #66), if both are known, else none.
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
         * used only when the card's sides sit on it one way round ([ScoreMatcher.firstIsHome]). Without
         * it, [known] gives the logos, so they never wait for the score feed.
         */
        fun of(item: SportsLiveItem, snapshot: ScoreSnapshot?, known: TeamLogoBook = TeamLogoBook.shared): GameArt? {
            if (item.isChannel || item.kind != SportsLiveKind.EVENTS) return null
            val (first, second) = ScoreChips.orderedSides(item) ?: return null
            val firstIsHome = snapshot?.let { ScoreMatcher.firstIsHome(first, second, it) }
            val event = snapshot?.takeIf { firstIsHome != null }
            val firstTeam = event?.let { if (firstIsHome == true) it.home else it.away }
            val secondTeam = event?.let { if (firstIsHome == true) it.away else it.home }
            val remembered = if (event == null) known.forCard(item, first, second) else null
            // ESPN's own time beats an addon's, unless ESPN has none yet (TBD).
            val eventStart = event?.takeIf { it.timeValid }?.startMs
            return GameArt(
                first = team(first, firstTeam, remembered?.first),
                second = team(second, secondTeam, remembered?.second),
                league = event?.leagueName?.takeIf { it.isNotBlank() } ?: item.sport?.takeIf { it.isNotBlank() },
                startMs = eventStart ?: item.startMs,
                hasTimeOfDay = if (eventStart != null) true else item.startHasTimeOfDay
            )
        }

        /**
         * The drawn card for an ESPN [event] alone (the Catch up row, #58): away first, as Results lists
         * them, ESPN's names, abbreviations and logos, its league; no time and no score.
         */
        fun ofEvent(event: ScoreSnapshot): GameArt = GameArt(
            first = espnTeam(event.away),
            second = espnTeam(event.home),
            league = event.leagueName?.takeIf { it.isNotBlank() },
            startMs = null,
            hasTimeOfDay = false
        )

        private fun espnTeam(team: ScoreTeam): GameArtTeam {
            val name = listOf(team.displayName, team.shortDisplayName, team.name, team.abbreviation)
                .firstOrNull { !it.isNullOrBlank() }.orEmpty()
            return GameArtTeam(
                name = name,
                short = team.abbreviation?.takeIf { it.isNotBlank() } ?: team.shortDisplayName?.takeIf { it.isNotBlank() } ?: name,
                logo = team.logo?.takeIf { it.isNotBlank() },
                shortName = team.shortDisplayName?.takeIf { it.isNotBlank() } ?: name
            )
        }

        /** The kickoff in the viewer's [zone] (time only today, "Tomorrow", weekday, else date), or null. */
        fun kickoff(art: GameArt, nowMs: Long, zone: ZoneId, locale: Locale): SportsLivePillTime? =
            art.startMs?.let { SportsLivePillTime.of(it, art.hasTimeOfDay, nowMs, zone, locale) }

        private fun team(cardName: String, espn: ScoreTeam?, known: KnownTeam?): GameArtTeam {
            val own = displayName(cardName)
            val name = espn?.displayName?.takeIf { it.isNotBlank() } ?: own
            val short = espn?.abbreviation?.takeIf { it.isNotBlank() } ?: espn?.shortDisplayName?.takeIf { it.isNotBlank() }
                ?: known?.abbreviation ?: own
            return GameArtTeam(
                name = name,
                short = short,
                logo = espn?.logo?.takeIf { it.isNotBlank() } ?: known?.logo,
                shortName = espn?.shortDisplayName?.takeIf { it.isNotBlank() } ?: name
            )
        }

        // "NBA: Boston Celtics" → "Boston Celtics"; a league or channel tag before the first team is not its name.
        private val LEADING_TAG = Regex("""^.*?(?::|\|)\s+""")

        private fun displayName(side: String): String = side.replace(LEADING_TAG, "").trim().ifEmpty { side.trim() }
    }
}
