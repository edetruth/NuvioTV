package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.Memo
import com.nuvio.tv.core.sportslive.SportsLiveTeamAliases
import java.text.Normalizer

/** Which of a team's names a search matched (#149): line 2 of a result says it. */
enum class TeamMatchField { NAME, SHORT_NAME, NICKNAME, CITY, ABBREVIATION, ALIAS }

/** How well a name matched: the whole name, its start (or a word's start), or anywhere in it. */
enum class TeamMatchTier { EXACT, PREFIX, CONTAINS }

/** Why a team is in the results: the [tier], the [field] and that name as shown ([text]). */
data class TeamMatch(val tier: TeamMatchTier, val field: TeamMatchField, val text: String)

/** One team of a searchable league; [match] is null when browsing. */
data class TeamHit(val team: DirectoryTeam, val league: MyLeague, val match: TeamMatch? = null) {
    /** A stable key for lists: the league and ESPN id. */
    val key: String get() = "${league.id}|${team.id}"
}

/** A league's teams A–Z, for browsing with an empty field. */
data class TeamGroup(val league: MyLeague, val teams: List<TeamHit>)

/**
 * The searchable teams, folded once ([TeamSearch.index]). Rebuilt when the directory gets a new list,
 * never per keystroke.
 */
class TeamSearchIndex internal constructor(internal val teams: List<IndexedTeam>) {
    /** The leagues with at least one team. */
    val leagues: Set<MyLeague> = teams.mapTo(HashSet()) { it.league }
    val isEmpty: Boolean get() = teams.isEmpty()
}

internal class IndexedName(val field: TeamMatchField, val text: String, val forms: List<String>)

internal class IndexedTeam(val team: DirectoryTeam, val league: MyLeague, val sortKey: String, val names: List<IndexedName>)

/**
 * Team search over the on-device team directory (#149): "+ Add a team" and first-run step 3. Local and
 * pure: it reads the ESPN team lists the directory keeps ([TeamDirectory], filled on demand by
 * [TeamListFill]), so it works off-season.
 *
 * - A team answers to its full name, short name, nickname, city and abbreviation, and to the aliases of
 *   those names ([SportsLiveTeamAliases]: "Connecticut" finds UConn). "St" and "State" are one word after
 *   the first word, and a first "Saint" is "St" (#132); apostrophes and dots join their word ("St. John's"
 *   = "st johns"); accents fold.
 * - Ranking: exact, then prefix (the name's or a word's start), then contains (3 letters or more); the
 *   abbreviation only exact or prefix. Then A–Z, then league order.
 * - An empty query browses: every team A–Z under its league.
 */
object TeamSearch {

    /** The leagues with an ESPN team list, in picker order. Soccer's cups and internationals have none here. */
    val LEAGUES: List<MyLeague> = listOf(
        MyLeague.NBA, MyLeague.COLLEGE_BASKETBALL, MyLeague.NFL, MyLeague.COLLEGE_FOOTBALL, MyLeague.NHL, MyLeague.MLB,
        MyLeague.PREMIER_LEAGUE, MyLeague.LA_LIGA, MyLeague.BUNDESLIGA, MyLeague.SERIE_A, MyLeague.LIGUE_1, MyLeague.MLS
    )

    /** The sports team search can filter by (team sports with a list), in picker order. */
    val SPORTS: List<MySport> = LEAGUES.map { it.sport }.distinct()

    /** Results past this many are not listed: the field narrows them. */
    const val MAX_RESULTS = 60

    /** Contains-matches start at this many letters (one or two letters are in almost every name). */
    private const val CONTAINS_MIN = 3

    /** [league]'s ESPN team list: its feed, or for a soccer league `soccer/{slug}`. */
    fun teamList(league: MyLeague): EspnLeague = league.feed ?: EspnLeague(league.sport.espnSport, league.id)

    /** The searchable leagues of [sport] (null: all). */
    fun leaguesOf(sport: MySport?): List<MyLeague> = if (sport == null) LEAGUES else LEAGUES.filter { it.sport == sport }

    /** The index of every searchable league's list in [directory]. */
    fun index(directory: TeamDirectory): TeamSearchIndex =
        index(LEAGUES.associateWith { directory.teams(teamList(it)) })

    /** The index of [lists] (league → its ESPN teams). */
    fun index(lists: Map<MyLeague, List<DirectoryTeam>>): TeamSearchIndex =
        TeamSearchIndex(LEAGUES.flatMap { league -> lists[league].orEmpty().map { indexed(it, league) } })

    /** The teams of [sport] (null: all) whose names match [query], best first; empty for a blank query. */
    fun search(index: TeamSearchIndex, query: String, sport: MySport? = null, limit: Int = MAX_RESULTS): List<TeamHit> {
        val folded = fold(query)
        if (folded.isEmpty()) return emptyList()
        val queries = listOf(folded, stForm(folded)).distinct()
        return index.teams.asSequence()
            .filter { sport == null || it.league.sport == sport }
            .mapNotNull { team -> bestMatch(team, queries)?.let { team to it } }
            .sortedWith(
                compareBy<Pair<IndexedTeam, TeamMatch>> { it.second.tier }
                    .thenBy { it.first.sortKey }
                    .thenBy { it.first.league.ordinal }
            )
            .take(limit)
            .map { (team, match) -> TeamHit(team.team, team.league, match) }
            .toList()
    }

