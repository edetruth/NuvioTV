package com.nuvio.tv.core.sportslive.scores

/**
 * Where a rank shows (#137, #160). College: the AP / coaches rank of a ranked team (1..25), a small muted "#3"
 * before its name on rows, the stage and the Game page. Tennis: the player's ranking, on the stage and the Game
 * page only (rows stay uncrowded). Pro leagues: nothing. Ranks are spoiler-neutral (they come from the poll, not
 * the game).
 */
object RankDisplay {
    /** The poll's depth: a college rank past it is not shown. */
    const val COLLEGE_TOP = 25

    private val COLLEGE = setOf(
        EspnLeagueMap.COLLEGE_FOOTBALL,
        EspnLeagueMap.COLLEGE_BASKETBALL,
        EspnLeagueMap.COLLEGE_BASKETBALL_WOMEN
    )

    fun isCollege(league: EspnLeague): Boolean = league in COLLEGE

    /** [rank] as [league] shows it anywhere, or null: a ranked college team (1..25), a ranked tennis player. */
    fun rank(league: EspnLeague, rank: Int?): Int? = when {
        rank == null || rank <= 0 -> null
        isCollege(league) -> rank.takeIf { it <= COLLEGE_TOP }
        league.sport == TENNIS -> rank
        else -> null
    }

    /** Rows show [league]'s ranks: college only. */
    fun onRows(league: EspnLeague): Boolean = isCollege(league)

    /** "#3". */
    fun label(rank: Int): String = "#$rank"

    private const val TENNIS = "tennis"
}
