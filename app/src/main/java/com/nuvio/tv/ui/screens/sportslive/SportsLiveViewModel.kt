package com.nuvio.tv.ui.screens.sportslive

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.sportslive.SportsLiveItemBucketer
import com.nuvio.tv.domain.model.ClaimedCatalog
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.repository.SportsLiveFailure
import com.nuvio.tv.domain.repository.SportsLiveLoad
import com.nuvio.tv.domain.repository.SportsLiveRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SportsLiveViewModel @Inject constructor(
    private val repo: SportsLiveRepository
) : ViewModel() {

    private data class Selection(
        val tab: SportsLiveTab = SportsLiveTab.LIVE_NOW,
        val sport: String? = null,
        val itemId: String? = null,
        val isRefreshing: Boolean = false,
        val failures: List<SportsLiveFailure> = emptyList(),
        val lastRefreshedMs: Long? = null
    )

    @VisibleForTesting
    internal var nowMs: () -> Long = { System.currentTimeMillis() }

    private val selectionState = MutableStateFlow(Selection())
    private var autoRefreshJob: Job? = null
    private var focusProbeJob: Job? = null

    val uiState: StateFlow<SportsLiveUiState> =
        combine(repo.claimedCatalogs, repo.latest, repo.endedKeys, selectionState, ::buildState)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SportsLiveUiState())

    init {
        refresh(force = false)
    }

    fun selectTab(tab: SportsLiveTab) {
        selectionState.update { it.copy(tab = tab, sport = null, itemId = null) }
    }

    fun selectSport(sport: String?) {
        selectionState.update { it.copy(sport = sport) }
    }

    /**
     * A Live now item the user settles on (selected for [FOCUS_PROBE_DELAY_MS]) has its streams
     * re-probed when the last probe is stale; scrolling past items probes nothing.
     */
    fun selectItem(id: String?) {
        selectionState.update { it.copy(itemId = id) }
        focusProbeJob?.cancel()
        if (id == null || selectionState.value.tab != SportsLiveTab.LIVE_NOW) return
        focusProbeJob = viewModelScope.launch {
            delay(FOCUS_PROBE_DELAY_MS)
            val selection = selectionState.value
            if (selection.tab != SportsLiveTab.LIVE_NOW || selection.itemId != id) return@launch
            uiState.value.tabs[SportsLiveTab.LIVE_NOW]?.firstOrNull { it.id == id }?.let(repo::reprobeIfStale)
        }
    }

    fun refresh(force: Boolean = true) {
        // Set before launching so the first frame already shows loading rather than an empty section.
        selectionState.update { it.copy(isRefreshing = true) }
        viewModelScope.launch { load(force) }
    }

    /**
     * On becoming visible (including a resume), loads at once (served from the repository's cache
     * when still fresh), then reloads every 60 s so the Live now tab stays current.
     */
    fun onScreenVisible(visible: Boolean) {
        autoRefreshJob?.cancel()
        autoRefreshJob = if (visible) {
            viewModelScope.launch {
                load(force = false)
                while (isActive) {
                    delay(AUTO_REFRESH_MS)
                    load(force = true)
                }
            }
        } else {
            null
        }
    }

    /** A failed load keeps the previous failures and items. */
    private suspend fun load(force: Boolean) {
        selectionState.update { it.copy(isRefreshing = true) }
        try {
            val result = repo.load(force)
            selectionState.update { it.copy(failures = result.failures, lastRefreshedMs = result.loadedAtMs) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        } finally {
            selectionState.update { it.copy(isRefreshing = false) }
        }
    }

    private fun buildState(
        claimed: List<ClaimedCatalog>,
        latest: SportsLiveLoad?,
        endedKeys: Set<String>,
        selection: Selection
    ): SportsLiveUiState {
        val hasClaimed = claimed.isNotEmpty()
        // Only items of catalogs claimed right now: the cache may still hold another profile's load.
        val claimedKeys = claimed.mapTo(HashSet()) { it.key }
        val items = latest?.items.orEmpty().filter { it.catalogKey in claimedKeys }
        // Live items whose streams are gone have ended; the other tabs are left as they are.
        val tabs = SportsLiveItemBucketer.bucket(items, nowMs()).mapValues { (tab, tabItems) ->
            if (tab == SportsLiveTab.LIVE_NOW) {
                tabItems.filterNot { it.streamKey in endedKeys }
            } else {
                tabItems
            }
        }
        val tabItems = tabs[selection.tab].orEmpty()
        val sports = tabItems.mapNotNull { it.sport }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        val sport = selection.sport?.takeIf { it in sports }
        val visible = if (sport == null) tabItems else tabItems.filter { it.sport == sport }
        val selectedId = selection.itemId?.takeIf { id -> visible.any { it.id == id } }
            ?: visible.firstOrNull()?.id
        return SportsLiveUiState(
            // Only a load with no data yet shows as loading, so a refresh does not blank the list.
            isLoading = hasClaimed && latest == null && selection.isRefreshing,
            hasClaimedCatalogs = hasClaimed,
            tabs = tabs,
            selectedTab = selection.tab,
            sports = sports,
            selectedSport = sport,
            visibleItems = visible,
            selectedItemId = selectedId,
            failures = selection.failures,
            lastRefreshedMs = selection.lastRefreshedMs
        )
    }

    private companion object {
        const val AUTO_REFRESH_MS = 60_000L
        const val FOCUS_PROBE_DELAY_MS = 700L
    }
}
