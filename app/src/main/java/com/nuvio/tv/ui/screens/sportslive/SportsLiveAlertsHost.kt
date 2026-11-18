package com.nuvio.tv.ui.screens.sportslive

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.sp
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
import com.nuvio.tv.core.sportslive.alerts.AlertAnswer
import com.nuvio.tv.core.sportslive.alerts.AlertContent
import com.nuvio.tv.core.sportslive.alerts.AlertSide
import com.nuvio.tv.core.sportslive.alerts.KickoffStyle
import com.nuvio.tv.core.sportslive.alerts.SportsLiveAlert
import com.nuvio.tv.core.sportslive.alerts.SportsLiveAlertContent
import com.nuvio.tv.core.sportslive.alerts.SportsLiveAlertEngine
import com.nuvio.tv.core.sportslive.alerts.SportsLiveAlerts
import com.nuvio.tv.core.sportslive.alerts.SportsLiveWatching
import com.nuvio.tv.core.sportslive.scores.TeamLogoUrls
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.navigation.navigateToSportsLiveZapTarget
import com.nuvio.tv.ui.navigation.sportsLiveTargetRoute
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniPlayers
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Hands the app-wide [SportsLiveAlerts] to the host. */
@HiltViewModel
class SportsLiveAlertsViewModel @Inject constructor(val alerts: SportsLiveAlerts) : ViewModel()

