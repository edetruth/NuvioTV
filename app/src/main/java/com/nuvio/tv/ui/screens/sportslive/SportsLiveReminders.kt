package com.nuvio.tv.ui.screens.sportslive

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.alerts.RemindersState
import com.nuvio.tv.core.sportslive.alerts.SportsLiveAlerts
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** "Remind me" / "Cancel reminder" (#44) on cards, the details pane and the Game page; per profile. */
@HiltViewModel
class SportsLiveRemindersViewModel @Inject constructor(private val alerts: SportsLiveAlerts) : ViewModel() {

    val state: StateFlow<RemindersState> = alerts.reminders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RemindersState())

    private val toggledEvents = Channel<Pair<SportsLiveItem, Boolean>>(Channel.BUFFERED)

    /** Each bell press: the game and whether it is reminded now. */
    val toggled: Flow<Pair<SportsLiveItem, Boolean>> = toggledEvents.receiveAsFlow()

    fun toggle(item: SportsLiveItem) {
        viewModelScope.launch { toggledEvents.send(item to alerts.toggleReminder(item)) }
    }
}

/** "Reminder set · ‹game› · ‹time› · We'll ask …" or "Reminder off · ‹game›" after each bell press. */
@Composable
internal fun SportsLiveReminderToasts(viewModel: SportsLiveRemindersViewModel) {
    val context = LocalContext.current
    val labelOf by rememberUpdatedState(rememberSportsLiveStateLabeler(SportsLiveTab.UPCOMING))
    LaunchedEffect(viewModel) {
        viewModel.toggled.collect { (item, on) ->
            val text = if (on) {
                context.getString(R.string.sports_live_reminder_set_toast, item.meta.name, labelOf(item).orEmpty())
            } else {
                context.getString(R.string.sports_live_reminder_off_toast, item.meta.name)
            }
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }
}
