package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.ui.components.EmptyScreenState

@Suppress("UNUSED_PARAMETER")
@Composable
fun SportsLiveScreen(
    onPlay: (SportsLiveItem) -> Unit,
    onOpenDetail: (SportsLiveItem) -> Unit,
    onOpenAddons: () -> Unit
) {
    EmptyScreenState(title = stringResource(R.string.nav_sports_live))
}
