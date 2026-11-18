package com.nuvio.tv.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.SportsLiveOpenGame
import com.nuvio.tv.core.sportslive.scores.MyLeague
import com.nuvio.tv.core.sportslive.scores.MySport
import com.nuvio.tv.core.sportslive.scores.MySports
import com.nuvio.tv.core.sportslive.scores.ScoreDelayChoices
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.ScoresStatus
import com.nuvio.tv.domain.model.CatalogStats
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveOverride
import com.nuvio.tv.ui.screens.sportslive.mini.SPORTS_LIVE_PIP_SETTINGS_ACTION
import com.nuvio.tv.ui.screens.sportslive.myLeagueLabel
import com.nuvio.tv.ui.screens.sportslive.rememberSportsLiveClockMs
import com.nuvio.tv.ui.screens.sportslive.mySportLabel
import com.nuvio.tv.ui.screens.sportslive.mini.sportsLivePipSupported
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun SportsLiveSettingsContent(
    initialFocusRequester: FocusRequester?,
    viewModel: SportsLiveSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val alerts by viewModel.alertSettings.collectAsStateWithLifecycle()
    val newSportsHint by viewModel.newSportsHint.collectAsStateWithLifecycle()
    var filterKey by remember { mutableStateOf<String?>(null) }
    var scorePicker by remember { mutableStateOf<ScorePicker?>(null) }
    // Scores status (#70): worked out on a 30 s clock, so a feed no longer in use drops out.
    val feedHealth by viewModel.feedHealth.collectAsStateWithLifecycle()
    val clockMs by rememberSportsLiveClockMs()
    val scoresStatus = remember(state.scores.mode, feedHealth, clockMs) {
        ScoresStatus.of(state.scores.mode, feedHealth.values, System.currentTimeMillis())
    }
    // The first usable row: the first sport of My sports, or the Show switch while the section is off.
    // The screen opens on it, and focus goes back to it after OK unfollows the last team.
    val ownFirstRowFocusRequester = remember { FocusRequester() }
    val firstRowFocusRequester = initialFocusRequester ?: ownFirstRowFocusRequester
    // After OK unfollows a team, focus goes to the row now in its place (the next team, else the
    // previous one); with no team left, to the first usable row.
    val teamFocusRequesters = remember { mutableMapOf<FollowedTeam, FocusRequester>() }
    var unfollowedIndex by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(state.followedTeams) {
        val index = unfollowedIndex ?: return@LaunchedEffect
        unfollowedIndex = null
        val next = state.followedTeams.getOrNull(index.coerceAtMost(state.followedTeams.lastIndex))
        withFrameNanos { }
        runCatching { (next?.let(teamFocusRequesters::get) ?: firstRowFocusRequester).requestFocus() }
    }
    var moreExpanded by rememberSaveable { mutableStateOf(SPORTS_LIVE_MORE_EXPANDED_BY_DEFAULT) }
    var streamMemoryReset by remember { mutableStateOf(false) }

    // One setting's row(s): switches write through viewModel.setSwitch, pickers through the dialogs below.
    val settingRows: @Composable (SportsLiveSetting) -> Unit = { setting ->
        val enabled = setting.isEnabled(state)
        val switchRow: @Composable (Int, Int, Boolean, Modifier) -> Unit = { title, subtitle, checked, modifier ->
            SettingsToggleRow(
                title = stringResource(title),
                subtitle = stringResource(subtitle),
                checked = checked,
                onToggle = { viewModel.setSwitch(setting, !checked) },
                enabled = enabled,
                modifier = modifier
            )
        }
        when (setting) {
            SportsLiveSetting.MY_SPORTS -> MySportsSettings(
                mySports = state.mySports,
                enabled = enabled,
                onToggleSport = viewModel::toggleMySport,
                onToggleLeague = viewModel::toggleMyLeague,
                onAllSoccerCompetitions = viewModel::allSoccerCompetitions,
                firstFocusRequester = if (state.showSection) firstRowFocusRequester else null,
                newSportsHint = newSportsHint,
                onShown = viewModel::onMySportsShown
            )
            SportsLiveSetting.MY_TEAMS -> SettingsGroupCard(
                modifier = Modifier.fillMaxWidth(),
                title = stringResource(R.string.sports_live_my_teams_title),
                subtitle = stringResource(R.string.sports_live_my_teams_subtitle)
            ) {
                if (state.followedTeams.isEmpty()) {
                    Text(
                        text = stringResource(R.string.sports_live_my_teams_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioTheme.colors.TextSecondary,
                        modifier = Modifier.padding(NuvioTheme.spacing.sm)
                    )
                }
                // OK unfollows at once; following again is a long-press away.
                state.followedTeams.forEachIndexed { index, team ->
                    SettingsActionRow(
                        title = listOfNotNull(team.name, team.sport).joinToString(" · "),
                        subtitle = null,
                        onClick = {
                            unfollowedIndex = index
                            viewModel.unfollow(team)
                        },
                        trailingIcon = Icons.Rounded.Close,
                        modifier = Modifier.focusRequester(teamFocusRequesters.getOrPut(team) { FocusRequester() })
                    )
                }
            }
            SportsLiveSetting.SCORES -> SettingsActionRow(
                title = stringResource(R.string.sports_live_setting_scores),
                subtitle = stringResource(R.string.sports_live_setting_scores_subtitle),
                value = scoresModeLabel(state.scores.mode),
                onClick = { scorePicker = ScorePicker.MODE },
                enabled = enabled
            )
            SportsLiveSetting.SCORE_DELAY -> SettingsActionRow(
                title = stringResource(R.string.sports_live_setting_score_delay),
                subtitle = stringResource(R.string.sports_live_setting_score_delay_subtitle),
                value = scoreDelayLabel(state.scores.delaySeconds),
                onClick = { scorePicker = ScorePicker.DELAY },
                enabled = enabled
            )
            SportsLiveSetting.OPEN_GAME -> SettingsActionRow(
                title = stringResource(R.string.sports_live_setting_open_game),
                subtitle = stringResource(R.string.sports_live_setting_open_game_subtitle),
                value = openGameLabel(state.openGame),
                onClick = { scorePicker = ScorePicker.OPEN_GAME },
                enabled = enabled
            )
            SportsLiveSetting.PICTURE_IN_PICTURE -> SportsLivePipRows(
                enabled = state.pipEnabled,
                rowEnabled = enabled,
                onToggle = { viewModel.setSwitch(setting, !state.pipEnabled) }
            )
            SportsLiveSetting.SHOW_SECTION -> switchRow(
                R.string.sports_live_setting_show_section,
                R.string.sports_live_setting_show_section_subtitle,
                state.showSection,
                if (state.showSection) Modifier else Modifier.focusRequester(firstRowFocusRequester)
            )
            SportsLiveSetting.HOME_ROW -> switchRow(
                R.string.sports_live_setting_home_row,
                R.string.sports_live_setting_home_row_subtitle,
                state.showHomeRow,
                Modifier
            )
            SportsLiveSetting.CATCH_UP_ROW -> switchRow(
                R.string.sports_live_setting_catch_up_row,
                R.string.sports_live_setting_catch_up_row_subtitle,
                state.catchUpRow,
                Modifier
            )
            SportsLiveSetting.HOVER_PREVIEW -> SettingsToggleRow(
                title = stringResource(R.string.sports_live_setting_hover_preview),
                subtitle = stringResource(R.string.sports_live_setting_hover_preview_subtitle),
                checked = state.hoverPreview && !state.dataSaver,
                onToggle = { viewModel.setSwitch(setting, !state.hoverPreview) },
                enabled = enabled
            )
            SportsLiveSetting.REMIND_MY_TEAMS -> switchRow(
                R.string.sports_live_setting_remind_my_teams,
                R.string.sports_live_setting_remind_my_teams_subtitle,
                alerts.remindMyTeams,
                Modifier
            )
            SportsLiveSetting.SCORE_ALERTS -> switchRow(
                R.string.sports_live_setting_score_alerts,
                R.string.sports_live_setting_score_alerts_subtitle,
                alerts.scoreAlerts,
                Modifier
            )
            SportsLiveSetting.FULL_TIME_ALERTS -> switchRow(
                R.string.sports_live_setting_full_time_alerts,
                R.string.sports_live_setting_full_time_alerts_subtitle,
                alerts.fullTimeAlerts,
                Modifier
            )
            SportsLiveSetting.SCORES_STATUS -> SettingsActionRow(
                title = stringResource(R.string.sports_live_setting_scores_status),
                subtitle = stringResource(R.string.sports_live_setting_scores_status_subtitle),
                value = scoresStatusText(scoresStatus),
                onClick = { scorePicker = ScorePicker.STATUS },
                enabled = enabled
            )
            SportsLiveSetting.DATA_SAVER -> switchRow(
                R.string.sports_live_setting_data_saver,
                R.string.sports_live_setting_data_saver_subtitle,
                state.dataSaver,
                Modifier
            )
            SportsLiveSetting.RESET_STREAM_MEMORY -> SettingsActionRow(
                title = stringResource(R.string.sports_live_setting_reset_stream_memory),
                subtitle = stringResource(R.string.sports_live_setting_reset_stream_memory_subtitle),
                value = if (streamMemoryReset) stringResource(R.string.sports_live_setting_reset_stream_memory_done) else null,
                onClick = {
                    viewModel.resetStreamMemory()
                    streamMemoryReset = true
                },
                enabled = enabled
            )
            SportsLiveSetting.CATALOGS -> if (state.catalogs.isNotEmpty()) {
                SettingsGroupCard(
                    modifier = Modifier.fillMaxWidth(),
                    title = stringResource(R.string.sports_live_setting_catalogs),
                    subtitle = stringResource(R.string.sports_live_setting_catalogs_subtitle)
                ) {
                    state.catalogs.forEach { catalog ->
                        SettingsActionRow(
                            title = "${catalog.addonName} · ${catalog.catalogName}",
                            subtitle = listOfNotNull(
                                catalogStatus(catalog),
                                catalog.stats?.let { catalogStats(it, catalog.filterValue) }
                            ).joinToString("\n"),
                            onClick = { viewModel.cycleOverride(catalog.key) },
                            enabled = enabled
                        )
                        if (catalog.filterExtra != null) {
                            SettingsActionRow(
                                title = stringResource(
                                    R.string.sports_live_filter_action,
                                    catalog.filterValue ?: stringResource(R.string.sports_live_filter_all_option)
                                ),
                                subtitle = null,
                                onClick = { filterKey = catalog.key },
                                enabled = enabled
                            )
                        }
                    }
                }
            }
        }
    }

    // Section headers are plain text (never focused); rows run top to bottom; More is one focusable row.
    // Rows keep their keys while values change, so focus stays on the row that changed.
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        item(key = "header") {
            SettingsDetailHeader(
                title = stringResource(R.string.settings_sports_live),
                subtitle = stringResource(R.string.sports_live_settings_subtitle)
            )
        }
        sportsLiveSettingsLayout().forEach { (section, settings) ->
            item(key = "section_${section.name}") {
                SportsLiveSectionHeader(sectionTitle(section))
            }
            if (section == SportsLiveSettingsSection.MORE) {
                item(key = "more_toggle") {
                    SettingsActionRow(
                        title = stringResource(R.string.sports_live_section_more_row),
                        subtitle = stringResource(R.string.sports_live_section_more_subtitle),
                        onClick = { moreExpanded = !moreExpanded },
                        trailingIcon = if (moreExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown
                    )
                }
                if (!moreExpanded) return@forEach
            }
            val shared = settings.filterNot { it.ownCard }
            if (shared.isNotEmpty()) {
                item(key = "card_${section.name}") {
                    SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                        shared.forEach { settingRows(it) }
                    }
                }
            }
            settings.filter { it.ownCard }.forEach { setting ->
                item(key = "card_${setting.name}") { settingRows(setting) }
            }
        }
    }

    when (scorePicker) {
        ScorePicker.MODE -> SettingsSingleChoiceDialog(
            title = stringResource(R.string.sports_live_setting_scores),
            options = ScoresMode.entries.map { SettingsPickerOption(it, scoresModeLabel(it)) },
            selectedValue = state.scores.mode,
            onOptionSelected = { mode ->
                scorePicker = null
                viewModel.setScoresMode(mode)
            },
            onDismiss = { scorePicker = null }
        )
        ScorePicker.DELAY -> SettingsSingleChoiceDialog(
            title = stringResource(R.string.sports_live_setting_score_delay),
            subtitle = stringResource(R.string.sports_live_setting_score_delay_subtitle),
            options = ScoreDelayChoices.SECONDS.map { SettingsPickerOption(it, scoreDelayLabel(it)) },
            selectedValue = state.scores.delaySeconds,
            onOptionSelected = { seconds ->
                scorePicker = null
                viewModel.setScoreDelaySeconds(seconds)
            },
            onDismiss = { scorePicker = null }
        )
        ScorePicker.OPEN_GAME -> SettingsSingleChoiceDialog(
            title = stringResource(R.string.sports_live_setting_open_game),
            subtitle = stringResource(R.string.sports_live_setting_open_game_subtitle),
            options = SportsLiveOpenGame.entries.map { SettingsPickerOption(it, openGameLabel(it)) },
            selectedValue = state.openGame,
            onOptionSelected = { openGame ->
                scorePicker = null
                viewModel.setOpenGame(openGame)
            },
            onDismiss = { scorePicker = null }
        )
        ScorePicker.STATUS -> ScoresStatusDialog(
            status = scoresStatus,
            onRetry = viewModel::retryScores,
            onDismiss = { scorePicker = null }
        )
        null -> Unit
    }

    state.catalogs.firstOrNull { it.key == filterKey }?.let { catalog ->
        val extra = catalog.filterExtra ?: return@let
        SettingsSingleChoiceDialog(
            title = stringResource(R.string.sports_live_filter_picker_title),
            subtitle = "${catalog.addonName} · ${catalog.catalogName}",
            options = listOf(SettingsPickerOption<String?>(null, stringResource(R.string.sports_live_filter_all_option))) +
                catalog.filterOptions.map { SettingsPickerOption<String?>(it, it) },
            selectedValue = catalog.filterValue,
            onOptionSelected = { value ->
                filterKey = null
                viewModel.setCatalogFilter(catalog.key, extra, value)
            },
            onDismiss = { filterKey = null },
            maxHeight = 420.dp
        )
    }
}

