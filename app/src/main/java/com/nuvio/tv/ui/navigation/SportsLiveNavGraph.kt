package com.nuvio.tv.ui.navigation

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.SportsLiveToastKind
import com.nuvio.tv.core.sportslive.SportsLiveToasts
import com.nuvio.tv.ui.screens.home.SportsLiveHomeNav
import com.nuvio.tv.ui.screens.player.PlayerViewModel
import com.nuvio.tv.ui.screens.settings.SportsLiveMySportsScreen
import com.nuvio.tv.ui.screens.sportslive.HighlightStep
import com.nuvio.tv.ui.screens.sportslive.LocalOpenSportsLiveGame
import com.nuvio.tv.ui.screens.sportslive.SportsLiveExtraSources
import com.nuvio.tv.ui.screens.sportslive.SportsLiveGameScreen
import com.nuvio.tv.ui.screens.sportslive.SportsLiveHighlights
import com.nuvio.tv.ui.screens.sportslive.SportsLivePlayHost
import com.nuvio.tv.ui.screens.sportslive.SportsLivePlayViewModel
import com.nuvio.tv.ui.screens.sportslive.SportsLivePlayerCardScreen
import com.nuvio.tv.ui.screens.sportslive.SportsLiveScreen
import com.nuvio.tv.ui.screens.sportslive.SportsLiveSetupExitTarget
import com.nuvio.tv.ui.screens.sportslive.SportsLiveSetupGate
import com.nuvio.tv.ui.screens.sportslive.SportsLiveSetupScreen
import com.nuvio.tv.ui.screens.sportslive.SportsLiveTeamScreen
import com.nuvio.tv.ui.screens.sportslive.SportsLiveTeamSearchScreen
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapSessions
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapTarget
import com.nuvio.tv.ui.screens.sportslive.dual.LocalOpenSportsLiveDual
import com.nuvio.tv.ui.screens.sportslive.dual.SportsLiveDualPopTarget
import com.nuvio.tv.ui.screens.sportslive.dual.SportsLiveDualScreen
import com.nuvio.tv.ui.screens.sportslive.dual.sportsLiveBackToDual
import com.nuvio.tv.ui.screens.sportslive.dual.sportsLiveDualEntryPopTarget
import com.nuvio.tv.ui.screens.sportslive.highlightPlayerRoute
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniPlayers
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniStop
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLivePip
import com.nuvio.tv.ui.screens.sportslive.mini.sportsLiveMiniStream
import com.nuvio.tv.ui.screens.sportslive.sportsLiveSetupExitTarget
import com.nuvio.tv.ui.screens.sportslive.streamRouteFor
import com.nuvio.tv.ui.screens.stream.StreamPlaybackInfo
import kotlinx.coroutines.flow.first

