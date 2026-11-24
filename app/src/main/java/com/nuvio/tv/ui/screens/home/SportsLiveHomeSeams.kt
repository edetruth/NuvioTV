package com.nuvio.tv.ui.screens.home

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.SportsLiveEndedGames
import com.nuvio.tv.core.sportslive.SportsLiveScoreWait
import com.nuvio.tv.core.sportslive.scores.LiveScoresRepository
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.ui.components.LocalDrawnCardArt
import com.nuvio.tv.ui.screens.sportslive.SportsLiveCatchUpHomeRow
import com.nuvio.tv.ui.screens.sportslive.SportsLiveCatchUpMenu
import com.nuvio.tv.ui.screens.sportslive.SportsLiveCatchUpViewModel
import com.nuvio.tv.ui.screens.sportslive.SportsLiveGameRequests
import com.nuvio.tv.ui.screens.sportslive.SportsLiveScoresViewModel
import com.nuvio.tv.ui.screens.sportslive.TrackLiveScores
import com.nuvio.tv.ui.screens.sportslive.catchUpGameRequest
import com.nuvio.tv.ui.screens.sportslive.catchUpTitle
import com.nuvio.tv.ui.screens.sportslive.rememberCatchUpCardArt
import com.nuvio.tv.ui.screens.sportslive.rememberScoreChipStrings
import com.nuvio.tv.ui.screens.sportslive.rememberSportsLiveClockMs

/*
 * Home's Sports & Live hooks (Live now and Catch up rows), kept out of HomeScreen.kt (#113) so upstream
 * merges of that file meet only one-line seams.
 */

/** Where Home's Sports & Live cards lead; NuvioNavHost fills it (see sportsLiveHomeNav). */
data class SportsLiveHomeNav(
    /** See all on the Live now row: the Sports & Live section. */
    val onNavigateToSportsLive: () -> Unit = {},
    val onLiveNowItemClick: (SportsLiveItem) -> Unit = {},
    /** Opens the Game page on the request just put in [SportsLiveGameRequests] (a Catch up card's menu). */
    val onOpenSportsLiveGame: () -> Unit = {},
    /** OK on a Catch up card (#58): its replay plays at once ([SportsLivePlayViewModel.playReplay]), titled by ESPN's matchup. */
    val onPlayCatchUpReplay: (replays: List<SportsLiveItem>, title: String) -> Unit = { _, _ -> },
    /** Dual screen, on the session just put in SportsLiveDualSessions (a Live now card's Watch with another game, G10). */
    val onOpenSportsLiveDual: () -> Unit = {}
)

/** Home's click routing for the Sports & Live rows. */
internal class SportsLiveHomeRouter(
    private val viewModel: HomeViewModel,
    private val catchUpViewModel: SportsLiveCatchUpViewModel,
    private val context: Context,
    menuKey: MutableState<String?>,
    liveMenuKey: MutableState<String?>
) {
    /** The Catch up card whose menu is open. */
    var catchUpMenuKey: String? by menuKey

    /** The Live now card (its id) whose menu is open (audit G10). */
    var liveNowMenuId: String? by liveMenuKey

    fun onResume() {
        catchUpViewModel.onOpened()
    }

    /** True when the long press is the Sports & Live rows' (upstream's poster options are skipped). */
    fun onLongPress(item: MetaPreview, addonBaseUrl: String): Boolean {
        // A Catch up card's offers Game stats (score behind Reveal) and Mark as watched. A Live now card's is the
        // section's game menu (audit G10), never upstream's poster options.
        if (SportsLiveCatchUpHomeRow.isCatchUpRow(addonBaseUrl)) {
            catchUpMenuKey = SportsLiveCatchUpHomeRow.eventIdOf(item.id)
            return true
        }
        if (SportsLiveHomeRow.isLiveNowRow(addonBaseUrl)) {
            liveNowMenuId = item.id
            return true
        }
        return false
    }

    /** True when the card is the Sports & Live rows' (it opens the Sports & Live item, not an addon detail page). */
    fun onCardClick(itemId: String, addonBaseUrl: String, nav: SportsLiveHomeNav): Boolean {
        if (SportsLiveCatchUpHomeRow.isCatchUpRow(addonBaseUrl)) {
            // OK plays the replay at once: never the addon's page or Stream screen (spoilers).
            viewModel.findCatchUpItem(itemId)?.let { item ->
                // Where it was left, as its card says (coordinator 2026-11-20).
                catchUpViewModel.prepareReplay(item)
                nav.onPlayCatchUpReplay(item.replays, catchUpTitle(context, item))
            }
            return true
        }
        if (SportsLiveHomeRow.isLiveNowRow(addonBaseUrl)) {
            viewModel.findLiveNowItem(itemId)?.let { item ->
                viewModel.startLiveNowZapSession(item)
                nav.onLiveNowItemClick(item)
            }
            return true
        }
        return false
    }
}

