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

/** Dual screen's two games, and which pane is highlighted (has the sound; OK acts on it). */
data class DualPanes(val left: SportsLiveItem, val right: SportsLiveItem, val leftHighlighted: Boolean)

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

    /** OK on a card: its game plays here. */
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

    /** OK on the kickoff prompt with Watch chosen (#173): its game plays here. */
    PROMPT_WATCH,

    /** OK with Later chosen, or Back, on the kickoff prompt (#173): ask again later. */
    PROMPT_LATER,

    /** ← on the kickoff prompt: Watch is chosen. */
    PROMPT_CHOOSE_WATCH,

    /** → on the kickoff prompt: Later is chosen. */
    PROMPT_CHOOSE_LATER
}

/** The kickoff prompt's two buttons (#173), left to right; OK presses the one chosen. */
enum class PromptChoice { WATCH, LATER }

/**
 * Who gets a fresh key press (#136). [cardActionable]: a score or full-time card shows over a screen
 * that can play its game here (the full player of a game, dual screen) and that screen's own overlays
 * (controls, the live games panel, a dialog) are closed, so OK is not needed by anything else.
 * [canPeek]: that screen can peek (the full player). [peeking]: a peek plays.
 */
data class AlertKeyContext(
    val cardActionable: Boolean,
    val canPeek: Boolean,
    val peeking: Boolean,
    /**
     * A kickoff prompt shows over a screen that can play its game here (#173), with this button chosen: it
     * takes OK, ← →, Back and every zap key until it closes, whatever else is up. Null: no such prompt.
     */
    val prompt: PromptChoice? = null,
    /** ▲ opens dual screen with both games: the full player only (never in dual screen, already two games). */
    val canBoth: Boolean = false
)
object SportsLiveAlertKeys {

    /**
     * Who takes keys now: [card] (null once it closed, a timeout too) over a screen that can play its game
     * here ([canActHere]: the full player of a game, dual screen) and registered as a surface ([surfaceBusy]
     * null when none is). A score card is actionable only while the screen's own overlays are closed
     * ([surfaceBusy] false); a kickoff prompt always is, with [promptChoice] chosen (#173). [canPeek]: the
     * screen could peek (the full player). [canBoth]: ▲ could open dual screen with both games (the full player).
     */
    fun context(
        card: AlertCard?,
        canActHere: Boolean,
        surfaceBusy: Boolean?,
        canPeek: Boolean,
        peeking: Boolean,
        promptChoice: PromptChoice = PromptChoice.WATCH,
        canBoth: Boolean = false
    ): AlertKeyContext {
        val onSurface = card != null && canActHere && surfaceBusy != null
        val prompt = onSurface && card?.first is SportsLiveAlert.Kickoff
        val actionable = onSurface && !prompt && surfaceBusy == false
        return AlertKeyContext(
            cardActionable = actionable,
            canPeek = actionable && canPeek,
            peeking = peeking,
            prompt = promptChoice.takeIf { prompt },
            canBoth = (actionable || prompt) && canBoth
        )
    }

    /** The action of a key's first press (repeats and the release follow it: see the host's dispatcher). */
    fun route(keyCode: Int, context: AlertKeyContext): AlertKeyAction {
        if (context.peeking) {
            return when {
                isOk(keyCode) -> AlertKeyAction.PEEK_STAY
                keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE -> AlertKeyAction.PEEK_RETURN
                // The system's volume keys keep working for the peek's sound.
                keyCode in SYSTEM_KEYS -> AlertKeyAction.PASS
                else -> AlertKeyAction.CONSUME
            }
        }
        context.prompt?.let { return routePrompt(keyCode, it, context.canBoth) }
        if (!context.cardActionable) return AlertKeyAction.PASS
        return when {
            isOk(keyCode) -> AlertKeyAction.WATCH
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN && context.canPeek -> AlertKeyAction.PEEK
            keyCode == KeyEvent.KEYCODE_DPAD_UP && context.canBoth -> AlertKeyAction.BOTH
            keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE -> AlertKeyAction.DISMISS
            else -> AlertKeyAction.PASS
        }
    }

    /**
     * A key while the kickoff prompt is up (#173): ▲ ▼ and CH± never zap under it; ▲ opens both games where
     * [canBoth] (#161); the volume and the rest pass.
     */
    private fun routePrompt(keyCode: Int, choice: PromptChoice, canBoth: Boolean): AlertKeyAction = when {
        isOk(keyCode) -> if (choice == PromptChoice.WATCH) AlertKeyAction.PROMPT_WATCH else AlertKeyAction.PROMPT_LATER
        keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE -> AlertKeyAction.PROMPT_LATER
        keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> AlertKeyAction.PROMPT_CHOOSE_WATCH
        keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> AlertKeyAction.PROMPT_CHOOSE_LATER
        keyCode == KeyEvent.KEYCODE_DPAD_UP && canBoth -> AlertKeyAction.BOTH
        keyCode in ZAP_KEYS -> AlertKeyAction.CONSUME
        else -> AlertKeyAction.PASS
    }

    fun isOk(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    private val SYSTEM_KEYS = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)

    /** Every key that zaps in the player or dual screen (the D-pad's ▲ ▼, CH±, next and previous). */
    private val ZAP_KEYS = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
        KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS
    )
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
