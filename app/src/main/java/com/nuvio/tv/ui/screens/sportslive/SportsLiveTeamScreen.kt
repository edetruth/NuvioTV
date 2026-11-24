package com.nuvio.tv.ui.screens.sportslive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.alerts.GameReminders
import com.nuvio.tv.core.sportslive.alerts.RemindersState
import com.nuvio.tv.core.sportslive.scores.PlayerCardTarget
import com.nuvio.tv.core.sportslive.scores.PlayerCards
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.TeamLeader
import com.nuvio.tv.core.sportslive.scores.TeamOutcome
import com.nuvio.tv.core.sportslive.scores.TeamPageSpoilers
import com.nuvio.tv.core.sportslive.scores.TeamPages
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButton
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButtonRole
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors
import com.nuvio.tv.ui.util.dpadRepeatThrottle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The Team page (#62; V2 #134, `TeamPage-PlayerCard.dc.html`): a header with no card (logo, league, name, one
 * line of record · standing · streak · home, and Follow as the PRIMARY: ★ Following turns it SECONDARY), then
 * rows, not tiles: Next games (OK: Watch when live, else Remind me / Cancel reminder, #44) and Last 5 (OK opens
 * the Game page; the Replay pill on a strict replay match) on the left, Leaders on the right (the name is the
 * headline; OK opens the Player card). Every row takes focus, so ↓ always scrolls. Scores: Show = all; Hide until
 * focused = each last result shows while its row has focus; Off = nothing loads or shows until Reveal
 * (decision 5). Back returns, and focus comes back to the row that opened a page.
 */
