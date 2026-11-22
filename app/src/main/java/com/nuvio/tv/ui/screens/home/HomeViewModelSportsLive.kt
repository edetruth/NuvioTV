package com.nuvio.tv.ui.screens.home

import android.util.Log
import com.nuvio.tv.R
import com.nuvio.tv.core.LogRedact
import com.nuvio.tv.core.sportslive.scores.CatchUpItem
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapSession
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapSessions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Catalogs claimed by Sports & Live leave Home. When the claimed set changes after Home has
 * loaded, the catalog order is rebuilt and the catalogs loaded again, so rows come back or
 * disappear without a restart.
 */
internal fun HomeViewModel.observeSportsLiveClaimedKeysPipeline() {
    viewModelScope.launch {
        sportsLiveRepository.claimedCatalogKeys.collectLatest { keys ->
            if (keys == sportsLiveClaimedKeys) return@collectLatest
            sportsLiveClaimedKeys = keys
            if (addonsCache.isEmpty()) return@collectLatest
            rebuildCatalogOrder(addonsCache)
            loadAllCatalogsPipeline(addonsCache)
        }
    }
}

/**
 * Before [item] of the Live now row is played: the player may zap through every Live now game, the
 * row's first (#68: not only the ones the row has room for). With no session to start, any earlier one ends.
 */
internal fun HomeViewModel.startLiveNowZapSession(item: SportsLiveItem) {
    val ended = _uiState.value.liveNowEnded
    val session = SportsLiveZapSession.of(
        items = liveNowAllItems.filterNot { it.streamKey in ended },
        itemKey = item.streamKey,
        tab = SportsLiveTab.LIVE_NOW,
        filter = null,
        returnToHomeOnBack = true
    )
    if (session != null) SportsLiveZapSessions.start(session) else SportsLiveZapSessions.clear()
}

/**
 * New score labels for the Live now cards. Only the Live now row is replaced (the other rows keep their
 * identity), so a clock tick never rebuilds Home or moves its focus and scroll; the next full rebuild
 * reads [HomeViewModel.liveNowScoreLabels] too.
 */
internal fun HomeViewModel.setLiveNowScoreLabels(
    labels: Map<String, String>,
    tags: Map<String, String> = liveNowTags,
    starts: Map<String, String> = liveNowStarts
) {
    if (labels == liveNowScoreLabels && tags == liveNowTags && starts == liveNowStarts) return
    liveNowScoreLabels = labels
    liveNowTags = tags
    liveNowStarts = starts
    replaceLiveNowRow()
}

/** Rebuilds the Live now row in place from [HomeUiState.liveNowShown]; with none left it leaves Home's rows. */
private fun HomeViewModel.replaceLiveNowRow() {
    _uiState.update { state ->
        val index = state.homeRows.indexOfFirst { it is HomeRow.Catalog && SportsLiveHomeRow.isLiveNowRow(it.row.addonBaseUrl) }
        if (index < 0) return@update state
        val rows = state.homeRows.toMutableList()
        if (state.liveNowShown.isEmpty()) {
            rows.removeAt(index)
        } else {
            val old = (state.homeRows[index] as HomeRow.Catalog).row
            rows[index] = HomeRow.Catalog(SportsLiveHomeRow.build(state.liveNowShown, old.catalogName, liveNowScoreLabels, sportsLiveHomeWords(), liveNowTags, liveNowStarts))
        }
        state.copy(homeRows = rows)
    }
}

/**
 * The Live now cards whose game is over (#60, worked out by Home's screen from the live scores and the
 * clock): they leave the row. The rows are rebuilt only when the set changes. l24 polish P1: the Live now row is
 * rebuilt in place at once, never after the debounced full rebuild (seconds while Home's catalogs load at a cold
 * start), so a finished game kept from the last session never shows under "Live now" with its final score.
 */
internal fun HomeViewModel.setLiveNowEnded(keys: Set<String>) {
    if (keys == _uiState.value.liveNowEnded) return
    _uiState.update { it.copy(liveNowEnded = keys) }
    replaceLiveNowRow()
    if (addonsCache.isNotEmpty()) scheduleUpdateCatalogRows()
}

/** The Catch up row's items (#58, from Home's screen): the rows are rebuilt only when they change. */
internal fun HomeViewModel.setCatchUpItems(items: List<CatchUpItem>) {
    if (items == _uiState.value.catchUpItems) return
    _uiState.update { it.copy(catchUpItems = items) }
    if (addonsCache.isNotEmpty()) scheduleUpdateCatalogRows()
}

/** P14: whether the Catch up row's place is held while it loads (from Home's screen). */
internal fun HomeViewModel.setCatchUpPending(pending: Boolean) {
    if (pending == _uiState.value.catchUpPending) return
    _uiState.update { it.copy(catchUpPending = pending) }
    if (addonsCache.isNotEmpty()) scheduleUpdateCatalogRows()
}

/**
 * Feeds the Live now row (its first [LIVE_NOW_ROW_LIMIT] games; [HomeViewModel.liveNowAllItems] keeps
 * them all). Before the catalogs load, the next catalog update picks the items up.
 */
internal fun HomeViewModel.observeLiveNowPipeline() {
    viewModelScope.launch {
        sportsLiveRepository.liveNow(limit = Int.MAX_VALUE).collect { all ->
            liveNowAllItems = all
            val items = all.take(LIVE_NOW_ROW_LIMIT)
            if (items == _uiState.value.liveNowItems) return@collect
            _uiState.update { it.copy(liveNowItems = items) }
            if (addonsCache.isNotEmpty()) scheduleUpdateCatalogRows()
        }
    }
}

/** The hero's words for the Live now cards (V2 phase 10). */
internal fun HomeViewModel.sportsLiveHomeWords(): SportsLiveHomeRow.Words = SportsLiveHomeRow.Words(
    liveNow = appContext.getString(R.string.sports_live_home_row_title),
    yourTeam = appContext.getString(R.string.sports_live_home_your_team),
    streams = { count -> appContext.resources.getQuantityString(R.plurals.sports_live_home_streams, count, count) },
    // The app's sport words (live.24), as the cards and Live Now's rows word them.
    sportWord = { kind -> appContext.getString(com.nuvio.tv.ui.screens.sportslive.sportKindLabelRes(kind)) }
)

/** How many games the Home Live now row shows. */
private const val LIVE_NOW_ROW_LIMIT = 20

/**
 * Starts a Sports & Live load without waiting for it; a failure only leaves the Live now row empty.
 * Channels catalogs (megabytes for large addons) are carried over from the last load rather than
 * refetched on every return to Home; a load with nothing cached still fetches them.
 */
internal fun HomeViewModel.refreshSportsLiveInBackground() {
    viewModelScope.launch {
        runCatching { sportsLiveRepository.load(includeChannels = false) }.onFailure { error ->
            if (error is CancellationException) throw error
            Log.w(HomeViewModel.TAG, "Sports & Live load failed", LogRedact.error(error))
        }
    }
}
