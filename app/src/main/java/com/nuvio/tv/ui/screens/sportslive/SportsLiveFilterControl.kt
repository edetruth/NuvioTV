package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveDisplayText
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveFocus
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors

/** One option of a [SportsLiveFilterControl]: its [value] (null: All) and [label]; [gold] for "★ My teams". */
data class SportsLiveFilterOption(val value: String?, val label: String, val gold: Boolean = false)

/** A key on a focused [SportsLiveFilterControl]. */
enum class SportsLiveFilterKey { LEFT, RIGHT, OK }

/** What a key on the filter control does beyond moving its cursor. */
sealed interface SportsLiveFilterAction {
    /** The cursor moved (or nothing changed): the control keeps focus. */
    data object Stay : SportsLiveFilterAction

    /** OK on an option not selected: it becomes the filter. */
    data class Select(val value: String?) : SportsLiveFilterAction

    /** OK on the selected option: into the list below. */
    data object OpenList : SportsLiveFilterAction

    /** Left on the first option or Right on the last: focus leaves the control that way. */
    data object ExitLeft : SportsLiveFilterAction
    data object ExitRight : SportsLiveFilterAction
}

/**
 * The per-tab filter in the header (V2): one focus stop. While it has focus, ← → move the cursor across
 * the options and OK selects the one under it; the [selected] option keeps its tinted fill until then.
 * Left past the first option and Right past the last leave the control; ↓ enters the list (the screen's
 * focus properties). Focus leaving puts the cursor back on the selection ([blurred]).
 */
data class SportsLiveFilterState(val values: List<String?>, val selected: String?, val cursor: String? = selected) {

    val cursorIndex: Int get() = values.indexOf(cursor).takeIf { it >= 0 } ?: values.indexOf(selected).coerceAtLeast(0)

    /** [key] pressed: the next state and what it does. */
    fun onKey(key: SportsLiveFilterKey): Pair<SportsLiveFilterState, SportsLiveFilterAction> {
        if (values.isEmpty()) return this to SportsLiveFilterAction.Stay
        val index = cursorIndex
        return when (key) {
            SportsLiveFilterKey.LEFT ->
                if (index <= 0) this to SportsLiveFilterAction.ExitLeft
                else copy(cursor = values[index - 1]) to SportsLiveFilterAction.Stay
            SportsLiveFilterKey.RIGHT ->
                if (index >= values.lastIndex) this to SportsLiveFilterAction.ExitRight
                else copy(cursor = values[index + 1]) to SportsLiveFilterAction.Stay
            SportsLiveFilterKey.OK -> {
                val value = values[index]
                if (value == selected) this to SportsLiveFilterAction.OpenList
                else copy(selected = value, cursor = value) to SportsLiveFilterAction.Select(value)
            }
        }
    }

    /** Focus left the control: the cursor goes back to the selection. */
    fun blurred(): SportsLiveFilterState = copy(cursor = selected)

    /** New [values] or [selected] from outside (a refresh, a fallback to All): the cursor stays when still listed. */
    fun updated(values: List<String?>, selected: String?): SportsLiveFilterState =
        SportsLiveFilterState(values, selected, cursor.takeIf { it in values } ?: selected)
}

/**
 * The header's per-tab filter (V2, Upcoming now; Channels and Replays until P4 gives them dropdowns):
 * a pill of [options], one focus stop with one ring round it. The [selected] option has a tinted fill
 * (never white); with focus, the option under the cursor reads white with a thin outline. OK calls
 * [onSelect] on another option, [onOpenList] on the selected one.
 */
@Composable
fun SportsLiveFilterControl(
    options: List<SportsLiveFilterOption>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onOpenList: () -> Unit,
    modifier: Modifier = Modifier
) {
    val values = options.map { it.value }
    var state by remember { mutableStateOf(SportsLiveFilterState(values, selected)) }
    if (state.values != values || state.selected != selected) state = state.updated(values, selected)
    var focused by remember { mutableStateOf(false) }
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnOpenList by rememberUpdatedState(onOpenList)
    fun press(key: SportsLiveFilterKey): Boolean {
        val (next, action) = state.onKey(key)
        state = next
        return when (action) {
            SportsLiveFilterAction.Stay -> true
            is SportsLiveFilterAction.Select -> {
                currentOnSelect(action.value)
                true
            }
            SportsLiveFilterAction.OpenList -> {
                currentOnOpenList()
                true
            }
            // Not consumed: focus moves on that way.
            SportsLiveFilterAction.ExitLeft, SportsLiveFilterAction.ExitRight -> false
        }
    }
    val shape = SportsLiveTokens.Radius.PillShape
    val scroll = rememberScrollState()
    val requesters = remember(values) { values.associateWith { BringIntoViewRequester() } }
    val cursor = state.cursor
    LaunchedEffect(cursor, requesters) { requesters[cursor]?.let { runCatching { it.bringIntoView() } } }
    Surface(
        onClick = { press(SportsLiveFilterKey.OK) },
        modifier = modifier
            .height(SportsLiveTokens.Size.ControlHeight)
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) state = state.blurred()
            }
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> press(SportsLiveFilterKey.LEFT)
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> press(SportsLiveFilterKey.RIGHT)
                    else -> false
                }
            },
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = SportsLiveTokens.Focus.LIFT),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Colors.TextPrimary,
            focusedContainerColor = SportsLiveFocus.focusedFill(Color.Transparent),
            focusedContentColor = Colors.TextPrimary,
            pressedContainerColor = SportsLiveFocus.focusedFill(Color.Transparent),
            pressedContentColor = Colors.TextPrimary
        ),
        border = ClickableSurfaceDefaults.border(
            border = SportsLiveFocus.outline(shape),
            focusedBorder = SportsLiveFocus.ring(shape),
            pressedBorder = SportsLiveFocus.ring(shape)
        )
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .horizontalScroll(scroll)
                .padding(horizontal = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            options.forEach { option ->
                val isSelected = option.value == state.selected
                val underCursor = focused && option.value == cursor && !isSelected
                val optionShape = RoundedCornerShape(10.dp)
                Text(
                    text = option.label,
                    style = SportsLiveTokens.style(if (isSelected) SportsLiveTokens.Type.ControlSelected else SportsLiveTokens.Type.Control),
                    color = when {
                        option.gold -> Colors.FollowedGold
                        isSelected || underCursor -> Colors.TextPrimary
                        else -> Colors.TextInactive
                    },
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .bringIntoViewRequester(requesters.getValue(option.value))
                        .background(if (isSelected) SportsLiveFocus.selectedFill else Color.Transparent, optionShape)
                        .border(1.dp, if (underCursor) Colors.TextPrimary else Color.Transparent, optionShape)
                        .padding(horizontal = 6.dp, vertical = 5.dp)
                )
            }
        }
    }
}

/**
 * The filter's options from a tab's [filters] ("★ My teams" first when listed, then All, then the rest), each
 * named by [labelOf] (a sport's short name, "Football" not "American Football").
 */
internal fun sportsLiveFilterOptions(
    filters: List<String>,
    myTeamsLabel: String,
    allLabel: String,
    labelOf: (String) -> String = { SportsLiveDisplayText.label(it) ?: it }
): List<SportsLiveFilterOption> {
    val myTeams = SportsLiveUiState.MY_TEAMS_FILTER
    return buildList {
        if (myTeams in filters) add(SportsLiveFilterOption(myTeams, myTeamsLabel, gold = true))
        add(SportsLiveFilterOption(null, allLabel))
        filters.filter { it != myTeams }.forEach { add(SportsLiveFilterOption(it, labelOf(it))) }
    }
}
