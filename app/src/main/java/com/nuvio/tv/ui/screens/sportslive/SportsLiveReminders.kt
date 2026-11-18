package com.nuvio.tv.ui.screens.sportslive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.sportslive.alerts.RemindersState
import com.nuvio.tv.core.sportslive.alerts.SportsLiveAlerts
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.domain.model.SportsLiveItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "Remind me" / "Cancel reminder" (#44) on cards, the details pane, the Timeline panel, the Game page and the
 * Team page; per profile. Each press shows "Reminder set" / "Reminder removed" as the app's toast card
 * ([SportsLiveAlerts.reminderNotice], drawn by the alerts host), not a system toast.
 */
@HiltViewModel
class SportsLiveRemindersViewModel @Inject constructor(private val alerts: SportsLiveAlerts) : ViewModel() {

    val state: StateFlow<RemindersState> = alerts.reminders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RemindersState())

    fun toggle(item: SportsLiveItem) {
        viewModelScope.launch {
            alerts.noticeReminder(item, alerts.toggleReminder(item))
        }
    }

    /** The bell on an ESPN game no card plays yet (a Team page's next game). */
    fun toggle(event: ScoreSnapshot) {
        viewModelScope.launch {
            alerts.noticeReminder(event, alerts.toggleReminder(event))
        }
    }
}
