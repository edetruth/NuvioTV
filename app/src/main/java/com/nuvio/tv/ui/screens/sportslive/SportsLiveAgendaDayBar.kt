package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveHeaderControl
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Upcoming's day tabs and time-slot targets (research 3), one row above the Agenda: "Today · Tomorrow · Sat 22 · Any
 * time", then "‹ Earlier" and "Later ›". Visible targets instead of the remote's CH± and ⏪ ⏩ alone, which Google TV
 * remotes do not have (TV-DM) and a one-time tip only hinted at (NN/g: no overlay for what the layout can show).
 * Focusing a day shows it (TV tab rows select on focus); OK or ↓ goes into its games. Earlier / Later move the stage
 * a time slot at a time and keep focus, so OK pages through the day; ↓ then lands on the game shown.
 *
 * [currentDay]: the day of the game the stage shows (its tab reads as selected). [dayFocusRequester] gives each day
 * tab its requester; ↑ goes to [upFocusRequester] (the selected header tab), ↓ to [downFocusRequester].
 */
@Composable
internal fun SportsLiveAgendaDayBar(
    days: List<AgendaDay>,
    currentDay: Int,
    canEarlier: Boolean,
    canLater: Boolean,
    dayFocusRequester: (Int) -> FocusRequester,
    upFocusRequester: FocusRequester,
    downFocusRequester: FocusRequester?,
    onDayFocused: (Int) -> Unit,
    onDayOk: (Int) -> Unit,
    onStep: (later: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val routes = Modifier.focusProperties {
        up = upFocusRequester
        downFocusRequester?.let { down = it }
    }
    // Room round the chips for the focus ring and lift, so the first one is never clipped at the scroll edge.
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = BAR_EDGE),
        horizontalArrangement = Arrangement.spacedBy(SportsLiveTokens.Size.ControlGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState())
                .padding(BAR_EDGE),
            horizontalArrangement = Arrangement.spacedBy(SportsLiveTokens.Size.ControlGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            days.forEachIndexed { index, day ->
                val selected = index == currentDay
                SportsLiveHeaderControl(
                    onClick = { onDayOk(index) },
                    round = false,
                    outline = if (selected) Colors.TextSecondary else Colors.Outline,
                    modifier = routes
                        .focusRequester(dayFocusRequester(index))
                        .onFocusChanged { if (it.isFocused) onDayFocused(index) }
                ) {
                    Text(
                        text = agendaDayTabText(day),
                        style = SportsLiveTokens.style(if (selected) SportsLiveTokens.Type.ControlSelected else SportsLiveTokens.Type.Control),
                        color = if (selected) Colors.TextPrimary else Colors.TextSecondary,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
        StepControl(stringResource(R.string.sports_live_agenda_earlier), enabled = canEarlier, modifier = routes) { onStep(false) }
        StepControl(stringResource(R.string.sports_live_agenda_later), enabled = canLater, modifier = routes) { onStep(true) }
    }
}

/** Earlier / Later: always focusable (the row never reflows under focus), muted and inert at either end. */
@Composable
private fun StepControl(label: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    SportsLiveHeaderControl(onClick = { if (enabled) onClick() }, round = false, modifier = modifier) {
        Text(
            text = label,
            style = SportsLiveTokens.style(SportsLiveTokens.Type.Control),
            color = if (enabled) Colors.TextSecondary else Colors.TextMuted.copy(alpha = 0.5f),
            maxLines = 1,
            softWrap = false
        )
    }
}

/** A day tab's words: "Today", "Tomorrow", "Any time", else the short date ("Sat 22"). */
@Composable
internal fun agendaDayTabText(day: AgendaDay): String =
    if (day.kind == AgendaDayKind.DATE && day.date != null) DateTimeFormatter.ofPattern("EEE d", Locale.getDefault()).format(day.date)
    else agendaDayName(day)

/** The chips' room for the ring and lift, inside the list column's own. */
private val BAR_EDGE = 4.dp
