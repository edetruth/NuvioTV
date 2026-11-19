package com.nuvio.tv.ui.navigation

import com.nuvio.tv.core.sportslive.SportsLiveToastKind
import com.nuvio.tv.core.sportslive.SportsLiveToasts
import com.nuvio.tv.ui.theme.NuvioMotion
import com.nuvio.tv.ui.components.PlaybackAvailabilityProvider
import com.nuvio.tv.ui.components.LocalPlaybackAvailability
import com.nuvio.tv.ui.components.canStream
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.nuvio.tv.R
import com.nuvio.tv.LocalRootContentInset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import com.nuvio.tv.core.player.LocalBackgroundTrailerController
import com.nuvio.tv.ui.components.BackgroundTrailerLayer
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.compose.animation.core.tween
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.core.party.PartyMedia
import com.nuvio.tv.domain.model.ExperienceMode
import com.nuvio.tv.data.mediaserver.ServerCatalog
import com.nuvio.tv.ui.screens.CatalogSeeAllScreen
import com.nuvio.tv.ui.screens.ExperienceModeSelectionScreen
import com.nuvio.tv.ui.screens.LayoutSelectionScreen
import com.nuvio.tv.ui.screens.detail.MetaDetailsScreen
import com.nuvio.tv.ui.screens.home.HomeScreen
import com.nuvio.tv.ui.screens.addon.AddonManagerScreen
import com.nuvio.tv.ui.screens.addon.CatalogOrderScreen
import com.nuvio.tv.ui.screens.library.LibraryScreen
import com.nuvio.tv.ui.screens.player.PlayerExitReason
import com.nuvio.tv.ui.screens.player.PlayerScreen
import com.nuvio.tv.ui.screens.player.PlayerViewModel
import com.nuvio.tv.ui.screens.player.PostPlayRecommendation
import com.nuvio.tv.ui.screens.player.playerBackOpensCurrentEpisodeStreams
import com.nuvio.tv.ui.screens.party.PartyScreen
import com.nuvio.tv.ui.screens.plugin.PluginScreen
import com.nuvio.tv.ui.screens.search.DiscoverScreen
import com.nuvio.tv.ui.screens.search.SearchScreen
import com.nuvio.tv.ui.screens.settings.AboutScreen
import com.nuvio.tv.ui.screens.settings.LayoutSettingsScreen
import com.nuvio.tv.ui.screens.settings.SportsLiveMySportsScreen
import com.nuvio.tv.ui.screens.sportslive.SportsLiveTeamSearchScreen
import com.nuvio.tv.ui.screens.settings.LicensesAttributionsScreen
import com.nuvio.tv.ui.screens.settings.PlaybackSettingsScreen
import com.nuvio.tv.ui.screens.settings.SettingsScreen
import com.nuvio.tv.ui.screens.settings.SupportersContributorsScreen
import com.nuvio.tv.ui.screens.settings.ThemeSettingsScreen
import com.nuvio.tv.ui.screens.settings.TrackingSettingsScreen
import com.nuvio.tv.ui.screens.settings.TmdbSettingsScreen
import com.nuvio.tv.ui.screens.sportslive.SportsLiveScreen
import com.nuvio.tv.ui.screens.sportslive.SportsLiveGameScreen
import com.nuvio.tv.ui.screens.sportslive.SportsLiveTeamScreen
import com.nuvio.tv.ui.screens.sportslive.SportsLivePlayerCardScreen
import com.nuvio.tv.ui.screens.sportslive.HighlightStep
import com.nuvio.tv.ui.screens.sportslive.SportsLiveHighlights
import com.nuvio.tv.ui.screens.sportslive.highlightPlayerRoute
import kotlinx.coroutines.flow.first
import com.nuvio.tv.ui.screens.sportslive.SportsLiveExtraSources
import com.nuvio.tv.ui.screens.sportslive.SportsLivePlayHost
import com.nuvio.tv.ui.screens.sportslive.SportsLivePlayViewModel
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapSessions
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapTarget
import com.nuvio.tv.ui.screens.sportslive.streamRouteFor
import com.nuvio.tv.ui.screens.sportslive.dual.LocalOpenSportsLiveDual
import com.nuvio.tv.ui.screens.sportslive.LocalOpenSportsLiveGame
import com.nuvio.tv.ui.screens.sportslive.dual.SportsLiveDualPopTarget
import com.nuvio.tv.ui.screens.sportslive.dual.SportsLiveDualScreen
import com.nuvio.tv.ui.screens.sportslive.dual.sportsLiveDualEntryPopTarget
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniPlayers
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniStop
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLivePip
import com.nuvio.tv.ui.screens.sportslive.mini.sportsLiveMiniStream
import com.nuvio.tv.ui.screens.stream.StreamPlaybackInfo
import com.nuvio.tv.ui.screens.stream.StreamScreen
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.screens.account.AuthQrSignInScreen
import com.nuvio.tv.ui.screens.cast.CastDetailScreen
import com.nuvio.tv.ui.screens.profile.ProfileSelectionMode
import com.nuvio.tv.ui.screens.profile.ProfileSelectionScreen
import com.nuvio.tv.ui.screens.tmdb.TmdbEntityBrowseScreen
import com.nuvio.tv.ui.screens.home.HeroBackdropState

@Composable
fun NuvioNavHost(
    navController: NavHostController,
    startDestination: String = Screen.Home.route,
    hideBuiltInHeaders: Boolean = false,
    discoverIsRoot: Boolean = false
) {
    PlaybackAvailabilityProvider {
        // The NavController keeps its back stack ViewModels in the store of the owner it is
        // composed under, so use the session store when MainActivity provides one.
        val navViewModelStoreOwner = LocalNavViewModelStoreOwner.current
            ?: checkNotNull(LocalViewModelStoreOwner.current)
        // The player's live games panel opens dual screen through this.
        val openSportsLiveDual: () -> Unit = remember(navController) { { openSportsLiveDualFromPlayer(navController) } }
        // ...and the Game page (Game stats in its Hold OK menu, #140), above the player: Back returns to it.
        val openSportsLiveGame: () -> Unit = remember(navController) { { navController.navigate(Screen.SportsLiveGame.route) } }
        CompositionLocalProvider(
            LocalViewModelStoreOwner provides navViewModelStoreOwner,
            LocalOpenSportsLiveDual provides openSportsLiveDual,
            LocalOpenSportsLiveGame provides openSportsLiveGame
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                LocalBackgroundTrailerController.current?.let { controller ->
                    BackgroundTrailerLayer(controller = controller)
                }
                PlaybackNavHost(navController, startDestination, hideBuiltInHeaders, discoverIsRoot)
            }
        }
    }
}

/** The Player route for a stream the Stream screen (or a Sports & Live zap) resolved to [playbackInfo]. */
private fun playerRouteFor(
    streamUrl: String,
    playbackInfo: StreamPlaybackInfo,
    autoPlayNav: Boolean,
    returnToDetailOnBack: Boolean,
    returnToHomeOnBack: Boolean,
    startFromBeginning: Boolean,
    fromLinkCache: Boolean
): String = Screen.Player.createRoute(
    streamUrl = streamUrl,
    title = playbackInfo.title,
    streamName = playbackInfo.streamName,
    year = playbackInfo.year,
    headers = playbackInfo.headers,
    contentId = playbackInfo.contentId,
    contentType = playbackInfo.contentType,
    contentName = playbackInfo.contentName,
    poster = playbackInfo.poster,
    backdrop = playbackInfo.backdrop,
    logo = playbackInfo.logo,
    videoId = playbackInfo.videoId,
    season = playbackInfo.season,
    episode = playbackInfo.episode,
    episodeTitle = playbackInfo.episodeTitle,
    bingeGroup = playbackInfo.bingeGroup,
    autoPlayNav = autoPlayNav,
    returnToDetailOnBack = returnToDetailOnBack,
    returnToHomeOnBack = returnToHomeOnBack,
    filename = playbackInfo.filename,
    videoHash = playbackInfo.videoHash,
    videoSize = playbackInfo.videoSize,
    startFromBeginning = startFromBeginning,
    addonName = playbackInfo.addonName,
    addonLogo = playbackInfo.addonLogo,
    streamDescription = playbackInfo.streamDescription,
    infoHash = playbackInfo.infoHash,
    fileIdx = playbackInfo.fileIdx,
    sources = playbackInfo.sources,
    contentLanguage = playbackInfo.contentLanguage,
    profileId = playbackInfo.profileId,
    audioDelayMs = playbackInfo.audioDelayMs,
    audioDelayLanguage = playbackInfo.audioDelayLanguage,
    fromLinkCache = fromLinkCache
)

