package com.nuvio.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R

/**
 * Settings → Sports & Live → My sports on its own, for Results' "Edit my sports": the same card as in
 * Settings, focus on the first sport. Back returns to Results, which reloads with the new choice.
 */
@Composable
fun SportsLiveMySportsScreen(
    onBackPress: () -> Unit,
    viewModel: SportsLiveSettingsViewModel = hiltViewModel()
) {
    BackHandler { onBackPress() }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val firstFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstFocusRequester.requestFocus() }
    }
    SettingsStandaloneScaffold(
        title = stringResource(R.string.settings_sports_live),
        subtitle = stringResource(R.string.sports_live_my_sports_title)
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item(key = "my_sports") {
                MySportsSettings(
                    mySports = state.mySports,
                    // Results is only reachable with the section shown.
                    enabled = true,
                    onToggleSport = viewModel::toggleMySport,
                    onToggleLeague = viewModel::toggleMyLeague,
                    onAllSoccerCompetitions = viewModel::allSoccerCompetitions,
                    firstFocusRequester = firstFocusRequester
                )
            }
        }
    }
}
