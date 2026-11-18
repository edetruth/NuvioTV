package com.nuvio.tv.core.sportslive.alerts

import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreMatcher
import com.nuvio.tv.core.sportslive.scores.ScoreSide
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveItem

/** One side of an alert, in the card's order: name, abbreviation, logo and (when shown) score. */
data class AlertSide(val name: String, val short: String, val logo: String?, val score: Int? = null)

/** What an alert says, as words to format: no strings here, so it is testable. */
sealed interface AlertContent {
    val first: AlertSide
    val second: AlertSide

    /** "Watch now?": both sides with logos, the league, whether a followed team plays. */
    data class Kickoff(override val first: AlertSide, override val second: AlertSide, val league: String?, val followed: Boolean) : AlertContent

    /** "‹Team› score" with the score in the card's order and the live clock. [scorer] null: both scored. */
    data class Score(override val first: AlertSide, override val second: AlertSide, val scorer: AlertSide?, val clock: String?) : AlertContent

    /** Full time with the score and ESPN's note ("Final/OT"). */
    data class FullTime(override val first: AlertSide, override val second: AlertSide, val note: String?, val league: String?) : AlertContent

    /** Full time under Hide until focused: "‹A› vs ‹B› · Full time", no score, no OT, no winner. */
    data class FullTimeNamesOnly(override val first: AlertSide, override val second: AlertSide) : AlertContent
}

object SportsLiveAlertContent {

    /** [alert] as words; null when the card names no two sides. */
    fun of(alert: SportsLiveAlert): AlertContent? = when (alert) {
        is SportsLiveAlert.Kickoff -> sides(alert.item, alert.snapshot, withScore = false)?.let { (first, second, art) ->
            AlertContent.Kickoff(first, second, art.league, alert.item.followed)
        }
        is SportsLiveAlert.ScoreChange -> sides(alert.item, alert.snapshot, withScore = true)?.let { (first, second, _) ->
            val firstIsHome = firstIsHome(alert.item, alert.snapshot)
            val scorer = alert.scorer?.takeIf { firstIsHome != null }?.let { side -> if (firstIsHome == (side == ScoreSide.HOME)) first else second }
            AlertContent.Score(first, second, scorer, alert.snapshot.clockText)
        }
        is SportsLiveAlert.FullTime -> if (alert.namesOnly) {
            sides(alert.item, alert.snapshot, withScore = false)?.let { (first, second, _) -> AlertContent.FullTimeNamesOnly(first, second) }
        } else {
            sides(alert.item, alert.snapshot, withScore = true)?.let { (first, second, art) ->
                AlertContent.FullTime(first, second, alert.snapshot.clockText, art.league)
            }
        }
    }

    private fun firstIsHome(item: SportsLiveItem, event: ScoreSnapshot): Boolean? {
        val (first, second) = ScoreChips.orderedSides(item) ?: return null
        return ScoreMatcher.firstIsHome(first, second, event)
    }

    private fun sides(item: SportsLiveItem, snapshot: ScoreSnapshot?, withScore: Boolean): Triple<AlertSide, AlertSide, GameArt>? {
        val art = GameArt.of(item, snapshot) ?: return null
        val firstIsHome = snapshot?.let { firstIsHome(item, it) }
        fun score(first: Boolean): Int? {
            if (!withScore || snapshot == null || firstIsHome == null) return null
            return if (first == firstIsHome) snapshot.homeScore else snapshot.awayScore
        }
        return Triple(
            AlertSide(art.first.name, art.first.short, art.first.logo, score(true)),
            AlertSide(art.second.name, art.second.short, art.second.logo, score(false)),
            art
        )
    }
}

/**
 * Which cards the alerts have scored (#42): followed teams' games, live or kicking off within
 * [LEAD_MS], in the leagues of the followed teams only, at most [MAX_CARDS]. Everything else is scored
 * only while a screen shows it (no new polling).
 */
object SportsLiveAlertScope {
    const val LEAD_MS = 15 * 60_000L

    /** A card started this long ago is not live any more (its load said so then). */
    const val LIVE_SPAN_MS = 5 * 60 * 60_000L
    const val MAX_CARDS = 8

    /**
     * The ESPN leagues of [teams]: a team followed from ESPN has its own; a team followed from a card,
     * those its sport word routes to ("Basketball" → NBA). A team with neither has none.
     */
    fun followedLeagues(teams: List<FollowedTeam>): Set<EspnLeague> = teams.flatMapTo(LinkedHashSet()) { team ->
        val stored = team.league?.split('/')?.takeIf { it.size == 2 && it.all(String::isNotBlank) }
        when {
            stored != null -> listOf(EspnLeague(stored[0], stored[1]))
            team.sport != null -> EspnLeagueMap.leaguesFor(sport = team.sport, hints = emptyList())
            else -> emptyList()
        }
    }

    /** True when [item] (or a source of it) routes to one of [leagues]; any soccer feed counts for a soccer team. */
    fun inLeagues(item: SportsLiveItem, leagues: Set<EspnLeague>): Boolean {
        if (leagues.isEmpty()) return false
        val soccer = leagues.any { it.sport == SOCCER }
        return (listOf(item) + item.alternates).flatMap(EspnLeagueMap::leaguesFor)
            .any { it in leagues || (soccer && it.sport == SOCCER) }
    }

    /** The cards to track for alerts, from [cards] (marked followed), soonest first. */
    fun cardsToTrack(cards: List<SportsLiveItem>, followed: List<FollowedTeam>, nowMs: Long): List<SportsLiveItem> {
        val leagues = followedLeagues(followed)
        if (leagues.isEmpty()) return emptyList()
        return cards.asSequence()
            .filter { it.followed && !it.isChannel && isLiveOrKickingOff(it, nowMs) && inLeagues(it, leagues) }
            .distinctBy { it.streamKey }
            .sortedBy { it.startMs ?: Long.MIN_VALUE }
            .take(MAX_CARDS)
            .toList()
    }

    private fun isLiveOrKickingOff(item: SportsLiveItem, nowMs: Long): Boolean {
        val start = item.startMs
        if (start == null) return item.isLive
        return start <= nowMs + LEAD_MS && nowMs - start <= LIVE_SPAN_MS && (item.isLive || item.startHasTimeOfDay)
    }

    private const val SOCCER = "soccer"
}
