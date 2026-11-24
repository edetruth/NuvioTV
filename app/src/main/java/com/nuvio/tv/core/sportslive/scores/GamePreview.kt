package com.nuvio.tv.core.sportslive.scores

/** One of a team's last games (#171): [result] "W", "L" or "D", the [opponent]'s short name, [home] or away, the [score] as ESPN writes it. */
data class FormGame(val result: String, val opponent: String?, val home: Boolean?, val score: String?)

/** A team's form: its last games, oldest first. */
data class TeamForm(val side: ScoreSide, val team: String, val games: List<FormGame>)

/** A meeting this season (or a soccer head-to-head): both sides' short names and scores, the winner. */
data class SeriesMeeting(val dateMs: Long?, val away: String, val awayScore: String?, val home: String, val homeScore: String?, val winner: ScoreSide?)

/** Head-to-head (#171): ESPN's line ("MEM leads series 1-0") and the meetings, latest first. */
data class HeadToHead(val summary: String, val meetings: List<SeriesMeeting>)

/** A season leader of one team: "Points" "J. Morant" "25.4". */
data class PreviewLeader(val side: ScoreSide, val category: String, val player: String, val value: String)

/** An injury: the player, ESPN's status ("Out", "Questionable"), what ("Ankle"). */
data class PreviewInjury(val side: ScoreSide, val player: String, val status: String, val detail: String?)

/** ESPN's win chance, in percent, for each side. */
data class WinChance(val away: Double, val home: Double)

/** A side of the preview's header: the record, a college rank, the place in its standings group. */
data class PreviewHeaderSide(val record: String?, val rank: Int?, val place: Int?, val group: String?)

/** The Preview's blocks, in the screen's order; one with no data is left out (#171). */
enum class PreviewBlock { HEADER, FORM, HEAD_TO_HEAD, LEADERS, INJURIES, WIN_CHANCE }

/**
 * An upcoming game's preview (#171), from ESPN's summary: the header (records, places, ranks), each side's
 * last five, the head-to-head, season leaders, injuries and win chance. Only the blocks with data show.
 * Spoiler-neutral: nothing about this game's result exists yet.
 */
data class GamePreview(
    val away: PreviewHeaderSide,
    val home: PreviewHeaderSide,
    val form: List<TeamForm>,
    val headToHead: HeadToHead?,
    val leaders: List<PreviewLeader>,
    val injuries: List<PreviewInjury>,
    val winChance: WinChance?
) {
    val blocks: List<PreviewBlock>
        get() = buildList {
            if (listOf(away, home).any { it.record != null || it.rank != null || it.place != null }) add(PreviewBlock.HEADER)
            if (form.any { it.games.isNotEmpty() }) add(PreviewBlock.FORM)
            if (headToHead != null) add(PreviewBlock.HEAD_TO_HEAD)
            if (leaders.isNotEmpty()) add(PreviewBlock.LEADERS)
            if (injuries.isNotEmpty()) add(PreviewBlock.INJURIES)
            if (winChance != null) add(PreviewBlock.WIN_CHANCE)
        }

    /**
     * Without other games' results: the form chips (W / L) and the head-to-head meetings give away how a series
     * stands, so they hide under Hide until I reveal and spoiler protection until revealed (live.28 H5).
     */
    fun withoutResults(): GamePreview = copy(form = emptyList(), headToHead = null)

    /** True when the Preview tab has anything beyond the header (a header alone is the page's own hero). */
    val hasContent: Boolean get() = blocks.any { it != PreviewBlock.HEADER }
}

/** Reads the preview's blocks from ESPN's summary of an upcoming game (#171). Tolerant: a missing part is no block. */
object GamePreviewParser {
    const val FORM_GAMES = 5
    const val MEETINGS = 3
    const val LEADER_CATEGORIES = 3
    const val INJURIES_PER_TEAM = 4

    fun parse(dto: EspnSummaryDto, home: GameTeam, away: GameTeam): GamePreview {
        fun sideOf(teamId: String?, abbreviation: String?): ScoreSide? = when {
            teamId != null && teamId == home.id -> ScoreSide.HOME
            teamId != null && teamId == away.id -> ScoreSide.AWAY
            abbreviation != null && abbreviation == home.abbreviation -> ScoreSide.HOME
            abbreviation != null && abbreviation == away.abbreviation -> ScoreSide.AWAY
            else -> null
        }
        val places = places(dto.standings, setOfNotNull(home.id, away.id))
        return GamePreview(
            away = PreviewHeaderSide(away.record, away.rank, places[away.id]?.first, places[away.id]?.second),
            home = PreviewHeaderSide(home.record, home.rank, places[home.id]?.first, places[home.id]?.second),
            form = form(dto.lastFiveGames.orEmpty(), ::sideOf, home, away),
            headToHead = headToHead(dto.seasonseries.orEmpty()),
            leaders = leaders(dto.leaders.orEmpty(), ::sideOf),
            injuries = injuries(dto.injuries.orEmpty(), ::sideOf),
            winChance = winChance(dto.predictor, home.id, away.id)
        )
    }

