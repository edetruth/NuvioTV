package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.SportsLiveDisplayText
import com.nuvio.tv.core.sportslive.SportsLivePillTime
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.identify.SportsLiveTeamIdentity
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import java.time.ZoneId
import java.util.Locale

/**
 * One side of a drawn game card: [name] for large cards, [short] (an abbreviation when known) for small
 * ones, and [shortName] (ESPN's short name, "Auburn" for "Auburn Tigers") between the two, for rows.
 */
data class GameArtTeam(
    val name: String,
    val short: String,
    val logo: String?,
    val shortName: String = name,
    /** ESPN's season record ("18-9") when the game is matched; null otherwise. */
    val record: String? = null,
    /** The rank to show before the name ([RankDisplay]: a ranked college team, a tennis player); null for none. */
    val rank: Int? = null
)

/**
 * The card Nuvio draws for a game in place of the addon's poster (whose painted-in "13:00 UTC" never
 * matches the viewer's zone): both teams in the title's order, the league and the kickoff, shown in
 * local time. Logos, ESPN names and abbreviations come from the matched score event; an unmatched game
 * keeps the card's own names and start, with the logos (and abbreviations) its sides matched before
 * ([TeamLogoBook], #66) or that its league's team list resolves to exactly one team each ([TeamDirectory],
 * #82), if both are known, else none.
 */
