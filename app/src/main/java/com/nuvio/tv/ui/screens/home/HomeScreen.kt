package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import android.util.Log
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.core.sportslive.SportsLiveEndedGames
import com.nuvio.tv.core.sportslive.scores.LiveScoresRepository
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.ui.screens.sportslive.SportsLiveCatchUpHomeRow
import com.nuvio.tv.ui.screens.sportslive.SportsLiveCatchUpMenu
import com.nuvio.tv.ui.screens.sportslive.SportsLiveCatchUpViewModel
import com.nuvio.tv.ui.screens.sportslive.SportsLiveGameRequests
import com.nuvio.tv.ui.screens.sportslive.SportsLiveScoresViewModel
import com.nuvio.tv.ui.screens.sportslive.catchUpGameRequest
import com.nuvio.tv.ui.screens.sportslive.catchUpTitle
import com.nuvio.tv.ui.screens.sportslive.rememberCatchUpCardArt
import com.nuvio.tv.ui.components.LocalDrawnCardArt
import androidx.compose.runtime.CompositionLocalProvider
import com.nuvio.tv.ui.screens.sportslive.TrackLiveScores
import com.nuvio.tv.ui.screens.sportslive.rememberScoreChipStrings
import com.nuvio.tv.ui.screens.sportslive.rememberSportsLiveClockMs
import com.nuvio.tv.domain.model.isSeriesLikeType
import com.nuvio.tv.ui.components.ErrorState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.LocalStartupLoadingState
import com.nuvio.tv.ui.components.LocalStartupSplashEnabled
import com.nuvio.tv.ui.components.shouldShowHomeStartupLoader
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.components.PosterCardStyle
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.posteroptions.PosterListPickerDialog
import com.nuvio.tv.core.tracking.LOCAL_LIBRARY_LIST_KEY
import com.nuvio.tv.core.tracking.supportsMembershipFor
import com.nuvio.tv.data.local.StartupAuthNotice
import com.nuvio.tv.ui.components.posteroptions.TrackingRemovalConfirmationDialog
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private data class HomePosterOptionsTarget(
    val item: MetaPreview,
    val addonBaseUrl: String
)

