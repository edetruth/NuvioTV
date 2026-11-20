package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.Memo
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import kotlin.math.abs

/** Why a game is big (#137): each becomes a short piece of its reason line ("#1 vs #8 · ESPN · Players Era 8"). */
enum class BigGameReasonKind {
    /** Two ranked teams or players: "#1 vs #8" ([BigGameReason.a] ≤ [BigGameReason.b]). */
    RANKS,

    /** One ranked college team: "#3 UConn" ([BigGameReason.a], [BigGameReason.text]). */
    RANKED,

    /** Two top places of a table or conference: "1st vs 3rd". */
    PLACES,

    /** Soccer: two teams in the top-four race. */
    TOP_FOUR,

    /** Soccer: two teams near the bottom. */
    RELEGATION,

    /** A curated soccer derby. */
    DERBY,

    /** Two winning records: "11-4 vs 12-3" ([BigGameReason.text]). */
    RECORDS,

    /** A postseason game. */
    PLAYOFFS,

    /** Tennis: a quarterfinal, semifinal or final ([BigGameReason.text], ESPN's round name). */
    ROUND,

    /** A national TV channel ([BigGameReason.text]: "ESPN"). */
    NETWORK,

    /** ESPN's event note ([BigGameReason.text]: "Players Era 8 - Championship"). */
    EVENT
}

data class BigGameReason(val kind: BigGameReasonKind, val text: String? = null, val a: Int? = null, val b: Int? = null)

/** A game's stakes (#137): its [score] and the [reasons] its line shows, most telling first (at most three). */
data class BigGameRating(val score: Double, val reasons: List<BigGameReason>) {
    /** Big enough for the badge, Games to watch and the Live Now big-game slot. */
    val isBig: Boolean get() = score >= BigGame.BIG
}

/** A team's place in its standings group (#137): [position] 1-based out of [groupSize]. */
data class TeamStanding(val position: Int, val groupSize: Int, val group: String?)

/**
 * The big-game score (#137): one engine, per-sport inputs, all from data the app already has (the scoreboard's
 * ranks, records, channels and notes; standings cached for leagues in My sports). Pure, never a request.
 * - College: both teams' poll ranks, more the higher they are, a bonus when both are ranked.
 * - Pro: both winning records, both near the top of their group, the postseason.
 * - Soccer: table places (title clash, top-four race, relegation six-pointer), form, a curated derby.
 * - Tennis: both rankings and the round (quarterfinal, semifinal, final weigh more).
 * - Any: a national TV channel, a named event, a close spread, a followed team (a boost with no reason line).
 */
object BigGame {
    /** The badge's bar. */
    const val BIG = 3.0

    /** Reasons on a line, at most. */
    const val MAX_REASONS = 3

    fun rate(
        event: ScoreSnapshot,
        standings: Map<String, TeamStanding> = emptyMap(),
        followed: Boolean = false
    ): BigGameRating {
        val primary = ArrayList<BigGameReason>()
        var score = when {
            event.individual is TennisMatch -> tennis(event, event.individual, primary)
            event.individual != null -> 0.0
            RankDisplay.isCollege(event.league) -> college(event, primary)
            event.league.sport == SOCCER -> soccer(event, standings, primary)
            else -> pro(event, standings, primary)
        }
        val extra = ArrayList<BigGameReason>()
        nationalNetwork(event.broadcasts)?.let {
            score += NETWORK_POINTS
            extra += BigGameReason(BigGameReasonKind.NETWORK, it)
        }
        eventName(event.headline)?.let {
            score += EVENT_POINTS
            extra += BigGameReason(BigGameReasonKind.EVENT, it)
        }
        if (event.spread?.let { abs(it) <= CLOSE_SPREAD } == true) score += SPREAD_POINTS
        if (followed) score += FOLLOWED_POINTS
        return BigGameRating(score, (primary + extra).take(MAX_REASONS))
    }

    private fun collegePoints(rank: Int): Double = (RankDisplay.COLLEGE_TOP + 1 - rank).toDouble() / RankDisplay.COLLEGE_TOP * 3

