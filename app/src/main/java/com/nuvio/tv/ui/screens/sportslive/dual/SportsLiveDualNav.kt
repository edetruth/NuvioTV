package com.nuvio.tv.ui.screens.sportslive.dual

/** What opening dual screen from a full player pops off the back stack. */
enum class SportsLiveDualPopTarget {
    /** Everything above the Sports & Live section (kept): Back from dual returns to the section. */
    ABOVE_SECTION,

    /** An older dual screen and everything above it: a player opened from dual replaces it. */
    DUAL,

    /** The player's Stream screen and the player. */
    STREAM,

    /** Only the player. */
    PLAYER
}

/**
 * Entering dual screen from a playing game leaves no player, Stream screen or older dual screen
 * behind it: with the section on the stack, everything above the section goes, so Back from dual
 * (or from a full screen opened from dual, which replaces it) returns to the section. Without it
 * (a game started from Home), only the player and what belongs to it go, as before.
 */
fun sportsLiveDualEntryPopTarget(
    sectionOnStack: Boolean,
    dualOnStack: Boolean,
    previousIsStreamScreen: Boolean
): SportsLiveDualPopTarget = when {
    sectionOnStack -> SportsLiveDualPopTarget.ABOVE_SECTION
    dualOnStack -> SportsLiveDualPopTarget.DUAL
    previousIsStreamScreen -> SportsLiveDualPopTarget.STREAM
    else -> SportsLiveDualPopTarget.PLAYER
}
