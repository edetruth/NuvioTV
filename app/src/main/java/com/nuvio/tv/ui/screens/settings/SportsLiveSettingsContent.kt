package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.CatalogStats
import com.nuvio.tv.domain.model.SportsLiveOverride
import com.nuvio.tv.ui.theme.NuvioTheme

@Composable
internal fun SportsLiveSettingsContent(
    initialFocusRequester: FocusRequester?,
    viewModel: SportsLiveSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

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
                    modifier = if (initialFocusRequester != null) {
                        Modifier.focusRequester(initialFocusRequester)
                    } else {
                        Modifier
                    }
                )
                SettingsToggleRow(
                    title = stringResource(R.string.sports_live_setting_home_row),
                    subtitle = null,
                    checked = state.showHomeRow,
                    onToggle = { viewModel.setShowHomeRow(!state.showHomeRow) },
                    enabled = state.showSection
                )
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
                            subtitle = listOfNotNull(catalogStatus(catalog), catalog.stats?.let { catalogStats(it) })
                                .joinToString("\n"),
                            onClick = { viewModel.cycleOverride(catalog.key) },
                            enabled = state.showSection
                        )
                    }
                }
            }
        }
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

/** "12 live · 8 timed · 3 unrecognised": the non-zero counts, or "No items". */
@Composable
private fun catalogStats(stats: CatalogStats): String {
    val parts = listOf(
        R.plurals.sports_live_stat_live to stats.live,
        R.plurals.sports_live_stat_timed to stats.timed,
        R.plurals.sports_live_stat_time_tba to stats.upcomingTba,
        R.plurals.sports_live_stat_channels to stats.channels,
        R.plurals.sports_live_stat_replays to stats.replays,
        R.plurals.sports_live_stat_unrecognised to stats.unrecognised
    ).filter { it.second > 0 }.map { (res, count) -> pluralStringResource(res, count, count) }
    return if (parts.isEmpty()) stringResource(R.string.sports_live_stat_empty) else parts.joinToString(" · ")
}