/** The dialog open: scores mode, score delay, "When I open a game", or Scores status. */
private enum class ScorePicker { MODE, DELAY, OPEN_GAME, STATUS }

/**
 * My sports (#55): a switch per sport, in order; a sport with several leagues has a Leagues row below
 * it that opens (OK) its league switches right under it, so the D-pad walks sports, then leagues.
 */
@Composable
internal fun MySportsSettings(
    mySports: MySports,
    enabled: Boolean,
    onToggleSport: (MySport) -> Unit,
    onToggleLeague: (MyLeague) -> Unit,
    onAllSoccerCompetitions: () -> Unit,
    /** On the first sport's switch, when a screen opens on this card. */
    firstFocusRequester: FocusRequester? = null,
    /** "New: Tennis, UFC, F1, Golf" under the subtitle, for a profile that saved My sports before them. */
    newSportsHint: Boolean = false,
    /** Called when the card shows, so its one-time hint can be claimed. */
    onShown: () -> Unit = {}
) {
    LaunchedEffect(Unit) { onShown() }
    var expanded by remember { mutableStateOf<MySport?>(null) }
    val allCompetitions = stringResource(R.string.sports_live_my_sports_all_competitions)
    val leagueIndent = Modifier.padding(start = NuvioTheme.spacing.lg)
    SettingsGroupCard(
        modifier = Modifier.fillMaxWidth(),
        title = stringResource(R.string.sports_live_my_sports_title),
        subtitle = listOfNotNull(
            stringResource(R.string.sports_live_my_sports_subtitle),
            if (newSportsHint) stringResource(R.string.sports_live_my_sports_new_sports) else null
        ).joinToString("\n")
    ) {
        MySport.entries.forEach { sport ->
            val on = sport in mySports.effectiveSports
            SettingsToggleRow(
                title = mySportLabel(sport),
                subtitle = null,
                checked = on,
                onToggle = { onToggleSport(sport) },
                enabled = enabled,
                modifier = if (sport.ordinal == 0 && firstFocusRequester != null) Modifier.focusRequester(firstFocusRequester) else Modifier
            )
            if (!on || sport.leagues.size < 2) return@forEach
            val isSoccer = sport == MySport.SOCCER
            val named = mySports.namedLeagues(sport)
            val summary = if (isSoccer && named.isEmpty()) {
                allCompetitions
            } else {
                mySports.leaguesOf(sport).sortedBy { it.ordinal }.map { myLeagueLabel(it) }.joinToString(", ")
            }
            val isExpanded = expanded == sport
            SettingsActionRow(
                title = stringResource(R.string.sports_live_my_sports_leagues),
                subtitle = null,
                value = summary,
                onClick = { expanded = if (isExpanded) null else sport },
                enabled = enabled,
                trailingIcon = if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                modifier = leagueIndent
            )
            if (!isExpanded) return@forEach
            if (isSoccer) {
                SettingsToggleRow(
                    title = allCompetitions,
                    subtitle = null,
                    checked = named.isEmpty(),
                    onToggle = onAllSoccerCompetitions,
                    enabled = enabled,
                    modifier = leagueIndent
                )
            }
            val inEffect = if (isSoccer) named else mySports.leaguesOf(sport)
            sport.leagues.forEach { league ->
                SettingsToggleRow(
                    title = myLeagueLabel(league),
                    subtitle = null,
                    checked = league in inEffect,
                    onToggle = { onToggleLeague(league) },
                    enabled = enabled,
                    modifier = leagueIndent
                )
            }
        }
    }
}