@Composable
fun SportsLiveTeamScreen(
    viewModel: SportsLiveTeamViewModel = hiltViewModel(),
    playViewModel: SportsLivePlayViewModel = hiltViewModel(),
    remindersViewModel: SportsLiveRemindersViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onPlay: (SportsLiveItem, Boolean) -> Unit,
    onPlayTarget: (SportsLiveZapTarget) -> Unit,
    /** Opens the Game page on the request just put in [SportsLiveGameRequests]. */
    onOpenGame: () -> Unit,
    /** Opens the Player card just put in [SportsLivePlayerCardRequests] (#63). */
    onOpenPlayerCard: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val currentOnBack by rememberUpdatedState(onBack)
    val team = state.team
    if (team == null) {
        LaunchedEffect(Unit) { currentOnBack() }
        return
    }
    BackHandler { currentOnBack() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.setVisible(true)
                Lifecycle.Event.ON_STOP -> viewModel.setVisible(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setVisible(false)
        }
    }
    SportsLivePlayHost(playViewModel = playViewModel, onOpenStreamScreen = onPlay, onPlayTarget = onPlayTarget)
    val reminders by remindersViewModel.state.collectAsStateWithLifecycle()

    val headerFocus = remember { FocusRequester() }
    val bodyFocus = remember { FocusRequester() }
    // Back from a Game page or a Player card: focus returns to the row that opened it.
    var returnKey by rememberSaveable { mutableStateOf<String?>(null) }
    var returnPending by rememberSaveable { mutableStateOf(false) }
    val returnFocus = remember { FocusRequester() }
    // The failure card (#97): its Try again is the header's Down and the first focus while it shows (review R2).
    val failureFocus = remember { FocusRequester() }
    val failed = state.status == GameLoadStatus.FAILED
    val failedNow by rememberUpdatedState(failed)
    // #461: whether anything on the page holds focus, and whether Try again did while the card showed.
    var pageHasFocus by remember { mutableStateOf(false) }
    var failureHadFocus by remember { mutableStateOf(false) }
    // First focus: Reveal under Off, else Follow. A Reveal goes away once pressed: focus moves to Follow.
    LaunchedEffect(state.revealed) {
        repeat(2) { withFrameNanos { } }
        if (returnPending) {
            returnPending = false
            withFrameNanos { }
            if (runCatching { returnFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
        runCatching { (if (failedNow) failureFocus else headerFocus).requestFocus() }
    }
    // Try again worked: the card that held focus is gone, so the header takes it, as on a first load.
    var wasFailed by remember { mutableStateOf(false) }
    LaunchedEffect(failed) {
        if (failed) {
            wasFailed = true
            return@LaunchedEffect
        }
        if (!wasFailed) return@LaunchedEffect
        wasFailed = false
        // #461: only when focus was on the card that went away; elsewhere it stays where the user is.
        if (!SportsLivePageFocus.recoveryMovesFocus(failureHadFocus)) return@LaunchedEffect
        failureHadFocus = false
        withFrameNanos { }
        runCatching { headerFocus.requestFocus() }
    }
    fun Modifier.returning(key: String): Modifier = if (key == returnKey) focusRequester(returnFocus) else this
    fun opening(key: String) {
        returnKey = key
        returnPending = true
    }

    val data = state.data
    val waiting = state.status == GameLoadStatus.WAITING_REVEAL
    fun openGame(event: ScoreSnapshot, card: SportsLiveItem? = null, revealed: Boolean = state.showsResults) {
        opening(teamGameKey(event.eventId))
        SportsLiveGameRequests.open(
            SportsLiveGameRequest(
                event = event,
                replay = state.replays[event.eventId],
                revealed = revealed,
                homeFirst = false,
                spoilerSafe = state.spoilerSafe,
                card = card
            )
        )
        onOpenGame()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .onFocusChanged { pageHasFocus = it.hasFocus }
            .background(Colors.Background)
            .padding(start = PAGE_SIDE, end = PAGE_SIDE, top = PAGE_TOP)
    ) {
        TeamHeader(
            state = state,
            firstFocus = headerFocus,
            downFocus = if (data != null) bodyFocus else if (failed) failureFocus else null,
            onReveal = viewModel::reveal,
            onToggleFollow = viewModel::toggleFollow
        )
        Spacer(Modifier.height(20.dp))
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                waiting -> SportsLivePageMessage(stringResource(R.string.sports_live_team_hidden_off))
                state.status == GameLoadStatus.FAILED ->
                    SportsLivePageFailure(
                        stringResource(R.string.sports_live_team_failed_title), state.retrying, viewModel::retry, focus = failureFocus,
                        takeFocus = SportsLivePageFocus.failureTakesFocus(pageHasFocus),
                        onFocused = { focused -> if (failedNow) failureHadFocus = focused }
                    )
                data == null -> SportsLiveRowsSkeleton()
                // Scrolling only when a row nears an edge, so the list opens at its top with its heading showing (the TV
                // default pivot would scroll a row returned to from a Game page up to a third of the height).
                else -> SportsLiveKeepFromEdge(margin = TEAM_EDGE_KEEP) { Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    val listModifier = Modifier.weight(1f).fillMaxSize().dpadRepeatThrottle()
                    val rowsPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 24.dp)
                    LazyColumn(modifier = listModifier, verticalArrangement = Arrangement.spacedBy(SportsLiveTokens.Size.RowGap), contentPadding = rowsPadding) {
                        // The first row of the left column takes the header's Down.
                        var firstTaken = false
                        fun first(): Modifier = if (firstTaken) Modifier else Modifier.focusRequester(bodyFocus).also { firstTaken = true }
                        if (state.stale) item(key = "stale") { SportsLivePageMessage(stringResource(R.string.sports_live_team_stale)) }
                        item(key = "next_title") { TeamHeading(stringResource(R.string.sports_live_team_next)) }
                        if (data.upcoming.isEmpty()) {
                            item(key = "no_games") { SportsLivePageMessage(stringResource(R.string.sports_live_team_no_games)) }
                        }
                        data.upcoming.forEach { event ->
                            val modifier = first().returning(teamGameKey(event.eventId))
                            item(key = "n|${event.eventId}") {
                                val card = state.cards[event.eventId]
                                NextGameRow(
                                    event = event,
                                    teamId = team.teamId,
                                    card = card,
                                    reminders = reminders,
                                    modifier = modifier,
                                    onClick = {
                                        when (upcomingAction(event, card, System.currentTimeMillis())) {
                                            UpcomingAction.WATCH -> card?.let { playViewModel.play(it) }
                                            // A card's reminder, else the ESPN game's own (matched by its sides at kickoff).
                                            UpcomingAction.REMIND -> if (card != null) remindersViewModel.toggle(card) else remindersViewModel.toggle(event)
                                            UpcomingAction.GAME_PAGE -> openGame(event, card)
                                        }
                                    }
                                )
                            }
                        }
                        item(key = "last_title") { TeamHeading(stringResource(R.string.sports_live_team_last, TeamPages.RECENT)) }
                        if (data.recent.isEmpty()) {
                            item(key = "no_results") { SportsLivePageMessage(stringResource(R.string.sports_live_team_no_results)) }
                        }
                        data.recent.forEach { event ->
                            val modifier = first().returning(teamGameKey(event.eventId))
                            item(key = "r|${event.eventId}") {
                                RecentRow(
                                    event = event,
                                    teamId = team.teamId,
                                    mode = state.mode,
                                    revealed = state.revealed,
                                    spoilerProtected = state.protection.protects(event),
                                    spoilerSafe = state.spoilerSafe,
                                    hasReplay = event.eventId in state.replays,
                                    modifier = modifier,
                                    // Under Hide until focused the row's result is showing as OK is pressed.
                                    onClick = { openGame(event, revealed = TeamPageSpoilers.showsResult(state.mode, state.revealed, focused = true, spoilerProtected = state.protection.protects(event), spoilerSafe = state.spoilerSafe)) }
                                )
                            }
                        }
                    }
                    if (data.leaders.isNotEmpty()) {
                        LazyColumn(modifier = listModifier, verticalArrangement = Arrangement.spacedBy(SportsLiveTokens.Size.RowGap), contentPadding = rowsPadding) {
                            item(key = "leaders_title") { TeamHeading(stringResource(R.string.sports_live_team_leaders)) }
                            items(data.leaders, key = { it.category }) { leader ->
                                val target = PlayerCards.targetFor(leader, team)
                                LeaderRow(
                                    leader = leader,
                                    modifier = Modifier.returning(teamLeaderKey(leader.category)),
                                    onClick = target?.let { open ->
                                        {
                                            opening(teamLeaderKey(leader.category))
                                            SportsLivePlayerCardRequests.open(teamPlayerCardRequest(open, state))
                                            onOpenPlayerCard()
                                        }
                                    }
                                )
                            }
                        }
                    }
                } }
            }
        }
    }
}

