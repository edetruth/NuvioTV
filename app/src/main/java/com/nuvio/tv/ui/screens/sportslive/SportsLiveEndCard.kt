package com.nuvio.tv.ui.screens.sportslive

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.GameEndReason
import com.nuvio.tv.core.sportslive.GameEndStrings
import com.nuvio.tv.core.sportslive.SportsLiveGameEnd
import com.nuvio.tv.core.sportslive.displayName
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButton
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButtonRole
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import kotlinx.coroutines.delay

/**
 * The end-of-game card (#161) for [item], which ended ([reason]): "Final · ‹score›" under Scores Show (else
 * "Game over"), Up next [next] with its countdown ([countdownEndsAtMs] while it runs), or, with nothing else
 * live, the soonest [upcoming] game. [toastOnly]: "When a game ends: Stay" over a stream still playing, a
 * small toast and no card. [boxScore]: a strict ESPN match, so Box score is offered.
 */
data class SportsLiveEndCard(
    val item: SportsLiveItem,
    val reason: GameEndReason,
    val eventId: String?,
    val snapshot: ScoreSnapshot?,
    val mode: ScoresMode?,
    val next: SportsLiveItem?,
    val upcoming: SportsLiveItem?,
    val boxScore: Boolean,
    val streamStopped: Boolean,
    val toastOnly: Boolean = false,
    /** The countdown runs once the card shows ([SportsLiveEndCountdown.runs]). */
    val countdown: Boolean = false,
    val countdownEndsAtMs: Long? = null,
    val upcomingReminded: Boolean = false,
    /** It would have moved by itself, but nobody pressed a key for long: "Are you still watching?" (SportsLiveStillWatching). */
    val stillWatching: Boolean = false
)

/** The card's buttons, in the order shown; the first one takes focus. */
enum class SportsLiveEndAction { WATCH_NEXT, REMIND, BACK_TO_LIVE_NOW, BOX_SCORE, STAY }

/**
 * The card's buttons (#161): Watch next (focused), Box score, Stay; with nothing else live, Remind me (when an
 * upcoming game is named) and Back to Live Now instead of Watch next. A dual pane ([pane]) has Watch next and
 * Stay only.
 */
fun sportsLiveEndActions(card: SportsLiveEndCard, pane: Boolean = false): List<SportsLiveEndAction> = buildList {
    if (card.next != null) {
        add(SportsLiveEndAction.WATCH_NEXT)
    } else if (!pane) {
        if (card.upcoming != null) add(SportsLiveEndAction.REMIND)
        add(SportsLiveEndAction.BACK_TO_LIVE_NOW)
    }
    if (!pane && card.boxScore) add(SportsLiveEndAction.BOX_SCORE)
    add(SportsLiveEndAction.STAY)
}

/**
 * The card itself, a calm V2 card over the last frame (#161), never an error. With [selected] null its buttons
 * are focusable and Watch next takes focus (the full player): any key stops the countdown ([onAnyKey]) and still
 * acts; Back (the player's, [SportsLiveZapViewModel.endCardOnBack]) stops it first and then closes the card (Stay). With [selected] set (a dual pane), its owner moves the
 * selection with the keys and the card takes no focus; [pane] draws it smaller, with [showActions] only on the
 * highlighted pane.
 */
