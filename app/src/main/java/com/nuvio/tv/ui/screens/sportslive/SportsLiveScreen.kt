package com.nuvio.tv.ui.screens.sportslive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.RefreshFilterChip
import com.nuvio.tv.ui.components.SourceStatusFilterChip
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.dpadRepeatThrottle

@Composable
fun SportsLiveScreen(
    viewModel: SportsLiveViewModel = hiltViewModel(),
    onPlay: (SportsLiveItem) -> Unit,
    onOpenDetail: (SportsLiveItem) -> Unit,
    onOpenAddons: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.onScreenVisible(true)
                Lifecycle.Event.ON_PAUSE -> viewModel.onScreenVisible(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onScreenVisible(false)
        }
    }

    val showNoAddons = !uiState.hasClaimedCatalogs && !uiState.isLoading && uiState.lastRefreshedMs != null
    when {
        showNoAddons -> NoAddonsState(onOpenAddons)
        // Until the first refresh completes, "no claimed catalogs" may only mean addons are still resolving.
        uiState.isLoading || !uiState.hasClaimedCatalogs -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
        }
        else -> SportsLiveContent(
            uiState = uiState,
            onSelectTab = viewModel::selectTab,
            onSelectFilter = viewModel::selectFilter,
            onSelectItem = viewModel::selectItem,
            onRefresh = { viewModel.refresh() },
            onPlay = onPlay,
            onOpenDetail = onOpenDetail
        )
    }
}

@Composable
private fun NoAddonsState(onOpenAddons: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        EmptyScreenState(
            title = stringResource(R.string.sports_live_no_addons_title),
            subtitle = stringResource(R.string.sports_live_no_addons_subtitle),
            height = NuvioTheme.spacing.huge * 2
        )
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
        SportsLivePaneButton(onClick = onOpenAddons) {
            Text(stringResource(R.string.sports_live_open_addons))
        }
    }
}

@Composable
private fun SportsLiveContent(
    uiState: SportsLiveUiState,
    onSelectTab: (SportsLiveTab) -> Unit,
    onSelectFilter: (String?) -> Unit,
    onSelectItem: (String?) -> Unit,
    onRefresh: () -> Unit,
    onPlay: (SportsLiveItem) -> Unit,
    onOpenDetail: (SportsLiveItem) -> Unit
) {
    val tabFocusRequesters = remember { SportsLiveTab.entries.associateWith { FocusRequester() } }
    val activeTabFocusRequester = tabFocusRequesters.getValue(uiState.selectedTab)
    val watchFocusRequester = remember { FocusRequester() }
    // One requester per card, keyed like the LazyColumn: two addons can list the same meta id, and a
    // requester shared by two cards sends focus to whichever of them attached last.
    val cardFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    fun cardFocusRequester(item: SportsLiveItem) = cardFocusRequesters.getOrPut(item.listKey) { FocusRequester() }
    var listHasFocus by remember { mutableStateOf(false) }
    // Saved with the back stack entry, so returning from the stream or detail page refocuses this card.
    var focusedCardKey by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val stateLabelOf = rememberSportsLiveStateLabeler(uiState.selectedTab)

    val items = uiState.visibleItems
    val selected = items.firstOrNull { it.listKey == focusedCardKey && it.id == uiState.selectedItemId }
        ?: items.firstOrNull { it.id == uiState.selectedItemId }
        ?: items.firstOrNull()
    val selectedCardFocusRequester = selected?.let(::cardFocusRequester)

    // Replay "series" items are team folders; the detail page lists their videos.
    val open: (SportsLiveItem) -> Unit = { item ->
        if (item.kind == SportsLiveKind.REPLAY && item.apiType == "series") onOpenDetail(item) else onPlay(item)
    }

    BackHandler(enabled = listHasFocus) {
        runCatching { activeTabFocusRequester.requestFocus() }
    }

    // Back from another screen: put the list back on the card that had focus. Runs once per entry.
    LaunchedEffect(Unit) {
        val key = focusedCardKey ?: return@LaunchedEffect
        val index = items.indexOfFirst { it.listKey == key }
        if (index < 0) return@LaunchedEffect
        listState.scrollToItem(index)
        repeat(2) { withFrameNanos { } }
        runCatching { cardFocusRequesters[key]?.requestFocus() }
    }

    // Only a real tab or filter change scrolls to the top; the saved value survives navigation.
    val filterKey = "${uiState.selectedTab.name}|${uiState.selectedFilter.orEmpty()}"
    var listedFilterKey by rememberSaveable { mutableStateOf(filterKey) }
    LaunchedEffect(filterKey) {
        if (filterKey == listedFilterKey) return@LaunchedEffect
        listedFilterKey = filterKey
        listState.scrollToItem(0)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = NuvioTheme.spacing.screen.horizontal, vertical = NuvioTheme.spacing.screen.vertical)
    ) {
        SportsLiveChipRow(
            uiState = uiState,
            tabFocusRequesters = tabFocusRequesters,
            onSelectTab = onSelectTab,
            onOpenTab = { runCatching { selectedCardFocusRequester?.requestFocus() } },
            onSelectFilter = onSelectFilter,
            onRefresh = onRefresh
        )
        if (uiState.failures.isNotEmpty()) {
            Text(
                text = stringResource(R.string.sports_live_partial_failure, uiState.failures.size),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = NuvioTheme.spacing.sm)
            )
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))

        if (selected == null || selectedCardFocusRequester == null) {
            EmptyScreenState(title = stringResource(R.string.sports_live_empty_tab))
            return@Column
        }

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(0.45f)
                    .fillMaxHeight()
                    .onFocusChanged { listHasFocus = it.hasFocus }
                    .focusRestorer(selectedCardFocusRequester)
                    .dpadRepeatThrottle(),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
            ) {
                itemsIndexed(items, key = { _, item -> item.listKey }) { index, item ->
                    SportsLiveListCard(
                        item = item,
                        stateLabel = stateLabelOf(item),
                        selected = item.listKey == selected.listKey,
                        onClick = { open(item) },
                        onLongClick = { onOpenDetail(item) },
                        onFocus = {
                            focusedCardKey = item.listKey
                            onSelectItem(item.id)
                        },
                        modifier = Modifier
                            .focusRequester(cardFocusRequester(item))
                            .focusProperties {
                                right = watchFocusRequester
                                if (index == 0) up = activeTabFocusRequester
                            }
                    )
                }
            }
            SportsLiveDetailsPane(
                item = selected,
                stateLabel = stateLabelOf(selected),
                onWatch = { open(selected) },
                onDetails = { onOpenDetail(selected) },
                modifier = Modifier
                    .weight(0.55f)
                    .fillMaxHeight(),
                watchModifier = Modifier
                    .focusRequester(watchFocusRequester)
                    .focusProperties { left = selectedCardFocusRequester }
            )
        }
    }
}

