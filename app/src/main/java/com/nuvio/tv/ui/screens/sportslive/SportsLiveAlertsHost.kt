package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.ui.screens.sportslive.dual.DualGeometry
import com.nuvio.tv.ui.screens.sportslive.dual.DualLayout
import com.nuvio.tv.ui.screens.sportslive.dual.Side
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import com.nuvio.tv.core.sportslive.SportsLiveToastKind
import com.nuvio.tv.core.sportslive.SportsLiveToasts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.nuvio.tv.core.sportslive.alerts.AlertCard
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.compose.ui.platform.LocalWindowInfo
import com.nuvio.tv.core.sportslive.alerts.AlertCardClock
import com.nuvio.tv.ui.screens.sportslive.preview.SportsLiveDecoderLimit
import com.nuvio.tv.core.sportslive.alerts.AlertChoice
import com.nuvio.tv.core.sportslive.alerts.AlertCountdown
import com.nuvio.tv.core.sportslive.alerts.AlertKeyAction
import com.nuvio.tv.core.sportslive.scores.CrunchMoment
import com.nuvio.tv.core.sportslive.alerts.AlertKeyContext
import com.nuvio.tv.core.sportslive.alerts.AlertSpot
import com.nuvio.tv.core.sportslive.alerts.SportsLiveAlertCards
import com.nuvio.tv.core.sportslive.alerts.SportsLiveAlertKeys
import com.nuvio.tv.core.sportslive.alerts.SportsLiveTipOffSwitch
import com.nuvio.tv.core.sportslive.alerts.SportsLiveAlertPlacement
import com.nuvio.tv.core.sportslive.displayName
import com.nuvio.tv.domain.model.SportsLiveItem
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.core.sportslive.alerts.AlertAnswer
import com.nuvio.tv.core.sportslive.alerts.AlertContent
import com.nuvio.tv.core.sportslive.alerts.AlertSide
import com.nuvio.tv.core.sportslive.alerts.KickoffStyle
import com.nuvio.tv.core.sportslive.SportsLiveDisplayText
import com.nuvio.tv.core.sportslive.SportsLiveGameReadiness
import com.nuvio.tv.core.sportslive.SportsLiveReadyMark
import com.nuvio.tv.core.sportslive.SportsLiveGameWatch
import com.nuvio.tv.core.sportslive.alerts.ReminderNotice
import com.nuvio.tv.core.sportslive.alerts.SportsLiveAlert
import com.nuvio.tv.core.sportslive.alerts.SportsLiveAlertContent
import com.nuvio.tv.core.sportslive.alerts.SportsLiveAlertEngine
import com.nuvio.tv.core.sportslive.alerts.SportsLiveAlerts
import com.nuvio.tv.core.sportslive.alerts.SportsLiveWatching
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.navigation.navigateToSportsLiveZapTarget
import com.nuvio.tv.ui.navigation.sportsLiveTargetRoute
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniPlayers
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** Hands the app-wide [SportsLiveAlerts] to the host. */
@HiltViewModel
class SportsLiveAlertsViewModel @Inject constructor(
    val alerts: SportsLiveAlerts,
    /** My games' readiness checks (1b): run beside the alerts while the app is in the foreground. */
    val readyKeeper: SportsLiveReadyKeeper
) : ViewModel()

/** How Watch from an alert leaves the screen under it (live.24), by what plays there. */
internal enum class AlertPlayRoute {
    /** A Sports & Live stream plays: the game replaces it, as a zap does. */
    ZAP,

    /**
     * A movie or show plays: its player closes, so its place is saved (the player's own release saves the watch
     * progress, as leaving it does), and the game plays; the game never plays on top of a paused film's decoder.
     */
    REPLACE_MOVIE,

    /** Nothing plays (browsing): the game opens. */
    OPEN
}

/** How Watch leaves [route]'s screen (live.24); [contentId]: the player's content, [isSports]: Sports & Live content. */
internal fun alertPlayRoute(route: String?, contentId: String?, isSports: (String?) -> Boolean): AlertPlayRoute = when {
    route != Screen.Player.route -> AlertPlayRoute.OPEN
    isSports(contentId) -> AlertPlayRoute.ZAP
    else -> AlertPlayRoute.REPLACE_MOVIE
}

/** A Sports & Live screen (the section, a Game page, a team), not dual screen: no close finish there (live.24). */
internal fun isSportsLiveScreen(route: String?): Boolean =
    route != null && route.startsWith("sports_live") && route != Screen.SportsLiveDual.route

/** The card on screen's state in the host (live.24): its time on screen and its countdown, for card [cardId]. */
private data class CardUi(
    val cardId: Long,
    val clock: AlertCardClock = AlertCardClock(),
    val countdown: AlertCountdown? = null,
    /** "At tip-off: Switch automatically" was looked at when the card first showed. */
    val autoTried: Boolean = false,
    /** A countdown's time left when the app left the foreground: it resumes from there. */
    val pausedLeftMs: Long? = null
)

