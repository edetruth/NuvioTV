package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.focusGroup
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties

/**
 * Where focus sits on a Sports & Live tab (#170). The screen is two columns under one header line: the list
 * column under the tabs (and Results' ‹ › day arrows), the stage column under the header controls (the filter,
 * e.g. My sports, and Refresh).
 */
internal enum class SportsLiveFocusSpot {
    /** The list column's top: its first row, the Resume row (#148), the Catch up strip or the empty-state card. */
    LIST_TOP,
    /** A stage button. */
    STAGE,
    /** The stage's last button. */
    STAGE_LAST,
    /** A tab, or one of Results' ‹ › day arrows beside it. */
    TAB,
    /** A header control over the stage: the filter or Refresh. */
    HEADER_CONTROL,
    /** The mini player docked in the stage (#157), the stage column's top while it plays. */
    DOCKED_MINI
}

internal enum class SportsLiveFocusMove { UP, DOWN, RIGHT }

/** Where a move goes (#170); [DEFAULT] leaves it to the focus search. */
internal enum class SportsLiveFocusTarget { SELECTED_TAB, HEADER_CONTROLS, DOCKED_MINI, LIST, STAGE_PRIMARY, STAY, DEFAULT }

/**
 * The columns' rule (#170): the list column ↔ the tabs, the stage column ↔ the header controls.
 * - ▲ from the list's top goes to the selected tab, never to the control above it on the right.
 * - ▲ from the stage goes to the header controls, or first to the docked mini player while one is docked (#157),
 *   whose ▲ then goes to the header controls; → at the stage's last button stays put.
 * - ▼ from a tab goes to the list; ▼ from a header control to the stage's primary, or the list with no stage.
 * → along the header line (tabs, day arrows, then controls) is [sportsLiveHeaderNeighbour]'s walk.
 */
internal fun sportsLiveColumnTarget(
    from: SportsLiveFocusSpot,
    move: SportsLiveFocusMove,
    stageShown: Boolean,
    miniDocked: Boolean = false
): SportsLiveFocusTarget =
    when (move) {
        SportsLiveFocusMove.UP -> when (from) {
            SportsLiveFocusSpot.LIST_TOP -> SportsLiveFocusTarget.SELECTED_TAB
            SportsLiveFocusSpot.STAGE, SportsLiveFocusSpot.STAGE_LAST ->
                if (miniDocked) SportsLiveFocusTarget.DOCKED_MINI else SportsLiveFocusTarget.HEADER_CONTROLS
            SportsLiveFocusSpot.DOCKED_MINI -> SportsLiveFocusTarget.HEADER_CONTROLS
            else -> SportsLiveFocusTarget.DEFAULT
        }
        SportsLiveFocusMove.DOWN -> when (from) {
            SportsLiveFocusSpot.TAB -> SportsLiveFocusTarget.LIST
            SportsLiveFocusSpot.HEADER_CONTROL -> if (stageShown) SportsLiveFocusTarget.STAGE_PRIMARY else SportsLiveFocusTarget.LIST
            else -> SportsLiveFocusTarget.DEFAULT
        }
        SportsLiveFocusMove.RIGHT -> if (from == SportsLiveFocusSpot.STAGE_LAST) SportsLiveFocusTarget.STAY else SportsLiveFocusTarget.DEFAULT
    }

/** The header control the stage's ▲ lands on (#170): the first control over the stage, the filter when shown, else Refresh. */
internal fun sportsLiveHeaderControlsStop(hasFilter: Boolean): SportsLiveHeaderStop =
    if (hasFilter) SportsLiveHeaderStop.FILTER else SportsLiveHeaderStop.REFRESH

/**
 * The stage as one focus column (#170): ▲ from any of its buttons goes to [controls], or to the docked mini
 * player ([mini], #157) while one is docked; → past its last button goes nowhere. ← and the moves inside it are
 * left as they are.
 */
internal fun Modifier.sportsLiveStageColumn(controls: FocusRequester, mini: FocusRequester? = null): Modifier = focusProperties {
    onExit = {
        val target = when (requestedFocusDirection) {
            FocusDirection.Up -> sportsLiveColumnTarget(SportsLiveFocusSpot.STAGE, SportsLiveFocusMove.UP, stageShown = true, miniDocked = mini != null)
            // Only the last button can leave the stage to the right: the others move along it.
            FocusDirection.Right -> sportsLiveColumnTarget(SportsLiveFocusSpot.STAGE_LAST, SportsLiveFocusMove.RIGHT, stageShown = true)
            else -> SportsLiveFocusTarget.DEFAULT
        }
        when (target) {
            SportsLiveFocusTarget.STAY -> cancelFocusChange()
            SportsLiveFocusTarget.HEADER_CONTROLS -> if (!runCatching { controls.requestFocus() }.isSuccess) cancelFocusChange()
            SportsLiveFocusTarget.DOCKED_MINI -> if (!runCatching { mini?.requestFocus() }.isSuccess) cancelFocusChange()
            else -> Unit
        }
    }
}.focusGroup()
