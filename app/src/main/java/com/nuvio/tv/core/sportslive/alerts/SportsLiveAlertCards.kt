package com.nuvio.tv.core.sportslive.alerts

import android.view.KeyEvent
import com.nuvio.tv.domain.model.SportsLiveItem

/**
 * One overlay on screen (#136): a kickoff prompt alone, or toasts that arrived close together, which the
 * card cycles through. [shownAtMs]: when it appeared; [lastJoinedAtMs]: when the newest alert joined it.
 */
data class AlertCard(
    val id: Long,
    val alerts: List<SportsLiveAlert>,
    val shownAtMs: Long,
    val lastJoinedAtMs: Long = shownAtMs
) {
    val first: SportsLiveAlert get() = alerts.first()
}

/** Grouping, cycling and timing of the alert cards (#136). Pure, so it is tested without a clock. */
object SportsLiveAlertCards {
    /** An alert arriving this soon after the card's newest one joins it instead of waiting its turn. */
    const val GROUP_WINDOW_MS = 8_000L

    /** A toast's time on screen. */
    const val NORMAL_MS = 6_000L

    /** A big moment's (a lead change, a final). */
    const val BIG_MS = 9_000L

    /** A grouped card shows each of its alerts this long in turn. */
    const val CYCLE_MS = 3_000L

    /** However many join, a card leaves after this long. */
    const val MAX_CARD_MS = 20_000L

    /** A lead change or a final: the card stays longer. */
    fun isBigMoment(alert: SportsLiveAlert): Boolean = when (alert) {
        is SportsLiveAlert.ScoreChange -> alert.leadChange
        is SportsLiveAlert.FullTime -> true
        is SportsLiveAlert.Crunch -> true
        is SportsLiveAlert.Kickoff -> false
    }

    /** How long [alert] stays on screen by itself. */
    fun durationMs(alert: SportsLiveAlert): Long = if (isBigMoment(alert)) BIG_MS else NORMAL_MS

    /** Score and full-time toasts group; a kickoff prompt (it asks a question) is always alone. */
    fun groupable(alert: SportsLiveAlert): Boolean = alert !is SportsLiveAlert.Kickoff

    /** When [card] leaves: its longest alert's time, or one turn each when grouped, from the last join; capped. */
    fun endsAtMs(card: AlertCard): Long {
        val life = maxOf(card.alerts.maxOf(::durationMs), if (card.alerts.size > 1) card.alerts.size * CYCLE_MS else 0L)
        return minOf(card.lastJoinedAtMs + life, card.shownAtMs + MAX_CARD_MS)
    }

    /** True when [alert], raised at [nowMs], joins [card] rather than waiting for the next card. */
    fun canJoin(card: AlertCard, alert: SportsLiveAlert, nowMs: Long): Boolean =
        groupable(card.first) && groupable(alert) &&
            nowMs - card.lastJoinedAtMs <= GROUP_WINDOW_MS && nowMs < endsAtMs(card)

    /** [card] with [alert] in it; a newer alert about a game already on the card takes that one's place. */
    fun join(card: AlertCard, alert: SportsLiveAlert, nowMs: Long): AlertCard {
        val key = SportsLiveAlertEngine.gameKey(alert.item)
        val at = card.alerts.indexOfFirst { SportsLiveAlertEngine.gameKey(it.item) == key }
        val alerts = if (at >= 0) card.alerts.toMutableList().also { it[at] = alert } else card.alerts + alert
        return card.copy(alerts = alerts, lastJoinedAtMs = nowMs)
    }

    /** The index of the alert [card] shows at [nowMs]: each in turn for [CYCLE_MS]. OK acts on this one. */
    fun showingIndex(card: AlertCard, nowMs: Long): Int {
        if (card.alerts.size <= 1) return 0
        val elapsed = (nowMs - card.shownAtMs).coerceAtLeast(0L)
        return ((elapsed / CYCLE_MS) % card.alerts.size).toInt()
    }
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

/** What a remote key does while an alert card or a peek is up (#136). */
enum class AlertKeyAction {
    /** Not the alert's: the screen below gets it as usual. */
    PASS,

    /** Swallowed: nothing happens (a peek covers the player, so its keys must not reach it). */
    CONSUME,

    /** ▶ (or a held OK) on a card: its game plays here. Never a plain OK (research Q17). */
    WATCH,

    /** ▼ on a card: its game plays full screen with sound for a few seconds. */
    PEEK,

    /** ▲ on a card in the full player: dual screen with the game playing and the card's game beside it, muted. */
    BOTH,

    /** Back on a card: it goes. */
    DISMISS,

    /** OK during a peek: stay on the peeked game. */
    PEEK_STAY,

    /** Back during a peek: the game from before, at its live edge. */
    PEEK_RETURN,

    /** ▶ or a held OK on the kickoff toast (research Q17): its game plays (the no-stream note: remind me in 10 min). */
    PROMPT_WATCH,