/**
 * Sports & Live's notification cards over every screen while the app is in the foreground (live.24 redesign,
 * docs/design/notifications-review.md option A, user-approved 2026-11-21):
 *
 * - One rule everywhere: if a card shows "OK", OK belongs to it; Full time shows no keys and every key works as normal.
 * - A card never shows over the screen's own overlays (the guide, controls, a menu, a dialog: [SportsLiveAlertSurface.busy],
 *   the window losing focus to a dialog, the Hold OK sheet); it waits, up to its 30 s stale limit, and its time counts
 *   only while it shows ([AlertCardClock]). An OK within 0.8 s of it appearing goes to the screen underneath.
 * - OK opens the countdown card: "Switching to ‹game› in 3…" with [Watch now] (focused; OK skips the countdown) and
 *   [Watch both] (over a sports stream only; moving to it stops the countdown); Back stays here. No score on it. The
 *   no-stream note's OK is Remind me in 10 min instead.
 * - One alert per card, 10 s (lead change 12 s, Full time 6 s), scaled by the accessibility "Time to take action".
 * - ▶ and Hold OK are never an alert's; a held OK goes back to its owner (the player menu, the Hold OK sheet).
 * Keys go through [SportsLiveAlertSurfaces.dispatch] before the screens; nothing here takes Compose focus.
 */
