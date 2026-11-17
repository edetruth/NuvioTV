package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.domain.model.FollowedTeam

/** Team sports have two sides and a score; individual sports (tennis, MMA, F1, golf: R2) have players and their own rows. */
enum class SportKind { TEAM, INDIVIDUAL }

/**
 * A sport the user can pick under Settings → Sports & Live → My sports (#55), in the order the picker
 * and the Results chips show them. [espnSport] is the ESPN sport key its feeds use. New sports (the
 * individual ones) are new entries here with their own [SportKind] and leagues.
 */
enum class MySport(val id: String, val kind: SportKind, val espnSport: String) {
    BASKETBALL("basketball", SportKind.TEAM, "basketball"),
    FOOTBALL("football", SportKind.TEAM, "football"),
    HOCKEY("hockey", SportKind.TEAM, "hockey"),
    BASEBALL("baseball", SportKind.TEAM, "baseball"),
    SOCCER("soccer", SportKind.TEAM, "soccer");

    /** Its leagues, in picker order. */
    val leagues: List<MyLeague> get() = MyLeague.entries.filter { it.sport == this }

    /** The leagues a sport picked without naming leagues stands for. Soccer: every competition (none named). */
    val defaultLeagues: Set<MyLeague> get() = leagues.filterTo(LinkedHashSet()) { it.isDefault }

    companion object {
        fun ofId(id: String): MySport? = entries.firstOrNull { it.id == id }
        fun ofEspn(sport: String): MySport? = entries.firstOrNull { it.espnSport == sport }
    }
}

/**
 * A league or competition inside a [MySport]. A non-soccer league has its own ESPN [feed]; a soccer
 * competition has none, as `soccer/all` lists every soccer game, and is told apart by the event's
 * league slug (or, for internationals, its competition name).
 */
enum class MyLeague(
    val id: String,
    val sport: MySport,
    val feed: EspnLeague?,
    val isDefault: Boolean,
    private val slugs: Regex? = null,
    private val names: Regex? = null
) {
    NBA("nba", MySport.BASKETBALL, EspnLeagueMap.NBA, isDefault = true),
    COLLEGE_BASKETBALL("college-basketball", MySport.BASKETBALL, EspnLeagueMap.COLLEGE_BASKETBALL, isDefault = false),
    NFL("nfl", MySport.FOOTBALL, EspnLeagueMap.NFL, isDefault = true),
    COLLEGE_FOOTBALL("college-football", MySport.FOOTBALL, EspnLeagueMap.COLLEGE_FOOTBALL, isDefault = false),
    NHL("nhl", MySport.HOCKEY, EspnLeagueMap.NHL, isDefault = true),
    MLB("mlb", MySport.BASEBALL, EspnLeagueMap.MLB, isDefault = true),

    // National teams: FIFA (World Cup, qualifiers, friendlies), Nations Leagues, Euros, AFCON, Gold Cup,
    // Copa América, Asian Cup. "fifa.cwc" is the Club World Cup, not national teams.
    INTERNATIONALS(
        "internationals", MySport.SOCCER, null, isDefault = false,
        slugs = Regex("""^(fifa\.(?!cwc)|uefa\.(w?nations|w?euro(?!pa))|caf\.nations|concacaf\.(nations|gold)|conmebol\.america|afc\.asian|ofc\.nations)"""),
        names = Regex("""\binternational\b""", RegexOption.IGNORE_CASE)
    ),
    CHAMPIONS_LEAGUE("uefa.champions", MySport.SOCCER, null, isDefault = false, slugs = Regex("""^uefa\.champions$""")),
    EUROPA_LEAGUE("uefa.europa", MySport.SOCCER, null, isDefault = false, slugs = Regex("""^uefa\.europa$""")),
    CONFERENCE_LEAGUE("uefa.europa.conf", MySport.SOCCER, null, isDefault = false, slugs = Regex("""^uefa\.europa\.conf$""")),
    PREMIER_LEAGUE("eng.1", MySport.SOCCER, null, isDefault = false, slugs = Regex("""^eng\.1$""")),
    LA_LIGA("esp.1", MySport.SOCCER, null, isDefault = false, slugs = Regex("""^esp\.1$""")),
    BUNDESLIGA("ger.1", MySport.SOCCER, null, isDefault = false, slugs = Regex("""^ger\.1$""")),
    SERIE_A("ita.1", MySport.SOCCER, null, isDefault = false, slugs = Regex("""^ita\.1$""")),
    LIGUE_1("fra.1", MySport.SOCCER, null, isDefault = false, slugs = Regex("""^fra\.1$""")),
    MLS("usa.1", MySport.SOCCER, null, isDefault = false, slugs = Regex("""^usa\.1$"""));

    /** True when [event] is a game of this league: its feed, or for soccer its slug / competition name. */
    fun covers(event: ScoreSnapshot): Boolean {
        if (event.league.sport != sport.espnSport) return false
        feed?.let { return event.league == it }
        return slugs?.containsMatchIn(event.leagueSlug) == true ||
            (names != null && event.leagueName?.let(names::containsMatchIn) == true)
    }

    /** The stored token: "basketball/nba". */
    val token: String get() = "${sport.id}/$id"

    companion object {
        fun ofToken(token: String): MyLeague? = entries.firstOrNull { it.token == token }
        fun ofFeed(feed: EspnLeague): MyLeague? = entries.firstOrNull { it.feed == feed }
    }
}

/**
 * The sports (and, optionally, leagues) of one profile. A sport mapped to an empty set stands for its
 * [MySport.defaultLeagues] (soccer: every competition). An empty selection is the default: every sport.
 */