/**
 * What HomeScreen gets from [sportsLiveHome], new each composition. Every [router] shares one menu state,
 * so a remembered lambda may keep an older one, as it kept HomeScreen's locals before.
 */
internal class SportsLiveHome(
    val router: SportsLiveHomeRouter,
    /** Catch up cards (#58) and Live now games (V2 phase 10) carry no image: the section's drawn game card stands in. */
    val cardArt: (String) -> (@Composable () -> Unit)?,
    /** Live now's See all line: every live game counted. */
    val seeAll: String?
) {
    fun onResume() = router.onResume()
    fun onLongPress(item: MetaPreview, addonBaseUrl: String): Boolean = router.onLongPress(item, addonBaseUrl)
    fun onCardClick(itemId: String, addonBaseUrl: String, nav: SportsLiveHomeNav): Boolean =
        router.onCardClick(itemId, addonBaseUrl, nav)
}

/** See all on the Live now row opens the section; every other row keeps upstream's See all. */
internal fun sportsLiveSeeAll(
    onNavigateToCatalogSeeAll: (String, String, String) -> Unit,
    nav: SportsLiveHomeNav
): (String, String, String) -> Unit = { catalogId, addonId, type ->
    if (addonId == SportsLiveHomeRow.ADDON_ID) {
        nav.onNavigateToSportsLive()
    } else {
        onNavigateToCatalogSeeAll(catalogId, addonId, type)
    }
}

