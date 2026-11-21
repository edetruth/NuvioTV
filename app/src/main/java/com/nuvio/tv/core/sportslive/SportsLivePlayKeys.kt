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
 * "In-player guide (beta)" (live.21, design B2), a temporary three-way setting so one build compares both squeezes on
 * the TV: [PANEL] (default) the side panel; [TABLE_SMOOTH] the new table with the video scaled to its corner over
 * 200 ms; [TABLE_JUMP] the new table with the video resized at once and only the guide fading in.
 */
enum class SportsLiveGuideStyle {
    PANEL, TABLE_SMOOTH, TABLE_JUMP;

    /** The new table, either squeeze. */
    val table: Boolean get() = this != PANEL

    companion object {
        /** live.23 (user 2026-11-21): the table is the default, no longer a beta; the side panel stays a choice. */
        val DEFAULT = TABLE_SMOOTH

        fun fromStored(value: String?): SportsLiveGuideStyle = entries.firstOrNull { it.name == value } ?: DEFAULT
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