private const val HOME_STABLE_GATE_TIMEOUT_MS = 5_000L

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit = { item ->
        onNavigateToDetail(
            when (item) {
                is ContinueWatchingItem.InProgress -> item.progress.contentId
                is ContinueWatchingItem.NextUp -> item.info.contentId
            },
            when (item) {
                is ContinueWatchingItem.InProgress -> item.progress.contentType
                is ContinueWatchingItem.NextUp -> item.info.contentType
            },
            ""
        )
    },
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit = onContinueWatchingClick,
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit = onContinueWatchingClick,
    onNavigateToCatalogSeeAll: (String, String, String) -> Unit = { _, _, _ -> },
    onNavigateToFolderDetail: (String, String) -> Unit = { _, _ -> },
    onNavigateToSportsLive: () -> Unit = {},
    onLiveNowItemClick: (SportsLiveItem) -> Unit = {},
    /** Opens the Game page on the request just put in [SportsLiveGameRequests] (a Catch up card's menu). */
    onOpenSportsLiveGame: () -> Unit = {},
    /** OK on a Catch up card (#58): its replay plays at once ([SportsLivePlayViewModel.playReplay]), titled by ESPN's matchup. */
    onPlayCatchUpReplay: (replays: List<SportsLiveItem>, title: String) -> Unit = { _, _ -> },
    liveScoresViewModel: SportsLiveScoresViewModel = hiltViewModel(),
    catchUpViewModel: SportsLiveCatchUpViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // The Catch up row (#58): worked out when Home resumes, never on a timer.
    val catchUpItems by catchUpViewModel.items.collectAsStateWithLifecycle()
    val catchUpContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(catchUpItems) { viewModel.setCatchUpItems(catchUpItems) }
    val catchUpCardArt = rememberCatchUpCardArt(uiState.catchUpItems)
    var catchUpMenuKey by remember { mutableStateOf<String?>(null) }
    uiState.catchUpItems.firstOrNull { it.key == catchUpMenuKey }?.let { item ->
        SportsLiveCatchUpMenu(
            item = item,
            onOpenGame = {
                catchUpMenuKey = null
                SportsLiveGameRequests.open(catchUpGameRequest(item))
                onOpenSportsLiveGame()
            },
            onMarkWatched = {
                catchUpMenuKey = null
                catchUpViewModel.markWatched(item)
            },
            onDismiss = { catchUpMenuKey = null }
        )
    }

    // Live now cards get a score label under Show only (Home has no focus reveal), polled only while
    // Home is started.
    val liveScores by liveScoresViewModel.scores.collectAsStateWithLifecycle()
    val liveScoresMode by liveScoresViewModel.mode.collectAsStateWithLifecycle()
    TrackLiveScores(
        screenId = LiveScoresRepository.SCREEN_HOME,
        cards = uiState.liveNowItems,
        enabled = liveScoresMode == ScoresMode.SHOW,
        tracking = liveScoresViewModel.tracking
    )
    val scoreChipStrings = rememberScoreChipStrings()
    val liveScoreFormat = stringResource(R.string.sports_live_chip_home_live)
    val liveNowScoreLabels = remember(uiState.liveNowItems, liveScores, liveScoresMode, scoreChipStrings, liveScoreFormat) {
        SportsLiveHomeRow.scoreLabels(uiState.liveNowItems, liveScores, liveScoresMode, scoreChipStrings, liveScoreFormat)
    }
    LaunchedEffect(liveNowScoreLabels) { viewModel.setLiveNowScoreLabels(liveNowScoreLabels) }
    // Games ESPN confirmed final (#60, past the grace) leave the row; an estimate never drops one.
    val liveClockMs by rememberSportsLiveClockMs()
    val liveNowEnded = remember(uiState.liveNowItems, liveScores, liveClockMs) {
        SportsLiveEndedGames.shared.confirmedEndedKeys(uiState.liveNowItems, liveScores, liveClockMs)
    }
    LaunchedEffect(liveNowEnded) { viewModel.setLiveNowEnded(liveNowEnded) }
    // V2 phase 10: Live now games are drawn as the section's game card; See all counts every live game.
    val liveNowCardArt = rememberLiveNowCardArt(uiState.liveNowItems, liveScores, liveScoresMode)
    val sportsCardArt = remember(catchUpCardArt, liveNowCardArt) { drawnArtOf(catchUpCardArt, liveNowCardArt) }
    val liveGameCount = remember(uiState.liveNowItems, liveNowEnded) {
        viewModel.liveNowAllItems.count { !it.isChannel && it.streamKey !in liveNowEnded }
    }
    val liveNowSeeAll = if (liveGameCount > 0) stringResource(R.string.sports_live_home_see_all_live, liveGameCount) else null

    // Home was the only major screen without a lifecycle observer, so nothing ever told it to
    // look at its catalogs again.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                catchUpViewModel.onOpened()
                viewModel.beginShuffleHomeVisit()
                viewModel.refreshHomeCatalogsIfStale()
                viewModel.refreshContinueWatchingIfReleased()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val modernPresentation by viewModel.modernHomePresentation.collectAsStateWithLifecycle()
    val initialCwResolved by viewModel.initialCwResolved.collectAsStateWithLifecycle()
    val scrollToTopTrigger by viewModel.scrollToTopTrigger.collectAsStateWithLifecycle()
    val effectiveAutoplayEnabled by viewModel.effectiveAutoplayEnabled.collectAsStateWithLifecycle(
        initialValue = false
    )
    val hasCatalogContent = uiState.catalogRows.any { it.items.isNotEmpty() }
    val hasCollectionContent = uiState.homeRows.any { it is HomeRow.CollectionRow }
    val hasHeroContent = uiState.heroItems.isNotEmpty()
    val modernPresentationReady =
        uiState.homeLayout != HomeLayout.MODERN ||
            modernPresentation.rows.list.isNotEmpty() ||
            (uiState.heroSectionEnabled && hasHeroContent && !hasCatalogContent && !hasCollectionContent)
    var showHomeContentWithAnimation by rememberSaveable { mutableStateOf(false) }
    var hasShownInitialHomeContent by rememberSaveable { mutableStateOf(false) }
    // Once we've shown stable home content, never go back to loading gate.
    var homeStableGateReleased by rememberSaveable { mutableStateOf(false) }
    // Track that catalog loading has started at least once (isLoading went true→false).
    var catalogLoadingStarted by rememberSaveable { mutableStateOf(false) }
    var posterOptionsTarget by remember { mutableStateOf<HomePosterOptionsTarget?>(null) }

    LaunchedEffect(uiState.homeLayout) {
        if (uiState.homeLayout != HomeLayout.MODERN) {
            HeroBackdropState.update(null)
        }
    }

    // Notify ViewModel of locale changes after activity recreation
    LaunchedEffect(Unit) {
        viewModel.notifyLocaleChanged()
    }

    val movieWatchedStatus = uiState.movieWatchedStatus
    // Use a stable lambda whose identity NEVER changes. The lambda captures
    // movieWatchedStatus via rememberUpdatedState so it always reads the latest
    // value without forcing downstream recomposition from lambda identity change.
    val latestMovieWatchedStatus = androidx.compose.runtime.rememberUpdatedState(movieWatchedStatus)
    val isCatalogItemWatched: (MetaPreview) -> Boolean = remember {
        { item: MetaPreview -> latestMovieWatchedStatus.value[homeItemStatusKey(item.id, item.apiType)] == true }
    }
    val onCatalogItemLongPress: (MetaPreview, String) -> Unit = remember {
        { item, addonBaseUrl ->
            // Live now cards have no poster options; a long press on them does nothing. A Catch up card's
            // offers Game stats (score behind Reveal) and Mark as watched.
            if (SportsLiveCatchUpHomeRow.isCatchUpRow(addonBaseUrl)) {
                catchUpMenuKey = SportsLiveCatchUpHomeRow.eventIdOf(item.id)
            } else if (!SportsLiveHomeRow.isLiveNowRow(addonBaseUrl)) {
                posterOptionsTarget = HomePosterOptionsTarget(item, addonBaseUrl)
                viewModel.refreshPosterLibraryStatus(item)
            }
        }
    }

    // Cards of the Live now row open the Sports & Live item, not an addon detail page.
    val onNavigateToDetailStable: (String, String, String) -> Unit = remember(onNavigateToDetail, onLiveNowItemClick, onPlayCatchUpReplay, viewModel) {
        { itemId, itemType, addonBaseUrl ->
            if (SportsLiveCatchUpHomeRow.isCatchUpRow(addonBaseUrl)) {
                // OK plays the replay at once: never the addon's page or Stream screen (spoilers).
                viewModel.findCatchUpItem(itemId)?.let { item ->
                    onPlayCatchUpReplay(item.replays, catchUpTitle(catchUpContext, item))
                }
            } else if (SportsLiveHomeRow.isLiveNowRow(addonBaseUrl)) {
                viewModel.findLiveNowItem(itemId)?.let { item ->
                    viewModel.startLiveNowZapSession(item)
                    onLiveNowItemClick(item)
                }
            } else {
                viewModel.beginTrailerHandoff(itemId)
                onNavigateToDetail(itemId, itemType, addonBaseUrl)
            }
        }
    }
    val latestContinueWatchingClickAction = androidx.compose.runtime.rememberUpdatedState(uiState.continueWatchingClickAction)
    val onContinueWatchingClickStable = remember(onContinueWatchingClick, onNavigateToDetail) {
        { item: ContinueWatchingItem ->
            if (latestContinueWatchingClickAction.value.opensDetails(item)) {
                when (item) {
                    is ContinueWatchingItem.InProgress -> onNavigateToDetail(item.progress.contentId, item.progress.contentType, "")
                    is ContinueWatchingItem.NextUp -> onNavigateToDetail(item.info.contentId, item.info.contentType, "")
                }
            } else {
                onContinueWatchingClick(item)
            }
        }
    }
    val onContinueWatchingStartFromBeginningStable = remember(onContinueWatchingStartFromBeginning) { onContinueWatchingStartFromBeginning }
    val onContinueWatchingPlayManuallyStable = remember(onContinueWatchingPlayManually) { onContinueWatchingPlayManually }
    val onNavigateToCatalogSeeAllStable: (String, String, String) -> Unit = remember(onNavigateToCatalogSeeAll, onNavigateToSportsLive) {
        { catalogId, addonId, type ->
            if (addonId == SportsLiveHomeRow.ADDON_ID) {
                onNavigateToSportsLive()
            } else {
                onNavigateToCatalogSeeAll(catalogId, addonId, type)
            }
        }
    }
    val onNavigateToFolderDetailStable = remember(onNavigateToFolderDetail) { onNavigateToFolderDetail }
    val onRemoveContinueWatchingStable = remember(viewModel) {
        { contentId: String, season: Int?, episode: Int?, isNextUp: Boolean ->
            viewModel.onEvent(HomeEvent.OnRemoveContinueWatching(contentId, season, episode, isNextUp))
        }
    }

    LaunchedEffect(
        uiState.isLoading,
        hasCatalogContent,
        hasCollectionContent,
        hasHeroContent,
        initialCwResolved,
        modernPresentationReady
    ) {
        // Track that addons are known (even if isLoading flipped too fast to catch).
        if (uiState.installedAddonsCount > 0) {
            catalogLoadingStarted = true
        }
        // Wait until catalog loading has completed with content AND the CW
        // pipeline has completed its first emission.
        if (!homeStableGateReleased &&
            catalogLoadingStarted &&
            !uiState.isLoading &&
            initialCwResolved &&
            modernPresentationReady &&
            // When addons are installed, require at least one catalog row.
            (hasCatalogContent || uiState.installedAddonsCount == 0)
        ) {
            Log.d("HomeGate", "RELEASE: catalogs=$hasCatalogContent cwResolved=$initialCwResolved cwItems=${uiState.continueWatchingItems.size} addons=${uiState.installedAddonsCount}")
            homeStableGateReleased = true
        }
    }

    LaunchedEffect(Unit) {
        // Safety timeout — if catalogs and CW haven't loaded within this
        // window, show whatever is available.  Covers edge cases like
        // clean cache (addons loading from remote sync) and users with
        // no addons at all.
        delay(HOME_STABLE_GATE_TIMEOUT_MS)
        if (!homeStableGateReleased) {
            Log.d("HomeGate", "RELEASE timeout: isLoading=${uiState.isLoading} cwResolved=$initialCwResolved catalogs=$hasCatalogContent cwItems=${uiState.continueWatchingItems.size}")
            homeStableGateReleased = true
        }
    }

    val posterCardStyle = remember(
        uiState.posterCardWidthDp,
        uiState.posterCardCornerRadiusDp
    ) {
        val computedHeightDp = (uiState.posterCardWidthDp * 1.5f).roundToInt()
        PosterCardStyle(
            width = uiState.posterCardWidthDp.dp,
            height = computedHeightDp.dp,
            cornerRadius = uiState.posterCardCornerRadiusDp.dp,
            focusedBorderWidth = PosterCardDefaults.Style.focusedBorderWidth,
            focusedScale = PosterCardDefaults.Style.focusedScale
        )
    }

    val noAddonsError = stringResource(R.string.home_empty_no_sources_title)
    val noCatalogAddonsError = stringResource(R.string.home_error_no_catalog_addons)
    val hasAnyContent = uiState.catalogRows.isNotEmpty() ||
        (uiState.continueWatchingEnabled && uiState.continueWatchingItems.isNotEmpty()) ||
        uiState.heroItems.isNotEmpty() ||
        hasCollectionContent
    val showStartupLoader = when {
        !uiState.layoutPreferencesReady -> true
        uiState.isLoading && !hasAnyContent -> true
        uiState.error == noAddonsError && uiState.catalogRows.isEmpty() -> !homeStableGateReleased
        uiState.error == noCatalogAddonsError && uiState.catalogRows.isEmpty() && !hasCollectionContent && !hasHeroContent -> !homeStableGateReleased
        uiState.error != null && uiState.catalogRows.isEmpty() -> false
        !uiState.isLoading && !hasAnyContent -> !homeStableGateReleased
        else -> !homeStableGateReleased || !modernPresentationReady || !showHomeContentWithAnimation
    }

    // Reports the home screen as fully drawn once it leaves the loading state so startup timing is measurable and post-launch work can be deferred.
    ReportDrawnWhen { !showStartupLoader }

    val startupLoadingState = LocalStartupLoadingState.current
    val showHomeLoader = shouldShowHomeStartupLoader(
        loading = showStartupLoader,
        sharedSplashEnabled = LocalStartupSplashEnabled.current,
        startupComplete = startupLoadingState?.complete != false
    )
    LaunchedEffect(showStartupLoader, startupLoadingState) {
        if (!showStartupLoader) {
            startupLoadingState?.complete = true
        }
    }

    // Catch up cards (#58) and Live now games (V2 phase 10) carry no image: the section's drawn game card stands in.
    CompositionLocalProvider(LocalDrawnCardArt provides sportsCardArt, LocalSportsLiveSeeAll provides liveNowSeeAll) {
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        when {
            !uiState.layoutPreferencesReady -> {
                Unit
            }

            uiState.isLoading && !hasAnyContent -> {
                Unit
            }

            uiState.error == noAddonsError && uiState.catalogRows.isEmpty() -> {
                if (!homeStableGateReleased) {
                    Unit
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.home_no_addons),
                            style = MaterialTheme.typography.bodyLarge,
                            color = NuvioTheme.colors.TextSecondary
                        )
                    }
                }
            }

            uiState.error == noCatalogAddonsError && uiState.catalogRows.isEmpty() && !hasCollectionContent && !hasHeroContent -> {
                if (!homeStableGateReleased) {
                    Unit
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.home_no_catalog_addons),
                            style = MaterialTheme.typography.bodyLarge,
                            color = NuvioTheme.colors.TextSecondary
                        )
                    }
                }
            }

            uiState.error != null && uiState.catalogRows.isEmpty() -> {
                ErrorState(
                    message = uiState.error ?: stringResource(R.string.error_generic),
                    onRetry = { viewModel.onEvent(HomeEvent.OnRetry) }
                )
            }

            !uiState.isLoading && !hasAnyContent -> {
                // Don't show "no catalogs" until the stable gate has released —
                // addons may still be loading from remote after a cache clear.
                if (!homeStableGateReleased) {
                    Unit
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.web_no_catalogs),
                            style = MaterialTheme.typography.bodyLarge,
                            color = NuvioTheme.colors.TextSecondary
                        )
                    }
                }
            }

            else -> {
                // On first launch, wait for stable content before revealing home.
                // Once released, never go back to loading (homeStableGateReleased is rememberSaveable).
                if (!homeStableGateReleased) {
                    Unit
                } else if (!modernPresentationReady) {
                    Unit
                } else {
                    // Flip showHomeContentWithAnimation on the next frame so
                    // AnimatedVisibility can run its enter transition.
                    LaunchedEffect(Unit) {
                        if (!showHomeContentWithAnimation) {
                            kotlinx.coroutines.yield()
                            showHomeContentWithAnimation = true
                        }
                    }
                    LaunchedEffect(showHomeContentWithAnimation) {
                        if (showHomeContentWithAnimation) {
                            hasShownInitialHomeContent = true
                        }
                    }
                    // Keep loading visible during the single-frame gap before animation starts.
                    if (!showHomeContentWithAnimation) {
                        Unit
                    }
                    AnimatedVisibility(
                        visible = showHomeContentWithAnimation,
                        enter = if (hasShownInitialHomeContent) {
                            EnterTransition.None
                        } else {
                            fadeIn(animationSpec = tween(320)) +
                                slideInVertically(
                                    initialOffsetY = { it / 24 },
                                    animationSpec = tween(320)
                                )
                        }
                    ) {
                        when (uiState.homeLayout) {
                            HomeLayout.CLASSIC -> ClassicHomeRoute(
                                viewModel = viewModel,
                                uiState = uiState,
                                posterCardStyle = posterCardStyle,
                                onNavigateToDetail = onNavigateToDetailStable,
                                onContinueWatchingClick = onContinueWatchingClickStable,
                                onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginningStable,
                                onContinueWatchingPlayManually = onContinueWatchingPlayManuallyStable,
                                showContinueWatchingManualPlayOption = effectiveAutoplayEnabled,
                                onNavigateToCatalogSeeAll = onNavigateToCatalogSeeAllStable,
                                onNavigateToFolderDetail = onNavigateToFolderDetailStable,
                                isCatalogItemWatched = isCatalogItemWatched,
                                onCatalogItemLongPress = onCatalogItemLongPress
                            )

                            HomeLayout.GRID -> GridHomeRoute(
                                viewModel = viewModel,
                                uiState = uiState,
                                posterCardStyle = posterCardStyle,
                                onNavigateToDetail = onNavigateToDetailStable,
                                onContinueWatchingClick = onContinueWatchingClickStable,
                                onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginningStable,
                                onContinueWatchingPlayManually = onContinueWatchingPlayManuallyStable,
                                showContinueWatchingManualPlayOption = effectiveAutoplayEnabled,
                                onNavigateToCatalogSeeAll = onNavigateToCatalogSeeAllStable,
                                onNavigateToFolderDetail = onNavigateToFolderDetailStable,
                                isCatalogItemWatched = isCatalogItemWatched,
                                onCatalogItemLongPress = onCatalogItemLongPress
                            )

                            HomeLayout.MODERN -> ModernHomeRoute(
                                viewModel = viewModel,
                                uiState = uiState,
                                onNavigateToDetail = onNavigateToDetailStable,
                                onContinueWatchingClick = onContinueWatchingClickStable,
                                onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginningStable,
                                onContinueWatchingPlayManually = onContinueWatchingPlayManuallyStable,
                                showContinueWatchingManualPlayOption = effectiveAutoplayEnabled,
                                onNavigateToFolderDetail = onNavigateToFolderDetailStable,
                                onNavigateToCatalogSeeAll = onNavigateToCatalogSeeAllStable,
                                isCatalogItemWatched = isCatalogItemWatched,
                                onCatalogItemLongPress = onCatalogItemLongPress
                            )
                        }
                    }
                }
            }
        }

        if (showHomeLoader) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                LoadingIndicator()
            }
        }

        val startupAuthNotice = uiState.startupAuthNotice
        if (startupAuthNotice != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = NuvioTheme.spacing.xl)
                    .background(
                        color = Color(0xFF5A1C1C),
                        shape = RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text(
                    text = when (startupAuthNotice) {
                        StartupAuthNotice.NUVIO -> stringResource(R.string.auth_notice_nuvio_logged_out)
                        StartupAuthNotice.TRAKT -> stringResource(R.string.auth_notice_trakt_logged_out)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextPrimary
                )
            }
        }
    }
    }

    val selectedPoster = posterOptionsTarget
    if (selectedPoster != null) {
        val item = selectedPoster.item
        val statusKey = homeItemStatusKey(item.id, item.apiType)
        val isSeries = isSeriesLikeType(item.apiType)
        HomePosterOptionsDialog(
            title = item.name,
            isInLibrary = uiState.posterLibraryMembership[statusKey] == true,
            isLibraryPending = statusKey in uiState.posterLibraryPending,
            showManageLists = uiState.librarySourceMode != LibrarySourceMode.LOCAL,
            isWatched = movieWatchedStatus[statusKey] == true,
            isWatchedPending = statusKey in uiState.movieWatchedPending,
            onDismiss = { posterOptionsTarget = null },
            onDetails = {
                onNavigateToDetailStable(item.id, item.apiType, selectedPoster.addonBaseUrl)
                posterOptionsTarget = null
            },
            onToggleLibrary = {
                if (uiState.librarySourceMode != LibrarySourceMode.LOCAL) {
                    viewModel.openPosterListPicker(item, selectedPoster.addonBaseUrl)
                } else {
                    viewModel.togglePosterLibrary(item, selectedPoster.addonBaseUrl)
                }
                posterOptionsTarget = null
            },
            onToggleWatched = {
                if (isSeries) {
                    viewModel.togglePosterSeriesWatched(item)
                } else {
                    viewModel.togglePosterMovieWatched(item)
                }
                posterOptionsTarget = null
            }
        )
    }

    if (uiState.showPosterListPicker) {
        val localTab = LibraryListTab(
            key = LOCAL_LIBRARY_LIST_KEY,
            title = stringResource(R.string.trakt_library_source_nuvio),
            type = LibraryListTab.Type.WATCHLIST
        )
        val contentType = uiState.posterListPickerContentType.orEmpty()
        val destinationTabs = uiState.libraryListTabs.filter { tab ->
            tab.supportsMembershipFor(contentType)
        }
        PosterListPickerDialog(
            title = uiState.posterListPickerTitle ?: stringResource(R.string.detail_lists_fallback),
            tabs = listOf(localTab) + destinationTabs,
            membership = uiState.posterListPickerMembership,
            isPending = uiState.posterListPickerPending,
            error = uiState.posterListPickerError,
            onToggle = { key -> viewModel.togglePosterListPickerMembership(key) },
            onSave = { viewModel.savePosterListPickerMembership() },
            onDismiss = { viewModel.dismissPosterListPicker() }
        )
    }

    if (uiState.posterListPickerRemovalConfirmations.isNotEmpty()) {
        TrackingRemovalConfirmationDialog(
            itemTitle = uiState.posterListPickerTitle.orEmpty(),
            confirmations = uiState.posterListPickerRemovalConfirmations,
            isPending = uiState.posterListPickerPending,
            onConfirm = viewModel::confirmPosterListPickerRemoval,
            onDismiss = viewModel::cancelPosterListPickerRemoval
        )
    }
}