/** Composes Home's Sports & Live state (Catch up row and menu, Live now scores) and returns its hooks. */
@Composable
internal fun sportsLiveHome(viewModel: HomeViewModel, uiState: HomeUiState, nav: SportsLiveHomeNav): SportsLiveHome {
    val liveScoresViewModel: SportsLiveScoresViewModel = hiltViewModel()
    val catchUpViewModel: SportsLiveCatchUpViewModel = hiltViewModel()

    // The Catch up row (#58): worked out when Home resumes, never on a timer.
    val catchUpItems by catchUpViewModel.items.collectAsStateWithLifecycle()
    val keptReplays by catchUpViewModel.kept.collectAsStateWithLifecycle()
    val replayResume by catchUpViewModel.resume.collectAsStateWithLifecycle()
    val catchUpContext = LocalContext.current
    val catchUpMenuKey = remember { mutableStateOf<String?>(null) }
    val liveNowMenuKey = remember { mutableStateOf<String?>(null) }
    val router = remember(viewModel, catchUpViewModel, catchUpContext) {
        SportsLiveHomeRouter(viewModel, catchUpViewModel, catchUpContext, catchUpMenuKey, liveNowMenuKey)
    }
    // Hold OK on a Live now card (audit G10): the section's game menu.
    router.liveNowMenuId?.let(viewModel::findLiveNowItem)?.let { item ->
        SportsLiveHomeCardMenu(
            item = item,
            all = viewModel.liveNowAllItems,
            ended = uiState.liveNowEnded,
            scoresViewModel = liveScoresViewModel,
            nav = nav,
            onDismiss = { router.liveNowMenuId = null }
        )
    }
    LaunchedEffect(catchUpItems) { viewModel.setCatchUpItems(catchUpItems) }
    // P14: the row under Continue Watching never changes title as it loads: its place is held while it loads. The
    // hold ends a moment after loading does, so the cards arrive in the held place instead of the row leaving first.
    val catchUpLoading by catchUpViewModel.loading.collectAsStateWithLifecycle()
    LaunchedEffect(catchUpLoading, catchUpItems.isEmpty()) {
        if (!catchUpLoading && catchUpItems.isEmpty()) kotlinx.coroutines.delay(CATCH_UP_HOLD_GRACE_MS)
        viewModel.setCatchUpPending(catchUpLoading)
    }
    val catchUpCardArt = rememberCatchUpCardArt(uiState.catchUpItems, replayResume)
    uiState.catchUpItems.firstOrNull { it.key == router.catchUpMenuKey }?.let { item ->
        SportsLiveCatchUpMenu(
            item = item,
            onOpenGame = {
                router.catchUpMenuKey = null
                SportsLiveGameRequests.open(catchUpGameRequest(item))
                nav.onOpenSportsLiveGame()
            },
            onMarkWatched = {
                router.catchUpMenuKey = null
                catchUpViewModel.markWatched(item)
            },
            onDismiss = { router.catchUpMenuKey = null },
            kept = item.key in keptReplays,
            onToggleKeep = {
                router.catchUpMenuKey = null
                catchUpViewModel.toggleKeep(item, item.key in keptReplays)
            },
            onStartOver = if (item.key in replayResume) {
                {
                    router.catchUpMenuKey = null
                    catchUpViewModel.prepareReplay(item, startOver = true)
                    nav.onPlayCatchUpReplay(item.replays, catchUpTitle(catchUpContext, item))
                }
            } else null
        )
    }

    // Live now cards get a score label under Show only (Home has no focus reveal), polled only while
    // Home is started. #207: the scores (and the clock) are read in [SportsLiveHomeScores] and by each drawn card,
    // never here: this function shares Home's recomposition scope, so a read here redrew all of Home on every update.
    val liveScores = liveScoresViewModel.scores.collectAsStateWithLifecycle()
    val liveScoresMode by liveScoresViewModel.mode.collectAsStateWithLifecycle()
    TrackLiveScores(
        screenId = LiveScoresRepository.SCREEN_HOME,
        cards = uiState.liveNowItems,
        // Under Hide too: the matched game gives the cards their logos (#182); the score stays hidden. Under Off, only
        // the start of games listed live is read, never a score (live.20, a wrong LIVE).
        enabled = liveScoresMode != null,
        tracking = liveScoresViewModel.tracking
    )
    val unmatchedScores = liveScoresViewModel.unmatched.collectAsStateWithLifecycle()
    // #375: which cards wait for ESPN and which Live Now calls Probably ended, worked out in the scores' own scope.
    val truth = remember { SportsLiveHomeTruth() }
    SportsLiveHomeScores(viewModel, uiState.liveNowItems, liveScores, liveScoresMode, unmatchedScores, truth)
    // V2 phase 10: Live now games are drawn as the section's game card; See all counts every live game.
    val liveNowCardArt = rememberLiveNowCardArt(uiState.liveNowItems, liveScores, liveScoresMode, unmatchedScores, truth)
    val sportsCardArt = remember(catchUpCardArt, liveNowCardArt) { drawnArtOf(catchUpCardArt, liveNowCardArt) }
    val liveNowOver = uiState.liveNowOver
    val liveGameCount = remember(uiState.liveNowItems, liveNowOver) {
        sportsLiveHomeLiveGames(viewModel.liveNowAllItems, liveNowOver).count { !it.isChannel }
    }
    val liveNowSeeAll = if (liveGameCount > 0) stringResource(R.string.sports_live_home_see_all_live, liveGameCount) else null
    return SportsLiveHome(router = router, cardArt = sportsCardArt, seeAll = liveNowSeeAll)
}