    /** Back while the kickoff toast counts down to switching ("At tip-off: Switch automatically"): no switch. */
    PROMPT_CANCEL
}

/**
 * Who gets a fresh key press (#136). [cardActionable]: a score or full-time card shows over a screen
 * that can play its game here (the full player of a game, dual screen) and that screen's own overlays
 * (controls, the live games panel, a dialog) are closed. [canPeek]: that screen can peek (the full player).
 * [peeking]: a peek plays.
 */
data class AlertKeyContext(
    val cardActionable: Boolean,
    val canPeek: Boolean,
    val peeking: Boolean,
    /**
     * A kickoff toast shows (research Q17), on any screen: it never takes focus, and only ▶ or a held OK act on it;
     * every other key, OK and Back included, does what it does underneath.
     */
    val kickoff: Boolean = false,
    /** The kickoff toast counts down to switching: Back cancels the switch (and only that). */
    val countdown: Boolean = false,
    /** ▲ opens dual screen with both games: the full player only (never in dual screen, already two games). */
    val canBoth: Boolean = false
)

/**
 * "At tip-off: Switch automatically" (research Q17): the kickoff toast counts down and switches only when opted in
 * ([tipOff]), for a game with a stream (never the no-stream note), and never over anything playing ([inPlayer]: a
 * live game being watched is the most valuable thing on screen; over a movie the toast only informs).
 */
object SportsLiveTipOffSwitch {
    fun runs(tipOff: com.nuvio.tv.core.sportslive.SportsLiveTipOff, noStreamYet: Boolean, inPlayer: Boolean): Boolean =
        tipOff == com.nuvio.tv.core.sportslive.SportsLiveTipOff.SWITCH && !noStreamYet && !inPlayer
}

object SportsLiveAlertKeys {

    /**
     * Who takes keys now: [card] (null once it closed, a timeout too) over a screen that can play its game
     * here ([canActHere]: the full player of a game, dual screen) and registered as a surface ([surfaceBusy]
     * null when none is). A score card is actionable only while the screen's own overlays are closed
     * ([surfaceBusy] false). A kickoff takes its keys wherever it shows as the toast ([kickoffKeys]: not over a
     * movie, where it only informs). [countdown]: its auto-switch countdown runs. [canPeek]: the screen could peek
     * (the full player). [canBoth]: ▲ could open dual screen with both games (the full player).
     */
    fun context(
        card: AlertCard?,
        canActHere: Boolean,
        surfaceBusy: Boolean?,
        canPeek: Boolean,
        peeking: Boolean,
        canBoth: Boolean = false,
        kickoffKeys: Boolean = true,
        countdown: Boolean = false
    ): AlertKeyContext {
        val kickoff = card?.first is SportsLiveAlert.Kickoff
        val onSurface = card != null && canActHere && surfaceBusy != null
        val actionable = onSurface && !kickoff && surfaceBusy == false
        return AlertKeyContext(
            cardActionable = actionable,
            canPeek = actionable && canPeek,
            peeking = peeking,
            kickoff = kickoff && kickoffKeys,
            countdown = kickoff && kickoffKeys && countdown,
            canBoth = actionable && canBoth
        )
    }

    /**
     * The action of a key's first press, or of a held OK's first repeat ([held]: about half a second in); the rest
     * of that press follows it (see the host's dispatcher). A plain OK never acts on an alert (research Q17: OK is
     * the most-pressed key, and a prompt that took it took presses meant for the screen).
     */
    fun route(keyCode: Int, context: AlertKeyContext, held: Boolean = false): AlertKeyAction {
        if (context.peeking) {
            if (held) return AlertKeyAction.PASS
            return when {
                isOk(keyCode) -> AlertKeyAction.PEEK_STAY
                keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE -> AlertKeyAction.PEEK_RETURN
                // The system's volume keys keep working for the peek's sound.
                keyCode in SYSTEM_KEYS -> AlertKeyAction.PASS
                else -> AlertKeyAction.CONSUME
            }
        }
        if (held) {
            return when {
                !isOk(keyCode) -> AlertKeyAction.PASS
                context.kickoff -> AlertKeyAction.PROMPT_WATCH
                context.cardActionable -> AlertKeyAction.WATCH
                else -> AlertKeyAction.PASS
            }
        }
        if (context.kickoff) {
            return when {
                isWatchKey(keyCode) -> AlertKeyAction.PROMPT_WATCH
                context.countdown && (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) -> AlertKeyAction.PROMPT_CANCEL
                else -> AlertKeyAction.PASS
            }
        }
        if (!context.cardActionable) return AlertKeyAction.PASS
        return when {
            isWatchKey(keyCode) -> AlertKeyAction.WATCH
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN && context.canPeek -> AlertKeyAction.PEEK
            keyCode == KeyEvent.KEYCODE_DPAD_UP && context.canBoth -> AlertKeyAction.BOTH
            keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE -> AlertKeyAction.DISMISS
            else -> AlertKeyAction.PASS
        }
    }

    fun isOk(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    /** ▶: Play/Pause or Play, the deliberate key an alert's Watch is bound to (research Q17). */
    fun isWatchKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY

    private val SYSTEM_KEYS = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)
}

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
