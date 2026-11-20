package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.SportsLiveDisplayText
import com.nuvio.tv.core.sportslive.SportsLiveStreamThresholds
import com.nuvio.tv.ui.screens.player.PlayerOverlayButton
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButton
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButtonRole
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveFocus
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.delay

/** The starting card's buttons (#138), in order; the first one is focused (or selected) first. Back is the Back key's (V2). */
enum class SportsLiveStartingAction { TRY_NEXT_LINK, SOURCES }

/** "0:07": the time a link has been starting, once it is [SportsLiveStreamThresholds.STARTING_ELAPSED_AFTER_MS] or more. */
internal fun sportsLiveStartingElapsed(elapsedMs: Long): String? {
    if (elapsedMs < SportsLiveStreamThresholds.STARTING_ELAPSED_AFTER_MS) return null
    val seconds = elapsedMs / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/**
 * The card shown while a sports link starts (#138, V2 `Player.dc.html` 7): no modal, no white slab. A spinner
 * and "Starting ‹game›", then "Link N of M · source · 0:07" (the elapsed time after a few seconds), then
 * **Try next link** (PRIMARY) and **Sources**. Its owner places it (the full player: bottom left).
 *
 * With [selected] null the buttons are real, focusable buttons side by side and Try next link takes focus
 * (the full player). With [selected] set the card draws that button focused and takes no focus: its owner
 * moves the selection with ▲ ▼ (a dual screen pane), so the buttons stack. [linkCount] 0 leaves "of M" out.
 * [onAction] is only called by the focusable buttons; a key-driven owner acts on [selected] itself.
 */
@Composable
fun SportsLiveStartingCard(
    game: String?,
    linkNumber: Int,
    linkCount: Int,
    sourceName: String?,
    startedAtMs: Long,
    onAction: (SportsLiveStartingAction) -> Unit,
    modifier: Modifier = Modifier,
    selected: SportsLiveStartingAction? = null,
    compact: Boolean = false,
    /** False: the game, link and time only (a dual pane that is not highlighted). */
    showActions: Boolean = true
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAtMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val link = if (linkCount >= linkNumber) {
        stringResource(R.string.sports_live_starting_link_short_of, linkNumber, linkCount)
    } else {
        stringResource(R.string.sports_live_starting_link_short, linkNumber)
    }
    // The source by its clean name: no emoji or symbols (V2).
    val source = sourceName?.let { SportsLiveDisplayText.label(it) }
    val line = listOfNotNull(link, source, sportsLiveStartingElapsed(now - startedAtMs))
        .joinToString(stringResource(R.string.sports_live_game_info_separator))
    val shape = RoundedCornerShape(SportsLiveTokens.Alert.Radius)
    Column(
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
        modifier = modifier
            .widthIn(max = if (compact) COMPACT_WIDTH else SportsLiveTokens.Alert.Width)
            .clip(shape)
            .background(Colors.SurfaceRaised.copy(alpha = SportsLiveTokens.Alert.FILL_ALPHA))
            .border(1.dp, SportsLiveTokens.Alert.Border, shape)
            .padding(SportsLiveTokens.Alert.Padding)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StartingSpinner()
            Text(
                text = game?.takeIf(String::isNotBlank)?.let { stringResource(R.string.sports_live_starting_game, it) }
                    ?: stringResource(R.string.sports_live_starting_untitled),
                style = SportsLiveTokens.style(SportsLiveTokens.Toast.Title),
                color = Colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = line,
            style = SportsLiveTokens.style(SportsLiveTokens.Toast.Line),
            color = Colors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!showActions) return@Column
        val labels = mapOf(
            SportsLiveStartingAction.TRY_NEXT_LINK to stringResource(R.string.sports_live_try_next_link),
            SportsLiveStartingAction.SOURCES to stringResource(R.string.sources_title)
        )
        Spacer(modifier = Modifier.height(4.dp))
        if (selected == null) {
            val firstFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
            Row(horizontalArrangement = Arrangement.spacedBy(SportsLiveTokens.Size.ButtonGap)) {
                SportsLiveStartingAction.entries.forEach { action ->
                    val primary = action == SportsLiveStartingAction.TRY_NEXT_LINK
                    SportsLiveButton(
                        onClick = { onAction(action) },
                        role = if (primary) SportsLiveButtonRole.PRIMARY else SportsLiveButtonRole.SECONDARY,
                        modifier = if (primary) Modifier.focusRequester(firstFocus) else Modifier
                    ) {
                        Text(text = labels.getValue(action), style = SportsLiveTokens.style(SportsLiveTokens.Type.Button), maxLines = 1)
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SportsLiveStartingAction.entries.forEach { action ->
                    SportsLiveDrawnButton(
                        text = labels.getValue(action),
                        primary = action == SportsLiveStartingAction.TRY_NEXT_LINK,
                        focused = action == selected,
                        height = COMPACT_BUTTON_HEIGHT,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/** A small turning arc: the link is starting. */
@Composable
private fun StartingSpinner() {
    val turn by rememberInfiniteTransition(label = "startingSpinner").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
        label = "startingSpinnerTurn"
    )
    Canvas(modifier = Modifier.size(14.dp)) {
        drawArc(
            color = Colors.TextSecondary,
            startAngle = turn,
            sweepAngle = 270f,
            useCenter = false,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

/**
 * A Sports & Live button drawn for an owner that moves the choice with its own keys (a dual pane, the kickoff
 * prompt over the player, #173). It never takes focus; [focused] draws the V2 focus (#93) as [SportsLiveButton]
 * does, the white ring and the lift. [primary] is filled white, the rest outlined. Never a white slab.
 */
@Composable
internal fun SportsLiveDrawnButton(
    text: String,
    primary: Boolean,
    focused: Boolean,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    height: Dp = SportsLiveTokens.Size.ButtonHeight
) {
    val shape = SportsLiveTokens.Radius.ButtonShape
    val gap = if (primary && focused) SportsLiveTokens.Focus.PrimaryRingGap else 0.dp
    val fill = when {
        primary -> Colors.PrimaryFill
        focused -> SportsLiveFocus.focusedFill(Color.Transparent)
        else -> Color.Transparent
    }
    val content = if (primary) Colors.OnPrimary else Colors.TextPrimary
    val lift = if (focused) SportsLiveTokens.Focus.LIFT else 1f
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = lift
                scaleY = lift
            }
            .then(
                when {
                    focused -> Modifier.border(SportsLiveTokens.Focus.RingWidth, Colors.FocusWhite, shape)
                    primary -> Modifier
                    else -> Modifier.border(1.dp, Colors.OutlineStrong, shape)
                }
            )
            .padding(gap)
            .clip(shape)
            .background(fill)
            .heightIn(min = height - gap * 2)
            .padding(horizontal = 15.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(imageVector = icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Text(text = text, style = SportsLiveTokens.style(SportsLiveTokens.Type.Button), color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private val COMPACT_WIDTH = 300.dp
private val COMPACT_BUTTON_HEIGHT = 28.dp

/**
 * "No other live game is playing right now" (#138 follow-up): Next live game found every other game tried and
 * failed this session. **Try again** (focused first) forgets the tried games and looks again; **Back to Live Now**
 * leaves the player. Never a loop back to a dead game.
 */
@Composable
fun SportsLiveNoLiveGameCard(
    onTryAgain: () -> Unit,
    onBackToLiveNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .widthIn(max = 420.dp)
            .clip(RoundedCornerShape(NuvioTheme.radii.md))
            .background(Color.Black.copy(alpha = 0.85f))
            .padding(horizontal = 24.dp, vertical = 20.dp)
    ) {
        Text(
            text = stringResource(R.string.sports_live_no_other_live_game),
            style = MaterialTheme.typography.titleMedium,
            color = NuvioTheme.colors.TextPrimary
        )
        PlayerOverlayButton(
            text = stringResource(R.string.sports_live_state_try_again),
            onClick = onTryAgain,
            primary = true,
            modifier = Modifier.fillMaxWidth().focusRequester(firstFocus)
        )
        PlayerOverlayButton(
            text = stringResource(R.string.sports_live_back_to_live_now),
            onClick = onBackToLiveNow,
            primary = false,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
