package com.nuvio.tv.core.sportslive.scores

import java.time.OffsetDateTime

/** A stat as ESPN labels it: "PTS" + "19.5". */
data class PlayerStat(val label: String, val value: String)

/** The Player card's header: who, for whom, and the bio line ESPN gives (height, weight, experience). */
data class PlayerBio(
    val name: String,
    val team: String?,
    val teamLogo: String?,
    val position: String?,
    /** "21", without the "#". */
    val jersey: String?,
    val headshot: String?,
    val details: List<String>
)

/** One of the last games: "@ BKN", "W", "135–123" (winner first, as ESPN), and the player's key stats. */
data class PlayerGame(
    val eventId: String,
    val startMs: Long?,
    /** "@" or "vs". */
    val atVs: String?,
    val opponent: String,
    val result: String?,
    val score: String?,
    val line: List<PlayerStat>
)

/** Everything a Player card (#63) shows. [lastGames] is newest first, at most [PlayerCards.LAST_GAMES]. */
data class PlayerCard(
    val bio: PlayerBio,
    /** ESPN's name for the season stats ("2026-27 regular season stats"), or null. */
    val seasonTitle: String?,
    val season: List<PlayerStat>,
    val lastGames: List<PlayerGame>
)

/** What opens a Player card: the athlete on a feed, and what the opener already knows (shown under Scores Off). */
data class PlayerCardTarget(val league: EspnLeague, val athleteId: String, val name: String, val team: String?)

/**
 * Reads ESPN's athlete and game log responses into a [PlayerCard], and decides where a card can open.
 * Pro leagues only: NBA, NFL, NHL, MLB and soccer (not college soccer).
 */
object PlayerCards {
    const val LAST_GAMES = 5
    const val MAX_SEASON_STATS = 6
    private const val LINE_STATS = 3

    /** Whether a Player card opens for players of [league]; [competitionSlug] tells a college soccer game apart. */
    fun supports(league: EspnLeague, competitionSlug: String? = null): Boolean = when (league.sport) {
        "basketball" -> league == EspnLeagueMap.NBA
        "football" -> league == EspnLeagueMap.NFL
        "hockey" -> league == EspnLeagueMap.NHL
        "baseball" -> league == EspnLeagueMap.MLB
        "soccer" -> !league.league.contains(COLLEGE) && competitionSlug?.contains(COLLEGE) != true
        else -> false
    }

    private const val COLLEGE = "ncaa"

