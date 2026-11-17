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
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
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
import com.nuvio.tv.core.sportslive.SportsLiveTeams
import com.nuvio.tv.core.sportslive.TeamPair
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.LiveScoresRepository
import com.nuvio.tv.core.sportslive.scores.MySport
import com.nuvio.tv.core.sportslive.scores.ResultRow
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreDetails
import com.nuvio.tv.core.sportslive.scores.ScoreDetailsBuilder
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.RefreshFilterChip
import com.nuvio.tv.ui.components.SourceStatusFilterChip
import com.nuvio.tv.ui.screens.sportslive.dual.SportsLiveDualSession
import com.nuvio.tv.ui.screens.sportslive.dual.SportsLiveDualSessions
import com.nuvio.tv.ui.screens.sportslive.dual.offersDual
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniPlayers
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniState
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniStop
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveNowPlayingCard
import com.nuvio.tv.ui.screens.sportslive.dual.sportsLivePickModeSession
import com.nuvio.tv.ui.screens.sportslive.preview.SportsLivePreviewPlayer
import com.nuvio.tv.ui.screens.sportslive.preview.SportsLivePreviewState
import com.nuvio.tv.ui.screens.sportslive.preview.SportsLivePreviewVideo
import com.nuvio.tv.ui.screens.sportslive.preview.previewKey
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.dpadRepeatThrottle
import kotlinx.coroutines.delay

