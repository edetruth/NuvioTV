package com.nuvio.tv.ui.screens.sportslive

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational
import android.view.KeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.DrawerItem
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.SportsLiveGameWatch
import com.nuvio.tv.core.sportslive.SportsLiveStillWatching
import com.nuvio.tv.core.sportslive.alerts.SportsLiveAlerts
import com.nuvio.tv.domain.repository.SportsLiveRepository
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.navigation.sportsLiveTargetRoute
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniFocus
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniPlayerHost
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniPlayers
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniStop
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLivePip
import com.nuvio.tv.ui.screens.sportslive.mini.sportsLiveMiniChipOn
import com.nuvio.tv.ui.screens.sportslive.mini.sportsLivePipEligible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/*
 * MainActivity's Sports & Live hooks, kept out of MainActivity.kt (#113) so upstream merges of that file
 * meet only one-line seams.
 */

/** MainActivity's Sports & Live lifecycle and key hooks; each is called from one line of MainActivity. */
object SportsLiveActivityHooks {

    fun onResume() {
        SportsLivePip.ownLaunchInFlight = false
    }

    fun onUserInteraction() {
        SportsLiveMiniPlayers.controller.onInteraction()
        SportsLiveStillWatching.shared.onInteraction()
    }

    /**
     * Sports & Live's keys, before the screens; true when consumed. [onHeldBack] runs when a held Back stopped
     * the mini player, so the rest of that Back is eaten and opens no menu.
     */
    fun dispatchKeyEvent(event: KeyEvent, onHeldBack: () -> Unit): Boolean {
        // A Sports & Live alert card that shows "OK" takes a plain OK and Back; its countdown card every key but the
        // volume (live.24). Before the screens, so the player never sees those presses.
        if (SportsLiveAlertSurfaces.dispatch(event)) return true
        // Play/Pause while the Sports & Live mini player plays expands it; otherwise upstream behavior.
        if ((event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) &&
            SportsLiveMiniPlayers.controller.isActive
        ) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                SportsLiveMiniPlayers.controller.requestExpand()
            }
            return true
        }
        // MEDIA_STOP or a held Back stops the Sports & Live mini player from any screen (BUG13).
        if (SportsLiveMiniPlayers.controller.onStopKey(event.keyCode, event.action == KeyEvent.ACTION_DOWN, event.isLongPress)) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) onHeldBack()
            return true
        }
        return false
    }

    /**
     * The screens' dispatch ([screens]: Activity.dispatchKeyEvent's super), through Hold OK (#218 everywhere): where
     * nothing focused has a hold of its own, a held OK opens the Report a problem sheet ([SportsLiveHoldOk]).
     */
    fun dispatchToScreens(
        event: KeyEvent,
        windowFocused: Boolean,
        reporter: SportsLiveReporter,
        screens: (KeyEvent) -> Boolean
    ): Boolean {
        // A held OK an alert card held back (live.24): the screens get its first press, then this repeat, so the
        // player menu or the Hold OK sheet opens as without a card.
        SportsLiveAlertSurfaces.takeReplay()?.let { SportsLiveHoldOk.dispatch(it, windowFocused, reporter, screens) }
        return SportsLiveHoldOk.dispatch(event, windowFocused, reporter, screens)
    }

    /** Every in-app launch (startActivity included) passes here; its leave hint is not Home. */
    fun onStartActivity() {
        SportsLivePip.ownLaunchInFlight = true
    }

    /** Home while Sports & Live plays: picture-in-picture (16:9) when the device has it. */
    fun onUserLeaveHint(activity: Activity) {
        val eligible = sportsLivePipEligible(
            sdkInt = Build.VERSION.SDK_INT,
            hasPipFeature = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE),
            sportsFullPlayerShowing = SportsLivePip.sportsFullPlayerShowing(),
            miniPlayerActive = SportsLiveMiniPlayers.controller.isActive,
            ownLaunchInFlight = SportsLivePip.ownLaunchInFlight,
            pipEnabled = SportsLivePip.userEnabled
        )
        if (eligible && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                activity.enterPictureInPictureMode(
                    PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
                )
            }
        }
    }

    fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, lifecycle: Lifecycle) {
        // Leaving PiP while stopped means its window was closed; otherwise the full UI is back.
        if (!isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            SportsLivePip.onClosed()
        } else {
            SportsLivePip.onModeChanged(isInPictureInPictureMode)
        }
    }

    fun onStop(isInPictureInPictureMode: Boolean, isChangingConfigurations: Boolean) {
        // L2: a dual pane held for the full player stops with the app, not 30 s later.
        SportsLiveFullScreenHold.onAppStop(isChangingConfigurations)
        when {
            // The PiP window was closed: its playback stops and the players are released.
            isInPictureInPictureMode -> SportsLivePip.onClosed()
            // No sound in the background: the mini player stops when the app leaves (an external
            // player included) without picture-in-picture.
            !isChangingConfigurations -> SportsLiveMiniPlayers.controller.stop(SportsLiveMiniStop.APP_BACKGROUND)
        }
    }

    fun onDestroy(isFinishing: Boolean) {
        if (isFinishing) SportsLiveMiniPlayers.controller.stop(SportsLiveMiniStop.APP_FINISH)
    }

    fun onProfileSwitch() {
        SportsLiveMiniPlayers.controller.stop(SportsLiveMiniStop.PROFILE_SWITCH)
    }

    /** Back on a root screen: true when it stopped the mini player (the sidebar then stays closed). */
    fun stopOnBack(): Boolean = SportsLiveMiniPlayers.controller.stopOnBack()
}