@Composable
fun SportsLiveAlertsHost(
    navController: NavHostController,
    contentFocusRequester: FocusRequester,
    viewModel: SportsLiveAlertsViewModel = hiltViewModel(),
    playViewModel: SportsLivePlayViewModel = hiltViewModel()
) {
    val alerts = viewModel.alerts
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(alerts, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // 1b: my games are kept "▶ Ready" on every screen while the app is in the foreground, never in the background.
            launch { viewModel.readyKeeper.runInForeground() }
            alerts.runInForeground()
        }
    }

    // What plays: the full player's content (its first seconds wait), the mini player's game, dual screen.
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val inPlayer = route == Screen.Player.route
    val dualRoute = route == Screen.SportsLiveDual.route
    val miniState by SportsLiveMiniPlayers.controller.state.collectAsState()
    val streamStartedAt = remember(entry?.id) { if (inPlayer) System.currentTimeMillis() else null }
    val playerContentId = entry?.arguments?.getString("contentId")?.takeIf { inPlayer }
    val playerTitle = entry?.arguments?.getString("title")?.takeIf { inPlayer }
    val miniItem = (miniState as? SportsLiveMiniState.Playing)?.item
    val dualPanes by SportsLiveAlertSurfaces.dualPanes.collectAsState()
    val panes = dualPanes?.takeIf { dualRoute }
    LaunchedEffect(inPlayer, playerContentId, playerTitle, miniItem, streamStartedAt, route, panes) {
        // Every game on screen (the full player's, both dual panes', the mini player's) is watched: none of them
        // alerts, matched by card, game identity or ESPN event, never by title.
        val playerItem = playerContentId?.let { id -> SportsLiveZapSessions.activeFor(id)?.current ?: SportsLiveGameWatch.shared.item(id) }
        val onScreen = listOfNotNull(playerItem, miniItem, panes?.left, panes?.right)
        val (ids, gameKeys, eventIds) = SportsLiveAlertEngine.watchingKeys(onScreen) { SportsLiveGameWatch.shared.snapshot(it.id)?.eventId }
        alerts.setWatching(
            SportsLiveWatching(
                playingIds = setOfNotNull(playerContentId) + ids,
                playingGameKeys = gameKeys,
                playingEventIds = eventIds,
                inPlayer = inPlayer,
                // A game or channel played from Sports & Live; anything else is a movie or show.
                sportsPlayback = inPlayer && SportsLiveExtraSources.isSportsPlayback(playerContentId),
                streamStartedAtMs = streamStartedAt,
                dual = dualRoute,
                miniPlaying = miniItem != null,
                playingTitle = playerTitle,
                sportsLiveScreen = isSportsLiveScreen(route)
            )
        )
    }

    // Watch: the game's best stream (#56). A Sports & Live stream is replaced as a zap does; a movie or show player
    // closes first, so its place is saved (live.24), and the game never plays over its paused decoder.
    SportsLivePlayHost(
        playViewModel = playViewModel,
        onOpenStreamScreen = { item, manual -> navController.navigate(streamRouteFor(item, returnToHomeOnBack = false, manualSelection = manual)) },
        onPlayTarget = { target ->
            val current = navController.currentBackStackEntry
            when (alertPlayRoute(current?.destination?.route, current?.arguments?.getString("contentId"), SportsLiveExtraSources::isSportsPlayback)) {
                AlertPlayRoute.ZAP -> navigateToSportsLiveZapTarget(navController, target)
                AlertPlayRoute.REPLACE_MOVIE -> navController.navigate(sportsLiveTargetRoute(target)) {
                    popUpTo(Screen.Player.route) { inclusive = true }
                }
                AlertPlayRoute.OPEN -> navController.navigate(sportsLiveTargetRoute(target))
            }
        },
        cooldownToasts = false
    )

    // "Reminder set" / "Reminder removed" (#44): the one toast card (#134, live.19 QA F8), bottom left, never
    // taking focus. The alert cards sit in the same corner (live.20): over video, the player's (above its controls
    // while they show) or dual screen's left tile.
    val corner = SportsLiveToastPlace.corner.collectAsState().value ?: SportsLiveCorner.SCREEN
    val promptPlace = Modifier.padding(start = corner.start, bottom = corner.bottom)
    val notice by alerts.reminderNotice.collectAsState()
    val toastContext = LocalContext.current
    val reminderLabelOf = rememberSportsLiveStateLabeler(SportsLiveTab.UPCOMING)
    val reminderSet = stringResource(R.string.sports_live_reminder_toast_set)
    val reminderRemoved = stringResource(R.string.sports_live_reminder_toast_removed)
    val reminderAsk = stringResource(R.string.sports_live_reminder_toast_ask)
    val separator = stringResource(R.string.sports_live_game_info_separator)
    val reminderGameOf: (ReminderNotice) -> String = { shown ->
        shown.title ?: toastContext.getString(R.string.sports_live_results_menu_title, shown.away.orEmpty(), shown.home.orEmpty())
    }
    LaunchedEffect(notice?.id) {
        val shown = notice ?: return@LaunchedEffect
        val locale = Locale.getDefault()
        val kickoff = shown.item?.let(reminderLabelOf) ?: shown.startMs?.let { start ->
            SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEjmm"), locale).format(Date(start))
        }
        val game = reminderGameOf(shown)
        SportsLiveToasts.show(
            SportsLiveToastKind.REMINDER,
            title = listOf(if (shown.on) reminderSet else reminderRemoved, game).joinToString(separator),
            line = if (shown.on) listOfNotNull(kickoff, reminderAsk).joinToString(separator) else null
        )
        alerts.closeReminderNotice(shown)
    }

    val card by alerts.current.collectAsState()
    val watching by alerts.watchingNow.collectAsState()
    // 1b: the kickoff card says whether a verified link is held ("Stream ready").
    val readyMarks by SportsLiveGameReadiness.shared.marks.collectAsState()
    val surface by SportsLiveAlertSurfaces.surface.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // A decoder limit this TV showed before (#106), read once for the screens that check it.
    remember(context) { SportsLiveDecoderLimit.init(context) }
    val windowInfo = LocalWindowInfo.current
    val accessibility = remember(context) { context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager }

    val currentCard by rememberUpdatedState(card)
    val currentSurface by rememberUpdatedState(surface)
    val currentWatching by rememberUpdatedState(watching)
    var ui by remember { mutableStateOf(CardUi(cardId = -1L)) }
    fun uiFor(shown: AlertCard): CardUi = ui.takeIf { it.cardId == shown.id } ?: CardUi(shown.id)

    /** [item] plays here: [on]'s own way (a zap, the highlighted pane), else as Watch on a kickoff. */
    fun playHere(item: SportsLiveItem, on: SportsLiveAlertSurface?) {
        if (on?.watch(item) != true) scope.launch { playViewModel.play(alerts.cardToWatch(item)) }
    }

    /** The screen's own overlays are up (the guide, controls, a menu, a dialog): a card waits under them. */
    fun screenBusy(): Boolean =
        currentSurface?.busy() == true || !windowInfo.isWindowFocused || SportsLiveHoldOk.sheet.value != null

    /** Watch both is offered on the countdown card: over a sports stream in the full player only (#136). */
    fun canBoth(): Boolean =
        currentWatching.inPlayer && currentWatching.sportsPlayback && currentSurface?.offersBoth() == true

    fun durationOf(alert: SportsLiveAlert): Long = SportsLiveAlertCards.durationMs(alert) { ms, actionable ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && accessibility != null) {
            val flags = AccessibilityManager.FLAG_CONTENT_TEXT or (if (actionable) AccessibilityManager.FLAG_CONTENT_CONTROLS else 0)
            accessibility.getRecommendedTimeoutMillis(ms.toInt(), flags).toLong()
        } else {
            ms
        }
    }

    /** [choice] on [shown]'s countdown card, or its countdown running out: its game plays here (or both games). */
    fun act(shown: AlertCard, choice: AlertChoice) {
        ui = uiFor(shown).copy(countdown = null)
        alerts.close(shown, AlertAnswer.WATCH)
        val on = currentSurface
        val item = shown.alert.item
        val both = choice == AlertChoice.WATCH_BOTH
        when {
            // A kickoff plays its fresh card (the newest load's copy, live, with its live link, #162).
            shown.alert is SportsLiveAlert.Kickoff -> scope.launch {
                if (both) sportsLiveKickoffBoth(item, alerts::cardToWatch, { on?.watchBoth(it) == true }) { playHere(it, on) }
                else playHere(alerts.cardToWatch(item), on)
            }
            both && on?.watchBoth(item) == true -> Unit
            else -> playHere(item, on)
        }
    }

    /** OK on [shown] (past its grace): the countdown card, or on the no-stream note Remind me in 10 min. */
    fun onOk(shown: AlertCard) {
        val alert = shown.alert
        if (alert is SportsLiveAlert.Kickoff && alert.noStreamYet) {
            alerts.close(shown, AlertAnswer.LATER)
            return
        }
        ui = uiFor(shown).copy(countdown = AlertCountdown.start(shown.id, System.currentTimeMillis()))
    }

    fun keyContext(): AlertKeyContext {
        val shown = currentCard ?: return AlertKeyContext()
        val state = uiFor(shown)
        val countdown = state.countdown
        return SportsLiveAlertKeys.context(
            card = shown,
            shownAtMs = if (countdown != null) 0L else state.clock.shownAtMs,
            nowMs = System.currentTimeMillis(),
            expanded = countdown != null,
            canBoth = canBoth()
        )
    }
    val keyHandler = remember {
        object : AlertKeyHandler {
            override fun context(): AlertKeyContext = keyContext()

            override fun onAction(action: AlertKeyAction) {
                val shown = currentCard ?: return
                when (action) {
                    AlertKeyAction.PASS, AlertKeyAction.CONSUME -> Unit
                    AlertKeyAction.OK -> if (keyContext().cardOk) onOk(shown)
                    AlertKeyAction.DISMISS, AlertKeyAction.CANCEL -> alerts.close(shown, AlertAnswer.DISMISS)
                    AlertKeyAction.PRESS -> uiFor(shown).countdown?.let { act(shown, it.selected) }
                    AlertKeyAction.MOVE -> {
                        val state = uiFor(shown)
                        state.countdown?.let { ui = state.copy(countdown = it.move()) }
                    }
                }
            }
        }
    }
    DisposableEffect(keyHandler) {
        SportsLiveAlertSurfaces.keys = keyHandler
        onDispose { if (SportsLiveAlertSurfaces.keys === keyHandler) SportsLiveAlertSurfaces.keys = null }
    }

    // The card's clock: it shows while the screen's overlays are closed, counts its time only then, times out or goes
    // stale; the countdown switches when it runs out. Both pause while the app is in the background.
    LaunchedEffect(card?.id, lifecycleOwner) {
        val shown = card ?: return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                uiFor(shown).let { state ->
                    val left = state.pausedLeftMs
                    if (left != null) {
                        ui = state.copy(pausedLeftMs = null, countdown = state.countdown?.copy(endsAtMs = System.currentTimeMillis() + left))
                    }
                }
                while (true) {
                    val nowMs = System.currentTimeMillis()
                    var state = uiFor(shown)
                    val countdown = state.countdown
                    if (countdown != null) {
                        if (countdown.due(nowMs)) {
                            act(shown, AlertChoice.WATCH_NOW)
                            return@repeatOnLifecycle
                        }
                    } else {
                        state = state.copy(clock = state.clock.step(nowMs, screenBusy()))
                        if (state.clock.shownAtMs != null && !state.autoTried) {
                            // "At tip-off: Switch automatically" (opt-in, browsing only): straight into a 10 s countdown.
                            val kickoff = shown.alert as? SportsLiveAlert.Kickoff
                            val auto = kickoff != null &&
                                SportsLiveTipOffSwitch.runs(alerts.tipOff, kickoff.noStreamYet, currentWatching.inPlayer)
                            state = state.copy(
                                autoTried = true,
                                countdown = if (auto) AlertCountdown.start(shown.id, nowMs, SportsLiveAlertCards.TIP_OFF_COUNTDOWN_MS) else null
                            )
                        }
                        ui = state
                        when (state.clock.fate(shown, nowMs, durationOf(shown.alert))) {
                            AlertCardClock.Fate.UP -> Unit
                            AlertCardClock.Fate.TIMED_OUT -> {
                                // A kickoff timing out is answered too (research Q17: one alert per game).
                                alerts.close(shown, AlertAnswer.TIMEOUT)
                                return@repeatOnLifecycle
                            }
                            AlertCardClock.Fate.STALE -> {
                                // Never shown: a kickoff asks once more later; a toast is old news.
                                alerts.close(shown, if (shown.alert is SportsLiveAlert.Kickoff) AlertAnswer.LATER else AlertAnswer.TIMEOUT)
                                return@repeatOnLifecycle
                            }
                        }
                    }
                    delay(CARD_TICK_MS)
                }
            } finally {
                // The app left the foreground: the card's time and its countdown stop where they are.
                val nowMs = System.currentTimeMillis()
                val state = uiFor(shown)
                if (state.cardId == shown.id && ui.cardId == shown.id) {
                    ui = state.copy(
                        clock = state.clock.step(nowMs, busy = true),
                        pausedLeftMs = state.countdown?.endsAtMs?.let { it - nowMs }
                    )
                }
            }
        }
    }

    val current = card ?: return
    val state = uiFor(current)
    val countdown = state.countdown
    val visible = countdown != null || state.clock.shownAtMs != null
    val actionable = SportsLiveAlertCards.actionable(current.alert)
    // Back closes a card that shows "OK", or cancels the countdown (never the screen under it), by the back callback
    // as by the key.
    TakeBack(visible && (actionable || countdown != null)) { currentCard?.let { alerts.close(it, AlertAnswer.DISMISS) } }
    if (!visible) return
    // Each card is its own overlay.
    key(current.id) {
    Box(modifier = Modifier.fillMaxSize()) {
        val alert = current.alert
        // While a kickoff card is up, the player's own Sports & Live cards wait under it (live.19 QA F11).
        if (alert is SportsLiveAlert.Kickoff) {
            DisposableEffect(current.id) {
                SportsLiveAlertSurfaces.setPromptUp(true)
                onDispose { SportsLiveAlertSurfaces.setPromptUp(false) }
            }
        }
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // One corner for everything (live.20): above the player's card, the toast above this card.
            val density = LocalDensity.current
            val baseTopPx by SportsLiveBottomLeft.baseTopPx.collectAsState()
            val place = if (alert is SportsLiveAlert.Kickoff) {
                null
            } else {
                alertCardPlace(
                    spot = SportsLiveAlertPlacement.spot(alert, panes),
                    width = maxWidth,
                    height = maxHeight,
                    corner = corner,
                    bigSmall = panes?.bigSmall == true,
                    claimedTop = baseTopPx?.let { with(density) { it.toDp() } }
                )
            }
            val placed = if (place == null) {
                Modifier.align(Alignment.BottomStart).then(promptPlace)
            } else {
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = place.start, bottom = place.bottom)
                    .claimsBottomLeft(stacked = true, enabled = place.holdsCorner)
            }
            if (countdown != null) {
                var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(countdown) {
                    while (true) {
                        nowMs = System.currentTimeMillis()
                        delay(CARD_TICK_MS)
                    }
                }
                AlertCountdownCard(
                    game = alert.item.displayName,
                    secondsLeft = countdown.secondsLeft(nowMs),
                    selected = countdown.selected,
                    canBoth = canBoth(),
                    movieTitle = playerTitle?.takeIf { watching.inPlayer && !watching.sportsPlayback }?.let(::sportsLivePlainTitle),
                    modifier = placed
                )
                return@BoxWithConstraints
            }
            if (alert is SportsLiveAlert.Kickoff) {
                val content = remember(alert) { SportsLiveAlertContent.of(alert) as? AlertContent.Kickoff }
                if (content == null) {
                    LaunchedEffect(current.id) { alerts.close(current, AlertAnswer.DISMISS) }
                    return@BoxWithConstraints
                }
                val kickoffLabelOf = rememberSportsLiveStateLabeler(SportsLiveTab.UPCOMING)
                KickoffToast(
                    content = content,
                    compact = SportsLiveAlertEngine.kickoffStyle(watching) == KickoffStyle.COMPACT,
                    noStreamYet = alert.noStreamYet,
                    streamReady = remember(readyMarks, alert) { SportsLiveGameReadiness.shared.of(alert.item) == SportsLiveReadyMark.READY },
                    watchingTitle = playerTitle?.let(::sportsLivePlainTitle),
                    // A time only when it is the real kickoff (F11: never "12:00 AM").
                    kickoffLabel = kickoffLabelOf(alert.item.copy(isLive = false))
                        .takeIf { sportsLiveKickoffShowsTime(alert.item.startMs, alert.item.startHasTimeOfDay, System.currentTimeMillis()) },
                    modifier = placed
                )
                return@BoxWithConstraints
            }
            val content = remember(alert) { SportsLiveAlertContent.of(alert) }
            if (content == null) {
                LaunchedEffect(current.id) { alerts.close(current, AlertAnswer.DISMISS) }
                return@BoxWithConstraints
            }
            AlertCardView(
                cardId = current.id,
                content = content,
                followed = alert.item.followed,
                // "OK Watch · Back Close" on a card that takes OK; none on Full time (it only informs).
                hint = if (actionable) stringResource(R.string.sports_live_alert_hint_ok_watch) else null,
                modifier = placed
            )
        }
    }
    }
}


