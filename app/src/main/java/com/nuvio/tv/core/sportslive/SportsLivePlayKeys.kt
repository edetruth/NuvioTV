package com.nuvio.tv.core.sportslive

/**
 * "OK on live" (research Q19): what a bare OK does on a live game or channel in the full player with its controls
 * hidden. [SHOW_CONTROLS] (default): the controls show with ⏸ focused, and a second OK pauses, as live-TV apps do
 * (Google's Live Channels, TiviMate); [PAUSE]: Google's TV-PC rule, OK pauses at once. Replays always pause on OK.
 */
enum class SportsLiveOkOnLive {
    SHOW_CONTROLS, PAUSE;

    companion object {
        val DEFAULT = SHOW_CONTROLS

        fun fromStored(value: String?): SportsLiveOkOnLive = entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}

/**
 * "At tip-off" (research Q17): what a reminded or followed game's kickoff does. [REMIND] (default): a toast that never
 * takes focus, "▶ or hold OK to watch"; [SWITCH]: the same toast counts down 10 s and then plays the game (Back
 * cancels), only where it cannot hurt (never over a live game being watched).
 */
enum class SportsLiveTipOff {
    REMIND, SWITCH;

    companion object {
        val DEFAULT = REMIND

        fun fromStored(value: String?): SportsLiveTipOff = entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}
