package com.nuvio.tv.ui.screens.sportslive

/**
 * A key held in the open table never leaks into a zap (live.26 shorter paths, #223, amendment A4). Held ▼ scrolls the
 * table fast; when the table closes under it (a pick, Back, an end card), the rest of that press (its repeats and its
 * release) would reach the player, where ▼ zaps to the next game. Every event of a zap key ([zapDirection]: ▲ ▼, CH±,
 * next/previous) pressed before the table closed is taken; a new press (pressed after) is the player's as before.
 * Times are uptime ms, as a key event's down time.
 */
internal class SportsLiveHeldKeyGuard {
    private var closedAt = Long.MIN_VALUE

    /** The table (or the side panel) closed at [uptimeMs]. */
    fun onClosed(uptimeMs: Long) {
        closedAt = uptimeMs
    }

    /**
     * True when the event of [keyCode] whose press went down at [downTime] is the rest of a press held across the close.
     * An event with no down time (0: some remotes and injected keys) is never taken, or zap would stop for good.
     */
    fun swallows(keyCode: Int, downTime: Long): Boolean =
        zapDirection(keyCode, partyActive = false) != null && downTime > 0L && downTime <= closedAt

    companion object {
        /** One for the app: a pick that switches the game opens a new player, which must still take the held press. */
        val shared = SportsLiveHeldKeyGuard()
    }
}
