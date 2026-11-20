package com.nuvio.tv.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import com.nuvio.tv.core.sportslive.SportsLiveMyGamesToday
import com.nuvio.tv.ui.screens.sportslive.SportsLiveReadyKeeper
import com.nuvio.tv.ui.screens.sportslive.sportsLiveMyGamesTodayText
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Home's "2 games tonight · 1 ready" (live.20 1b extra a): my games today, from the readiness keeper. */
@HiltViewModel
class SportsLiveMyGamesTodayViewModel @Inject constructor(keeper: SportsLiveReadyKeeper) : ViewModel() {
    val today: StateFlow<SportsLiveMyGamesToday?> = keeper.today.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)
}

/** The quiet line's rule: only on a day with my games (not over yet), and only above the Live now row. */
internal object SportsLiveHomeTodayRule {
    fun shown(today: SportsLiveMyGamesToday?, addonId: String?, catalogId: String?): Boolean =
        today != null && today.games > 0 && addonId == SportsLiveHomeRow.ADDON_ID && catalogId == SportsLiveHomeRow.CATALOG_ID
}

/** My games today for Home's line ([SportsLiveReadyKeeper.today]); null on a day with none. */
internal val LocalSportsLiveMyGamesToday = compositionLocalOf<SportsLiveMyGamesToday?> { null }

/** Above Home's Live now row only (Modern layout): "2 games tonight · 1 ready", muted, one line. */
@Composable
internal fun SportsLiveHomeTodayLine(addonId: String?, catalogId: String?, modifier: Modifier = Modifier) {
    val today = LocalSportsLiveMyGamesToday.current
    if (today == null || !SportsLiveHomeTodayRule.shown(today, addonId, catalogId)) return
    Text(
        text = sportsLiveMyGamesTodayText(today),
        style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
        color = Colors.TextMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}