@Composable
private fun ClassicHomeRoute(
    viewModel: HomeViewModel,
    uiState: HomeUiState,
    posterCardStyle: PosterCardStyle,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit,
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit,
    showContinueWatchingManualPlayOption: Boolean,
    onNavigateToCatalogSeeAll: (String, String, String) -> Unit,
    onNavigateToFolderDetail: (String, String) -> Unit = { _, _ -> },
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit
) {
    val focusState by viewModel.focusState.collectAsStateWithLifecycle()
    val scrollToTopTrigger by viewModel.scrollToTopTrigger.collectAsStateWithLifecycle()
    ClassicHomeContent(
        uiState = uiState,
        posterCardStyle = posterCardStyle,
        focusState = focusState,
        scrollToTopTrigger = scrollToTopTrigger,
        trailerPreviewUrls = viewModel.trailerPreviewUrls,
        trailerPreviewAudioUrls = viewModel.trailerPreviewAudioUrls,
        onNavigateToDetail = onNavigateToDetail,
        onContinueWatchingClick = onContinueWatchingClick,
        onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning,
        onContinueWatchingPlayManually = onContinueWatchingPlayManually,
        showContinueWatchingManualPlayOption = showContinueWatchingManualPlayOption,
        onNavigateToCatalogSeeAll = onNavigateToCatalogSeeAll,
        onNavigateToFolderDetail = onNavigateToFolderDetail,
        onMarkContinueWatchingWatched = remember(viewModel) {
            { item -> viewModel.onEvent(HomeEvent.OnMarkContinueWatchingWatched(item)) }
        },
        onRemoveContinueWatching = { contentId, season, episode, isNextUp ->
            viewModel.onEvent(HomeEvent.OnRemoveContinueWatching(contentId, season, episode, isNextUp))
        },
        isCatalogItemWatched = isCatalogItemWatched,
        onCatalogItemLongPress = onCatalogItemLongPress,
        onRequestTrailerPreview = { item ->
            viewModel.requestTrailerPreview(item)
        },
        onItemFocus = { item ->
            viewModel.onItemFocus(item)
        },
        onSaveFocusState = { vi, vo, rk, ikm, m, ma, ri, ii ->
            viewModel.saveFocusState(vi, vo, rk, ikm, m, ma, ri, ii)
            // Authoritative: this is the row that actually held focus when Home went away.
            viewModel.setLiveFocusedRowKey(rk)
        },
        onFocusedRowKeyChanged = remember(viewModel) {
            { key: String? -> viewModel.setLiveFocusedRowKey(key) }
        },
        onRequestLazyCatalogLoad = remember(viewModel) {
            { catalogKey: String -> viewModel.requestLazyCatalogLoad(catalogKey) }
        }
    )
}