/**
 * ▲ Both on a kickoff prompt (live.20): the game's fresh card ([freshCard], the one Watch plays: the newest load's copy,
 * live, with its live link) goes beside the game playing ([both]); when dual screen cannot open, it plays as Watch.
 */
internal suspend fun sportsLiveKickoffBoth(
    item: SportsLiveItem,
    freshCard: suspend (SportsLiveItem) -> SportsLiveItem,
    both: (SportsLiveItem) -> Boolean,
    watch: (SportsLiveItem) -> Unit
) {
    val fresh = freshCard(item)
    if (!both(fresh)) watch(fresh)
}

/**
 * While [active], Back is [onBack]'s (#136). The callback is added when it turns active, so it is the
 * newest and runs before the screens' own (a [BackHandler] composed at app start would come last); the
 * key events of that same press are then swallowed ([SportsLiveAlertSurfaces.backTaken]).
 */
@Composable
private fun TakeBack(active: Boolean, onBack: () -> Unit) {
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher ?: return
    val currentOnBack by rememberUpdatedState(onBack)
    DisposableEffect(active, dispatcher) {
        if (!active) return@DisposableEffect onDispose { }
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                SportsLiveAlertSurfaces.backTaken()
                currentOnBack()
            }
        }
        dispatcher.addCallback(callback)
        onDispose { callback.remove() }
    }
}