/**
 * The Player card a Team page leader opens. The page's results already show (Show, or revealed) and none is
 * protected: the card inherits it (as BUG19). One of the last results protected (G6): the card is spoiler-safe,
 * so its last games stay behind Reveal even under Scores Show, as from a protected Game page.
 */
internal fun teamPlayerCardRequest(target: PlayerCardTarget, state: SportsLiveTeamUiState): SportsLivePlayerCardRequest {
    val anyProtected = state.hasProtectedResult
    return SportsLivePlayerCardRequest(target, spoilerSafe = anyProtected || state.spoilerSafe, revealed = state.showsResults && !anyProtected)
}

private fun teamGameKey(eventId: String) = "g|$eventId"
private fun teamLeaderKey(category: String) = "l|$category"

/** What OK on a next game does: Watch a live game with a card, toggle the reminder of an upcoming one, else its Game page. */
internal enum class UpcomingAction { WATCH, REMIND, GAME_PAGE }

internal fun upcomingAction(event: ScoreSnapshot, card: SportsLiveItem?, nowMs: Long): UpcomingAction = when {
    event.state == ScoreState.IN || card?.isLive == true -> if (card != null) UpcomingAction.WATCH else UpcomingAction.GAME_PAGE
    card != null -> if (GameReminders.canRemind(card, nowMs)) UpcomingAction.REMIND else UpcomingAction.GAME_PAGE
    // No card plays it yet: the ESPN game itself is reminded, and matched by its sides at kickoff.
    GameReminders.canRemind(event, nowMs) -> UpcomingAction.REMIND
    else -> UpcomingAction.GAME_PAGE
}

