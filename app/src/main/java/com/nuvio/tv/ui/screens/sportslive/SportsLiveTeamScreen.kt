package com.nuvio.tv.ui.screens.sportslive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.alerts.GameReminders
import com.nuvio.tv.core.sportslive.alerts.RemindersState
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.PlayerCards
import com.nuvio.tv.core.sportslive.scores.TeamLeader
import com.nuvio.tv.core.sportslive.scores.TeamOutcome
import com.nuvio.tv.core.sportslive.scores.TeamPages
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.ui.components.LightweightCard
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The Team page (#62, `TeamPage.dc.html`): a header (logo, name, record, standing, Follow / ★ Following),
 * then Last 5 (OK opens the Game page; the Replay pill on a strict replay match), Next games (OK: Watch
 * when live, else Remind me / Cancel reminder, #44) and Leaders. ↑ ↓ move between sections, ← → within
 * one, Back returns. Scores: Show = all; Hide until focused = the last results wait for Reveal; Off =
 * nothing loads or shows until Reveal (decision 5).
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
    SportsLiveReminderToasts(remindersViewModel)

    val firstFocus = remember { FocusRequester() }
    // First focus: Reveal under Off, else Follow. A Reveal goes away once pressed: focus moves to Follow.
    LaunchedEffect(state.revealed) {
        repeat(2) { withFrameNanos { } }
        runCatching { firstFocus.requestFocus() }
    }

    val data = state.data
    val waiting = state.status == GameLoadStatus.WAITING_REVEAL
    fun openGame(event: ScoreSnapshot, card: SportsLiveItem? = null) {
        SportsLiveGameRequests.open(
            SportsLiveGameRequest(
                event = event,
                replay = state.replays[event.eventId],
                revealed = state.showsResults,
                homeFirst = false,
                card = card
            )
        )
        onOpenGame()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .padding(horizontal = NuvioTheme.spacing.xxxl, vertical = NuvioTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
    ) {
        TeamHeaderPanel(
            state = state,
            firstFocus = firstFocus,
            onReveal = viewModel::reveal,
            onToggleFollow = viewModel::toggleFollow
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                waiting -> TeamMessage(stringResource(R.string.sports_live_team_hidden_off))
                state.status == GameLoadStatus.FAILED -> TeamMessage(stringResource(R.string.sports_live_team_unavailable))
                data == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)) {
                    if (state.stale) item(key = "stale") { TeamMessage(stringResource(R.string.sports_live_team_stale)) }
                    item(key = "recent") {
                        TeamSection(stringResource(R.string.sports_live_team_last, TeamPages.RECENT)) {
                            if (data.recent.isEmpty()) {
                                TeamMessage(stringResource(R.string.sports_live_team_no_results))
                            } else {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                                    if (!state.showsResults) {
                                        item(key = "reveal") {
                                            SportsLivePaneButton(onClick = viewModel::reveal) {
                                                PaneButtonLabel(stringResource(R.string.sports_live_team_reveal_results))
                                            }
                                        }
                                    }
                                    items(data.recent, key = { it.eventId }) { event ->
                                        RecentTile(
                                            event = event,
                                            teamId = team.teamId,
                                            showsResult = state.showsResults,
                                            hasReplay = event.eventId in state.replays,
                                            onClick = { openGame(event) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item(key = "next") {
                        TeamSection(stringResource(R.string.sports_live_team_next)) {
                            if (data.upcoming.isEmpty()) {
                                TeamMessage(stringResource(R.string.sports_live_team_no_games))
                            } else {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                                    items(data.upcoming, key = { it.eventId }) { event ->
                                        val card = state.cards[event.eventId]
                                        UpcomingTile(
                                            event = event,
                                            teamId = team.teamId,
                                            card = card,
                                            reminders = reminders,
                                            onClick = {
                                                when (upcomingAction(event, card, System.currentTimeMillis())) {
                                                    UpcomingAction.WATCH -> card?.let { playViewModel.play(it) }
                                                    UpcomingAction.REMIND -> card?.let(remindersViewModel::toggle)
                                                    UpcomingAction.GAME_PAGE -> openGame(event, card)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (data.leaders.isNotEmpty()) {
                        item(key = "leaders") {
                            TeamSection(stringResource(R.string.sports_live_team_leaders)) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
                                    items(data.leaders, key = { it.category }) { leader ->
                                        val target = PlayerCards.targetFor(leader, team)
                                        LeaderTile(
                                            leader = leader,
                                            onClick = target?.let { open ->
                                                {
                                                    SportsLivePlayerCardRequests.open(SportsLivePlayerCardRequest(open))
                                                    onOpenPlayerCard()
                                                }
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
        Text(
            text = stringResource(R.string.sports_live_team_keys),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextTertiary
        )
    }
}

/** What OK on a next game does: Watch a live game with a card, toggle the reminder of an upcoming one, else its Game page. */
internal enum class UpcomingAction { WATCH, REMIND, GAME_PAGE }

internal fun upcomingAction(event: ScoreSnapshot, card: SportsLiveItem?, nowMs: Long): UpcomingAction = when {
    card == null -> UpcomingAction.GAME_PAGE
    event.state == ScoreState.IN || card.isLive -> UpcomingAction.WATCH
    GameReminders.canRemind(card, nowMs) -> UpcomingAction.REMIND
    else -> UpcomingAction.GAME_PAGE
}

@Composable
private fun TeamHeaderPanel(
    state: SportsLiveTeamUiState,
    firstFocus: FocusRequester,
    onReveal: () -> Unit,
    onToggleFollow: () -> Unit
) {
    val team = state.team ?: return
    val header = state.data?.header
    val name = header?.name ?: team.name
    val separator = stringResource(R.string.sports_live_game_info_separator)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NuvioTheme.radii.lg))
            .background(NuvioTheme.colors.BackgroundCard)
            .padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SportsLiveTeamLogo(
            logo = header?.logo ?: team.team.logo,
            short = header?.abbreviation ?: team.team.abbreviation ?: name,
            px = 192,
            modifier = Modifier.size(88.dp),
            letters = 3,
            fontSize = 22.sp
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)) {
            team.event.leagueName?.takeIf { it.isNotBlank() }?.let {
                Text(it.uppercase(Locale.getDefault()), style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextSecondary, maxLines = 1)
            }
            Text(
                text = name,
                style = MaterialTheme.typography.headlineSmall.sportsLiveBold(),
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (header != null && state.showsRecord) {
                header.record?.let {
                    Text(it, style = MaterialTheme.typography.titleLarge.sportsLiveBold().copy(fontFeatureSettings = "tnum"), color = NuvioTheme.colors.TextPrimary)
                }
                val streak = header.streak?.let { value ->
                    if (value > 0) stringResource(R.string.sports_live_team_streak_won, value)
                    else stringResource(R.string.sports_live_team_streak_lost, -value)
                }
                val home = header.homeRecord?.let { stringResource(R.string.sports_live_team_home_record, it) }
                val line = listOfNotNull(header.standing, streak, home)
                if (line.isNotEmpty()) {
                    Text(line.joinToString(separator), style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Column(modifier = Modifier.width(280.dp), verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
            val offReveal = state.mode == ScoresMode.OFF && !state.revealed
            if (offReveal) {
                SportsLivePaneButton(onClick = onReveal, modifier = Modifier.fillMaxWidth().focusRequester(firstFocus)) {
                    Text(stringResource(R.string.sports_live_team_reveal_all), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            SportsLivePaneButton(
                onClick = onToggleFollow,
                modifier = Modifier.fillMaxWidth().then(if (offReveal) Modifier else Modifier.focusRequester(firstFocus))
            ) {
                if (state.followed) {
                    Icon(imageVector = Icons.Rounded.Star, contentDescription = null, tint = TEAM_STAR_COLOR, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(NuvioTheme.spacing.xs))
                }
                Text(
                    text = stringResource(if (state.followed) R.string.sports_live_team_following else R.string.sports_live_team_follow),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun TeamSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
        Text(
            text = title.uppercase(Locale.getDefault()),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = NuvioTheme.colors.TextSecondary
        )
        content()
    }
}

@Composable
private fun TeamMessage(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.TextSecondary, modifier = Modifier.padding(NuvioTheme.spacing.sm))
}

@Composable
private fun TeamTile(onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    LightweightCard(
        onClick = onClick,
        modifier = Modifier.width(TILE_WIDTH),
        shape = shape,
        containerColor = NuvioTheme.colors.BackgroundCard,
        focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
    ) {
        Column(
            modifier = Modifier.padding(NuvioTheme.spacing.md).height(TILE_CONTENT_HEIGHT),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
            content = content
        )
    }
}

/** "vs Pacers" / "@ Celtics" with the opponent's logo. */
@Composable
private fun OpponentLine(event: ScoreSnapshot, teamId: String) {
    val opponent = TeamPages.opponentOf(event, teamId)
    val name = opponent.shortDisplayName ?: opponent.displayName ?: opponent.abbreviation.orEmpty()
    Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        SportsLiveTeamLogo(
            logo = opponent.logo,
            short = opponent.abbreviation ?: name,
            px = 64,
            modifier = Modifier.size(28.dp)
        )
        Text(
            text = stringResource(if (TeamPages.isHome(event, teamId)) R.string.sports_live_team_vs else R.string.sports_live_team_at, name),
            style = MaterialTheme.typography.titleSmall.sportsLiveBold(),
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun dateText(event: ScoreSnapshot, withTime: Boolean): String? {
    val locale = Locale.getDefault()
    return remember(event.startMs, withTime, locale) {
        event.startMs?.let { start ->
            val zoned = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault())
            val day = DateTimeFormatter.ofPattern("EEE d MMM", locale).format(zoned)
            if (withTime && event.timeValid) day + " · " + DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(zoned) else day
        }
    }
}

@Composable
private fun RecentTile(event: ScoreSnapshot, teamId: String, showsResult: Boolean, hasReplay: Boolean, onClick: () -> Unit) {
    TeamTile(onClick = onClick) {
        dateText(event, withTime = false)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary, maxLines = 1) }
        OpponentLine(event, teamId)
        val result = TeamPages.resultOf(event, teamId)
        Text(
            text = if (showsResult && result != null) {
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
            style = MaterialTheme.typography.titleMedium.sportsLiveBold().copy(fontFeatureSettings = "tnum"),
            color = if (showsResult && result?.outcome == TeamOutcome.WIN) NuvioTheme.colors.TextPrimary else NuvioTheme.colors.TextSecondary,
            maxLines = 1
        )
        Spacer(modifier = Modifier.weight(1f))
        if (hasReplay) ReplayPill()
    }
}

@Composable
private fun UpcomingTile(event: ScoreSnapshot, teamId: String, card: SportsLiveItem?, reminders: RemindersState, onClick: () -> Unit) {
    val live = event.state == ScoreState.IN
    TeamTile(onClick = onClick) {
        if (live) {
            Text(
                text = stringResource(R.string.sports_live_team_live),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                modifier = Modifier.clip(RoundedCornerShape(NuvioTheme.radii.sm)).background(ArtColors.LiveRed).padding(horizontal = NuvioTheme.spacing.sm, vertical = 2.dp)
            )
        } else {
            dateText(event, withTime = true)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = NuvioTheme.colors.TextSecondary, maxLines = 1) }
        }
        OpponentLine(event, teamId)
        Spacer(modifier = Modifier.weight(1f))
        when (upcomingAction(event, card, System.currentTimeMillis())) {
            UpcomingAction.WATCH -> ActionLine(Icons.Rounded.PlayArrow, stringResource(R.string.sports_live_watch), REMINDER_TEXT)
            UpcomingAction.REMIND -> {
                val reminded = card != null && reminders.isReminded(card)
                ActionLine(
                    Icons.Rounded.Notifications,
                    stringResource(if (reminded) R.string.sports_live_team_reminder_set else R.string.sports_live_remind_me),
                    if (reminded) REMINDER_TEXT else NuvioTheme.colors.TextSecondary
                )
            }
            UpcomingAction.GAME_PAGE -> Unit
        }
    }
}

@Composable
private fun ActionLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Icon(imageVector = icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1)
    }
}

/** A leader: focusable so ↓ reaches the section; OK opens the Player card (#63) when there is one. */
@Composable
private fun LeaderTile(leader: TeamLeader, onClick: (() -> Unit)?) {
    TeamTile(onClick = onClick ?: {}) {
        Text(leader.category.uppercase(Locale.getDefault()), style = MaterialTheme.typography.labelSmall, color = NuvioTheme.colors.TextSecondary, maxLines = 1)
        Text(leader.value, style = MaterialTheme.typography.headlineSmall.sportsLiveBold().copy(fontFeatureSettings = "tnum"), color = NuvioTheme.colors.TextPrimary, maxLines = 1)
        val player = leader.position?.let { stringResource(R.string.sports_live_team_leader_player, leader.player, it) } ?: leader.player
        Text(player, style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val TILE_WIDTH = 200.dp
private val TILE_CONTENT_HEIGHT = 112.dp
private val TEAM_STAR_COLOR = Color(0xFFFFC107)
private val REMINDER_TEXT = Color(0xFFCFE1FF)
