package com.nuvio.tv.ui.screens.sportslive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.scores.PlayerCard
import com.nuvio.tv.core.sportslive.scores.PlayerGame
import com.nuvio.tv.core.sportslive.scores.PlayerStat
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Player card (#63, `PlayerCard.dc.html`): headshot (letters until it loads), name, position and
 * number, team, bio line; the season's key stats as tiles; the last 5 games as short lines (date,
 * opponent, result, key stats), behind Reveal while scores are hidden. No tables. Back returns.
 */
@Composable
fun SportsLivePlayerCardScreen(
    viewModel: SportsLivePlayerCardViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val currentOnBack by rememberUpdatedState(onBack)
    val target = state.target
    if (target == null) {
        // Nothing to show (the app was restored without the request): back to where it was opened.
        LaunchedEffect(Unit) { currentOnBack() }
        return
    }
    BackHandler { currentOnBack() }

    val revealFocus = remember { FocusRequester() }
    LaunchedEffect(state.showsReveal) {
        if (!state.showsReveal) return@LaunchedEffect
        withFrameNanos { }
        runCatching { revealFocus.requestFocus() }
    }

    val card = state.card
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .padding(horizontal = NuvioTheme.spacing.xxxl, vertical = NuvioTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl)
    ) {
        PlayerHeader(card = card, fallbackName = target.name, fallbackTeam = target.team)
        Column(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl)) {
            when {
                state.status == PlayerCardStatus.WAITING_REVEAL -> HiddenBlock(
                    message = stringResource(R.string.sports_live_player_hidden),
                    button = stringResource(R.string.sports_live_player_reveal_all),
                    focusRequester = revealFocus,
                    onReveal = viewModel::reveal
                )
                state.status == PlayerCardStatus.FAILED -> PlayerMessage(stringResource(R.string.sports_live_player_unavailable))
                card == null -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                else -> {
                    SeasonStats(card)
                    PlayerSectionTitle(stringResource(R.string.sports_live_player_last_games))
                    when {
                        !state.showsLastGames -> HiddenBlock(
                            message = stringResource(R.string.sports_live_player_last_games_hidden),
                            button = stringResource(R.string.sports_live_player_reveal_last_games),
                            focusRequester = revealFocus,
                            onReveal = viewModel::reveal
                        )
                        card.lastGames.isEmpty() -> PlayerMessage(stringResource(R.string.sports_live_player_no_games))
                        else -> Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                            card.lastGames.forEach { GameLine(it) }
                        }
                    }
                }
            }
        }
        Text(
            text = stringResource(R.string.sports_live_player_keys),
            style = MaterialTheme.typography.bodySmall,
            color = NuvioTheme.colors.TextTertiary
        )
    }
}