/*
 * Sports & Live's navigation, kept out of NuvioNavHost.kt (#113) so upstream merges of that file meet only
 * one-line seams: the section's destinations, the zap, dual screen and mini player helpers, and the
 * Sports & Live hooks of the Home and Player destinations.
 */

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
private fun openSportsLiveDualFromPlayer(navController: NavHostController, root: android.view.View) {
    val playerEntry = navController.currentBackStackEntry
        ?.takeIf { it.destination.route == Screen.Player.route } ?: return
    // live.20: the player's last frame stays in the game's pane until it plays there (no black pane).
    com.nuvio.tv.ui.screens.sportslive.dual.SportsLiveDualHandoffFrame.capture(root)
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
 * instead (#148). A player left before its stream drew a frame (still starting or switching links)
 * starts nothing (#175): Back there is a stop. Runs before the session is cleared.
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
        paused = player.isPausedByUser(),
        started = player.hasStartedPlaying()
    )
}

/**
 * The Stream screen's pick replaces that screen when it is a zap hop (see [navigateToSportsLiveZapTarget]),
 * so Back from the zapped game still returns once.
 */
internal fun NavOptionsBuilder.sportsLiveZapHop(streamSavedState: SavedStateHandle) {
    val zapHop = streamSavedState.get<Boolean>(SPORTS_LIVE_ZAP_HOP) == true
    if (zapHop) popUpTo(Screen.Stream.route) { inclusive = true }
}

/** The composition locals NuvioNavHost provides for Sports & Live. */
@Composable
internal fun sportsLiveNavLocals(navController: NavHostController): Array<ProvidedValue<*>> {
    // The player's live games panel opens dual screen through this.
    val openSportsLiveDual: () -> Unit = LocalView.current.let { root -> remember(navController, root) { { openSportsLiveDualFromPlayer(navController, root) } } }
    // ...and the Game page (Game stats in its Hold OK menu, #140), above the player: Back returns to it.
    val openSportsLiveGame: () -> Unit = remember(navController) { { navController.navigate(Screen.SportsLiveGame.route) } }
    return arrayOf(
        LocalOpenSportsLiveDual provides openSportsLiveDual,
        LocalOpenSportsLiveGame provides openSportsLiveGame
    )
}

/** Home's Sports & Live navigation; composes the play host Live now and Catch up cards play through. */
@Composable
internal fun sportsLiveHomeNav(navController: NavHostController): SportsLiveHomeNav {
    // A merged Live now game plays across its sources, as in the section.
    val sportsLivePlayViewModel: SportsLivePlayViewModel = hiltViewModel()
    SportsLivePlayHost(
        playViewModel = sportsLivePlayViewModel,
        onOpenStreamScreen = { item, manual ->
            navController.navigate(streamRouteFor(item, returnToHomeOnBack = true, manualSelection = manual))
        },
        onPlayTarget = { target ->
            navController.navigate(sportsLivePlayerRoute(target) ?: streamRouteFor(target.item, returnToHomeOnBack = true))
        }
    )
    return remember(navController, sportsLivePlayViewModel) {
        SportsLiveHomeNav(
            onNavigateToSportsLive = {
                navController.navigate(Screen.SportsLive.route) { launchSingleTop = true }
            },
            // As in the section: a live game plays its best stream (or opens its Stream screen, per setting).
            onLiveNowItemClick = { item -> sportsLivePlayViewModel.play(item, returnToHomeOnBack = true) },
            onOpenSportsLiveGame = { navController.navigate(Screen.SportsLiveGame.route) },
            onPlayCatchUpReplay = { replays, title -> sportsLivePlayViewModel.playReplay(replays, title, returnToHomeOnBack = true) }
        )
    }
}

/**
 * The Sports & Live hooks of one Player destination: a Game page highlights playlist, the mini player
 * and picture-in-picture, and Back to dual screen. NuvioNavHost calls each from one line.
 */
internal class SportsLivePlayerSeams(
    private val navController: NavHostController,
    private val backStackEntry: NavBackStackEntry,
    /** The PlayerScreen's own view model (same back stack entry): Back reads its current stream. */
    val playerViewModel: PlayerViewModel,
    private val context: Context,
    private val isHighlightClip: Boolean,
    private val openSportsLiveDual: (() -> Unit)?
) {
    // Only while this player is on top: a player already left never moves the playlist on.
    fun followHighlights(advance: () -> HighlightStep) {
        if (navController.currentBackStackEntry?.id != backStackEntry.id) return
        val step = advance()
        if (step.failedClipId != null) {
            SportsLiveToasts.show(SportsLiveToastKind.PROBLEM, context.getString(R.string.sports_live_game_highlight_skipped))
        }
        when (step) {
            is HighlightStep.Play -> navController.navigate(highlightPlayerRoute(step.clip, SystemClock.elapsedRealtime())) {
                popUpTo(Screen.Player.route) { inclusive = true }
            }
            is HighlightStep.Done -> navController.popBackStack()
        }
    }

    /** Player Back: true when it is handled here (back to dual screen); otherwise upstream's Back goes on. */
    fun onBack(playbackCompleted: Boolean): Boolean {
        // A game OK took full screen from dual screen: Back returns to dual with both games (live.20).
        if (sportsLiveBackToDual(backStackEntry.arguments?.getString("contentId"), openSportsLiveDual ?: {})) return true
        // Back stops a highlights playlist and returns to the Game page.
        if (isHighlightClip) SportsLiveHighlights.stop()
        if (!playbackCompleted) {
            startSportsLiveMiniPlayerOnBack(backStackEntry.arguments?.getString("contentId"), playerViewModel)
        }
        clearSportsLiveZapSessionOnPlayerBack(navController, backStackEntry.arguments?.getString("contentId"))
        return false
    }

    /** True when a highlight clip ended: the playlist moves on and upstream's post-play is skipped. */
    fun onPlaybackEnded(): Boolean {
        if (!isHighlightClip) return false
        followHighlights(SportsLiveHighlights::ended)
        return true
    }

    fun onPlaybackErrorBack() {
        if (isHighlightClip) SportsLiveHighlights.stop()
    }
}

/** Composes the Player destination's Sports & Live effects and returns its hooks. */
@Composable
internal fun sportsLivePlayerSeams(navController: NavHostController, backStackEntry: NavBackStackEntry): SportsLivePlayerSeams {
    // A Game page highlight clip (#61): its end plays the next clip, a failure the next link or
    // clip (with a toast when a clip is given up on), and the last one returns to the Game page.
    val highlightContext = LocalContext.current
    val isHighlightClip = remember(backStackEntry.id) {
        SportsLiveHighlights.isClip(backStackEntry.arguments?.getString("streamUrl"))
    }
    val playerViewModel: PlayerViewModel = hiltViewModel()
    val seams = SportsLivePlayerSeams(
        navController = navController,
        backStackEntry = backStackEntry,
        playerViewModel = playerViewModel,
        context = highlightContext,
        isHighlightClip = isHighlightClip,
        openSportsLiveDual = LocalOpenSportsLiveDual.current
    )
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
            seams.followHighlights(SportsLiveHighlights::failed)
        }
        // Leaving this player any other way (zap, party, closed) ends the playlist too.
        DisposableEffect(backStackEntry.id) {
            val url = backStackEntry.arguments?.getString("streamUrl")
            onDispose { SportsLiveHighlights.playerLeft(url) }
        }
    }
    return seams
}

