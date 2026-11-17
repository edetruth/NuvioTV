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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.nuvio.tv.domain.model.CatalogStats
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveOverride
import com.nuvio.tv.ui.screens.sportslive.mini.SPORTS_LIVE_PIP_SETTINGS_ACTION
import com.nuvio.tv.ui.screens.sportslive.mini.sportsLivePipSupported
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun SportsLiveSettingsContent(
    initialFocusRequester: FocusRequester?,
    viewModel: SportsLiveSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var filterKey by remember { mutableStateOf<String?>(null) }
    // After OK unfollows a team, focus goes to the row now in its place (the next team, else the
    // previous one); with no team left, to the nearest enabled switch above the card.
    val teamFocusRequesters = remember { mutableMapOf<FollowedTeam, FocusRequester>() }
    val switchFocusRequester = remember { FocusRequester() }
    var unfollowedIndex by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(state.followedTeams) {
        val index = unfollowedIndex ?: return@LaunchedEffect
        unfollowedIndex = null
        val next = state.followedTeams.getOrNull(index.coerceAtMost(state.followedTeams.lastIndex))
        withFrameNanos { }
        runCatching { (next?.let(teamFocusRequesters::get) ?: switchFocusRequester).requestFocus() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        item(key = "header") {
            SettingsDetailHeader(
                title = stringResource(R.string.settings_sports_live),
                subtitle = stringResource(R.string.sports_live_setting_show_section_subtitle)
            )
        }
        item(key = "switches") {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                SettingsToggleRow(
                    title = stringResource(R.string.sports_live_setting_show_section),
                    subtitle = null,
                    checked = state.showSection,
                    onToggle = { viewModel.setShowSection(!state.showSection) },
                    modifier = Modifier
                        .then(if (initialFocusRequester != null) Modifier.focusRequester(initialFocusRequester) else Modifier)
                        .then(if (state.showSection) Modifier else Modifier.focusRequester(switchFocusRequester))
                )
                SettingsToggleRow(
                    title = stringResource(R.string.sports_live_setting_home_row),
                    subtitle = null,
                    checked = state.showHomeRow,
                    onToggle = { viewModel.setShowHomeRow(!state.showHomeRow) },
                    enabled = state.showSection
                )
                SettingsToggleRow(
                    title = stringResource(R.string.sports_live_setting_hover_preview),
                    subtitle = null,
                    checked = state.hoverPreview && !state.dataSaver,
                    onToggle = { viewModel.setHoverPreview(!state.hoverPreview) },
                    enabled = state.showSection && !state.dataSaver,
                    modifier = if (state.showSection) Modifier.focusRequester(switchFocusRequester) else Modifier
                )
                SettingsToggleRow(
                    title = stringResource(R.string.sports_live_setting_data_saver),
                    subtitle = stringResource(R.string.sports_live_setting_data_saver_subtitle),
                    checked = state.dataSaver,
                    onToggle = { viewModel.setDataSaver(!state.dataSaver) },
                    enabled = state.showSection
                )
                var streamMemoryReset by remember { mutableStateOf(false) }
                SettingsActionRow(
                    title = stringResource(R.string.sports_live_setting_reset_stream_memory),
                    subtitle = stringResource(R.string.sports_live_setting_reset_stream_memory_subtitle),
                    value = if (streamMemoryReset) stringResource(R.string.sports_live_setting_reset_stream_memory_done) else null,
                    onClick = {
                        viewModel.resetStreamMemory()
                        streamMemoryReset = true
                    }
                )
            }
        }
        item(key = "pip") {
            SportsLivePipSettings(
                enabled = state.pipEnabled,
                sectionShown = state.showSection,
                onToggle = { viewModel.setPipEnabled(!state.pipEnabled) }
            )
        }
        item(key = "my_teams") {
            SettingsGroupCard(
                modifier = Modifier.fillMaxWidth(),
                title = stringResource(R.string.sports_live_my_teams_title)
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
        }
        if (state.catalogs.isNotEmpty()) {
            item(key = "catalogs") {
                SettingsGroupCard(
                    modifier = Modifier.fillMaxWidth(),
                    title = stringResource(R.string.sports_live_setting_catalogs)
                ) {
                    state.catalogs.forEach { catalog ->
                        SettingsActionRow(
                            title = "${catalog.addonName} · ${catalog.catalogName}",
                            subtitle = listOfNotNull(
                                catalogStatus(catalog),
                                catalog.stats?.let { catalogStats(it, catalog.filterValue) }
                            ).joinToString("\n"),
                            onClick = { viewModel.cycleOverride(catalog.key) },
                            enabled = state.showSection
                        )
                        if (catalog.filterExtra != null) {
                            SettingsActionRow(
                                title = stringResource(
                                    R.string.sports_live_filter_action,
                                    catalog.filterValue ?: stringResource(R.string.sports_live_filter_all_option)
                                ),
                                subtitle = null,
                                onClick = { filterKey = catalog.key },
                                enabled = state.showSection
                            )
                        }
                    }
                }
            }
        }
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
 * per-app setting when the device has a screen for it.
 */
@Composable
private fun SportsLivePipSettings(enabled: Boolean, sectionShown: Boolean, onToggle: () -> Unit) {
    val context = LocalContext.current
    val supported = remember(context) {
        sportsLivePipSupported(
            sdkInt = Build.VERSION.SDK_INT,
            hasPipFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        )
    }
    val systemSetting = remember(context) { pipSystemSettingIntent(context) }
    SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
        SettingsToggleRow(
            title = stringResource(R.string.sports_live_setting_pip),
            subtitle = stringResource(R.string.sports_live_setting_pip_subtitle),
            checked = enabled && supported,
            onToggle = onToggle,
            enabled = sectionShown && supported
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
                subtitle = null,
                onClick = { runCatching { context.startActivity(systemSetting) } }
            )
        }
    }
}

/** The system's picture-in-picture setting (this app's page when it has one); null when nothing handles it. */
private fun pipSystemSettingIntent(context: Context): Intent? {
    val pm = context.packageManager
    return listOf(
        Intent(SPORTS_LIVE_PIP_SETTINGS_ACTION, Uri.fromParts("package", context.packageName, null)),
        Intent(SPORTS_LIVE_PIP_SETTINGS_ACTION)
    ).firstOrNull { it.resolveActivity(pm) != null }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