@Composable
private fun GridHomeRoute(
    viewModel: HomeViewModel,
    uiState: HomeUiState,
    posterCardStyle: PosterCardStyle,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit,
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit,
    showContinueWatchingManualPlayOption: Boolean,
    onNavigateToCatalogSeeAll: (String, String, String) -> Unit,
    onNavigateToFolderDetail: (String, String) -> Unit = { _, _ -> },
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit
) {
    val gridFocusState by viewModel.gridFocusState.collectAsStateWithLifecycle()
    val scrollToTopTrigger by viewModel.scrollToTopTrigger.collectAsStateWithLifecycle()
    GridHomeContent(
        onFocusedRowKeyChanged = remember(viewModel) {
            { key: String? -> viewModel.setLiveFocusedRowKey(key) }
        },
        uiState = uiState,
        posterCardStyle = posterCardStyle,
        gridFocusState = gridFocusState,
        scrollToTopTrigger = scrollToTopTrigger,
        onNavigateToDetail = onNavigateToDetail,
        onContinueWatchingClick = onContinueWatchingClick,
        onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning,
        onContinueWatchingPlayManually = onContinueWatchingPlayManually,
        showContinueWatchingManualPlayOption = showContinueWatchingManualPlayOption,
        onNavigateToCatalogSeeAll = onNavigateToCatalogSeeAll,
        onNavigateToFolderDetail = onNavigateToFolderDetail,
        onMarkContinueWatchingWatched = remember(viewModel) {
            { item -> viewModel.onEvent(HomeEvent.OnMarkContinueWatchingWatched(item)) }
        },
        onRemoveContinueWatching = remember(viewModel) {
            { contentId, season, episode, isNextUp ->
                viewModel.onEvent(HomeEvent.OnRemoveContinueWatching(contentId, season, episode, isNextUp))
            }
        },
        isCatalogItemWatched = isCatalogItemWatched,
        onCatalogItemLongPress = onCatalogItemLongPress,
        onItemFocus = remember(viewModel) {
            { item ->
                viewModel.onItemFocus(item)
            }
        },
        onSaveGridFocusState = remember(viewModel) {
            { vi, vo, key ->
                viewModel.saveGridFocusState(vi, vo, focusedItemKey = key)
            }
        }
    )
}

