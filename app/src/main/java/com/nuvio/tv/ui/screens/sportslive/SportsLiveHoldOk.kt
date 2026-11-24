package com.nuvio.tv.ui.screens.sportslive

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.report.SportsLiveReportTrail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hold OK on any screen (#218 outside the player): where the focused element has no hold of its own, a small sheet
 * with **Report a problem** (first) and **Cancel**. D-pad, OK and Back only, so every remote has it; the held
 * Play/Pause or Info shortcut ([com.nuvio.tv.core.sportslive.report.SportsLiveReportKey]) stays an extra.
 *
 * Every OK event reaches the screens as before, except the rest of a press that opened the sheet. Who owns a hold:
 * - The first repeat (the system's long-press time, as the player's Hold OK) goes to the screens first. Not taken
 *   (nothing focused wants OK held): the sheet opens at once.
 * - Taken: a hold of the element's own (poster options, a Sports & Live row's menu: dialogs, which take the window and
 *   so the rest of the press; or a new screen), or an element that takes every OK event and does nothing with a hold
 *   (tv-material buttons and cards: most of Settings). The sheet opens only when the press is still held here, on the
 *   same screen with the window focused, [graceMs] after that first repeat.
 * - Never in the full player or dual screen ([EXCLUDED]): they have their own Hold OK. A sports replay's player has
 *   none (no zap session), so the sheet opens there too (live.26, G-C10): at the first repeat, never offered to the
 *   player, which takes every OK and its repeats as play/pause (#254: the press stays its pause, as a short OK).
 * A release before the first repeat is a normal click: nothing here changes it. Once the sheet opened, the press's
 * remaining repeats and its release are eaten, so the focused element never takes the release for a click.
 */
internal class SportsLiveHoldOkKey(
    private val graceMs: Long = GRACE_MS,
    /** Whether the full player has its Hold OK now; false for a sports replay (no zap session: G-C10), which has none. */
    private val playerHasOwnHold: () -> Boolean = { true }
) {
    private var pressDown = NO_PRESS
    private var pressRoute: String? = null
    private var mine = false
    private var skip = false
    private var takenAt = NO_PRESS

    /** The press began in a sports replay's player: its hold is the sheet's, never the player's (#254). */
    private var replayPlayer = false

    /**
     * One key event, before the screens see it. [screens] hands it to them (true when they consumed it); [open] opens
     * the sheet for the route the press began on. Returns whether the event was consumed.
     */
    fun onKey(
        keyCode: Int,
        action: Int,
        repeatCount: Int,
        downTime: Long,
        eventTime: Long,
        route: String?,
        windowFocused: Boolean,
        screens: () -> Boolean,
        open: (route: String?) -> Unit
    ): Boolean {
        if (!isOk(keyCode)) return screens()
        if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) {
            pressDown = downTime
            pressRoute = route
            mine = false
            takenAt = NO_PRESS
            val screen = holdOkScreen(route)
            replayPlayer = screen == PLAYER && !playerHasOwnHold()
            skip = screen in EXCLUDED && !replayPlayer
            return screens()
        }
        // A repeat or a release of a press this never saw (another hook took its press): not ours.
        if (pressDown == NO_PRESS || downTime != pressDown) return screens()
        if (action == KeyEvent.ACTION_UP) {
            val eaten = mine
            pressDown = NO_PRESS
            mine = false
            skip = false
            takenAt = NO_PRESS
            return eaten || screens()
        }
        if (mine) return true
        if (action != KeyEvent.ACTION_DOWN || skip) return screens()
        if (route != pressRoute || !windowFocused) {
            // Something answered the hold: a new screen, or a dialog that has the window now.
            skip = true
            return screens()
        }
        if (takenAt == NO_PRESS) {
            // The first repeat: the screens' own hold goes first (a replay's player has none: the sheet's at once).
            if (!replayPlayer && screens()) {
                takenAt = eventTime
                return true
            }
        } else if (eventTime - takenAt < graceMs) {
            return screens()
        }
        mine = true
        open(pressRoute)
        return true
    }

    companion object {
        /** How long a hold the screens took must last longer for the sheet: their dialog has the window by then. */
        const val GRACE_MS = 500L

        /** Screens with a Hold OK of their own: the full player (#218) and dual screen. */
        const val PLAYER = "player"
        val EXCLUDED = setOf(PLAYER, "sports_live_dual")

        private const val NO_PRESS = Long.MIN_VALUE

        private fun isOk(keyCode: Int): Boolean =
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
    }
}