private fun NavHostController.previousIsStreamScreen(): Boolean =
    previousBackStackEntry?.destination?.route?.startsWith("stream/") == true

/** The Player route for a Sports & Live zap target; null when its stream needs the Stream screen. */
private fun sportsLivePlayerRoute(target: SportsLiveZapTarget): String? {
    val playback = target.playback ?: return null
    val streamUrl = playback.url ?: if (playback.isTorrent) "torrent://${playback.infoHash}" else return null
    return playerRouteFor(
        streamUrl = streamUrl,
        playbackInfo = playback,
        // A merged game's auto-picked stream falls back to other sources, as an autoplayed one does.
        autoPlayNav = target.autoPicked,
        returnToDetailOnBack = false,
        returnToHomeOnBack = target.returnToHomeOnBack,
        // No saved position: a live stream then opens at its live edge (Resume, #148).
        startFromBeginning = target.liveEdge,
        fromLinkCache = false
    )
}

/** The route that plays a Sports & Live target: its Player route, else its Stream screen. */
fun sportsLiveTargetRoute(target: SportsLiveZapTarget): String =
    sportsLivePlayerRoute(target) ?: streamRouteFor(target.item, target.returnToHomeOnBack, target.manualSelection)

/**
 * Replaces the playing Player with the zap target's. The first game's Stream screen (when it is
 * below the player) goes too, so Back from any zapped game returns once to where the session began.
 * A stream that needs the Stream screen's preparation opens the target's Stream screen instead.
 */
internal fun navigateToSportsLiveZapTarget(navController: NavHostController, target: SportsLiveZapTarget) {
    val playerRoute = sportsLivePlayerRoute(target)
    val route = playerRoute ?: streamRouteFor(target.item, target.returnToHomeOnBack, target.manualSelection)
    val popUpToRoute = if (navController.previousIsStreamScreen()) Screen.Stream.route else Screen.Player.route
    navController.navigate(route) {
        popUpTo(popUpToRoute) { inclusive = true }
    }
    if (playerRoute == null) {
        // The target's Stream screen is a hop: the stream picked there replaces it, so Back still returns once.
        navController.currentBackStackEntry?.savedStateHandle?.set(SPORTS_LIVE_ZAP_HOP, true)
    }
}

/**
 * Leaves the playing Player for dual screen, on the session already in SportsLiveDualSessions. The
 * player (and its Stream screen when below it, as a zap pops it) goes, so it never plays alongside the
 * two panes; with the section on the stack everything above it goes, so Back from dual screen returns
 * to the section (see sportsLiveDualEntryPopTarget). Like Back from the player, its zap session and
 * the other addons' streams registered for it end.
 */
private fun openSportsLiveDualFromPlayer(navController: NavHostController) {
    val playerEntry = navController.currentBackStackEntry
        ?.takeIf { it.destination.route == Screen.Player.route } ?: return
    SportsLiveZapSessions.clear()
    SportsLiveExtraSources.clear(playerEntry.arguments?.getString("contentId"))
    val stackRoutes = navController.currentBackStack.value.map { it.destination.route }
    val target = sportsLiveDualEntryPopTarget(
        sectionOnStack = Screen.SportsLive.route in stackRoutes,
        // A player opened from dual screen replaces that dual screen rather than stacking a second one.
        dualOnStack = Screen.SportsLiveDual.route in stackRoutes,
        previousIsStreamScreen = navController.previousIsStreamScreen()
    )
    val popUpToRoute = when (target) {
        SportsLiveDualPopTarget.ABOVE_SECTION -> Screen.SportsLive.route
        SportsLiveDualPopTarget.DUAL -> Screen.SportsLiveDual.route
        SportsLiveDualPopTarget.STREAM -> Screen.Stream.route
        SportsLiveDualPopTarget.PLAYER -> Screen.Player.route
    }
    navController.navigate(Screen.SportsLiveDual.route) {
        popUpTo(popUpToRoute) { inclusive = target != SportsLiveDualPopTarget.ABOVE_SECTION }
    }
}

/** Saved state flag of a Stream screen opened by a Sports & Live zap. */
private const val SPORTS_LIVE_ZAP_HOP = "sportsLiveZapHop"

/**
 * Back to anything but the player's own Stream screen ends a Sports & Live zap session, and drops
 * the other addons' streams registered for the player's content.
 */
private fun clearSportsLiveZapSessionOnPlayerBack(navController: NavHostController, contentId: String?) {
    if (navController.previousIsStreamScreen()) return
    SportsLiveZapSessions.clear()
    SportsLiveExtraSources.clear(contentId)
}

/**
 * Back from a player whose content has a Sports & Live zap session starts the mini player on the
 * stream that player plays now (its url and headers, after any source fallback); any other
 * player (movies, series) leaves it alone. A paused player starts none: Live now offers Resume
 * instead (#148). Runs before the session is cleared.
 */
private fun startSportsLiveMiniPlayerOnBack(contentId: String?, player: PlayerViewModel) {
    // An exit for an in-app launch (external player, browser) is not the user going back to browse.
    if (SportsLivePip.ownLaunchInFlight) return
    val session = SportsLiveZapSessions.activeFor(contentId) ?: return
    val playing = player.controller
    SportsLiveMiniPlayers.controller.onBackFromPlayer(
        contentId = contentId,
        session = session,
        stream = sportsLiveMiniStream(
            url = player.getCurrentStreamUrl(),
            headers = player.getCurrentHeaders(),
            responseHeaders = playing.currentStreamResponseHeaders,
            filename = playing.currentFilename,
            name = null,
            addonName = playing.currentAddonName
        ),
        paused = player.isPausedByUser()
    )
}

// Root destinations carry the legacy sidebar inset themselves so the NavHost container
// keeps a constant width while navigating between root and non-root routes.
private fun NavGraphBuilder.rootComposable(
    route: String,
    withInset: Boolean = true,
    content: @Composable (NavBackStackEntry) -> Unit
) {
    composable(route) { backStackEntry ->
        val inset = if (withInset) LocalRootContentInset.current else 0.dp
        Box(modifier = Modifier.padding(start = inset)) {
            content(backStackEntry)
        }
    }
}

