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
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import android.view.SurfaceView
import android.view.ViewGroup
import com.nuvio.tv.core.sportslive.SportsLiveToastKind
import com.nuvio.tv.core.sportslive.SportsLiveToasts
import androidx.annotation.OptIn
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.sportslive.alerts.AlertCard
import com.nuvio.tv.core.sportslive.alerts.AlertKeyAction
import com.nuvio.tv.core.sportslive.scores.CrunchMoment
import com.nuvio.tv.core.sportslive.alerts.AlertKeyContext
import com.nuvio.tv.core.sportslive.alerts.AlertSpot
import com.nuvio.tv.core.sportslive.alerts.PeekCommand
import com.nuvio.tv.core.sportslive.alerts.PeekEvent
import com.nuvio.tv.core.sportslive.alerts.PeekPhase
import com.nuvio.tv.core.sportslive.alerts.PeekState
import com.nuvio.tv.core.sportslive.alerts.PromptChoice
import com.nuvio.tv.core.sportslive.alerts.SportsLiveAlertCards
import com.nuvio.tv.core.sportslive.alerts.SportsLiveAlertKeys
import com.nuvio.tv.core.sportslive.alerts.SportsLiveAlertPlacement
import com.nuvio.tv.core.sportslive.alerts.SportsLivePeek
import com.nuvio.tv.core.sportslive.displayName
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.screens.sportslive.preview.SportsLivePreviewPlayer
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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

