package com.nuvio.tv.ui.screens.sportslive.dual

/** What opening dual screen from a full player pops off the back stack. */
enum class SportsLiveDualPopTarget {
    /**
     * Everything above the Sports & Live section (kept): Back from dual plays the game it was opened
     * from full screen in its place (M11), and Back from that player returns to the section.
     */
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

/**
 * The back stack after OK hands a dual pane off to full screen ([targetRoute], the player or a
 * Stream screen): dual and anything above it go, whatever opened dual (the section, a player, or
 * Home's Live now row), so full screen leaves dual behind and Back from it returns to what was
 * below dual: the section, or Home when dual was opened from Home. NuvioNavHost does this with
 * `popUpTo(dual) { inclusive = true }`.
 */
fun sportsLiveStackAfterDualHandOff(stack: List<String>, dualRoute: String, targetRoute: String): List<String> {
    val dual = stack.lastIndexOf(dualRoute)
    return (if (dual < 0) stack else stack.subList(0, dual)) + targetRoute
}