    private fun college(event: ScoreSnapshot, reasons: MutableList<BigGameReason>): Double {
        val home = RankDisplay.rank(event.league, event.home.rank)
        val away = RankDisplay.rank(event.league, event.away.rank)
        return when {
            home != null && away != null -> {
                reasons += BigGameReason(BigGameReasonKind.RANKS, a = minOf(home, away), b = maxOf(home, away))
                collegePoints(home) + collegePoints(away) + 2 + if (home <= 10 && away <= 10) 1 else 0
            }
            home != null || away != null -> {
                val (rank, team) = if (home != null) home to event.home else away!! to event.away
                reasons += BigGameReason(BigGameReasonKind.RANKED, text = shortName(team), a = rank)
                collegePoints(rank) * 0.5
            }
            else -> 0.0
        }
    }

    private fun pro(event: ScoreSnapshot, standings: Map<String, TeamStanding>, reasons: MutableList<BigGameReason>): Double {
        var score = 0.0
        if (event.seasonType == POSTSEASON) {
            score += 3
            reasons += BigGameReason(BigGameReasonKind.PLAYOFFS)
        }
        val home = event.home.id?.let(standings::get)
        val away = event.away.id?.let(standings::get)
        if (home != null && away != null && home.position <= TOP_PLACES && away.position <= TOP_PLACES) {
            score += 2
            reasons += BigGameReason(BigGameReasonKind.PLACES, a = minOf(home.position, away.position), b = maxOf(home.position, away.position))
        }
        val homeRecord = record(event.home.record)
        val awayRecord = record(event.away.record)
        if (homeRecord != null && awayRecord != null && homeRecord.games >= MIN_GAMES && awayRecord.games >= MIN_GAMES &&
            homeRecord.pct >= WINNING && awayRecord.pct >= WINNING
        ) {
            score += 1.5 + ((homeRecord.pct + awayRecord.pct) / 2 - WINNING) * 5
            // The away team first, as the scoreboard reads.
            reasons += BigGameReason(BigGameReasonKind.RECORDS, text = "${event.away.record!!.trim()} vs ${event.home.record!!.trim()}")
        }
        return score
    }

    private fun soccer(event: ScoreSnapshot, standings: Map<String, TeamStanding>, reasons: MutableList<BigGameReason>): Double {
        var score = 0.0
        val home = event.home.id?.let(standings::get)
        val away = event.away.id?.let(standings::get)
        if (home != null && away != null) {
            val top = minOf(home.position, away.position)
            val other = maxOf(home.position, away.position)
            val size = maxOf(home.groupSize, away.groupSize)
            when {
                other <= 2 -> {
                    score += 3
                    reasons += BigGameReason(BigGameReasonKind.PLACES, a = top, b = other)
                }
                other <= 6 && top <= 4 -> {
                    score += 2
                    reasons += BigGameReason(BigGameReasonKind.TOP_FOUR)
                }
                size >= 10 && top > size - RELEGATION_ZONE -> {
                    score += 2
                    reasons += BigGameReason(BigGameReasonKind.RELEGATION)
                }
            }
        }
        if (isDerby(event)) {
            score += 3
            reasons.add(0, BigGameReason(BigGameReasonKind.DERBY))
        }
        // Form: both sides winning lately adds a little.
        val wins = listOfNotNull(event.home.form, event.away.form).takeIf { it.size == 2 }?.sumOf { form -> form.count { it == 'W' || it == 'w' } }
        if (wins != null) score += wins / 10.0
        return score
    }

    private fun tennis(event: ScoreSnapshot, match: TennisMatch, reasons: MutableList<BigGameReason>): Double {
        if (match.players.size != 2) return 0.0
        var score = 0.0
        val first = match.players[0].rank
        val second = match.players[1].rank
        fun points(rank: Int?): Double = rank?.let { (TENNIS_TOP + 1 - it).coerceAtLeast(0).toDouble() / TENNIS_TOP * 2.5 } ?: 0.0
        score += points(first) + points(second)
        if (first != null && second != null) {
            if (first <= TENNIS_TOP && second <= TENNIS_TOP) score += 1
            reasons += BigGameReason(BigGameReasonKind.RANKS, a = minOf(first, second), b = maxOf(first, second))
        }
        val round = match.title?.trim().orEmpty()
        val roundPoints = roundPoints(round)
        if (roundPoints > 0) {
            score += roundPoints
            reasons += BigGameReason(BigGameReasonKind.ROUND, text = round)
        }
        return score
    }