/** Hands the app-wide [SportsLiveAlerts] to the host, and looks a peek's stream up (#136). */
@HiltViewModel
class SportsLiveAlertsViewModel @Inject constructor(
    val alerts: SportsLiveAlerts,
    /** My games' readiness checks (1b): run beside the alerts while the app is in the foreground. */
    val readyKeeper: SportsLiveReadyKeeper,
    private val resolver: SportsLiveZapResolver,
    private val directDebridResolver: DirectDebridResolver
) : ViewModel() {

    /** [item]'s stream for a peek: one a light player plays as is (a plain url), else null. */
    suspend fun peekStream(item: SportsLiveItem): Stream? = try {
        resolver.resolve(item)?.takeIf { sportsLiveLightPlayable(it, directDebridResolver) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

/** The player under a peek (#136): it went quiet and gets its sound back; [entryId], its back stack entry. */
private class PeekSession(val surface: SportsLiveAlertSurface, val host: SportsLivePeekHost, val entryId: String?)

/**
 * Reminders' "Watch now?" prompt (#44) and followed teams' toasts (#42), over every screen while the
 * app is in the foreground. Over the player the prompt is the small one. The prompt takes focus (Watch
 * first) and gives it back to [contentFocusRequester] when it closes.
 *
 * Toasts never take focus (#136): toasts raised close together share one slim card at the top right that
 * cycles through them. Over the full player of a game or dual screen, while that screen's own overlays are
 * closed, the card takes OK (its game plays here: a zap, or the highlighted pane), ▼ (a peek: its game
 * full screen with sound for a few seconds, the game from before kept playing quietly underneath) and
 * Back (it goes); every other key, and every key while not actionable, goes to the screen as usual
 * ([SportsLiveAlertSurfaces.dispatch], [SportsLiveAlertKeys]).
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
                playingTitle = playerTitle
            )
        )
    }

    // Watch: the game's best stream (#56), replacing the player when one plays.
    SportsLivePlayHost(
        playViewModel = playViewModel,
        onOpenStreamScreen = { item, manual -> navController.navigate(streamRouteFor(item, returnToHomeOnBack = false, manualSelection = manual)) },
        onPlayTarget = { target ->
            val playerEntry = navController.currentBackStackEntry?.takeIf { it.destination.route == Screen.Player.route }
            // Only a Sports & Live stream is replaced; a movie or show player is never closed by Watch.
            if (playerEntry != null && SportsLiveExtraSources.isSportsPlayback(playerEntry.arguments?.getString("contentId"))) {
                navigateToSportsLiveZapTarget(navController, target)
            } else {
                navController.navigate(sportsLiveTargetRoute(target))
            }
        },
        cooldownToasts = false
    )

    // "Reminder set" / "Reminder removed" (#44): the one toast card (#134, live.19 QA F8), bottom left, never
    // taking focus. The kickoff prompt and the score cards sit in the same corner (live.20): over video, the
    // player's (above its controls while they show) or dual screen's left tile.
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

    // The tip-off heads-up (1b), my games only: kickoff and no working stream yet; the prompt follows once one is verified.
    val headsUp by alerts.headsUp.collectAsState()
    LaunchedEffect(headsUp?.id) {
        val shown = headsUp ?: return@LaunchedEffect
        SportsLiveToasts.show(
            SportsLiveToastKind.REMINDER,
            title = toastContext.getString(R.string.sports_live_heads_up_title, SportsLiveDisplayText.name(shown.item.meta.name)),
            line = toastContext.getString(R.string.sports_live_heads_up_line)
        )
        alerts.closeHeadsUp(shown)
    }

    val card by alerts.current.collectAsState()
    val watching by alerts.watchingNow.collectAsState()
    // 1b: the kickoff prompt says whether a verified link is held ("Stream ready · Watch now?").
    val readyMarks by SportsLiveGameReadiness.shared.marks.collectAsState()
    val surface by SportsLiveAlertSurfaces.surface.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun giveFocusBack() {
        runCatching { contentFocusRequester.restoreFocusedChild() || contentFocusRequester.requestFocus(FocusDirection.Enter) }
    }

    /** [item] plays here: [on]'s own way (a zap, the highlighted pane), else as Watch on a prompt. */
    fun playHere(item: SportsLiveItem, on: SportsLiveAlertSurface?) {
        if (on?.watch(item) != true) scope.launch { playViewModel.play(alerts.cardToWatch(item)) }
    }

    // The peek (#136): its state machine ([SportsLivePeek]) and the player under it.
    var peek by remember { mutableStateOf<PeekState?>(null) }
    var peekSession by remember { mutableStateOf<PeekSession?>(null) }
    var peekStream by remember { mutableStateOf<Pair<SportsLiveItem, Stream>?>(null) }
    fun onPeek(event: PeekEvent) {
        val state = peek ?: return
        val session = peekSession ?: return
        val (next, command) = SportsLivePeek.step(state, event)
        peek = next
        if (next == null) {
            peekSession = null
            peekStream = null
        }
        when (command) {
            PeekCommand.NONE, PeekCommand.END -> Unit
            PeekCommand.ZAP -> playHere(state.item, session.surface)
            PeekCommand.RETURN -> session.host.restore()
            PeekCommand.RETURN_FAILED -> {
                session.host.restore()
                SportsLiveToasts.show(SportsLiveToastKind.PROBLEM, context.getString(R.string.sports_live_zap_no_stream, state.item.displayName))
            }
        }
    }
    fun startPeek(item: SportsLiveItem, on: SportsLiveAlertSurface) {
        val host = on.peek ?: return
        host.quiet()
        peekSession = PeekSession(on, host, entry?.id)
        peekStream = null
        peek = SportsLivePeek.start(item, System.currentTimeMillis())
    }
    val peekKey = peek?.item?.streamKey
    LaunchedEffect(peekKey) {
        val item = peek?.item ?: return@LaunchedEffect
        val stream = viewModel.peekStream(item)
        if (peek?.item?.streamKey != item.streamKey) return@LaunchedEffect
        if (stream == null) onPeek(PeekEvent.Failed) else peekStream = item to stream
    }
    LaunchedEffect(peekKey != null) {
        while (peek != null) {
            onPeek(PeekEvent.Tick(System.currentTimeMillis()))
            delay(PEEK_TICK_MS)
        }
    }
    // The zap of OK arrived (or the player went another way): nothing under the peek to give back.
    LaunchedEffect(entry?.id) {
        val session = peekSession ?: return@LaunchedEffect
        if (session.entryId != entry?.id) onPeek(PeekEvent.PlayerReplaced)
    }
    // The app leaves the foreground: the peek ends, the game from before gets its sound back.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && peek != null) {
                peekSession?.host?.restore()
                peek = null
                peekSession = null
                peekStream = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Who takes a key (#136): a toast card over a screen that can play its game here, with that screen's
    // own overlays closed; a peek takes every key but the volume.
    val currentCard by rememberUpdatedState(card)
    val currentSurface by rememberUpdatedState(surface)
    val canActHere by rememberUpdatedState((inPlayer && watching.sportsPlayback) || dualRoute)
    val currentInPlayer by rememberUpdatedState(inPlayer)
    // The kickoff prompt's chosen button while it takes the keys (#173), by card: Watch first on each new prompt.
    var promptPick by remember { mutableStateOf<Pair<Long, PromptChoice>?>(null) }
    fun choose(choice: PromptChoice) {
        promptPick = currentCard?.let { it.id to choice }
    }
    fun keyContext(): AlertKeyContext {
        val on = currentSurface
        // ▲ Both: the full player only; dual screen already shows two games (OK replaces the highlighted one).
        // A kickoff with no live stream yet (#162) has nothing to put beside the game.
        val kickoff = currentCard?.first as? SportsLiveAlert.Kickoff
        return SportsLiveAlertKeys.context(
            card = currentCard,
            canActHere = canActHere,
            surfaceBusy = on?.busy(),
            canPeek = currentInPlayer && on?.peek?.canPeek() == true,
            peeking = peek != null,
            promptChoice = promptPick?.takeIf { it.first == currentCard?.id }?.second ?: PromptChoice.WATCH,
            canBoth = currentInPlayer && kickoff?.noStreamYet != true && on?.offersBoth() == true
        )
    }
    /** Watch on the kickoff prompt over the player or dual screen (#173): the game's fresh card, played here. */
    fun watchFromPrompt(shown: AlertCard) {
        alerts.close(shown, AlertAnswer.WATCH)
        val on = currentSurface
        scope.launch { playHere(alerts.cardToWatch(shown.first.item), on) }
    }
    val keyHandler = remember {
        object : AlertKeyHandler {
            override fun context(): AlertKeyContext = keyContext()

            override fun onAction(action: AlertKeyAction) {
                val nowMs = System.currentTimeMillis()
                when (action) {
                    AlertKeyAction.PASS, AlertKeyAction.CONSUME -> Unit
                    AlertKeyAction.PEEK_STAY -> onPeek(PeekEvent.Ok(nowMs))
                    AlertKeyAction.PEEK_RETURN -> onPeek(PeekEvent.Back)
                    AlertKeyAction.PROMPT_CHOOSE_WATCH -> choose(PromptChoice.WATCH)
                    AlertKeyAction.PROMPT_CHOOSE_LATER -> choose(PromptChoice.LATER)
                    // No live stream yet (#162): the buttons are Remind me in 10 min (Later's answer) and Dismiss.
                    AlertKeyAction.PROMPT_WATCH -> currentCard?.let {
                        if (it.noStreamYet()) alerts.close(it, AlertAnswer.LATER) else watchFromPrompt(it)
                    }
                    AlertKeyAction.PROMPT_LATER -> currentCard?.let {
                        alerts.close(it, if (it.noStreamYet()) AlertAnswer.DISMISS else AlertAnswer.LATER)
                    }
                    AlertKeyAction.WATCH, AlertKeyAction.PEEK, AlertKeyAction.BOTH, AlertKeyAction.DISMISS -> {
                        val shown = currentCard ?: return
                        // OK acts on the alert the card shows now (of those it shows: the ones with words).
                        val worded = shown.alerts.filter { SportsLiveAlertContent.of(it) != null }.ifEmpty { shown.alerts }
                        val alert = worded[SportsLiveAlertCards.showingIndex(shown.copy(alerts = worded), nowMs)]
                        val on = currentSurface
                        when (action) {
                            AlertKeyAction.WATCH -> {
                                alerts.close(shown, AlertAnswer.WATCH)
                                playHere(alert.item, on)
                            }
                            AlertKeyAction.PEEK -> {
                                alerts.close(shown, AlertAnswer.WATCH)
                                on?.let { startPeek(alert.item, it) }
                            }
                            AlertKeyAction.BOTH -> {
                                alerts.close(shown, AlertAnswer.WATCH)
                                when {
                                    // A kickoff's fresh card (its live link, #162) goes beside the game, as Watch takes it.
                                    shown.first is SportsLiveAlert.Kickoff -> scope.launch {
                                        sportsLiveKickoffBoth(alert.item, alerts::cardToWatch, { on?.watchBoth(it) == true }) { playHere(it, on) }
                                    }
                                    on?.watchBoth(alert.item) == true -> Unit
                                    else -> playHere(alert.item, on)
                                }
                            }
                            else -> alerts.close(shown, AlertAnswer.DISMISS)
                        }
                    }
                }
            }
        }
    }
    DisposableEffect(keyHandler) {
        SportsLiveAlertSurfaces.keys = keyHandler
        onDispose { if (SportsLiveAlertSurfaces.keys === keyHandler) SportsLiveAlertSurfaces.keys = null }
    }

    // Back reaches the screens by the back callback first on recent Android: during a peek it is the peek's.
    TakeBack(peek != null) { onPeek(PeekEvent.Back) }
    peek?.let { state ->
        SportsLivePeekLayer(
            state = state,
            stream = peekStream?.takeIf { it.first.streamKey == state.item.streamKey }?.second,
            onFirstFrame = { onPeek(PeekEvent.FirstFrame(System.currentTimeMillis())) },
            onError = { onPeek(PeekEvent.Failed) }
        )
    }

    val current = card ?: return
    // Each card is its own overlay: its countdown and first focus start afresh.
    key(current.id) {
    Box(modifier = Modifier.fillMaxSize()) {
        val first = current.first
        val style = (first as? SportsLiveAlert.Kickoff)?.let { SportsLiveAlertEngine.kickoffStyle(watching) }
        if (first is SportsLiveAlert.Kickoff && style != KickoffStyle.TOAST) {
            val content = remember(first) { SportsLiveAlertContent.of(first) as? AlertContent.Kickoff }
            if (content == null) {
                LaunchedEffect(current.id) { alerts.close(current, AlertAnswer.DISMISS) }
                return@Box
            }
            // Over the player or dual screen the prompt takes its keys by the dispatcher (#173), not by focus;
            // Back is Later, by the back callback as by the key.
            val keyChoice = keyContext().prompt
            TakeBack(keyChoice != null && peek == null) {
                currentCard?.let { alerts.close(it, if (it.noStreamYet()) AlertAnswer.DISMISS else AlertAnswer.LATER) }
            }
            // While it is up, the player's own cards wait under it (live.19 QA F11: one focused button at a time).
            DisposableEffect(current.id) {
                SportsLiveAlertSurfaces.setPromptUp(true)
                onDispose { SportsLiveAlertSurfaces.setPromptUp(false) }
            }
            val kickoffLabelOf = rememberSportsLiveStateLabeler(SportsLiveTab.UPCOMING)
            KickoffPrompt(
                content = content,
                compact = style == KickoffStyle.COMPACT,
                noStreamYet = first.noStreamYet,
                streamReady = remember(readyMarks, first) { SportsLiveGameReadiness.shared.of(first.item) == SportsLiveReadyMark.READY },
                // ▲ Both (the full player only, never dual screen): this game and the kickoff's side by side.
                onBoth = surface?.takeIf { inPlayer && !dualRoute && style == KickoffStyle.COMPACT && !first.noStreamYet && it.offersBoth() }?.let { on ->
                    {
                        alerts.close(current, AlertAnswer.WATCH)
                        scope.launch { sportsLiveKickoffBoth(first.item, alerts::cardToWatch, on::watchBoth) { playViewModel.play(it) } }
                    }
                },
                watchingTitle = playerTitle?.let(::sportsLivePlainTitle),
                // "Kicking off now" already says when: a time only when it is the real kickoff (F11: never "12:00 AM").
                kickoffLabel = kickoffLabelOf(first.item.copy(isLive = false))
                    .takeIf { sportsLiveKickoffShowsTime(first.item.startMs, first.item.startHasTimeOfDay, System.currentTimeMillis()) },
                keyChoice = keyChoice,
                onWatch = {
                    alerts.close(current, AlertAnswer.WATCH)
                    scope.launch { playViewModel.play(alerts.cardToWatch(first.item)) }
                },
                onLater = {
                    alerts.close(current, AlertAnswer.LATER)
                    giveFocusBack()
                },
                onBack = {
                    alerts.close(current, AlertAnswer.DISMISS)
                    giveFocusBack()
                },
                onTimeout = {
                    alerts.close(current, AlertAnswer.TIMEOUT)
                    // A key-driven prompt never took focus: there is none to give back.
                    if (keyChoice == null) giveFocusBack()
                },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .then(promptPlace)
            )
            return@Box
        }
        // A toast card: the alerts that have words, cycled through; it leaves when its time is up. Over a
        // movie or show (a kickoff toast) it never takes a key, so OK can never replace that player.
        val shownCard by rememberUpdatedState(current)
        val worded = remember(current) {
            current.alerts.mapNotNull { alert -> SportsLiveAlertContent.of(alert)?.let { alert to it } }
        }
        var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                nowMs = System.currentTimeMillis()
                val live = shownCard
                if (nowMs >= SportsLiveAlertCards.endsAtMs(live)) {
                    alerts.close(live, if (live.first is SportsLiveAlert.Kickoff) AlertAnswer.DISMISS else AlertAnswer.TIMEOUT)
                    break
                }
                delay(CARD_TICK_MS)
            }
        }
        if (worded.isEmpty()) {
            LaunchedEffect(current) { alerts.close(current, AlertAnswer.DISMISS) }
            return@Box
        }
        val index = SportsLiveAlertCards.showingIndex(current.copy(alerts = worded.map { it.first }), nowMs)
        val (alert, content) = worded[index]
        // The hint shows only while the card takes the keys (read again with every tick).
        val keys = keyContext()
        // Back closes an actionable card only (never the player under it), by the back callback as by the key.
        TakeBack(keys.cardActionable && peek == null) { currentCard?.let { alerts.close(it, AlertAnswer.DISMISS) } }
        val hint = if (peek != null || !keys.cardActionable) {
            null
        } else {
            // "OK Watch · ▲ Both · ▼ Peek · Back Dismiss", each part only where it works (research 4: every prompt
            // names its keys, Back too).
            listOfNotNull(
                stringResource(R.string.sports_live_alert_hint_watch),
                stringResource(R.string.sports_live_alert_hint_both).takeIf { keys.canBoth },
                stringResource(R.string.sports_live_alert_hint_peek).takeIf { keys.canPeek },
                stringResource(R.string.sports_live_alert_hint_dismiss)
            ).joinToString(HINT_SEPARATOR)
        }
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // One corner for everything (live.20): above the player's card or the prompt, the toast above this card.
            val density = LocalDensity.current
            val baseTopPx by SportsLiveBottomLeft.baseTopPx.collectAsState()
            val place = alertCardPlace(
                spot = SportsLiveAlertPlacement.spot(alert, panes),
                width = maxWidth,
                height = maxHeight,
                corner = corner,
                bigSmall = panes?.bigSmall == true,
                claimedTop = baseTopPx?.let { with(density) { it.toDp() } }
            )
            AlertCardView(
                cardId = current.id,
                content = content,
                followed = alert.item.followed,
                hint = hint,
                index = index,
                count = worded.size,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = place.start, bottom = place.bottom)
                    .claimsBottomLeft(stacked = true, enabled = place.holdsCorner)
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
 * The peek (#136): the peeked game full screen with sound, over the player whose game plays on quietly
 * underneath. Its label says what to press; the countdown runs from the first frame.
 */
@OptIn(UnstableApi::class)
@Composable
private fun SportsLivePeekLayer(state: PeekState, stream: Stream?, onFirstFrame: () -> Unit, onError: () -> Unit) {
    val context = LocalContext.current
    val currentFirstFrame by rememberUpdatedState(onFirstFrame)
    val currentError by rememberUpdatedState(onError)
    val player = remember {
        SportsLivePreviewPlayer(
            context = context,
            onFirstFrame = { currentFirstFrame() },
            onError = { currentError() },
            lowBitrate = false,
            withSound = true
        )
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(stream) { if (stream != null) player.play(state.item, stream) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(CARD_TICK_MS)
        }
    }
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    isFocusable = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    keepScreenOn = true
                    // Above the full player's own video surface, which keeps playing underneath.
                    (videoSurfaceView as? SurfaceView)?.setZOrderMediaOverlay(true)
                }.also(player::attach)
            },
            onRelease = { player.attach(null) },
            modifier = Modifier.fillMaxSize()
        )
        if (state.phase == PeekPhase.LOADING) LoadingIndicator(modifier = Modifier.align(Alignment.Center))
        val secondsLeft = if (state.phase == PeekPhase.PLAYING) {
            ((SportsLivePeek.PEEK_MS - (nowMs - state.sinceMs)).coerceAtLeast(0L) + 999L) / 1_000L
        } else {
            null
        }
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = SportsLiveTokens.Size.SafeTop)
                .clip(RoundedCornerShape(SportsLiveTokens.Alert.Radius))
                .background(SportsLiveTokens.Alert.PeekLabelFill)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.sports_live_alert_peeking, state.item.displayName),
                style = MaterialTheme.typography.titleSmall.sportsLiveBold(),
                color = ArtColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (secondsLeft != null) {
                Text(
                    text = stringResource(R.string.sports_live_alert_peek_hint, secondsLeft.toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = SportsLiveTokens.Alert.Hint,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * The "Watch now?" prompt (#44). Outside the player and dual screen ([keyChoice] null) it takes focus, Watch
 * first. Over them (#173) it takes no focus: the dispatcher gives it OK, ← → and Back ([SportsLiveAlertKeys]),
 * it draws [keyChoice] focused and says so in a hint.
 */
@Composable
private fun KickoffPrompt(
    content: AlertContent.Kickoff,
    compact: Boolean,
    /** The quiet note (#162): no link came up within 20 minutes; Remind me in 10 min / Dismiss, no Watch. */
    noStreamYet: Boolean,
    /** 1b: a verified link is held, so Watch plays it at once: "▶ Ready · Stream ready · Watch now?"; else "Waiting for a working stream…". */
    streamReady: Boolean,
    /** ▲ Both over the full player; null where dual screen cannot open. */
    onBoth: (() -> Unit)?,
    watchingTitle: String?,
    kickoffLabel: String?,
    keyChoice: PromptChoice?,
    onWatch: () -> Unit,
    onLater: () -> Unit,
    onBack: () -> Unit,
    onTimeout: () -> Unit,
    modifier: Modifier
) {
    val watchFocus = remember { FocusRequester() }
    var secondsLeft by remember { mutableIntStateOf((PROMPT_MS / 1_000).toInt()) }
    val keyDriven = keyChoice != null
    LaunchedEffect(keyDriven) {
        if (keyDriven) return@LaunchedEffect
        withFrameNanos { }
        runCatching { watchFocus.requestFocus() }
    }
    // The countdown runs only while the app is in the foreground; it resumes where it was.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (secondsLeft > 0) {
                delay(1_000)
                secondsLeft--
            }
            onTimeout()
        }
    }
    BackHandler(enabled = !keyDriven, onBack = onBack)
    val shape = RoundedCornerShape(16.dp)
    val logoSize = if (compact) 36.dp else 52.dp
    Column(
        modifier = modifier
            .claimsBottomLeft()
            // ▲ Both (the full player): the key works wherever focus is on the prompt.
            .onPreviewKeyEvent { event ->
                val both = onBoth ?: return@onPreviewKeyEvent false
                if (event.nativeKeyEvent.keyCode != android.view.KeyEvent.KEYCODE_DPAD_UP) return@onPreviewKeyEvent false
                if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN && event.nativeKeyEvent.repeatCount == 0) both()
                true
            }
            .widthIn(max = if (compact) 440.dp else 560.dp)
            .clip(shape)
            // V2 (#134): the toast card's raised surface, in the toasts' corner (live.19 QA F11).
            .background(SportsLiveTokens.Colors.SurfaceRaised.copy(alpha = 0.96f))
            .border(1.dp, SportsLiveTokens.Colors.Outline, shape)
            .padding(if (compact) 16.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)
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
                text = stringResource(if (noStreamYet) R.string.sports_live_alert_no_stream_yet else R.string.sports_live_alert_kicking_off),
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
                style = (if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall).sportsLiveBold(),
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
                text = details.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = ArtColors.Secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!noStreamYet) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (streamReady) SportsLiveReadyPill()
                Text(
                    text = stringResource(if (streamReady) R.string.sports_live_kickoff_stream_ready else R.string.sports_live_kickoff_waiting_stream),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (streamReady) ArtColors.Text else ArtColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (keyChoice != null) {
                SportsLiveDrawnButton(
                    text = stringResource(if (noStreamYet) R.string.sports_live_alert_remind_in_10 else R.string.sports_live_watch),
                    primary = true,
                    focused = keyChoice == PromptChoice.WATCH,
                    icon = Icons.Rounded.PlayArrow.takeIf { !noStreamYet }
                )
                SportsLiveDrawnButton(
                    text = stringResource(if (noStreamYet) R.string.sports_live_alert_dismiss else R.string.sports_live_alert_later),
                    primary = false,
                    focused = keyChoice == PromptChoice.LATER
                )
            } else if (noStreamYet) {
                SportsLivePaneButton(onClick = onLater, modifier = Modifier.focusRequester(watchFocus), primary = true) {
                    Text(stringResource(R.string.sports_live_alert_remind_in_10), maxLines = 1)
                }
                SportsLivePaneButton(onClick = onBack) {
                    Text(stringResource(R.string.sports_live_alert_dismiss), maxLines = 1)
                }
            } else {
                SportsLivePaneButton(onClick = onWatch, modifier = Modifier.focusRequester(watchFocus), primary = true) {
                    Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.sports_live_watch), maxLines = 1)
                }
                if (onBoth != null) {
                    SportsLivePaneButton(onClick = onBoth) {
                        Text(stringResource(R.string.sports_live_alert_both_button), maxLines = 1)
                    }
                }
                SportsLivePaneButton(onClick = onLater) {
                    Text(stringResource(R.string.sports_live_alert_later), maxLines = 1)
                }
            }
            Text(
                text = stringResource(R.string.sports_live_alert_closes, secondsLeft),
                style = MaterialTheme.typography.bodySmall,
                color = ArtColors.Muted,
                maxLines = 1
            )
        }
        // Every prompt names its keys (research 4): the no-stream note's too.
        if (keyDriven) {
            Text(
                text = if (noStreamYet) {
                    stringResource(R.string.sports_live_alert_no_stream_hint)
                } else {
                    listOfNotNull(
                        stringResource(R.string.sports_live_alert_prompt_hint),
                        stringResource(R.string.sports_live_alert_hint_both).takeIf { onBoth != null }
                    ).joinToString(HINT_SEPARATOR)
                },
                style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag),
                color = SportsLiveTokens.Alert.Hint,
                maxLines = 1
            )
        }
    }
}

