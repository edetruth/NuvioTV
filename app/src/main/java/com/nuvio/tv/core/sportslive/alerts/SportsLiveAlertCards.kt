package com.nuvio.tv.core.sportslive.alerts

import android.view.KeyEvent
import com.nuvio.tv.domain.model.SportsLiveItem

/**
 * One overlay on screen (#136, live.24): one alert per card, never cycling (Material snackbars: one at a time, a single
 * action). [raisedAtMs]: when its alert was raised; a card waiting under the screen's own overlays goes stale from it.
 */
data class AlertCard(
    val id: Long,
    val alert: SportsLiveAlert,
    val raisedAtMs: Long
) {
    val first: SportsLiveAlert get() = alert
}

/**
 * The cards' rules (live.24 notifications redesign, docs/design/notifications-review.md option A). Pure, so it is tested
 * without a clock.
 *
 * One rule everywhere: if a card shows "OK", OK belongs to the card; if it does not (Full time), the card is only
 * information and every key works as normal.
 */
object SportsLiveAlertCards {
    /** An actionable card's time on screen (NN/g: a toast that fades in 5 s goes before it can be acted on). */
    const val ACTIONABLE_MS = 10_000L

    /** A lead change keeps the longer time. */
    const val LEAD_CHANGE_MS = 12_000L

    /** A passive card's (Full time): it only informs. */
    const val PASSIVE_MS = 6_000L

    /** An OK pressed this soon after a card appeared was on its way already: it goes to the screen underneath. */
    const val OK_GRACE_MS = 800L

    /** OK on a card starts this countdown to the switch; Back keeps you where you are. */
    const val COUNTDOWN_MS = 3_000L

    /** "At tip-off: Switch automatically": the same countdown card, longer (research Q17: 10 s, Back stays). */
    const val TIP_OFF_COUNTDOWN_MS = 10_000L

    /** A card that could not show this long after its alert was raised (the screen's overlays stayed open) is old news. */
    const val STALE_MS = 30_000L

    /**
     * Whether [alert]'s card shows "OK": everything but Full time (the game is over, there is nothing to switch to). A
     * kickoff watches (the no-stream note reminds in 10 min); a score, a lead change and a close finish watch.
     */
    fun actionable(alert: SportsLiveAlert): Boolean = alert !is SportsLiveAlert.FullTime

    /**
     * Which alert wins when several wait (lower first): my team's kickoff, then my team's score or lead change, then
     * full time, then a close finish. The others are dropped as stale: the guide and the rows still show them.
     */
    fun priority(alert: SportsLiveAlert): Int = when (alert) {
        is SportsLiveAlert.Kickoff -> 0
        is SportsLiveAlert.ScoreChange -> 1
        is SportsLiveAlert.FullTime -> 2
        is SportsLiveAlert.Crunch -> 3
    }

    /**
     * How long [alert]'s card stays on screen (its time counts only while it shows): 10 s actionable, 12 s for a lead
     * change, 6 s passive; [recommended] scales it as Android's "Time to take action" asks
     * (AccessibilityManager.getRecommendedTimeoutMillis), never shorter.
     */
    fun durationMs(alert: SportsLiveAlert, recommended: (Long, Boolean) -> Long = { ms, _ -> ms }): Long {
        val actionable = actionable(alert)
        val base = when {
            !actionable -> PASSIVE_MS
            alert is SportsLiveAlert.ScoreChange && alert.leadChange -> LEAD_CHANGE_MS
            else -> ACTIONABLE_MS
        }
        return maxOf(base, recommended(base, actionable))
    }

    /**
     * The alert of [waiting] (with when each was raised) to show next: the highest [priority], the earliest of those.
     * Null for none.
     */
    fun next(waiting: List<Pair<SportsLiveAlert, Long>>): Int? =
        waiting.indices.minWithOrNull(compareBy<Int>({ priority(waiting[it].first) }, { waiting[it].second }))
}

/** Where a card sits (#136): top right of the screen, or of one dual screen pane. */
enum class AlertSpot { SCREEN, LEFT_PANE, RIGHT_PANE }

/**
 * Dual screen's two games, and which pane is highlighted (has the sound; OK acts on it). [bigSmall]: the
 * left game large and the right one small in the corner (#123), which moves where a card sits.
 */
data class DualPanes(val left: SportsLiveItem, val right: SportsLiveItem, val leftHighlighted: Boolean, val bigSmall: Boolean = false)

object SportsLiveAlertPlacement {

