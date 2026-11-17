package com.nuvio.tv.core.sportslive.scores

/** An ESPN scoreboard feed: `{sport}/{league}`, e.g. `basketball/nba`, `soccer/eng.1`. */
data class EspnLeague(val sport: String, val league: String) {
    val path: String get() = "$sport/$league"
}

/**
 * ESPN `status.type.state`: pre (scheduled), in (in progress), post (finished). [OFF] is a game
 * postponed, cancelled, suspended or delayed (from `status.type.name`): it never shows a score.
 */
enum class ScoreState { PRE, IN, POST, OFF, UNKNOWN }

enum class ScoreSide { HOME, AWAY }

/** One side of a game, as the score feed names it. Every name may be missing. */
data class ScoreTeam(
    val id: String?,
    val abbreviation: String?,
    val displayName: String?,
    val shortDisplayName: String?,
    val name: String?,
    val location: String?,
    val logo: String?,
    val score: Int?,
    /** Points per period (quarters, halves, periods, innings); empty when the feed has none. */
    val linescores: List<Int>
)

/** A soccer goal from the scoreboard's `details`. */
data class ScoreScorer(
    val side: ScoreSide?,
    val name: String,
    val clock: String?,
    val penalty: Boolean,
    val ownGoal: Boolean
)

/** One game's score at the time it was fetched. */
data class ScoreSnapshot(
    val eventId: String,
    /** The feed it came from (`eng.1`, `nba`, `all`). */
    val league: EspnLeague,
    /**
     * The event's own league slug: from its ESPN links when they name one (`soccer/all` mixes
     * `caf.nations_qual`, `fifa.friendly_u21`, `eng.w.1`...), else the feed's [EspnLeague.league].
     */
    val leagueSlug: String = league.league,
    /** The league's short name from the feed ("NBA", "Premier League"); null when it gave none (soccer/all). */
    val leagueName: String? = null,
    /** Scheduled start in epoch millis (UTC); null when the feed's date was missing or unreadable. */
    val startMs: Long?,
    val state: ScoreState,
    /** ESPN `status.type.shortDetail`: "Q3 4:12", "67'", "Final/OT", "FT". */
    val clockText: String?,
    val isFinal: Boolean,
    val home: ScoreTeam,
    val away: ScoreTeam,
    val scorers: List<ScoreScorer> = emptyList(),
    /** For an [ScoreState.OFF] game: true when it is suspended or delayed and may resume. */
    val mayResume: Boolean = false
) {
    val homeAbbr: String? get() = home.abbreviation
    val awayAbbr: String? get() = away.abbreviation
    val homeName: String? get() = home.displayName
    val awayName: String? get() = away.displayName
    val homeScore: Int? get() = home.score
    val awayScore: Int? get() = away.score
    val homeLogo: String? get() = home.logo
    val awayLogo: String? get() = away.logo
}

/**
 * A scoreboard fetch: [failed] is true on any HTTP or parse failure, with no events. [retryAfterMs] is
 * the server's Retry-After on a failure, when it sent one.
 */
data class ScoreboardResult(
    val events: List<ScoreSnapshot>,
    val failed: Boolean = false,
    val retryAfterMs: Long? = null
) {
    companion object {
        val FAILED = ScoreboardResult(emptyList(), failed = true)
    }
}