/**
 * Reminders' "Watch now?" prompt (#44) and followed teams' toasts (#42), over every screen while the
 * app is in the foreground. Over the player the prompt is the small one; toasts never take focus. The
 * prompt takes focus (Watch first) and gives it back to [contentFocusRequester] when it closes.
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
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { alerts.runInForeground() }
    }

    // What plays: the full player's content (its first seconds wait), the mini player's game, dual screen.
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val inPlayer = route == Screen.Player.route
    val miniState by SportsLiveMiniPlayers.controller.state.collectAsState()
    val streamStartedAt = remember(entry?.id) { if (inPlayer) System.currentTimeMillis() else null }
    val playerContentId = entry?.arguments?.getString("contentId")?.takeIf { inPlayer }
    val playerTitle = entry?.arguments?.getString("title")?.takeIf { inPlayer }
    val miniItem = (miniState as? SportsLiveMiniState.Playing)?.item
    LaunchedEffect(inPlayer, playerContentId, playerTitle, miniItem, streamStartedAt, route) {
        alerts.setWatching(
            SportsLiveWatching(
                playingIds = setOfNotNull(playerContentId) + listOfNotNull(miniItem).flatMap { listOf(it) + it.alternates }.map { it.id },
                inPlayer = inPlayer,
                // A game or channel played from Sports & Live; anything else is a movie or show.
                sportsPlayback = inPlayer && SportsLiveExtraSources.isSportsPlayback(playerContentId),
                streamStartedAtMs = streamStartedAt,
                dual = route == Screen.SportsLiveDual.route,
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

    val alert by alerts.current.collectAsState()
    val watching by alerts.watchingNow.collectAsState()
    val current = alert ?: return
    val content = remember(current) { SportsLiveAlertContent.of(current) }
    if (content == null) {
        LaunchedEffect(current) { alerts.close(current, AlertAnswer.DISMISS) }
        return
    }
    val scope = rememberCoroutineScope()
    fun giveFocusBack() {
        runCatching { contentFocusRequester.restoreFocusedChild() || contentFocusRequester.requestFocus(FocusDirection.Enter) }
    }
    // Each alert is its own overlay: its countdown and first focus start afresh.
    key(current) {
    Box(modifier = Modifier.fillMaxSize()) {
        val style = (current as? SportsLiveAlert.Kickoff)?.let { SportsLiveAlertEngine.kickoffStyle(watching) }
        when {
            // Over a movie or show: a toast that never takes focus or Back, so OK cannot replace that player.
            current is SportsLiveAlert.Kickoff && style == KickoffStyle.TOAST -> {
                LaunchedEffect(current) {
                    delay(TOAST_MS)
                    alerts.close(current, AlertAnswer.DISMISS)
                }
                AlertToast(
                    content = content,
                    followed = current.item.followed,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(horizontal = 48.dp, vertical = 40.dp)
                )
            }
            current is SportsLiveAlert.Kickoff -> KickoffPrompt(
                content = content as AlertContent.Kickoff,
                compact = style == KickoffStyle.COMPACT,
                watchingTitle = playerTitle,
                kickoffLabel = rememberSportsLiveStateLabeler(SportsLiveTab.UPCOMING)(current.item.copy(isLive = false)),
                onWatch = {
                    alerts.close(current, AlertAnswer.WATCH)
                    scope.launch { playViewModel.play(alerts.cardToWatch(current.item)) }
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
                    giveFocusBack()
                },
                modifier = Modifier
                    .align(if (inPlayer) Alignment.TopEnd else Alignment.BottomStart)
                    .padding(horizontal = 48.dp, vertical = 40.dp)
            )
            else -> {
                LaunchedEffect(current) {
                    delay(TOAST_MS)
                    alerts.close(current, AlertAnswer.TIMEOUT)
                }
                AlertToast(
                    content = content,
                    followed = current.item.followed,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(horizontal = 48.dp, vertical = 40.dp)
                )
            }
        }
    }
    }
}

@Composable
private fun KickoffPrompt(
    content: AlertContent.Kickoff,
    compact: Boolean,
    watchingTitle: String?,
    kickoffLabel: String?,
    onWatch: () -> Unit,
    onLater: () -> Unit,
    onBack: () -> Unit,
    onTimeout: () -> Unit,
    modifier: Modifier
) {
    val watchFocus = remember { FocusRequester() }
    var secondsLeft by remember { mutableIntStateOf((PROMPT_MS / 1_000).toInt()) }
    LaunchedEffect(Unit) {
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
    BackHandler(onBack = onBack)
    val shape = RoundedCornerShape(16.dp)
    val logoSize = if (compact) 36.dp else 52.dp
    Column(
        modifier = modifier
            .widthIn(max = if (compact) 460.dp else 620.dp)
            .clip(shape)
            .background(ArtColors.Panel.copy(alpha = 0.96f))
            .border(1.dp, PANEL_BORDER, shape)
            .padding(if (compact) 16.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.sports_live_live_pill),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = ArtColors.Text,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(ArtColors.LiveRed)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
            Text(
                text = stringResource(R.string.sports_live_alert_kicking_off),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = ArtColors.LiveText
            )
            if (content.followed) StarIcon()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SideLogo(content.first, logoSize)
            Text(
                text = stringResource(R.string.sports_live_alert_matchup, content.first.name, content.second.name),
                style = (if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall).sportsLiveBold(),
                color = ArtColors.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            SideLogo(content.second, logoSize)
        }
        val details = listOfNotNull(
            content.league,
            kickoffLabel,
            watchingTitle?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.sports_live_alert_watching, it) }
        )
        if (details.isNotEmpty()) {
            Text(
                text = details.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = ArtColors.Secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SportsLivePaneButton(onClick = onWatch, modifier = Modifier.focusRequester(watchFocus)) {
                Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.sports_live_watch), maxLines = 1)
            }
            SportsLivePaneButton(onClick = onLater) {
                Text(stringResource(R.string.sports_live_alert_later), maxLines = 1)
            }
            Text(
                text = stringResource(R.string.sports_live_alert_closes, secondsLeft),
                style = MaterialTheme.typography.bodySmall,
                color = ArtColors.Muted,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun AlertToast(content: AlertContent, followed: Boolean, modifier: Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .widthIn(max = 440.dp)
            .clip(shape)
            .background(ArtColors.Panel.copy(alpha = 0.96f))
            .border(1.dp, PANEL_BORDER, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        when (content) {
            is AlertContent.Score -> {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (followed) StarIcon()
                    Text(
                        text = content.scorer?.let { stringResource(R.string.sports_live_alert_scored, it.name) }
                            ?: stringResource(R.string.sports_live_alert_score_update),
                        style = MaterialTheme.typography.titleMedium.sportsLiveBold(),
                        color = ArtColors.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                ScoreLine(content.first, content.second)
                content.clock?.let { SmallLine(it, ArtColors.LiveText) }
            }
            is AlertContent.FullTime -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.sports_live_alert_full_time),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = ArtColors.Secondary
                    )
                    content.league?.let { SmallLine(it, ArtColors.Muted) }
                }
                ScoreLine(content.first, content.second)
                content.note?.let { SmallLine(it, ArtColors.Secondary) }
            }
            is AlertContent.FullTimeNamesOnly -> Text(
                text = stringResource(R.string.sports_live_alert_full_time_names, content.first.name, content.second.name),
                style = MaterialTheme.typography.titleMedium,
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
                    style = MaterialTheme.typography.titleMedium,
                    color = ArtColors.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ScoreLine(first: AlertSide, second: AlertSide) {
    val firstScore = first.score ?: return
    val secondScore = second.score ?: return
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        SideLogo(first, 32.dp)
        Text(
            text = stringResource(R.string.sports_live_alert_score_line, first.short, firstScore, secondScore, second.short),
            style = MaterialTheme.typography.headlineSmall.sportsLiveBold().copy(fontFeatureSettings = "tnum"),
            color = ArtColors.Text,
            maxLines = 1
        )
        SideLogo(second, 32.dp)
    }
}

@Composable
private fun SideLogo(side: AlertSide, size: Dp) {
    SportsLiveTeamLogo(
        logo = side.logo,
        short = side.short,
        px = TeamLogoUrls.CARD_PX,
        modifier = Modifier.size(size),
        letters = 2,
        fontSize = 12.sp
    )
}

@Composable
private fun SmallLine(text: String, color: Color) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun StarIcon() {
    Icon(imageVector = Icons.Rounded.Star, contentDescription = null, tint = FOLLOWED_STAR_COLOR, modifier = Modifier.size(18.dp))
}

private val PANEL_BORDER = Color(0xFF1E2632)

/** Toasts hide after this long (the design's 8 s). */
private const val TOAST_MS = 8_000L

/** The prompt closes by itself after this long, as Later (the design's 15 s). */
private const val PROMPT_MS = 15_000L