    /**
     * Top right of the pane [alert] is not about; when neither pane shows its game (or there is no dual
     * screen), top right of the whole screen.
     */
    fun spot(alert: SportsLiveAlert, panes: DualPanes?): AlertSpot = when {
        panes == null -> AlertSpot.SCREEN
        sameGame(alert.item, panes.left) -> AlertSpot.RIGHT_PANE
        sameGame(alert.item, panes.right) -> AlertSpot.LEFT_PANE
        else -> AlertSpot.SCREEN
    }

    /** True when [a] and [b] are one game: a shared source, or the same two sides on the same day. */
    fun sameGame(a: SportsLiveItem, b: SportsLiveItem): Boolean {
        val ids = (listOf(b) + b.alternates).mapTo(HashSet()) { it.id }
        if ((listOf(a) + a.alternates).any { it.id in ids }) return true
        return a.teams != null && b.teams != null && SportsLiveAlertEngine.gameKey(a) == SportsLiveAlertEngine.gameKey(b)
    }
}
/** What a remote key does while an alert card shows (live.24). */
enum class AlertKeyAction {
    /** Not the card's: the screen below gets it as usual. */
    PASS,

    /** Swallowed: the expanded card has the keys, and this one does nothing there. */
    CONSUME,

    /** A plain OK on a card that shows "OK": its action (a countdown to its game, or Remind me in 10 min). */
    OK,

    /** Back on a card that shows "OK": it goes. */
    DISMISS,

    /** OK on the expanded card: its selected choice (Watch now, Watch both). */
    PRESS,

    /** Back on the expanded card: no switch, you stay where you are. */
    CANCEL,

    /** ◀ ▶ on the expanded card: the selection moves between Watch now and Watch both. */
    MOVE
}

/**
 * Who gets a key now (live.24). [cardOk]: a card that shows "OK" is on screen (never under the screen's own overlays: it
 * waits until they close) and has been for [SportsLiveAlertCards.OK_GRACE_MS]. [cardBack]: such a card is on screen,
 * however long: Back closes it. [expanded]: OK started the countdown; the card has every key but the volume.
 * [canBoth]: the expanded card offers Watch both (over a sports stream only).
 */
data class AlertKeyContext(
    val cardOk: Boolean = false,
    val cardBack: Boolean = false,
    val expanded: Boolean = false,
    val canBoth: Boolean = false
)

/**
 * "At tip-off: Switch automatically" (research Q17): the kickoff card opens straight into its countdown, only when
 * opted in ([tipOff]), for a game with a stream (never the no-stream note), and never over anything playing
 * ([inPlayer]: a live game being watched is the most valuable thing on screen).
 */
object SportsLiveTipOffSwitch {
    fun runs(tipOff: com.nuvio.tv.core.sportslive.SportsLiveTipOff, noStreamYet: Boolean, inPlayer: Boolean): Boolean =
        tipOff == com.nuvio.tv.core.sportslive.SportsLiveTipOff.SWITCH && !noStreamYet && !inPlayer
}

object SportsLiveAlertKeys {

    /**
     * Who takes keys now: [card] (null: none) is on screen since [shownAtMs] (null: it waits under the screen's own
     * overlays, so it takes nothing), [nowMs] now. [expanded]: its countdown card is up; [canBoth]: Watch both is offered.
     */
    fun context(card: AlertCard?, shownAtMs: Long?, nowMs: Long, expanded: Boolean = false, canBoth: Boolean = false): AlertKeyContext {
        if (card == null || shownAtMs == null) return AlertKeyContext()
        if (expanded) return AlertKeyContext(expanded = true, canBoth = canBoth)
        val actionable = SportsLiveAlertCards.actionable(card.alert)
        return AlertKeyContext(
            cardOk = actionable && nowMs - shownAtMs >= SportsLiveAlertCards.OK_GRACE_MS,
            cardBack = actionable
        )
    }

    /**
     * The action of a key's first press. ▶ and Hold OK are never an alert's (live.24): ▶ is missing on many remotes and
     * is the player's pause, and Hold OK keeps its meaning everywhere (#218, the dispatcher hands a held OK back).
     */
    fun route(keyCode: Int, context: AlertKeyContext): AlertKeyAction {
        val back = keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE
        if (context.expanded) {
            return when {
                isOk(keyCode) -> AlertKeyAction.PRESS
                back -> AlertKeyAction.CANCEL
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ->
                    if (context.canBoth) AlertKeyAction.MOVE else AlertKeyAction.CONSUME
                // The system's volume keys keep working for the video underneath.
                keyCode in SYSTEM_KEYS -> AlertKeyAction.PASS
                else -> AlertKeyAction.CONSUME
            }
        }
        return when {
            isOk(keyCode) && context.cardOk -> AlertKeyAction.OK
            back && context.cardBack -> AlertKeyAction.DISMISS
            else -> AlertKeyAction.PASS
        }
    }