    private fun form(
        entries: List<EspnLastFiveDto>,
        sideOf: (String?, String?) -> ScoreSide?,
        home: GameTeam,
        away: GameTeam
    ): List<TeamForm> {
        val forms = entries.mapNotNull { entry ->
            val side = sideOf(entry.team?.id, entry.team?.abbreviation) ?: return@mapNotNull null
            val games = entry.events.orEmpty().mapNotNull { game ->
                val result = game.gameResult?.trim()?.uppercase()?.takeIf { it in RESULTS } ?: return@mapNotNull null
                val atHome = when (game.atVs?.trim()) {
                    "vs" -> true
                    "@" -> false
                    else -> null
                }
                val opponent = game.opponent?.let { it.abbreviation ?: it.shortDisplayName ?: it.displayName }?.takeIf { it.isNotBlank() }
                FormGame(result, opponent, atHome, game.score?.trim()?.takeIf { it.isNotEmpty() })
            }
            // ESPN lists the latest first: the chips read oldest to latest, left to right.
            if (games.isEmpty()) null else TeamForm(side, if (side == ScoreSide.HOME) home.short else away.short, games.take(FORM_GAMES).reversed())
        }
        // Away first, as the page reads.
        return forms.distinctBy { it.side }.sortedBy { if (it.side == ScoreSide.AWAY) 0 else 1 }
    }

    private fun headToHead(series: List<EspnSeasonSeriesDto>): HeadToHead? {
        val entry = series.firstOrNull { !it.summary.isNullOrBlank() } ?: return null
        val meetings = entry.events.orEmpty().mapNotNull { event ->
            val competitors = event.competitors.orEmpty()
            val home = competitors.firstOrNull { it.homeAway == "home" } ?: return@mapNotNull null
            val away = competitors.firstOrNull { it.homeAway == "away" } ?: return@mapNotNull null
            // A meeting still to come lists "0" scores and no winner either way: not a meeting yet.
            if (home.winner == null && away.winner == null) return@mapNotNull null
            val homeName = home.team?.abbreviation ?: home.team?.displayName ?: return@mapNotNull null
            val awayName = away.team?.abbreviation ?: away.team?.displayName ?: return@mapNotNull null
            SeriesMeeting(
                dateMs = event.date?.let(EspnScoreProvider::parseDate),
                away = awayName,
                awayScore = away.score?.trim()?.takeIf { it.isNotEmpty() },
                home = homeName,
                homeScore = home.score?.trim()?.takeIf { it.isNotEmpty() },
                winner = when {
                    home.winner == true -> ScoreSide.HOME
                    away.winner == true -> ScoreSide.AWAY
                    else -> null
                }
            )
        }.filter { it.awayScore != null && it.homeScore != null }
            .sortedByDescending { it.dateMs ?: Long.MIN_VALUE }
            .take(MEETINGS)
        return HeadToHead(entry.summary!!.trim(), meetings)
    }

    private fun leaders(entries: List<EspnTeamLeadersDto>, sideOf: (String?, String?) -> ScoreSide?): List<PreviewLeader> {
        val bySide = entries.mapNotNull { entry ->
            val side = sideOf(entry.team?.id, entry.team?.abbreviation) ?: return@mapNotNull null
            side to entry.leaders.orEmpty().mapNotNull { category ->
                val leader = category.leaders?.firstOrNull() ?: return@mapNotNull null
                val player = (leader.athlete?.shortName ?: leader.athlete?.displayName)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val value = leader.displayValue?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val label = (category.displayName ?: category.abbreviation ?: category.name)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                PreviewLeader(side, label, player, value)
            }.take(LEADER_CATEGORIES)
        }.distinctBy { it.first }
        return bySide.sortedBy { if (it.first == ScoreSide.AWAY) 0 else 1 }.flatMap { it.second }
    }

    private fun injuries(entries: List<EspnTeamInjuriesDto>, sideOf: (String?, String?) -> ScoreSide?): List<PreviewInjury> =
        entries.mapNotNull { entry ->
            val side = sideOf(entry.team?.id, entry.team?.abbreviation) ?: return@mapNotNull null
            side to entry.injuries.orEmpty().mapNotNull { injury ->
                val player = (injury.athlete?.shortName ?: injury.athlete?.displayName)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val status = injury.status?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                PreviewInjury(side, player, status, injury.details?.type?.trim()?.takeIf { it.isNotEmpty() })
            }.sortedBy { if (it.status.equals("Out", ignoreCase = true)) 0 else 1 }.take(INJURIES_PER_TEAM)
        }.distinctBy { it.first }.sortedBy { if (it.first == ScoreSide.AWAY) 0 else 1 }.flatMap { it.second }

    private fun winChance(predictor: EspnPredictorDto?, homeId: String?, awayId: String?): WinChance? {
        predictor ?: return null
        val homeTeam = predictor.homeTeam?.takeIf { it.id == null || homeId == null || it.id == homeId }
        val awayTeam = predictor.awayTeam?.takeIf { it.id == null || awayId == null || it.id == awayId }
        val home = homeTeam?.gameProjection?.trim()?.toDoubleOrNull() ?: return null
        val away = awayTeam?.gameProjection?.trim()?.toDoubleOrNull() ?: return null
        if (home < 0 || away < 0 || home + away <= 0) return null
        return WinChance(away, home)
    }

    /** Each game team's place in its standings group (1-based, by ESPN's listed order) and the group's name. */
    private fun places(dto: EspnStandingsDto?, teamIds: Set<String>): Map<String?, Pair<Int, String?>> {
        val out = HashMap<String?, Pair<Int, String?>>()
        dto?.groups.orEmpty().forEach { group ->
            group.standings?.entries.orEmpty().forEachIndexed { index, entry ->
                val id = entry.id ?: return@forEachIndexed
                if (id in teamIds && id !in out) out[id] = (index + 1) to (group.header ?: dto?.header)?.takeIf { it.isNotBlank() }
            }
        }
        return out
    }

    private val RESULTS = setOf("W", "L", "D", "T")
}
