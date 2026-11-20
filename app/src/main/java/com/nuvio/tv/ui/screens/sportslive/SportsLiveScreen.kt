package com.nuvio.tv.ui.screens.sportslive

import android.os.SystemClock
import kotlinx.coroutines.withTimeoutOrNull
import com.nuvio.tv.core.sportslive.SportsLiveToastKind
import com.nuvio.tv.core.sportslive.SportsLiveToasts
import com.nuvio.tv.core.sportslive.scores.FollowTarget
import com.nuvio.tv.core.sportslive.scores.FollowTargets
import com.nuvio.tv.core.sportslive.scores.GameSection
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.core.sportslive.GameStatus
import com.nuvio.tv.core.sportslive.GameStatusText
import androidx.compose.foundation.background
import com.nuvio.tv.core.sportslive.displayName
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.nuvio.tv.ui.screens.ambient.LocalAmbientIdleController
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.SportsLiveEndedGames
import com.nuvio.tv.core.sportslive.SportsLiveGameReadiness
import com.nuvio.tv.core.sportslive.SportsLiveReadyMark
import com.nuvio.tv.core.sportslive.alerts.GameReminders
import com.nuvio.tv.core.sportslive.alerts.RemindersState
import com.nuvio.tv.core.sportslive.scores.GameSituations
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.TeamLogoBook
import com.nuvio.tv.core.sportslive.scores.LiveScoresRepository
import com.nuvio.tv.core.sportslive.scores.MySport
import com.nuvio.tv.core.sportslive.scores.ResultRow
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreDetails
import com.nuvio.tv.core.sportslive.scores.ScoreDetailsBuilder
import com.nuvio.tv.core.sportslive.scores.ScoreSide
import com.nuvio.tv.core.sportslive.scores.CatchUpItem
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.TeamFollows
import com.nuvio.tv.core.sportslive.scores.TeamPages
import com.nuvio.tv.core.sportslive.scores.TeamRef
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.SpoilerProtection
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.screens.sportslive.dual.SportsLiveDualSession
import com.nuvio.tv.ui.screens.sportslive.dual.SportsLiveDualSessions
import com.nuvio.tv.ui.screens.sportslive.dual.sportsLiveSavedForLater
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniGeometry
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniDock
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniFocus
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniPlacement
import com.nuvio.tv.ui.screens.sportslive.mini.rememberSportsLiveMiniDock
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniPlayers
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniSpot
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniState
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniStop
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveNowPlayingCard
import com.nuvio.tv.ui.screens.sportslive.dual.SportsLiveDualPick
import com.nuvio.tv.ui.screens.sportslive.dual.SportsLiveDualPickerDialog
import com.nuvio.tv.ui.screens.sportslive.preview.SportsLivePreviewPlayer
import com.nuvio.tv.ui.screens.sportslive.preview.SportsLivePreviewState
import com.nuvio.tv.ui.screens.sportslive.preview.SportsLivePreviewVideo
import com.nuvio.tv.ui.screens.sportslive.preview.previewKey
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.dpadRepeatThrottle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SportsLiveScreen(
    viewModel: SportsLiveViewModel = hiltViewModel(),
    playViewModel: SportsLivePlayViewModel = hiltViewModel(),
    resultsViewModel: SportsLiveResultsViewModel = hiltViewModel(),
    scoresViewModel: SportsLiveScoresViewModel = hiltViewModel(),
    remindersViewModel: SportsLiveRemindersViewModel = hiltViewModel(),
    catchUpViewModel: SportsLiveCatchUpViewModel = hiltViewModel(),
    /** Opens the item's Stream screen; the Boolean: the user picks there (no autoplay). */
    onPlay: (SportsLiveItem, Boolean) -> Unit,
    onPlayTarget: (SportsLiveZapTarget) -> Unit,
    onOpenDetail: (SportsLiveItem) -> Unit,
    onOpenAddons: () -> Unit,
    /** Opens Settings → Sports & Live → My sports (Results' "Edit my sports"). */
    onOpenMySports: () -> Unit = {},
    /** Opens dual screen on the session just put in [SportsLiveDualSessions]. */
    onOpenDual: () -> Unit,
    /** Opens the Game page on the request just put in [SportsLiveGameRequests]. */
    onOpenGame: () -> Unit = {},
    /** Opens the Team page (#62) on the request just put in [SportsLiveTeamRequests]. */
    onOpenTeam: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // While Sports Live shows, the mini player stays docked in its stage on every tab (#157).
    val miniSection = remember { Any() }
    DisposableEffect(miniSection) {
        SportsLiveMiniPlacement.enterSection(miniSection)
        onDispose { SportsLiveMiniPlacement.leaveSection(miniSection) }
    }
    val followedTeams by viewModel.followedTeams.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val playState by playViewModel.state.collectAsStateWithLifecycle()
    val resultsState by resultsViewModel.uiState.collectAsStateWithLifecycle()
    val reminders by remindersViewModel.state.collectAsStateWithLifecycle()
    // The Catch up strip (#58) at the top of Live Now: worked out when the screen resumes, never on a timer.
    val catchUpItems by catchUpViewModel.items.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Live scores for the Live now and Upcoming cards on screen, polled only while the screen is
    // started and Scores is not Off. Followed teams' games come first in these lists.
    val scores by scoresViewModel.scores.collectAsStateWithLifecycle()
    val scoresMode by scoresViewModel.mode.collectAsStateWithLifecycle()
    val revealed by scoresViewModel.revealed.collectAsStateWithLifecycle()
    // Spoiler protection (#151): the scores come stamped with it; a kept Just ended final is stamped here.
    val protection by scoresViewModel.protection.collectAsStateWithLifecycle()
    val scoredTab = uiState.selectedTab == SportsLiveTab.LIVE_NOW || uiState.selectedTab == SportsLiveTab.UPCOMING
    TrackLiveScores(
        screenId = LiveScoresRepository.SCREEN_SECTION,
        // Live now tracks the whole tab (every filter), so each game's feed answers before it may be
        // estimated as ended (#60) and the tab count does not change with the filter.
        cards = when (uiState.selectedTab) {
            SportsLiveTab.LIVE_NOW -> uiState.tabs[SportsLiveTab.LIVE_NOW].orEmpty()
            SportsLiveTab.UPCOMING -> uiState.visibleItems
            else -> emptyList()
        },
        enabled = scoresMode != null && scoresMode != ScoresMode.OFF,
        tracking = scoresViewModel.tracking
    )
    // Worked out once per card for each scores update (or setting / reveal change), not per recomposition.
    val chipOf: (SportsLiveItem) -> ScoreChip? = remember(scores, scoresMode, revealed, scoredTab, uiState.justEnded, protection) {
        val cache = HashMap<SportsLiveItem, ScoreChip?>()
        val chip: (SportsLiveItem) -> ScoreChip? = { item ->
            if (!scoredTab) {
                null
            } else {
                cache.getOrPut(item) {
                    // A Just ended card keeps the final it was moved on, whatever is tracked now.
                    val snapshot = scores[item.streamKey] ?: uiState.endedOf(item)?.event?.let(protection::stamp)
                    ScoreChips.of(item, snapshot, scoresMode, item.streamKey in revealed)
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
    // The ESPN game behind a card, only for a strict match (as the chip needs): "Game stats" opens it.
    val gameOf: (SportsLiveItem) -> ScoreSnapshot? = remember(scores, scoredTab) {
        // An individual sport's match (a tennis score, #160) has no Game page.
        { item -> if (scoredTab) ScoreChips.matchedEvent(item, scores[item.streamKey])?.takeIf { it.individual == null } else null }
    }
    // The list's model (#71): the Scoreboard's rows and the drawn cards, built by the view model off the main thread
    // from the scores shown here; the list draws the board once it is ready.
    val board by viewModel.board.collectAsStateWithLifecycle()
    // The situation line follows the score: only while the card would show its live score.
    val situationStrings = rememberGameSituationStrings()
    val situationOf: (SportsLiveItem) -> String = remember(scores, scoresMode, revealed, scoredTab, situationStrings) {
        { item ->
            val event = scores[item.streamKey]
            val situation = if (scoredTab) GameSituations.of(item, event, scoresMode, item.streamKey in revealed) else null
            if (situation != null && event != null) GameSituations.situationLine(situation, event, situationStrings) else ""
        }
    }
    // The drawn cards come with the board (#71); a card it does not hold yet is drawn as before.
    val logoRevision by TeamLogoBook.shared.revision.collectAsState()
    val boardArts = board?.arts
    val artOf: (SportsLiveItem) -> GameArt? = remember(scores, logoRevision, boardArts) {
        val cache = HashMap<SportsLiveItem, GameArt?>()
        val art: (SportsLiveItem) -> GameArt? = { item ->
            if (boardArts != null && item.streamKey in boardArts) boardArts[item.streamKey]
            else cache.getOrPut(item) { GameArt.of(item, scores[item.streamKey]) }
        }
        art
    }

    // Ambient mode or the screensaver over the section (#71): no hover preview and no catalog refresh under it.
    val ambientController = LocalAmbientIdleController.current
    val ambientShowing by (ambientController?.isShowing ?: remember { MutableStateFlow(false) }).collectAsState()
    LaunchedEffect(ambientShowing) { viewModel.onAmbient(ambientShowing) }

    // Results loads only while its tab is selected and the screen is started (Today refreshes then, never
    // behind the player, the Game page or in the background); Scores Off hides the tab (and requests nothing).
    val screenLifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val screenStarted = screenLifecycle.isAtLeast(Lifecycle.State.STARTED)
    LaunchedEffect(uiState.selectedTab, screenStarted) {
        resultsViewModel.setActive(uiState.selectedTab == SportsLiveTab.RESULTS && screenStarted)
    }
    LaunchedEffect(resultsState.mode, uiState.selectedTab) {
        if (resultsState.modeKnown && resultsState.mode == ScoresMode.OFF && uiState.selectedTab == SportsLiveTab.RESULTS) {
            viewModel.selectTab(SportsLiveTab.LIVE_NOW)
        }
    }
    // Replays are what a result opens.
    LaunchedEffect(uiState.tabs) {
        resultsViewModel.setReplays(uiState.tabs[SportsLiveTab.REPLAYS].orEmpty())
    }
    // Replays' strict ESPN games (P4): Game stats and the Team pages in More…, never a score.
    LaunchedEffect(uiState.selectedTab, screenStarted) {
        resultsViewModel.setReplaysActive(uiState.selectedTab == SportsLiveTab.REPLAYS && screenStarted)
    }
    val replayMatches by resultsViewModel.replayMatches.collectAsStateWithLifecycle()
    // Your teams (#155): followed teams' games on Upcoming, for the strip's "later today" (a few rows at most).
    val upcomingFollowed = remember(uiState.tabs) { uiState.tabs[SportsLiveTab.UPCOMING].orEmpty().filter { it.followed } }
    val upcomingFollowedRows = remember(upcomingFollowed, scores, scoresMode, revealed, uiState.nowMs) {
        if (upcomingFollowed.isEmpty()) emptyList() else scoreboardRows(upcomingFollowed, SportsLiveTab.UPCOMING, scores, scoresMode, revealed, uiState.nowMs)
    }
    // The board's inputs from here (#71), with the Replays of games saved for later (#151) listed first.
    val replaysTabItems = uiState.tabs[SportsLiveTab.REPLAYS].orEmpty()
    LaunchedEffect(scores, scoresMode, revealed, protection, replayMatches, replaysTabItems) {
        val saved = protection.savedReplayKeys(replaysTabItems, replayMatches)
        viewModel.setBoardScores(SportsLiveBoardScores(scores, scoresMode, revealed, protection, saved))
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
                    catchUpViewModel.onOpened()
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

    // "Watch with another game" (#57): the picker ([dualPick]) for a game or a channel (F13); the pick starts
    // dual screen with the card in slot 1, with no lookup until the panes start.
    val canWatchWith: (SportsLiveItem) -> Boolean = { item -> uiState.offersWatchWith(item, scores[item.streamKey]) }
    var dualPick by remember { mutableStateOf<SportsLiveDualPick?>(null) }
    fun startDual(session: SportsLiveDualSession) {
        // Slot 1 keeps the stream its preview plays, with no lookup (#65); freed before dual starts.
        val handOff = viewModel.takeOverPreview(session.left)?.stream
        previewPlayer.release()
        SportsLiveDualSessions.start(if (handOff != null) session.copy(leftStream = handOff) else session)
        onOpenDual()
    }
    dualPick?.let { pick ->
        SportsLiveDualPickerDialog(
            candidates = pick.candidates,
            rows = emptyMap(),
            onPick = { second ->
                dualPick = null
                startDual(pick.session(second))
            },
            onDismiss = { dualPick = null }
        )
    }

    val showNoAddons = !uiState.hasClaimedCatalogs && !uiState.isLoading && uiState.lastRefreshedMs != null
    when {
        showNoAddons -> NoAddonsState(onOpenAddons)
        // Until the first refresh completes, "no claimed catalogs" may only mean addons are still resolving.
        // Skeleton rows where the list and the stage will be (#134), never a lone spinner.
        uiState.isLoading || !uiState.hasClaimedCatalogs -> SportsLiveLoadingSkeleton()
        else -> SportsLiveContent(
            uiState = uiState,
            followedTeams = followedTeams,
            board = board,
            situationOf = situationOf,
            chipOf = chipOf,
            artOf = artOf,
            detailsOf = detailsOf,
            gameOf = gameOf,
            onOpenGame = { item, event ->
                // A finished game carries its strict replay match (Watch replay), as from Results.
                val replay = event.takeIf(SportsLiveEndedGames::isFinalWithScore)?.let {
                    SportsLiveEndedGames.replayFor(it, uiState.tabs[SportsLiveTab.REPLAYS].orEmpty(), System.currentTimeMillis())
                }
                SportsLiveGameRequests.open(
                    SportsLiveGameRequest(
                        event,
                        replay = replay,
                        revealed = scoresMode == ScoresMode.SHOW || item.streamKey in revealed,
                        // The page names the teams in the card's order (N10).
                        homeFirst = sportsLiveGameHomeFirst(item, event),
                        card = item,
                        // A live game's page offers "Watch with another game" (#57), paired when pressed.
                        watchWith = if (event.state == ScoreState.IN && !item.isChannel && canWatchWith(item)) {
                            {
                                val scoresNow = scoresViewModel.scores.value
                                viewModel.uiState.value.watchWithSession(
                                    item, scoresNow, sportsLiveSavedForLater(scoresViewModel.protection.value, scoresNow)
                                )
                            }
                        } else {
                            null
                        }
                    )
                )
                onOpenGame()
            },
            onRejoin = { item, stream ->
                // Freed before the full player starts, so the two never hold decoders together.
                previewPlayer.release()
                playViewModel.rejoin(item, stream)
            },
            onCardFocus = scoresViewModel::onCardFocus,
            onSelectTab = viewModel::selectTab,
            onSelectFilter = viewModel::selectFilter,
            onUserMoved = viewModel::onUserMoved,
            onSelectItem = viewModel::selectItem,
            onRefresh = { viewModel.refresh() },
            onPlay = { item ->
                // A saved game's replay played to its end ends its protection (#151).
                if (item.kind == SportsLiveKind.REPLAY) scoresViewModel.onReplayStarted(listOf(item) + item.alternates, replayMatches[item.streamKey])
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
            dialogOpen = playState != SportsLivePlayState.Idle || dualPick != null,
            onPreviewFocus = viewModel::setPreviewFocus,
            onOpenDetail = onOpenDetail,
            onToggleFollow = { _, target -> viewModel.toggleFollow(target) },
            canWatchWith = canWatchWith,
            onWatchWith = { item ->
                // Always the picker (F13): the viewer sees and picks what goes beside the game or channel.
                val pick = uiState.watchWithFor(item, scores)
                if (pick != null) {
                    dualPick = pick
                } else {
                    SportsLiveToasts.show(SportsLiveToastKind.PROBLEM, context.getString(R.string.sports_live_dual_no_pair))
                }
            },
            results = resultsState,
            onSelectResultsDay = resultsViewModel::selectDay,
            onSelectResultsSport = resultsViewModel::selectSport,
            onExpandResultsGroup = resultsViewModel::expandGroup,
            onEditMySports = onOpenMySports,
            onResultFocus = resultsViewModel::onRowFocus,
            onOpenResult = { row, section ->
                // The Game page (#18), which carries the strict replay match (Watch replay) and Follow; the
                // stage's Game stats opens it on its Box score.
                SportsLiveGameRequests.open(resultsState.gameRequestFor(row).copy(section = section))
                onOpenGame()
            },
            onToggleResultFollow = resultsViewModel::toggleFollow,
            // Results' and Replays' Watch replay (P4): the replay plays at once under a clean title, never through
            // the addon's page or Stream screen (spoilers).
            onWatchReplay = { replay, title ->
                scoresViewModel.onReplayStarted(listOf(replay) + replay.alternates, replayMatches[replay.streamKey])
                previewPlayer.release()
                playViewModel.playReplay(listOf(replay) + replay.alternates, title)
            },
            replayMatches = replayMatches,
            onOpenReplayGame = { replay, event ->
                SportsLiveGameRequests.open(sportsLiveReplayGameRequest(event, replay))
                onOpenGame()
            },
            onOpenTeam = { ref ->
                SportsLiveTeamRequests.open(SportsLiveTeamRequest(ref))
                onOpenTeam()
            },
            reminders = reminders,
            onToggleReminder = remindersViewModel::toggle,
            onToggleTeamReminder = remindersViewModel::toggleTeam,
            catchUpItems = catchUpItems,
            upcomingFollowedRows = upcomingFollowedRows,
            scoresMode = scoresMode,
            onPlayCatchUp = { item ->
                // OK plays the replay at once: never the addon's page or Stream screen (spoilers).
                previewPlayer.release()
                scoresViewModel.onReplayStarted(item.replays, item.event)
                playViewModel.playReplay(item.replays, catchUpTitle(context, item))
            },
            onOpenCatchUpGame = { item ->
                SportsLiveGameRequests.open(catchUpGameRequest(item))
                onOpenGame()
            },
            onMarkCatchUpWatched = catchUpViewModel::markWatched,
            onOpenAddons = onOpenAddons,
            onHide = viewModel::hideFromLiveNow,
            protection = protection,
            onSaveForLater = scoresViewModel::saveForLater,
            onRevealResult = scoresViewModel::revealResult,
            onBrowsing = viewModel::setBrowsing,
            onShowNewRows = viewModel::showNewRows
        )
    }
}

@Composable
private fun NoAddonsState(onOpenAddons: () -> Unit) {
    // The one state card (#134), where the list would start: what is missing, and the way to fix it.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }
    SportsLiveStateCard(
        title = stringResource(R.string.sports_live_no_addons_title),
        line = stringResource(R.string.sports_live_no_addons_subtitle),
        primary = SportsLiveStateAction(stringResource(R.string.sports_live_open_addons), onOpenAddons),
        primaryModifier = Modifier.focusRequester(focusRequester),
        modifier = Modifier.padding(
            start = SportsLiveTokens.Size.SafeStart,
            top = SportsLiveTokens.Size.HeaderTop + SportsLiveTokens.Size.HeaderHeight + SportsLiveTokens.Size.HeaderBottomGap
        )
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SportsLiveContent(
    uiState: SportsLiveUiState,
    followedTeams: List<FollowedTeam>,
    /** The list's model, built off the main thread (#71); null or another tab's until ready. */
    board: SportsLiveBoard?,
    /** A game's situation line ("" when none or hidden with the score). */
    situationOf: (SportsLiveItem) -> String,
    /** A card's score chip (Live now / Upcoming only, spoiler setting applied). */
    chipOf: (SportsLiveItem) -> ScoreChip?,
    /** A game's drawn card; null keeps the addon's art. */
    artOf: (SportsLiveItem) -> GameArt?,
    /** A card's score block for the details pane (Live now / Upcoming only, spoiler setting applied). */
    detailsOf: (SportsLiveItem) -> ScoreDetails?,
    /** A card's strictly matched ESPN game (Live now / Upcoming only); null offers no "Game stats". */
    gameOf: (SportsLiveItem) -> ScoreSnapshot?,
    onOpenGame: (SportsLiveItem, ScoreSnapshot) -> Unit,
    /** OK on Live now's Resume chip (#148): the game's link again, at the live edge. */
    onRejoin: (SportsLiveItem, Stream) -> Unit,
    /** The focused card's key (null: none), for Hide until focused. */
    onCardFocus: (String?) -> Unit,
    onSelectTab: (SportsLiveTab) -> Unit,
    onSelectFilter: (String?) -> Unit,
    /** A key pressed on the section: an emptied selected chip falls back to All (#79); true when it did. */
    onUserMoved: () -> Boolean,
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
    /** More… → Follow ‹team› (#134): a side matched to a real team. */
    onToggleFollow: (SportsLiveItem, FollowTarget) -> Unit,
    /** "Watch with another game" is offered on a card (#57). */
    canWatchWith: (SportsLiveItem) -> Boolean,
    /** "Watch with another game": dual screen with the card in slot 1 and a game picked for it (#57). */
    onWatchWith: (SportsLiveItem) -> Unit,
    results: SportsLiveResultsUiState,
    onSelectResultsDay: (Int) -> Unit,
    onSelectResultsSport: (MySport?) -> Unit,
    onExpandResultsGroup: (String) -> Unit,
    onEditMySports: () -> Unit,
    onResultFocus: (String?) -> Unit,
    /** A result's Game page, on [GameSection] when given (the stage's Game stats: the Box score). */
    onOpenResult: (ResultRow, GameSection?) -> Unit,
    onToggleResultFollow: (ResultRow, ScoreSide) -> Unit,
    /** Watch replay (Results, Replays): [SportsLiveItem] plays at once as the clean title [String]. */
    onWatchReplay: (SportsLiveItem, String) -> Unit,
    /** Replays' strict ESPN games by the replay's stream key (P4). */
    replayMatches: Map<String, ScoreSnapshot>,
    /** Replays' More… → Game stats: the replay's game, spoiler-safe. */
    onOpenReplayGame: (SportsLiveItem, ScoreSnapshot) -> Unit,
    /** Hold OK → Team page ‹team› (#62) on a card or Results row with a strict ESPN match. */
    onOpenTeam: (TeamRef) -> Unit = {},
    /** Reminders (#44): the bell on upcoming games, and Remind me / Cancel reminder. */
    reminders: RemindersState = RemindersState(),
    onToggleReminder: (SportsLiveItem) -> Unit = {},
    /** "Remind me for every ‹team› game" in More… (#154). */
    onToggleTeamReminder: (FollowTarget) -> Unit = {},
    /** The catch-up replays (#58): the last part of Live Now's Your teams strip (#155). */
    catchUpItems: List<CatchUpItem>,
    /** Followed teams' games on Upcoming: Your teams' "later today" (#155). */
    upcomingFollowedRows: List<ScoreboardRow> = emptyList(),
    scoresMode: ScoresMode? = null,
    onPlayCatchUp: (CatchUpItem) -> Unit,
    onOpenCatchUpGame: (CatchUpItem) -> Unit,
    onMarkCatchUpWatched: (CatchUpItem) -> Unit,
    /** The empty card's "Sources" (#134): the addons screen. */
    onOpenAddons: () -> Unit = {},
    /** More… → Hide from Live Now (#134). */
    onHide: (SportsLiveItem) -> Unit = {},
    /** Spoiler protection (#151): More… offers Watch later (no spoilers), or Reveal result on a saved game. */
    protection: SpoilerProtection = SpoilerProtection(),
    onSaveForLater: (ScoreSnapshot) -> Unit = {},
    onRevealResult: (ScoreSnapshot) -> Unit = {},
    /** Live now's content holds focus (F6, TV QA BUG7): its rows, order and count hold meanwhile. */
    onBrowsing: (Boolean) -> Unit = {},
    /** "N new" on Refresh (TV QA Q5): the rows that waited while Live now was browsed. */
    onShowNewRows: () -> Unit = {}
) {
    val context = LocalContext.current
    val tabFocusRequesters = remember { SportsLiveTab.entries.associateWith { FocusRequester() } }
    val liveNowScope = rememberCoroutineScope()
    val activeTabFocusRequester = tabFocusRequesters.getValue(uiState.selectedTab)
    val watchFocusRequester = remember { FocusRequester() }
    // One requester per card, keyed like the LazyColumn: two addons can list the same meta id, and a
    // requester shared by two cards sends focus to whichever of them attached last.
    val cardFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    fun cardFocusRequester(item: SportsLiveItem) = cardFocusRequesters.getOrPut(item.listKey) { FocusRequester() }
    // A game listed twice on Upcoming (#137: in its day's Games to watch and in its time slot): its pick row has its own.
    fun pickFocusRequester(item: SportsLiveItem) = cardFocusRequesters.getOrPut("pick|${item.listKey}") { FocusRequester() }
    var listHasFocus by remember { mutableStateOf(false) }
    var paneHasFocus by remember { mutableStateOf(false) }
    // Saved with the back stack entry, so returning from the stream or detail page refocuses this card.
    var focusedCardKey by rememberSaveable { mutableStateOf<String?>(null) }
    // One scroll position per tab and filter: switching opens the list at the top, while coming back
    // from a game, Details or the player restores the position saved with the back stack entry.
    // The board for this tab and filter (#71): until it is built, the list waits (a frame or two), never
    // holding the main thread.
    val shownBoard = board?.takeIf { it.shows(uiState.selectedTab, uiState.selectedFilter) }
    val boardPending = shownBoard == null
    // V2 (#132): Live now and Upcoming are the Scoreboard + stage; Channels and Replays list cards.
    val scoreboard = shownBoard?.rows != null
    // V2 Upcoming (P3): the Agenda, whose list has a sticky day header above each day's games.
    val agenda = shownBoard?.agenda
    val filterKey = "${uiState.selectedTab.name}|${uiState.selectedFilter.orEmpty()}"
    val listState = rememberSaveable(filterKey, saver = LazyListState.Saver) { LazyListState() }
    val baseStateLabelOf = rememberSportsLiveStateLabeler(uiState.selectedTab)
    val liveLabel = stringResource(R.string.sports_live_live_pill)
    val finalLabel = stringResource(R.string.sports_live_chip_final_note)
    val probablyEndedLabel = stringResource(R.string.sports_live_probably_ended)
    // One status source (#102): the same answer as the Scoreboard and the player's panel.
    val statusOf: (SportsLiveItem) -> GameStatus = { item -> GameStatus.of(item, gameOf(item), uiState.endedOf(item), uiState.nowMs) }
    // ESPN says the game is over (C3): labelled final, never LIVE, even in the postgame grace.
    val espnFinalOf: (SportsLiveItem) -> Boolean = { item -> statusOf(item) is GameStatus.Final }
    // A Just ended card (#60) or an ESPN final: "Final" (left to the chip when it already says it) or "Probably ended".
    val stateLabelOf: (SportsLiveItem) -> String? = { item ->
        when (val status = statusOf(item)) {
            is GameStatus.Live -> liveLabel
            is GameStatus.JustEnded, is GameStatus.Final -> when {
                status is GameStatus.JustEnded && status.estimate -> probablyEndedLabel
                chipOf(item) is ScoreChip.Final -> null
                else -> finalLabel
            }
            GameStatus.Channel -> baseStateLabelOf(item)
            // Not started: an addon-live card ESPN still has as scheduled reads its kickoff, never LIVE (F5).
            is GameStatus.StartingSoon, is GameStatus.Upcoming, is GameStatus.Postponed -> baseStateLabelOf(item.copy(isLive = false))
        }
    }
    // Just ended cards with a strict replay match carry the Replay tag.
    val replaysTab = uiState.tabs[SportsLiveTab.REPLAYS].orEmpty()
    val replayKeys = remember(uiState.justEnded, replaysTab) {
        val nowMs = System.currentTimeMillis()
        uiState.justEnded.filterValues { ended ->
            ended.event?.let { SportsLiveEndedGames.replayFor(it, replaysTab, nowMs) } != null
        }.keys
    }

    // The Scoreboard's rows come ready from the view model (#71), grouped LIVE → STARTING SOON → CHANNELS → JUST ENDED.
    val boardRowOf = shownBoard?.rowOf.orEmpty()
    val items = shownBoard?.items.orEmpty()
    // The tab and filter the list was last opened on: until the change below resets [focusedCardKey], it names a row
    // of the list before.
    var listedFilterKey by rememberSaveable { mutableStateOf(filterKey) }
    // The mini player's game (playing, else the one last stopped), which the stage falls back to (F23).
    val stageMiniState by SportsLiveMiniPlayers.controller.state.collectAsStateWithLifecycle()
    val playingKey = (stageMiniState as? SportsLiveMiniState.Playing)?.item?.listKey
    var lastMiniKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(playingKey) { if (playingKey != null) lastMiniKey = playingKey }
    // F23: the focused row, else the mini player's game, else the first row (the first live game); never the view
    // model's selection alone, which a tab focus once reset to the addon's first item.
    val stageKey = sportsLiveStageKey(
        keys = items.map { it.listKey },
        focusedKey = focusedCardKey.takeIf { listedFilterKey == filterKey },
        miniKey = playingKey ?: lastMiniKey
    )
    val selected = items.firstOrNull { it.listKey == stageKey }
    // "▶ Ready" (live.20 1b) on my games' rows and stage: read once per change of the marks.
    val readyMarks by SportsLiveGameReadiness.shared.marks.collectAsStateWithLifecycle()
    val readyOf: (SportsLiveItem) -> Boolean = remember(readyMarks) {
        { item -> SportsLiveGameReadiness.shared.of(item) == SportsLiveReadyMark.READY }
    }
    val selectedCardFocusRequester = selected?.let(::cardFocusRequester)
    // Brings the card [key] (at [index] in [items]) on screen.
    suspend fun scrollToCard(@Suppress("UNUSED_PARAMETER") key: String, index: Int, onlyIfHidden: Boolean = true) {
        if (index < 0) return
        // The Agenda's list position counts its day headers too.
        val listIndex = agenda?.listIndexOf(index) ?: index
        if (!onlyIfHidden || listState.layoutInfo.visibleItemsInfo.none { it.index == listIndex }) listState.scrollToItem(listIndex)
    }
    // Where a move into the list lands: the focused card while on screen, else the first card on
    // screen. Read from the layout, so Down never aims at a card scrolled away (which traps focus).
    val currentItemKeys by rememberUpdatedState(items.map { it.listKey })
    val currentAgenda by rememberUpdatedState(agenda)
    val entryKey by remember(listState) {
        derivedStateOf {
            val positions = listState.layoutInfo.visibleItemsInfo.map { it.index }
            sportsLiveListEntryKey(
                keys = currentItemKeys,
                focusedKey = focusedCardKey,
                // The Agenda's positions include its day headers: read back as game indices.
                visibleIndices = currentAgenda?.let { agenda -> positions.mapNotNull(agenda::gameAt) } ?: positions
            )
        }
    }
    // Upcoming lists a big game twice (#137: its day's Games to watch and its time slot): the way in is the copy on
    // screen (the focused game's when shown, else the first game shown), by its own requester.
    val agendaEntryGame by remember(listState) {
        derivedStateOf {
            val shown = currentAgenda ?: return@derivedStateOf null
            val visible = listState.layoutInfo.visibleItemsInfo.mapNotNull { shown.gameAt(it.index) }
            visible.firstOrNull { shown.games.getOrNull(it)?.row?.item?.listKey == focusedCardKey } ?: visible.firstOrNull()
        }
    }
    val agendaEntryRequester = agendaEntryGame?.let { agenda?.games?.getOrNull(it) }?.let { game ->
        if (game.pick) pickFocusRequester(game.row.item) else cardFocusRequester(game.row.item)
    }
    val entryCardFocusRequester = agendaEntryRequester
        ?: items.firstOrNull { it.listKey == entryKey }?.let(::cardFocusRequester) ?: selectedCardFocusRequester
    // The Your teams strip (#155) sits between the header (and the Resume row) and Live Now's games: live now,
    // later today, then catch-up replays (#58). Up from the first game goes back to it.
    val liveNowShown = uiState.selectedTab == SportsLiveTab.LIVE_NOW
    val liveBoardRows = if (liveNowShown) shownBoard?.rows.orEmpty() else emptyList()
    val yourTeams = remember(liveNowShown, liveBoardRows, upcomingFollowedRows, catchUpItems, uiState.nowMs) {
        if (liveNowShown) YourTeams.strip(liveBoardRows, upcomingFollowedRows, catchUpItems, uiState.nowMs.takeIf { it > 0L } ?: System.currentTimeMillis()) else emptyList()
    }
    val catchUpShown = yourTeams.isNotEmpty()
    val catchUpFocusRequester = remember { FocusRequester() }
    val stripRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val stripRequesterOf: (String) -> FocusRequester = { key -> stripRequesters.getOrPut(key) { FocusRequester() } }
    // The strip card focus was last on (#155), cleared when a list row takes focus: a later-today or replay card
    // takes the stage over, a live card selects its list row; Left from the stage goes back to it.
    var stripStageKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(uiState.selectedTab) { stripStageKey = null }
    val stripStage = YourTeams.stageFor(yourTeams, stripStageKey)?.takeIf { it !is YourTeamsStage.ListRow }
    var catchUpMenuKey by remember { mutableStateOf<String?>(null) }
    catchUpItems.firstOrNull { it.key == catchUpMenuKey }?.let { item ->
        SportsLiveCatchUpMenu(
            item = item,
            onOpenGame = {
                catchUpMenuKey = null
                onOpenCatchUpGame(item)
            },
            onMarkWatched = {
                catchUpMenuKey = null
                onMarkCatchUpWatched(item)
            },
            onDismiss = { catchUpMenuKey = null },
            fromYourTeams = true
        )
    }
    // The strip held focus and has emptied (its last game watched): focus goes to the games, else the tab.
    var catchUpHoldsFocus by remember { mutableStateOf(false) }
    LaunchedEffect(catchUpShown) {
        if (catchUpShown || !catchUpHoldsFocus) return@LaunchedEffect
        catchUpHoldsFocus = false
        withFrameNanos { }
        runCatching { (entryCardFocusRequester ?: activeTabFocusRequester).requestFocus() }
    }
    // Back from a paused game (#148): Live now's and Channels' first row offers Resume while it is live, its final
    // once it ended (live.19 F15).
    val resumeOffer by SportsLiveMiniPlayers.controller.resume.collectAsStateWithLifecycle()
    val resumeListed = remember(resumeOffer, uiState.tabs) { sportsLiveResumeListed(resumeOffer, uiState.tabs) }
    val resumeChip = if (sportsLiveResumeShownOn(uiState.selectedTab)) {
        sportsLiveResumeChip(resumeOffer, resumeListed, statusOf, uiState.nowMs)
    } else {
        null
    }
    val statusStrings = rememberGameStatusStrings()
    val resumeLabel = when (resumeChip) {
        is SportsLiveResumeChip.Live -> stringResource(R.string.sports_live_resume_live, resumeChip.item.displayName)
        is SportsLiveResumeChip.Final -> stringResource(
            R.string.sports_live_resume_final,
            resumeChip.item.displayName,
            // The one status formatter: "Final 2–3", "FT 2–1", or "Score hidden" under Hide until focused.
            GameStatusText.of(resumeChip.status, chipOf(resumeChip.item), statusStrings, { _, _ -> "" })
        )
        null -> null
    }
    val resumeShown = resumeLabel != null
    val resumeFocusRequester = remember { FocusRequester() }
    // The row held focus and went away (expired, or its game left Live now): focus goes to the games.
    var resumeHoldsFocus by remember { mutableStateOf(false) }
    LaunchedEffect(resumeShown) {
        if (resumeShown || !resumeHoldsFocus) return@LaunchedEffect
        resumeHoldsFocus = false
        withFrameNanos { }
        runCatching { (if (catchUpShown) catchUpFocusRequester else entryCardFocusRequester ?: activeTabFocusRequester).requestFocus() }
    }
    // An empty tab (#134): Down lands on its card's first button.
    val emptyStateFocusRequester = remember { FocusRequester() }
    val belowResumeFocusRequester = if (catchUpShown) {
        catchUpFocusRequester
    } else {
        entryCardFocusRequester ?: emptyStateFocusRequester.takeIf { selected == null && uiState.selectedTab != SportsLiveTab.RESULTS }
    }
    // Down from the header (#155, live.20 1c): Resume, else the strip's first card (a followed team's live game comes
    // first; a replay may take it), else the list.
    val liveNowEntry = YourTeams.entry(resumeShown, yourTeams, listHasRows = entryCardFocusRequester != null)
    // Upcoming's day tabs (research 3): the row between the header and the Agenda, where Down from the header lands
    // (on the tab of the day the stage shows) and Up from the first game goes back to.
    val dayBarAgenda = agenda?.takeIf { uiState.selectedTab == SportsLiveTab.UPCOMING && selected != null && boardRowOf[selected.listKey] != null }
    val dayRequesters = remember { mutableMapOf<Int, FocusRequester>() }
    fun dayRequester(day: Int) = dayRequesters.getOrPut(day) { FocusRequester() }
    val shownKey = selected?.listKey
    val currentAgendaGame = dayBarAgenda?.slotGameOf { it.listKey == shownKey }
    val currentAgendaDay = currentAgendaGame?.let { game -> dayBarAgenda?.games?.getOrNull(game)?.day } ?: 0
    val dayBarFocusRequester = dayBarAgenda?.let { dayRequester(currentAgendaDay) }
    var dayBarHasFocus by remember { mutableStateOf(false) }
    BackHandler(enabled = dayBarHasFocus) {
        runCatching { activeTabFocusRequester.requestFocus() }
    }
    val contentEntryFocusRequester = when (liveNowEntry) {
        LiveNowEntry.RESUME -> resumeFocusRequester
        LiveNowEntry.STRIP -> catchUpFocusRequester
        LiveNowEntry.LIST -> dayBarFocusRequester ?: entryCardFocusRequester
        LiveNowEntry.EMPTY -> emptyStateFocusRequester.takeIf { selected == null && uiState.selectedTab != SportsLiveTab.RESULTS }
    }
    // Results' "N games live now →" (#170): once Live now is the composed tab and its rows are drawn, focus goes
    // to its list (the column rule), else to its tab.
    // Saved: the section's content can be composed again as Live now's board arrives, and the move must survive it.
    var focusLiveNowList by rememberSaveable { mutableStateOf(false) }
    // Read live: a filter change on arrival (Live now drops a sport filter) gives the list a new state.
    val currentListState by rememberUpdatedState(listState)
    LaunchedEffect(focusLiveNowList, uiState.selectedTab, boardPending) {
        if (!focusLiveNowList) return@LaunchedEffect
        if (uiState.selectedTab != SportsLiveTab.LIVE_NOW) {
            if (uiState.selectedTab != SportsLiveTab.RESULTS) focusLiveNowList = false
            return@LaunchedEffect
        }
        if (boardPending) return@LaunchedEffect
        // The Resume row when shown, else Your teams' first card (#155, live.20 1c), else the list's first row,
        // scrolled in.
        if (liveNowEntry == LiveNowEntry.RESUME || liveNowEntry == LiveNowEntry.STRIP || items.isEmpty()) {
            withFrameNanos { }
            focusLiveNowList = false
            runCatching { (contentEntryFocusRequester ?: activeTabFocusRequester).requestFocus() }
            return@LaunchedEffect
        }
        // The board can still be rebuilt in the first frames (games re-sorted, rows added above): the list then
        // keeps the focused row and scrolls with it. So the move follows the current first row for a moment, and
        // stops as soon as the user moves elsewhere. The flag (a key) is cleared last, so it never cancels this.
        var asked: String? = null
        val until = SystemClock.uptimeMillis() + LIVE_NOW_FOCUS_MS
        while (SystemClock.uptimeMillis() < until) {
            val first = currentItemKeys.firstOrNull()
            if (asked != null && focusedCardKey != asked && focusedCardKey != first) {
                focusLiveNowList = false
                return@LaunchedEffect
            }
            if (first != null && (focusedCardKey != first || !listHasFocus)) {
                withTimeoutOrNull(LIVE_NOW_SCROLL_MS) { currentListState.scrollToItem(0) }
                withFrameNanos { }
                asked = first
                runCatching { cardFocusRequesters[first]?.requestFocus() }
            }
            withFrameNanos { }
        }
        focusLiveNowList = false
    }
    // On the Results tab: the day in the title (P4), and where the header's Down lands in its content.
    val resultsDateFocusRequester = remember { FocusRequester() }
    val resultsTopFocusRequester = remember { FocusRequester() }
    val resultsStageFocusRequester = remember { FocusRequester() }
    val showingResults = uiState.selectedTab == SportsLiveTab.RESULTS
    // The tab's filter (V2): one focus stop in the header. Upcoming inline; Channels' regions, Replays' sports
    // and Results' sports as one dropdown each (P4).
    val resultsFilterShown = showingResults && results.status == ResultsStatus.READY && results.sports.isNotEmpty()
    // #146: while My sports hides games, All sports is offered (on Live now too, as its only option).
    val allSportsOffered = SportsLiveUiState.ALL_SPORTS_FILTER in uiState.filters
    val filterShown = if (showingResults) resultsFilterShown else sportsLiveHeaderFilterShown(uiState.selectedTab, uiState.filters.size, allSportsOffered)
    val filterFocusRequester = remember { FocusRequester() }
    // The columns (#170, [sportsLiveColumnTarget]): Up from the list column's top goes to the selected tab, on
    // every tab, never to the control above it on the right.
    val aboveContentFocusRequester = activeTabFocusRequester
    // The stage column's Up lands on the first header control over it; the controls' Down on the stage's primary.
    val controlsFocusRequester = remember { FocusRequester() }
    var resultsStageShown by remember { mutableStateOf(false) }
    val aboveListFocusRequester = when {
        catchUpShown -> catchUpFocusRequester
        resumeShown -> resumeFocusRequester
        else -> aboveContentFocusRequester
    }
    val aboveCatchUpFocusRequester = if (resumeShown) resumeFocusRequester else aboveContentFocusRequester
    @Composable
    fun ResumeRow(modifier: Modifier) {
        val chip = resumeChip ?: return
        SportsLiveResumeRow(
            label = resumeLabel ?: return,
            live = chip is SportsLiveResumeChip.Live,
            focusRequester = resumeFocusRequester,
            upFocusRequester = aboveContentFocusRequester,
            downFocusRequester = belowResumeFocusRequester,
            onOpen = {
                SportsLiveMiniPlayers.controller.dismissResume()
                when (val ok = sportsLiveResumeOk(chip)) {
                    is SportsLiveResumeOk.RejoinLiveEdge -> onRejoin(ok.item, ok.stream)
                    is SportsLiveResumeOk.OpenGame -> onOpenGame(ok.item, ok.event)
                }
            },
            onDismiss = SportsLiveMiniPlayers.controller::dismissResume,
            modifier = modifier.onFocusChanged {
                // Losing focus because the row is being removed keeps the flag (see above).
                if (it.hasFocus || SportsLiveMiniPlayers.controller.resume.value != null) resumeHoldsFocus = it.hasFocus
            }
        )
    }
    val myTeamsLabel = stringResource(R.string.sports_live_filter_my_teams_star)
    val allLabel = stringResource(
        when {
            // #146: All is My sports' games while it scopes the tab; All sports is its own option.
            uiState.mySportsScoped -> R.string.sports_live_filter_my_sports
            uiState.selectedTab == SportsLiveTab.CHANNELS -> R.string.sports_live_filter_all_regions
            uiState.selectedTab == SportsLiveTab.REPLAYS -> R.string.sports_live_filter_all_sports
            else -> R.string.sports_live_filter_all
        }
    )
    val allSportsLabel = stringResource(R.string.sports_live_filter_all_sports)
    // Short sport names (V2 P3): "Football", "Soccer"; a channel region by its code or name (P4).
    val otherRegionLabel = stringResource(R.string.sports_live_region_other)
    val filterLabels = uiState.filters.associateWith { value ->
        when {
            value == SportsLiveUiState.ALL_SPORTS_FILTER -> allSportsLabel
            uiState.selectedTab == SportsLiveTab.CHANNELS -> channelRegionLabel(value, otherRegionLabel)
            else -> sportFilterLabel(value)
        }
    }
    // Live now has no sport filter (V2): only My sports and All sports (#146).
    val headerFilters = if (uiState.selectedTab == SportsLiveTab.LIVE_NOW) uiState.filters.filter { it == SportsLiveUiState.ALL_SPORTS_FILTER } else uiState.filters
    val mySportsLabel = stringResource(R.string.sports_live_results_my_sports_star)
    val resultsSportLabels = results.sports.associateWith { mySportLabel(it) }
    val filterOptions = remember(filterShown, showingResults, headerFilters, myTeamsLabel, allLabel, filterLabels, results.sports, resultsSportLabels, mySportsLabel) {
        when {
            !filterShown -> null
            showingResults -> listOf(SportsLiveFilterOption(null, mySportsLabel, gold = true)) +
                results.sports.map { SportsLiveFilterOption(it.id, resultsSportLabels[it] ?: it.id) }
            else -> sportsLiveFilterOptions(headerFilters, myTeamsLabel, allLabel) { filterLabels[it] ?: it }
        }
    }
    val editMySportsLabel = stringResource(R.string.sports_live_results_edit_my_sports)
    val filterDropdown = when (uiState.selectedTab) {
        SportsLiveTab.LIVE_NOW -> SportsLiveHeaderDropdown(stringResource(R.string.sports_live_filter_title_sport))
        // F16: Upcoming's sports as one dropdown too; the inline strip clipped its last option ("G…").
        SportsLiveTab.UPCOMING -> SportsLiveHeaderDropdown(stringResource(R.string.sports_live_filter_title_sport))
        SportsLiveTab.CHANNELS -> SportsLiveHeaderDropdown(stringResource(R.string.sports_live_filter_title_region))
        SportsLiveTab.REPLAYS -> SportsLiveHeaderDropdown(stringResource(R.string.sports_live_filter_title_sport))
        // "Edit my sports" closes the menu (F7: no chip runs under it any more).
        SportsLiveTab.RESULTS -> SportsLiveHeaderDropdown(stringResource(R.string.sports_live_filter_title_sport), listOf(editMySportsLabel to onEditMySports))
        else -> null
    }
    // Results' day in the title: OK on ‹ older, on › newer (#153).
    val resultsDay = results.dates.firstOrNull { it.daysAgo == results.daysAgo }
    val resultsDate = if (showingResults && resultsDay != null) {
        SportsLiveHeaderDate(
            label = resultsDayText(resultsDay),
            canOlder = resultsDayStep(results.dates, results.daysAgo, newer = false) != null,
            canNewer = resultsDayStep(results.dates, results.daysAgo, newer = true) != null,
            onStep = { newer -> resultsDayStep(results.dates, results.daysAgo, newer)?.let(onSelectResultsDay) }
        )
    } else {
        null
    }
    // Live now has no sport filter (V2): a filter left from earlier falls back to All; All sports stays (#146).
    LaunchedEffect(uiState.selectedTab, uiState.selectedFilter) {
        val filter = uiState.selectedFilter
        if (uiState.selectedTab == SportsLiveTab.LIVE_NOW && filter != null && filter != SportsLiveUiState.ALL_SPORTS_FILTER) onSelectFilter(null)
    }
    // #170: a one-off miss says nothing; a source failing on shows a dot on Refresh, whose Hold OK opens the sheet.
    val sourcesNowMs = uiState.nowMs.takeIf { it > 0L } ?: System.currentTimeMillis()
    val sourceBadge = uiState.sources.badge(sourcesNowMs)
    // A refresh the user asked for (Refresh, Retry): when a flagged source still fails, a toast says so (#170).
    var refreshedFrom by remember { mutableStateOf<SportsLiveSources?>(null) }
    val userRefresh: () -> Unit = {
        refreshedFrom = uiState.sources
        onRefresh()
    }
    LaunchedEffect(uiState.sources) {
        val before = refreshedFrom ?: return@LaunchedEffect
        if (before == uiState.sources) return@LaunchedEffect
        refreshedFrom = null
        uiState.sources.stillFailing(before)?.let { source ->
            SportsLiveToasts.show(
                SportsLiveToastKind.INFO,
                context.getString(R.string.sports_live_source_still_failing, source.name),
                context.getString(R.string.sports_live_source_still_failing_hint)
            )
        }
    }
    var failuresOpen by remember { mutableStateOf(false) }
    if (failuresOpen && sourceBadge != SportsLiveSourceBadge.NONE) {
        SportsLiveSourcesSheet(
            flagged = uiState.sources.flagged(),
            onRetry = userRefresh,
            onOpenSources = onOpenAddons,
            onDismiss = { failuresOpen = false }
        )
    }
    // Scores Off: no Results tab at all.
    // Shown only once the setting is read, so a Scores-Off user never sees it flash in.
    val shownTabs = SportsLiveTab.entries.filter { it != SportsLiveTab.RESULTS || (results.modeKnown && results.mode != ScoresMode.OFF) }
    // Replay "series" items are team folders; the detail page lists their videos.
    val open: (SportsLiveItem) -> Unit = { item ->
        if (item.kind == SportsLiveKind.REPLAY && item.apiType == "series") onOpenDetail(item) else onPlay(item)
    }
    // OK on a card: an ESPN-ended game in Just ended opens its Game page; every other card (an estimate
    // too: its stream may still work) opens as usual. Hold OK keeps Choose stream.
    val openCard: (SportsLiveItem) -> Unit = { item ->
        when (val ok = sportsLiveCardOk(uiState.endedOf(item))) {
            is SportsLiveCardOk.OpenGame -> onOpenGame(item, ok.event)
            SportsLiveCardOk.Play -> open(item)
        }
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
            scrollToCard(target.listKey, targetIndex)
            repeat(2) { withFrameNanos { } }
            runCatching { cardFocusRequester(target).requestFocus() }
        }
        // Cleared last: clearing restarts this effect, which would cancel the focus request above.
        if (gone) followedCardKey = null
    }
    // OK on a card (list card or Scoreboard row).
    fun cardClick(item: SportsLiveItem) = openCard(item)
    // Results' Watch replay names the player after ESPN's matchup, never the addon's title.
    fun resultsReplayTitle(row: ResultRow): String =
        context.getString(R.string.sports_live_results_menu_title, row.event.away.catchUpName, row.event.home.catchUpName)
    // "Remind me" (#44) on a game not started yet; the bell on its card while reminded.
    fun remindable(item: SportsLiveItem) = GameReminders.canRemind(item, uiState.nowMs)
    fun remindedCard(item: SportsLiveItem) = remindable(item) && reminders.isReminded(item)
    // Hold OK on a row opens the same menu as More… (V2): Details is always in it.
    fun cardLongClick(item: SportsLiveItem) {
        menuCardKey = item.listKey
    }
    fun cardFocused(item: SportsLiveItem) {
        stripStageKey = null
        onCardFocus(item.streamKey)
        if (item.listKey != followedCardKey) followedCardKey = null
        focusedCardKey = item.listKey
        onSelectItem(item.id)
    }
    // A later-today strip card (#155) opens the same menu as its Upcoming row.
    val stripRowOf: (String) -> ScoreboardRow? = { key ->
        yourTeams.firstNotNullOfOrNull { entry -> (entry as? YourTeamsEntry.LaterToday)?.row?.takeIf { it.item.listKey == key } }
    }
    (items.firstOrNull { it.listKey == menuCardKey } ?: menuCardKey?.let(stripRowOf)?.item)?.let { item ->
        // Replays (P4): Game stats and the Team pages from the replay's strict ESPN game; no Follow there or on
        // Channels, whose More… is Watch with another game, Choose stream and Details.
        val replayGame = if (uiState.selectedTab == SportsLiveTab.REPLAYS) replayMatches[item.streamKey] else null
        val game = gameOf(item) ?: replayGame
        val followSides = uiState.selectedTab != SportsLiveTab.REPLAYS && !item.isChannel
        // Follow ‹team› only for sides matched to a real team (#134): the strict ESPN game's, else the team
        // directory's, named as ESPN names them; never a side read from the raw title.
        val targets = remember(item, game, uiState.selectedTab) {
            if (!followSides) emptyList() else FollowTargets.of(item, gameOf(item), homeFirst = gameOf(item)?.let { sportsLiveGameHomeFirst(item, it) } ?: false)
        }
        val entries = sportsLiveMoreEntries(
            watchWith = canWatchWith(item),
            chooseStream = sportsLiveAutoPlayEligible(item),
            strictMatch = game != null,
            sides = targets.size,
            remindable = remindable(item),
            hide = sportsLiveOffersHide(uiState.selectedTab, item, strictMatch = game != null, followable = targets.isNotEmpty())
        )
        val watchWith = SportsLiveMoreEntry.WATCH_WITH in entries
        val canRemind = SportsLiveMoreEntry.REMIND in entries
        val heading = rememberMoreHeading(item, boardRowOf[item.listKey] ?: stripRowOf(item.listKey))
        // #154: per matched side, hidden for a followed team while "Remind me for my teams" is On.
        val teamReminderTargets = targets.filter { GameReminders.offersTeamReminder(reminders, followedTeams, it.event, it.side) }
        // Gone from the list (hidden), or followed under ★ My teams: focus goes to the card in its place.
        fun refocusAfter() {
            followedCardKey = item.listKey
            followedCardIndex = items.indexOf(item).coerceAtLeast(0)
        }
        SportsLiveCardMenu(
            title = heading.title,
            status = heading.status,
            statusLive = heading.live,
            sides = targets.map { it.label to it.isFollowed(followedTeams) },
            onWatchWith = if (watchWith) {
                {
                    menuCardKey = null
                    onWatchWith(item)
                }
            } else {
                null
            },
            onChooseStream = if (SportsLiveMoreEntry.CHOOSE_STREAM in entries) {
                {
                    menuCardKey = null
                    onChooseStream(item)
                }
            } else {
                null
            },
            gamePreview = game?.state == ScoreState.PRE,
            onGameStats = game?.takeIf { SportsLiveMoreEntry.GAME_STATS in entries }?.let { event ->
                {
                    menuCardKey = null
                    if (replayGame != null) onOpenReplayGame(item, event) else onOpenGame(item, event)
                }
            },
            onToggleFollow = { index ->
                menuCardKey = null
                refocusAfter()
                targets.getOrNull(index)?.let { onToggleFollow(item, it) }
            },
            onDetails = {
                menuCardKey = null
                onOpenDetail(item)
            },
            onDismiss = { menuCardKey = null },
            onToggleReminder = if (canRemind) {
                {
                    menuCardKey = null
                    onToggleReminder(item)
                }
            } else {
                null
            },
            reminded = canRemind && reminders.isReminded(item),
            teamReminders = teamReminderTargets.map { it.label to GameReminders.isTeamReminded(reminders, it.event, it.side) },
            onToggleTeamReminder = { index ->
                menuCardKey = null
                teamReminderTargets.getOrNull(index)?.let(onToggleTeamReminder)
            },
            // Watch later (no spoilers) (#151): a game with its strict ESPN match, on any tab but Channels.
            onWatchLater = game?.takeIf { !item.isChannel }?.let { event ->
                {
                    menuCardKey = null
                    if (protection.isSaved(event.eventId)) {
                        onRevealResult(event)
                    } else {
                        onSaveForLater(event)
                        SportsLiveToasts.show(
                            SportsLiveToastKind.INFO,
                            context.getString(R.string.sports_live_watch_later_toast, heading.title),
                            context.getString(R.string.sports_live_watch_later_toast_line)
                        )
                    }
                }
            },
            savedForLater = game != null && protection.isSaved(game.eventId),
            // Team page ‹team› (Decision 6): from the strict ESPN game, else (no game listed yet) from the team
            // directory, for pro leagues with ESPN's team id.
            teamPages = (
                replayGame?.let { event -> TeamPages.refsOf(event, homeFirst = false) }
                    ?: gameOf(item)?.let { event -> TeamPages.refsOf(event, sportsLiveGameHomeFirst(item, event)) }
                    ?: TeamPages.refsForCard(item)
                ).map { ref ->
                    (ref.team.shortDisplayName ?: ref.name) to {
                        menuCardKey = null
                        onOpenTeam(ref)
                    }
                },
            onHide = if (SportsLiveMoreEntry.HIDE in entries) {
                {
                    menuCardKey = null
                    refocusAfter()
                    onHide(item)
                    // Where to undo it: the one place hidden titles are listed.
                    SportsLiveToasts.show(
                        SportsLiveToastKind.INFO,
                        context.getString(R.string.sports_live_hidden_toast, heading.title),
                        context.getString(R.string.sports_live_hidden_toast_line)
                    )
                }
            } else {
                null
            }
        )
    }

    @Composable
    fun YourTeamsStrip(stageShown: Boolean, modifier: Modifier) {
        SportsLiveYourTeamsStrip(
            entries = yourTeams,
            requesterOf = stripRequesterOf,
            firstFocusRequester = catchUpFocusRequester,
            upFocusRequester = aboveCatchUpFocusRequester,
            downFocusRequester = entryCardFocusRequester,
            stageFocusRequester = watchFocusRequester.takeIf { stageShown },
            selectedKey = stripStageKey?.takeIf { paneHasFocus },
            scoresMode = scoresMode,
            reminded = { item -> reminders.isReminded(item) },
            onFocus = { entry ->
                if (entry is YourTeamsEntry.Live) cardFocused(entry.row.item) else onCardFocus(null)
                stripStageKey = entry.key
            },
            onClick = { entry ->
                when (entry) {
                    is YourTeamsEntry.Live -> cardClick(entry.row.item)
                    // OK on a later-today card is Remind me (#155), the bell showing the state; once "▶ Ready" it is
                    // Watch now: the held verified link plays at once (live.20 1b HARD RULE).
                    is YourTeamsEntry.LaterToday ->
                        if (YourTeams.okPlays(entry, com.nuvio.tv.core.sportslive.SportsLiveGameReadiness.shared.of(entry.row.item))) {
                            cardClick(entry.row.item)
                        } else {
                            onToggleReminder(entry.row.item)
                        }
                    is YourTeamsEntry.Replay -> onPlayCatchUp(entry.item)
                }
            },
            onLongClick = { entry ->
                when (entry) {
                    is YourTeamsEntry.Live -> cardLongClick(entry.row.item)
                    is YourTeamsEntry.LaterToday -> cardLongClick(entry.row.item)
                    is YourTeamsEntry.Replay -> catchUpMenuKey = entry.item.key
                }
            },
            menuOpen = catchUpMenuKey != null || menuCardKey != null,
            onHoldsFocus = { catchUpHoldsFocus = it },
            modifier = modifier.onFocusChanged { if (it.hasFocus) followedCardKey = null }
        )
    }
    BackHandler(enabled = listHasFocus) {
        runCatching { activeTabFocusRequester.requestFocus() }
    }
    // F6, TV QA BUG7 / Q5: once the user moves into Live now's content (the list, the strip, the Resume row, the stage)
    // nothing moves under them, on the tabs too (no row leaves, none re-orders, new ones wait behind "N new" on
    // Refresh); Refresh, another tab or leaving the screen lets it go. Before that (the first loads) rows land freely.
    val currentOnBrowsing by rememberUpdatedState(onBrowsing)
    val contentHasFocus = listHasFocus || paneHasFocus || catchUpHoldsFocus || resumeHoldsFocus
    LaunchedEffect(contentHasFocus) { if (contentHasFocus) currentOnBrowsing(true) }
    // The preview follows the focused card (in the list, or its pane's buttons) while no menu or
    // dialog is open; anything else stops it.
    // A live Your teams card previews its game too; a later-today or replay stage has no preview (#155).
    val previewItem = selected?.takeIf {
        (listHasFocus || paneHasFocus || catchUpHoldsFocus) && stripStage == null && menuCardKey == null && !dialogOpen
    }
    val currentOnPreviewFocus by rememberUpdatedState(onPreviewFocus)
    LaunchedEffect(previewItem?.listKey) { currentOnPreviewFocus(previewItem) }
    DisposableEffect(Unit) { onDispose { currentOnPreviewFocus(null) } }

    // Back from another screen: put the list back on the card that had focus, once its board is ready (it
    // may be built again on return). Live Now opens at its top so the LIVE group shows (F3): the card keeps
    // focus while it sits in the first screenful, else the first row takes it.
    var returnHandled by remember { mutableStateOf(false) }
    LaunchedEffect(boardPending) {
        if (boardPending || returnHandled) return@LaunchedEffect
        returnHandled = true
        val key = focusedCardKey ?: return@LaunchedEffect
        val liveNow = uiState.selectedTab == SportsLiveTab.LIVE_NOW && scoreboard
        val target = if (liveNow) sportsLiveReturnFocusKey(items.map { it.listKey }, key, RETURN_ROWS_ON_SCREEN) else key
        val index = items.indexOfFirst { it.listKey == target }
        if (target == null || index < 0) return@LaunchedEffect
        if (liveNow) listState.scrollToItem(0) else scrollToCard(target, index, onlyIfHidden = false)
        if (target != key) {
            focusedCardKey = target
            onSelectItem(items[index].id)
        }
        repeat(2) { withFrameNanos { } }
        // Back from the player the card gets focus, not the stage's Watch it was played from (N3): asked
        // again for a few frames until the list holds it.
        repeat(RETURN_FOCUS_FRAMES) {
            runCatching { cardFocusRequesters[target]?.requestFocus() }
            withFrameNanos { }
            if (listHasFocus) return@LaunchedEffect
        }
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
        if (key in itemKeys) {
            // Moved (into the Just ended strip, #60): keep the focused card in view.
            val index = itemKeys.indexOf(key)
            if (listFocusedAtChange && shownKeys.indexOf(key) != index) {
                scrollToCard(key, index)
                repeat(2) { withFrameNanos { } }
                runCatching { cardFocusRequesters[key]?.requestFocus() }
            }
            return@LaunchedEffect
        }
        val nextKey = sportsLiveKeyAfterRemoval(shownKeys, itemKeys, key)
        val next = items.firstOrNull { it.listKey == nextKey }
        focusedCardKey = nextKey
        onSelectItem(next?.id)
        if (!listFocusedAtChange) return@LaunchedEffect
        if (next == null) {
            runCatching { activeTabFocusRequester.requestFocus() }
            return@LaunchedEffect
        }
        scrollToCard(next.listKey, items.indexOf(next))
        repeat(2) { withFrameNanos { } }
        runCatching { cardFocusRequester(next).requestFocus() }
    }

    // Rows that arrive while the list has no focus (a slower addon's games) land above the first row shown:
    // the list keeps its top, so the LIVE group stays in view instead of anchoring on the old first row.
    LaunchedEffect(itemKeys) {
        if (scoreboard && !listHasFocus && !paneHasFocus && focusedCardKey == null && listState.firstVisibleItemIndex > 0) {
            listState.scrollToItem(0)
        }
    }

    // Only a real tab or filter change scrolls to the top; the saved value survives navigation.
    LaunchedEffect(filterKey) {
        if (filterKey == listedFilterKey) return@LaunchedEffect
        listedFilterKey = filterKey
        // A stopped mini player's game is the stage's fallback only on the list it was stopped on (F23).
        lastMiniKey = null
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

    val currentSelectedFilterEmpty by rememberUpdatedState(uiState.selectedFilterEmpty)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SportsLiveTokens.Colors.Background)
            // #79: a selected filter whose games have gone stays shown until the user moves; the first key
            // falls back to All (the filter control keeps focus).
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_DOWN && currentSelectedFilterEmpty) onUserMoved()
                false
            }
            .padding(
                start = SportsLiveTokens.Size.SafeStart,
                // live.20 (the user's TV photos): the tab labels sit on the 5 % safe line, not below it.
                top = SportsLiveTokens.Size.HeaderTop,
                end = SportsLiveTokens.Size.SafeEnd,
                bottom = SportsLiveTokens.Size.SafeBottom
            )
    ) {
        val openList: () -> Unit = { runCatching { entryCardFocusRequester?.requestFocus() } }
        val miniPlayer = SportsLiveMiniPlayers.controller
        val miniState by miniPlayer.state.collectAsStateWithLifecycle()
        val nowPlaying = (miniState as? SportsLiveMiniState.Playing)?.item
        // The stage's header with the mini player docked (#157): Selected, or merged on its own game, where Watch expands it.
        val stageHeader = sportsLiveStageHeader(nowPlaying?.listKey, selected?.listKey)
        val stageWatch: () -> Unit = {
            if (stageHeader == SportsLiveStageHeader.MERGED) miniPlayer.requestExpand() else selected?.let(open)
        }
        val miniDock = rememberSportsLiveMiniDock()
        val miniDocked = nowPlaying != null && miniDock is SportsLiveMiniDock.Docked
        // #170: Up from the docked mini player goes to the header controls over the stage.
        SideEffect { SportsLiveMiniFocus.up = controlsFocusRequester.takeIf { miniDocked } }
        DisposableEffect(Unit) {
            onDispose {
                SportsLiveMiniFocus.up = null
                SportsLiveMiniFocus.back = null
            }
        }
        // A card that goes away (idle timeout, profile switch) while focused hands focus to the active tab.
        var nowPlayingHasFocus by remember { mutableStateOf(false) }
        LaunchedEffect(nowPlaying == null) {
            if (nowPlaying != null || !nowPlayingHasFocus) return@LaunchedEffect
            nowPlayingHasFocus = false
            withFrameNanos { }
            runCatching { activeTabFocusRequester.requestFocus() }
        }
        // On the Scoreboard the Live Now / Channels stage shows (V2).
        val stageShown = scoreboard && !showingResults && uiState.selectedTab != SportsLiveTab.REPLAYS && selected != null && boardRowOf[selected.listKey] != null
        // The stage column is drawn (#170): Live now, Channels, Upcoming and Replays with a selected row.
        val stageColumnShown = !showingResults && selected != null && boardRowOf[selected.listKey] != null && (scoreboard || agenda != null)
        // F23: Back on the docked mini player stops it and gives focus back to the stage's Watch it was reached from.
        SideEffect { SportsLiveMiniFocus.back = watchFocusRequester.takeIf { miniDocked && stageColumnShown } }
        // Docked in a stage (every tab's, #157) the mini player needs no card; only in the corner (a tab with no
        // stage, after the hold) does it show, so a tab switch never flashes it.
        if (nowPlaying != null && miniDock == SportsLiveMiniDock.Corner) {
            SportsLiveNowPlayingCard(
                name = nowPlaying.displayName,
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
        SportsLiveHeader(
            tabs = shownTabs,
            selectedTab = uiState.selectedTab,
            tabFocusRequesters = tabFocusRequesters,
            downFocusRequester = if (showingResults) resultsTopFocusRequester else contentEntryFocusRequester,
            onSelectTab = { tab ->
                // TV QA BUG8 / Q12: focus on the tab row shows the list's own first game (or the mini player's), never
                // the strip card or row focused last, whose stage (a Remind me) was one stray OK from a toggle.
                stripStageKey = null
                if (tab == uiState.selectedTab) focusedCardKey = null
                onSelectTab(tab)
            },
            onOpenTab = openList,
            filterOptions = filterOptions,
            filterFocusRequester = filterFocusRequester,
            selectedFilter = if (showingResults) results.selectedSport?.id else uiState.selectedFilter,
            onSelectFilter = if (showingResults) {
                { id -> onSelectResultsSport(MySport.entries.firstOrNull { it.id == id }) }
            } else {
                onSelectFilter
            },
            filterDropdown = filterDropdown,
            date = resultsDate,
            dateFocusRequester = resultsDateFocusRequester,
            sourceBadge = sourceBadge,
            controlsFocusRequester = controlsFocusRequester,
            controlsDownFocusRequester = when {
                showingResults -> resultsStageFocusRequester.takeIf { resultsStageShown }
                stageColumnShown -> watchFocusRequester
                else -> null
            },
            onShowFailures = { failuresOpen = true },
            // "N new" (Q5): OK shows the rows that waited, at once (no reload); otherwise Refresh as before.
            newRows = if (uiState.selectedTab == SportsLiveTab.LIVE_NOW) uiState.newLiveRows else 0,
            onRefresh = if (uiState.selectedTab == SportsLiveTab.LIVE_NOW && uiState.newLiveRows > 0) {
                onShowNewRows
            } else {
                {
                    // A refresh asked for shows its result as it lands, until the user moves into the list again.
                    onBrowsing(false)
                    userRefresh()
                }
            },
            modifier = Modifier.onFocusChanged { if (it.hasFocus) followedCardKey = null },
            updatedAtMs = uiState.staleListAtMs.takeIf { !showingResults }
        )
        if (showingResults) {
            SportsLiveResultsView(
                state = results,
                tabFocusRequester = activeTabFocusRequester,
                headerFocusRequester = aboveContentFocusRequester,
                topFocusRequester = resultsTopFocusRequester,
                controlsFocusRequester = controlsFocusRequester,
                stageFocusRequester = resultsStageFocusRequester,
                onStageShown = { resultsStageShown = it },
                onSelectDay = onSelectResultsDay,
                // Today's "N games live now →": the Live Now tab, focus on its list once drawn (#170's columns).
                onOpenLiveNow = {
                    // Never focus the Live Now tab straight away: until the header recomposes, the tab row's
                    // onEnter still sends focus to the Results tab, which selects Results again (the line then
                    // seemed to do nothing). The move waits for the new tab, below.
                    onSelectTab(SportsLiveTab.LIVE_NOW)
                    // No card to return to: the list's first row takes focus, not the card it last had.
                    focusedCardKey = null
                    focusLiveNowList = true
                },
                onExpandGroup = onExpandResultsGroup,
                onRowFocus = onResultFocus,
                onOpen = { row -> onOpenResult(row, null) },
                onGameStats = { row -> onOpenResult(row, GameSection.BOX_SCORE) },
                onWatchReplay = { row, replay ->
                    onWatchReplay(replay, resultsReplayTitle(row))
                },
                isFollowed = { row, side ->
                    TeamFollows.followersOf(followedTeams, row.event, if (side == ScoreSide.HOME) row.event.home else row.event.away).isNotEmpty()
                },
                onToggleFollow = onToggleResultFollow,
                onOpenTeam = onOpenTeam,
                liveGames = uiState.liveGameCount,
                onEditMySports = onEditMySports,
                onSaveForLater = onSaveForLater,
                onRevealResult = onRevealResult,
                modifier = Modifier.padding(top = SportsLiveTokens.Size.HeaderBottomGap)
            )
            return@Column
        }
        // With the stage shown the row and the strip sit at the top of the list's column (the stage keeps its place).
        if (!stageShown) ResumeRow(Modifier.padding(top = NuvioTheme.spacing.sm))
        if (catchUpShown && !stageShown) {
            YourTeamsStrip(stageShown = false, modifier = Modifier.padding(top = NuvioTheme.spacing.sm))
        }
        Spacer(modifier = Modifier.height(SportsLiveTokens.Size.HeaderBottomGap))

        // The board is being built (#71): nothing to draw for a moment, and never "nothing here".
        if (boardPending && uiState.visibleItems.isNotEmpty()) return@Column
        if (selected == null || selectedCardFocusRequester == null) {
            // One empty card (#134) that always offers the next step.
            SportsLiveEmptyCard(
                empty = sportsLiveEmptyState(uiState, uiState.nowMs.takeIf { it > 0L } ?: System.currentTimeMillis()),
                nowMs = uiState.nowMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
                reminders = reminders,
                onSelectTab = onSelectTab,
                onShowAll = {
                    onSelectFilter(if (uiState.selectedFilter == null && uiState.mySportsScoped) SportsLiveUiState.ALL_SPORTS_FILTER else null)
                },
                onRetry = onRefresh,
                onOpenAddons = onOpenAddons,
                onToggleReminder = onToggleReminder,
                primaryModifier = Modifier
                    .focusRequester(emptyStateFocusRequester)
                    .focusProperties { up = aboveListFocusRequester }
            )
            return@Column
        }

        val selectedPreview = preview?.takeIf { it.item.listKey == selected.listKey }
        val selectedRow = boardRowOf[selected.listKey]
        val agendaRow = selectedRow
        if (uiState.selectedTab == SportsLiveTab.REPLAYS && agenda != null && selectedRow != null) {
            // V2 Replays (P4, #90): by day under sticky headers, spoiler-free rows, and the fixed stage.
            // A strict ESPN game names the teams as ESPN does, with its logos and league (never its score).
            val replayRowOf: (ScoreboardRow) -> ScoreboardRow = { row ->
                replayMatches[row.item.streamKey]?.let { event -> row.copy(art = GameArt.ofEvent(event, records = false), twoSided = true) } ?: row
            }
            val replayTitleOf: (ScoreboardRow) -> String = { row -> agendaStageTitleText(replayRowOf(row), context) }
            fun replayJump(target: Int) {
                val item = agenda.games.getOrNull(target)?.row?.item ?: return
                liveNowScope.launch {
                    val listIndex = agenda.listIndexOf(target)
                    if (listState.layoutInfo.visibleItemsInfo.none { it.index == listIndex }) listState.scrollToItem(listIndex)
                    withFrameNanos { }
                    runCatching { cardFocusRequester(item).requestFocus() }
                }
            }
            val watchReplay: (SportsLiveItem) -> Unit = { item ->
                // A team folder lists its videos on the detail page; a replay plays at once, spoiler-safe.
                if (item.apiType == "series") onOpenDetail(item) else onWatchReplay(item, replayTitleOf(boardRowOf[item.listKey] ?: selectedRow))
            }
            Row(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .width(SportsLiveTokens.Size.ListWidth + LIST_FOCUS_ROOM * 2)
                        .offset(x = -LIST_FOCUS_ROOM)
                        .fillMaxHeight()
                ) {
                    key(filterKey) {
                        SportsLiveKeepFromEdge(margin = LIST_EDGE_KEEP) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .sportsLiveFadingEdges(listState, LIST_FADE, top = false)
                                    .onFocusChanged {
                                        listHasFocus = it.hasFocus
                                        if (!it.hasFocus) onCardFocus(null)
                                    }
                                    .focusRestorer(entryCardFocusRequester ?: selectedCardFocusRequester)
                                    .dpadRepeatThrottle(),
                                contentPadding = PaddingValues(horizontal = LIST_FOCUS_ROOM, vertical = LIST_FOCUS_ROOM),
                                verticalArrangement = Arrangement.spacedBy(SportsLiveTokens.Size.RowGap)
                            ) {
                                agenda.entries.forEach { entry ->
                                    when (entry) {
                                        is AgendaEntry.Day -> stickyHeader(key = "day_${entry.day}") {
                                            SportsLiveAgendaDayHeader(agenda.days[entry.day])
                                        }
                                        is AgendaEntry.Game -> {
                                            val gameIndex = entry.game
                                            val row = replayRowOf(agenda.games[gameIndex].row)
                                            val item = row.item
                                            item(key = item.listKey) {
                                                SportsLiveScoreboardRow(
                                                    row = row,
                                                    selected = paneHasFocus && item.listKey == selected.listKey,
                                                    onClick = { watchReplay(item) },
                                                    onLongClick = { cardLongClick(item) },
                                                    onFocus = { cardFocused(item) },
                                                    menuOpen = item.listKey == menuCardKey,
                                                    agenda = AgendaRowSide(bell = AgendaBell.NONE, trailing = replayTimeText(item)),
                                                    modifier = Modifier
                                                        .focusRequester(cardFocusRequester(item))
                                                        .focusProperties {
                                                            right = watchFocusRequester
                                                            if (gameIndex == 0) up = aboveListFocusRequester
                                                        }
                                                        .onPreviewKeyEvent { event ->
                                                            if (event.nativeKeyEvent.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                                                            // CH+ / CH− a day, as on Upcoming; ← → are left alone (→ the stage).
                                                            val key = agendaKeyOf(event.nativeKeyEvent.keyCode)
                                                                ?.takeIf { it == AgendaKey.DAY_BACK || it == AgendaKey.DAY_FORWARD } ?: return@onPreviewKeyEvent false
                                                            agenda.target(gameIndex, key)?.let(::replayJump)
                                                            true
                                                        }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.width(SportsLiveTokens.Size.StageGap - LIST_FOCUS_ROOM * 2))
                val stageRow = replayRowOf(selectedRow)
                SportsLiveReplayStage(
                    row = stageRow,
                    title = replayTitleOf(selectedRow),
                    nowMs = uiState.nowMs,
                    onWatch = { watchReplay(selected) },
                    onMore = { menuCardKey = selected.listKey },
                    modifier = Modifier
                        .padding(top = LIST_FOCUS_ROOM)
                        .onFocusChanged { paneHasFocus = it.hasFocus }
                        .sportsLiveStageColumn(controlsFocusRequester, SportsLiveMiniFocus.requester.takeIf { miniDocked }),
                    watchModifier = Modifier
                        .focusRequester(watchFocusRequester)
                        .focusProperties {
                            left = entryCardFocusRequester ?: selectedCardFocusRequester
                            // Up reaches the docked mini player (#157), whose Back stops it.
                            if (miniDocked) up = SportsLiveMiniFocus.requester
                        },
                    miniPlaying = nowPlaying != null
                )
            }
            return@Column
        }
        if (agenda != null && agendaRow != null) {
            // V2 Upcoming (#132, P3): the Agenda (sticky day headers, time-slot headers) and the fixed 360 dp stage.
            // OK on a row does the stage's primary: Remind me while the start is over 15 min away, else Watch.
            fun agendaOk(item: SportsLiveItem, row: ScoreboardRow) {
                if (upcomingStageButtons(item, agendaStartOf(row), uiState.nowMs).first() == UpcomingStageButton.REMIND) {
                    onToggleReminder(item)
                } else {
                    cardClick(item)
                }
            }
            // ← → a time slot, CH+ / CH− a day: focus moves to that slot's or day's first game, scrolled in first.
            fun agendaJump(target: Int) {
                val game = agenda.games.getOrNull(target) ?: return
                val item = game.row.item
                liveNowScope.launch {
                    val listIndex = agenda.listIndexOf(target)
                    if (listState.layoutInfo.visibleItemsInfo.none { it.index == listIndex }) listState.scrollToItem(listIndex)
                    withFrameNanos { }
                    runCatching { (if (game.pick) pickFocusRequester(item) else cardFocusRequester(item)).requestFocus() }
                }
            }
            // The stage shows game [target] (a day tab or Earlier / Later, research 3): its row scrolled in, focus kept.
            fun agendaShow(target: Int) {
                val item = agenda.games.getOrNull(target)?.row?.item ?: return
                focusedCardKey = item.listKey
                onSelectItem(item.id)
                liveNowScope.launch {
                    val listIndex = agenda.listIndexOf(target)
                    // A day opens under its own header; a slot only scrolls when its game is off screen.
                    if (agenda.firstGameOf(agenda.games[target].day) == target) {
                        listState.scrollToItem(listIndex - 1)
                    } else if (listState.layoutInfo.visibleItemsInfo.none { it.index == listIndex }) {
                        listState.scrollToItem(listIndex)
                    }
                }
            }
            Row(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .width(SportsLiveTokens.Size.ListWidth + LIST_FOCUS_ROOM * 2)
                        .offset(x = -LIST_FOCUS_ROOM)
                        .fillMaxHeight()
                ) {
                    SportsLiveAgendaDayBar(
                        days = agenda.days,
                        currentDay = currentAgendaDay,
                        canEarlier = currentAgendaGame?.let { agenda.timeSlotJump(it, forward = false) } != null,
                        canLater = currentAgendaGame?.let { agenda.timeSlotJump(it, forward = true) } != null,
                        dayFocusRequester = ::dayRequester,
                        upFocusRequester = aboveContentFocusRequester,
                        downFocusRequester = entryCardFocusRequester,
                        // Selection follows focus (TV tab rows); the day already shown keeps its game in view.
                        onDayFocused = { day -> if (day != currentAgendaDay) agenda.firstGameOf(day)?.let(::agendaShow) },
                        onDayOk = { day ->
                            val target = if (day == currentAgendaDay) currentAgendaGame else agenda.firstGameOf(day)
                            target?.let(::agendaJump)
                        },
                        onStep = { later -> currentAgendaGame?.let { agenda.timeSlotJump(it, forward = later) }?.let(::agendaShow) },
                        modifier = Modifier.onFocusChanged { dayBarHasFocus = it.hasFocus }
                    )
                    key(filterKey) {
                        SportsLiveKeepFromEdge(margin = LIST_EDGE_KEEP) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .sportsLiveFadingEdges(listState, LIST_FADE, top = false)
                                    .onFocusChanged {
                                        listHasFocus = it.hasFocus
                                        if (!it.hasFocus) onCardFocus(null)
                                    }
                                    .focusRestorer(entryCardFocusRequester ?: selectedCardFocusRequester)
                                    .dpadRepeatThrottle(),
                                contentPadding = PaddingValues(horizontal = LIST_FOCUS_ROOM, vertical = LIST_FOCUS_ROOM),
                                verticalArrangement = Arrangement.spacedBy(SportsLiveTokens.Size.RowGap)
                            ) {
                                agenda.entries.forEach { entry ->
                                    when (entry) {
                                        is AgendaEntry.Day -> stickyHeader(key = "day_${entry.day}") {
                                            SportsLiveAgendaDayHeader(agenda.days[entry.day])
                                        }
                                        is AgendaEntry.Game -> {
                                            val gameIndex = entry.game
                                            val game = agenda.games[gameIndex]
                                            val item = game.row.item
                                            item(key = if (game.pick) "pick|${item.listKey}" else item.listKey) {
                                                Column {
                                                    if (agenda.opensSlot(gameIndex)) {
                                                        SportsLiveAgendaSlotHeader(agenda.slots[game.slot], uiState.nowMs, day = agenda.days.getOrNull(game.day))
                                                    }
                                                    SportsLiveScoreboardRow(
                                                        row = game.row,
                                                        // The row the stage shows keeps its bar while focus is on the stage or the day tabs.
                                                        selected = (paneHasFocus || dayBarHasFocus) && item.listKey == selected.listKey,
                                                        onClick = { agendaOk(item, game.row) },
                                                        onLongClick = { cardLongClick(item) },
                                                        onFocus = { cardFocused(item) },
                                                        menuOpen = item.listKey == menuCardKey,
                                                        agenda = AgendaRowSide(
                                                            bell = agendaBellOf(remindable(item), reminders.isReminded(item)),
                                                            bigGame = !game.pick && game.row.bigGame != null
                                                        ),
                                                        watching = item.listKey == nowPlaying?.listKey,
                                                        ready = readyOf(item),
                                                        modifier = Modifier
                                                            .focusRequester(if (game.pick) pickFocusRequester(item) else cardFocusRequester(item))
                                                            .focusProperties {
                                                                // → enters the stage from any row (#134), as on Live Now; Hold OK opens More….
                                                                right = watchFocusRequester
                                                                // ↑ from the first game: the day tabs (research 3).
                                                                if (gameIndex == 0) up = dayBarFocusRequester ?: aboveListFocusRequester
                                                            }
                                                            .onPreviewKeyEvent { event ->
                                                                if (event.nativeKeyEvent.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                                                                val key = agendaKeyOf(event.nativeKeyEvent.keyCode) ?: return@onPreviewKeyEvent false
                                                                val target = agenda.target(gameIndex, key)
                                                                if (target != null) agendaJump(target)
                                                                // A day or slot key at either end does nothing (media keys never move focus).
                                                                true
                                                            }
                                                    )
                                                    // A Games to watch row (#137): why, under it.
                                                    if (game.pick) game.row.bigGame?.let { SportsLiveAgendaReasonLine(it) }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.width(SportsLiveTokens.Size.StageGap - LIST_FOCUS_ROOM * 2))
                SportsLiveUpcomingStage(
                    row = agendaRow,
                    nowMs = uiState.nowMs,
                    reminded = remindedCard(selected),
                    preview = if (selectedPreview != null) {
                        { SportsLivePreviewVideo(previewPlayer) }
                    } else {
                        null
                    },
                    previewShowing = selectedPreview?.rendering == true,
                    miniInStage = nowPlaying != null,
                    noStream = noStreamKey == selected.listKey,
                    ready = readyOf(selected),
                    onRemind = { onToggleReminder(selected) },
                    onWatch = stageWatch,
                    onMore = { menuCardKey = selected.listKey },
                    modifier = Modifier
                        .padding(top = LIST_FOCUS_ROOM)
                        .onFocusChanged { paneHasFocus = it.hasFocus }
                        .sportsLiveStageColumn(controlsFocusRequester, SportsLiveMiniFocus.requester.takeIf { miniDocked }),
                    primaryModifier = Modifier
                        .focusRequester(watchFocusRequester)
                        .focusProperties {
                            left = entryCardFocusRequester ?: selectedCardFocusRequester
                            // Up reaches the docked mini player (#157), whose Back stops it.
                            if (miniDocked) up = SportsLiveMiniFocus.requester
                        },
                    header = stageHeader
                )
            }
            return@Column
        }

        if (scoreboard && selectedRow != null) {
            // V2 Live Now (#132): the Scoreboard list (400 dp, faded edges) and the fixed 360 dp stage.
            Row(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .width(SportsLiveTokens.Size.ListWidth + LIST_FOCUS_ROOM * 2)
                        .offset(x = -LIST_FOCUS_ROOM)
                        .fillMaxHeight()
                ) {
                    ResumeRow(Modifier.padding(start = LIST_FOCUS_ROOM, end = LIST_FOCUS_ROOM, bottom = NuvioTheme.spacing.sm))
                    if (catchUpShown) {
                        // The strip keeps its own room for the focus ring (BUG10): its cards line up with the rows.
                        YourTeamsStrip(stageShown = true, modifier = Modifier.padding(start = LIST_FOCUS_ROOM - YOUR_TEAMS_FOCUS_ROOM, bottom = NuvioTheme.spacing.xs))
                    }
                    val groupCounts = remember(shownBoard) { shownBoard?.rows.orEmpty().groupingBy { it.group }.eachCount() }
                    // Channels (P4): one list, under a header per region while more than one region shows.
                    val channelsTab = uiState.selectedTab == SportsLiveTab.CHANNELS
                    val sectionCounts = remember(shownBoard) { shownBoard?.rows.orEmpty().mapNotNull { it.section }.groupingBy { it }.eachCount() }
                    // Recreated per tab and filter, so its focus restorer never brings back another list's card.
                    key(filterKey) {
                        SportsLiveKeepFromEdge(margin = LIST_EDGE_KEEP) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .sportsLiveFadingEdges(listState, LIST_FADE)
                                    .onFocusChanged {
                                        listHasFocus = it.hasFocus
                                        if (!it.hasFocus) onCardFocus(null)
                                    }
                                    .focusRestorer(entryCardFocusRequester ?: selectedCardFocusRequester)
                                    .dpadRepeatThrottle(),
                                contentPadding = PaddingValues(horizontal = LIST_FOCUS_ROOM, vertical = LIST_FOCUS_ROOM),
                                verticalArrangement = Arrangement.spacedBy(SportsLiveTokens.Size.RowGap)
                            ) {
                                itemsIndexed(items, key = { _, item -> item.listKey }) { index, item ->
                                    val boardRow = boardRowOf[item.listKey] ?: return@itemsIndexed
                                    // The group's heading rides on its first row, so list indices stay the rows' own.
                                    Column {
                                        if (channelsTab) {
                                            val section = boardRow.section
                                            if (section != null && sectionCounts.size > 1 && (index == 0 || boardRowOf[items[index - 1].listKey]?.section != section)) {
                                                SportsLiveSectionHeader(channelRegionLabel(section, otherRegionLabel), sectionCounts[section] ?: 0)
                                            }
                                        } else if (index == 0 || boardRowOf[items[index - 1].listKey]?.group != boardRow.group) {
                                            SportsLiveScoreboardHeader(boardRow.group, groupCounts[boardRow.group] ?: 0)
                                        }
                                        SportsLiveScoreboardRow(
                                            row = boardRow,
                                            // Focus in the stage: the selected row keeps a white left bar (not while it shows a strip card).
                                            selected = paneHasFocus && stripStageKey == null && item.listKey == selected.listKey,
                                            onClick = { cardClick(item) },
                                            onLongClick = { cardLongClick(item) },
                                            onFocus = { cardFocused(item) },
                                            menuOpen = item.listKey == menuCardKey,
                                            reminded = remindedCard(item),
                                            hasReplay = item.streamKey in replayKeys,
                                            watching = item.listKey == nowPlaying?.listKey,
                                            ready = readyOf(item),
                                            modifier = Modifier
                                                .focusRequester(cardFocusRequester(item))
                                                .focusProperties {
                                                    right = watchFocusRequester
                                                    if (index == 0) up = aboveListFocusRequester
                                                }
                                        )
                                    }
                                }
                                // #167: under My sports, the games it hides are one press away (OK: All sports).
                                if (uiState.hiddenByMySports > 0 && !channelsTab) {
                                    item(key = HIDDEN_BY_MY_SPORTS_KEY) {
                                        SportsLiveHiddenByMySportsLine(
                                            count = uiState.hiddenByMySports,
                                            onClick = { onSelectFilter(SportsLiveUiState.ALL_SPORTS_FILTER) },
                                            height = SportsLiveTokens.Size.PanelLine + 8.dp,
                                            modifier = Modifier.focusProperties { right = watchFocusRequester }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.width(SportsLiveTokens.Size.StageGap - LIST_FOCUS_ROOM * 2))
                // The stage follows the strip (#155): a later-today card shows Upcoming's stage, a replay Watch replay.
                val stripCardFocusRequester = stripStageKey?.takeIf { key -> yourTeams.any { it.key == key } }?.let(stripRequesterOf)
                val stripStageModifier = Modifier
                    .focusRequester(watchFocusRequester)
                    .focusProperties {
                        stripCardFocusRequester?.let { left = it }
                        if (miniDocked) up = SportsLiveMiniFocus.requester
                    }
                when (val stage = stripStage) {
                    is YourTeamsStage.Upcoming -> SportsLiveUpcomingStage(
                        row = stage.row,
                        nowMs = uiState.nowMs,
                        reminded = remindedCard(stage.row.item),
                        preview = null,
                        previewShowing = false,
                        miniInStage = nowPlaying != null,
                        noStream = false,
                        ready = readyOf(stage.row.item),
                        onRemind = { onToggleReminder(stage.row.item) },
                        onWatch = { open(stage.row.item) },
                        onMore = { menuCardKey = stage.row.item.listKey },
                        modifier = Modifier
                            .padding(top = LIST_FOCUS_ROOM)
                            .onFocusChanged { paneHasFocus = it.hasFocus }
                            .sportsLiveStageColumn(controlsFocusRequester, SportsLiveMiniFocus.requester.takeIf { miniDocked }),
                        primaryModifier = stripStageModifier,
                        header = sportsLiveStageHeader(nowPlaying?.listKey, stage.row.item.listKey)
                    )
                    is YourTeamsStage.Replay -> SportsLiveReplayStage(
                        row = YourTeams.replayStageRow(stage.item),
                        title = catchUpMatchup(stage.item),
                        nowMs = uiState.nowMs,
                        onWatch = { onPlayCatchUp(stage.item) },
                        onMore = { catchUpMenuKey = stage.item.key },
                        modifier = Modifier
                            .padding(top = LIST_FOCUS_ROOM)
                            .onFocusChanged { paneHasFocus = it.hasFocus }
                            .sportsLiveStageColumn(controlsFocusRequester, SportsLiveMiniFocus.requester.takeIf { miniDocked }),
                        watchModifier = stripStageModifier,
                        miniPlaying = nowPlaying != null,
                        whenText = catchUpReplayWhen(stage.item, uiState.nowMs)
                    )
                    else -> SportsLiveStage(
                        row = selectedRow,
                        details = detailsOf(selected),
                        situation = situationOf(selected),
                        preview = if (selectedPreview != null) {
                            { SportsLivePreviewVideo(previewPlayer) }
                        } else {
                            null
                        },
                        previewShowing = selectedPreview?.rendering == true,
                        miniInStage = nowPlaying != null,
                        noStream = noStreamKey == selected.listKey,
                        onWatch = stageWatch,
                        onMore = { menuCardKey = selected.listKey },
                        facts = sportsLiveStageFacts(selectedRow, readyOf(selected), uiState.nowMs),
                        modifier = Modifier
                            .padding(top = LIST_FOCUS_ROOM)
                            .onFocusChanged { paneHasFocus = it.hasFocus }
                            .sportsLiveStageColumn(controlsFocusRequester, SportsLiveMiniFocus.requester.takeIf { miniDocked }),
                        watchModifier = Modifier
                            .focusRequester(watchFocusRequester)
                            .focusProperties {
                                left = stripCardFocusRequester ?: entryCardFocusRequester ?: selectedCardFocusRequester
                                // Up reaches the docked mini player (#157), whose Back stops it.
                                if (miniDocked) up = SportsLiveMiniFocus.requester
                            },
                        header = stageHeader
                    )
                }
            }
            return@Column
        }
    }
}

private const val FOLLOW_REFOCUS_WINDOW_MS = 1_500L
private const val RETURN_FOCUS_FRAMES = 10

/** How long Results' "N games live now" keeps focus on Live now's first row while its board settles. */
private const val LIVE_NOW_FOCUS_MS = 3_000L
private const val LIVE_NOW_SCROLL_MS = 300L

/** On return to Live Now the card that had focus keeps it while among this many first rows (F3). */
private const val RETURN_ROWS_ON_SCREEN = 4

/** The Your teams strip's own room round its cards ([SportsLiveYourTeamsStrip]'s FOCUS_ROOM). */
private val YOUR_TEAMS_FOCUS_ROOM = 4.dp

/** Room round the list's rows for the focus ring and lift, so neither is clipped. */
private val LIST_FOCUS_ROOM = 8.dp

/** The list's scroll edges fade over 80 px (V2) instead of a hard cut. */
private val LIST_FADE = 40.dp

/** Focus keeps about two rows from the list's top and bottom edges (V2). */
private val LIST_EDGE_KEEP = 100.dp

/**
 * The Agenda's move for a remote [keyCode]: ⏪ ⏩ (rewind / fast-forward, previous / next) a time slot, CH+ (up) /
 * CH− (down) a day, as CH zaps in the player. ← → are left to focus (#134): → enters the stage from any row.
 */
internal fun agendaKeyOf(keyCode: Int): AgendaKey? = when (keyCode) {
    AndroidKeyEvent.KEYCODE_MEDIA_REWIND, AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS -> AgendaKey.SLOT_BACK
    AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD, AndroidKeyEvent.KEYCODE_MEDIA_NEXT -> AgendaKey.SLOT_FORWARD
    AndroidKeyEvent.KEYCODE_CHANNEL_UP, AndroidKeyEvent.KEYCODE_PAGE_UP -> AgendaKey.DAY_BACK
    AndroidKeyEvent.KEYCODE_CHANNEL_DOWN, AndroidKeyEvent.KEYCODE_PAGE_DOWN -> AgendaKey.DAY_FORWARD
    else -> null
}

/** Unique within a tab: the bucketer keeps one copy of an item per addon. */
private val SportsLiveItem.listKey: String get() = "$addonId|$id"