/**
 * A toast card (#136): slim, slightly see-through, sliding in from the left as the toasts do (live.20). A grouped
 * card shows a dot for each of its alerts; [hint] ("OK Watch · ▼ Peek") only while the card takes those keys.
 */
@Composable
private fun AlertCardView(
    cardId: Long,
    content: AlertContent,
    followed: Boolean,
    hint: String?,
    index: Int,
    count: Int,
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
            if (count > 1 || hint != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (count > 1) {
                        Row(horizontalArrangement = Arrangement.spacedBy(SportsLiveTokens.Alert.Dot)) {
                            repeat(count) { at ->
                                Box(
                                    modifier = Modifier
                                        .size(SportsLiveTokens.Alert.Dot)
                                        .clip(RoundedCornerShape(50))
                                        .background(if (at == index) SportsLiveTokens.Alert.DotOn else SportsLiveTokens.Alert.DotOff)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    hint?.let { SmallLine(it, SportsLiveTokens.Alert.Hint) }
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
        // Over a movie or show ([KickoffStyle.TOAST]): no actions, so nothing to press by accident.
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

/** A toast card and a peek check their time this often. */
private const val CARD_TICK_MS = 250L

/** The peek's state machine is stepped this often. */
private const val PEEK_TICK_MS = 500L

private const val HINT_SEPARATOR = " · "

/** The prompt closes by itself after this long, as Later (the design's 15 s). */
private const val PROMPT_MS = 15_000L

/** A kickoff prompt with no live stream yet (#162): Remind me in 10 min and Dismiss, no Watch. */
private fun AlertCard.noStreamYet(): Boolean = (first as? SportsLiveAlert.Kickoff)?.noStreamYet == true
