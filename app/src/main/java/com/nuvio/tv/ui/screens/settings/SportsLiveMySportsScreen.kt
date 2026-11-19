package com.nuvio.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R

/**
 * Settings → Sports & Live → My sports on its own, for Results' "Edit my sports": the same page as in
 * Settings (one row per sport, a sport's leagues as a sub-step), focus on the first sport. Back from a
 * sport's leagues returns to My sports; Back from My sports returns to Results, which reloads.
 */
@Composable
fun SportsLiveMySportsScreen(onBackPress: () -> Unit) {
    // The pane's own Back (leagues → My sports) composes later, so it is asked first.
    BackHandler { onBackPress() }
    val firstFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstFocusRequester.requestFocus() }
    }
    SettingsStandaloneScaffold(
        title = stringResource(R.string.settings_sports_live),
        subtitle = stringResource(R.string.sports_live_my_sports_title)
    ) {
        SportsLiveSettingsContent(
            initialFocusRequester = firstFocusRequester,
            modifier = Modifier.weight(1f),
            rootPage = SportsLiveSettingsPage.MySports
        )
    }
}
