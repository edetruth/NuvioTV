package com.nuvio.tv.core.sportslive

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * What a Sports & Live toast's icon says (V2 #134, decision 7): its colour carries the meaning. A blue bell is
 * a reminder, a gold ★ your team, an amber ⚠ a problem, "FT" a final; [INFO] (a muted ▶) only reports
 * what just happened ("Playing from Source A").
 */
enum class SportsLiveToastKind { REMINDER, FOLLOWED, PROBLEM, FINAL, INFO }

/** One toast: a [title] and an optional second [line], both one line on screen. [id] tells two toasts apart. */
data class SportsLiveToast(
    val kind: SportsLiveToastKind,
    val title: String,
    val line: String? = null,
    val id: Long = 0L
)

/**
 * The section's one toast slot (#134): one toast at a time, and the newest wins. A toast shown while another is
 * up replaces it at once, so a burst of events never queues stale messages behind the current one. The host
 * ([com.nuvio.tv.ui.screens.sportslive.SportsLiveToastHost]) shows [current] for [SHOW_MS] and then dismisses
 * it by id, so a newer toast's timer is never cut short by an older one's. Safe to call from any thread.
 */
class SportsLiveToastQueue {

    private val ids = AtomicLong(0L)
    private val state = MutableStateFlow<SportsLiveToast?>(null)

    /** The toast on screen; null when none is. */
    val current: StateFlow<SportsLiveToast?> = state.asStateFlow()

    /** Shows [kind] [title] / [line] in place of whatever is up; returns the new toast. A blank title shows nothing. */
    fun show(kind: SportsLiveToastKind, title: String, line: String? = null): SportsLiveToast? {
        if (title.isBlank()) return null
        val toast = SportsLiveToast(kind, title.trim(), line?.trim()?.takeIf { it.isNotEmpty() }, ids.incrementAndGet())
        state.value = toast
        return toast
    }

    /** Takes the toast [id] down; nothing when a newer one has replaced it. */
    fun dismiss(id: Long) {
        state.update { shown -> if (shown?.id == id) null else shown }
    }

    companion object {
        /** How long a toast stays on screen: 6 s. */
        const val SHOW_MS = 6_000L
    }
}

/** The app's one toast slot: every Sports & Live surface (and the player) shows its messages through it. */
object SportsLiveToasts {
    val shared = SportsLiveToastQueue()

    fun show(kind: SportsLiveToastKind, title: String, line: String? = null): SportsLiveToast? = shared.show(kind, title, line)
}
