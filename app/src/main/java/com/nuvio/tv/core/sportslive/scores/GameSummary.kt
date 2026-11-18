package com.nuvio.tv.core.sportslive.scores

/** One side of a game on the Game page, from the summary's header. */
data class GameTeam(
    val id: String?,
    val abbreviation: String?,
    val displayName: String?,
    val shortDisplayName: String?,
    val logo: String?,
    /** Null before the game is in play (ESPN's pre-game "0" is never shown). */
    val score: Int?,
    val linescores: List<Int>,
    /** The season record ("11-3", soccer "2-1-7"); null when ESPN gives none. */
    val record: String?
) {
    /** "BOS", else the short name, else the full name. */
    val short: String get() = abbreviation ?: shortDisplayName ?: displayName.orEmpty()

    /** "Boston Celtics", else the short name or abbreviation. */
    val fullName: String get() = displayName ?: shortDisplayName ?: abbreviation.orEmpty()
}

/** A team stat the Game page compares, with its ESPN name per sport. Labels come from string resources. */
enum class GameStat(val espnName: String, val lowerIsBetter: Boolean = false, val percent: Boolean = false, val time: Boolean = false) {
    FIELD_GOAL_PCT("fieldGoalPct", percent = true),
    THREE_POINT_PCT("threePointFieldGoalPct", percent = true),
    REBOUNDS("totalRebounds"),
    ASSISTS("assists"),
    TURNOVERS("turnovers", lowerIsBetter = true),
    TOTAL_YARDS("totalYards"),
    PASSING_YARDS("netPassingYards"),
    RUSHING_YARDS("rushingYards"),
    FIRST_DOWNS("firstDowns"),
    POSSESSION_TIME("possessionTime", time = true),
    SHOTS("shotsTotal"),
    FACEOFF_PCT("faceoffPercent", percent = true),
    POWER_PLAY_GOALS("powerPlayGoals"),
    HITS("hits"),
    BLOCKED_SHOTS("blockedShots"),
    PENALTY_MINUTES("penaltyMinutes", lowerIsBetter = true),
    POSSESSION("possessionPct", percent = true),
    SOCCER_SHOTS("totalShots"),
    SHOTS_ON_TARGET("shotsOnTarget"),
    CORNERS("wonCorners"),
    FOULS("foulsCommitted", lowerIsBetter = true);

    companion object {
        /** The stats compared for an ESPN sport, in display order; none for a sport without a set. */
        fun forSport(sport: String): List<GameStat> = when (sport) {
            "basketball" -> listOf(FIELD_GOAL_PCT, THREE_POINT_PCT, REBOUNDS, ASSISTS, TURNOVERS)
            "football" -> listOf(TOTAL_YARDS, PASSING_YARDS, RUSHING_YARDS, FIRST_DOWNS, TURNOVERS, POSSESSION_TIME)
            "hockey" -> listOf(SHOTS, FACEOFF_PCT, POWER_PLAY_GOALS, HITS, BLOCKED_SHOTS, PENALTY_MINUTES)
            "soccer" -> listOf(POSSESSION, SOCCER_SHOTS, SHOTS_ON_TARGET, CORNERS, FOULS)
            else -> emptyList()
        }
    }
}

/** One stat bar: each side's ESPN value as given, read as numbers for the bar and [better]. */
data class StatComparison(
    val stat: GameStat,
    val away: String,
    val home: String,
    val awayValue: Double?,
    val homeValue: Double?,
    /** The side with the better value; null when equal or unreadable. */
    val better: ScoreSide?
)

/** A piece of a performer's line: "24" + "PTS", or a whole football line with no [label]. */
data class PerformerStat(val value: String, val label: String?)

/** A top performer card: the team, the player and the categories they led; [athleteId] opens the Player card (#63). */
data class Performer(
    val side: ScoreSide,
    val team: String,
    val player: String,
    val stats: List<PerformerStat>,
    val athleteId: String? = null
)

data class BoxScoreRow(val player: String, val position: String?, val values: List<String>)

/** One player table of a team ([title]: "Passing", "Forwards"; null when the sport has one table). */
data class BoxScoreTable(val title: String?, val labels: List<String>, val rows: List<BoxScoreRow>, val totals: List<String>?)

data class BoxScoreTeam(val side: ScoreSide, val team: String, val tables: List<BoxScoreTable>)

data class StandingsRow(val team: String, val values: List<String>, val inGame: Boolean)