@Composable
private fun scoresModeLabel(mode: ScoresMode): String = stringResource(
    when (mode) {
        ScoresMode.SHOW -> R.string.sports_live_scores_show
        ScoresMode.HIDE_UNTIL_FOCUSED -> R.string.sports_live_scores_hide_until_focused
        ScoresMode.OFF -> R.string.sports_live_scores_off
    }
)

@Composable
private fun openGameLabel(openGame: SportsLiveOpenGame): String = stringResource(
    when (openGame) {
        SportsLiveOpenGame.PLAY_BEST -> R.string.sports_live_open_game_play_best
        SportsLiveOpenGame.LET_ME_CHOOSE -> R.string.sports_live_open_game_let_me_choose
    }
)

@Composable
private fun scoreDelayLabel(seconds: Int): String =
    if (seconds <= 0) stringResource(R.string.sports_live_score_delay_off)
    else stringResource(R.string.sports_live_score_delay_seconds, seconds)

@Composable
private fun catalogStatus(catalog: SportsLiveCatalogSetting): String = when (catalog.override) {
    SportsLiveOverride.INCLUDE -> stringResource(R.string.sports_live_override_included)
    SportsLiveOverride.EXCLUDE -> stringResource(R.string.sports_live_override_excluded)
    null -> catalog.autoClaim?.let {
        "${stringResource(R.string.sports_live_override_auto_in)} (${it.reason})"
    } ?: stringResource(R.string.sports_live_override_auto_out)
}

