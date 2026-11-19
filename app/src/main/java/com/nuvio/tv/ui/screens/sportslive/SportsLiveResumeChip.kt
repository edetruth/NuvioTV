package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.GameStatus
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveResumeOffer
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveHeaderControl
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens

/** Live now's Resume chip (#148), after Back from a paused game. */
internal sealed interface SportsLiveResumeChip {
    val item: SportsLiveItem

    /** Still live: "▶ Resume <game> · Live". */
    data class Live(override val item: SportsLiveItem, val stream: Stream) : SportsLiveResumeChip

    /** Ended meanwhile ([status]: ESPN's final): "<game> · Final 2–3", spoiler setting applied. */
    data class Final(override val item: SportsLiveItem, val status: GameStatus, val event: ScoreSnapshot) : SportsLiveResumeChip
}

/** What OK on the chip does. */
internal sealed interface SportsLiveResumeOk {
    /** The same game and link again, at the live edge: a fresh player, never the paused position. */
    data class RejoinLiveEdge(val item: SportsLiveItem, val stream: Stream) : SportsLiveResumeOk

    /** The game is over: its Game page. */
    data class OpenGame(val item: SportsLiveItem, val event: ScoreSnapshot) : SportsLiveResumeOk
}

/**
 * The chip for [offer] at [nowMs], or null: none offered, expired ([SportsLiveResumeOffer.expiredAt]), or the
 * game neither listed in Live now ([listed], its current card) nor ended. [statusOf] is the screen's one
 * status source, so the chip never says Live where the Scoreboard says Final.
 */
internal fun sportsLiveResumeChip(
    offer: SportsLiveResumeOffer?,
    listed: SportsLiveItem?,
    statusOf: (SportsLiveItem) -> GameStatus,
    nowMs: Long
): SportsLiveResumeChip? {
    if (offer == null || offer.expiredAt(nowMs)) return null
    val item = listed ?: offer.item
    val status = statusOf(item)
    val event = when (status) {
        is GameStatus.Final -> status.event
        is GameStatus.JustEnded -> status.ended.event?.takeUnless { status.estimate }
        else -> null
    }
    return when {
        event != null -> SportsLiveResumeChip.Final(item, status, event)
        // Gone from Live now without a final (its addon dropped it): nothing left to rejoin.
        listed == null -> null
        else -> SportsLiveResumeChip.Live(item, offer.stream)
    }
}

internal fun sportsLiveResumeOk(chip: SportsLiveResumeChip): SportsLiveResumeOk = when (chip) {
    is SportsLiveResumeChip.Live -> SportsLiveResumeOk.RejoinLiveEdge(chip.item, chip.stream)
    is SportsLiveResumeChip.Final -> SportsLiveResumeOk.OpenGame(chip.item, chip.event)
}

/**
 * Live now's Resume row (#148), the first row above the games: "▶ [label]" (OK [onOpen]) and a round ✕
 * ([onDismiss], as Back on either). [live] draws the play glyph; an ended game's chip opens its Game page.
 * Up goes to [upFocusRequester] (the header), Down to [downFocusRequester]. Removed while focused (expired,
 * dismissed, the game gone), focus goes to [downFocusRequester].
 */
@Composable
internal fun SportsLiveResumeRow(
    label: String,
    live: Boolean,
    focusRequester: FocusRequester,
    upFocusRequester: FocusRequester?,
    downFocusRequester: FocusRequester?,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val closeFocusRequester = remember { FocusRequester() }
    fun dismiss() {
        runCatching { downFocusRequester?.requestFocus() }
        onDismiss()
    }
    fun stop(requester: FocusRequester) = Modifier
        .focusRequester(requester)
        .focusProperties {
            upFocusRequester?.let { up = it }
            downFocusRequester?.let { down = it }
        }
        // Back on the chip or its ✕ dismisses it.
        .onPreviewKeyEvent { event ->
            val back = event.key == Key.Back || event.key == Key.Escape
            if (back && event.type == KeyEventType.KeyUp) dismiss()
            back
        }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SportsLiveTokens.Size.ControlGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SportsLiveHeaderControl(onClick = onOpen, round = false, modifier = stop(focusRequester).weight(1f, fill = false)) {
            if (live) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = SportsLiveTokens.Colors.TextPrimary,
                    modifier = Modifier.size(SportsLiveTokens.Size.ControlIcon)
                )
            }
            Text(
                text = label,
                style = SportsLiveTokens.style(SportsLiveTokens.Type.ControlSelected),
                color = SportsLiveTokens.Colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        SportsLiveHeaderControl(onClick = ::dismiss, modifier = stop(closeFocusRequester)) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.sports_live_resume_dismiss),
                tint = SportsLiveTokens.Colors.TextSecondary,
                modifier = Modifier.size(SportsLiveTokens.Size.ControlIcon)
            )
        }
    }
}
