package com.nuvio.tv.core.sportslive.identify

import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.squareup.moshi.Moshi

/**
 * Real answers of 2026-11-19 (#168), trimmed to the fields read: ESPN's search with `type=team`, and
 * TheSportsDB's `searchteams.php` on key "123" (descriptions and social links dropped).
 */
internal object TeamLookupFixtures {
    val moshi: Moshi = Moshi.Builder().build()

    /** ESPN "Maccabi Tel Aviv": the soccer club only. */
    const val ESPN_MACCABI_TEL_AVIV = """{"totalFound": 1, "resultTypes": [{"totalFound": 4, "type": "team", "displayName": "Teams"}, {"totalFound": 899, "type": "article", "displayName": "Articles"}, {"totalFound": 7, "type": "clips", "displayName": "Clips"}], "results": [{"type": "team", "totalFound": 4, "page": 1, "limit": 10, "displayName": "Teams", "contents": [{"id": "ab8b44f7-a6d9-76d0-ccdc-acfcd9345344", "uid": "s:600~t:524", "type": "team", "displayName": "Maccabi Tel-Aviv", "subtitle": "Israeli Premier League", "image": {"default": "https://a.espncdn.com/i/teamlogos/soccer/500/524.png", "defaultDark": "https://a.espncdn.com/i/teamlogos/soccer/500-dark/524.png"}, "defaultLeagueSlug": "isr.1", "sport": "soccer"}]}]}"""

    /** ESPN "Rapid Bucuresti": the soccer club (TheSportsDB answers its rugby team). */
    const val ESPN_RAPID_BUCURESTI = """{"totalFound": 1, "didYouMean": "rabid bucuresti", "resultTypes": [{"totalFound": 2, "type": "team", "displayName": "Teams"}, {"totalFound": 19839, "type": "article", "displayName": "Articles"}, {"totalFound": 10, "type": "clips", "displayName": "Clips"}], "results": [{"type": "team", "totalFound": 2, "page": 1, "limit": 5, "displayName": "Teams", "contents": [{"id": "17b88043-66d4-37bf-d455-76587fdd5c29", "uid": "s:600~t:545", "type": "team", "displayName": "Rapid Bucuresti", "subtitle": "Romanian Liga 1", "image": {"default": "https://a.espncdn.com/i/teamlogos/soccer/500/545.png", "defaultDark": "https://a.espncdn.com/i/teamlogos/soccer/500-dark/545.png"}, "defaultLeagueSlug": "rou.1", "sport": "soccer"}]}]}"""

    /** ESPN "Dubai Basketball": articles only, no team. */
    const val ESPN_DUBAI_BASKETBALL = """{"resultTypes": [{"totalFound": 144316, "type": "article", "displayName": "Articles"}]}"""

    /** ESPN "BC Dubai": articles only, no team. */
    const val ESPN_BC_DUBAI = """{"resultTypes": [{"totalFound": 33821, "type": "article", "displayName": "Articles"}]}"""

    /** TheSportsDB "Dubai Basketball". */
    const val SDB_DUBAI_BASKETBALL = """{"teams": [{"idTeam": "148701", "strTeam": "Dubai Basketball", "strTeamAlternate": "BC Dubai", "strTeamShort": "", "strSport": "Basketball", "strLeague": "Adriatic ABA League", "strLeague2": "EuroLeague Basketball", "strLeague3": "", "strLeague4": "", "strLeague5": "", "strLeague6": "", "strLeague7": "", "strGender": "Male", "strCountry": "United Arab Emirates", "strBadge": "https://r2.thesportsdb.com/images/media/team/badge/fgtnti1758215967.png"}]}"""

    /** TheSportsDB "BC Dubai" (and "Dubai BC"): a loose match, the soccer club Dubai City. */
    const val SDB_BC_DUBAI = """{"teams": [{"idTeam": "157559", "strTeam": "Dubai City", "strTeamAlternate": "نادي مدينة دبي لكرة القدم, Dubai City Football Club", "strTeamShort": "", "strSport": "Soccer", "strLeague": "UAE First Division League", "strLeague2": "", "strLeague3": "", "strLeague4": "", "strLeague5": "", "strLeague6": "", "strLeague7": "", "strGender": "Male", "strCountry": "United Arab Emirates", "strBadge": "https://r2.thesportsdb.com/images/media/team/badge/xd88501793386483.png"}]}"""

    /** TheSportsDB "Maccabi Tel Aviv": one team per name on the free key, the soccer club. */
    const val SDB_MACCABI_TEL_AVIV = """{"teams": [{"idTeam": "134315", "strTeam": "Maccabi Tel Aviv", "strTeamAlternate": "MTAFC, FCMTA, מועדון כדורגל מכבי תל אביב", "strTeamShort": "", "strSport": "Soccer", "strLeague": "Israeli Premier League", "strLeague2": "UEFA Europa League", "strLeague3": "UEFA Conference League", "strLeague4": "", "strLeague5": "", "strLeague6": "", "strLeague7": "", "strGender": "Male", "strCountry": "Israel", "strBadge": "https://r2.thesportsdb.com/images/media/team/badge/oeer261781239315.png"}]}"""

