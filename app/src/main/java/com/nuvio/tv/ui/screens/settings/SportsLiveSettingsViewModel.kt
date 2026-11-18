package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.sportslive.SportsLiveCatalogOptions
import com.nuvio.tv.core.sportslive.SportsLiveCatalogPolicy
import com.nuvio.tv.core.sportslive.SportsLiveOpenGame
import com.nuvio.tv.core.sportslive.SportsLiveSourceReliability
import com.nuvio.tv.core.sportslive.scores.EspnFeedHealth
import com.nuvio.tv.core.sportslive.scores.EspnFeedKey
import com.nuvio.tv.core.sportslive.scores.FeedHealth
import com.nuvio.tv.core.sportslive.scores.LiveScoresRepository
import com.nuvio.tv.core.sportslive.scores.MyLeague
import com.nuvio.tv.core.sportslive.scores.MySport
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.ScoreSettings
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.domain.model.CatalogOption
import com.nuvio.tv.domain.model.CatalogStats
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveClaim
import com.nuvio.tv.domain.model.SportsLiveOverride
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.SportsLiveRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One addon catalog as the Sports & Live settings list shows it. */
data class SportsLiveCatalogSetting(
    val key: String,
    val addonName: String,
    val catalogName: String,
    val apiType: String,
    /** What automatic classification would do, ignoring any override. */
    val autoClaim: SportsLiveClaim?,
    val override: SportsLiveOverride?,
    /** How this session's last load recognised the catalog's items; null unless it is claimed and was loaded. */
    val stats: CatalogStats? = null,
    /** Name of the extra the user can pin a value of; null when the catalog offers none or the section does not use it. */
    val filterExtra: String? = null,
    /** The values the addon offers for [filterExtra], exactly as it gives them. */
    val filterOptions: List<String> = emptyList(),
    /** The pinned value; null for All, including a pin the addon no longer offers. */
    val filterValue: String? = null
)

data class SportsLiveSettingsUiState(
    val showSection: Boolean = true,
    val showHomeRow: Boolean = true,
    val hoverPreview: Boolean = true,
    val dataSaver: Boolean = false,
    /** Our own picture-in-picture switch; the device's support is checked by the screen. */
    val pipEnabled: Boolean = true,
    val catalogs: List<SportsLiveCatalogSetting> = emptyList(),
    /** The active profile's followed teams, in follow order. */
    val followedTeams: List<FollowedTeam> = emptyList(),
    /** Scores (Show / Hide until focused / Off) and the score delay. */
    val scores: ScoreSettings = ScoreSettings(),
    /** My sports as picked (followed teams' sports are added where it is used, not here). */
    val mySports: MySports = MySports.DEFAULT,
    /** "When I open a game": Play best stream (default) / Let me choose. */
    val openGame: SportsLiveOpenGame = SportsLiveOpenGame.PLAY_BEST
)