data class MySports(val sports: Map<MySport, Set<MyLeague>>) {

    /** True when nothing was picked: every supported sport, each with its default leagues. */
    val isDefault: Boolean get() = sports.isEmpty()

    /** The sports in effect, in [MySport] order. */
    val effectiveSports: List<MySport>
        get() = if (isDefault) MySport.entries.filter { it.kind == SportKind.TEAM } else MySport.entries.filter { it in sports }

    /** The leagues named for [sport]: empty when it uses its defaults (or is not picked). */
    fun namedLeagues(sport: MySport): Set<MyLeague> = sports[sport].orEmpty()

    /** [sport]'s leagues in effect: those named, else its defaults; empty when the sport is not picked. */
    fun leaguesOf(sport: MySport): Set<MyLeague> =
        if (sport !in effectiveSports) emptySet() else namedLeagues(sport).ifEmpty { sport.defaultLeagues }

    /**
     * The ESPN feeds a Results day fetches: `soccer/all` once when soccer is in, then each non-soccer
     * league in effect (NBA, NFL, NHL, MLB by default; college only when picked).
     */
    fun feeds(): List<EspnLeague> {
        val soccer = if (MySport.SOCCER in effectiveSports) listOf(EspnLeagueMap.SOCCER_ALL) else emptyList()
        return soccer + effectiveSports.filter { it != MySport.SOCCER }.flatMap { leaguesOf(it) }.mapNotNull { it.feed }
    }

    /**
     * True when [event] is in My sports: its sport is in and, when that sport names leagues, one of them
     * covers it. Soccer with no competition named takes every soccer game.
     */
    fun includes(event: ScoreSnapshot): Boolean {
        val sport = MySport.ofEspn(event.league.sport) ?: return false
        if (sport !in effectiveSports) return false
        val named = namedLeagues(sport)
        if (named.isEmpty() && sport == MySport.SOCCER) return true
        return leaguesOf(sport).any { it.covers(event) }
    }

    /** As a stored set: a token per sport ("soccer") and per named league ("basketball/nba"). */
    fun encode(): Set<String> = sports.flatMapTo(LinkedHashSet()) { (sport, leagues) -> listOf(sport.id) + leagues.map { it.token } }

    /** [sport] switched on or off. The last sport stays on (an empty set would mean every sport). */
    fun toggleSport(sport: MySport): MySports {
        val current = effectiveSports.associateWith { namedLeagues(it) }
        val next = if (sport in current) current - sport else current + (sport to emptySet())
        return if (next.isEmpty()) this else MySports(next)
    }

    /**
     * [league] switched on or off within its sport (which is switched on if it was off). A non-soccer
     * sport keeps at least one league (the sport's own switch turns it off); soccer with no competition
     * left is back to every competition.
     */
    fun toggleLeague(league: MyLeague): MySports {
        val sport = league.sport
        val current = effectiveSports.associateWith { namedLeagues(it) }.toMutableMap()
        val inEffect = if (sport in current) {
            if (sport == MySport.SOCCER) namedLeagues(sport) else leaguesOf(sport)
        } else {
            emptySet()
        }
        val leagues = if (league in inEffect && sport in current) inEffect - league else inEffect + league
        if (leagues.isEmpty() && sport != MySport.SOCCER) return this
        current[sport] = leagues
        return MySports(current)
    }

    /** Soccer back to every competition. */
    fun allSoccerCompetitions(): MySports =
        if (MySport.SOCCER !in effectiveSports) this
        else MySports(effectiveSports.associateWith { if (it == MySport.SOCCER) emptySet() else namedLeagues(it) })

    /**
     * With followed teams' sports added (#55: following a team brings its sport): a team routed to a
     * league ([EspnLeagueMap]) adds that league to its sport; a soccer team adds soccer (every
     * competition when soccer was off). A team with no routed sport adds nothing.
     */
    fun withFollowed(teams: List<FollowedTeam>): MySports {
        if (teams.isEmpty()) return this
        val result = effectiveSports.associateWith { namedLeagues(it) }.toMutableMap()
        var changed = false
        for (team in teams) {
            val feeds = team.sport?.let { EspnLeagueMap.leaguesFor(sport = it, hints = emptyList()) }.orEmpty()
            for (feed in feeds) {
                if (feed == EspnLeagueMap.SOCCER_ALL) {
                    if (MySport.SOCCER !in result) {
                        result[MySport.SOCCER] = emptySet()
                        changed = true
                    }
                    continue
                }
                val league = MyLeague.ofFeed(feed) ?: continue
                val sport = league.sport
                val inEffect = if (sport in result) result.getValue(sport).ifEmpty { sport.defaultLeagues } else emptySet()
                if (league !in inEffect) {
                    result[sport] = inEffect + league
                    changed = true
                }
            }
        }
        return if (changed) MySports(result) else this
    }

    companion object {
        val DEFAULT = MySports(emptyMap())

        /** From the stored set; unknown tokens are skipped (a later version's sports). A league brings its sport. */
        fun decode(tokens: Set<String>?): MySports {
            if (tokens.isNullOrEmpty()) return DEFAULT
            val sports = LinkedHashMap<MySport, Set<MyLeague>>()
            tokens.mapNotNull(MySport::ofId).forEach { sports[it] = emptySet() }
            tokens.mapNotNull(MyLeague::ofToken).forEach { league -> sports[league.sport] = sports[league.sport].orEmpty() + league }
            return MySports(MySport.entries.filter { it in sports }.associateWith { sports.getValue(it) })
        }
    }
}
