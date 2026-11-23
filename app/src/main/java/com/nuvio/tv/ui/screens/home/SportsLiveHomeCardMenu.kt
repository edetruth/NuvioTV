package com.nuvio.tv.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.SportsLiveToastKind
import com.nuvio.tv.core.sportslive.SportsLiveToasts
import com.nuvio.tv.core.sportslive.displayName
import com.nuvio.tv.core.sportslive.scores.FollowTarget
import com.nuvio.tv.core.sportslive.scores.FollowTargets
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.domain.model.FollowedTeam
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.repository.SportsLiveRepository
import com.nuvio.tv.ui.screens.sportslive.SportsLiveCardMenu
import com.nuvio.tv.ui.screens.sportslive.SportsLiveGameRequest
import com.nuvio.tv.ui.screens.sportslive.SportsLiveGameRequests
import com.nuvio.tv.ui.screens.sportslive.SportsLiveReporter
import com.nuvio.tv.ui.screens.sportslive.SportsLiveScoresViewModel
import com.nuvio.tv.ui.screens.sportslive.dual.SportsLiveDualPick
import com.nuvio.tv.ui.screens.sportslive.dual.SportsLiveDualPickerDialog
import com.nuvio.tv.ui.screens.sportslive.dual.SportsLiveDualSessions
import com.nuvio.tv.ui.screens.sportslive.dual.dualPickerBoards
import com.nuvio.tv.ui.screens.sportslive.dual.sportsLiveDualAvailable
import com.nuvio.tv.ui.screens.sportslive.sportsLiveGameHomeFirst
import com.nuvio.tv.ui.screens.sportslive.toggleFollowTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Follow and Report for Home's Live now card menu (audit G10). */
@HiltViewModel
class SportsLiveHomeMenuViewModel @Inject constructor(
    private val repo: SportsLiveRepository,
    private val reporter: SportsLiveReporter
) : ViewModel() {
    val followedTeams: StateFlow<List<FollowedTeam>> = repo.followedTeams.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggleFollow(target: FollowTarget) {
        viewModelScope.launch { repo.toggleFollowTarget(target) }
    }

    fun report() = reporter.report(HOME_MENU_TRIGGER)

    private companion object {
        const val HOME_MENU_TRIGGER = "Hold OK › Report a problem (home Live now card)"
    }
}

/**
 * What Home's Live now card menu offers for [item] with its strict ESPN [game] (audit G10: the same menu as a Live now
 * row, from the shared [SportsLiveCardMenu]): Game stats with a strict match, Follow for each real team, Watch later
 * with a strict match, Watch with another game while another live game can go beside it, and Report a problem.
 */
internal data class HomeCardMenuEntries(
    val gameStats: Boolean,
    val followSides: Int,
    val watchLater: Boolean,
    val watchWith: Boolean,
    val report: Boolean = true
)

internal fun homeCardMenuEntries(item: SportsLiveItem, game: ScoreSnapshot?, followSides: Int, partners: Int): HomeCardMenuEntries =
    HomeCardMenuEntries(
        gameStats = game != null,
        followSides = if (item.isChannel) 0 else followSides,
        watchLater = game != null && !item.isChannel,
        watchWith = partners > 0 && sportsLiveDualAvailable()
    )

/**
 * Hold OK on a Home Live now card (audit G10): the section's card menu for [item], not the bare Report sheet. [all]: every
 * live game Home knows (the partners for Watch with another game), [ended]: card keys known over.
 */
@Composable
internal fun SportsLiveHomeCardMenu(
    item: SportsLiveItem,
    all: List<SportsLiveItem>,
    ended: Set<String>,
    scoresViewModel: SportsLiveScoresViewModel,
    nav: SportsLiveHomeNav,
    onDismiss: () -> Unit,
    menuViewModel: SportsLiveHomeMenuViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scores by scoresViewModel.scores.collectAsStateWithLifecycle()
    val mode by scoresViewModel.mode.collectAsStateWithLifecycle()
    val protection by scoresViewModel.protection.collectAsStateWithLifecycle()
    val followedTeams by menuViewModel.followedTeams.collectAsStateWithLifecycle()
    val game = ScoreChips.matchedEvent(item, scores[item.streamKey]).takeIf { mode != null }
    val homeFirst = game?.let { sportsLiveGameHomeFirst(item, it) } ?: false
    val targets = remember(item, game) { FollowTargets.of(item, game, homeFirst) }
    val pick = remember(item, all, ended) { SportsLiveDualPick(item, all, filter = null, tab = SportsLiveTab.LIVE_NOW, endedKeys = ended) }
    // live.26 (#222): only live games count as partners; the entry is hidden with none.
    val entries = homeCardMenuEntries(item, game, targets.size, pick.candidates.count { !it.isChannel })
    var picking by remember(item) { mutableStateOf(false) }
    if (picking) {
        // The same rows and spoiler gate as Live Now's picker: Hide until focused reveals a focused row (#117).
        val revealed by scoresViewModel.revealed.collectAsStateWithLifecycle()
        SportsLiveDualPickerDialog(
            candidates = pick.candidates,
            boards = remember(pick, scores, mode, revealed) { dualPickerBoards(pick.candidates, scores, mode, revealed, System.currentTimeMillis()) },
            onPick = { second ->
                onDismiss()
                SportsLiveDualSessions.start(pick.session(second))
                nav.onOpenSportsLiveDual()
            },
            onDismiss = onDismiss,
            onFocus = scoresViewModel::onCardFocus,
            anchor = item,
            scores = scores
        )
        return
    }
    SportsLiveCardMenu(
        title = item.displayName,
        sides = targets.take(entries.followSides).map { it.label to it.isFollowed(followedTeams) },
        onToggleFollow = { index ->
            onDismiss()
            targets.getOrNull(index)?.let(menuViewModel::toggleFollow)
        },
        onDetails = null,
        onDismiss = onDismiss,
        onGameStats = game?.takeIf { entries.gameStats }?.let { event ->
            {
                onDismiss()
                SportsLiveGameRequests.open(
                    SportsLiveGameRequest(event, revealed = SportsLiveHomeRow.showsScores(mode), homeFirst = homeFirst, card = item)
                )
                nav.onOpenSportsLiveGame()
            }
        },
        gamePreview = game?.state == ScoreState.PRE,
        onWatchWith = if (entries.watchWith) ({ picking = true }) else null,
        onWatchLater = game?.takeIf { entries.watchLater }?.let { event ->
            {
                onDismiss()
                if (protection.isSaved(event.eventId)) {
                    scoresViewModel.revealResult(event)
                } else {
                    scoresViewModel.saveForLater(event)
                    SportsLiveToasts.show(
                        SportsLiveToastKind.INFO,
                        context.getString(R.string.sports_live_watch_later_toast, item.displayName),
                        context.getString(R.string.sports_live_watch_later_toast_line)
                    )
                }
            }
        },
        savedForLater = game != null && protection.isSaved(game.eventId),
        onReport = {
            onDismiss()
            menuViewModel.report()
        }
    )
}
