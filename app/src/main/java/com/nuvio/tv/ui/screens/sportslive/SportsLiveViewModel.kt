package com.nuvio.tv.ui.screens.sportslive

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.sportslive.SportsLiveDataSaver
import com.nuvio.tv.core.sportslive.SportsLiveItemBucketer
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.data.local.SportsLivePreferencesDataStore
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.repository.SportsLiveFailure
import com.nuvio.tv.domain.repository.SportsLiveRepository
import com.nuvio.tv.ui.screens.sportslive.preview.SportsLivePreviewController
import com.nuvio.tv.ui.screens.sportslive.preview.SportsLivePreviewState
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniPlayers
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SportsLiveViewModel internal constructor(
    private val repo: SportsLiveRepository,
    /** The hover preview's stream for an item: one a light player can play as it is, else null. */
    previewResolve: suspend (SportsLiveItem) -> Stream? = { null },
    hoverPreviewEnabled: Flow<Boolean> = flowOf(false),
    /** The mini player plays: it takes priority, so no preview starts meanwhile. */
    miniPlayerActive: Flow<Boolean> = flowOf(false),
    /** Data saver: when on, the on-focus ended-probe is skipped (hover preview is already gated off). */
    dataSaver: Flow<Boolean> = flowOf(false),
    /** Drops [item]'s cached resolve after its preview failed, so the next play/preview re-resolves. */
    private val onResolveFailed: (SportsLiveItem) -> Unit = {}
) : ViewModel() {

    @Inject
    constructor(
        repo: SportsLiveRepository,
        resolver: SportsLiveZapResolver,
        directDebridResolver: DirectDebridResolver,
        prefs: SportsLivePreferencesDataStore
    ) : this(
        repo = repo,
        // The zap's pick, which joins the stream search the ended-games probe started on settle.
        previewResolve = { item ->
            resolver.resolve(item)?.takeIf { stream -> sportsLiveLightPlayable(stream, directDebridResolver) }
        },
        hoverPreviewEnabled = combine(prefs.hoverPreview, prefs.dataSaver) { hover, saver ->
            SportsLiveDataSaver.hoverPreviewAllowed(hover, saver)
        },
        miniPlayerActive = SportsLiveMiniPlayers.controller.state.map { it is SportsLiveMiniState.Playing },
        dataSaver = prefs.dataSaver,
        onResolveFailed = resolver::invalidate
    )

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
    internal var bucket: (List<SportsLiveItem>, Long, List<String>) -> Map<SportsLiveTab, List<SportsLiveItem>> =
        SportsLiveItemBucketer::bucket

    private val selectionState = MutableStateFlow(Selection())
    private var autoRefreshJob: Job? = null
    private var focusProbeJob: Job? = null

    // Latest Data saver value; the on-focus probe is skipped while it is on.
    @Volatile
    private var dataSaverActive = false

    // Bucketing a large channel list is the expensive step, so selection changes do not repeat it,
    // and it runs off the main thread.
    private val bucketed = combine(repo.claimedCatalogs, repo.latest) { claimed, latest ->
        // Only items of catalogs claimed right now: the cache may still hold another profile's load.
        val claimedKeys = claimed.mapTo(HashSet()) { it.key }
        val items = latest?.items.orEmpty().filter { it.catalogKey in claimedKeys }
        // Claimed catalogs come in the user's addon order, which picks a merged game's primary.
        val addonOrder = claimed.map { it.addon.id }.distinct()
        Bucketed(hasClaimed = claimed.isNotEmpty(), hasLoad = latest != null, tabs = bucket(items, nowMs(), addonOrder))
    }.flowOn(Dispatchers.Default)

    // Followed teams' games first in every tab but Channels, where they are only marked (for the
    // card star); a follow change does not re-bucket.
    private val pinned = combine(bucketed, repo.followedTeams) { bucketed, followed ->
        bucketed.copy(
            tabs = bucketed.tabs.mapValues { (tab, items) ->
                if (tab == SportsLiveTab.CHANNELS) {
                    SportsLiveItemBucketer.markFollowed(items, followed)
                } else {
                    SportsLiveItemBucketer.pinFollowed(items, followed)
                }
            }
        )
    }.flowOn(Dispatchers.Default)

    val uiState: StateFlow<SportsLiveUiState> =
        combine(pinned, repo.endedKeys, selectionState, ::buildState)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SportsLiveUiState())

    /** The active profile's followed teams, for the card menu's Follow / Unfollow labels. */
    val followedTeams: StateFlow<List<FollowedTeam>> =
        repo.followedTeams.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val previewController = SportsLivePreviewController(viewModelScope, previewResolve).apply {
        onTab(selectionState.value.tab)
    }

    /** The details pane's hover preview. */
    val preview: StateFlow<SportsLivePreviewState> = previewController.state

    init {
        refresh(force = false)
        // An active mini player counts as the setting off; once it stops, a fresh settle starts.
        viewModelScope.launch {
            combine(hoverPreviewEnabled, miniPlayerActive) { enabled, mini -> enabled && !mini }
                .collect(previewController::onEnabled)
        }
        viewModelScope.launch { dataSaver.collect { dataSaverActive = it } }
    }

    /**
     * The card the preview may play: the focused card while the list or the details pane holds
     * focus and no dialog, menu or pick mode is open; null otherwise.
     */
    fun setPreviewFocus(item: SportsLiveItem?) {
        previewController.onFocus(item)
    }

    fun onPreviewFirstFrame(item: SportsLiveItem) = previewController.onFirstFrame(item)

    fun onPreviewError(item: SportsLiveItem) {
        // The previewed stream failed to play: drop its cached resolve so it is re-resolved next time.
        onResolveFailed(item)
        previewController.onError(item)
    }

    /** [item]'s preview when it is playing on screen, stopped so the full player can take its stream over. */
    fun takeOverPreview(item: SportsLiveItem): SportsLivePreviewState.Playing? = previewController.takeOver(item)

    /**
     * Unfollows every followed team that is [side] of [item] or of any of its alternates ("Patriots"
     * for "New England Patriots"), or, when none is, follows [side] with the first known sport of
     * its sources ([SportsLiveTeams.knownSport]; none for a channel or a catalog-named sport), so the
     * follow matches the team in every addon.
     */
    fun toggleFollow(item: SportsLiveItem, side: String) {
        viewModelScope.launch {
            val matching = repo.followedTeams.first().filter { SportsLiveTeams.followsSideOfAnySource(it, side, item) }
            if (matching.isEmpty()) {
                val sport = if (item.isChannel) null else (listOf(item) + item.alternates).firstNotNullOfOrNull(SportsLiveTeams::knownSport)
                repo.follow(FollowedTeam(side, sport))
            } else {
                matching.forEach { repo.unfollow(it) }
            }
        }
    }

    fun selectTab(tab: SportsLiveTab) {
        selectionState.update { it.copy(tab = tab, filter = null, itemId = null) }
        previewController.onTab(tab)
    }

    /** [value] is a sport, a group on the Channels tab, or [SportsLiveUiState.MY_TEAMS_FILTER]; null means All. */
    fun selectFilter(value: String?) {
        // Another filter lists other cards: the preview stops until a card is focused again.
        if (value != selectionState.value.filter) previewController.onFocus(null)
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
        // Data saver: resolve/probe only on explicit play or zap-settle, never on mere focus.
        if (!SportsLiveDataSaver.onFocusProbeAllowed(dataSaverActive)) return
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
        // The preview stops while the screen is hidden; coming back, it waits for a fresh settle.
        previewController.onVisible(visible)
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
                tabItems.filterNot { it.hasEnded(endedKeys) }
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
