package com.nuvio.tv.ui.screens.sportslive

import android.view.KeyEvent as AndroidKeyEvent
import com.nuvio.tv.core.sportslive.scores.GameSection

/** An action of the Game page's one action row (#134). More… is always last and is not one of these. */
internal enum class GameAction { WATCH, REMIND, REPLAY, REVEAL }

/**
 * The Game page's action row (#134, decisions 1 and 2), the PRIMARY first, then the rest as SECONDARY buttons
 * before More…:
 * - a live game a card plays: **Watch**; an upcoming one that can be reminded: **Remind me**; a game with a
 *   replay: **Watch replay**;
 * - scores hidden: **Watch replay** when there is a replay (spoiler-free viewing), else **Reveal score**; Reveal
 *   stays beside it. An upcoming game has no score to give away, so Remind me keeps the lead there.
 *
 * Empty: only More… shows (a final with no replay, revealed).
 */
internal fun gameActions(
    showsScore: Boolean,
    hasReplay: Boolean,
    canWatchLive: Boolean = false,
    canRemind: Boolean = false
): List<GameAction> {
    val main = buildList {
        if (canWatchLive) add(GameAction.WATCH)
        if (canRemind) add(GameAction.REMIND)
        if (hasReplay) add(GameAction.REPLAY)
    }
    if (showsScore) return main
    val primary = when {
        hasReplay -> GameAction.REPLAY
        canRemind -> GameAction.REMIND
        else -> GameAction.REVEAL
    }
    return listOf(primary) + (main - primary) + listOfNotNull(GameAction.REVEAL.takeIf { it != primary })
}

/** What a key on the Game page's text tabs does (#134). */
internal sealed interface GameTabKey {
    /** Select this section and move focus to its tab. */
    data class Move(val section: GameSection) : GameTabKey

    /** ← on the first tab, → on the last: focus stays on the tabs (it never jumps into the header). */
    data object Stay : GameTabKey

    /** Up, Down, OK and the rest: the usual focus handling. */
    data object Pass : GameTabKey
}

/** ← → step through [sections] from [current]; the body below never changes section (#134: F8). */
internal fun gameTabKey(sections: List<GameSection>, current: GameSection?, keyCode: Int): GameTabKey {
    val step = when (keyCode) {
        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> -1
        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> 1
        else -> return GameTabKey.Pass
    }
    val index = sections.indexOf(current)
    if (index < 0) return sections.firstOrNull()?.let { GameTabKey.Move(it) } ?: GameTabKey.Stay
    return sections.getOrNull(index + step)?.let { GameTabKey.Move(it) } ?: GameTabKey.Stay
}