    /** TheSportsDB "Maccabi Tel Aviv BC": the basketball team. */
    const val SDB_MACCABI_TEL_AVIV_BC = """{"teams": [{"idTeam": "136065", "strTeam": "Maccabi Tel Aviv BC", "strTeamAlternate": "Maccabi Tel Aviv", "strTeamShort": "", "strSport": "Basketball", "strLeague": "Israeli Basketball Premier League", "strLeague2": "EuroLeague Basketball", "strLeague3": "Israeli Basketball State Cup", "strLeague4": "", "strLeague5": "", "strLeague6": "", "strLeague7": "", "strGender": "Male", "strCountry": "Israel", "strBadge": "https://r2.thesportsdb.com/images/media/team/badge/z0mk1l1789281457.png"}]}"""

    /** TheSportsDB "Rapid Bucuresti": the rugby team, "Rapid București". */
    const val SDB_RAPID_BUCURESTI = """{"teams": [{"idTeam": "152120", "strTeam": "Rapid București", "strTeamAlternate": "Clubul Sportiv Rapid București, CS Rapid București, Rapid Bucharest XV", "strTeamShort": "", "strSport": "Rugby", "strLeague": "Romanian SuperLiga", "strLeague2": "", "strLeague3": "", "strLeague4": "", "strLeague5": "", "strLeague6": "", "strLeague7": "", "strGender": "Male", "strCountry": "Romania", "strBadge": "https://r2.thesportsdb.com/images/media/team/badge/57pei61747227915.png"}]}"""

    /** TheSportsDB with no such team. */
    const val SDB_NONE = """{"teams":null}"""

    fun espn(json: String): List<TeamCandidate> = EspnTeamSearch.parse(json, moshi)!!

    fun sportsDb(json: String): List<TeamCandidate> = SportsDbTeamSearch.parse(json, moshi)!!

    /** A source answering from [answers] by exact query; a missing query is [fallback], null fails. */
    class FakeSource(
        private val answers: Map<String, String?>,
        private val parse: (String) -> List<TeamCandidate>,
        private val fallback: String?
    ) : TeamLookupSource {
        val asked = ArrayList<String>()
        var failNext = 0
        var rateLimitNext = false

        override suspend fun search(name: String): TeamSearchResult {
            asked += name
            if (rateLimitNext) {
                rateLimitNext = false
                return TeamSearchResult.Failed(rateLimited = true)
            }
            if (failNext > 0) {
                failNext--
                return TeamSearchResult.Failed()
            }
            val body = if (answers.containsKey(name)) answers[name] else fallback
            return body?.let { TeamSearchResult.Found(parse(it)) } ?: TeamSearchResult.Failed()
        }
    }

    fun espnSource() = FakeSource(
        mapOf(
            "Maccabi Tel Aviv" to ESPN_MACCABI_TEL_AVIV,
            "Rapid Bucuresti" to ESPN_RAPID_BUCURESTI,
            "Dubai Basketball" to ESPN_DUBAI_BASKETBALL,
            "BC Dubai" to ESPN_BC_DUBAI
        ),
        ::espn,
        fallback = ESPN_BC_DUBAI
    )

    fun sportsDbSource() = FakeSource(
        mapOf(
            "Dubai Basketball" to SDB_DUBAI_BASKETBALL,
            "BC Dubai" to SDB_BC_DUBAI,
            "Dubai BC" to SDB_BC_DUBAI,
            "Maccabi Tel Aviv" to SDB_MACCABI_TEL_AVIV,
            "Maccabi Tel Aviv BC" to SDB_MACCABI_TEL_AVIV_BC,
            "Rapid Bucuresti" to SDB_RAPID_BUCURESTI
        ),
        ::sportsDb,
        fallback = SDB_NONE
    )

    fun game(
        name: String,
        sport: String? = null,
        isLive: Boolean = true,
        startMs: Long? = null,
        isChannel: Boolean = false,
        genres: List<String> = emptyList()
    ) = SportsLiveItem(
        meta = MetaPreview(
            id = name, type = ContentType.UNKNOWN, rawType = "tv", name = name, poster = null,
            posterShape = PosterShape.LANDSCAPE, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = genres
        ),
        addonId = "addon", addonName = "Sports", addonBaseUrl = "https://example.invalid", catalogKey = "addon:tv:live",
        catalogName = "Events", kind = SportsLiveKind.EVENTS, isLive = isLive, isChannel = isChannel,
        startMs = startMs, sport = sport, startHasTimeOfDay = startMs != null,
        teams = SportsLiveTeams.parse(name)
    )
}