    /** Every team of [sport] (null: all), A–Z under its league, leagues in picker order; leagues without a list are left out. */
    fun browse(index: TeamSearchIndex, sport: MySport? = null): List<TeamGroup> =
        index.teams.filter { sport == null || it.league.sport == sport }
            .groupBy { it.league }
            .toSortedMap(compareBy { LEAGUES.indexOf(it) })
            .map { (league, teams) -> TeamGroup(league, teams.sortedBy { it.sortKey }.map { TeamHit(it.team, league) }) }

    private fun bestMatch(team: IndexedTeam, queries: List<String>): TeamMatch? {
        var best: TeamMatch? = null
        for (name in team.names) {
            val tier = name.forms.mapNotNull { form -> queries.mapNotNull { tierOf(form, it, name.field) }.minOrNull() }.minOrNull()
                ?: continue
            // Names are listed in field order, so a later name wins only with a better tier.
            if (best == null || tier < best.tier) best = TeamMatch(tier, name.field, name.text)
            if (tier == TeamMatchTier.EXACT) break
        }
        return best
    }

    private fun tierOf(form: String, query: String, field: TeamMatchField): TeamMatchTier? = when {
        form == query -> TeamMatchTier.EXACT
        form.startsWith(query) -> TeamMatchTier.PREFIX
        field == TeamMatchField.ABBREVIATION -> null
        form.contains(" $query") -> TeamMatchTier.PREFIX
        query.length >= CONTAINS_MIN && form.contains(query) -> TeamMatchTier.CONTAINS
        else -> null
    }

    private fun indexed(team: DirectoryTeam, league: MyLeague): IndexedTeam {
        val names = ArrayList<IndexedName>()
        val seen = HashSet<String>()
        fun add(field: TeamMatchField, text: String?) {
            val shown = text?.trim()?.takeIf { it.isNotEmpty() } ?: return
            val folded = fold(shown)
            if (folded.isEmpty() || !seen.add(folded)) return
            names += IndexedName(field, shown, listOf(folded, stForm(folded)).distinct())
        }
        add(TeamMatchField.NAME, team.displayName)
        add(TeamMatchField.SHORT_NAME, team.shortName)
        add(TeamMatchField.NICKNAME, team.nickname)
        add(TeamMatchField.CITY, team.location)
        add(TeamMatchField.ABBREVIATION, team.abbreviation)
        // Aliases of the place names ("UConn" → "Connecticut"), alone and with the nickname.
        val places = listOfNotNull(team.displayName, team.shortName, team.location).map(::fold)
        // A soccer club's "nickname" is its full name: no combination then.
        val nickname = team.nickname?.let(::fold)?.takeIf { it.isNotEmpty() && it != fold(team.displayName) }
        for (place in places) {
            for (alias in SportsLiveTeamAliases.group(place)) {
                val shown = titleCase(alias)
                add(TeamMatchField.ALIAS, shown)
                if (nickname != null && !fold(shown).endsWith(nickname)) add(TeamMatchField.ALIAS, "$shown ${team.nickname!!.trim()}")
            }
        }
        return IndexedTeam(team, league, fold(team.displayName), names)
    }

    private fun titleCase(text: String): String =
        text.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }

    private val folds = Memo(16_384) { text: String -> foldUncached(text) }
    private val stForms = Memo(16_384) { text: String -> stFormUncached(text) }

    /** [text] lowercased, accents and apostrophes/dots dropped, "&" as "and", any other mark a space. */
    internal fun fold(text: String): String = folds(text)

    /** "Saint" first and "State" after the first word, as "st": one form for both spellings. */
    internal fun stForm(folded: String): String = stForms(folded)

    private fun foldUncached(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        var gap = false
        fun word(chars: CharSequence) {
            if (gap && out.isNotEmpty()) out.append(' ')
            gap = false
            out.append(chars)
        }
        for (c in decomposed) {
            when {
                Character.getType(c) == Character.NON_SPACING_MARK.toInt() -> Unit
                c == '\'' || c == '’' || c == '‘' || c == '`' || c == '.' -> Unit
                c == '&' -> { gap = true; word("and"); gap = true }
                c.isLetterOrDigit() -> word(c.lowercaseChar().toString())
                else -> gap = true
            }
        }
        return out.toString()
    }

    private fun stFormUncached(folded: String): String {
        val words = folded.split(' ')
        return words.mapIndexed { i, w ->
            when {
                i == 0 && w == "saint" -> "st"
                i > 0 && w == "state" -> "st"
                else -> w
            }
        }.joinToString(" ")
    }
}
