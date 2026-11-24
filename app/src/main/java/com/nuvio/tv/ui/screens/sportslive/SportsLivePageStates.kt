package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens

/**
 * What the Game, Team and Player pages show when their load failed (#97): the shared state card, left aligned under
 * the page header, with **Try again** focused. While the retry runs ([retrying]) the button reads "Trying..." and
 * does nothing; a second failure leaves the card as it is. A page's own Back needs no button of its own.
 */
@Composable
internal fun SportsLivePageFailure(
    title: String,
    retrying: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    /** The page's own requester for Try again, so its header's Down can lead here (review R2). */
    focus: FocusRequester = remember { FocusRequester() },
    /**
     * #461: whether Try again takes focus as the card appears ([SportsLivePageFocus.failureTakesFocus]): only while nothing
     * on the page holds focus yet. A load failing later never pulls focus from where the user is; Down reaches it.
     */
    takeFocus: Boolean = true,
    /** #461: Try again gained or lost focus (the page moves focus on recovery only if it was here). */
    onFocused: (Boolean) -> Unit = {}
) {
    // After a frame, once the button is placed (review R2: a request in the first frame was lost to the header's).
    val takes = androidx.compose.runtime.rememberUpdatedState(takeFocus)
    LaunchedEffect(focus) {
        withFrameNanos { }
        if (takes.value) runCatching { focus.requestFocus() }
    }
    SportsLiveStateCard(
        title = title,
        line = stringResource(R.string.sports_live_page_failed_line),
        primary = SportsLiveStateAction(
            label = stringResource(if (retrying) R.string.sports_live_state_trying else R.string.sports_live_state_try_again),
            onClick = { if (!retrying) onRetry() }
        ),
        problem = true,
        modifier = modifier,
        primaryModifier = Modifier.focusRequester(focus).onFocusChanged { onFocused(it.isFocused) }
    )
}

/** #461: the failure card's focus rules (principle 3: nothing moves under focus), shared by the Game and Team pages. */
internal object SportsLivePageFocus {
    /** Try again takes focus by itself only when nothing on the page holds focus yet (its first focus). */
    fun failureTakesFocus(pageHasFocus: Boolean): Boolean = !pageHasFocus

    /** When the failure clears, focus moves to the header only if it was on the card that went away. */
    fun recoveryMovesFocus(cardHadFocus: Boolean): Boolean = cardHadFocus
}

/** A skeleton block in the state card's placeholder colour: no shimmer, nothing moves (principle 3). */
@Composable
private fun SkeletonBlock(modifier: Modifier) {
    Box(modifier = modifier.background(SportsLiveTokens.State.Skeleton, SportsLiveTokens.Radius.RowShape))
}

/** The Game page's body while it loads: two stat panels (the hero above it is already drawn from the request). */
@Composable
internal fun SportsLiveGameSkeleton() {
    Row(horizontalArrangement = Arrangement.spacedBy(SportsLiveTokens.Size.RowGap)) {
        repeat(2) { SkeletonBlock(Modifier.width(360.dp).height(200.dp)) }
    }
}

/** [count] rows at the soon-row height, in the list column: the Team page's body while it loads. */
@Composable
internal fun SportsLiveRowsSkeleton(count: Int = SportsLiveTokens.State.SkeletonRows, width: Dp = SportsLiveTokens.Size.ListWidth) {
    Column(modifier = Modifier.width(width), verticalArrangement = Arrangement.spacedBy(SportsLiveTokens.Size.RowGap)) {
        repeat(count) { SkeletonBlock(Modifier.fillMaxWidth().height(SportsLiveTokens.Size.RowSoon)) }
    }
}

/** The Player card's body while it loads: a row of four stat tiles, then table rows. */
@Composable
internal fun SportsLivePlayerSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(SportsLiveTokens.Size.RowGap)) {
            repeat(4) { SkeletonBlock(Modifier.width(160.dp).height(80.dp)) }
        }
        SportsLiveRowsSkeleton(width = 4 * 160.dp + 3 * SportsLiveTokens.Size.RowGap)
    }
}