/**
 * Where a score or full-time card sits (live.20: one corner for everything): its bottom left corner as [start] and
 * [bottom] insets of the screen. [holdsCorner]: it is in the toasts' corner, so the toast stacks above it.
 */
internal data class AlertCardPlace(val start: Dp, val bottom: Dp, val holdsCorner: Boolean)

/**
 * Where a card sits (#136, live.20): the toasts' [corner] (the screen's, the player's, dual screen's left tile),
 * [SportsLiveTokens.Size.RowGap] above a card already there (its top edge at [claimedTop]: the player's one card or
 * the kickoff prompt); the toast then stacks above it. In dual screen split a card about the left game goes to the
 * bottom left of the right pane instead, [SportsLiveTokens.Alert.PaneInset] inside, so it never covers the game it is
 * about. Big + small (#123): the small tile is narrower than a card, so every card takes the corner (the big tile).
 */
internal fun alertCardPlace(
    spot: AlertSpot,
    width: Dp,
    height: Dp,
    corner: SportsLiveCorner,
    bigSmall: Boolean = false,
    claimedTop: Dp? = null
): AlertCardPlace {
    if (spot == AlertSpot.RIGHT_PANE && !bigSmall) {
        val pane = DualGeometry.corner(Side.RIGHT, DualLayout.SPLIT, width, height)
        return AlertCardPlace(pane.start, pane.bottom, holdsCorner = false)
    }
    val bottom = sportsLiveToastBottom(corner.bottom, height, claimedTop, SportsLiveTokens.Size.RowGap)
    return AlertCardPlace(corner.start, bottom, holdsCorner = true)
}

