package com.nuvio.tv.ui.screens.sportslive

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.sportslive.SportsLiveItemBucketer
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.repository.SportsLiveFailure
import com.nuvio.tv.domain.repository.SportsLiveRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
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
        val filter: String? = null,
        val itemId: String? = null,
        val isRefreshing: Boolean = false,
        val failures: List<SportsLiveFailure> = emptyList(),
        val lastRefreshedMs: Long? = null
    )

    /** A load sorted into tabs; recomputed only when the load or the claimed catalogs change. */
    private data class Bucketed(
        val hasClaimed: Boolean,
        val hasLoad: Boolean,
        val tabs: Map<SportsLiveTab, List<SportsLiveItem>>
    )

    @VisibleForTesting
    internal var nowMs: () -> Long = { System.currentTimeMillis() }

    @VisibleForTesting
    internal var bucket: (List<SportsLiveItem>, Long) -> Map<SportsLiveTab, List<SportsLiveItem>> =
        SportsLiveItemBucketer::bucket

    private val selectionState = MutableStateFlow(Selection())
    private var autoRefreshJob: Job? = null
    private var focusProbeJob: Job? = null

    // Bucketing a large channel list is the expensive step, so selection changes do not repeat it,
    // and it runs off the main thread.
    private val bucketed = combine(repo.claimedCatalogs, repo.latest) { claimed, latest ->
        // Only items of catalogs claimed right now: the cache may still hold another profile's load.
        val claimedKeys = claimed.mapTo(HashSet()) { it.key }
        val items = latest?.items.orEmpty().filter { it.catalogKey in claimedKeys }
        Bucketed(hasClaimed = claimed.isNotEmpty(), hasLoad = latest != null, tabs = bucket(items, nowMs()))
    }.flowOn(Dispatchers.Default)

    // Followed teams' games first in every tab but Channels; a follow change does not re-bucket.
    private val pinned = combine(bucketed, repo.followedTeams) { bucketed, followed ->
        bucketed.copy(
            tabs = bucketed.tabs.mapValues { (tab, items) ->
                if (tab == SportsLiveTab.CHANNELS) items else SportsLiveItemBucketer.pinFollowed(items, followed)
            }
        )
    }.flowOn(Dispatchers.Default)

    val uiState: StateFlow<SportsLiveUiState> =
        combine(pinned, repo.endedKeys, selectionState, ::buildState)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SportsLiveUiState())

    init {
        refresh(force = false)
    }

    fun selectTab(tab: SportsLiveTab) {
        selectionState.update { it.copy(tab = tab, filter = null, itemId = null) }
    }

    /** [value] is a sport, a group on the Channels tab, or [SportsLiveUiState.MY_TEAMS_FILTER]; null means All. */
    fun selectFilter(value: String?) {
        selectionState.update { it.copy(filter = value) }
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

    /**
     * Before [item] is played: the player may zap through the list shown now (this tab and filter).
     * With no session to start, any earlier one ends.
     */
    fun startZapSession(item: SportsLiveItem) {
        val state = uiState.value
        val session = SportsLiveZapSession.of(
            items = state.visibleItems,
            itemKey = item.streamKey,
            tab = state.selectedTab,
            filter = state.selectedFilter,
            returnToHomeOnBack = false
        )
        if (session != null) SportsLiveZapSessions.start(session) else SportsLiveZapSessions.clear()
    }

    fun refresh(force: Boolean = true) {
        // Set before launching so the first frame already shows loading rather than an empty section.
        selectionState.update { it.copy(isRefreshing = true) }
        viewModelScope.launch { load(force) }
    }

    /**
     * On becoming visible (including a resume), loads at once (served from the repository's cache
     * when still fresh), then reloads every 60 s so the Live now tab stays current. These loads
     * leave channels catalogs out (carried over from the last load); they reload on the first
     * load and on a manual refresh.
     */
    fun onScreenVisible(visible: Boolean) {
        autoRefreshJob?.cancel()
        autoRefreshJob = if (visible) {
            viewModelScope.launch {
                load(force = false, includeChannels = false)
                while (isActive) {
                    delay(AUTO_REFRESH_MS)
                    load(force = true, includeChannels = false)
                }
            }
        } else {
            null
        }
    }

    /** A failed load keeps the previous failures and items. */
    private suspend fun load(force: Boolean, includeChannels: Boolean = true) {
        selectionState.update { it.copy(isRefreshing = true) }
        try {
            val result = repo.load(force, includeChannels)
            selectionState.update { it.copy(failures = result.failures, lastRefreshedMs = result.loadedAtMs) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        } finally {
            selectionState.update { it.copy(isRefreshing = false) }
        }
    }

    private fun buildState(
        bucketed: Bucketed,
        endedKeys: Set<String>,
        selection: Selection
    ): SportsLiveUiState {
        val hasClaimed = bucketed.hasClaimed
        // Live items whose streams are gone have ended; the other tabs are left as they are.
        val tabs = bucketed.tabs.mapValues { (tab, tabItems) ->
            if (tab == SportsLiveTab.LIVE_NOW) {
                tabItems.filterNot { it.streamKey in endedKeys }
            } else {
                tabItems
            }
        }
        val tabItems = tabs[selection.tab].orEmpty()
        // Channels are filtered by group, every other tab by sport.
        val filterOf: (SportsLiveItem) -> String? =
            if (selection.tab == SportsLiveTab.CHANNELS) SportsLiveItem::group else SportsLiveItem::sport
        val values = tabItems.mapNotNull(filterOf).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        // "★ My teams" leads whenever a followed team plays in the tab (never on Channels).
        val filters = if (selection.tab != SportsLiveTab.CHANNELS && tabItems.any { it.followed }) {
            listOf(SportsLiveUiState.MY_TEAMS_FILTER) + values
        } else {
            values
        }
        val filter = selection.filter?.takeIf { it in filters }
        // A filter that left the list is cleared rather than kept hidden, so it never silently
        // comes back on a later load that lists it again.
        if (selection.filter != null && filter == null) {
            selectionState.update { if (it.filter == selection.filter) it.copy(filter = null) else it }
        }
        val visible = when (filter) {
            null -> tabItems
            SportsLiveUiState.MY_TEAMS_FILTER -> tabItems.filter { it.followed }
            else -> tabItems.filter { filterOf(it) == filter }
        }
        val selectedId = selection.itemId?.takeIf { id -> visible.any { it.id == id } }
            ?: visible.firstOrNull()?.id
        return SportsLiveUiState(
            // Only a load with no data yet shows as loading, so a refresh does not blank the list.
            isLoading = hasClaimed && !bucketed.hasLoad && selection.isRefreshing,
            hasClaimedCatalogs = hasClaimed,
            tabs = tabs,
            selectedTab = selection.tab,
            filters = filters,
            selectedFilter = filter,
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