@Composable
fun SportsLiveScreen(
    viewModel: SportsLiveViewModel = hiltViewModel(),
    playViewModel: SportsLivePlayViewModel = hiltViewModel(),
    resultsViewModel: SportsLiveResultsViewModel = hiltViewModel(),
    scoresViewModel: SportsLiveScoresViewModel = hiltViewModel(),
    /** Opens the item's Stream screen; the Boolean: the user picks there (no autoplay). */
    onPlay: (SportsLiveItem, Boolean) -> Unit,
    onPlayTarget: (SportsLiveZapTarget) -> Unit,
    onOpenDetail: (SportsLiveItem) -> Unit,
    onOpenAddons: () -> Unit,
    /** Opens Settings → Sports & Live → My sports (Results' "Edit my sports"). */
    onOpenMySports: () -> Unit = {},
    /** Opens dual screen on the session just put in [SportsLiveDualSessions]. */
    onOpenDual: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val followedTeams by viewModel.followedTeams.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val playState by playViewModel.state.collectAsStateWithLifecycle()
    val resultsState by resultsViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Live scores for the Live now and Upcoming cards on screen, polled only while the screen is
    // started and Scores is not Off. Followed teams' games come first in these lists.
    val scores by scoresViewModel.scores.collectAsStateWithLifecycle()
    val scoresMode by scoresViewModel.mode.collectAsStateWithLifecycle()
    val revealed by scoresViewModel.revealed.collectAsStateWithLifecycle()
    val scoredTab = uiState.selectedTab == SportsLiveTab.LIVE_NOW || uiState.selectedTab == SportsLiveTab.UPCOMING
    TrackLiveScores(
        screenId = LiveScoresRepository.SCREEN_SECTION,
        cards = if (scoredTab) uiState.visibleItems else emptyList(),
        enabled = scoresMode != null && scoresMode != ScoresMode.OFF,
        tracking = scoresViewModel.tracking
    )
    // Worked out once per card for each scores update (or setting / reveal change), not per recomposition.
    val chipOf: (SportsLiveItem) -> ScoreChip? = remember(scores, scoresMode, revealed, scoredTab) {
        val cache = HashMap<SportsLiveItem, ScoreChip?>()
        val chip: (SportsLiveItem) -> ScoreChip? = { item ->
            if (!scoredTab) {
                null
            } else {
                cache.getOrPut(item) {
                    ScoreChips.of(item, scores[item.streamKey], scoresMode, item.streamKey in revealed)
                }
            }
        }
        chip
    }
    // The pane's score block: the same match, side order and spoiler rule as the chip.
    val detailsOf: (SportsLiveItem) -> ScoreDetails? = remember(scores, scoresMode, revealed, scoredTab) {
        { item ->
            if (scoredTab) ScoreDetailsBuilder.of(item, scores[item.streamKey], scoresMode, item.streamKey in revealed) else null
        }
    }
    val artOf: (SportsLiveItem) -> GameArt? = remember(scores) {
        val cache = HashMap<SportsLiveItem, GameArt?>()
        val art: (SportsLiveItem) -> GameArt? = { item -> cache.getOrPut(item) { GameArt.of(item, scores[item.streamKey]) } }
        art
    }

    // Results loads only while its tab is selected; Scores Off hides the tab (and requests nothing).
    LaunchedEffect(uiState.selectedTab) { resultsViewModel.setActive(uiState.selectedTab == SportsLiveTab.RESULTS) }
    LaunchedEffect(resultsState.mode, uiState.selectedTab) {
        if (resultsState.modeKnown && resultsState.mode == ScoresMode.OFF && uiState.selectedTab == SportsLiveTab.RESULTS) {
            viewModel.selectTab(SportsLiveTab.LIVE_NOW)
        }
    }
    // Replays are what a result opens.
    LaunchedEffect(uiState.tabs) {
        resultsViewModel.setReplays(uiState.tabs[SportsLiveTab.REPLAYS].orEmpty())
    }

    // The hover preview's one player: it plays while the preview does, and is freed the moment the
    // screen pauses or leaves, or OK hands its stream to the full player.
    val previewPlayer = remember {
        SportsLivePreviewPlayer(context, viewModel::onPreviewFirstFrame, viewModel::onPreviewError)
    }
    val playing = preview as? SportsLivePreviewState.Playing
    LaunchedEffect(playing?.item?.previewKey, playing?.stream) {
        if (playing == null) previewPlayer.release() else previewPlayer.play(playing.item, playing.stream)
    }

    // A live game or channel plays its best stream (#56); other cards keep their behaviour.
    SportsLivePlayHost(
        playViewModel = playViewModel,
        onOpenStreamScreen = { item, manual ->
            viewModel.startZapSession(item)
            onPlay(item, manual)
        },
        onPlayTarget = { target ->
            previewPlayer.release()
            viewModel.startZapSession(target.item)
            onPlayTarget(target)
        }
    )

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    previewPlayer.suspended = false
                    viewModel.onScreenVisible(true)
                }
                Lifecycle.Event.ON_PAUSE -> {
                    previewPlayer.suspended = true
                    viewModel.onScreenVisible(false)
                    previewPlayer.release()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onScreenVisible(false)
            previewPlayer.release()
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
            followedTeams = followedTeams,
            chipOf = chipOf,
            artOf = artOf,
            detailsOf = detailsOf,
            onCardFocus = scoresViewModel::onCardFocus,
            onSelectTab = viewModel::selectTab,
            onSelectFilter = viewModel::selectFilter,
            onSelectItem = viewModel::selectItem,
            onRefresh = { viewModel.refresh() },
            onPlay = { item ->
                // OK on a playing preview: its stream plays full screen at once (unless the stored
                // setting lets the user choose); otherwise the best stream is looked up.
                playViewModel.playCard(item) {
                    viewModel.takeOverPreview(item)?.let { previewed ->
                        // Freed before the full player starts, so the two never hold decoders together.
                        previewPlayer.release()
                        previewed.stream
                    }
                }
            },
            onChooseStream = { item -> playViewModel.chooseStream(item) },
            preview = playing,
            noStreamKey = (preview as? SportsLivePreviewState.NoStream)?.item?.listKey,
            previewPlayer = previewPlayer,
            dialogOpen = playState != SportsLivePlayState.Idle,
            onPreviewFocus = viewModel::setPreviewFocus,
            onOpenDetail = onOpenDetail,
            onToggleFollow = viewModel::toggleFollow,
            onOpenDual = { session ->
                SportsLiveDualSessions.start(session)
                onOpenDual()
            },
            results = resultsState,
            onSelectResultsDay = resultsViewModel::selectDay,
            onSelectResultsSport = resultsViewModel::selectSport,
            onSelectResultsLeague = resultsViewModel::selectLeague,
            onExpandResultsGroup = resultsViewModel::expandGroup,
            onEditMySports = onOpenMySports,
            onResultFocus = resultsViewModel::onRowFocus,
            onOpenResult = { row ->
                // The game's replay when an addon lists it (strict match); a row without one does nothing (#51).
                val replay = resultsState.replays[row.key]
                if (replay == null) {
                    // No replay: nothing happens; the Game page (R3) will take OK over.
                } else if (replay.apiType == "series") {
                    onOpenDetail(replay)
                } else {
                    playViewModel.play(replay)
                }
            }
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
    followedTeams: List<FollowedTeam>,
    /** A card's score chip (Live now / Upcoming only, spoiler setting applied). */
    chipOf: (SportsLiveItem) -> ScoreChip?,
    /** A game's drawn card; null keeps the addon's art. */
    artOf: (SportsLiveItem) -> GameArt?,
    /** A card's score block for the details pane (Live now / Upcoming only, spoiler setting applied). */
    detailsOf: (SportsLiveItem) -> ScoreDetails?,
    /** The focused card's key (null: none), for Hide until focused. */
    onCardFocus: (String?) -> Unit,
    onSelectTab: (SportsLiveTab) -> Unit,
    onSelectFilter: (String?) -> Unit,
    onSelectItem: (String?) -> Unit,
    onRefresh: () -> Unit,
    onPlay: (SportsLiveItem) -> Unit,
    /** "Choose stream": the card's streams to pick from, whatever "When I open a game" says. */
    onChooseStream: (SportsLiveItem) -> Unit,
    preview: SportsLivePreviewState.Playing?,
    /** The card whose stream lookup found nothing to play. */
    noStreamKey: String?,
    previewPlayer: SportsLivePreviewPlayer,
    /** OK's stream lookup or picker is open. */
    dialogOpen: Boolean,
    onPreviewFocus: (SportsLiveItem?) -> Unit,
    onOpenDetail: (SportsLiveItem) -> Unit,
    onToggleFollow: (SportsLiveItem, String) -> Unit,
    onOpenDual: (SportsLiveDualSession) -> Unit,
    results: SportsLiveResultsUiState,
    onSelectResultsDay: (Int) -> Unit,
    onSelectResultsSport: (MySport?) -> Unit,
    onSelectResultsLeague: (String?) -> Unit,
    onExpandResultsGroup: (String) -> Unit,
    onEditMySports: () -> Unit,
    onResultFocus: (String?) -> Unit,
    onOpenResult: (ResultRow) -> Unit
) {
    val tabFocusRequesters = remember { SportsLiveTab.entries.associateWith { FocusRequester() } }
    val activeTabFocusRequester = tabFocusRequesters.getValue(uiState.selectedTab)
    val watchFocusRequester = remember { FocusRequester() }
    // One requester per card, keyed like the LazyColumn: two addons can list the same meta id, and a
    // requester shared by two cards sends focus to whichever of them attached last.
    val cardFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    fun cardFocusRequester(item: SportsLiveItem) = cardFocusRequesters.getOrPut(item.listKey) { FocusRequester() }
    var listHasFocus by remember { mutableStateOf(false) }
    var paneHasFocus by remember { mutableStateOf(false) }
    // Saved with the back stack entry, so returning from the stream or detail page refocuses this card.
    var focusedCardKey by rememberSaveable { mutableStateOf<String?>(null) }
    // One scroll position per tab and filter: switching opens the list at the top, while coming back
    // from a game, Details or the player restores the position saved with the back stack entry.
    val filterKey = "${uiState.selectedTab.name}|${uiState.selectedFilter.orEmpty()}"
    val listState = rememberSaveable(filterKey, saver = LazyListState.Saver) { LazyListState() }
    val stateLabelOf = rememberSportsLiveStateLabeler(uiState.selectedTab)

    val items = uiState.visibleItems
    val selected = items.firstOrNull { it.listKey == focusedCardKey && it.id == uiState.selectedItemId }
        ?: items.firstOrNull { it.id == uiState.selectedItemId }
        ?: items.firstOrNull()
    val selectedCardFocusRequester = selected?.let(::cardFocusRequester)
    // Where a move into the list lands: the focused card while on screen, else the first card on
    // screen. Read from the layout, so Down never aims at a card scrolled away (which traps focus).
    val currentItemKeys by rememberUpdatedState(items.map { it.listKey })
    val entryKey by remember(listState) {
        derivedStateOf {
            sportsLiveListEntryKey(
                keys = currentItemKeys,
                focusedKey = focusedCardKey,
                visibleIndices = listState.layoutInfo.visibleItemsInfo.map { it.index }
            )
        }
    }
    val entryCardFocusRequester = items.firstOrNull { it.listKey == entryKey }?.let(::cardFocusRequester)
        ?: selectedCardFocusRequester
    // Attached to whichever filter chip is selected; the row only shows with two or more values.
    val filterFocusRequester = remember { FocusRequester() }
    val showFilterRow = uiState.filters.size >= 2
    val aboveListFocusRequester = if (showFilterRow) filterFocusRequester else activeTabFocusRequester
    // On the Results tab, the selected date chip (the tab row's Down).
    val resultsDateFocusRequester = remember { FocusRequester() }
    val showingResults = uiState.selectedTab == SportsLiveTab.RESULTS
    // Scores Off: no Results tab at all.
    // Shown only once the setting is read, so a Scores-Off user never sees it flash in.
    val shownTabs = SportsLiveTab.entries.filter { it != SportsLiveTab.RESULTS || (results.modeKnown && results.mode != ScoresMode.OFF) }
    // A refresh can drop the filter row while a chip holds focus; focus then goes to the active tab
    // instead of being lost. Losing focus because the row is being removed does not clear the flag.
    var filterRowHasFocus by remember { mutableStateOf(false) }
    val currentShowFilterRow by rememberUpdatedState(showFilterRow)
    LaunchedEffect(showFilterRow) {
        if (showFilterRow || !filterRowHasFocus) return@LaunchedEffect
        filterRowHasFocus = false
        withFrameNanos { }
        runCatching { activeTabFocusRequester.requestFocus() }
    }

    // Replay "series" items are team folders; the detail page lists their videos.
    val open: (SportsLiveItem) -> Unit = { item ->
        if (item.kind == SportsLiveKind.REPLAY && item.apiType == "series") onOpenDetail(item) else onPlay(item)
    }

    // The card whose long-press menu is open; the menu closes if a refresh drops the card.
    var menuCardKey by remember { mutableStateOf<String?>(null) }
    // A follow moves the card (to the top, or back down); focus and the scroll go with it. The
    // reorder lands a moment after the menu closes, so the card is followed for a short while, or
    // until focus lands anywhere else in the screen or the tab or filter changes.
    var followedCardKey by remember { mutableStateOf<String?>(null) }
    // Where the followed card last was, for when an unfollow drops it (under "★ My teams").
    var followedCardIndex by remember { mutableIntStateOf(0) }
    LaunchedEffect(followedCardKey) {
        if (followedCardKey == null) return@LaunchedEffect
        delay(FOLLOW_REFOCUS_WINDOW_MS)
        followedCardKey = null
    }
    LaunchedEffect(followedCardKey, items) {
        val key = followedCardKey ?: return@LaunchedEffect
        val index = items.indexOfFirst { it.listKey == key }
        val gone = index < 0
        if (!gone) followedCardIndex = index
        // Gone from the list: the card that took its place (or the last one) gets focus instead.
        val targetIndex = if (gone) followedCardIndex.coerceAtMost(items.lastIndex) else index
        val target = items.getOrNull(targetIndex)
        if (target == null) {
            runCatching { activeTabFocusRequester.requestFocus() }
        } else {
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == targetIndex }) listState.scrollToItem(targetIndex)
            repeat(2) { withFrameNanos { } }
            runCatching { cardFocusRequester(target).requestFocus() }
        }
        // Cleared last: clearing restarts this effect, which would cancel the focus request above.
        if (gone) followedCardKey = null
    }
    // "Watch with another game": any card on Live now or Channels (games and channels alike).
    val canWatchWith: (SportsLiveItem) -> Boolean = { uiState.selectedTab.offersDual() }
    // Pick-second mode: the first game, chosen in the menu; OK on another eligible card opens dual
    // screen with both. Back leaves the mode; leaving the section drops it (not saved).
    var pickFirst by remember { mutableStateOf<SportsLiveItem?>(null) }
    // Pick mode survives tab changes (tabs select on focus, so Live now → Channels passes Upcoming);
    // OK on Upcoming/Replays cards is ignored while picking.
    LaunchedEffect(pickFirst) {
        val first = pickFirst ?: return@LaunchedEffect
        // The menu has just closed: focus goes back to the first game's card.
        repeat(2) { withFrameNanos { } }
        runCatching { cardFocusRequesters[first.listKey]?.requestFocus() }
    }
    items.firstOrNull { it.listKey == menuCardKey }?.let { item ->
        val watchWith = canWatchWith(item)
        val teams = item.teams
        if (teams == null && !watchWith) return@let
        SportsLiveCardMenu(
            title = item.meta.name,
            sides = teams?.inTitleOrder(item.meta.name).orEmpty().map { side ->
                side to followedTeams.any { SportsLiveTeams.followsSideOfAnySource(it, side, item) }
            },
            onWatchWith = if (watchWith) {
                {
                    menuCardKey = null
                    pickFirst = item
                }
            } else {
                null
            },
            onChooseStream = if (sportsLiveAutoPlayEligible(item)) {
                {
                    menuCardKey = null
                    onChooseStream(item)
                }
            } else {
                null
            },
            onToggleFollow = { side ->
                menuCardKey = null
                followedCardKey = item.listKey
                followedCardIndex = items.indexOf(item).coerceAtLeast(0)
                onToggleFollow(item, side)
            },
            onDetails = {
                menuCardKey = null
                onOpenDetail(item)
            },
            onDismiss = { menuCardKey = null }
        )
    }

    BackHandler(enabled = listHasFocus) {
        runCatching { activeTabFocusRequester.requestFocus() }
    }
    // Composed after the list's handler, so Back leaves pick mode first.
    BackHandler(enabled = pickFirst != null) {
        pickFirst = null
    }

    // The preview follows the focused card (in the list, or its pane's buttons) while no menu, pick
    // mode or dialog is open; anything else stops it.
    val previewItem = selected?.takeIf {
        (listHasFocus || paneHasFocus) && menuCardKey == null && pickFirst == null && !dialogOpen
    }
    val currentOnPreviewFocus by rememberUpdatedState(onPreviewFocus)
    LaunchedEffect(previewItem?.listKey) { currentOnPreviewFocus(previewItem) }
    DisposableEffect(Unit) { onDispose { currentOnPreviewFocus(null) } }

    // Back from another screen: put the list back on the card that had focus. Runs once per entry.
    LaunchedEffect(Unit) {
        val key = focusedCardKey ?: return@LaunchedEffect
        val index = items.indexOfFirst { it.listKey == key }
        if (index < 0) return@LaunchedEffect
        listState.scrollToItem(index)
        repeat(2) { withFrameNanos { } }
        runCatching { cardFocusRequesters[key]?.requestFocus() }
    }

    // A refresh or an ended verdict can drop the focused card. The selection (and with it the pane
    // and preview) moves to the card that took its place, never staying on a card no longer listed,
    // and focus follows when it was in the list, instead of falling onto the filter chips.
    val itemKeys = remember(items) { items.map { it.listKey } }
    var shownListing by remember { mutableStateOf(filterKey to itemKeys) }
    // Read while composing the change, before the removed card's focus is gone.
    val listFocusedAtChange = remember(itemKeys) { listHasFocus }
    LaunchedEffect(filterKey, itemKeys) {
        val (shownFilterKey, shownKeys) = shownListing
        shownListing = filterKey to itemKeys
        // A tab or filter change is not a removal; a follow refocus handles its own card.
        if (shownFilterKey != filterKey || followedCardKey != null) return@LaunchedEffect
        val key = focusedCardKey ?: return@LaunchedEffect
        if (key in itemKeys) return@LaunchedEffect
        val nextKey = sportsLiveKeyAfterRemoval(shownKeys, itemKeys, key)
        val next = items.firstOrNull { it.listKey == nextKey }
        focusedCardKey = nextKey
        onSelectItem(next?.id)
        if (!listFocusedAtChange) return@LaunchedEffect
        if (next == null) {
            runCatching { activeTabFocusRequester.requestFocus() }
            return@LaunchedEffect
        }
        val index = items.indexOf(next)
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == index }) listState.scrollToItem(index)
        repeat(2) { withFrameNanos { } }
        runCatching { cardFocusRequester(next).requestFocus() }
    }

    // Only a real tab or filter change scrolls to the top; the saved value survives navigation.
    var listedFilterKey by rememberSaveable { mutableStateOf(filterKey) }
    LaunchedEffect(filterKey) {
        if (filterKey == listedFilterKey) return@LaunchedEffect
        listedFilterKey = filterKey
        // A follow refocus still pending means the change came from the unfollow itself (it cleared
        // "★ My teams"), not from a chip, so focus goes to the new list's first card.
        val refocus = followedCardKey != null
        followedCardKey = null
        // The new list starts at its first card: nothing restores the old list's card or scroll.
        focusedCardKey = null
        listState.scrollToItem(0)
        if (refocus) {
            repeat(2) { withFrameNanos { } }
            runCatching { selectedCardFocusRequester?.requestFocus() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = NuvioTheme.spacing.screen.horizontal, vertical = NuvioTheme.spacing.screen.vertical)
    ) {
        val openList: () -> Unit = { runCatching { entryCardFocusRequester?.requestFocus() } }
        val miniPlayer = SportsLiveMiniPlayers.controller
        val miniState by miniPlayer.state.collectAsStateWithLifecycle()
        val nowPlaying = (miniState as? SportsLiveMiniState.Playing)?.item
        // A card that goes away (idle timeout, profile switch) while focused hands focus to the active tab.
        var nowPlayingHasFocus by remember { mutableStateOf(false) }
        LaunchedEffect(nowPlaying == null) {
            if (nowPlaying != null || !nowPlayingHasFocus) return@LaunchedEffect
            nowPlayingHasFocus = false
            withFrameNanos { }
            runCatching { activeTabFocusRequester.requestFocus() }
        }
        if (nowPlaying != null) {
            SportsLiveNowPlayingCard(
                name = nowPlaying.meta.name,
                onExpand = { miniPlayer.requestExpand() },
                onStop = {
                    runCatching { activeTabFocusRequester.requestFocus() }
                    miniPlayer.stop(SportsLiveMiniStop.USER_STOP)
                },
                modifier = Modifier
                    .padding(bottom = NuvioTheme.spacing.xs)
                    // Losing focus because the card is being removed keeps the flag.
                    .onFocusChanged { if (it.hasFocus || miniPlayer.isActive) nowPlayingHasFocus = it.hasFocus }
            )
        }
        SportsLiveTabRow(
            uiState = uiState,
            tabs = shownTabs,
            tabFocusRequesters = tabFocusRequesters,
            downFocusRequester = when {
                showingResults -> resultsDateFocusRequester
                showFilterRow -> filterFocusRequester
                else -> entryCardFocusRequester
            },
            onSelectTab = onSelectTab,
            onOpenTab = openList,
            onRefresh = onRefresh,
            modifier = Modifier.onFocusChanged { if (it.hasFocus) followedCardKey = null }
        )
        if (showingResults) {
            SportsLiveResultsView(
                state = results,
                tabFocusRequester = activeTabFocusRequester,
                dateFocusRequester = resultsDateFocusRequester,
                onSelectDay = onSelectResultsDay,
                onSelectSport = onSelectResultsSport,
                onSelectLeague = onSelectResultsLeague,
                onExpandGroup = onExpandResultsGroup,
                onEditMySports = onEditMySports,
                onRowFocus = onResultFocus,
                onOpen = onOpenResult
            )
            return@Column
        }
        if (showFilterRow) {
            SportsLiveFilterRow(
                uiState = uiState,
                filterFocusRequester = filterFocusRequester,
                upFocusRequester = activeTabFocusRequester,
                downFocusRequester = entryCardFocusRequester,
                onSelectFilter = onSelectFilter,
                onOpenFilter = openList,
                modifier = Modifier
                    .padding(top = NuvioTheme.spacing.xs)
                    .onFocusChanged { state ->
                        if (state.hasFocus) {
                            filterRowHasFocus = true
                            followedCardKey = null
                        } else if (currentShowFilterRow) {
                            filterRowHasFocus = false
                        }
                    }
            )
        }
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
        pickFirst?.let { first ->
            Text(
                text = stringResource(R.string.sports_live_dual_pick_banner, first.meta.name),
                style = MaterialTheme.typography.titleMedium,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = NuvioTheme.spacing.sm)
                    .clip(RoundedCornerShape(NuvioTheme.radii.md))
                    .background(NuvioTheme.colors.FocusBackground)
                    .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.xs)
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
            // Recreated per tab and filter, so its focus restorer never brings back another list's card.
            key(filterKey) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(0.45f)
                    .fillMaxHeight()
                    .onFocusChanged {
                        listHasFocus = it.hasFocus
                        if (!it.hasFocus) onCardFocus(null)
                    }
                    .focusRestorer(entryCardFocusRequester ?: selectedCardFocusRequester)
                    .dpadRepeatThrottle(),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
            ) {
                itemsIndexed(items, key = { _, item -> item.listKey }) { index, item ->
                    SportsLiveListCard(
                        item = item,
                        stateLabel = stateLabelOf(item),
                        selected = item.listKey == selected.listKey,
                        onClick = {
                            val first = pickFirst
                            if (first == null) {
                                open(item)
                            } else {
                                // Pick mode: OK pairs an eligible card (the list as shown is the dual
                                // list); any other card, or a tab without dual screen, ignores it.
                                sportsLivePickModeSession(
                                    chosen = first,
                                    item = item,
                                    tab = uiState.selectedTab,
                                    items = items,
                                    filter = uiState.selectedFilter
                                )?.let { session ->
                                    pickFirst = null
                                    onOpenDual(session)
                                }
                            }
                        },
                        // A card naming a game, or a live game, opens the menu; other cards open Details directly.
                        // Ignored in pick mode.
                        onLongClick = {
                            when {
                                pickFirst != null -> Unit
                                item.teams != null || canWatchWith(item) -> menuCardKey = item.listKey
                                else -> onOpenDetail(item)
                            }
                        },
                        menuOpen = item.listKey == menuCardKey,
                        pickedFirst = item.listKey == pickFirst?.listKey,
                        art = artOf(item),
                        chip = chipOf(item),
                        onFocus = {
                            onCardFocus(item.streamKey)
                            if (item.listKey != followedCardKey) followedCardKey = null
                            focusedCardKey = item.listKey
                            onSelectItem(item.id)
                        },
                        modifier = Modifier
                            .focusRequester(cardFocusRequester(item))
                            .focusProperties {
                                right = watchFocusRequester
                                if (index == 0) up = aboveListFocusRequester
                            }
                    )
                }
            }
            }
            val selectedPreview = preview?.takeIf { it.item.listKey == selected.listKey }
            SportsLiveDetailsPane(
                item = selected,
                stateLabel = stateLabelOf(selected),
                onWatch = { open(selected) },
                onDetails = { onOpenDetail(selected) },
                onChooseStream = if (sportsLiveAutoPlayEligible(selected)) {
                    { onChooseStream(selected) }
                } else {
                    null
                },
                preview = if (selectedPreview != null) {
                    { SportsLivePreviewVideo(previewPlayer) }
                } else {
                    null
                },
                previewShowing = selectedPreview?.rendering == true,
                noStream = noStreamKey == selected.listKey,
                art = artOf(selected),
                chip = chipOf(selected),
                scoreDetails = detailsOf(selected),
                modifier = Modifier
                    .weight(0.55f)
                    .fillMaxHeight()
                    .onFocusChanged { paneHasFocus = it.hasFocus },
                watchModifier = Modifier
                    .focusRequester(watchFocusRequester)
                    .focusProperties { left = entryCardFocusRequester ?: selectedCardFocusRequester }
            )
        }
    }
}