@Composable
private fun PlaybackNavHost(
    navController: NavHostController,
    startDestination: String,
    hideBuiltInHeaders: Boolean,
    discoverIsRoot: Boolean
) {
    val playbackAvailability = LocalPlaybackAvailability.current
    val context = LocalContext.current
    fun isStreamToPlayer(from: String, to: String): Boolean {
        return from.startsWith("stream/") && to.startsWith("player/")
    }

    fun isPlayerToStream(from: String, to: String): Boolean {
        return from.startsWith("player/") && to.startsWith("stream/")
    }

    fun openPartyMedia(media: PartyMedia) {
        if (navController.currentDestination?.route?.startsWith("player/") == true) navController.popBackStack()
        navController.navigate(
            Screen.Stream.createRoute(
                videoId = media.videoId ?: media.metaId,
                contentType = media.type,
                title = media.title,
                poster = media.poster,
                backdrop = media.background,
                logo = media.logo,
                season = media.season,
                episode = media.episode,
                episodeName = media.episodeTitle,
                contentId = media.metaId,
                contentName = media.title,
                manualSelection = true
            )
        )
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            val from = initialState.destination.route.orEmpty()
            val to = targetState.destination.route.orEmpty()
            val isAutoPlayNav = targetState.arguments
                ?.getString("autoPlayNav")
                ?.toBooleanStrictOrNull() == true
            if (isStreamToPlayer(from, to) && isAutoPlayNav) {
                EnterTransition.None
            } else {
                fadeIn(animationSpec = tween(NuvioMotion.tokens.durations.medium))
            }
        },
        exitTransition = {
            val from = initialState.destination.route.orEmpty()
            val to = targetState.destination.route.orEmpty()
            val isAutoPlayNav = targetState.arguments
                ?.getString("autoPlayNav")
                ?.toBooleanStrictOrNull() == true
            if (isStreamToPlayer(from, to) && isAutoPlayNav) {
                ExitTransition.None
            } else {
                fadeOut(animationSpec = tween(NuvioMotion.tokens.durations.medium))
            }
        },
        popEnterTransition = {
            val from = initialState.destination.route.orEmpty()
            val to = targetState.destination.route.orEmpty()
            val isAutoPlayNav = initialState.arguments
                ?.getString("autoPlayNav")
                ?.toBooleanStrictOrNull() == true
            if (isPlayerToStream(from, to) && isAutoPlayNav) {
                EnterTransition.None
            } else {
                fadeIn(animationSpec = tween(NuvioMotion.tokens.durations.medium))
            }
        },
        popExitTransition = {
            val from = initialState.destination.route.orEmpty()
            val to = targetState.destination.route.orEmpty()
            val isAutoPlayNav = initialState.arguments
                ?.getString("autoPlayNav")
                ?.toBooleanStrictOrNull() == true
            if (isPlayerToStream(from, to) && isAutoPlayNav) {
                ExitTransition.None
            } else {
                fadeOut(animationSpec = tween(NuvioMotion.tokens.durations.medium))
            }
        }
    ) {
        composable(Screen.ExperienceModeSelection.route) {
            ExperienceModeSelectionScreen(
                onContinue = { mode ->
                    val destination = if (mode == ExperienceMode.ESSENTIAL) {
                        Screen.Home.route
                    } else {
                        Screen.LayoutSelection.route
                    }
                    navController.navigate(destination) {
                        popUpTo(Screen.ExperienceModeSelection.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.LayoutSelection.route) {
            LayoutSelectionScreen(
                onContinue = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.LayoutSelection.route) { inclusive = true }
                    }
                }
            )
        }

        rootComposable(Screen.Home.route) {
            fun createContinueWatchingRoute(
                item: ContinueWatchingItem,
                manualSelection: Boolean = false,
                startFromBeginning: Boolean = false
            ): String {
                return when (item) {
                    is ContinueWatchingItem.InProgress -> Screen.Stream.createRoute(
                        videoId = item.progress.videoId,
                        contentType = item.progress.contentType,
                        title = item.progress.name,
                        poster = item.progress.poster,
                        backdrop = item.progress.backdrop,
                        logo = item.progress.logo,
                        season = item.progress.season,
                        episode = item.progress.episode,
                        episodeName = item.progress.episodeTitle,
                        genres = null,
                        year = null,
                        contentId = item.progress.contentId,
                        contentName = item.progress.name,
                        runtime = null,
                        manualSelection = manualSelection,
                        returnToDetailOnBack = item.progress.contentType.equals("series", ignoreCase = true),
                        returnToHomeOnBack = true,
                        startFromBeginning = startFromBeginning,
                        contentLanguage = item.contentLanguage
                    )
                    is ContinueWatchingItem.NextUp -> Screen.Stream.createRoute(
                        videoId = item.info.videoId,
                        contentType = item.info.contentType,
                        title = item.info.name,
                        poster = item.info.poster,
                        backdrop = item.info.backdrop,
                        logo = item.info.logo,
                        season = item.info.season,
                        episode = item.info.episode,
                        episodeName = item.info.episodeTitle,
                        genres = null,
                        year = null,
                        contentId = item.info.contentId,
                        contentName = item.info.name,
                        runtime = null,
                        manualSelection = manualSelection,
                        returnToDetailOnBack = item.info.contentType.equals("series", ignoreCase = true),
                        returnToHomeOnBack = true,
                        startFromBeginning = startFromBeginning,
                        contentLanguage = item.info.contentLanguage
                    )
                }
            }

            // A merged Live now game plays across its sources, as in the section.
            val sportsLivePlayViewModel: SportsLivePlayViewModel = androidx.hilt.navigation.compose.hiltViewModel()
            SportsLivePlayHost(
                playViewModel = sportsLivePlayViewModel,
                onOpenStreamScreen = { item, manual ->
                    navController.navigate(streamRouteFor(item, returnToHomeOnBack = true, manualSelection = manual))
                },
                onPlayTarget = { target ->
                    navController.navigate(sportsLivePlayerRoute(target) ?: streamRouteFor(target.item, returnToHomeOnBack = true))
                }
            )
            HomeScreen(
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    val heroBackdrop = HeroBackdropState.consumeAndClear()
                    navController.navigate(
                        Screen.Detail.createRoute(
                            itemId = itemId,
                            itemType = itemType,
                            addonBaseUrl = addonBaseUrl,
                            heroBackdropUrl = heroBackdrop
                        )
                    )
                },
                onContinueWatchingClick = onContinueWatchingClick@{ item ->
                    if (!playbackAvailability.canStream(item)) {
                        Toast.makeText(context, R.string.playback_unavailable_message, Toast.LENGTH_SHORT).show()
                        return@onContinueWatchingClick
                    }
                    navController.navigate(createContinueWatchingRoute(item))
                },
                onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning@{ item ->
                    if (!playbackAvailability.canStream(item)) {
                        Toast.makeText(context, R.string.playback_unavailable_message, Toast.LENGTH_SHORT).show()
                        return@onContinueWatchingStartFromBeginning
                    }
                    navController.navigate(
                        createContinueWatchingRoute(item, startFromBeginning = true)
                    )
                },
                onContinueWatchingPlayManually = onContinueWatchingPlayManually@{ item ->
                    if (!playbackAvailability.canStream(item)) {
                        Toast.makeText(context, R.string.playback_unavailable_message, Toast.LENGTH_SHORT).show()
                        return@onContinueWatchingPlayManually
                    }
                    navController.navigate(
                        createContinueWatchingRoute(item, manualSelection = true)
                    )
                },
                onNavigateToCatalogSeeAll = { catalogId, addonId, type ->
                    navController.navigate(Screen.CatalogSeeAll.createRoute(catalogId, addonId, type))
                },
                onNavigateToFolderDetail = { collectionId, folderId ->
                    navController.navigate(Screen.FolderDetail.createRoute(collectionId, folderId))
                },
                onNavigateToSportsLive = {
                    navController.navigate(Screen.SportsLive.route) { launchSingleTop = true }
                },
                // As in the section: a live game plays its best stream (or opens its Stream screen, per setting).
                onLiveNowItemClick = { item -> sportsLivePlayViewModel.play(item, returnToHomeOnBack = true) },
                onOpenSportsLiveGame = { navController.navigate(Screen.SportsLiveGame.route) },
                onPlayCatchUpReplay = { replays, title -> sportsLivePlayViewModel.playReplay(replays, title, returnToHomeOnBack = true) }
            )
        }

        composable(
            route = Screen.Detail.route,
            arguments = listOf(
                navArgument("itemId") { type = NavType.StringType },
                navArgument("itemType") { type = NavType.StringType },
                navArgument("addonBaseUrl") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("returnFocusSeason") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("returnFocusEpisode") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("returnToHomeOnBack") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "false"
                },
                navArgument("heroBackdropUrl") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("playOnLoad") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "false"
                },
                navArgument("manualSelection") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "false"
                }
            )
        ) { backStackEntry ->
            val detailArgs = backStackEntry.arguments
            if (ServerCatalog.isCollection(detailArgs?.getString("itemId"), detailArgs?.getString("itemType"))) {
                ServerCatalogDestination(navController, backStackEntry)
                return@composable
            }
            val savedState = backStackEntry.savedStateHandle
            val returnToHomeOnBack = detailArgs
                ?.getString("returnToHomeOnBack")
                ?.toBooleanStrictOrNull() == true
            val returnFocusSeason by savedState.getStateFlow(
                "returnFocusSeason", detailArgs?.getString("returnFocusSeason")?.toIntOrNull()
            ).collectAsState()
            val returnFocusEpisode by savedState.getStateFlow(
                "returnFocusEpisode", detailArgs?.getString("returnFocusEpisode")?.toIntOrNull()
            ).collectAsState()
            val heroRestoreToken by savedState.getStateFlow(
                "heroRestoreToken", 0
            ).collectAsState()
            val heroBackdropUrl = detailArgs?.getString("heroBackdropUrl")?.takeIf { it.isNotBlank() }
            val playOnLoad = detailArgs?.getString("playOnLoad")?.toBooleanStrictOrNull() == true
            val manualSelection = detailArgs?.getString("manualSelection")?.toBooleanStrictOrNull() == true
            DetailChildHost(parentNavController = navController) { childNav ->
            MetaDetailsScreen(
                returnFocusSeason = returnFocusSeason,
                returnFocusEpisode = returnFocusEpisode,
                heroRestoreToken = heroRestoreToken,
                heroBackdropUrl = heroBackdropUrl,
                playOnLoad = playOnLoad,
                playOnLoadManually = manualSelection,
                onReturnFocusConsumed = {
                    savedState["returnFocusSeason"] = null
                    savedState["returnFocusEpisode"] = null
                },
                onBackPress = {
                    if (returnToHomeOnBack) {
                        val popped = navController.popBackStack(Screen.Home.route, inclusive = false)
                        if (!popped) {
                            navController.navigate(Screen.Home.route) {
                                launchSingleTop = true
                            }
                        }
                    } else {
                        navController.popBackStack()
                    }
                },
                onNavigateToCastDetail = { personId, personName, preferCrew ->
                    childNav.navigate(Screen.CastDetail.createRoute(personId, personName, preferCrew))
                },
                onNavigateToTmdbEntityBrowse = { entityKind, entityId, entityName, sourceType ->
                    childNav.navigate(
                        Screen.TmdbEntityBrowse.createRoute(
                            entityKind = entityKind,
                            entityId = entityId,
                            entityName = entityName,
                            sourceType = sourceType
                        )
                    )
                },
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    childNav.navigateNestedDetail(itemId, itemType, addonBaseUrl)
                },
                onPlayClick = { videoId, contentType, contentId, title, poster, backdrop, logo, season, episode, episodeName, genres, year, runtime, contentLanguage ->
                    navController.navigate(
                        Screen.Stream.createRoute(
                            videoId = videoId,
                            contentType = contentType,
                            title = title,
                            poster = poster,
                            backdrop = backdrop,
                            logo = logo,
                            season = season,
                            episode = episode,
                            episodeName = episodeName,
                            genres = genres,
                            year = year,
                            contentId = contentId,
                            contentName = title,
                            runtime = runtime,
                            returnToDetailOnBack = contentType.equals("series", ignoreCase = true),
                            contentLanguage = contentLanguage
                        )
                    )
                },
                onPlayManuallyClick = { videoId, contentType, contentId, title, poster, backdrop, logo, season, episode, episodeName, genres, year, runtime, contentLanguage ->
                    navController.navigate(
                        Screen.Stream.createRoute(
                            videoId = videoId,
                            contentType = contentType,
                            title = title,
                            poster = poster,
                            backdrop = backdrop,
                            logo = logo,
                            season = season,
                            episode = episode,
                            episodeName = episodeName,
                            genres = genres,
                            year = year,
                            contentId = contentId,
                            contentName = title,
                            runtime = runtime,
                            manualSelection = true,
                            returnToDetailOnBack = contentType.equals("series", ignoreCase = true),
                            contentLanguage = contentLanguage
                        )
                    )
                },
                onPlayStartFromBeginningClick = { videoId, contentType, contentId, title, poster, backdrop, logo, season, episode, episodeName, genres, year, runtime, contentLanguage ->
                    navController.navigate(
                        Screen.Stream.createRoute(
                            videoId = videoId,
                            contentType = contentType,
                            title = title,
                            poster = poster,
                            backdrop = backdrop,
                            logo = logo,
                            season = season,
                            episode = episode,
                            episodeName = episodeName,
                            genres = genres,
                            year = year,
                            contentId = contentId,
                            contentName = title,
                            runtime = runtime,
                            startFromBeginning = true,
                            returnToDetailOnBack = contentType.equals("series", ignoreCase = true),
                            contentLanguage = contentLanguage
                        )
                    )
                }
            )
            }
        }

        composable(
            route = Screen.Stream.route,
            arguments = listOf(
                navArgument("videoId") { type = NavType.StringType },
                navArgument("contentType") { type = NavType.StringType },
                navArgument("title") {
                    type = NavType.StringType
                    nullable = true
                },
                navArgument("poster") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("backdrop") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("logo") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("season") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("episode") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("episodeName") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("genres") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("year") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("contentId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("contentName") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("runtime") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("manualSelection") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "false"
                },
                navArgument("returnToDetailOnBack") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "false"
                },
                navArgument("returnToHomeOnBack") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "false"
                },
                navArgument("startFromBeginning") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "false"
                },
                navArgument("contentLanguage") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("profileId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val streamArgs = backStackEntry.arguments
            val streamSavedState = backStackEntry.savedStateHandle
            val restoreSourceSelection by streamSavedState.getStateFlow(
                SOURCE_SELECTION_RESTORE_STATE_KEY, false
            ).collectAsState()
            val returnToDetailOnBack = streamArgs
                ?.getString("returnToDetailOnBack")
                ?.toBooleanStrictOrNull() == true
            val returnToHomeOnBack = streamArgs
                ?.getString("returnToHomeOnBack")
                ?.toBooleanStrictOrNull() == true
            val startFromBeginning = streamArgs
                ?.getString("startFromBeginning")
                ?.toBooleanStrictOrNull() == true
            StreamScreen(
                startFromBeginning = startFromBeginning,
                restoreSourceSelection = restoreSourceSelection,
                onSourceSelectionRestoreHandled = {
                    streamSavedState[SOURCE_SELECTION_RESTORE_STATE_KEY] = false
                },
                onBackPress = {
                    val streamContentType = streamArgs?.getString("contentType").orEmpty()
                    val streamContentId = streamArgs?.getString("contentId").orEmpty()
                    // Leaving a merged Sports & Live game's Stream screen drops its other addons' streams.
                    SportsLiveExtraSources.clear(streamContentId)
                    val season = streamArgs?.getString("season")?.toIntOrNull()
                    val episode = streamArgs?.getString("episode")?.toIntOrNull()
                    if (streamContentType.equals("series", ignoreCase = true) && streamContentId.isNotBlank()) {
                        val detailEntry = runCatching { navController.getBackStackEntry(Screen.Detail.route) }.getOrNull()
                        if (detailEntry != null) {
                            detailEntry.savedStateHandle["returnFocusSeason"] = season
                            detailEntry.savedStateHandle["returnFocusEpisode"] = episode
                            navController.popBackStack(Screen.Detail.route, inclusive = false)
                        } else {
                            navController.navigate(
                                Screen.Detail.createRoute(
                                    itemId = streamContentId,
                                    itemType = streamContentType,
                                    addonBaseUrl = null,
                                    returnFocusSeason = season,
                                    returnFocusEpisode = episode,
                                    returnToHomeOnBack = returnToHomeOnBack,
                                    heroBackdropUrl = streamArgs?.getString("backdrop")
                                )
                            ) {
                                popUpTo(Screen.Stream.route) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    } else {
                        navController.popBackStack()
                    }
                },
                onStreamSelected = { playbackInfo ->
                    val streamUrl = playbackInfo.url
                        ?: if (playbackInfo.isTorrent) "torrent://${playbackInfo.infoHash}" else null
                    val zapHop = streamSavedState.get<Boolean>(SPORTS_LIVE_ZAP_HOP) == true
                    streamUrl?.let { url ->
                        navController.navigate(
                            playerRouteFor(
                                streamUrl = url,
                                playbackInfo = playbackInfo,
                                autoPlayNav = false,
                                returnToDetailOnBack = returnToDetailOnBack,
                                returnToHomeOnBack = returnToHomeOnBack,
                                startFromBeginning = startFromBeginning,
                                fromLinkCache = false
                            )
                        ) {
                            if (zapHop) popUpTo(Screen.Stream.route) { inclusive = true }
                        }
                    }
                },
                onAutoPlayResolved = { playbackInfo ->
                    val autoPlayUrl = playbackInfo.url
                        ?: if (playbackInfo.isTorrent) "torrent://${playbackInfo.infoHash}" else null
                    autoPlayUrl?.let { url ->
                        navController.navigate(
                            playerRouteFor(
                                streamUrl = url,
                                playbackInfo = playbackInfo,
                                autoPlayNav = true,
                                returnToDetailOnBack = returnToDetailOnBack,
                                returnToHomeOnBack = returnToHomeOnBack,
                                startFromBeginning = startFromBeginning,
                                fromLinkCache = playbackInfo.fromLinkCache
                            )
                        ) {
                            popUpTo(Screen.Stream.route) { inclusive = true }
                        }
                    }
                }
            )
        }

        composable(
            route = Screen.Player.route,
            arguments = listOf(
                navArgument("streamUrl") { type = NavType.StringType },
                navArgument("title") {
                    type = NavType.StringType
                    nullable = true
                },
                navArgument("streamName") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("year") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("headers") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("contentId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("contentType") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("contentName") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("poster") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("backdrop") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("logo") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("videoId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("season") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("episode") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("episodeTitle") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("bingeGroup") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("autoPlayNav") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "false"
                },
                navArgument("fromLinkCache") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "false"
                },
                navArgument("returnToDetailOnBack") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "false"
                },
                navArgument("returnToHomeOnBack") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "false"
                },
                navArgument("filename") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("videoHash") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("videoSize") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("startFromBeginning") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = "false"
                },
                navArgument("addonName") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("addonLogo") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("streamDescription") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("contentLanguage") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("cloudSessionToken") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("launchStartedAtMs") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("profileId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("audioDelayMs") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("audioDelayLanguage") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            fun popBackToStream(): Boolean {
                val autoPlayNavigation = backStackEntry.arguments
                    ?.getString("autoPlayNav")
                    ?.toBooleanStrictOrNull() == true
                val restoreEntry = navController.previousBackStackEntry?.takeIf { previousEntry ->
                    shouldArmSourceSelectionRestore(
                        autoPlayNavigation = autoPlayNavigation,
                        previousRoute = previousEntry.destination.route
                    )
                }
                val returnedToStream = navController.popBackStack(Screen.Stream.route, inclusive = false)
                if (returnedToStream && restoreEntry != null) {
                    restoreEntry.savedStateHandle[SOURCE_SELECTION_RESTORE_STATE_KEY] = true
                }
                return returnedToStream
            }

            // A Game page highlight clip (#61): its end plays the next clip, a failure the next link or
            // clip (with a toast when a clip is given up on), and the last one returns to the Game page.
            val highlightContext = LocalContext.current
            val isHighlightClip = remember(backStackEntry.id) {
                SportsLiveHighlights.isClip(backStackEntry.arguments?.getString("streamUrl"))
            }
            // Only while this player is on top: a player already left never moves the playlist on.
            fun followHighlights(advance: () -> HighlightStep) {
                if (navController.currentBackStackEntry?.id != backStackEntry.id) return
                val step = advance()
                if (step.failedClipId != null) {
                    SportsLiveToasts.show(SportsLiveToastKind.PROBLEM, highlightContext.getString(R.string.sports_live_game_highlight_skipped))
                }
                when (step) {
                    is HighlightStep.Play -> navController.navigate(highlightPlayerRoute(step.clip, SystemClock.elapsedRealtime())) {
                        popUpTo(Screen.Player.route) { inclusive = true }
                    }
                    is HighlightStep.Done -> navController.popBackStack()
                }
            }

            fun navigateFromPostPlay(
                recommendation: PostPlayRecommendation,
                playOnLoad: Boolean,
                manualSelection: Boolean = false
            ) {
                val returnToHomeOnBack = backStackEntry.arguments
                    ?.getString("returnToHomeOnBack")
                    ?.toBooleanStrictOrNull() == true
                val playbackRootRoute = postPlayRecommendationPopUpRoute(
                    navController.previousBackStackEntry?.destination?.route
                )
                navController.navigate(
                    Screen.Detail.createRoute(
                        itemId = recommendation.id,
                        itemType = recommendation.contentType,
                        addonBaseUrl = recommendation.sourceAddonBaseUrl,
                        returnToHomeOnBack = returnToHomeOnBack,
                        heroBackdropUrl = recommendation.backdrop,
                        playOnLoad = playOnLoad,
                        manualSelection = manualSelection
                    )
                ) {
                    popUpTo(playbackRootRoute) { inclusive = true }
                }
            }

            // The PlayerScreen's own view model (same back stack entry): Back reads its current stream.
            val playerViewModel: PlayerViewModel = androidx.hilt.navigation.compose.hiltViewModel()
            // Any player opening stops the Sports & Live mini player, before it starts its own sound.
            DisposableEffect(backStackEntry.id) {
                SportsLiveMiniPlayers.controller.stop(SportsLiveMiniStop.OTHER_PLAYER)
                // Something plays again: Live now's Resume chip (#148) has had its turn.
                SportsLiveMiniPlayers.controller.dismissResume()
                // Home can put this player in picture-in-picture when it plays a zap session;
                // closing that window stops it and leaves the player, as Back does.
                val contentId = backStackEntry.arguments?.getString("contentId")
                val pipToken = SportsLivePip.attachFullPlayer(contentId) {
                    playerViewModel.stopAndRelease()
                    clearSportsLiveZapSessionOnPlayerBack(navController, contentId)
                    if (navController.currentBackStackEntry?.id == backStackEntry.id) navController.popBackStack()
                }
                onDispose { SportsLivePip.detachFullPlayer(pipToken) }
            }
            // In picture-in-picture only the video shows: the controls are hidden.
            val inPip by SportsLivePip.inPip.collectAsState()
            LaunchedEffect(inPip) { if (inPip) playerViewModel.hideControls() }
            if (isHighlightClip) {
                // A clip's error (after the player's own retries) moves on at once: no error screen.
                LaunchedEffect(backStackEntry.id) {
                    playerViewModel.uiState.first { !it.error.isNullOrBlank() }
                    playerViewModel.stopAndRelease()
                    followHighlights(SportsLiveHighlights::failed)
                }
                // Leaving this player any other way (zap, party, closed) ends the playlist too.
                DisposableEffect(backStackEntry.id) {
                    val url = backStackEntry.arguments?.getString("streamUrl")
                    onDispose { SportsLiveHighlights.playerLeft(url) }
                }
            }
            PlayerScreen(
                viewModel = playerViewModel,
                onBackPress = { currentVideoId, currentSeason, currentEpisode, autoPlayEnabled, playbackCompleted ->
                    // Back stops a highlights playlist and returns to the Game page.
                    if (isHighlightClip) SportsLiveHighlights.stop()
                    if (!playbackCompleted) {
                        startSportsLiveMiniPlayerOnBack(backStackEntry.arguments?.getString("contentId"), playerViewModel)
                    }
                    clearSportsLiveZapSessionOnPlayerBack(navController, backStackEntry.arguments?.getString("contentId"))
                    val args = backStackEntry.arguments
                    val initialSeason = args?.getString("season")?.toIntOrNull()
                    val initialEpisode = args?.getString("episode")?.toIntOrNull()
                    val episodeChangedInPlace = (currentSeason != null || currentEpisode != null) &&
                        (currentSeason != initialSeason || currentEpisode != initialEpisode)
                    val returnToDetailOnBack = args?.getString("returnToDetailOnBack")
                        ?.toBooleanStrictOrNull() == true
                    val returnToHomeOnBack = args?.getString("returnToHomeOnBack")
                        ?.toBooleanStrictOrNull() == true
                    val contentType = args?.getString("contentType").orEmpty()
                    val contentId = args?.getString("contentId").orEmpty()
                    val focusSeason = currentSeason ?: initialSeason
                    val focusEpisode = currentEpisode ?: initialEpisode
                    fun returnToDetail() {
                        val detailEntry = navController.currentBackStack.value
                            .lastOrNull {
                                val itemId = it.arguments?.getString("itemId").orEmpty()
                                val itemType = it.arguments?.getString("itemType").orEmpty()
                                it.destination.route?.startsWith("detail/") == true &&
                                    itemId == contentId &&
                                    (itemType.isBlank() || contentType.isBlank() || itemType.equals(contentType, ignoreCase = true))
                            }
                        if (detailEntry != null) {
                            detailEntry.savedStateHandle["returnFocusSeason"] = focusSeason
                            detailEntry.savedStateHandle["returnFocusEpisode"] = focusEpisode
                            navController.popBackStack(Screen.Detail.route, inclusive = false)
                        } else {
                            navController.navigate(
                                Screen.Detail.createRoute(
                                    itemId = contentId,
                                    itemType = contentType,
                                    addonBaseUrl = null,
                                    returnFocusSeason = focusSeason,
                                    returnFocusEpisode = focusEpisode,
                                    returnToHomeOnBack = returnToHomeOnBack,
                                    heroBackdropUrl = args?.getString("backdrop")
                                )
                            ) {
                                popUpTo(Screen.Player.route) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }

                    when {
                        playbackCompleted && contentId.isNotBlank() -> {
                            returnToDetail()
                        }
                        episodeChangedInPlace && autoPlayEnabled -> {
                            // autoplay moved to next episode — skip Stream, go to detail
                            if (returnToDetailOnBack && contentType.equals("series", ignoreCase = true) && contentId.isNotBlank()) {
                                returnToDetail()
                            } else if (
                                navController.previousBackStackEntry?.destination?.route
                                    ?.startsWith("stream/") == true
                            ) {
                                // Binge group reuse keeps the first episode's manual Stream screen
                                // below the player; drop it like the autoplay path does
                                navController.popBackStack(Screen.Stream.route, inclusive = true)
                                navController.currentBackStackEntry
                                    ?.takeIf { it.destination.route?.startsWith("detail/") == true }
                                    ?.savedStateHandle
                                    ?.let { state ->
                                        state["returnFocusSeason"] = focusSeason
                                        state["returnFocusEpisode"] = focusEpisode
                                    }
                            } else {
                                navController.popBackStack()
                            }
                        }
                        playerBackOpensCurrentEpisodeStreams(episodeChangedInPlace, autoPlayEnabled) -> {
                            // manual stream switch to next episode — go to Stream of current episode
                            val videoId = currentVideoId ?: args?.getString("videoId").orEmpty()
                            if (videoId.isNotBlank() && contentType.isNotBlank()) {
                                navController.navigate(
                                    Screen.Stream.createRoute(
                                        videoId = videoId,
                                        contentType = contentType,
                                        title = args?.getString("title").orEmpty(),
                                        poster = args?.getString("poster"),
                                        backdrop = args?.getString("backdrop"),
                                        logo = args?.getString("logo"),
                                        season = focusSeason,
                                        episode = focusEpisode,
                                        year = args?.getString("year"),
                                        contentId = contentId.takeIf { it.isNotBlank() },
                                        contentName = args?.getString("contentName"),
                                        manualSelection = true,
                                        returnToDetailOnBack = returnToDetailOnBack,
                                        returnToHomeOnBack = returnToHomeOnBack,
                                        profileId = args?.getString("profileId")?.toIntOrNull()
                                    )
                                ) {
                                    popUpTo(Screen.Stream.route) { inclusive = true }
                                    launchSingleTop = true
                                }
                            } else {
                                navController.popBackStack()
                            }
                        }
                        else -> {
                            val returnedToStream = popBackToStream()
                            if (!returnedToStream) {
                                if (returnToDetailOnBack && contentType.equals("series", ignoreCase = true) && contentId.isNotBlank()) {
                                    returnToDetail()
                                } else {
                                    navController.popBackStack()
                                }
                            }
                        }
                    }
                },
                onPlaybackEnded = onPlaybackEnded@{ nextVideoId, nextSeason, nextEpisode, exitReason ->
                    if (isHighlightClip) {
                        followHighlights(SportsLiveHighlights::ended)
                        return@onPlaybackEnded
                    }
                    val args = backStackEntry.arguments
                    val contentType = args?.getString("contentType").orEmpty()
                    val contentId = args?.getString("contentId").orEmpty()
                    val returnToDetailOnBack = args?.getString("returnToDetailOnBack")
                        ?.toBooleanStrictOrNull() == true
                    val returnToHomeOnBack = args?.getString("returnToHomeOnBack")
                        ?.toBooleanStrictOrNull() == true
                    if (nextVideoId != null && nextSeason != null && nextEpisode != null) {
                        val route = Screen.Stream.createRoute(
                            videoId = nextVideoId,
                            contentType = contentType,
                            title = args?.getString("title").orEmpty(),
                            poster = args?.getString("poster"),
                            backdrop = args?.getString("backdrop"),
                            logo = args?.getString("logo"),
                            season = nextSeason,
                            episode = nextEpisode,
                            episodeName = null,
                            genres = null,
                            year = args?.getString("year"),
                            contentId = contentId.takeIf { it.isNotBlank() },
                            contentName = args?.getString("contentName"),
                            runtime = null,
                            returnToDetailOnBack = returnToDetailOnBack,
                            returnToHomeOnBack = returnToHomeOnBack,
                            profileId = args?.getString("profileId")?.toIntOrNull()
                        )
                        navController.navigate(route) {
                            popUpTo(Screen.Player.route) { inclusive = true }
                        }
                    } else {
                        if (exitReason == PlayerExitReason.StillWatchingPrompt) {
                            val detailEntry = navController.currentBackStack.value
                                .lastOrNull { it.destination.route?.startsWith("detail/") == true }
                            if (detailEntry != null) {
                                detailEntry.savedStateHandle["returnFocusSeason"] = null
                                detailEntry.savedStateHandle["returnFocusEpisode"] = null
                                val token = (detailEntry.savedStateHandle.get<Int>("heroRestoreToken") ?: 0) + 1
                                detailEntry.savedStateHandle["heroRestoreToken"] = token
                                navController.popBackStack(Screen.Detail.route, inclusive = false)
                            } else {
                                val contentId = args?.getString("contentId").orEmpty()
                                val contentType = args?.getString("contentType").orEmpty()
                                val returnToHomeOnBack = args?.getString("returnToHomeOnBack")
                                    ?.toBooleanStrictOrNull() == true
                                if (contentId.isNotBlank()) {
                                    navController.navigate(
                                        Screen.Detail.createRoute(
                                            itemId = contentId,
                                            itemType = contentType,
                                            addonBaseUrl = null,
                                            returnToHomeOnBack = returnToHomeOnBack,
                                            heroBackdropUrl = args?.getString("backdrop")
                                        )
                                    ) {
                                        popUpTo(Screen.Player.route) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                } else {
                                    val poppedToStream = navController.popBackStack(Screen.Stream.route, inclusive = true)
                                    if (!poppedToStream) {
                                        navController.popBackStack()
                                    }
                                }
                            }
                        } else {
                            val contentId = args?.getString("contentId").orEmpty()
                            val contentType = args?.getString("contentType").orEmpty()
                            val returnToHomeOnBack = args?.getString("returnToHomeOnBack")
                                ?.toBooleanStrictOrNull() == true
                            val focusSeason = args?.getString("season")?.toIntOrNull()
                            val focusEpisode = args?.getString("episode")?.toIntOrNull()
                            if (contentId.isNotBlank()) {
                                val detailEntry = navController.currentBackStack.value
                                    .lastOrNull {
                                        val itemId = it.arguments?.getString("itemId").orEmpty()
                                        val itemType = it.arguments?.getString("itemType").orEmpty()
                                        it.destination.route?.startsWith("detail/") == true &&
                                            itemId == contentId &&
                                            (itemType.isBlank() || contentType.isBlank() || itemType.equals(contentType, ignoreCase = true))
                                    }
                                if (detailEntry != null) {
                                    detailEntry.savedStateHandle["returnFocusSeason"] = focusSeason
                                    detailEntry.savedStateHandle["returnFocusEpisode"] = focusEpisode
                                    navController.popBackStack(Screen.Detail.route, inclusive = false)
                                } else {
                                    navController.navigate(
                                        Screen.Detail.createRoute(
                                            itemId = contentId,
                                            itemType = contentType,
                                            addonBaseUrl = null,
                                            returnFocusSeason = focusSeason,
                                            returnFocusEpisode = focusEpisode,
                                            returnToHomeOnBack = returnToHomeOnBack,
                                            heroBackdropUrl = args?.getString("backdrop")
                                        )
                                    ) {
                                        popUpTo(Screen.Player.route) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                }
                            } else {
                                val poppedToStream = navController.popBackStack(Screen.Stream.route, inclusive = true)
                                if (!poppedToStream) {
                                    navController.popBackStack()
                                }
                            }
                        }
                    }
                },
                onPlayRecommendation = { recommendation, manualSelection ->
                    navigateFromPostPlay(
                        recommendation = recommendation,
                        playOnLoad = true,
                        manualSelection = manualSelection
                    )
                },
                onOpenRecommendationDetails = { recommendation ->
                    navigateFromPostPlay(
                        recommendation = recommendation,
                        playOnLoad = false
                    )
                },
                onOpenPartyMedia = ::openPartyMedia,
                onZap = { target -> navigateToSportsLiveZapTarget(navController, target) },
                onPlaybackErrorBack = {
                    if (isHighlightClip) SportsLiveHighlights.stop()
                    val returnedToStream = popBackToStream()
                    if (!returnedToStream) {
                        val args = backStackEntry.arguments
                        val videoId = args?.getString("videoId").orEmpty()
                        val contentType = args?.getString("contentType").orEmpty()
                        val title = args?.getString("title").orEmpty()

                        if (videoId.isBlank() || contentType.isBlank() || title.isBlank()) {
                            navController.popBackStack()
                        } else {
                            val route = Screen.Stream.createRoute(
                                videoId = videoId,
                                contentType = contentType,
                                title = title,
                                poster = args?.getString("poster"),
                                backdrop = args?.getString("backdrop"),
                                logo = args?.getString("logo"),
                                season = args?.getString("season")?.toIntOrNull(),
                                episode = args?.getString("episode")?.toIntOrNull(),
                                episodeName = args?.getString("episodeTitle"),
                                genres = null,
                                year = args?.getString("year"),
                                contentId = args?.getString("contentId"),
                                contentName = args?.getString("contentName"),
                                runtime = null,
                                manualSelection = true,
                                returnToDetailOnBack = args?.getString("returnToDetailOnBack")
                                    ?.toBooleanStrictOrNull() == true,
                                returnToHomeOnBack = args?.getString("returnToHomeOnBack")
                                    ?.toBooleanStrictOrNull() == true,
                                profileId = args?.getString("profileId")?.toIntOrNull()
                            )

                            navController.navigate(route) {
                                popUpTo(Screen.Player.route) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }
                }
            )
        }

        rootComposable(Screen.Search.route) { backStackEntry ->
            val searchViewModel: com.nuvio.tv.ui.screens.search.SearchViewModel =
                androidx.hilt.navigation.compose.hiltViewModel(backStackEntry)
            SearchScreen(
                viewModel = searchViewModel,
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    val heroBackdrop = HeroBackdropState.consumeAndClear()
                    navController.navigate(
                        Screen.Detail.createRoute(
                            itemId = itemId,
                            itemType = itemType,
                            addonBaseUrl = addonBaseUrl,
                            heroBackdropUrl = heroBackdrop
                        )
                    )
                },
                onNavigateToSeeAll = { catalogId, addonId, type ->
                    navController.navigate(
                        Screen.CatalogSeeAll.createRoute(catalogId, addonId, type, fromSearch = true)
                    )
                },
                onOpenDiscover = { navController.navigate(Screen.Discover.route) }
            )
        }

        rootComposable(Screen.Discover.route, withInset = discoverIsRoot) {
            DiscoverScreen(
                showBuiltInHeader = !hideBuiltInHeaders,
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    val heroBackdrop = HeroBackdropState.consumeAndClear()
                    navController.navigate(
                        Screen.Detail.createRoute(
                            itemId = itemId,
                            itemType = itemType,
                            addonBaseUrl = addonBaseUrl,
                            heroBackdropUrl = heroBackdrop
                        )
                    )
                }
            )
        }

        rootComposable(Screen.Library.route) {
            LibraryScreen(
                showBuiltInHeader = !hideBuiltInHeaders,
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    navController.navigate(Screen.Detail.createRoute(itemId, itemType, addonBaseUrl))
                },
                onNavigateToCatalogSeeAll = { catalogId, addonId, type ->
                    navController.navigate(Screen.CatalogSeeAll.createRoute(catalogId, addonId, type))
                },
                onCloudPlaybackResolved = { info ->
                    val filename = info.filename ?: info.file.name
                    navController.navigate(
                        Screen.Player.createRoute(
                            streamUrl = info.url,
                            title = filename,
                            streamName = filename,
                            contentType = "cloud",
                            contentName = info.item.name,
                            videoId = "${info.item.stableKey}:${info.file.stableKey}",
                            season = 1,
                            episode = info.sequenceIndex + 1,
                            episodeTitle = filename,
                            filename = filename,
                            videoSize = info.videoSizeBytes,
                            addonName = info.item.providerName,
                            streamDescription = info.item.name,
                            cloudSessionToken = info.sessionToken
                        )
                    )
                }
            )
        }

        rootComposable(Screen.SportsLive.route) {
            SportsLiveScreen(
                onPlay = { item, manual ->
                    navController.navigate(streamRouteFor(item, returnToHomeOnBack = false, manualSelection = manual))
                },
                // A merged game's stream, picked across its sources, plays straight from the section.
                onPlayTarget = { target ->
                    navController.navigate(sportsLivePlayerRoute(target) ?: streamRouteFor(target.item, returnToHomeOnBack = false))
                },
                onOpenDetail = { item ->
                    navController.navigate(
                        Screen.Detail.createRoute(
                            itemId = item.id,
                            itemType = item.apiType,
                            addonBaseUrl = item.addonBaseUrl
                        )
                    )
                },
                onOpenAddons = { navController.navigate(Screen.AddonManager.route) },
                onOpenMySports = { navController.navigate(Screen.SportsLiveMySports.route) },
                onOpenDual = { navController.navigate(Screen.SportsLiveDual.route) },
                onOpenGame = { navController.navigate(Screen.SportsLiveGame.route) },
                onOpenTeam = { navController.navigate(Screen.SportsLiveTeam.route) }
            )
        }

        composable(Screen.SportsLiveGame.route) {
            SportsLiveGameScreen(
                onBack = {
                    // Once only: a second Back during the exit transition must not also pop the screen below.
                    if (navController.currentDestination?.route == Screen.SportsLiveGame.route) navController.popBackStack()
                },
                onPlay = { item, manual ->
                    navController.navigate(streamRouteFor(item, returnToHomeOnBack = false, manualSelection = manual))
                },
                onPlayTarget = { target ->
                    navController.navigate(sportsLivePlayerRoute(target) ?: streamRouteFor(target.item, returnToHomeOnBack = false))
                },
                onOpenDetail = { item ->
                    navController.navigate(
                        Screen.Detail.createRoute(
                            itemId = item.id,
                            itemType = item.apiType,
                            addonBaseUrl = item.addonBaseUrl
                        )
                    )
                },
                onPlayHighlight = { clip -> navController.navigate(highlightPlayerRoute(clip, SystemClock.elapsedRealtime())) },
                // Dual screen replaces the Game page (#57): Back from it returns to the section.
                onOpenDual = {
                    navController.navigate(Screen.SportsLiveDual.route) {
                        popUpTo(Screen.SportsLiveGame.route) { inclusive = true }
                    }
                },
                onOpenTeam = { navController.navigate(Screen.SportsLiveTeam.route) },
                onOpenPlayerCard = { navController.navigate(Screen.SportsLivePlayerCard.route) }
            )
        }

        composable(Screen.SportsLiveTeam.route) {
            SportsLiveTeamScreen(
                onBack = {
                    // Once only: a second Back during the exit transition must not also pop the screen below.
                    if (navController.currentDestination?.route == Screen.SportsLiveTeam.route) navController.popBackStack()
                },
                onPlay = { item, manual ->
                    navController.navigate(streamRouteFor(item, returnToHomeOnBack = false, manualSelection = manual))
                },
                onPlayTarget = { target ->
                    navController.navigate(sportsLivePlayerRoute(target) ?: streamRouteFor(target.item, returnToHomeOnBack = false))
                },
                onOpenGame = { navController.navigate(Screen.SportsLiveGame.route) },
                onOpenPlayerCard = { navController.navigate(Screen.SportsLivePlayerCard.route) }
            )
        }

        composable(Screen.SportsLivePlayerCard.route) {
            SportsLivePlayerCardScreen(
                onBack = {
                    // Once only, as the Game page: a second Back must not also pop the screen below.
                    if (navController.currentDestination?.route == Screen.SportsLivePlayerCard.route) navController.popBackStack()
                }
            )
        }

        composable(Screen.SportsLiveMySports.route) {
            SportsLiveMySportsScreen(onBackPress = { navController.popBackStack() })
        }

        composable(Screen.SportsLiveTeamSearch.route) {
            SportsLiveTeamSearchScreen(
                onBack = {
                    // Once only: a second Back during the exit transition must not also pop the screen below.
                    if (navController.currentDestination?.route == Screen.SportsLiveTeamSearch.route) navController.popBackStack()
                }
            )
        }

        composable(Screen.SportsLiveDual.route) {
            SportsLiveDualScreen(
                onBack = {
                    // Once only: a second Back during the exit transition must not also pop the screen below.
                    if (navController.currentDestination?.route == Screen.SportsLiveDual.route) navController.popBackStack()
                },
                // OK plays the highlighted game full screen in place of dual screen (whatever opened
                // dual, Home included), so Back from it returns to what was below dual, never to dual
                // (see sportsLiveStackAfterDualHandOff).
                onOpenStreamScreen = { item, manual ->
                    navController.navigate(streamRouteFor(item, returnToHomeOnBack = false, manualSelection = manual)) {
                        popUpTo(Screen.SportsLiveDual.route) { inclusive = true }
                    }
                },
                onPlayTarget = { target ->
                    navController.navigate(sportsLivePlayerRoute(target) ?: streamRouteFor(target.item, returnToHomeOnBack = false)) {
                        popUpTo(Screen.SportsLiveDual.route) { inclusive = true }
                    }
                }
            )
        }

        rootComposable(Screen.Settings.route) {
            SettingsScreen(
                showBuiltInHeader = !hideBuiltInHeaders,
                onNavigateToAddons = { navController.navigate(Screen.AddonManager.route) },
                onNavigateToPlugins = { navController.navigate(Screen.Plugins.route) },
                onNavigateToAuthQrSignIn = { navController.navigate(Screen.AuthQrSignIn.route) },
                onNavigateToManageProfiles = { navController.navigate(Screen.ManageProfiles.route) },
                onNavigateToSupportersContributors = {
                    navController.navigate(Screen.SupportersContributors.route)
                },
                onNavigateToLicensesAttributions = {
                    navController.navigate(Screen.LicensesAttributions.route)
                },
                onNavigateToWatchParty = { navController.navigate(Screen.WatchParty.createRoute()) },
                onNavigateToSportsLiveTeamSearch = { navController.navigate(Screen.SportsLiveTeamSearch.route) }
            )
        }

        composable(
            route = Screen.WatchParty.route,
            arguments = listOf(
                navArgument("code") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { backStackEntry ->
            PartyScreen(
                initialCode = backStackEntry.arguments?.getString("code").orEmpty(),
                onBackPress = { navController.popBackStack() },
                onWatch = ::openPartyMedia
            )
        }

        composable(Screen.ManageProfiles.route) {
            ProfileSelectionScreen(
                onProfileSelected = {},
                screenMode = ProfileSelectionMode.Management,
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.Tracking.route) {
            TrackingSettingsScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.TmdbSettings.route) {
            TmdbSettingsScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.ThemeSettings.route) {
            ThemeSettingsScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.PlaybackSettings.route) {
            PlaybackSettingsScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.About.route) {
            AboutScreen(
                onBackPress = { navController.popBackStack() },
                onNavigateToSupportersContributors = {
                    navController.navigate(Screen.SupportersContributors.route)
                },
                onNavigateToLicensesAttributions = {
                    navController.navigate(Screen.LicensesAttributions.route)
                }
            )
        }

        if (AppFeaturePolicy.supportNuvioEnabled) {
            composable(Screen.SupportersContributors.route) {
                SupportersContributorsScreen(
                    onBackPress = { navController.popBackStack() }
                )
            }
        }

        composable(Screen.LicensesAttributions.route) {
            LicensesAttributionsScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.AddonManager.route) {
            AddonManagerScreen(
                showBuiltInHeader = !hideBuiltInHeaders,
                onBackPress = { navController.popBackStack() },
                onNavigateToCatalogOrder = { navController.navigate(Screen.CatalogOrder.route) },
                onNavigateToCollections = { navController.navigate(Screen.Collections.route) }
            )
        }

        composable(Screen.CatalogOrder.route) {
            CatalogOrderScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.Collections.route) {
            com.nuvio.tv.ui.screens.collection.CollectionManagementScreen(
                onNavigateToEditor = { collectionId ->
                    navController.navigate(Screen.CollectionEditor.createRoute(collectionId))
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.CollectionEditor.route,
            arguments = listOf(
                navArgument("collectionId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) {
            com.nuvio.tv.ui.screens.collection.CollectionEditorScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.FolderDetail.route,
            arguments = listOf(
                navArgument("collectionId") { type = NavType.StringType },
                navArgument("folderId") { type = NavType.StringType }
            )
        ) {
            com.nuvio.tv.ui.screens.collection.FolderDetailScreen(
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    val heroBackdrop = HeroBackdropState.consumeAndClear()
                    navController.navigate(Screen.Detail.createRoute(itemId, itemType, addonBaseUrl, heroBackdropUrl = heroBackdrop))
                },
                onBack = { navController.popBackStack() }
            )
        }

        if (AppFeaturePolicy.pluginsEnabled) {
            composable(Screen.Plugins.route) {
                PluginScreen(
                    onBackPress = { navController.popBackStack() }
                )
            }
        }

        composable(Screen.Account.route) {
            AuthQrSignInScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.AuthSignIn.route) {
            AuthQrSignInScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.AuthQrSignIn.route) {
            AuthQrSignInScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(Screen.LayoutSettings.route) {
            LayoutSettingsScreen(
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.CatalogSeeAll.route,
            arguments = listOf(
                navArgument("catalogId") { type = NavType.StringType },
                navArgument("addonId") { type = NavType.StringType },
                navArgument("type") { type = NavType.StringType },
                navArgument("fromSearch") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { backStackEntry ->
            val catalogId = backStackEntry.arguments?.getString("catalogId") ?: ""
            val addonId = backStackEntry.arguments?.getString("addonId") ?: ""
            val type = backStackEntry.arguments?.getString("type") ?: ""
            val fromSearch = backStackEntry.arguments?.getBoolean("fromSearch") ?: false
            if (ServerCatalog.isServerAddonId(addonId) && !fromSearch) {
                ServerCatalogDestination(navController, backStackEntry)
                return@composable
            }

            // When coming from search, get the SearchViewModel from the Search back stack entry
            // so we share the same data (existing results + pagination)
            val searchBackStackEntry = androidx.compose.runtime.remember(fromSearch) {
                if (fromSearch) {
                    try { navController.getBackStackEntry(Screen.Search.route) } catch (_: Exception) { null }
                } else null
            }
            val searchViewModel: com.nuvio.tv.ui.screens.search.SearchViewModel? =
                if (searchBackStackEntry != null) {
                    androidx.hilt.navigation.compose.hiltViewModel<com.nuvio.tv.ui.screens.search.SearchViewModel>(searchBackStackEntry)
                } else null
            val homeBackStackEntry = androidx.compose.runtime.remember {
                try { navController.getBackStackEntry(Screen.Home.route) } catch (_: Exception) { null }
            }
            val homeViewModel: com.nuvio.tv.ui.screens.home.HomeViewModel =
                if (homeBackStackEntry != null) {
                    androidx.hilt.navigation.compose.hiltViewModel(homeBackStackEntry)
                } else {
                    androidx.hilt.navigation.compose.hiltViewModel(backStackEntry)
                }

            CatalogSeeAllScreen(
                catalogId = catalogId,
                addonId = addonId,
                type = type,
                searchViewModel = searchViewModel,
                viewModel = homeViewModel,
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    navController.navigate(Screen.Detail.createRoute(itemId, itemType, addonBaseUrl))
                },
                onBackPress = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.CastDetail.route,
            arguments = listOf(
                navArgument("personId") { type = NavType.StringType },
                navArgument("personName") { type = NavType.StringType },
                navArgument("preferCrew") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) {
            CastDetailScreen(
                onBackPress = { navController.popBackStack() },
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    navController.navigate(Screen.Detail.createRoute(itemId, itemType, addonBaseUrl))
                }
            )
        }

        composable(
            route = Screen.TmdbEntityBrowse.route,
            arguments = listOf(
                navArgument("entityKind") { type = NavType.StringType },
                navArgument("entityId") { type = NavType.IntType },
                navArgument("entityName") { type = NavType.StringType },
                navArgument("sourceType") {
                    type = NavType.StringType
                    defaultValue = "tv"
                }
            )
        ) {
            TmdbEntityBrowseScreen(
                onBackPress = { navController.popBackStack() },
                onNavigateToDetail = { itemId, itemType, addonBaseUrl ->
                    navController.navigate(Screen.Detail.createRoute(itemId, itemType, addonBaseUrl))
                }
            )
        }
    }
}
