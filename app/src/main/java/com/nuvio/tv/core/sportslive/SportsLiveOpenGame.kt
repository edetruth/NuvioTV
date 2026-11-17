package com.nuvio.tv.core.sportslive

/** Settings → Sports & Live → "When I open a game": what OK on a live game or channel card does. */
enum class SportsLiveOpenGame {
    /** The best stream (as a zap picks it) plays at once; "Choose stream…" still lets the user pick. */
    PLAY_BEST,

    /** The stream list opens, as before #56. */
    LET_ME_CHOOSE
}