/** The Sports & Live section, a root destination (NuvioNavHost wraps it in its sidebar inset). */
@Composable
internal fun SportsLiveRootDestination(navController: NavHostController) {
    // First-run setup (#134 phase 7): the first time a profile opens the section.
    SportsLiveSetupGate(
        onShow = {
            if (navController.currentDestination?.route == Screen.SportsLive.route) {
                navController.navigate(Screen.SportsLiveSetup.createRoute(fromSettings = false))
            }
        }
    )
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

/** The section's pushed pages: Game, Team, player card, My sports, team search, first-run setup, dual screen. */
internal fun NavGraphBuilder.sportsLiveGraph(navController: NavHostController) {
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

    composable(
        route = Screen.SportsLiveSetup.route,
        arguments = listOf(
            navArgument("fromSettings") {
                type = NavType.BoolType
                defaultValue = false
            }
        )
    ) { backStackEntry ->
        val fromSettings = backStackEntry.arguments?.getBoolean("fromSettings") ?: false
        SportsLiveSetupScreen(
            onExit = { exit ->
                // Once only, as the other pushed pages.
                if (navController.currentDestination?.route != Screen.SportsLiveSetup.route) return@SportsLiveSetupScreen
                when (sportsLiveSetupExitTarget(exit, fromSettings)) {
                    SportsLiveSetupExitTarget.BACK -> navController.popBackStack()
                    SportsLiveSetupExitTarget.LIVE_NOW -> navController.navigate(Screen.SportsLive.route) {
                        popUpTo(navController.graph.startDestinationId)
                        launchSingleTop = true
                    }
                }
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
}