@HiltViewModel
class SportsLiveSettingsViewModel @Inject constructor(
    private val prefs: SportsLivePreferencesDataStore,
    addonRepository: AddonRepository,
    private val sportsLiveRepository: SportsLiveRepository,
    playerSettingsDataStore: PlayerSettingsDataStore,
    health: EspnFeedHealth,
    private val liveScores: LiveScoresRepository
) : ViewModel() {

    /** ESPN feed health (#70), for Scores status; the screen works the status out on its clock. */
    val feedHealth: StateFlow<Map<EspnFeedKey, FeedHealth>> = health.feeds

    /** Scores status → Retry now: one immediate fetch of the scoreboards in use (none while Scores is Off). */
    fun retryScores() {
        liveScores.retryNow()
    }

    /** The "When I open a game" in effect: the stored choice, else the default for the player's autoplay mode. */
    private val openGame = combine(prefs.openGame, playerSettingsDataStore.playerSettings) { stored, player ->
        SportsLiveOpenGame.effective(
            stored,
            manualPlayer = player.streamAutoPlayModeChosen && player.streamAutoPlayMode == StreamAutoPlayMode.MANUAL
        )
    }

    private val claimedStats = combine(
        sportsLiveRepository.claimedCatalogKeys,
        sportsLiveRepository.catalogStats
    ) { keys, stats -> stats.filterKeys { it in keys } }

    val uiState: StateFlow<SportsLiveSettingsUiState> = combine(
        addonRepository.getInstalledAddons(),
        combine(prefs.overrides, prefs.catalogOptions, prefs.followedTeams, ::Triple),
        prefs.showSection,
        combine(prefs.showHomeRow, prefs.hoverPreview, prefs.dataSaver, ::Triple),
        combine(claimedStats, prefs.pipEnabled, combine(prefs.scoreSettings, prefs.mySports, openGame, ::Triple), ::Triple)
    ) { addons, (overrides, options, followedTeams), showSection, (showHomeRow, hoverPreview, dataSaver), (stats, pipEnabled, scoreAndSports) ->
        val (scores, mySports, openGame) = scoreAndSports
        val catalogs = addons.enabledAddons().flatMap { addon ->
            addon.catalogs.filter(SportsLiveCatalogPolicy::isClaimable).map { catalog ->
                val key = SportsLiveCatalogPolicy.keyFor(addon, catalog)
                val autoClaim = SportsLiveCatalogPolicy.classify(addon, catalog, emptyMap())
                val override = overrides[key]
                val claimed = override == SportsLiveOverride.INCLUDE || (override == null && autoClaim != null)
                // A pin only matters for catalogs the section loads; the stored pin is kept either way.
                val extra = if (claimed) SportsLiveCatalogOptions.eligibleExtra(catalog) else null
                SportsLiveCatalogSetting(
                    key = key,
                    addonName = addon.displayName,
                    catalogName = catalog.name,
                    apiType = catalog.apiType,
                    autoClaim = autoClaim,
                    override = override,
                    stats = stats[key],
                    filterExtra = extra?.name,
                    filterOptions = extra?.options.orEmpty(),
                    filterValue = extra?.let { SportsLiveCatalogOptions.effectiveOption(catalog, options[key])?.value }
                )
            }
        }
        SportsLiveSettingsUiState(showSection, showHomeRow, hoverPreview, dataSaver, pipEnabled, catalogs, followedTeams, scores, mySports, openGame)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SportsLiveSettingsUiState())

    fun setShowSection(show: Boolean) {
        viewModelScope.launch { prefs.setShowSection(show) }
    }

    fun setShowHomeRow(show: Boolean) {
        viewModelScope.launch { prefs.setShowHomeRow(show) }
    }

    fun setHoverPreview(enabled: Boolean) {
        viewModelScope.launch { prefs.setHoverPreview(enabled) }
    }

    fun setDataSaver(enabled: Boolean) {
        viewModelScope.launch { prefs.setDataSaver(enabled) }
    }

    fun setOpenGame(openGame: SportsLiveOpenGame) {
        viewModelScope.launch { prefs.setOpenGame(openGame) }
    }

    fun setPipEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setPipEnabled(enabled) }
    }

    fun setScoresMode(mode: ScoresMode) {
        viewModelScope.launch { prefs.setScoresMode(mode) }
    }

    fun setScoreDelaySeconds(seconds: Int) {
        viewModelScope.launch { prefs.setScoreDelaySeconds(seconds) }
    }

    private val _newSportsHint = MutableStateFlow(false)

    /** My sports shows "New: Tennis, UFC, F1, Golf" (once per profile: [SportsLivePreferencesDataStore.claimNewSportsHint]). */
    val newSportsHint: StateFlow<Boolean> = _newSportsHint.asStateFlow()

    private var newSportsHintClaimed = false

    /** The My sports picker shows: claims its one-time hint, once for this screen. */
    fun onMySportsShown() {
        if (newSportsHintClaimed) return
        newSportsHintClaimed = true
        viewModelScope.launch { _newSportsHint.value = prefs.claimNewSportsHint() }
    }

    /** My sports: [sport] on or off (the last sport stays on). */
    fun toggleMySport(sport: MySport) = updateMySports { it.toggleSport(sport) }

    /** My sports: [league] on or off within its sport. */
    fun toggleMyLeague(league: MyLeague) = updateMySports { it.toggleLeague(league) }

    /** My sports: soccer back to every competition. */
    fun allSoccerCompetitions() = updateMySports { it.allSoccerCompetitions() }

    private fun updateMySports(change: (MySports) -> MySports) {
        viewModelScope.launch { prefs.updateMySports(change) }
    }

    /** Forgets which stream sources worked (#40); every source starts neutral again. */
    fun resetStreamMemory() {
        SportsLiveSourceReliability.clear()
    }

    fun unfollow(team: FollowedTeam) {
        viewModelScope.launch { prefs.unfollow(team) }
    }

    /** Auto, then INCLUDE, then EXCLUDE, then back to Auto. */
    fun cycleOverride(key: String) {
        viewModelScope.launch {
            val next = when (prefs.overrides.first()[key]) {
                null -> SportsLiveOverride.INCLUDE
                SportsLiveOverride.INCLUDE -> SportsLiveOverride.EXCLUDE
                SportsLiveOverride.EXCLUDE -> null
            }
            prefs.setOverride(key, next)
        }
    }

    /**
     * Pins [value] of [extra] for the catalog [key] (null for All), then reloads so it applies now.
     * Channels catalogs whose pin did not change are carried over rather than refetched.
     */
    fun setCatalogFilter(key: String, extra: String, value: String?) {
        viewModelScope.launch {
            prefs.setCatalogOption(key, value?.let { CatalogOption(extra, it) })
            try {
                sportsLiveRepository.load(force = false, includeChannels = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The section loads with the new pin next time it opens.
            }
        }
    }
}
