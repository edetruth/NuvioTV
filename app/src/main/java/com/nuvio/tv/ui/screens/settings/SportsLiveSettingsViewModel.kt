package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.sportslive.SportsLiveCatalogOptions
import com.nuvio.tv.core.sportslive.SportsLiveCatalogPolicy
import com.nuvio.tv.core.sportslive.SportsLiveHiddenTitles
import com.nuvio.tv.core.sportslive.SportsLiveOpenGame
import com.nuvio.tv.core.sportslive.SportsLiveSourceReliability
import com.nuvio.tv.core.sportslive.alerts.SportsLiveAlertSettings
import com.nuvio.tv.core.sportslive.scores.EspnFeedHealth
import com.nuvio.tv.core.sportslive.scores.EspnFeedKey
import com.nuvio.tv.core.sportslive.scores.FeedHealth
import com.nuvio.tv.core.sportslive.scores.LiveScoresRepository
import com.nuvio.tv.core.sportslive.scores.MyLeague
import com.nuvio.tv.core.sportslive.scores.MySport
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.ScoreSettings
import com.nuvio.tv.core.sportslive.scores.ScoresChoice
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
import kotlinx.coroutines.flow.map
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
    /** Scores (stored mode: Show / Hide until I reveal / Never show) and the score delay. */
    val scores: ScoreSettings = ScoreSettings(),
    /** My sports as picked (followed teams' sports are added where it is used, not here). */
    val mySports: MySports = MySports.DEFAULT,
    /** "When I open a game": Play best stream (default) / Let me choose. */
    val openGame: SportsLiveOpenGame = SportsLiveOpenGame.PLAY_BEST,
    /** The Catch up row (#58) on Home and Live Now. */
    val catchUpRow: Boolean = true,
    /** "Hide links this TV can't play" (#166). */
    val hideUnplayable: Boolean = true,
    /** Scores as the screen shows it (research item 5): the mode and "Hide scores for my teams only". */
    val scoresChoice: ScoresChoice = ScoresChoice(),
    /** "Hide my teams' results until I reveal" (M2): on until the profile sets it. */
    val hideMyTeamsResults: Boolean = true
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
        combine(prefs.overrides, prefs.catalogOptions, combine(prefs.followedTeams, prefs.catchUpRow, combine(prefs.hideUnplayable, prefs.scoresChoice, prefs.hideMyTeamsResults, ::Triple), ::Triple), ::Triple),
        prefs.showSection,
        combine(prefs.showHomeRow, prefs.hoverPreview, prefs.dataSaver, ::Triple),
        combine(claimedStats, prefs.pipEnabled, combine(prefs.scoreSettings, prefs.mySports, openGame, ::Triple), ::Triple)
    ) { addons, (overrides, options, teamsAndCatchUp), showSection, (showHomeRow, hoverPreview, dataSaver), (stats, pipEnabled, scoreAndSports) ->
        val (scores, mySports, openGame) = scoreAndSports
        val (followedTeams, catchUpRow, hides) = teamsAndCatchUp
        val (hideUnplayable, scoresChoice, hideMyTeamsResults) = hides
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
        SportsLiveSettingsUiState(showSection, showHomeRow, hoverPreview, dataSaver, pipEnabled, catalogs, followedTeams, scores, mySports, openGame, catchUpRow, hideUnplayable, scoresChoice, hideMyTeamsResults)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SportsLiveSettingsUiState())

    /** A switch row of the screen (#72) set to [on], through the same setter (and pref key) as before. */
    internal fun setSwitch(setting: SportsLiveSetting, on: Boolean) {
        when (setting) {
            SportsLiveSetting.SHOW_SECTION -> setShowSection(on)
            SportsLiveSetting.HOME_ROW -> setShowHomeRow(on)
            SportsLiveSetting.CATCH_UP_ROW -> setCatchUpRow(on)
            SportsLiveSetting.HOVER_PREVIEW -> setHoverPreview(on)
            SportsLiveSetting.DATA_SAVER -> setDataSaver(on)
            SportsLiveSetting.PICTURE_IN_PICTURE -> setPipEnabled(on)
            SportsLiveSetting.HIDE_UNPLAYABLE -> setHideUnplayable(on)
            SportsLiveSetting.REMIND_MY_TEAMS -> setRemindMyTeams(on)
            SportsLiveSetting.SCORE_ALERTS -> setScoreAlerts(on)
            SportsLiveSetting.FULL_TIME_ALERTS -> setFullTimeAlerts(on)
            SportsLiveSetting.CLOSE_GAME_ALERTS -> viewModelScope.launch { prefs.setCloseGameAlerts(on) }
            SportsLiveSetting.MY_TEAMS_SPOILERS -> viewModelScope.launch { prefs.updateScoresChoice { it.withMyTeamsOnly(on) } }
            SportsLiveSetting.REAL_TIME_SCORES -> viewModelScope.launch { prefs.setRealTimeScores(on) }
            SportsLiveSetting.MY_TEAMS_RESULTS -> viewModelScope.launch { prefs.setHideMyTeamsResults(on) }
            // Not switches: pickers, lists and actions with their own calls.
            SportsLiveSetting.MY_SPORTS, SportsLiveSetting.MY_TEAMS, SportsLiveSetting.SCORES,
            SportsLiveSetting.SCORE_DELAY, SportsLiveSetting.OPEN_GAME, SportsLiveSetting.SCORES_STATUS,
            SportsLiveSetting.RESET_STREAM_MEMORY, SportsLiveSetting.RUN_SETUP, SportsLiveSetting.HIDDEN, SportsLiveSetting.CATALOGS,
            SportsLiveSetting.GAME_END, SportsLiveSetting.REMINDED_TEAMS, SportsLiveSetting.TIP_OFF, SportsLiveSetting.OK_ON_LIVE,
            SportsLiveSetting.REPORT_PROBLEM, SportsLiveSetting.GUIDE_TABLE -> Unit
        }
    }

    fun setShowSection(show: Boolean) {
        viewModelScope.launch { prefs.setShowSection(show) }
    }

    fun setShowHomeRow(show: Boolean) {
        viewModelScope.launch { prefs.setShowHomeRow(show) }
    }

    fun setCatchUpRow(enabled: Boolean) {
        viewModelScope.launch { prefs.setCatchUpRow(enabled) }
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

    fun setHideUnplayable(hide: Boolean) {
        viewModelScope.launch { prefs.setHideUnplayable(hide) }
    }

    /** Alerts (#42, #44): Remind me for my teams, Score alerts, Full-time alerts. */
    val alertSettings: StateFlow<SportsLiveAlertSettings> =
        prefs.alertSettings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SportsLiveAlertSettings())

    /** Titles hidden from Live Now (#134), A–Z: Settings → More → Hidden lists them, and OK shows one again. */
    val hiddenTitles: StateFlow<List<String>> = prefs.hiddenTitles
        .map(SportsLiveHiddenTitles::sorted)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun unhide(title: String) {
        viewModelScope.launch { prefs.unhideTitle(title) }
    }

    /** Reminder-only teams (#154), in the order added. */
    val remindedTeams: StateFlow<List<com.nuvio.tv.domain.model.FollowedTeam>> = prefs.reminders
        .map { it.remindedTeams }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** OK on a reminder-only team: it goes from the list. */
    fun removeRemindedTeam(team: com.nuvio.tv.domain.model.FollowedTeam) {
        viewModelScope.launch {
            prefs.updateReminders { com.nuvio.tv.core.sportslive.alerts.GameReminders.removeTeam(it, team) }
        }
    }

    /** "In-player guide (beta)" (live.21 B2). */
    val guideStyle: StateFlow<com.nuvio.tv.core.sportslive.SportsLiveGuideStyle> = prefs.guideStyle
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.nuvio.tv.core.sportslive.SportsLiveGuideStyle.DEFAULT)

    fun setGuideStyle(value: com.nuvio.tv.core.sportslive.SportsLiveGuideStyle) {
        viewModelScope.launch { prefs.setGuideStyle(value) }
    }

    /** "OK on live" (research Q19). */
    val okOnLive: StateFlow<com.nuvio.tv.core.sportslive.SportsLiveOkOnLive> = prefs.okOnLive
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.nuvio.tv.core.sportslive.SportsLiveOkOnLive.DEFAULT)

    fun setOkOnLive(value: com.nuvio.tv.core.sportslive.SportsLiveOkOnLive) {
        viewModelScope.launch { prefs.setOkOnLive(value) }
    }

    /** "At tip-off" (research Q17), read with the alert settings. */
    fun setTipOff(value: com.nuvio.tv.core.sportslive.SportsLiveTipOff) {
        viewModelScope.launch { prefs.setTipOff(value) }
    }

    /** "When a game ends" (#161). */
    val gameEndAction: StateFlow<com.nuvio.tv.core.sportslive.SportsLiveGameEndAction> = prefs.gameEndAction
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.nuvio.tv.core.sportslive.SportsLiveGameEndAction.DEFAULT)

    fun setGameEndAction(action: com.nuvio.tv.core.sportslive.SportsLiveGameEndAction) {
        viewModelScope.launch { prefs.setGameEndAction(action) }
    }

    fun setRemindMyTeams(enabled: Boolean) {
        viewModelScope.launch { prefs.setRemindMyTeams(enabled) }
    }

    fun setScoreAlerts(enabled: Boolean) {
        viewModelScope.launch { prefs.setScoreAlerts(enabled) }
    }

    fun setFullTimeAlerts(enabled: Boolean) {
        viewModelScope.launch { prefs.setFullTimeAlerts(enabled) }
    }

    /** The Scores picker: Hide until I reveal keeps "my teams only"; Show and Never show turn it off. */
    fun setScoresMode(mode: ScoresMode) {
        viewModelScope.launch { prefs.updateScoresChoice { it.withMode(mode) } }
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

    /** My sports → a sport's page: [league] pressed (no league left turns the sport off: [pressLeague]). */
    fun pressMyLeague(league: MyLeague) = updateMySports { it.pressLeague(league) }

    /** My sports → Soccer: "All competitions" pressed ([pressAllCompetitions]). */
    fun pressAllCompetitions() = updateMySports { it.pressAllCompetitions() }

    private fun updateMySports(change: (MySports) -> MySports) {
        viewModelScope.launch { prefs.updateMySports(change) }
    }

    /** Forgets which stream sources worked (#40); every source starts neutral again. */
    fun resetStreamMemory() {
        SportsLiveSourceReliability.clear()
        com.nuvio.tv.core.sportslive.SportsLiveLinkMarks.shared.clear()
        // #147: which links start fast, and which games were checked ready or without a working stream.
        com.nuvio.tv.core.sportslive.SportsLiveLinkSpeed.shared.clear()
        com.nuvio.tv.core.sportslive.SportsLiveGameReadiness.shared.clear()
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