/**
 * "12 live · 8 timed · 3 unrecognised": the non-zero counts, or "No items" ("No items for United States"
 * when a pinned [filterValue] emptied it).
 */
@Composable
private fun catalogStats(stats: CatalogStats, filterValue: String?): String {
    val parts = listOf(
        R.plurals.sports_live_stat_live to stats.live,
        R.plurals.sports_live_stat_timed to stats.timed,
        R.plurals.sports_live_stat_time_tba to stats.upcomingTba,
        R.plurals.sports_live_stat_channels to stats.channels,
        R.plurals.sports_live_stat_replays to stats.replays,
        R.plurals.sports_live_stat_ended to stats.ended,
        R.plurals.sports_live_stat_unrecognised to stats.unrecognised
    ).filter { it.second > 0 }.map { (res, count) -> pluralStringResource(res, count, count) }
    return when {
        parts.isNotEmpty() -> parts.joinToString(" · ")
        filterValue != null -> stringResource(R.string.sports_live_stat_no_items_for, filterValue)
        else -> stringResource(R.string.sports_live_stat_empty)
    }
}

/**
 * Picture-in-picture, which some TVs (onn boxes among them) lack or hide: whether this device
 * supports it, our own switch (Home only goes picture-in-picture with it on), and the system's
 * per-app setting when the device has a screen for it. Rows of the Playing card.
 */
