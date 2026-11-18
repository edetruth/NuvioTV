package com.nuvio.tv.core.sportslive.scores

/**
 * An individual sport's result on the Results view (#55 part 2): a tennis match, a UFC fight, an F1
 * race podium or a golf leaderboard. Its [ScoreSnapshot] has no sides; this carries what the row shows.
 */
sealed interface IndividualResult {
    /** The row's tag: the tennis round, the UFC weight class, the race or the golf tournament. */
    val title: String?

    /** The row's place in its section before start time: a UFC card lists its main event first. */
    val listOrder: Int get() = 0
}

/** A tennis set as one player won games in it; [tiebreak] is that player's tiebreak points, if any. */
data class TennisSet(val games: Int, val tiebreak: Int? = null)

data class TennisPlayer(val name: String, val winner: Boolean, val sets: List<TennisSet>)

enum class TennisEnding { COMPLETED, RETIRED, WALKOVER }

/** A tennis match: [players] in ESPN's listing order (never the result's), each with their sets. */
data class TennisMatch(
    override val title: String?,
    val players: List<TennisPlayer>,
    val ending: TennisEnding
) : IndividualResult {
    val winner: TennisPlayer? get() = players.singleOrNull { it.winner }

    /** The set count both players have. */
    val setCount: Int get() = players.minOfOrNull { it.sets.size } ?: 0
}

data class Fighter(val name: String, val winner: Boolean)

enum class FightMethod { KO_TKO, SUBMISSION, DECISION }

/**
 * A UFC fight: [fighters] in ESPN's order. [method] is null when ESPN names none; [round] and [clock]
 * are where it ended. [bout] counts from the main event (0).
 */
data class Fight(
    override val title: String?,
    val fighters: List<Fighter>,
    val method: FightMethod?,
    val round: Int?,
    val clock: String?,
    val bout: Int
) : IndividualResult {
    val winner: Fighter? get() = fighters.singleOrNull { it.winner }
    override val listOrder: Int get() = bout

    /** A stoppage names its round and time ("R2 3:14"); a decision goes the distance, so it does not. */
    val showsRound: Boolean get() = round != null && method != null && method != FightMethod.DECISION
}

data class RaceFinisher(val position: Int, val name: String, val shortName: String?)

/** A race's top three ([podium], by finishing position). */
data class RaceResult(override val title: String?, val podium: List<RaceFinisher>) : IndividualResult

/** A golf leaderboard place: [position] with [tied] when another player shares it; [toPar] as ESPN writes it. */
data class GolfLeader(val position: Int, val tied: Boolean, val name: String, val toPar: String)

/** A finished golf tournament's top of the leaderboard: the winner first, then the rest of the top five. */
data class GolfResult(override val title: String?, val leaders: List<GolfLeader>) : IndividualResult {
    val winner: GolfLeader? get() = leaders.firstOrNull()?.takeIf { it.position == 1 }
}

/** The pure text rules of individual results: set scores, podiums, golf places. No words: only numbers and names. */
object IndividualFormat {
    private const val DASH = '–'

    /**
     * The match from the winner's side: "6–4 3–6 7–6(5)", the tiebreak's losing points in brackets; a
     * match tiebreak (a deciding "1–0" set) as its points "[10–4]". No winner: in listing order. A
     * walkover has no sets: empty.
     */
    fun setScore(match: TennisMatch): String {
        val first = match.winner ?: match.players.firstOrNull() ?: return ""
        val second = match.players.firstOrNull { it !== first } ?: return ""
        return (0 until match.setCount).joinToString(" ") { index ->
            val mine = first.sets[index]
            val theirs = second.sets[index]
            when {
                isMatchTiebreak(mine, theirs) -> "[${mine.tiebreak}$DASH${theirs.tiebreak}]"
                else -> {
                    val loserPoints = if (mine.games > theirs.games) theirs.tiebreak else mine.tiebreak
                    val tiebreak = if (loserPoints != null && mine.games != theirs.games) "($loserPoints)" else ""
                    "${mine.games}$DASH${theirs.games}$tiebreak"
                }
            }
        }
    }

    /** A deciding set played as a match tiebreak: "1–0" with tiebreak points of 10 or more. */
    fun isMatchTiebreak(a: TennisSet, b: TennisSet): Boolean =
        a.tiebreak != null && b.tiebreak != null && a.games + b.games == 1 && maxOf(a.tiebreak, b.tiebreak) >= 10

    /** "1. Norris · 2. Verstappen · 3. Hadjar": each by surname. */
    fun podium(race: RaceResult): String = race.podium.joinToString(" · ") { "${it.position}. ${surname(it)}" }

    /** "L. Norris" → "Norris"; else the full name. */
    fun surname(finisher: RaceFinisher): String =
        finisher.shortName?.substringAfter(". ", missingDelimiterValue = "")?.takeIf { it.isNotBlank() } ?: finisher.name

    /** "1", "2", "T4". */
    fun position(leader: GolfLeader): String = if (leader.tied) "T${leader.position}" else leader.position.toString()

    /** ESPN's "-20" as "−20" (a real minus); "E" and "+3" as they are. */
    fun toPar(leader: GolfLeader): String = leader.toPar.replace('-', '−')
}