    /** A tennis round's weight: final 3, semifinal 2, quarterfinal 1, any other 0. */
    internal fun roundPoints(round: String): Int {
        val folded = round.lowercase().replace("-", "").replace(" ", "")
        return when {
            folded.startsWith("quarterfinal") -> 1
            folded.startsWith("semifinal") -> 2
            folded == "final" || folded == "finals" || folded == "championship" -> 3
            else -> 0
        }
    }

    /** Wins and games of a record ("11-4", "7-0-3" W-D-L or W-L-T, "8-1-2" W-L-OTL); null when unreadable. */
    internal data class Record(val wins: Int, val games: Int) {
        val pct: Double get() = if (games == 0) 0.0 else wins.toDouble() / games
    }

    internal fun record(text: String?): Record? {
        val parts = text?.trim()?.split('-')?.map { it.trim().toIntOrNull() ?: return null } ?: return null
        if (parts.size !in 2..3) return null
        return Record(parts[0], parts.sum())
    }

    /** The first national channel ESPN lists ("ESPN", "TNT"); null when only regional or streaming ones. */
    internal fun nationalNetwork(broadcasts: List<String>): String? =
        broadcasts.firstOrNull { it.trim().lowercase() in NATIONAL }?.trim()

    /** ESPN's note as a reason: trimmed to [EVENT_MAX] at a word; null for none. */
    internal fun eventName(headline: String?): String? {
        val text = headline?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (text.length <= EVENT_MAX) return text
        val cut = text.substring(0, EVENT_MAX - 1).substringBeforeLast(' ').trimEnd(' ', '-', '·', ',')
        return "$cut…"
    }

    private fun shortName(team: ScoreTeam): String =
        listOf(team.shortDisplayName, team.abbreviation, team.displayName, team.name).firstOrNull { !it.isNullOrBlank() }.orEmpty()

    // A small curated list of derbies (#137), by canonical team names: each pair either way round.
    private val DERBIES: List<Pair<String, String>> = listOf(
        "arsenal" to "tottenham hotspur",
        "liverpool" to "everton",
        "manchester united" to "manchester city",
        "liverpool" to "manchester united",
        "chelsea" to "tottenham hotspur",
        "newcastle united" to "sunderland",
        "real madrid" to "barcelona",
        "atletico madrid" to "real madrid",
        "sevilla" to "real betis",
        "ac milan" to "internazionale",
        "roma" to "lazio",
        "juventus" to "torino",
        "juventus" to "internazionale",
        "bayern munich" to "borussia dortmund",
        "borussia dortmund" to "schalke 04",
        "paris saint germain" to "marseille",
        "ajax" to "feyenoord",
        "benfica" to "porto",
        "benfica" to "sporting cp",
        "celtic" to "rangers",
        "boca juniors" to "river plate",
        "la galaxy" to "lafc"
    ).map { (a, b) -> SportsLiveTeams.canonicalise(a) to SportsLiveTeams.canonicalise(b) }

    private val canonical = Memo(1_024) { name: String -> SportsLiveTeams.canonicalise(name) }

    internal fun isDerby(event: ScoreSnapshot): Boolean {
        val home = listOfNotNull(event.home.displayName, event.home.shortDisplayName, event.home.name).map(canonical::invoke).toSet()
        val away = listOfNotNull(event.away.displayName, event.away.shortDisplayName, event.away.name).map(canonical::invoke).toSet()
        if (home.isEmpty() || away.isEmpty()) return false
        return DERBIES.any { (a, b) -> (a in home && b in away) || (b in home && a in away) }
    }

    private val NATIONAL = setOf(
        "espn", "espn2", "espnu", "abc", "fox", "fs1", "cbs", "cbs sports network", "cbssn", "nbc", "nbcsn", "tnt", "tbs", "trutv",
        "nba tv", "nfl network", "nhl network", "mlb network", "peacock", "prime video", "amazon prime video", "netflix", "usa network",
        "tennis channel", "ion"
    )

    private const val SOCCER = "soccer"
    private const val POSTSEASON = 3
    private const val TOP_PLACES = 3
    private const val MIN_GAMES = 5
    private const val WINNING = 0.6
    private const val RELEGATION_ZONE = 5
    private const val TENNIS_TOP = 20
    private const val CLOSE_SPREAD = 3.0
    private const val NETWORK_POINTS = 1.0
    private const val EVENT_POINTS = 1.0
    private const val SPREAD_POINTS = 0.5
    private const val FOLLOWED_POINTS = 1.5
    private const val EVENT_MAX = 28
}