@Composable
fun SportsLiveEndCardView(
    card: SportsLiveEndCard,
    onAction: (SportsLiveEndAction) -> Unit,
    onAnyKey: () -> Unit,
    modifier: Modifier = Modifier,
    pane: Boolean = false,
    selected: SportsLiveEndAction? = null,
    showActions: Boolean = true
) {
    val title = endCardTitle(card)
    if (card.toastOnly) {
        EndToast(title = title, modifier = modifier)
        return
    }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(card.countdownEndsAtMs) {
        while (card.countdownEndsAtMs != null) {
            nowMs = System.currentTimeMillis()
            delay(250)
        }
    }
    val secondsLeft = card.countdownEndsAtMs?.let { ((it - nowMs + 999) / 1_000).toInt().coerceAtLeast(0) }
    val upcomingLabel = card.upcoming?.let { rememberSportsLiveStateLabeler(SportsLiveTab.UPCOMING)(it.copy(isLive = false)) }
    val shape = RoundedCornerShape(SportsLiveTokens.Alert.Radius)
    val focusable = selected == null && showActions
    val focusManager = LocalFocusManager.current
    Column(
        verticalArrangement = Arrangement.spacedBy(if (pane) 6.dp else 10.dp),
        modifier = modifier
            .widthIn(max = if (pane) 320.dp else 520.dp)
            .clip(shape)
            .background(SportsLiveTokens.Colors.Surface.copy(alpha = 0.94f))
            .border(1.dp, SportsLiveTokens.Colors.Outline, shape)
            .padding(horizontal = if (pane) 14.dp else 24.dp, vertical = if (pane) 12.dp else 20.dp)
            .onPreviewKeyEvent { event ->
                // Any key stops the countdown and still does what it does.
                if (focusable && event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && card.countdownEndsAtMs != null &&
                    event.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_BACK
                ) {
                    onAnyKey()
                }
                false
            }
            .onKeyEvent { event ->
                // The D-pad stays on the card (moving between its buttons), never zapping or opening the panel under it.
                if (!focusable) return@onKeyEvent false
                val down = event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (down) focusManager.moveFocus(FocusDirection.Left)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (down) focusManager.moveFocus(FocusDirection.Right)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true
                    else -> false
                }
            }
    ) {
        Text(
            text = title,
            style = SportsLiveTokens.style(if (pane) SportsLiveTokens.Type.PanelTitle else SportsLiveTokens.Type.PageTitle),
            color = SportsLiveTokens.Colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val next = card.next
        if (next != null) {
            Text(
                text = stringResource(R.string.sports_live_end_up_next),
                style = SportsLiveTokens.style(SportsLiveTokens.Type.SectionHeader),
                color = SportsLiveTokens.Colors.TextMuted
            )
            Text(
                text = next.displayName,
                style = SportsLiveTokens.style(if (pane) SportsLiveTokens.Type.PanelLine else SportsLiveTokens.Type.RowName),
                color = SportsLiveTokens.Colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val waiting = when {
                secondsLeft != null -> stringResource(R.string.sports_live_end_switching_in, secondsLeft)
                card.stillWatching -> stringResource(R.string.sports_live_end_still_watching)
                pane && !card.countdown -> stringResource(R.string.sports_live_end_ok_to_switch)
                else -> null
            }
            waiting?.let {
                Text(
                    text = it,
                    style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
                    color = SportsLiveTokens.Colors.TextSecondary
                )
            }
        } else {
            Text(
                text = card.upcoming?.let { upcoming ->
                    stringResource(R.string.sports_live_end_nothing_live_next, upcoming.displayName, upcomingLabel.orEmpty()).trim()
                } ?: stringResource(R.string.sports_live_end_nothing_live),
                style = SportsLiveTokens.style(if (pane) SportsLiveTokens.Type.PanelLine else SportsLiveTokens.Type.RowName),
                color = SportsLiveTokens.Colors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!showActions) return@Column
        val actions = sportsLiveEndActions(card, pane)
        val labels = mapOf(
            SportsLiveEndAction.WATCH_NEXT to stringResource(R.string.sports_live_end_watch_next),
            SportsLiveEndAction.REMIND to stringResource(if (card.upcomingReminded) R.string.sports_live_cancel_reminder else R.string.sports_live_remind_me),
            SportsLiveEndAction.BACK_TO_LIVE_NOW to stringResource(R.string.sports_live_back_to_live_now),
            SportsLiveEndAction.BOX_SCORE to stringResource(R.string.sports_live_end_box_score),
            SportsLiveEndAction.STAY to stringResource(R.string.sports_live_end_stay)
        )
        if (selected == null) {
            val firstFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                runCatching { firstFocus.requestFocus() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(SportsLiveTokens.Size.ButtonGap)) {
                actions.forEachIndexed { index, action ->
                    SportsLiveButton(
                        onClick = { onAction(action) },
                        role = if (index == 0) SportsLiveButtonRole.PRIMARY else SportsLiveButtonRole.SECONDARY,
                        modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier
                    ) {
                        Text(labels.getValue(action), style = SportsLiveTokens.style(SportsLiveTokens.Type.Button), maxLines = 1)
                    }
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                actions.forEach { action ->
                    val isSelected = action == selected
                    val chip = SportsLiveTokens.Radius.ButtonShape
                    Text(
                        text = labels.getValue(action),
                        style = SportsLiveTokens.style(SportsLiveTokens.Type.Control),
                        color = if (isSelected) SportsLiveTokens.Colors.OnPrimary else SportsLiveTokens.Colors.TextPrimary,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(chip)
                            .background(if (isSelected) SportsLiveTokens.Colors.PrimaryFill else Color.Transparent)
                            .border(1.dp, if (isSelected) SportsLiveTokens.Colors.FocusWhite else SportsLiveTokens.Colors.OutlineStrong, chip)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun endCardTitle(card: SportsLiveEndCard): String {
    val strings = GameEndStrings(
        gameOver = stringResource(R.string.sports_live_end_game_over),
        finalScore = stringResource(R.string.sports_live_end_final_score)
    )
    return remember(card.item, card.snapshot, card.mode, strings) { SportsLiveGameEnd.title(card.item, card.snapshot, card.mode, strings) }
}

/** "Final · ‹score›" alone (Stay, over a postgame still playing): a toast, no buttons, no focus. */
@Composable
private fun EndToast(title: String, modifier: Modifier) {
    val shape = RoundedCornerShape(SportsLiveTokens.Alert.Radius)
    Column(
        modifier = modifier
            .widthIn(max = 520.dp)
            .clip(shape)
            .background(SportsLiveTokens.Colors.Surface.copy(alpha = SportsLiveTokens.Alert.FILL_ALPHA))
            .border(1.dp, SportsLiveTokens.Alert.Border, shape)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = title,
            style = SportsLiveTokens.style(SportsLiveTokens.Type.PanelTitle),
            color = SportsLiveTokens.Colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = stringResource(R.string.sports_live_end_staying),
            style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
            color = SportsLiveTokens.Colors.TextSecondary,
            maxLines = 1
        )
    }
}