    /** The card a top performer opens, or null: no ESPN athlete id, or not a pro league. */
    fun targetFor(performer: Performer, league: EspnLeague, competitionSlug: String?, teamName: String?): PlayerCardTarget? {
        val id = performer.athleteId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!supports(league, competitionSlug)) return null
        return PlayerCardTarget(league, id, performer.player, teamName ?: performer.team)
    }

    /** The card a Team page leader opens, or null: no ESPN athlete id, or not a pro league. */
    fun targetFor(leader: TeamLeader, team: TeamRef): PlayerCardTarget? {
        val id = leader.athleteId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!supports(team.league, team.event.leagueSlug)) return null
        return PlayerCardTarget(team.league, id, leader.player, team.name.takeIf { it.isNotBlank() })
    }

    /** The card, or null when the athlete response names no player. [gameLog] null: no last games. */
    fun parse(athlete: EspnAthleteResponseDto, gameLog: EspnGameLogDto?, sport: String): PlayerCard? {
        val dto = athlete.athlete ?: return null
        val name = dto.displayName.clean() ?: dto.shortName.clean() ?: return null
        val bio = PlayerBio(
            name = name,
            team = dto.team?.displayName.clean() ?: dto.team?.abbreviation.clean(),
            teamLogo = dto.team?.logos.orEmpty().firstNotNullOfOrNull { it.href.clean() },
            position = dto.position?.displayName.clean() ?: dto.position?.abbreviation.clean(),
            jersey = dto.jersey.clean()?.removePrefix("#"),
            headshot = dto.headshot?.href.clean(),
            details = listOfNotNull(dto.displayHeight.clean(), dto.displayWeight.clean(), dto.displayExperience.clean())
        )
        val season = dto.statsSummary?.statistics.orEmpty().mapNotNull { stat ->
            val label = stat.abbreviation.clean() ?: return@mapNotNull null
            val value = stat.displayValue.clean() ?: return@mapNotNull null
            PlayerStat(label, value)
        }.take(MAX_SEASON_STATS)
        return PlayerCard(
            bio = bio,
            seasonTitle = dto.statsSummary?.displayName.clean(),
            season = season,
            lastGames = gameLog?.let { lastGames(it, sport) }.orEmpty()
        )
    }

    /** The newest [LAST_GAMES] games of the log (every season type, by date), each with its key stat line. */
    internal fun lastGames(log: EspnGameLogDto, sport: String): List<PlayerGame> {
        val labels = log.labels.orEmpty()
        val names = log.names.orEmpty()
        val columns = lineColumns(names, sport)
        val rows = HashMap<String, List<String>>()
        log.seasonTypes.orEmpty().forEach { type ->
            type.categories.orEmpty().forEach { category ->
                category.events.orEmpty().forEach { row ->
                    val id = row.eventId ?: return@forEach
                    row.stats?.let { rows.putIfAbsent(id, it) }
                }
            }
        }
        return log.events.orEmpty().values.mapNotNull { event ->
            val id = event.id.clean() ?: return@mapNotNull null
            val opponent = event.opponent?.abbreviation.clean() ?: event.opponent?.displayName.clean() ?: return@mapNotNull null
            val stats = rows[id].orEmpty()
            PlayerGame(
                eventId = id,
                startMs = event.gameDate?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() },
                atVs = event.atVs.clean(),
                opponent = opponent,
                result = event.gameResult.clean(),
                score = event.score.clean()?.replace('-', '–'),
                line = columns.mapNotNull { index ->
                    val label = labels.getOrNull(index).clean() ?: return@mapNotNull null
                    val value = stats.getOrNull(index).clean() ?: return@mapNotNull null
                    PlayerStat(label, value)
                }
            )
        }
            .sortedByDescending { it.startMs ?: Long.MIN_VALUE }
            .take(LAST_GAMES)
    }

    // A game's key stats per kind of player, by ESPN column name. The kind is the group whose first column
    // comes first in the log: a quarterback's log opens with passing, a receiver's with receptions, a
    // goalie's or pitcher's with their own columns.
    private val LINES: Map<String, List<List<String>>> = mapOf(
        "basketball" to listOf(listOf("points", "totalRebounds", "assists")),
        "football" to listOf(
            listOf("passingYards", "passingTouchdowns", "interceptions"),
            listOf("rushingYards", "rushingTouchdowns", "rushingAttempts"),
            listOf("receptions", "receivingYards", "receivingTouchdowns"),
            listOf("totalTackles", "sacks", "interceptions")
        ),
        "hockey" to listOf(
            listOf("goals", "assists", "plusMinus"),
            listOf("saves", "goalsAgainst", "savePct")
        ),
        "baseball" to listOf(
            listOf("hits", "homeRuns", "RBIs"),
            listOf("innings", "strikeouts", "earnedRuns")
        ),
        "soccer" to listOf(
            listOf("totalGoals", "goalAssists", "totalShots"),
            listOf("saves", "goalsConceded")
        )
    )

    /** The column indexes of a game's key stat line: the player's group, else the first columns. */
    internal fun lineColumns(names: List<String>, sport: String): List<Int> {
        val groups = LINES[sport].orEmpty()
            .map { group -> group.map(names::indexOf).filter { it >= 0 } }
            .filter { it.isNotEmpty() }
        val group = groups.minByOrNull { it.min() }
        return group ?: names.indices.take(LINE_STATS).toList()
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