@Composable
private fun SportsLiveChipRow(
    uiState: SportsLiveUiState,
    tabFocusRequesters: Map<SportsLiveTab, FocusRequester>,
    onSelectTab: (SportsLiveTab) -> Unit,
    onOpenTab: () -> Unit,
    onSelectFilter: (String?) -> Unit,
    onRefresh: () -> Unit
) {
    // A plain scrolling Row keeps every chip composed, so the tab FocusRequesters stay attached.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            // Room inside the scroll for the focused chip's border, which otherwise clips at the edges.
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SportsLiveTab.entries.forEach { tab ->
            val label = stringResource(tab.labelRes())
            val count = uiState.tabs[tab].orEmpty().size
            SourceStatusFilterChip(
                name = if (count > 0) "$label $count" else label,
                isSelected = tab == uiState.selectedTab,
                // Like FolderDetailScreen, focusing a tab selects it; OK moves into the list.
                onFocusSelect = { onSelectTab(tab) },
                onClick = onOpenTab,
                modifier = Modifier.focusRequester(tabFocusRequesters.getValue(tab))
            )
        }
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
        SourceStatusFilterChip(
            name = stringResource(R.string.sports_live_filter_all),
            isSelected = uiState.selectedFilter == null,
            onClick = { onSelectFilter(null) }
        )
        uiState.filters.forEach { filter ->
            SourceStatusFilterChip(
                name = filter,
                isSelected = filter == uiState.selectedFilter,
                onClick = { onSelectFilter(filter) }
            )
        }
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
        RefreshFilterChip(onClick = onRefresh, isLoading = uiState.isLoading)
    }
}

/** Unique within a tab: the bucketer keeps one copy of an item per addon. */
private val SportsLiveItem.listKey: String get() = "$addonId|$id"

private fun SportsLiveTab.labelRes(): Int = when (this) {
    SportsLiveTab.LIVE_NOW -> R.string.sports_live_tab_live_now
    SportsLiveTab.UPCOMING -> R.string.sports_live_tab_upcoming
    SportsLiveTab.CHANNELS -> R.string.sports_live_tab_channels
    SportsLiveTab.REPLAYS -> R.string.sports_live_tab_replays
}