data class GameArt(
    val first: GameArtTeam,
    val second: GameArtTeam,
    val league: String?,
    val startMs: Long?,
    val hasTimeOfDay: Boolean,
    /** ESPN's season type of the matched game (1 preseason, 2 regular season, 3 postseason); null otherwise. */
    val seasonType: Int? = null,
    /** Rows show the teams' ranks too (#137: college only); the stage and Game page always do. */
    val ranksOnRows: Boolean = false
) {
    /** Logos are drawn only when both teams have one (#130); otherwise the names are set in large type. */
    val hasLogos: Boolean get() = TeamMarks.pair(first.logo, second.logo) != null

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
            // Else the badges the teams' lookup found (#168, item 3: a game no ESPN list names).
            val remembered = if (event == null) known.forCard(item, first, second) ?: identityLogos(first, second) else null
            // ESPN's own time beats an addon's, unless ESPN has none yet (TBD).
            val eventStart = event?.takeIf { it.timeValid }?.startMs
            // A game saved for later (#151) shows no records: they move with the result.
            val records = event?.spoilerProtected != true
            return GameArt(
                first = team(first, firstTeam, remembered?.first, event?.league, records),
                second = team(second, secondTeam, remembered?.second, event?.league, records),
                league = event?.leagueName?.takeIf { it.isNotBlank() } ?: sportLabelOf(item),
                startMs = eventStart ?: item.startMs,
                hasTimeOfDay = if (eventStart != null) true else item.startHasTimeOfDay,
                seasonType = event?.seasonType,
                ranksOnRows = event != null && RankDisplay.onRows(event.league)
            )
        }

        private fun identityLogos(first: String, second: String): Pair<KnownTeam, KnownTeam>? =
            SportsLiveTeamIdentity.logosOf(first, second)?.let { (a, b) -> KnownTeam(a, null) to KnownTeam(b, null) }

        /**
         * A replay's drawn card (#90, P4): the two sides its spoiler-free [title] names (never the addon's poster,
         * which may carry the score), each with its own name and the logos its league's directory or earlier
         * matches know; the replay's date. Null when the title names no two sides.
         */
        fun ofReplay(item: SportsLiveItem, title: String, known: TeamLogoBook = TeamLogoBook.shared): GameArt? {
            if (item.isChannel) return null
            val (writtenFirst, writtenSecond) = SportsLiveTeams.writtenOrder(title, withCompetition = true) ?: return null
            // "Real Madrid Euroleague", "Espanyol Spain La Liga Football" (#210): the competition comes off the team's
            // name and becomes the league.
            val first = SportsLiveDisplayText.teamSide(writtenFirst)
            val second = SportsLiveDisplayText.teamSide(writtenSecond)
            val competition = SportsLiveDisplayText.competitionSuffix(writtenSecond)?.second
                ?: SportsLiveDisplayText.competitionSuffix(writtenFirst)?.second
            val remembered = known.forCard(item, first, second)
            return GameArt(
                first = team(first, null, remembered?.first),
                second = team(second, null, remembered?.second),
                league = competition ?: sportLabelOf(item),
                startMs = item.startMs,
                hasTimeOfDay = item.startHasTimeOfDay
            )
        }

        /**
         * The drawn card for an ESPN [event] alone (the Catch up row, #58): away first, as Results lists
         * them, ESPN's names, abbreviations and logos, its league; no time and no score. [records] false leaves the
         * teams' records out (they move with the result): a screen whose score is hidden passes it (#151).
         */
        fun ofEvent(event: ScoreSnapshot, records: Boolean = !event.spoilerProtected): GameArt = GameArt(
            first = espnTeam(event.away, event.league, records = records && !event.spoilerProtected),
            second = espnTeam(event.home, event.league, records = records && !event.spoilerProtected),
            league = event.leagueName?.takeIf { it.isNotBlank() },
            startMs = null,
            hasTimeOfDay = false,
            ranksOnRows = RankDisplay.onRows(event.league)
        )

        private fun espnTeam(team: ScoreTeam, league: EspnLeague, records: Boolean): GameArtTeam {
            val name = listOf(team.displayName, team.shortDisplayName, team.name, team.abbreviation)
                .firstOrNull { !it.isNullOrBlank() }.orEmpty()
            return GameArtTeam(
                name = name,
                short = team.abbreviation?.takeIf { it.isNotBlank() } ?: team.shortDisplayName?.takeIf { it.isNotBlank() } ?: name,
                logo = team.logo?.takeIf { it.isNotBlank() },
                shortName = team.shortDisplayName?.takeIf { it.isNotBlank() } ?: name,
                record = team.record?.takeIf { records },
                rank = RankDisplay.rank(league, team.rank)
            )
        }

        /** The kickoff in the viewer's [zone] (time only today, "Tomorrow", weekday, else date), or null. */
        fun kickoff(art: GameArt, nowMs: Long, zone: ZoneId, locale: Locale): SportsLivePillTime? =
            art.startMs?.let { SportsLivePillTime.of(it, art.hasTimeOfDay, nowMs, zone, locale) }

        private fun team(cardName: String, espn: ScoreTeam?, known: KnownTeam?, league: EspnLeague? = null, records: Boolean = true): GameArtTeam {
            val own = displayName(cardName)
            val name = espn?.displayName?.takeIf { it.isNotBlank() } ?: own
            val short = espn?.abbreviation?.takeIf { it.isNotBlank() } ?: espn?.shortDisplayName?.takeIf { it.isNotBlank() }
                ?: known?.abbreviation ?: own
            return GameArtTeam(
                name = name,
                short = short,
                logo = espn?.logo?.takeIf { it.isNotBlank() } ?: known?.logo,
                shortName = espn?.shortDisplayName?.takeIf { it.isNotBlank() } ?: name,
                record = espn?.record?.takeIf { records },
                rank = league?.let { RankDisplay.rank(it, espn?.rank) }
            )
        }

        // "NBA: Boston Celtics" → "Boston Celtics"; a league or channel tag before the first team is not its name.
        private val LEADING_TAG = Regex("""^.*?(?::|\|)\s+""")

        private fun displayName(side: String): String = side.replace(LEADING_TAG, "").trim().ifEmpty { side.trim() }
    }
}

/** [item]'s sport as a league label; none for a non-sport tag ("OTHER", #145), which says nothing about the game. */
private fun sportLabelOf(item: SportsLiveItem): String? =
    item.sport?.takeIf { it.isNotBlank() && !SportsLiveTeams.isNonSportTag(it) }