    fun isOk(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    private val SYSTEM_KEYS = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)
}

/**
 * A card's OK press (live.24): a plain OK on a card is taken on its press and acts on its release; held until the key
 * repeats, it is handed back to the screens (Hold OK keeps its meaning everywhere, #218: the player menu, the Hold OK
 * sheet). Pure: the dispatcher feeds it the OK key events.
 */
class AlertOkPress {
    enum class Step {
        /** Not a press this follows: the event goes on as usual. */
        NONE,

        /** Taken, nothing more yet. */
        TAKE,

        /** The release of a short press: the card's OK acts now. */
        ACT,

        /** Held: the press goes back to the screens from its start. */
        HAND_BACK
    }

    private var pending: Long? = null

    /** An OK key event ([action] as KeyEvent's, [repeatCount], [downTime]); [cardTakes]: on a fresh press, the card takes it. */
    fun onOk(action: Int, repeatCount: Int, downTime: Long, cardTakes: Boolean): Step {
        if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) {
            pending = if (cardTakes) downTime else null
            return if (cardTakes) Step.TAKE else Step.NONE
        }
        if (pending != downTime) return Step.NONE
        return when {
            action == KeyEvent.ACTION_UP -> {
                pending = null
                Step.ACT
            }
            repeatCount > 0 -> {
                pending = null
                Step.HAND_BACK
            }
            else -> Step.TAKE
        }
    }
}

/** The expanded card's choices: Watch now (focused first), and Watch both where offered. */
enum class AlertChoice { WATCH_NOW, WATCH_BOTH }

/**
 * The expanded countdown card (live.24): OK on a card opened it. [selected] is focused; [endsAtMs] is when the switch
 * happens, null once the countdown stopped (focus moved to Watch both: the viewer is choosing).
 */
data class AlertCountdown(val cardId: Long, val selected: AlertChoice, val endsAtMs: Long?) {
    /** ◀ ▶ (only with Watch both offered): the other choice; moving off Watch now stops the countdown for good. */
    fun move(): AlertCountdown = copy(
        selected = if (selected == AlertChoice.WATCH_NOW) AlertChoice.WATCH_BOTH else AlertChoice.WATCH_NOW,
        endsAtMs = null
    )

    /** Whole seconds left, rounded up (the card's "in 3…"); null once stopped. */
    fun secondsLeft(nowMs: Long): Int? = endsAtMs?.let { (((it - nowMs).coerceAtLeast(0L) + 999L) / 1_000L).toInt() }

    /** True when the countdown ran out at [nowMs]: the switch happens. */
    fun due(nowMs: Long): Boolean = endsAtMs != null && nowMs >= endsAtMs

    companion object {
        fun start(cardId: Long, nowMs: Long, durationMs: Long = SportsLiveAlertCards.COUNTDOWN_MS): AlertCountdown =
            AlertCountdown(cardId, AlertChoice.WATCH_NOW, nowMs + durationMs)
    }
}

/**
 * A card's time on screen (live.24): it shows only while the screen's own overlays (the guide, controls, a menu, a
 * dialog) are closed, and waits under them; its time counts only while it shows, and each time it shows again the OK
 * grace starts afresh. Pure: the host steps it with the clock.
 */
data class AlertCardClock(
    /** When the card showed this time; null: it waits. */
    val shownAtMs: Long? = null,
    /** Time it has shown before this showing. */
    val shownMs: Long = 0L
) {
    /** The clock at [nowMs], the screen [busy] or not. */
    fun step(nowMs: Long, busy: Boolean): AlertCardClock = when {
        busy && shownAtMs != null -> AlertCardClock(null, shownMs + (nowMs - shownAtMs))
        !busy && shownAtMs == null -> copy(shownAtMs = nowMs)
        else -> this
    }

    fun totalShownMs(nowMs: Long): Long = shownMs + (shownAtMs?.let { nowMs - it } ?: 0L)

    /**
     * What becomes of [card] at [nowMs]: [Fate.UP] while its time lasts; [Fate.TIMED_OUT] once it has shown its
     * [SportsLiveAlertCards.durationMs] ([duration]); [Fate.STALE] when it never showed within
     * [SportsLiveAlertCards.STALE_MS] of being raised.
     */
    fun fate(card: AlertCard, nowMs: Long, duration: Long): Fate = when {
        totalShownMs(nowMs) >= duration -> Fate.TIMED_OUT
        shownAtMs == null && shownMs == 0L && nowMs - card.raisedAtMs >= SportsLiveAlertCards.STALE_MS -> Fate.STALE
        else -> Fate.UP
    }

    enum class Fate { UP, TIMED_OUT, STALE }
}