/** A standings table ("2026-27 Atlantic Division Standings") with ESPN's column abbreviations. */
data class StandingsTable(val title: String?, val columns: List<String>, val rows: List<StandingsRow>)

/**
 * An ESPN highlight clip (#61), played in the app player. [links] are its direct video links, best
 * first (HLS, then HD MP4, then MP4); never empty: a clip without one is not a highlight here.
 */
data class GameHighlight(
    val id: String,
    val title: String,
    val thumbnail: String?,
    val durationSeconds: Int?,
    val links: List<String>
)

enum class GameSection { GAME, BOX_SCORE, STANDINGS, HIGHLIGHTS }

/** One game as the Game page shows it, from ESPN's summary. Every list may be empty. */
data class GameSummary(
    val eventId: String,
    val league: EspnLeague,
    val state: ScoreState,
    val isFinal: Boolean,
    /** ESPN's shortDetail: "Final", "Final/OT", "FT", "Q3 4:12", "67'". */
    val clockText: String?,
    val startMs: Long?,
    val venue: String?,
    val away: GameTeam,
    val home: GameTeam,
    val scorers: List<ScoreScorer>,
    val teamStats: List<StatComparison>,
    val performers: List<Performer>,
    val boxScore: List<BoxScoreTeam>,
    val standings: List<StandingsTable>,
    val highlights: List<GameHighlight>
) {
    val isLive: Boolean get() = state == ScoreState.IN

    /** Both sides have periods: the linescore table shows. */
    val hasLinescore: Boolean get() = away.linescores.isNotEmpty() && home.linescores.isNotEmpty()

    /** The sections with something to show, in pill order; an empty one is hidden. */
    val sections: List<GameSection>
        get() = buildList {
            if (hasLinescore || performers.isNotEmpty() || teamStats.isNotEmpty()) add(GameSection.GAME)
            if (boxScore.isNotEmpty()) add(GameSection.BOX_SCORE)
            if (standings.isNotEmpty()) add(GameSection.STANDINGS)
            if (highlights.isNotEmpty()) add(GameSection.HIGHLIGHTS)
        }
}

/**
 * Reads ESPN's summary into a [GameSummary]. Tolerant: missing parts are empty lists, and a summary
 * without two named sides is null. Game stats (team stats, performers, box score) only for a game in
 * play or over: before kick-off ESPN puts season averages in the same fields.
 */
object GameSummaryParser {

    fun parse(dto: EspnSummaryDto, league: EspnLeague, eventId: String): GameSummary? {
        // Another game's summary is never shown as this one.
        dto.header?.id?.takeIf { it.isNotBlank() }?.let { if (it != eventId) return null }
        val competition = dto.header?.competitions?.firstOrNull() ?: return null
        val competitors = competition.competitors.orEmpty()
        val homeDto = competitors.firstOrNull { it.homeAway == "home" } ?: return null
        val awayDto = competitors.firstOrNull { it.homeAway == "away" } ?: return null
        val type = competition.status?.type
        val state = EspnScoreProvider.stateOf(type)
        val played = state == ScoreState.IN || state == ScoreState.POST
        val home = team(homeDto, played) ?: return null
        val away = team(awayDto, played) ?: return null
        val sport = league.sport
        return GameSummary(
            eventId = eventId,
            league = league,
            state = state,
            isFinal = state != ScoreState.OFF && type?.completed == true,
            clockText = type?.shortDetail?.takeIf { it.isNotBlank() },
            startMs = competition.date?.let(EspnScoreProvider::parseDate),
            venue = dto.gameInfo?.venue?.fullName?.takeIf { it.isNotBlank() },
            away = away,
            home = home,
            scorers = if (played && sport == SOCCER) scorers(competition.details.orEmpty(), home.id, away.id) else emptyList(),
            teamStats = if (played) teamStats(dto.boxscore?.teams.orEmpty(), sport, home.id, away.id) else emptyList(),
            performers = if (played) performers(dto.leaders.orEmpty(), home, away) else emptyList(),
            boxScore = if (played) boxScore(dto.boxscore?.players.orEmpty(), sport, home, away) else emptyList(),
            standings = standings(dto.standings, sport, setOfNotNull(home.id, away.id)),
            // One card per clip: ESPN can list a clip twice, and the list is keyed by id.
            highlights = dto.videos.orEmpty().mapNotNull(::highlight).distinctBy { it.id }
        )
    }