@Composable
private fun SportsLiveTabRow(
    uiState: SportsLiveUiState,
    tabs: List<SportsLiveTab>,
    tabFocusRequesters: Map<SportsLiveTab, FocusRequester>,
    downFocusRequester: FocusRequester?,
    onSelectTab: (SportsLiveTab) -> Unit,
    onOpenTab: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Down goes to the selected filter chip (or card), never to whichever chip sits below, since filter chips select on focus.
    val downModifier = Modifier.focusProperties { downFocusRequester?.let { down = it } }
    val activeTabFocusRequester = tabFocusRequesters.getValue(uiState.selectedTab)
    // A plain scrolling Row keeps every chip composed, so the tab FocusRequesters stay attached.
    Row(
        modifier = modifier
            .fillMaxWidth()
            // Entering the row from elsewhere (the pane, the list) lands on the active tab, never on
            // whichever tab sits nearest, since tabs select on focus.
            .focusProperties { onEnter = { activeTabFocusRequester.requestFocus() } }
            .focusGroup()
            .horizontalScroll(rememberScrollState())
            // Room inside the scroll for the focused chip's border, which otherwise clips at the edges.
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEach { tab ->
            val label = stringResource(tab.labelRes())
            val count = uiState.tabs[tab].orEmpty().size
            SourceStatusFilterChip(
                name = if (count > 0) "$label $count" else label,
                isSelected = tab == uiState.selectedTab,
                // Like FolderDetailScreen, focusing a tab selects it; OK moves into the list.
                onFocusSelect = { onSelectTab(tab) },
                onClick = onOpenTab,
                modifier = Modifier
                    .focusRequester(tabFocusRequesters.getValue(tab))
                    .then(downModifier)
            )
        }
        Spacer(modifier = Modifier.width(NuvioTheme.spacing.md))
        RefreshFilterChip(onClick = onRefresh, isLoading = uiState.isLoading, modifier = downModifier)
    }
}

