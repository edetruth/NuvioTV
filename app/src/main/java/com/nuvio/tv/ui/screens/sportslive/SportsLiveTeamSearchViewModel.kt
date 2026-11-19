package com.nuvio.tv.ui.screens.sportslive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.sportslive.scores.MyLeague
import com.nuvio.tv.core.sportslive.scores.MySport
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.TeamDirectory
import com.nuvio.tv.core.sportslive.scores.TeamFollows
import com.nuvio.tv.core.sportslive.scores.TeamGroup
import com.nuvio.tv.core.sportslive.scores.TeamHit
import com.nuvio.tv.core.sportslive.scores.TeamListFill
import com.nuvio.tv.core.sportslive.scores.TeamListResult
import com.nuvio.tv.core.sportslive.scores.TeamSearch
import com.nuvio.tv.core.sportslive.scores.TeamSearchIndex
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.domain.model.FollowedTeam
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

/** Team search (#149): what the screen draws. */
data class TeamSearchUiState(
    val query: String = "",
    /** The sport filter; null = All. */
    val sport: MySport? = null,
    /** The filter's sports after All. */
    val sports: List<MySport> = TeamSearch.SPORTS,
    /** The matches while the field has text. */
    val hits: List<TeamHit> = emptyList(),
    /** The teams A–Z by league while the field is empty. */
    val groups: List<TeamGroup> = emptyList(),
    /** The league whose list is being fetched now. */
    val filling: MyLeague? = null,
    /** Nothing listable for the filter, and ESPN did not answer. */
    val offline: Boolean = false,
    /** Followed teams, in the order they were followed. */
    val followed: List<FollowedTeam> = emptyList(),
    /** Followed on this visit: tagged "New" in Your teams. */
    val newKeys: Set<String> = emptySet(),
    /** Logos for followed teams stored without one, from the directory, by [TeamSearchState.followKey]. */
    val logos: Map<String, String> = emptyMap()
) {
    val browsing: Boolean get() = query.isBlank()

    /** True when nothing is listed for the filter yet and a list is on its way: skeleton rows. */
    val firstFill: Boolean get() = filling != null && groups.isEmpty() && hits.isEmpty() && browsing

    fun isFollowed(hit: TeamHit): Boolean =
        TeamFollows.followersOf(followed, TeamSearch.teamList(hit.league), hit.team).isNotEmpty()

    fun isNew(team: FollowedTeam): Boolean = TeamSearchState.followKey(team) in newKeys

    fun logoOf(team: FollowedTeam): String? = team.logo ?: logos[TeamSearchState.followKey(team)]
}

/** The pure parts of team search's state. */
internal object TeamSearchState {
    /** A followed entry's identity, as the prefs store compares them: name and sport, ignoring case. */
    fun followKey(team: FollowedTeam): String = "${team.name.trim().lowercase()}|${team.sport?.trim()?.lowercase().orEmpty()}"

    /** The filter's sports after All (#149: it defaults to My sports): My sports' team sports, else every one. */
    fun filterSports(mySports: MySports): List<MySport> {
        val mine = mySports.effectiveSports.toSet()
        return TeamSearch.SPORTS.filter { it in mine }.ifEmpty { TeamSearch.SPORTS }
    }

    /** The order leagues are filled in: My sports' leagues first, then the rest. */
    fun fillOrder(sport: MySport?, mySports: MySports): List<MyLeague> {
        val mine = mySports.effectiveSports.flatMapTo(HashSet()) { mySports.leaguesOf(it) }
        val leagues = TeamSearch.leaguesOf(sport)
        return leagues.filter { it in mine } + leagues.filterNot { it in mine }
    }

    /** Logos for [followed] entries stored without one: their ESPN id's team in [index]. */
    fun logos(followed: List<FollowedTeam>, index: TeamSearchIndex): Map<String, String> {
        val missing = followed.filter { it.logo == null && it.espnId != null }
        if (missing.isEmpty()) return emptyMap()
        val all = TeamSearch.browse(index).flatMap { it.teams }
        return missing.mapNotNull { f ->
            val hit = all.firstOrNull { TeamFollows.followersOf(listOf(f), TeamSearch.teamList(it.league), it.team).isNotEmpty() }
            hit?.let { followKey(f) to it.team.logo }
        }.toMap()
    }
}

/**
 * Team search (#149): the letter grid's text and the sport filter search the on-device team directory;
 * the leagues in view are filled on demand ([TeamListFill]); OK follows in place.
 */