/**
 * Board ①: the logo (140 px), "NFL" in 22 px caps, the 52 px name, one 28 px line (record · standing · streak ·
 * home); Follow is the PRIMARY and stays visible (decision 1); ★ Following is SECONDARY with a gold star. Under
 * Off, Reveal is the PRIMARY and Follow sits beside it.
 */
@Composable
private fun TeamHeader(
    state: SportsLiveTeamUiState,
    firstFocus: FocusRequester,
    downFocus: FocusRequester?,
    onReveal: () -> Unit,
    onToggleFollow: () -> Unit
) {
    val team = state.team ?: return
    val header = state.data?.header
    val name = header?.name ?: team.name
    val separator = stringResource(R.string.sports_live_game_info_separator)
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // The logo when the team has one, else nothing (#130): the name block moves left.
        rememberTeamLogo(header?.logo ?: team.team.logo, px = 280)?.let {
            TeamLogoSlot(it.painter, it.shown, TEAM_LOGO)
            Spacer(Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            team.event.leagueName?.takeIf { it.isNotBlank() }?.let { SportsLivePageHeading(it, color = Colors.TextMuted) }
            Text(
                text = name,
                style = SportsLiveTokens.style(SportsLiveTokens.Type.PageTitle).sportsLiveBold(),
                color = Colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Not while a last result is protected (G5): the streak or record would tell it.
            if (header != null && state.showsRecordLine) {
                val streak = header.streak?.let { value ->
                    if (value > 0) stringResource(R.string.sports_live_team_streak_won, value)
                    else stringResource(R.string.sports_live_team_streak_lost, -value)
                }
                val home = header.homeRecord?.let { stringResource(R.string.sports_live_team_home_record, it) }
                val line = listOfNotNull(header.record, header.standing, streak, home)
                if (line.isNotEmpty()) {
                    Text(
                        line.joinToString(separator),
                        style = SportsLiveTokens.style(HEADER_LINE),
                        color = Colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(SportsLiveTokens.Size.ButtonGap), verticalAlignment = Alignment.CenterVertically) {
            val offReveal = (state.mode == ScoresMode.OFF || state.spoilerSafe) && !state.revealed
            val leaves = Modifier.focusProperties { down = downFocus ?: FocusRequester.Cancel }
            if (offReveal) {
                SportsLiveButton(onClick = onReveal, role = SportsLiveButtonRole.PRIMARY, modifier = Modifier.focusRequester(firstFocus).then(leaves)) {
                    Text(stringResource(R.string.sports_live_team_reveal_all), style = SportsLiveTokens.style(SportsLiveTokens.Type.Button), maxLines = 1, softWrap = false)
                }
            }
            SportsLiveButton(
                onClick = onToggleFollow,
                role = if (state.followed || offReveal) SportsLiveButtonRole.SECONDARY else SportsLiveButtonRole.PRIMARY,
                modifier = Modifier
                    .widthIn(min = SportsLiveTokens.Size.ButtonMinWidth)
                    .then(if (offReveal) Modifier else Modifier.focusRequester(firstFocus))
                    .then(leaves)
            ) {
                Icon(
                    imageVector = if (state.followed) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                    contentDescription = null,
                    tint = if (state.followed) Colors.FollowedGold else LocalContentColor.current,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(if (state.followed) R.string.sports_live_team_following else R.string.sports_live_team_follow),
                    style = SportsLiveTokens.style(SportsLiveTokens.Type.Button),
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
private fun TeamHeading(text: String) {
    SportsLivePageHeading(text, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
}

/** "vs Pacers" / "@ Celtics" with the opponent's logo; the slot keeps its room so names line up row to row. */
@Composable
private fun OpponentName(event: ScoreSnapshot, teamId: String, modifier: Modifier = Modifier) {
    val opponent = TeamPages.opponentOf(event, teamId)
    val name = opponent.shortDisplayName ?: opponent.displayName ?: opponent.abbreviation.orEmpty()
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(ROW_LOGO), contentAlignment = Alignment.Center) {
            rememberTeamLogo(opponent.logo, px = 64)?.let { TeamLogoSlot(it.painter, it.shown, ROW_LOGO) }
        }
        Text(
            text = stringResource(if (TeamPages.isHome(event, teamId)) R.string.sports_live_team_vs else R.string.sports_live_team_at, name),
            style = SportsLiveTokens.style(SportsLiveTokens.Type.RowName).sportsLiveBold(),
            color = Colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** "Sun 29 Nov", and the local "3:05 PM" when the start time is known. */
@Composable
private fun dayAndTime(event: ScoreSnapshot): Pair<String?, String?> {
    val locale = Locale.getDefault()
    return remember(event.startMs, event.timeValid, locale) {
        val zoned = event.startMs?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) } ?: return@remember null to null
        DateTimeFormatter.ofPattern("EEE d MMM", locale).format(zoned) to
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(zoned).takeIf { event.timeValid }
    }
}

/** Board ②: a next game, 96 px: the date and time, the opponent, and what OK does (Watch, or the reminder bell). */
@Composable
private fun NextGameRow(
    event: ScoreSnapshot,
    teamId: String,
    card: SportsLiveItem?,
    reminders: RemindersState,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val live = event.state == ScoreState.IN
    SportsLivePageRow(height = NEXT_ROW, modifier = modifier, onClick = onClick, container = if (live) Colors.LiveRow else Colors.Row) {
        Column(modifier = Modifier.width(DATE_COLUMN)) {
            if (live) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    SportsLiveLiveDot()
                    Text(stringResource(R.string.sports_live_team_live), style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta), color = Colors.LiveText, maxLines = 1)
                }
            } else {
                val (day, time) = dayAndTime(event)
                day?.let { Text(it, style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta), color = Colors.TextPrimary, maxLines = 1) }
                time?.let { Text(it, style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag), color = Colors.TextMuted, maxLines = 1) }
            }
        }
        OpponentName(event, teamId, Modifier.weight(1f))
        when (upcomingAction(event, card, System.currentTimeMillis())) {
            UpcomingAction.WATCH -> RowAction(Icons.Rounded.PlayArrow, stringResource(R.string.sports_live_watch), Colors.TextPrimary, Colors.TextPrimary)
            UpcomingAction.REMIND -> {
                val reminded = if (card != null) reminders.isReminded(card) else reminders.isReminded(event)
                // The bell is blue once set: blue means "you set this", never live (red) or followed (gold).
                if (reminded) {
                    RowAction(Icons.Rounded.NotificationsActive, stringResource(R.string.sports_live_team_reminder_set), Colors.WhyTagBlue, Colors.WhyTagText)
                } else {
                    RowAction(Icons.Rounded.Notifications, stringResource(R.string.sports_live_remind_me), Colors.TextMuted, Colors.TextMuted)
                }
            }
            UpcomingAction.GAME_PAGE -> Unit
        }
    }
}

@Composable
private fun RowAction(icon: ImageVector, text: String, tint: Color, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Text(text, style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta), color = color, maxLines = 1)
    }
}

/**
 * Board ②: a last result, 72 px: the date, the opponent, "W 31–13" (a win in bold white) and the Replay pill.
 * Under Hide until focused the result shows only while the row has focus.
 */
@Composable
private fun RecentRow(
    event: ScoreSnapshot,
    teamId: String,
    mode: ScoresMode,
    revealed: Boolean,
    hasReplay: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    spoilerProtected: Boolean = false,
    spoilerSafe: Boolean = false
) {
    var focused by remember { mutableStateOf(false) }
    SportsLivePageRow(
        height = RECENT_ROW,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        onClick = onClick
    ) {
        Text(
            dayAndTime(event).first.orEmpty(),
            style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
            color = Colors.TextSecondary,
            maxLines = 1,
            modifier = Modifier.width(DATE_COLUMN)
        )
        OpponentName(event, teamId, Modifier.weight(1f))
        val result = TeamPages.resultOf(event, teamId)
        val shown = TeamPageSpoilers.showsResult(mode, revealed, focused, spoilerProtected, spoilerSafe) && result != null
        val win = shown && result?.outcome == TeamOutcome.WIN
        Text(
            text = if (shown && result != null) {
                val letter = stringResource(
                    when (result.outcome) {
                        TeamOutcome.WIN -> R.string.sports_live_team_win
                        TeamOutcome.LOSS -> R.string.sports_live_team_loss
                        TeamOutcome.DRAW -> R.string.sports_live_team_draw
                    }
                )
                stringResource(R.string.sports_live_team_result, letter, result.teamScore, result.opponentScore)
            } else {
                stringResource(R.string.sports_live_results_score_hidden)
            },
            // Hidden: the quieter 26 px, so "Score hidden" fits the result column.
            style = SportsLiveTokens.style(if (shown) SportsLiveTokens.Type.RowName else SportsLiveTokens.Type.Meta)
                .copy(fontFeatureSettings = "tnum").let { if (win) it.sportsLiveBold() else it },
            color = when {
                !shown -> Colors.TextMuted
                win -> Colors.TextPrimary
                else -> Colors.TextSecondary
            },
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(RESULT_COLUMN)
        )
        // The pill's room stays, so results line up whether or not a replay is listed.
        Box(modifier = Modifier.width(PILL_COLUMN), contentAlignment = Alignment.CenterEnd) {
            if (hasReplay) SportsLiveReplayPill()
        }
    }
}

/** Board ③: a leader, the name the 30 px headline over a 24 px "Passing · 2,330 yds" line; OK opens the Player card. */
@Composable
private fun LeaderRow(leader: TeamLeader, modifier: Modifier, onClick: (() -> Unit)?) {
    val separator = stringResource(R.string.sports_live_game_info_separator)
    SportsLivePageRow(height = LEADER_ROW, modifier = modifier, onClick = onClick ?: {}) {
        Text(
            leader.position.orEmpty(),
            style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag),
            color = Colors.TextMuted,
            maxLines = 1,
            modifier = Modifier.width(POSITION_COLUMN)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                leader.player,
                style = SportsLiveTokens.style(SportsLiveTokens.Type.RowName).sportsLiveBold(),
                color = Colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                listOf(leader.category, leader.value).joinToString(separator),
                style = SportsLiveTokens.style(LEADER_STAT),
                color = Colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** The header's one line: 28 px. */
private val HEADER_LINE = SportsLiveTokens.Type.Meta.copy(fontSize = 14.sp, lineHeight = 18.sp)
/** A leader's stat line: 24 px. */
private val LEADER_STAT = SportsLiveTokens.Type.Control.copy(fontWeight = FontWeight.Normal)
private val TEAM_LOGO = 70.dp
/** How close to the list's top or bottom a focused row may come before it scrolls: one next-game row. */
private val TEAM_EDGE_KEEP = 48.dp
private val ROW_LOGO = 16.dp
private val NEXT_ROW = 48.dp
private val RECENT_ROW = 36.dp
private val LEADER_ROW = 44.dp
private val DATE_COLUMN = 84.dp
private val RESULT_COLUMN = 104.dp
private val PILL_COLUMN = 56.dp
private val POSITION_COLUMN = 24.dp
