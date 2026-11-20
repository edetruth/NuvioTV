package com.nuvio.tv.ui.screens.home

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
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
import com.nuvio.tv.core.sportslive.scores.LiveScoresRepository
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
    val onPlayCatchUpReplay: (replays: List<SportsLiveItem>, title: String) -> Unit = { _, _ -> }
)

/** Home's click routing for the Sports & Live rows. */
internal class SportsLiveHomeRouter(
    private val viewModel: HomeViewModel,
    private val catchUpViewModel: SportsLiveCatchUpViewModel,
    private val context: Context,
    menuKey: MutableState<String?>
) {
    /** The Catch up card whose menu is open. */
    var catchUpMenuKey: String? by menuKey

    fun onResume() {
        catchUpViewModel.onOpened()
    }

    /** True when the long press is the Sports & Live rows' (upstream's poster options are skipped). */
    fun onLongPress(item: MetaPreview, addonBaseUrl: String): Boolean {
        // Live now cards have no poster options; a long press on them does nothing. A Catch up card's
        // offers Game stats (score behind Reveal) and Mark as watched.
        if (SportsLiveCatchUpHomeRow.isCatchUpRow(addonBaseUrl)) {
            catchUpMenuKey = SportsLiveCatchUpHomeRow.eventIdOf(item.id)
            return true
        }
        return SportsLiveHomeRow.isLiveNowRow(addonBaseUrl)
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
    val router = remember(viewModel, catchUpViewModel, catchUpContext) {
        SportsLiveHomeRouter(viewModel, catchUpViewModel, catchUpContext, catchUpMenuKey)
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
    // Home is started.
    val liveScores by liveScoresViewModel.scores.collectAsStateWithLifecycle()
    val liveScoresMode by liveScoresViewModel.mode.collectAsStateWithLifecycle()
    TrackLiveScores(
        screenId = LiveScoresRepository.SCREEN_HOME,
        cards = uiState.liveNowItems,
        // Under Hide too: the matched game gives the cards their logos (#182); the score stays hidden. Under Off, only
        // the start of games listed live is read, never a score (live.20, a wrong LIVE).
        enabled = liveScoresMode != null,
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
    return SportsLiveHome(router = router, cardArt = sportsCardArt, seeAll = liveNowSeeAll)
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