@Composable
private fun ModernHomeRoute(
    viewModel: HomeViewModel,
    uiState: HomeUiState,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit,
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit,
    showContinueWatchingManualPlayOption: Boolean,
    onNavigateToFolderDetail: (String, String) -> Unit = { _, _ -> },
    onNavigateToCatalogSeeAll: (String, String, String) -> Unit = { _, _, _ -> },
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit
) {
    val focusState by viewModel.focusState.collectAsStateWithLifecycle()
    val scrollToTopTrigger by viewModel.scrollToTopTrigger.collectAsStateWithLifecycle()
    val modernPresentation by viewModel.modernHomePresentation.collectAsStateWithLifecycle()
    // Passed down as State, not values: enrichment changes on most focus moves, and reading it
    // here recomposed all of the home content for each one.
    val enrichingItemId = viewModel.enrichingItemId.collectAsStateWithLifecycle()
    val enrichedPreviews = viewModel.enrichedPreviews.collectAsStateWithLifecycle()
    val failedEnrichmentIds = viewModel.failedEnrichmentIds.collectAsStateWithLifecycle()
    val requestTrailerPreview = remember(viewModel) {
        { itemId: String, title: String, releaseInfo: String?, apiType: String ->
            viewModel.requestTrailerPreview(itemId, title, releaseInfo, apiType)
        }
    }
    val loadMoreCatalog = remember(viewModel) {
        { catalogId: String, addonId: String, type: String ->
            viewModel.onEvent(HomeEvent.OnLoadMoreCatalog(catalogId, addonId, type))
        }
    }
    val removeContinueWatching = remember(viewModel) {
        { contentId: String, season: Int?, episode: Int?, isNextUp: Boolean ->
            viewModel.onEvent(HomeEvent.OnRemoveContinueWatching(contentId, season, episode, isNextUp))
        }
    }
    val saveModernFocusState = remember(viewModel) {
        { vi: Int, vo: Int, rk: String?, ikm: Map<String, String>, m: Map<String, Int>, ma: Map<String, String>, ri: Int, ii: Int ->
            viewModel.saveFocusState(vi, vo, rk, ikm, m, ma, ri, ii)
            // Authoritative: this is the row that actually held focus when Home went away.
            viewModel.setLiveFocusedRowKey(rk)
        }
    }
    val preloadAdjacentItem = remember(viewModel) {
        { item: MetaPreview ->
            viewModel.preloadAdjacentItem(item)
        }
    }
    ModernHomeContent(
        uiState = uiState,
        modernPresentation = modernPresentation,
        focusState = focusState,
        scrollToTopTrigger = scrollToTopTrigger,
        enrichingItemId = enrichingItemId,
        enrichedPreviews = enrichedPreviews,
        failedEnrichmentIds = failedEnrichmentIds,
        trailerPreviewUrls = viewModel.trailerPreviewUrls,
        trailerPreviewAudioUrls = viewModel.trailerPreviewAudioUrls,
        onNavigateToDetail = onNavigateToDetail,
        onContinueWatchingClick = onContinueWatchingClick,
        onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning,
        onContinueWatchingPlayManually = onContinueWatchingPlayManually,
        showContinueWatchingManualPlayOption = showContinueWatchingManualPlayOption,
        onRequestTrailerPreview = requestTrailerPreview,
        onLoadMoreCatalog = loadMoreCatalog,
        onMarkContinueWatchingWatched = remember(viewModel) {
            { item -> viewModel.onEvent(HomeEvent.OnMarkContinueWatchingWatched(item)) }
        },
        onRemoveContinueWatching = removeContinueWatching,
        isCatalogItemWatched = isCatalogItemWatched,
        onCatalogItemLongPress = onCatalogItemLongPress,
        onNavigateToFolderDetail = onNavigateToFolderDetail,
        onNavigateToCatalogSeeAll = onNavigateToCatalogSeeAll,
        onItemFocus = remember(viewModel) {
            { item -> viewModel.onItemFocus(item) }
        },
        onPreloadAdjacentItem = preloadAdjacentItem,
        onSaveFocusState = saveModernFocusState,
        onFocusedRowKeyChanged = remember(viewModel) {
            { key: String? -> viewModel.setLiveFocusedRowKey(key) }
        },
        onRequestLazyCatalogLoad = remember(viewModel) {
            { catalogKey: String -> viewModel.requestLazyCatalogLoad(catalogKey) }
        }
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HomePosterOptionsDialog(
    title: String,
    isInLibrary: Boolean,
    isLibraryPending: Boolean,
    showManageLists: Boolean,
    isWatched: Boolean,
    isWatchedPending: Boolean,
    onDismiss: () -> Unit,
    onDetails: () -> Unit,
    onToggleLibrary: () -> Unit,
    onToggleWatched: () -> Unit
) {
    val primaryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        primaryFocusRequester.requestFocus()
    }

    NuvioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = stringResource(R.string.home_poster_dialog_subtitle)
    ) {
        Button(
            onClick = onDetails,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(primaryFocusRequester),
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                contentColor = NuvioTheme.colors.TextPrimary
            )
        ) {
            Text(stringResource(R.string.cw_action_go_to_details))
        }

        Button(
            onClick = onToggleLibrary,
            enabled = !isLibraryPending,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                contentColor = NuvioTheme.colors.TextPrimary
            )
        ) {
            Text(
                if (showManageLists) {
                    stringResource(R.string.library_manage_lists)
                } else {
                    if (isInLibrary) {
                        stringResource(R.string.hero_remove_from_library)
                    } else {
                        stringResource(R.string.hero_add_to_library)
                    }
                }
            )
        }

        Button(
            onClick = onToggleWatched,
            enabled = !isWatchedPending,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                contentColor = NuvioTheme.colors.TextPrimary
            )
        ) {
            Text(
                if (isWatched) {
                    stringResource(R.string.hero_mark_unwatched)
                } else {
                    stringResource(R.string.hero_mark_watched)
                }
            )
        }
    }
}