    /** A clip with a title and at least one playable link; null otherwise (it is not shown). */
    internal fun highlight(video: EspnVideoDto): GameHighlight? {
        val title = video.headline?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val links = highlightLinks(video.links?.source)
        if (links.isEmpty()) return null
        return GameHighlight(
            id = video.id?.takeIf { it.isNotBlank() } ?: links.first(),
            title = title,
            thumbnail = video.thumbnail?.takeIf { it.isNotBlank() },
            durationSeconds = video.duration?.toInt()?.takeIf { it > 0 },
            links = links
        )
    }

    /** A clip's links, best first: HLS (HD, then plain), then HD MP4, then MP4. http(s) only, no repeats. */
    internal fun highlightLinks(source: EspnVideoSourceDto?): List<String> =
        listOfNotNull(source?.hls?.hd?.href, source?.hls?.href, source?.hd?.href, source?.href)
            .map { it.trim() }
            .filter { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) }
            .distinct()

    /** True when the summary says the game is over for good: its copy never changes again. */
    fun isFinal(dto: EspnSummaryDto): Boolean {
        val type = dto.header?.competitions?.firstOrNull()?.status?.type
        return EspnScoreProvider.stateOf(type) == ScoreState.POST && type?.completed == true
    }

    private fun team(dto: EspnSummaryCompetitorDto, played: Boolean): GameTeam? {
        val team = dto.team ?: return null
        val names = listOf(team.abbreviation, team.displayName, team.shortDisplayName, team.name, team.location)
        if (names.all { it.isNullOrBlank() }) return null
        val records = dto.record.orEmpty()
        return GameTeam(
            id = team.id?.takeIf { it.isNotBlank() },
            abbreviation = team.abbreviation?.takeIf { it.isNotBlank() },
            displayName = (team.displayName ?: team.name ?: team.location)?.takeIf { it.isNotBlank() },
            shortDisplayName = team.shortDisplayName?.takeIf { it.isNotBlank() },
            logo = (team.logos?.firstNotNullOfOrNull { it.href } ?: team.logo)?.takeIf { it.isNotBlank() },
            score = if (played) dto.score?.trim()?.toIntOrNull() else null,
            linescores = if (played) {
                dto.linescores.orEmpty().mapNotNull { it.displayValue?.trim()?.toIntOrNull() ?: it.value?.toInt() }
            } else {
                emptyList()
            },
            record = (records.firstOrNull { it.type == "total" } ?: records.firstOrNull())?.summary?.takeIf { it.isNotBlank() }
        )
    }

    private fun scorers(details: List<EspnSummaryDetailDto>, homeId: String?, awayId: String?): List<ScoreScorer> =
        details.filter { it.scoringPlay == true && it.shootout != true }.mapNotNull { detail ->
            val athlete = detail.participants?.firstOrNull()?.athlete
            val name = (athlete?.shortName ?: athlete?.displayName)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ScoreScorer(
                side = sideOf(detail.team?.id, homeId, awayId),
                name = name,
                clock = detail.clock?.displayValue?.takeIf { it.isNotBlank() },
                penalty = detail.penaltyKick == true,
                ownGoal = detail.ownGoal == true
            )
        }

    private fun sideOf(teamId: String?, homeId: String?, awayId: String?): ScoreSide? = when {
        teamId == null -> null
        teamId == homeId -> ScoreSide.HOME
        teamId == awayId -> ScoreSide.AWAY
        else -> null
    }

    /** The sport's [GameStat]s both sides have, each with its better side. */
    internal fun teamStats(teams: List<EspnBoxscoreTeamDto>, sport: String, homeId: String?, awayId: String?): List<StatComparison> {
        fun side(side: ScoreSide): EspnBoxscoreTeamDto? = teams.firstOrNull {
            it.homeAway == if (side == ScoreSide.HOME) "home" else "away"
        } ?: teams.firstOrNull { it.team?.id != null && it.team.id == if (side == ScoreSide.HOME) homeId else awayId }
        val home = side(ScoreSide.HOME)?.statistics.orEmpty()
        val away = side(ScoreSide.AWAY)?.statistics.orEmpty()
        return GameStat.forSport(sport).mapNotNull { stat ->
            val awayText = away.firstOrNull { it.name == stat.espnName }?.displayValue?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val homeText = home.firstOrNull { it.name == stat.espnName }?.displayValue?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val awayValue = statValue(awayText, stat)
            val homeValue = statValue(homeText, stat)
            StatComparison(stat, awayText, homeText, awayValue, homeValue, better(stat, awayValue, homeValue))
        }
    }

    /** "51.2" / "51.2%" → 51.2; a time ("35:16") → seconds; null when unreadable. */
    internal fun statValue(text: String, stat: GameStat): Double? {
        val clean = text.trim().removeSuffix("%")
        if (stat.time) {
            val parts = clean.split(':')
            if (parts.size != 2) return null
            val minutes = parts[0].toIntOrNull() ?: return null
            val seconds = parts[1].toIntOrNull() ?: return null
            return (minutes * 60 + seconds).toDouble()
        }
        return clean.toDoubleOrNull()
    }

    /** The better side: the higher value, or the lower for a stat like turnovers; null on a tie. */
    fun better(stat: GameStat, away: Double?, home: Double?): ScoreSide? {
        if (away == null || home == null || away == home) return null
        val awayHigher = away > home
        return if (awayHigher != stat.lowerIsBetter) ScoreSide.AWAY else ScoreSide.HOME
    }

    /**
     * Up to two players per team from ESPN's leaders, each with every category they led ("24 PTS · 12 REB"),
     * ordered for a 2×2 grid: away 1, home 1, away 2, home 2.
     */
    internal fun performers(leaders: List<EspnTeamLeadersDto>, home: GameTeam, away: GameTeam): List<Performer> {
        fun forSide(side: ScoreSide, team: GameTeam): List<Performer> {
            val entry = leaders.firstOrNull { it.team?.id != null && it.team.id == team.id }
                ?: leaders.firstOrNull { it.team?.abbreviation != null && it.team.abbreviation == team.abbreviation }
                ?: return emptyList()
            val byPlayer = LinkedHashMap<String, Triple<String, String?, MutableList<PerformerStat>>>()
            for (category in entry.leaders.orEmpty()) {
                val leader = category.leaders?.firstOrNull() ?: continue
                val athlete = leader.athlete ?: continue
                val name = (athlete.shortName ?: athlete.displayName)?.takeIf { it.isNotBlank() } ?: continue
                val value = leader.displayValue?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                // A football leader's value is already a line ("10 CAR, 42 YDS"); a bare number gets its label.
                val stat = if (value.any { it.isLetter() }) {
                    PerformerStat(value, null)
                } else {
                    PerformerStat(value, (leader.mainStat?.label ?: category.abbreviation ?: category.displayName)?.takeIf { it.isNotBlank() })
                }
                byPlayer.getOrPut(athlete.id ?: name) { Triple(name, athlete.id, mutableListOf()) }.third += stat
            }
            return byPlayer.values.take(2).map { (name, id, stats) -> Performer(side, team.short, name, stats, id) }
        }
        val awayPerformers = forSide(ScoreSide.AWAY, away)
        val homePerformers = forSide(ScoreSide.HOME, home)
        return (0 until 2).flatMap { listOfNotNull(awayPerformers.getOrNull(it), homePerformers.getOrNull(it)) }
    }

    // The columns a compact box score keeps, by ESPN label, in this order; a table with none of them keeps
    // its first MAX_COLUMNS. Football tables are already short.
    private val BASKETBALL_COLUMNS = listOf("MIN", "PTS", "FG", "3PT", "FT", "REB", "AST", "STL", "BLK", "TO")
    private val HOCKEY_COLUMNS = listOf("G", "A", "+/-", "SOG", "SA", "GA", "SV", "SV%", "TOI", "PIM")
    private const val MAX_COLUMNS = 10

    /** Each team's player tables (away first), with a compact column set; players who did not play left out. */
    internal fun boxScore(players: List<EspnBoxscorePlayersDto>, sport: String, home: GameTeam, away: GameTeam): List<BoxScoreTeam> {
        fun forSide(side: ScoreSide, team: GameTeam): BoxScoreTeam? {
            val entry = players.firstOrNull { it.team?.id != null && it.team.id == team.id } ?: return null
            val tables = entry.statistics.orEmpty().mapNotNull { group -> table(group, sport) }
            return if (tables.isEmpty()) null else BoxScoreTeam(side, team.short, tables)
        }
        return listOfNotNull(forSide(ScoreSide.AWAY, away), forSide(ScoreSide.HOME, home))
    }

    private fun table(group: EspnPlayerGroupDto, sport: String): BoxScoreTable? {
        val labels = group.labels.orEmpty()
        if (labels.isEmpty()) return null
        val preferred = when (sport) {
            "basketball" -> BASKETBALL_COLUMNS
            "hockey" -> HOCKEY_COLUMNS
            else -> emptyList()
        }
        val columns = preferred.mapNotNull { label -> labels.indexOf(label).takeIf { it >= 0 } }
            .ifEmpty { labels.indices.take(MAX_COLUMNS) }
        val rows = group.athletes.orEmpty().mapNotNull { line ->
            if (line.didNotPlay == true) return@mapNotNull null
            val stats = line.stats.orEmpty()
            if (stats.isEmpty()) return@mapNotNull null
            val name = (line.athlete?.shortName ?: line.athlete?.displayName)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            BoxScoreRow(name, line.athlete?.position?.abbreviation?.takeIf { it.isNotBlank() }, columns.map { stats.getOrNull(it).orEmpty() })
        }
        if (rows.isEmpty()) return null
        val totals = group.totals?.takeIf { it.size == labels.size }?.let { totals -> columns.map { totals[it] } }
        return BoxScoreTable(groupTitle(group), columns.map { labels[it] }, rows, totals)
    }

    // "Minnesota Passing"; else a one-word ESPN name ("forwards" → "Forwards"); none for a single table.
    private fun groupTitle(group: EspnPlayerGroupDto): String? =
        group.text?.trim()?.takeIf { it.isNotEmpty() }
            ?: group.name?.trim()?.takeIf { name -> name.isNotEmpty() && name.all { it.isLowerCase() } }?.replaceFirstChar { it.uppercase() }

    private val SOCCER_STANDINGS = listOf("gamesPlayed", "wins", "ties", "losses", "pointDifferential", "points")
    private val STANDINGS = listOf("wins", "losses", "ties", "otLosses", "winPercent", "gamesBehind", "pointsFor", "pointsAgainst", "points", "streak")

    /** ESPN's standings tables with the sport's columns in the usual order; [gameTeamIds] mark the two teams. */
    internal fun standings(dto: EspnStandingsDto?, sport: String, gameTeamIds: Set<String>): List<StandingsTable> =
        dto?.groups.orEmpty().mapNotNull { group ->
            val entries = group.standings?.entries.orEmpty().filter { !it.team.isNullOrBlank() }
            if (entries.isEmpty()) return@mapNotNull null
            val first = entries.first().stats.orEmpty()
            val columns = (if (sport == SOCCER) SOCCER_STANDINGS else STANDINGS).filter { name -> first.any { it.name == name } }
            if (columns.isEmpty()) return@mapNotNull null
            StandingsTable(
                title = (group.header ?: dto?.header)?.takeIf { it.isNotBlank() },
                columns = columns.map { name -> first.first { it.name == name }.abbreviation?.takeIf { it.isNotBlank() } ?: name },
                rows = entries.map { entry ->
                    val stats = entry.stats.orEmpty()
                    StandingsRow(
                        team = entry.team.orEmpty(),
                        values = columns.map { name -> stats.firstOrNull { it.name == name }?.displayValue.orEmpty() },
                        inGame = entry.id != null && entry.id in gameTeamIds
                    )
                }
            )
        }

    private const val SOCCER = "soccer"
}

