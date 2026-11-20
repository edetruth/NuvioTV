package com.nuvio.tv.core.sportslive

/**
 * The "Are you still watching?" guard of the end card (#161, research 6): automatic moves to the next game stop after
 * [MAX_AUTO_MOVES] moves in a row, or [IDLE_LIMIT_MS] after the last key press, with no key pressed meanwhile. The
 * card then asks instead of counting down, so a TV left on never flips through games all night (Netflix asks after
 * a long stretch with no input; WCAG 2.2.1 wants a time limit the viewer controls). Any key press ([onInteraction],
 * fed by the activity) starts afresh. Process-wide: the full player, a dual pane and the next player share it.
 * Thread-safe.
 */
class SportsLiveStillWatching(private val nowMs: () -> Long = { System.currentTimeMillis() }) {

    private var lastInteractionMs = nowMs()
    private var autoMoves = 0

    /** A key was pressed: the viewer is here. */
    @Synchronized
    fun onInteraction() {
        lastInteractionMs = nowMs()
        autoMoves = 0
    }

    /** An end card's countdown ran out and moved to its next game. */
    @Synchronized
    fun onAutoMove() {
        autoMoves++
    }

    /** True while an end card may still move on by itself; false: it asks "Are you still watching?". */
    @Synchronized
    fun mayAutoMove(): Boolean = autoMoves < MAX_AUTO_MOVES && nowMs() - lastInteractionMs < IDLE_LIMIT_MS

    companion object {
        /** Automatic moves in a row with no key press before the card asks. */
        const val MAX_AUTO_MOVES = 3

        /** No key press for this long and the card asks: about one long game. */
        const val IDLE_LIMIT_MS = 3 * 60 * 60_000L

        val shared = SportsLiveStillWatching()
    }
}
