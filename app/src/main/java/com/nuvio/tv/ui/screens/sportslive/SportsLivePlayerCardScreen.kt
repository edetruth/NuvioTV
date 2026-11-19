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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.scores.PlayerCard
import com.nuvio.tv.core.sportslive.scores.PlayerCards
import com.nuvio.tv.core.sportslive.scores.PlayerGame
import com.nuvio.tv.core.sportslive.scores.PlayerStat
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButton
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButtonRole
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors
import com.nuvio.tv.ui.util.dpadRepeatThrottle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Player card (#63; V2 #134, `TeamPage-PlayerCard.dc.html`): a header with no card (the headshot only when
 * ESPN has one, name, position and number, team and bio line); up to 6 season tiles that never overflow and take
 * no focus; the last 5 games as focusable 64 px rows ("@ Lions", the Team page's wording) that scroll with ↓.
 * No Back button and no key hints: the Back key returns, and the page that opened the card puts focus back on
 * the leader or performer. Spoilers: Hide until focused shows each game's result and line while its row has
 * focus; Off (or a spoiler-safe opener, #58) keeps them behind Reveal.
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
    val firstGameFocus = remember { FocusRequester() }
    val card = state.card
    // Under Hide until focused a focused row shows its result, so the first game never takes focus by itself:
    // the first ↓ moves onto it.
    val hasGames = card != null && card.lastGames.isNotEmpty() && !state.showsReveal && !state.revealsOnFocus
    // Reveal while it shows, else the first game.
    LaunchedEffect(state.showsReveal, hasGames) {
        withFrameNanos { }
        runCatching {
            when {
                state.showsReveal -> revealFocus.requestFocus()
                hasGames -> firstGameFocus.requestFocus()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Colors.Background)
            .padding(start = PAGE_SIDE, end = PAGE_SIDE, top = PAGE_TOP)
    ) {
        PlayerHeader(card = card, fallbackName = target.name, fallbackTeam = target.team)
        Spacer(Modifier.height(20.dp))
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().dpadRepeatThrottle(),
            verticalArrangement = Arrangement.spacedBy(SportsLiveTokens.Size.RowGap),
            // Room for the focused row's lift and ring at both ends of the scroll.
            contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 24.dp)
        ) {
            when {
                state.status == PlayerCardStatus.WAITING_REVEAL -> item(key = "hidden") {
                    HiddenBlock(
                        message = stringResource(R.string.sports_live_player_hidden),
                        button = stringResource(R.string.sports_live_player_reveal_all),
                        focusRequester = revealFocus,
                        onReveal = viewModel::reveal
                    )
                }
                state.status == PlayerCardStatus.FAILED -> item(key = "failed") {
                    SportsLivePageMessage(stringResource(R.string.sports_live_player_unavailable))
                }
                card == null -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                }
                else -> {
                    if (card.season.isNotEmpty()) {
                        item(key = "season_title") { SportsLivePageHeading(card.seasonTitle ?: stringResource(R.string.sports_live_player_season)) }
                        item(key = "season") { SeasonTiles(card.season) }
                    }
                    item(key = "last_title") {
                        SportsLivePageHeading(stringResource(R.string.sports_live_player_last_games), modifier = Modifier.padding(top = 12.dp))
                    }
                    when {
                        state.showsReveal -> item(key = "last_hidden") {
                            HiddenBlock(
                                message = stringResource(R.string.sports_live_player_last_games_hidden),
                                button = stringResource(R.string.sports_live_player_reveal_last_games),
                                focusRequester = revealFocus,
                                onReveal = viewModel::reveal
                            )
                        }
                        card.lastGames.isEmpty() -> item(key = "no_games") { SportsLivePageMessage(stringResource(R.string.sports_live_player_no_games)) }
                        else -> itemsIndexed(card.lastGames, key = { _, game -> game.eventId }) { index, game ->
                            GameRow(
                                game = game,
                                showsGame = state::showsGame,
                                modifier = if (index == 0) Modifier.focusRequester(firstGameFocus) else Modifier
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Board ⑤: the headshot only when ESPN has one (else the name moves left), the 52 px name, role, team line. */
@Composable
private fun PlayerHeader(card: PlayerCard?, fallbackName: String, fallbackTeam: String?) {
    val bio = card?.bio
    val name = bio?.name ?: fallbackName
    val separator = stringResource(R.string.sports_live_game_info_separator)
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // The headshot when ESPN has one; else nothing, never initials (#130).
        playerHeadshot(card)?.let { url ->
            AsyncImage(
                model = rememberSizedImage(url, HEADSHOT_SIZE, HEADSHOT_SIZE),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(HEADSHOT_SIZE).clip(CircleShape).background(Colors.SurfaceRaised)
            )
            Spacer(Modifier.width(18.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                name,
                style = SportsLiveTokens.style(SportsLiveTokens.Type.PageTitle),
                color = Colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val role = listOfNotNull(bio?.position, bio?.jersey?.let { stringResource(R.string.sports_live_player_jersey, it) })
            if (role.isNotEmpty()) {
                Text(role.joinToString(separator), style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta), color = Colors.TextSecondary, maxLines = 1)
            }
            val team = bio?.team ?: fallbackTeam
            val details = bio?.details.orEmpty()
            if (team != null || details.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    bio?.teamLogo?.let { logo ->
                        AsyncImage(model = rememberSizedImage(logo, 14.dp, 14.dp), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(14.dp))
                    }
                    team?.let {
                        Text(it, style = SportsLiveTokens.style(SportsLiveTokens.Type.Control).copy(fontWeight = FontWeight.SemiBold), color = Colors.TextPrimary, maxLines = 1)
                    }
                    if (details.isNotEmpty()) {
                        Text(
                            (if (team != null) separator else "") + details.joinToString(separator),
                            style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag),
                            color = Colors.TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** Board ⑥: up to 6 season tiles, 200 × 120 px, not focusable; a 22 px caps label over a 48 px tabular value. */
@Composable
private fun SeasonTiles(stats: List<PlayerStat>) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        stats.take(PlayerCards.MAX_SEASON_STATS).forEach { stat ->
            Column(
                modifier = Modifier
                    .width(TILE_WIDTH)
                    .height(TILE_HEIGHT)
                    .clip(SportsLiveTokens.Radius.RowShape)
                    .background(Colors.Surface)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                SportsLivePageHeading(stat.label)
                Text(
                    stat.value,
                    style = SportsLiveTokens.style(TILE_VALUE),
                    color = Colors.TextPrimary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip
                )
            }
        }
    }
}

/** Board ⑦: a last game, a focusable 64 px row: the date, "@ Lions" with its logo, the result, the stat line. */
@Composable
private fun GameRow(game: PlayerGame, showsGame: (Boolean) -> Boolean, modifier: Modifier) {
    val locale = Locale.getDefault()
    val dayFormat = remember(locale) { DateTimeFormatter.ofPattern("EEE d MMM", locale) }
    val separator = stringResource(R.string.sports_live_game_info_separator)
    val statFormat = stringResource(R.string.sports_live_game_performer_stat)
    var focused by remember { mutableStateOf(false) }
    val shown = showsGame(focused)
    SportsLivePageRow(height = GAME_ROW, modifier = modifier.onFocusChanged { focused = it.isFocused }) {
        Text(
            game.startMs?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(dayFormat) }.orEmpty(),
            style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
            color = Colors.TextSecondary,
            maxLines = 1,
            modifier = Modifier.width(DATE_WIDTH)
        )
        Row(modifier = Modifier.width(OPPONENT_WIDTH), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(LOGO), contentAlignment = Alignment.Center) {
                rememberTeamLogo(game.opponentLogo, px = 64)?.let { TeamLogoSlot(it.painter, it.shown, LOGO) }
            }
            val opponent = game.opponentName ?: game.opponent
            Text(
                game.atVs?.let { stringResource(R.string.sports_live_player_opponent, it, opponent) } ?: opponent,
                style = SportsLiveTokens.style(SportsLiveTokens.Type.RowName).copy(fontWeight = FontWeight.SemiBold),
                color = Colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        val win = game.result.equals("W", ignoreCase = true)
        Text(
            if (!shown) stringResource(R.string.sports_live_results_score_hidden)
            else if (game.result != null && game.score != null) String.format(statFormat, game.result, game.score)
            else game.result ?: game.score.orEmpty(),
            style = SportsLiveTokens.style(SportsLiveTokens.Type.RowName)
                .copy(fontFeatureSettings = "tnum", fontWeight = if (shown && win) FontWeight.Bold else FontWeight.Normal),
            color = when {
                !shown -> Colors.TextMuted
                win -> Colors.TextPrimary
                else -> Colors.TextSecondary
            },
            maxLines = 1,
            modifier = Modifier.width(RESULT_WIDTH)
        )
        if (shown) {
            Text(
                game.line.joinToString(separator) { String.format(statFormat, it.value, it.label) },
                style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta).copy(fontFeatureSettings = "tnum"),
                color = Colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun HiddenBlock(message: String, button: String, focusRequester: FocusRequester, onReveal: () -> Unit) {
    Column(
        modifier = Modifier
            .widthIn(max = 520.dp)
            .clip(SportsLiveTokens.Radius.RowShape)
            .background(Colors.Surface)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(message, style = SportsLiveTokens.style(SportsLiveTokens.Type.RowName), color = Colors.TextSecondary)
        SportsLiveButton(onClick = onReveal, role = SportsLiveButtonRole.PRIMARY, modifier = Modifier.focusRequester(focusRequester)) {
            Text(button, style = SportsLiveTokens.style(SportsLiveTokens.Type.Button), maxLines = 1, softWrap = false)
        }
    }
}

/** The Player card's headshot URL, or null when ESPN has none: the header then draws no circle (#130). */
internal fun playerHeadshot(card: PlayerCard?): String? = card?.bio?.headshot?.trim()?.takeIf { it.isNotEmpty() }

/** Headshot: 140 px. */
private val HEADSHOT_SIZE = 70.dp
/** Season tiles: 200 × 120 px; the value 48 px tabular. */
private val TILE_WIDTH = 100.dp
private val TILE_HEIGHT = 60.dp
private val TILE_VALUE = TextStyle(fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
/** Last 5 rows: 64 px. */
private val GAME_ROW = 32.dp
private val DATE_WIDTH = 84.dp
private val OPPONENT_WIDTH = 160.dp
private val RESULT_WIDTH = 96.dp
private val LOGO = 14.dp