/** MainActivity's injected Sports & Live services: the section's availability and the debug intents. */
class SportsLiveActivityServices @Inject constructor(
    private val repository: SportsLiveRepository,
    private val alerts: SportsLiveAlerts,
    private val debugProbe: SportsLiveDebugProbe
) {
    /**
     * Null until the first emission, so a restore onto Sports & Live is not redirected before the
     * stores have loaded; the sidebar treats null as not available.
     */
    @Composable
    fun availability(profileKey: Any?, sessionKey: Any?): Boolean? = key(profileKey, sessionKey) {
        remember(repository) {
            repository.sectionAvailable
        }.collectAsState(initial = null as Boolean?)
    }.value

    /** Debug builds only: adb intents that raise alerts and drive game-end and probe checks on a device. */
    fun onNewIntent(intent: Intent, scope: CoroutineScope) {
        if (!BuildConfig.IS_DEBUG_BUILD) return
        // `--ei debugSportsAlerts N` raises N score alerts, for checking #136 on a device.
        val count = intent.getIntExtra("debugSportsAlerts", 0)
        if (count > 0) scope.launch { alerts.debugRaise(count) }
        // `--ez debugSportsKickoff true`: a kickoff prompt for a live game, for checking #173.
        if (intent.getBooleanExtra("debugSportsKickoff", false)) scope.launch { alerts.debugKickoff() }
        // `--ez debugSportsCrunch true`: one crunch-time alert (#137).
        if (intent.getBooleanExtra("debugSportsCrunch", false)) scope.launch { alerts.debugCrunch() }
        // #161/#162 emulator checks: the game playing ends (optionally with nothing else live), every stream
        // stopping is a game end (dual, mini), the next link start waits, a kickoff is held or given up.
        // A score toast for the game on screen (through another addon's copy where listed): must not show.
        if (intent.getBooleanExtra("debugSportsAlertPlaying", false)) scope.launch { alerts.debugRaiseForPlaying() }
        val watch = SportsLiveGameWatch.shared
        if (intent.hasExtra("debugSportsNoNext")) watch.debugNoNext = intent.getBooleanExtra("debugSportsNoNext", false)
        if (intent.hasExtra("debugSportsEndAll")) watch.debugEndAll = intent.getBooleanExtra("debugSportsEndAll", false)
        if (intent.getBooleanExtra("debugSportsEnd", false)) watch.debugEndNow()
        if (intent.getBooleanExtra("debugSportsWait", false)) watch.debugWaitNext(intent.getIntExtra("debugSportsWaitSeconds", 120) * 1_000L)
        val holdSeconds = intent.getIntExtra("debugSportsKickoffHold", 0)
        if (holdSeconds > 0) {
            scope.launch {
                alerts.debugKickoffHold(holdSeconds * 1_000L, giveUp = intent.getBooleanExtra("debugSportsKickoffGiveUp", false))
            }
        }
        // `--es debugSportsProbe <sport>`: measures sports lookups and link starts (#158).
        intent.getStringExtra("debugSportsProbe")?.let { filter ->
            debugProbe.run(
                filter = filter,
                games = intent.getIntExtra("debugProbeGames", 3),
                links = intent.getIntExtra("debugProbeLinks", 3),
                maxMs = intent.getIntExtra("debugProbeMaxMs", 45_000).toLong(),
                skipGames = intent.getIntExtra("debugProbeSkip", 0),
                watch = intent.getBooleanExtra("debugProbeWatch", false)
            )
        }
    }
}