@HiltViewModel
class SportsLiveTeamSearchViewModel internal constructor(
    private val prefs: SportsLivePreferencesDataStore,
    private val fill: TeamListFill,
    private val directory: TeamDirectory,
    private val work: CoroutineContext
) : ViewModel() {

    @Inject
    constructor(prefs: SportsLivePreferencesDataStore, fill: TeamListFill) :
        this(prefs, fill, TeamDirectory.shared, Dispatchers.Default)

    private val query = MutableStateFlow("")
    private val sport = MutableStateFlow<MySport?>(null)
    private val index = MutableStateFlow(TeamSearch.index(emptyMap()))
    private val filling = MutableStateFlow<MyLeague?>(null)
    private val failed = MutableStateFlow(false)
    private val newKeys = MutableStateFlow<Set<String>>(emptySet())
    private var fillJob: Job? = null

    /** The query and filter with what they list, worked out together off the main thread. */
    private class Listed(val query: String, val sport: MySport?, val hits: List<TeamHit>, val groups: List<TeamGroup>, val any: Boolean)

    private val listed: Flow<Listed> =
        combine(query, sport, index) { q, s, idx ->
            val browsing = q.isBlank()
            Listed(
                query = q,
                sport = s,
                hits = if (browsing) emptyList() else TeamSearch.search(idx, q, s),
                groups = if (browsing) TeamSearch.browse(idx, s) else emptyList(),
                any = idx.hasTeams(s)
            )
        }.flowOn(work)

    private val followState = combine(prefs.followedTeams, newKeys, index) { f, n, idx -> Triple(f, n, TeamSearchState.logos(f, idx)) }
        .flowOn(work)

    val uiState: StateFlow<TeamSearchUiState> = combine(
        listed,
        combine(filling, failed, ::Pair),
        followState,
        prefs.mySports
    ) { list, (fillingNow, failedNow), (followed, fresh, logos), mySports ->
        TeamSearchUiState(
            query = list.query,
            sport = list.sport,
            sports = TeamSearchState.filterSports(mySports),
            hits = list.hits,
            groups = list.groups,
            filling = fillingNow,
            offline = !list.any && failedNow && fillingNow == null,
            followed = followed,
            newKeys = fresh,
            logos = logos
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TeamSearchUiState())

    init {
        refill()
    }

    fun type(text: String) {
        query.update { (it + text).take(MAX_QUERY) }
    }

    fun deleteLetter() {
        query.update { it.dropLast(1) }
    }

    fun clear() {
        query.value = ""
    }

    /** The recognised words of a voice search replace the field. */
    fun setQuery(text: String) {
        query.value = text.take(MAX_QUERY)
    }

    fun selectSport(next: MySport?) {
        if (sport.value == next) return
        sport.value = next
        refill()
    }

    /** Fetches the filter's leagues not kept yet (or a week old), one at a time; the index grows as they land. */
    fun refill() {
        fillJob?.cancel()
        fillJob = viewModelScope.launch {
            failed.value = false
            index.value = withContext(work) { TeamSearch.index(directory) }
            val order = TeamSearchState.fillOrder(sport.value, prefs.mySports.first())
            for (league in order) {
                filling.value = league
                val result = fill.ensure(TeamSearch.teamList(league))
                if (result is TeamListResult.Failed || result is TeamListResult.Stale) failed.value = true
                if (result !is TeamListResult.Cached) index.value = withContext(work) { TeamSearch.index(directory) }
            }
            filling.value = null
        }
    }

    /** OK on a result: follows it, or stops following it. Rows stay where they are. */
    fun toggle(hit: TeamHit) {
        val list = TeamSearch.teamList(hit.league)
        viewModelScope.launch {
            val current = prefs.followedTeams.first()
            val followers = TeamFollows.followersOf(current, list, hit.team)
            if (followers.isNotEmpty()) {
                followers.forEach { prefs.unfollow(it) }
            } else {
                val team = TeamFollows.teamToFollow(list, hit.team)
                prefs.follow(team)
                newKeys.update { it + TeamSearchState.followKey(team) }
            }
        }
    }

    /** OK in Your teams: stops following [team]. */
    fun unfollow(team: FollowedTeam) {
        viewModelScope.launch { prefs.unfollow(team) }
    }

    private fun TeamSearchIndex.hasTeams(sport: MySport?): Boolean =
        if (sport == null) !isEmpty else leagues.any { it.sport == sport }

    companion object {
        const val MAX_QUERY = 40
    }
}