/*
 * The peek (#136) is no alert action any more (live.24: Watch, with Back to undo, and Watch both cover it; it needed a
 * spare decoder and swallowed keys). Its state machine is kept for the panel to use later.
 */

/** A peek's phase (#136): its stream is looked up and starting, it plays, or OK was pressed and the zap runs. */
enum class PeekPhase { LOADING, PLAYING, STAYING }

/** A peek at [item]: [sinceMs] is when its phase began. */
data class PeekState(val item: SportsLiveItem, val phase: PeekPhase, val sinceMs: Long)

sealed interface PeekEvent {
    /** The clock (once a second while a peek is up). */
    data class Tick(val nowMs: Long) : PeekEvent

    /** The peeked game's first frame. */
    data class FirstFrame(val nowMs: Long) : PeekEvent

    /** No playable stream, or the stream failed. */
    data object Failed : PeekEvent

    data class Ok(val nowMs: Long) : PeekEvent
    data object Back : PeekEvent

    /** The player under the peek was replaced (the zap of [PeekPhase.STAYING] arrived, or the user left). */
    data object PlayerReplaced : PeekEvent
}

/** What the host does after a peek event. */
enum class PeekCommand {
    NONE,

    /** OK: zap the player to the peeked game; the peek stays up until the new player replaces the old. */
    ZAP,

    /** The peek ends and the game from before plays again, with its sound, at its live edge. */
    RETURN,

    /** The peek could not play: as [RETURN], and "No stream" is said. */
    RETURN_FAILED,

    /** The peek ends; the player under it is gone, so there is nothing to give back. */
    END
}

/**
 * A peek's state machine (#136): about [PEEK_MS] of the peeked game from its first frame (the lookup is
 * not counted, up to [LOAD_TIMEOUT_MS]); Back or the time running out gives the game from before back;
 * OK stays on the peeked game (a zap, as picking it in the live games panel). A zap that has not replaced
 * the player after [STAY_TIMEOUT_MS] (no stream after all) gives the game from before back.
 */
object SportsLivePeek {
    const val PEEK_MS = 10_000L
    const val LOAD_TIMEOUT_MS = 15_000L
    const val STAY_TIMEOUT_MS = 15_000L

    fun start(item: SportsLiveItem, nowMs: Long): PeekState = PeekState(item, PeekPhase.LOADING, nowMs)

    /** The state after [event] (null: the peek is over) and what to do. */
    fun step(state: PeekState, event: PeekEvent): Pair<PeekState?, PeekCommand> = when (event) {
        is PeekEvent.Tick -> {
            val limit = when (state.phase) {
                PeekPhase.LOADING -> LOAD_TIMEOUT_MS
                PeekPhase.PLAYING -> PEEK_MS
                PeekPhase.STAYING -> STAY_TIMEOUT_MS
            }
            when {
                event.nowMs - state.sinceMs < limit -> state to PeekCommand.NONE
                state.phase == PeekPhase.LOADING -> null to PeekCommand.RETURN_FAILED
                else -> null to PeekCommand.RETURN
            }
        }
        is PeekEvent.FirstFrame ->
            if (state.phase == PeekPhase.LOADING) state.copy(phase = PeekPhase.PLAYING, sinceMs = event.nowMs) to PeekCommand.NONE
            else state to PeekCommand.NONE
        PeekEvent.Failed ->
            // Once staying, the zap looks its own stream up: the peek's failing no longer matters.
            if (state.phase == PeekPhase.STAYING) state to PeekCommand.NONE else null to PeekCommand.RETURN_FAILED
        is PeekEvent.Ok ->
            if (state.phase == PeekPhase.STAYING) state to PeekCommand.NONE
            else state.copy(phase = PeekPhase.STAYING, sinceMs = event.nowMs) to PeekCommand.ZAP
        // Staying, the zap is under way: Back waits for it rather than leaving the player.
        PeekEvent.Back -> if (state.phase == PeekPhase.STAYING) state to PeekCommand.NONE else null to PeekCommand.RETURN
        PeekEvent.PlayerReplaced -> null to PeekCommand.END
    }
}