/**
 * A route on Sports & Live while the section stays unavailable for 5 s goes Home (#211: a short drop no longer
 * throws the user out, see [sportsLiveUnavailableRedirects]); the mini player follows the route.
 */
@Composable
fun SportsLiveRouteEffects(navController: NavHostController, currentRoute: String?, availability: Boolean?) {
    // The mini player follows the route (#219: it plays on everywhere, as a chip outside the section).
    LaunchedEffect(currentRoute) { SportsLiveMiniPlayers.controller.onRoute(currentRoute) }
    val latestRoute by rememberUpdatedState(currentRoute)
    val latestAvailability by rememberUpdatedState(availability)
    LaunchedEffect(navController) {
        val state = snapshotFlow {
            val route = latestRoute
            latestAvailability to (route == Screen.SportsLive.route || route?.startsWith("${Screen.SportsLive.route}/") == true)
        }
        sportsLiveUnavailableRedirects(state).collect {
            navController.navigate(Screen.Home.route) {
                popUpTo(navController.graph.startDestinationId) { saveState = false }
                launchSingleTop = true
            }
        }
    }
}

/** The sidebar's Sports & Live entry. */
fun sportsLiveDrawerItem(label: String): DrawerItem =
    DrawerItem(
        route = Screen.SportsLive.route,
        label = label,
        iconRes = R.raw.sidebar_sports
    )

/**
 * Wraps the app's content so the mini player's arrows give focus back to it, where it last was.
 * Saved only for the mini player's arrows to restore; nothing else enters through this group, so
 * screens keep their own focus.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SportsLiveContentFocusGroup(contentFocus: FocusRequester, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(contentFocus)
            .focusProperties { onExit = { contentFocus.saveFocusedChild() } }
            .onFocusChanged { SportsLiveMiniFocus.onContentFocus(it.hasFocus) }
            .focusGroup(),
        content = content
    )
}

/** The app-wide Sports & Live layers above the NavHost: the mini player, alert cards and the toast card. */
@Composable
fun SportsLiveActivityOverlays(navController: NavHostController, currentRoute: String?, contentFocus: FocusRequester) {
    // What the screen on top plays (audit G30): sports toasts and the report shortcut stay out of a movie.
    val top by navController.currentBackStackEntryAsState()
    val topRoute = top?.destination?.route
    val topContentId = top?.arguments?.getString("contentId")
    LaunchedEffect(topRoute, topContentId) { SportsLiveOverMovie.onScreen(topRoute, topContentId) }
    // The Sports & Live mini player, above the NavHost; focus reaches it only by a D-pad move.
    SportsLiveMiniPlayerHost(
        onExpand = { target -> navController.navigate(sportsLiveTargetRoute(target)) },
        contentFocusRequester = contentFocus,
        chip = sportsLiveMiniChipOn(currentRoute)
    )
    // Sports & Live reminders' "Watch now?" and followed teams' toasts (#42, #44).
    SportsLiveAlertsHost(
        navController = navController,
        contentFocusRequester = contentFocus
    )
    // Sports & Live's one toast card (#134): top right, never focused, newest wins.
    SportsLiveToastLayer()
    // Hold OK's Report a problem sheet, on any screen without a hold of its own (#218 everywhere).
    SportsLiveHoldOkHost()
}

/**
 * Sports video playing outside the full player (dual screen, the mini player) keeps the screen awake and
 * ambient mode away; [onStopped] restarts the idle time when it stops (#38). Returns whether it plays.
 */
@Composable
fun sportsLiveKeepAwake(onStopped: () -> Unit): Boolean {
    val sportsVideoPlaying by SportsLiveAwakeHolds.shared.anyPlaying.collectAsState()
    SportsLiveKeepScreenOn(sportsVideoPlaying)
    LaunchedEffect(sportsVideoPlaying) {
        if (!sportsVideoPlaying) onStopped()
    }
    return sportsVideoPlaying
}