/**
 * Live now's score labels and the games ESPN confirmed final, from the scores and the 30 s clock, handed to Home's view
 * model. In a recomposition scope of its own (#207): a score update or a clock tick reruns only this, not all of Home.
 */
@Composable
private fun SportsLiveHomeScores(
    viewModel: HomeViewModel,
    items: List<SportsLiveItem>,
    scores: State<Map<String, ScoreSnapshot>>,
    mode: ScoresMode?,
    unmatched: State<Set<String>>,
    truth: SportsLiveHomeTruth
) {
    val current = scores.value
    val scoreChipStrings = rememberScoreChipStrings()
    val liveScoreFormat = stringResource(R.string.sports_live_chip_home_live)
    val labels = remember(items, current, mode, scoreChipStrings, liveScoreFormat) {
        SportsLiveHomeRow.scoreLabels(items, current, mode, scoreChipStrings, liveScoreFormat)
    }
    // The hero's eyebrow tag as the focused card reads it (live.24, one game identity).
    val sportWord = com.nuvio.tv.ui.screens.sportslive.rememberSportWord()
    val tags = remember(items, current, sportWord) { SportsLiveHomeRow.tags(items, current, sportWord) }
    // live.25 #226: a game not started yet reads "Starts 7:00 PM" in the hero's eyebrow, as its card does.
    val clockMs by rememberSportsLiveClockMs()
    val startsFormat = stringResource(R.string.sports_live_waiting_starts)
    val tomorrowFormat = stringResource(R.string.sports_live_tomorrow)
    // Games ESPN shows final leave the row (#60), at once (l24 polish P1: in the postgame grace too, so a final never
    // reads "Live now"); an estimate never drops one.
    val ended = remember(items, current, clockMs) { SportsLiveEndedGames.shared.finalKeys(items, current, clockMs) }
    // #375: the games Live Now calls Probably ended (an estimate, overdue) are labelled so on their card and in the hero,
    // never hidden, and leave the "N live" count and the zap list as Live Now's strip does. An ESPN-covered game reads its
    // start, not "Live", until ESPN answers (at most SportsLiveScoreWait.MAX_WAIT_MS, then as before), as on Live Now; a game
    // no feed covers and Scores Off are unchanged. One update at the wait's deadline.
    val unmatchedNow = unmatched.value
    val wait = remember { SportsLiveScoreWait() }
    var wake by remember { mutableStateOf(0) }
    val homeTruth = remember(items, current, mode, unmatchedNow, clockMs, wake) {
        homeLiveTruth(
            items, viewModel.liveNowAllItems, current, mode, unmatchedNow, System.currentTimeMillis(), wait,
            SportsLiveEndedGames.shared, viewModel.sportsLiveRepository::hasRecentLinks
        )
    }
    // live.25 #226: the "N live" count and the zap list keep a final through the grace, as Live Now does.
    val over = remember(items, current, clockMs, homeTruth) {
        SportsLiveEndedGames.shared.confirmedEndedKeys(viewModel.liveNowAllItems, current, clockMs) + homeTruth.probablyEnded +
            // L5: a game ESPN has postponed or cancelled is no live game in the count or the zap list either.
            SportsLiveEndedGames.postponedKeys(viewModel.liveNowAllItems, current)
    }
    LaunchedEffect(ended, over) { viewModel.setLiveNowEnded(ended, over) }
    LaunchedEffect(homeTruth.deadline) {
        val at = homeTruth.deadline ?: return@LaunchedEffect
        kotlinx.coroutines.delay((at - System.currentTimeMillis()).coerceAtLeast(0L) + 1)
        wake++
    }
    // The hero's eyebrow says what the focused card says: its start while it waits, Probably ended, "Starts 7:00 PM".
    val checkingWord = stringResource(R.string.sports_live_checking_score)
    val probablyEndedWord = stringResource(R.string.sports_live_probably_ended)
    val postponedWord = stringResource(R.string.sports_live_chip_postponed)
    val starts = remember(items, current, clockMs, startsFormat, tomorrowFormat, homeTruth, checkingWord, probablyEndedWord, postponedWord) {
        SportsLiveHomeRow.starts(
            items, current, startsFormat, tomorrowFormat, clockMs,
            awaiting = homeTruth.awaiting, checkingWord = checkingWord,
            probablyEnded = homeTruth.probablyEnded, probablyEndedWord = probablyEndedWord, postponedWord = postponedWord
        )
    }
    LaunchedEffect(labels, tags, starts) { viewModel.setLiveNowScoreLabels(labels, tags, starts) }
    SideEffect {
        truth.awaiting.value = homeTruth.awaiting
        truth.probablyEnded.value = homeTruth.probablyEnded
    }
}