@Composable
private fun PlayerHeader(card: PlayerCard?, fallbackName: String, fallbackTeam: String?) {
    val bio = card?.bio
    val name = bio?.name ?: fallbackName
    val separator = stringResource(R.string.sports_live_game_info_separator)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NuvioTheme.radii.lg))
            .background(NuvioTheme.colors.BackgroundCard)
            .padding(NuvioTheme.spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xl),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(HEADSHOT_SIZE)
                .clip(CircleShape)
                .background(NuvioTheme.colors.SurfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = playerInitials(name),
                style = MaterialTheme.typography.headlineMedium.sportsLiveBold(),
                color = NuvioTheme.colors.TextSecondary
            )
            bio?.headshot?.let { url ->
                AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)) {
            Text(
                name,
                style = MaterialTheme.typography.displaySmall.sportsLiveBold(),
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val role = listOfNotNull(bio?.position, bio?.jersey?.let { stringResource(R.string.sports_live_player_jersey, it) })
            if (role.isNotEmpty()) {
                Text(role.joinToString(separator), style = MaterialTheme.typography.titleLarge, color = NuvioTheme.colors.TextSecondary, maxLines = 1)
            }
            (bio?.team ?: fallbackTeam)?.let { team ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
                    bio?.teamLogo?.let { logo ->
                        AsyncImage(model = logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(28.dp))
                    }
                    Text(team, style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.TextPrimary, maxLines = 1)
                }
            }
            bio?.details?.takeIf { it.isNotEmpty() }?.let { details ->
                Text(details.joinToString(separator), style = MaterialTheme.typography.bodyMedium, color = NuvioTheme.colors.TextTertiary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SeasonStats(card: PlayerCard) {
    if (card.season.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)) {
        PlayerSectionTitle(card.seasonTitle ?: stringResource(R.string.sports_live_player_season))
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
            card.season.forEach { StatTile(it) }
        }
    }
}

@Composable
private fun StatTile(stat: PlayerStat) {
    Column(
        modifier = Modifier
            .width(STAT_TILE_WIDTH)
            .clip(RoundedCornerShape(NuvioTheme.radii.md))
            .background(NuvioTheme.colors.BackgroundCard)
            .padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm)
    ) {
        Text(stat.label, style = MaterialTheme.typography.labelMedium, color = NuvioTheme.colors.TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            stat.value,
            style = MaterialTheme.typography.headlineMedium.sportsLiveBold().copy(fontFeatureSettings = "tnum"),
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1
        )
    }
}

/** "Sun 16 · @ BKN · W 135–123 · 37 PTS · 5 REB · 1 AST", in aligned parts. */
@Composable
private fun GameLine(game: PlayerGame) {
    val dayFormat = remember { DateTimeFormatter.ofPattern("EEE d", Locale.getDefault()) }
    val separator = stringResource(R.string.sports_live_game_info_separator)
    val statFormat = stringResource(R.string.sports_live_game_performer_stat)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NuvioTheme.radii.md))
            .background(NuvioTheme.colors.BackgroundCard)
            .padding(horizontal = NuvioTheme.spacing.lg, vertical = NuvioTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            game.startMs?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(dayFormat) }.orEmpty(),
            style = MaterialTheme.typography.titleSmall,
            color = NuvioTheme.colors.TextTertiary,
            maxLines = 1,
            modifier = Modifier.width(LINE_DATE_WIDTH)
        )
        Text(
            game.atVs?.let { stringResource(R.string.sports_live_player_opponent, it, game.opponent) } ?: game.opponent,
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(LINE_OPPONENT_WIDTH)
        )
        Text(
            if (game.result != null && game.score != null) String.format(statFormat, game.result, game.score) else game.result ?: game.score.orEmpty(),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1,
            modifier = Modifier.width(LINE_RESULT_WIDTH)
        )
        Text(
            game.line.joinToString(separator) { String.format(statFormat, it.value, it.label) },
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            color = NuvioTheme.colors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun HiddenBlock(message: String, button: String, focusRequester: FocusRequester, onReveal: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)) {
        PlayerMessage(message)
        SportsLivePaneButton(onClick = onReveal, modifier = Modifier.focusRequester(focusRequester)) {
            Text(button, maxLines = 1)
        }
    }
}

@Composable
private fun PlayerMessage(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, color = NuvioTheme.colors.TextSecondary)
}

@Composable
private fun PlayerSectionTitle(text: String) {
    Text(
        text = text.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 2.sp),
        color = NuvioTheme.colors.TextTertiary,
        maxLines = 1
    )
}

/** "Joel Embiid" → "JE"; one name → its first letter. */
internal fun playerInitials(name: String): String =
    name.split(' ').filter { it.isNotBlank() }.let { parts ->
        listOfNotNull(parts.firstOrNull(), parts.drop(1).lastOrNull()).joinToString("") { it.take(1).uppercase(Locale.getDefault()) }
    }

private val HEADSHOT_SIZE = 128.dp
private val STAT_TILE_WIDTH = 150.dp
private val LINE_DATE_WIDTH = 90.dp
private val LINE_OPPONENT_WIDTH = 140.dp
private val LINE_RESULT_WIDTH = 170.dp