/**
 * The screen a route names, for a report: its first word, with no argument ("detail/{itemId}/...?..." -> "detail");
 * "unknown" for none.
 */
internal fun holdOkScreen(route: String?): String =
    route?.substringBefore('/')?.substringBefore('?')?.substringBefore('{')?.trim()?.ifEmpty { null } ?: "unknown"

/** The report's "From:" for Hold OK on [route]'s screen: "Hold OK (settings)". */
internal fun holdOkTrigger(route: String?): String = "Hold OK (${holdOkScreen(route)})"

/**
 * The sheet for a hold on [route]'s screen, its report snapshot taken now ([capture]). Runs inside dispatchKeyEvent: a
 * snapshot that fails to build never crashes key handling (live.23 review L6); Report a problem then saves one built at
 * that moment ([reportLater]), with the usual saved or failed toast.
 */
internal fun holdOkSheet(route: String?, capture: (String) -> () -> Unit, reportLater: (String) -> Unit): SportsLiveHoldOk.Sheet {
    val trigger = holdOkTrigger(route)
    val save: () -> Unit = try {
        capture(trigger)
    } catch (_: Exception) {
        { reportLater(trigger) }
    }
    return SportsLiveHoldOk.Sheet(holdOkTitle(route), save)
}

/** The sheet's title: the screen in words ("playback_settings" -> "Playback settings"). */
internal fun holdOkTitle(route: String?): String =
    holdOkScreen(route).replace('_', ' ').replaceFirstChar { it.uppercaseChar() }

/** MainActivity's Hold OK (from [SportsLiveActivityHooks.dispatchToScreens]) and the sheet it opens. */
object SportsLiveHoldOk {
    /** The open sheet: its [title] and [save], which saves the report snapshot taken as the hold began. */
    internal class Sheet(val title: String, val save: () -> Unit)

    /** The full player now open plays a sports replay, which has no zap session and so no Hold OK menu of its own (G-C10). */
    @Volatile
    internal var replayPlayerOpen = false

    private val key = SportsLiveHoldOkKey(playerHasOwnHold = { !replayPlayerOpen })
    private val _sheet = MutableStateFlow<Sheet?>(null)
    internal val sheet: StateFlow<Sheet?> = _sheet.asStateFlow()

    /** [event] on its way to the screens ([screens]); true when consumed. The report snapshot is taken at the hold. */
    fun dispatch(event: KeyEvent, windowFocused: Boolean, reporter: SportsLiveReporter, screens: (KeyEvent) -> Boolean): Boolean =
        key.onKey(
            keyCode = event.keyCode,
            action = event.action,
            repeatCount = event.repeatCount,
            downTime = event.downTime,
            eventTime = event.eventTime,
            route = SportsLiveReportTrail.screen,
            windowFocused = windowFocused,
            screens = { screens(event) }
        ) { route ->
            _sheet.value = holdOkSheet(route, reporter::capture, reporter::report)
        }

    internal fun dismiss() {
        _sheet.value = null
    }

    /** Report a problem picked: the sheet closes and the report saves, with its "Report #N saved" toast. */
    internal fun report() {
        val shown = _sheet.value ?: return
        _sheet.value = null
        shown.save()
    }
}

/** The Hold OK sheet (MainActivity's overlays): the screen's name, Report a problem first, then Cancel; Back closes. */
@Composable
internal fun SportsLiveHoldOkHost() {
    val shown by SportsLiveHoldOk.sheet.collectAsState()
    val sheet = shown ?: return
    SportsLiveSheet(
        title = sheet.title,
        status = stringResource(R.string.sports_live_hold_ok_status),
        statusLive = false,
        onDismiss = SportsLiveHoldOk::dismiss,
        onBack = null
    ) { first ->
        SportsLiveMenuButton(
            text = stringResource(R.string.sports_live_setting_report_problem),
            onClick = SportsLiveHoldOk::report,
            modifier = first()
        )
        SportsLiveMenuButton(text = stringResource(R.string.action_cancel), onClick = SportsLiveHoldOk::dismiss)
    }
}
