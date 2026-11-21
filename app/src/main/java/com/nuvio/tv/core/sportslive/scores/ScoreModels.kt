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
    val linescores: List<Int>,
    /** The season record ESPN's scoreboard gives ("18-9", soccer "2-1-7"); null when none. */
    val record: String? = null,
    /**
     * Other names a non-ESPN feed knows the team by (#169: EuroLeague's "Maccabi", "Maccabi Tel Aviv" for
     * "Maccabi Rapyd Tel Aviv"); matched as its own names are. Empty for ESPN teams.
     */
    val alternateNames: List<String> = emptyList(),
    /**
     * ESPN's `curatedRank` (#137): a college team's poll rank (1..25; ESPN's 99 "unranked" is null) or a tennis
     * player's ranking; null when none. [RankDisplay] decides where it shows.
     */
    val rank: Int? = null,
    /** Soccer: the last five results, oldest first ("DWWLW"); null when ESPN gives none. */
    val form: String? = null
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
    /**
     * The competition's name: the feed's short league name ("NBA", "NCAAM"), else, on `soccer/all`, the
     * event's competition ("UEFA Nations League", "Men's International Friendly"); null when neither is given.
     */
    val leagueName: String? = null,
    /** ESPN's league id from the event uid ("2395" for the UEFA Nations League); null when absent. */
    val competitionId: String? = null,
    /** Scheduled start in epoch millis (UTC); null when the feed's date was missing or unreadable. */
    val startMs: Long?,
    /** False when ESPN has no kick-off time yet ("TBD"): [startMs] then holds only the date. */
    val timeValid: Boolean = true,
    val state: ScoreState,
    /** ESPN `status.type.shortDetail`: "Q3 4:12", "67'", "Final/OT", "FT". */
    val clockText: String?,
    val isFinal: Boolean,
    val home: ScoreTeam,
    val away: ScoreTeam,
    val scorers: List<ScoreScorer> = emptyList(),
    /** For an [ScoreState.OFF] game: true when it is suspended or delayed and may resume. */
    val mayResume: Boolean = false,
    /** What is happening now in a live game (ESPN `situation`); null unless live and given. */
    val situation: GameSituation? = null,
    /**
     * An individual sport's result (tennis, UFC, F1, golf: #55 part 2); null for a team game. Such an
     * event has no sides: [home] and [away] are empty, and it never matches a card, team or replay.
     */
    val individual: IndividualResult? = null,
    /** ESPN's season type: 1 preseason, 2 regular season, 3 postseason; null when not given. */
    val seasonType: Int? = null,
    /** The TV channels ESPN lists (#137): "ESPN", "TNT", "ESPN+"; national ones count toward a big game. */
    val broadcasts: List<String> = emptyList(),
    /** ESPN's note naming the event (#137): "Players Era 8 - Championship"; null when none. */
    val headline: String? = null,
    /** The betting spread where ESPN lists one (#137); null when none. */
    val spread: Double? = null,
    /** Spoiler-protected (#151, [SpoilerProtection]): every surface hides the score, stats and alerts. */
    val spoilerProtected: Boolean = false,
    /** Being watched now (live.20 Q18, [ScoreWatch.masked]): served with no score and no clock; the player says LIVE. */
    val watched: Boolean = false,
    /**
     * Read back from the scores last shown before the app closed (live.23, [KeptScores]), drawn until the card's feed
     * answers: not news, so no alert treats it as a score seen before.
     */
    val kept: Boolean = false
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
 * A live game's situation from ESPN's scoreboard, sport by sport; every part may be missing.
 * Football: [possession], [downDistance] ("2nd & 6"), [yardLine] ("LV 34"), [redZone].
 * Baseball: [balls], [strikes], [outs], runners, [batter], [pitcher].
 * Any sport: [lastPlay], already cut short. ESPN gives none for soccer and nothing usable for hockey.
 */
data class GameSituation(
    val possession: ScoreSide? = null,
    val downDistance: String? = null,
    val yardLine: String? = null,
    val redZone: Boolean = false,
    val balls: Int? = null,
    val strikes: Int? = null,
    val outs: Int? = null,
    val onFirst: Boolean = false,
    val onSecond: Boolean = false,
    val onThird: Boolean = false,
    val batter: String? = null,
    val pitcher: String? = null,
    val lastPlay: String? = null
)

/**
 * A scoreboard fetch: [failed] is true on any HTTP or parse failure, with no events. [retryAfterMs] is
 * the server's Retry-After on a failure, when it sent one.
 */
data class ScoreboardResult(
    val events: List<ScoreSnapshot>,
    val failed: Boolean = false,
    val retryAfterMs: Long? = null,
    /** For a stored copy ([ScoreProvider.recentScoreboard]): when it was received from ESPN. */
    val receivedAtMs: Long? = null
) {
    companion object {
        val FAILED = ScoreboardResult(emptyList(), failed = true)
    }
}