private val SHOOTOUT_NOTE = Regex("""\bSO\b""")

/**
 * The details pane's score block (S5) for the Game page, away side first: the linescore with the same
 * period headings (OT, 2OT, ET1, SO, "–" for unplayed) and, for soccer, the scorers. Null when the
 * game has neither a score nor periods yet.
 */
fun GameSummary.scoreDetails(): ScoreDetails.Shown? {
    if ((away.score == null || home.score == null) && !hasLinescore) return null
    val periods = maxOf(away.linescores.size, home.linescores.size)
    val soccer = league.sport == "soccer"
    fun side(team: GameTeam, side: ScoreSide) = ScoreDetailsSide(
        short = team.short,
        score = team.score,
        linescores = if (hasLinescore) List(periods) { team.linescores.getOrNull(it) } else emptyList(),
        scorers = if (soccer) scorers.filter { it.side == side } else emptyList()
    )
    val shootout = league.sport == "hockey" && clockText?.let(SHOOTOUT_NOTE::containsMatchIn) == true
    return ScoreDetails.Shown(
        first = side(away, ScoreSide.AWAY),
        second = side(home, ScoreSide.HOME),
        status = clockText,
        live = isLive,
        periods = if (hasLinescore) ScoreDetailsBuilder.periodLabels(league, periods, shootout) else emptyList(),
        soccer = soccer
    )
}
