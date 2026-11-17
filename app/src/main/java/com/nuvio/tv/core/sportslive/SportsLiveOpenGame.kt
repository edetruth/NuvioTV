package com.nuvio.tv.core.sportslive

/** Settings → Sports & Live → "When I open a game": what OK on a live game or channel card does. */
enum class SportsLiveOpenGame {
    /** The best stream (as a zap picks it) plays at once; "Choose stream…" still lets the user pick. */
    PLAY_BEST,

    /** The stream list opens, as before #56. */
    LET_ME_CHOOSE;

    companion object {
        /**
         * The choice in effect: the [stored] one when the user made it; never set, Let me choose for
         * a user who chose manual stream selection for the player ([manualPlayer]: they already pick
         * streams), else Play best stream. The player's untouched default (also manual) does not count.
         */
        fun effective(stored: SportsLiveOpenGame?, manualPlayer: Boolean): SportsLiveOpenGame =
            stored ?: if (manualPlayer) LET_ME_CHOOSE else PLAY_BEST
    }
}