/**
 * The kickoff card (live.24, replacing research Q17's "▶ or hold OK" toast): "LIVE · Just started", the matchup,
 * "Stream ready" or "Waiting for a working stream…", and its keys "OK Watch · Back Close". The no-stream note
 * ([noStreamYet], #162) offers "OK Remind me in 10 min · Back Close". Its time and its keys are the host's.
 */
@Composable
private fun KickoffToast(
    content: AlertContent.Kickoff,
    compact: Boolean,
    noStreamYet: Boolean,
    streamReady: Boolean,
    watchingTitle: String?,
    kickoffLabel: String?,
    modifier: Modifier
) {
    val shape = RoundedCornerShape(16.dp)
    val logoSize = if (compact) 36.dp else 44.dp
    Column(
        modifier = modifier
            .claimsBottomLeft()
            .widthIn(max = if (compact) 440.dp else 520.dp)
            .clip(shape)
            // V2 (#134): the toast card's raised surface, in the toasts' corner (live.19 QA F11).
            .background(SportsLiveTokens.Colors.SurfaceRaised.copy(alpha = 0.96f))
            .border(1.dp, SportsLiveTokens.Colors.Outline, shape)
            .padding(if (compact) 14.dp else 18.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!noStreamYet) {
                Text(
                    text = stringResource(R.string.sports_live_live_pill),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = ArtColors.Text,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(ArtColors.LiveRed)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
            Text(
                text = stringResource(if (noStreamYet) R.string.sports_live_alert_no_stream_yet else R.string.sports_live_alert_just_started),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = if (noStreamYet) ArtColors.Secondary else ArtColors.LiveText
            )
            if (content.followed) StarIcon()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val logos = rememberTeamLogoPair(content.first.logo, content.second.logo)
            logos?.let { TeamLogoSlot(it.first, it.shown, logoSize) }
            Text(
                text = stringResource(R.string.sports_live_alert_matchup, content.first.name, content.second.name),
                style = (if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge).sportsLiveBold(),
                color = ArtColors.Text,
                // One line (F11): it never wraps over the screen behind.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            logos?.let { TeamLogoSlot(it.second, it.shown, logoSize) }
        }
        val details = if (noStreamYet) {
            listOf(stringResource(R.string.sports_live_alert_no_stream_line))
        } else {
            listOfNotNull(
                SportsLiveDisplayText.label(content.league),
                kickoffLabel,
                watchingTitle?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.sports_live_alert_watching, it) }
            )
        }
        if (details.isNotEmpty()) {
            Text(
                text = details.joinToString(HINT_SEPARATOR),
                style = MaterialTheme.typography.bodyMedium,
                color = ArtColors.Secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!noStreamYet) {
            // live.20 1b wording, kept: a verified link held, or not yet.
            Text(
                text = stringResource(if (streamReady) R.string.sports_live_kickoff_stream_ready_short else R.string.sports_live_kickoff_waiting_stream),
                style = MaterialTheme.typography.bodyMedium,
                color = if (streamReady) ArtColors.Text else ArtColors.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // Its keys, named (research 4): OK is the card's while it shows.
        Text(
            text = stringResource(if (noStreamYet) R.string.sports_live_alert_hint_ok_remind else R.string.sports_live_alert_hint_ok_watch),
            style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag),
            color = SportsLiveTokens.Alert.Hint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * The countdown card OK opens (live.24): "Switching to ‹game› in 3…" ([secondsLeft]; null once stopped: "Switch to
 * ‹game›?"), [Watch now] and, where offered ([canBoth]), [Watch both], the [selected] one ringed as focus is in the V2
 * look; "Back Stay here". Never a score (whatever the Scores setting). Over a movie or show ([movieTitle]) it says the
 * film's place is kept.
 */
@Composable
private fun AlertCountdownCard(
    game: String,
    secondsLeft: Int?,
    selected: AlertChoice,
    canBoth: Boolean,
    movieTitle: String?,
    modifier: Modifier
) {
    val shape = RoundedCornerShape(SportsLiveTokens.Alert.Radius)
    Column(
        modifier = modifier
            .width(SportsLiveTokens.Alert.Width + 60.dp)
            .clip(shape)
            .background(SportsLiveTokens.Colors.SurfaceRaised.copy(alpha = 0.96f))
            .border(1.dp, SportsLiveTokens.Colors.Outline, shape)
            .padding(SportsLiveTokens.Alert.Padding + 2.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = if (secondsLeft != null) {
                stringResource(R.string.sports_live_alert_switching_to, game, secondsLeft)
            } else {
                stringResource(R.string.sports_live_alert_switch_to, game)
            },
            style = MaterialTheme.typography.titleSmall.sportsLiveBold(),
            color = ArtColors.Text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        movieTitle?.takeIf { it.isNotBlank() }?.let {
            SmallLine(stringResource(R.string.sports_live_alert_movie_saved, it), ArtColors.Secondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            CountdownChoice(stringResource(R.string.sports_live_alert_choice_watch_now), selected == AlertChoice.WATCH_NOW)
            if (canBoth) CountdownChoice(stringResource(R.string.sports_live_alert_choice_watch_both), selected == AlertChoice.WATCH_BOTH)
        }
        SmallLine(stringResource(R.string.sports_live_alert_hint_stay), SportsLiveTokens.Alert.Hint)
    }
}

/** One choice of the countdown card: ringed and filled white while selected (the V2 focus look). */
@Composable
private fun CountdownChoice(text: String, selected: Boolean) {
    val shape = SportsLiveTokens.Radius.ButtonShape
    Text(
        text = text,
        style = SportsLiveTokens.style(SportsLiveTokens.Type.ControlSelected),
        color = if (selected) Color.Black else ArtColors.Text,
        maxLines = 1,
        modifier = Modifier
            .clip(shape)
            .background(if (selected) SportsLiveTokens.Colors.FocusWhite else Color.Transparent)
            .border(if (selected) 0.dp else 1.dp, SportsLiveTokens.Colors.OutlineStrong, shape)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    )
}

/**
 * An alert card (#136, live.24): slim, slightly see-through, sliding in from the left as the toasts do (live.20). One
 * alert, never cycling; [hint] ("OK Watch · Back Close") on a card that takes OK, none on Full time.
 */
@Composable
private fun AlertCardView(
    cardId: Long,
    content: AlertContent,
    followed: Boolean,
    hint: String?,
    modifier: Modifier
) {
    val visible = remember(cardId) { MutableTransitionState(false).apply { targetState = true } }
    val shape = RoundedCornerShape(SportsLiveTokens.Alert.Radius)
    AnimatedVisibility(
        visibleState = visible,
        enter = slideInHorizontally(tween(SportsLiveTokens.Alert.SLIDE_MS)) { -it / 2 } + fadeIn(tween(SportsLiveTokens.Alert.SLIDE_MS)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .width(SportsLiveTokens.Alert.Width)
                .clip(shape)
                .background(ArtColors.Panel.copy(alpha = SportsLiveTokens.Alert.FILL_ALPHA))
                .border(1.dp, SportsLiveTokens.Alert.Border, shape)
                .padding(SportsLiveTokens.Alert.Padding),
            verticalArrangement = Arrangement.spacedBy(SportsLiveTokens.Alert.Gap)
        ) {
            AlertBody(content, followed)
            hint?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.weight(1f))
                    SmallLine(it, SportsLiveTokens.Alert.Hint)
                }
            }
        }
    }
}


@Composable
private fun AlertBody(content: AlertContent, followed: Boolean) {
    when (content) {
        is AlertContent.Score -> {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (followed) StarIcon()
                Text(
                    text = content.scorer?.let { stringResource(R.string.sports_live_alert_scored, it.name) }
                        ?: stringResource(R.string.sports_live_alert_score_update),
                    style = MaterialTheme.typography.titleSmall.sportsLiveBold(),
                    color = ArtColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                content.clock?.let { SmallLine(it, ArtColors.LiveText) }
            }
            ScoreLine(content.first, content.second)
        }
        is AlertContent.FullTime -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.sports_live_alert_full_time),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = ArtColors.Secondary
                )
                val details = listOfNotNull(content.league, content.note)
                if (details.isNotEmpty()) SmallLine(details.joinToString(HINT_SEPARATOR), ArtColors.Muted)
            }
            ScoreLine(content.first, content.second)
        }
        // Crunch time (#137): "Close finish" and where the game stands; the score under Show only.
        is AlertContent.Crunch -> {
            val moment = crunchMomentText(content.moment)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.sports_live_alert_close_finish),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = ArtColors.LiveText
                )
                val details = listOfNotNull(content.league, moment.takeIf { content.withScore })
                if (details.isNotEmpty()) SmallLine(details.joinToString(HINT_SEPARATOR), ArtColors.Muted)
            }
            if (content.withScore) {
                ScoreLine(content.first, content.second)
            } else {
                Text(
                    text = listOfNotNull(stringResource(R.string.sports_live_alert_matchup, content.first.name, content.second.name), moment)
                        .joinToString(", "),
                    style = MaterialTheme.typography.titleSmall,
                    color = ArtColors.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        is AlertContent.FullTimeNamesOnly -> Text(
            text = stringResource(R.string.sports_live_alert_full_time_names, content.first.name, content.second.name),
            style = MaterialTheme.typography.titleSmall,
            color = ArtColors.Text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        // Not shown since live.24 (the kickoff has its own card); kept so every content has words.
        is AlertContent.Kickoff -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (followed) StarIcon()
            Text(
                text = stringResource(
                    R.string.sports_live_alert_starting_toast,
                    stringResource(R.string.sports_live_alert_matchup, content.first.name, content.second.name)
                ),
                style = MaterialTheme.typography.titleSmall,
                color = ArtColors.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ScoreLine(first: AlertSide, second: AlertSide) {
    val firstScore = first.score ?: return
    val secondScore = second.score ?: return
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        // Logos only when both teams have one and both loaded (#130); never letters.
        val logos = rememberTeamLogoPair(first.logo, second.logo)
        logos?.let { TeamLogoSlot(it.first, it.shown, SportsLiveTokens.Alert.Logo) }
        Text(
            text = stringResource(R.string.sports_live_alert_score_line, first.short, firstScore, secondScore, second.short),
            style = MaterialTheme.typography.titleMedium.sportsLiveBold().copy(fontFeatureSettings = "tnum"),
            color = ArtColors.Text,
            maxLines = 1
        )
        logos?.let { TeamLogoSlot(it.second, it.shown, SportsLiveTokens.Alert.Logo) }
    }
}

/** Where a crunch-time game stands (#137): "Overtime", "Final minute", "Final 3 min", else ESPN's clock ("Bot 9th", "88'"). */
@Composable
private fun crunchMomentText(moment: CrunchMoment): String? = when {
    moment.overtime -> stringResource(R.string.sports_live_alert_crunch_overtime)
    moment.minutesLeft == 1 -> stringResource(R.string.sports_live_alert_crunch_final_minute)
    moment.minutesLeft != null -> stringResource(R.string.sports_live_alert_crunch_final_minutes, moment.minutesLeft)
    else -> moment.clock?.takeIf { it.isNotBlank() }
}

@Composable
private fun SmallLine(text: String, color: Color) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun StarIcon() {
    Icon(imageVector = Icons.Rounded.Star, contentDescription = null, tint = FOLLOWED_STAR_COLOR, modifier = Modifier.size(18.dp))
}

/** A card checks its time this often. */
private const val CARD_TICK_MS = 250L

private const val HINT_SEPARATOR = " · "
