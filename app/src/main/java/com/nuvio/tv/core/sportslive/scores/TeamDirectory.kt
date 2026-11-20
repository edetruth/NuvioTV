package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.core.sportslive.Memo
import com.nuvio.tv.domain.model.SportsLiveItem
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * One team of a league's ESPN team list (#82). [nickname] is ESPN's `name` ("Celtics", "Wildcats"; a
 * soccer club's full name); [logo] is ESPN's default `/i/` logo, which [TeamLogoUrls.scaled] sizes when drawn.
 */
data class DirectoryTeam(
    val id: String,
    val displayName: String,
    val shortName: String?,
    val abbreviation: String?,
    val location: String?,
    val nickname: String?,
    val logo: String
) {
    internal fun asScoreTeam() = ScoreTeam(
        id = id, abbreviation = abbreviation, displayName = displayName, shortDisplayName = shortName, name = nickname,
        location = location, logo = logo, score = null, linescores = emptyList()
    )
}

/**
 * Every team of the leagues the viewer watches or follows, by league (#82), from ESPN's team lists: a
 * card's logos are drawn before any scoreboard answers, or when its game never matches. Precision first:
 * a card side resolves only when exactly one team of that league answers to it (the names [ScoreMatcher]
 * reads, through the alias layer), and a card resolves only when exactly one of its leagues resolves both
 * sides and every one of its leagues is known. Soccer leagues stay apart here (no merged "soccer" book),
 * and `soccer/all` has no list. Least recently used leagues go past [maxLeagues].
 */
class TeamDirectory(private val maxLeagues: Int = MAX_LEAGUES) {

    private class Entry(val teams: List<DirectoryTeam>, val fetchedAt: Long, val keys: List<Set<String>>)

    private val leagues = LinkedHashMap<String, Entry>(16, 0.75f, true)

    /** Bumped whenever a league is added or replaced: [leaguesHolding]'s answers are kept per generation. */
    @Volatile
    private var generation = 0L

    /** Called after a league is added or replaced (the [TeamLogoBook] bumps its revision: cards redraw). */
    @Volatile
    internal var onChange: (() -> Unit)? = null

    /** Stores [teams] as [league]'s list, fetched at [fetchedAt]; returns the leagues dropped to stay bounded. */
    fun put(league: EspnLeague, teams: List<DirectoryTeam>, fetchedAt: Long): List<EspnLeague> {
        val entry = Entry(teams, fetchedAt, teams.map { TeamLogoBook.namesOf(it.asScoreTeam()) })
        val dropped = ArrayList<EspnLeague>()
        synchronized(this) {
            generation++
            leagues[league.path] = entry
            val iterator = leagues.keys.iterator()
            while (leagues.size > maxLeagues && iterator.hasNext()) {
                val path = iterator.next()
                iterator.remove()
                leagueOf(path)?.let(dropped::add)
            }
        }
        onChange?.invoke()
        return dropped
    }

    /** When [league]'s list was fetched; null when it is not known. */
    @Synchronized
    fun fetchedAt(league: EspnLeague): Long? = leagues[league.path]?.fetchedAt

    @Synchronized
    fun teams(league: EspnLeague): List<DirectoryTeam> = leagues[league.path]?.teams.orEmpty()

    /** The one team of [league] whose names include [cardSide]; null when none, several, or the league is unknown. */
    fun resolve(league: EspnLeague, cardSide: String): DirectoryTeam? {
        val entry = synchronized(this) { leagues[league.path] } ?: return null
        fun found(keys: Set<String>) = entry.teams.indices.filter { index -> entry.keys[index].any { it in keys } }
        // The names layer drops a trailing bracketed group ("Celtics [HD]"), which would read "Miami (OH)"
        // as "Miami": with the brackets opened first, a side naming a team that way finds that team only.
        val opened = cardSide.replace(BRACKETS, " ")
        if (opened != cardSide) {
            val full = found(TeamLogoBook.sideKeys(opened))
            if (full.isNotEmpty()) return full.singleOrNull()?.let(entry.teams::get)
        }
        // College lists also take "St" for "State" and the reverse, as the score matcher does (P4).
        val keys = TeamLogoBook.sideKeys(cardSide).let { own ->
            if (league in COLLEGE_LEAGUES) own + own.flatMap { ScoreMatcher.collegeAlternates(it) + stateInside(it) } else own
        }
        if (keys.isEmpty()) return null
        return found(keys).singleOrNull()?.let(entry.teams::get)
    }

    /**
     * The loaded leagues whose list holds both [first] and [second] as two different teams (#167): a card that
     * names no sport is told by its teams. Kept per pair and directory generation (no regex work per call).
     */
    fun leaguesHolding(first: String, second: String): List<EspnLeague> = holding(Triple(first, second, generation))

    /**
     * [side]'s abbreviation ("SUU", "STET") when it resolves in one of [item]'s listed leagues (#167): a panel
     * line names an unmatched game's side by it. Kept per side, card leagues and directory generation.
     */
    fun abbreviationOf(item: SportsLiveItem, side: String): String? = abbreviations(Triple(side, leaguesFor(item), generation))

    private val abbreviations = Memo<Triple<String, List<EspnLeague>, Long>, String?>(HOLDING_MEMO) { (side, leagues, _) ->
        leagues.firstNotNullOfOrNull { league -> resolve(league, side)?.abbreviation?.trim()?.takeIf(String::isNotEmpty) }
    }

    /** True when [text] is a listed team's location ("Stetson", "Southern Utah") in a loaded league: a place, not a nickname. */
    fun isLocation(text: String): Boolean = text.trim().lowercase() in locations(generation)

    private val locations = Memo<Long, Set<String>>(2) { _ ->
        synchronized(this) { leagues.values.flatMap { it.teams } }
            .mapNotNullTo(HashSet()) { team -> team.location?.trim()?.lowercase()?.takeIf(String::isNotEmpty) }
    }

    private val holding = Memo<Triple<String, String, Long>, List<EspnLeague>>(HOLDING_MEMO) { (first, second, _) ->
        val loaded = synchronized(this) { leagues.keys.toList() }.mapNotNull(::leagueOf)
        loaded.filter { league ->
            val a = resolve(league, first) ?: return@filter false
            val b = resolve(league, second) ?: return@filter false
            a.id != b.id
        }
    }

    /**
     * Both sides' teams for [item] ([first], [second] in its title order): only when every league the card
     * can be on ([EspnLeagueMap], with its college fallback) has a list, and exactly one of them resolves
     * both sides. A card no listed league covers (`soccer/all`, individual sports) gets none.
     */
    fun forCard(item: SportsLiveItem, first: String, second: String): Pair<KnownTeam, KnownTeam>? {
        val leagues = leaguesFor(item)
        if (leagues.isEmpty()) return null
        if (leagues.any { fetchedAt(it) == null }) return null
        val found = leagues.mapNotNull { league ->
            val a = resolve(league, first) ?: return@mapNotNull null
            val b = resolve(league, second) ?: return@mapNotNull null
            if (a.id == b.id) return@mapNotNull null
            KnownTeam(a.logo, a.abbreviation) to KnownTeam(b.logo, b.abbreviation)
        }
        // A school's men's and women's teams share their logos (#163): a "College" card on both feeds still gets them.
        return found.distinct().singleOrNull()
    }

    /**
     * A stand-in game for [item] from this directory (#62, #134): its two teams (first away, second home, in the
     * card's order) and its start, when every league the card can be on has a list and exactly one of them
     * names both sides. Team pages and Follow read real ESPN teams (ids, short names) from it; null otherwise.
     */
    fun standInEvent(item: SportsLiveItem): ScoreSnapshot? {
        val (first, second) = ScoreChips.orderedSides(item) ?: return null
        val leagues = leaguesFor(item)
        if (leagues.isEmpty() || leagues.any { fetchedAt(it) == null }) return null
        val found = leagues.mapNotNull { league ->
            val a = resolve(league, first) ?: return@mapNotNull null
            val b = resolve(league, second) ?: return@mapNotNull null
            if (a.id == b.id) null else Triple(league, a, b)
        }
        val (league, away, home) = found.singleOrNull() ?: return null
        return ScoreSnapshot(
            eventId = "",
            league = league,
            startMs = item.startMs,
            timeValid = item.startHasTimeOfDay,
            state = ScoreState.PRE,
            clockText = null,
            isFinal = false,
            home = home.asScoreTeam(),
            away = away.asScoreTeam()
        )
    }

    companion object {
        /** Leagues kept in memory and on disk. */
        const val MAX_LEAGUES = 48

        private const val HOLDING_MEMO = 2_048

        /** A list is fetched again after a week. */
        const val REFRESH_MS = 7L * 24 * 60 * 60 * 1000

        private val SLUG = Regex("""[a-z0-9._-]+""")
        /** "michigan st spartans" ↔ "michigan state spartans": "St" after the first word is "State" (a first "St" is Saint). */
        internal fun stateInside(key: String): Set<String> {
            val words = key.split(' ')
            if (words.size < 3) return emptySet()
            return buildSet {
                for (i in 1 until words.lastIndex) {
                    when (words[i]) {
                        "st" -> add((words.take(i) + "state" + words.drop(i + 1)).joinToString(" "))
                        "state" -> add((words.take(i) + "st" + words.drop(i + 1)).joinToString(" "))
                    }
                }
            }
        }

        private val COLLEGE_LEAGUES = setOf(EspnLeagueMap.COLLEGE_BASKETBALL, EspnLeagueMap.COLLEGE_BASKETBALL_WOMEN, EspnLeagueMap.COLLEGE_FOOTBALL)
        private val BRACKETS = Regex("""[\[\]()]""")

        // Team sports ESPN lists teams for; individual sports (tennis, golf, MMA, racing) have none.
        private val TEAM_SPORTS = setOf("basketball", "football", "hockey", "baseball", "soccer")

        /** True when [league] has a team list worth asking for: a team sport, never `soccer/all`. */
        fun covers(league: EspnLeague): Boolean =
            league.sport in TEAM_SPORTS && league != EspnLeagueMap.SOCCER_ALL &&
                SLUG.matches(league.sport) && SLUG.matches(league.league)

        /** The listed leagues a card can be on: its first routed source's leagues and college fallback. */
        fun leaguesFor(item: SportsLiveItem): List<EspnLeague> {
            val source = (listOf(item) + item.alternates).firstOrNull { EspnLeagueMap.leaguesFor(it).isNotEmpty() } ?: return emptyList()
            val all = EspnLeagueMap.leaguesFor(source) + listOfNotNull(EspnLeagueMap.collegeFallbackFor(source))
            // A card that can be on soccer/all could be any club anywhere: no single list answers for it.
            if (EspnLeagueMap.SOCCER_ALL in all) return emptyList()
            return all.distinct().filter(::covers)
        }

        private fun leagueOf(path: String): EspnLeague? =
            path.split('/').takeIf { it.size == 2 }?.let { EspnLeague(it[0], it[1]) }

        /** [body], ESPN's `{sport}/{league}/teams`, as directory teams; null when unreadable. Teams without an id, name or `/i/` logo are left out. */
        fun parse(body: String, moshi: Moshi): List<DirectoryTeam>? {
            val dto = runCatching { moshi.adapter(EspnTeamListDto::class.java).fromJson(body) }.getOrNull() ?: return null
            val teams = dto.sports.orEmpty().flatMap { it.leagues.orEmpty() }.flatMap { it.teams.orEmpty() }
                .mapNotNull { it.team }
                .filter { it.isActive != false }
                .mapNotNull { team ->
                    val id = team.id?.trim()?.takeIf { it.isNotEmpty() && clean(it) } ?: return@mapNotNull null
                    val name = team.displayName?.trim()?.takeIf { it.isNotEmpty() && clean(it) } ?: return@mapNotNull null
                    val logos = team.logos.orEmpty().filter { logo -> logo.href?.let(::scalable) == true }
                    val logo = (logos.firstOrNull { "default" in it.rel.orEmpty() } ?: logos.firstOrNull())?.href?.trim()
                        ?: return@mapNotNull null
                    DirectoryTeam(
                        id = id,
                        displayName = name,
                        shortName = team.shortDisplayName.field(),
                        abbreviation = team.abbreviation.field(),
                        location = team.location.field(),
                        nickname = team.name.field(),
                        logo = logo
                    )
                }
                .distinctBy { it.id }
            return teams
        }

        // Only ESPN's /i/ PNGs: they go through its image combiner at the drawn size (a /guid/ logo cannot).
        private val SCALABLE = Regex("""^https?://a\.espncdn\.com/i/[A-Za-z0-9/._-]+\.png$""")

        private fun scalable(url: String) = SCALABLE.matches(url.trim())

        private fun clean(text: String) = text.none { it == '\t' || it == '\n' || it == '\r' }

        private fun String?.field(): String? = this?.trim()?.takeIf { it.isNotEmpty() && clean(it) }

        /** One line per team, tab-separated, after a header with the fetch time. */
        internal fun encode(teams: List<DirectoryTeam>, fetchedAt: Long): String = buildString {
            append(HEADER).append('\t').append(fetchedAt).append('\n')
            teams.forEach { t ->
                append(listOf(t.id, t.displayName, t.shortName, t.abbreviation, t.location, t.nickname, t.logo)
                    .joinToString("\t") { it.orEmpty() }).append('\n')
            }
        }

        /** [encode]d text back to (fetch time, teams); null when unreadable. */
        internal fun decode(text: String): Pair<Long, List<DirectoryTeam>>? {
            val lines = text.lines()
            val header = lines.firstOrNull()?.split('\t') ?: return null
            if (header.size != 2 || header[0] != HEADER) return null
            val fetchedAt = header[1].toLongOrNull() ?: return null
            val teams = lines.drop(1).mapNotNull { line ->
                val p = line.split('\t')
                if (p.size != 7 || p[0].isEmpty() || p[1].isEmpty() || !scalable(p[6])) return@mapNotNull null
                DirectoryTeam(p[0], p[1], p[2].ifEmpty { null }, p[3].ifEmpty { null }, p[4].ifEmpty { null }, p[5].ifEmpty { null }, p[6])
            }
            return if (teams.isEmpty()) null else fetchedAt to teams
        }

        private const val HEADER = "teams-v1"

        /** The app's directory, read through [TeamLogoBook.shared]. */
        val shared = TeamDirectory()
    }
}

/** The directory's files in app storage, one per league. Failure-silent: an unreadable file is a miss. */
class TeamDirectoryStore(private val dir: File) {
    private fun fileOf(league: EspnLeague) = File(dir, "${league.sport}__${league.league}.tsv")

    /** Every stored league's (league, fetch time, teams). */
    fun readAll(): List<Triple<EspnLeague, Long, List<DirectoryTeam>>> = runCatching {
        dir.listFiles { file -> file.isFile && file.name.endsWith(".tsv") }.orEmpty().mapNotNull { file ->
            val parts = file.name.removeSuffix(".tsv").split("__")
            if (parts.size != 2) return@mapNotNull null
            val league = EspnLeague(parts[0], parts[1]).takeIf(TeamDirectory::covers) ?: return@mapNotNull null
            val (at, teams) = runCatching { TeamDirectory.decode(file.readText()) }.getOrNull() ?: return@mapNotNull null
            Triple(league, at, teams)
        }.sortedBy { it.second }
    }.getOrDefault(emptyList())

    fun write(league: EspnLeague, teams: List<DirectoryTeam>, fetchedAt: Long) {
        runCatching {
            dir.mkdirs()
            val file = fileOf(league)
            val temp = File(dir, file.name + ".tmp")
            temp.writeText(TeamDirectory.encode(teams, fetchedAt))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
    }

    fun delete(league: EspnLeague) {
        runCatching { fileOf(league).delete() }
    }
}

/**
 * Keeps [directory] filled (#82): loads the stored lists once, then asks ESPN for each wanted league
 * whose list is missing or a week old, one league at a time. A failed league is not asked again for
 * [RETRY_MS]. The caller decides when it may run (never while Scores is Off).
 */
class TeamDirectorySync(
    val directory: TeamDirectory,
    private val fetch: suspend (EspnLeague) -> List<DirectoryTeam>?,
    private val store: TeamDirectoryStore?,
    private val now: () -> Long
) {
    private val mutex = Mutex()
    private var loaded = false
    private val lastAttempt = HashMap<String, Long>()

    /** Reads the stored lists into [directory], once. */
    suspend fun load() = mutex.withLock { loadLocked() }

    private fun loadLocked() {
        if (loaded) return
        loaded = true
        store?.readAll()?.forEach { (league, at, teams) ->
            directory.put(league, teams, at).forEach { store.delete(it) }
        }
    }

    /** Fetches the lists of [wanted] (at most [MAX_PER_PASS] of the listed leagues) that are missing or stale. */
    suspend fun refresh(wanted: Collection<EspnLeague>) = mutex.withLock {
        loadLocked()
        for (league in wanted.filter(TeamDirectory::covers).distinct().take(MAX_PER_PASS)) {
            val at = now()
            val fetched = directory.fetchedAt(league)
            if (fetched != null && at - fetched in 0 until TeamDirectory.REFRESH_MS) continue
            val tried = lastAttempt[league.path]
            if (tried != null && at - tried in 0 until RETRY_MS) continue
            // Recorded once it answered: a pass cancelled mid-request (Scores turned Off) asks again next time.
            val answer = fetch(league)
            if (lastAttempt.size >= MAX_ATTEMPTS_KEPT) lastAttempt.clear()
            lastAttempt[league.path] = at
            val teams = answer?.takeIf { it.isNotEmpty() } ?: continue
            val stamp = now()
            directory.put(league, teams, stamp).forEach { store?.delete(it) }
            store?.write(league, teams, stamp)
        }
    }

    companion object {
        /** A league whose list failed is asked again after this long. */
        const val RETRY_MS = 60 * 60_000L
        /** Leagues checked per pass, so a screen of many soccer leagues stays bounded. */
        const val MAX_PER_PASS = TeamDirectory.MAX_LEAGUES
        private const val MAX_ATTEMPTS_KEPT = 256
    }
}

// Tolerant DTOs for ESPN's `{sport}/{league}/teams` (#82): only what the directory reads.

@JsonClass(generateAdapter = true)
data class EspnTeamListDto(val sports: List<EspnTeamListSportDto>? = null)

@JsonClass(generateAdapter = true)
data class EspnTeamListSportDto(val leagues: List<EspnTeamListLeagueDto>? = null)

@JsonClass(generateAdapter = true)
data class EspnTeamListLeagueDto(val teams: List<EspnTeamListEntryDto>? = null)

@JsonClass(generateAdapter = true)
data class EspnTeamListEntryDto(val team: EspnTeamListTeamDto? = null)

@JsonClass(generateAdapter = true)
data class EspnTeamListTeamDto(
    val id: String? = null,
    val abbreviation: String? = null,
    val displayName: String? = null,
    val shortDisplayName: String? = null,
    val name: String? = null,
    val location: String? = null,
    val isActive: Boolean? = null,
    val logos: List<EspnTeamListLogoDto>? = null
)

@JsonClass(generateAdapter = true)
data class EspnTeamListLogoDto(val href: String? = null, val rel: List<String>? = null)