@Composable
private fun SportsLivePipRows(enabled: Boolean, rowEnabled: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    val supported = remember(context) {
        sportsLivePipSupported(
            sdkInt = Build.VERSION.SDK_INT,
            hasPipFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        )
    }
    val systemSetting = remember(context) { pipSystemSettingIntent(context) }
    SettingsToggleRow(
        title = stringResource(R.string.sports_live_setting_pip),
        subtitle = stringResource(R.string.sports_live_setting_pip_subtitle),
        checked = enabled && supported,
        onToggle = onToggle,
        enabled = rowEnabled && supported
    )
    Text(
        text = stringResource(
            if (supported) R.string.sports_live_setting_pip_supported else R.string.sports_live_setting_pip_unsupported
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = NuvioTheme.colors.TextSecondary,
        modifier = Modifier.padding(NuvioTheme.spacing.sm)
    )
    if (supported && systemSetting != null) {
        SettingsActionRow(
            title = stringResource(R.string.sports_live_setting_pip_system),
            subtitle = stringResource(R.string.sports_live_setting_pip_system_subtitle),
            onClick = { runCatching { context.startActivity(systemSetting) } }
        )
    }
}

/** A section title (#72): plain text, so the D-pad passes over it. */
@Composable
private fun SportsLiveSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = NuvioTheme.colors.TextPrimary,
        modifier = Modifier.padding(top = NuvioTheme.spacing.sm)
    )
}