@Composable
private fun SportsLiveFilterRow(
    uiState: SportsLiveUiState,
    filterFocusRequester: FocusRequester,
    upFocusRequester: FocusRequester,
    downFocusRequester: FocusRequester?,
    onSelectFilter: (String?) -> Unit,
    onOpenFilter: () -> Unit,
    modifier: Modifier = Modifier
) {
    @Composable
    fun FilterRowChip(name: String, value: String?) {
        val isSelected = value == uiState.selectedFilter
        SourceStatusFilterChip(
            name = name,
            isSelected = isSelected,
            outlined = true,
            // Like the tabs, focusing a filter selects it; OK moves into the list.
            onFocusSelect = { onSelectFilter(value) },
            onClick = onOpenFilter,
            modifier = Modifier
                .then(if (isSelected) Modifier.focusRequester(filterFocusRequester) else Modifier)
                .focusProperties {
                    up = upFocusRequester
                    downFocusRequester?.let { down = it }
                }
        )
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            // Entering the row from elsewhere (the pane, the list) lands on the active chip, never
            // on whichever chip sits nearest, since chips select on focus.
            .focusProperties { onEnter = { filterFocusRequester.requestFocus() } }
            .focusGroup()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterRowChip(name = stringResource(R.string.sports_live_filter_all), value = null)
        uiState.filters.forEach { filter ->
            val name = if (filter == SportsLiveUiState.MY_TEAMS_FILTER) stringResource(R.string.sports_live_filter_my_teams) else filter
            FilterRowChip(name = name, value = filter)
        }
    }
}

private const val FOLLOW_REFOCUS_WINDOW_MS = 1_500L

/** The two sides in the order the title names them ("Patriots at Jets": Patriots, then Jets). */
private fun TeamPair.inTitleOrder(title: String): List<String> =
    listOf(home, away).sortedBy { side -> title.indexOf(side, ignoreCase = true).let { if (it < 0) Int.MAX_VALUE else it } }

/** Unique within a tab: the bucketer keeps one copy of an item per addon. */
private val SportsLiveItem.listKey: String get() = "$addonId|$id"

private fun SportsLiveTab.labelRes(): Int = when (this) {
    SportsLiveTab.LIVE_NOW -> R.string.sports_live_tab_live_now
    SportsLiveTab.UPCOMING -> R.string.sports_live_tab_upcoming
    SportsLiveTab.CHANNELS -> R.string.sports_live_tab_channels
    SportsLiveTab.REPLAYS -> R.string.sports_live_tab_replays
    SportsLiveTab.RESULTS -> R.string.sports_live_tab_results
}