/** #375: what Home's Live now row reads of the live truth: the cards waiting for ESPN, the Probably ended ones, the wait's deadline. */
internal data class HomeLiveTruth(val awaiting: Set<String>, val probablyEnded: Set<String>, val deadline: Long?)

/**
 * #375: Home's Live now [items] (the row; [allItems] every live game, for the count) as Live Now reads them at [nowMs]: the
 * games it shows Probably ended ([SportsLiveEndedGames.probablyEndedKeys]) and the ESPN-covered ones still waiting for
 * ESPN ([SportsLiveScoreWait]; none while the Scores setting is unknown, [mode] null, or Off). Pure but for [wait]'s clock.
 */
internal fun homeLiveTruth(
    items: List<SportsLiveItem>,
    allItems: List<SportsLiveItem>,
    scores: Map<String, ScoreSnapshot>,
    mode: ScoresMode?,
    unmatched: Set<String>,
    nowMs: Long,
    wait: SportsLiveScoreWait,
    endedGames: SportsLiveEndedGames,
    hasRecentLinks: (SportsLiveItem) -> Boolean
): HomeLiveTruth {
    val gate = SportsLiveEndedGames.estimateGate(mode == ScoresMode.OFF, unmatched)
    val probablyEnded = endedGames.probablyEndedKeys(allItems, scores, nowMs, gate, hasRecentLinks)
    val tracked = if (mode == null) emptyList() else items.filter { it.isLive && !it.isChannel && it.streamKey !in probablyEnded }
    val awaiting = wait.awaiting(tracked, scores, gate, nowMs)
    return HomeLiveTruth(awaiting, probablyEnded, wait.deadline)
}

/** #375: Home's Live now cards' waiting and Probably ended keys, written by [SportsLiveHomeScores], read by each card. */
internal class SportsLiveHomeTruth {
    val awaiting: MutableState<Set<String>> = mutableStateOf(emptySet())
    val probablyEnded: MutableState<Set<String>> = mutableStateOf(emptySet())
}

/** Provides Home's content the Sports & Live drawn card art, Live now's See all line and My games today. */
@Composable
internal fun SportsLiveHomeLocals(sportsLive: SportsLiveHome, content: @Composable () -> Unit) {
    // My games today (live.20 1b extra a): the quiet line above Live now.
    val myGamesTodayViewModel: SportsLiveMyGamesTodayViewModel = hiltViewModel()
    val myGamesToday by myGamesTodayViewModel.today.collectAsStateWithLifecycle()
    CompositionLocalProvider(
        LocalDrawnCardArt provides sportsLive.cardArt,
        LocalSportsLiveSeeAll provides sportsLive.seeAll,
        LocalSportsLiveMyGamesToday provides myGamesToday,
        content = content
    )
}

/** P14: how long Home keeps the Catch up row's place after it finished loading with nothing, so its cards land there. */
private const val CATCH_UP_HOLD_GRACE_MS = 400L