@Composable
private fun sectionTitle(section: SportsLiveSettingsSection): String = stringResource(
    when (section) {
        SportsLiveSettingsSection.WHAT_I_FOLLOW -> R.string.sports_live_section_what_i_follow
        SportsLiveSettingsSection.SCORES_AND_SPOILERS -> R.string.sports_live_section_scores_and_spoilers
        SportsLiveSettingsSection.PLAYING -> R.string.sports_live_section_playing
        SportsLiveSettingsSection.HOME_AND_ROWS -> R.string.sports_live_section_home_and_rows
        SportsLiveSettingsSection.ALERTS -> R.string.sports_live_alerts_title
        SportsLiveSettingsSection.STATUS -> R.string.sports_live_section_status
        SportsLiveSettingsSection.MORE -> R.string.sports_live_section_more
    }
)

/** The system's picture-in-picture setting (this app's page when it has one); null when nothing handles it. */
private fun pipSystemSettingIntent(context: Context): Intent? {
    val pm = context.packageManager
    return listOf(
        Intent(SPORTS_LIVE_PIP_SETTINGS_ACTION, Uri.fromParts("package", context.packageName, null)),
        Intent(SPORTS_LIVE_PIP_SETTINGS_ACTION)
    ).firstOrNull { it.resolveActivity(pm) != null }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
