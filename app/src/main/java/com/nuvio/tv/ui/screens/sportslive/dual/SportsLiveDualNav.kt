package com.nuvio.tv.ui.screens.sportslive.dual

import com.nuvio.tv.core.sportslive.SportsLiveWorkingLinks
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapSession
import com.nuvio.tv.ui.screens.sportslive.SportsLiveZapSessions

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

/**
 * Coordinator decision 1 (as YouTube TV and Apple multiview): a game OK took full screen from dual remembers the
 * game in the other pane and dual's lists, in its zap session ([SportsLiveZapSession.dualReturn]), so Back from
 * that player returns to dual with both games. A session started anywhere else carries none.
 */
data class SportsLiveDualReturn(
    /** The other pane's game. */
    val other: SportsLiveItem,
    val items: List<SportsLiveItem>,
    val zapItems: List<SportsLiveItem>,
    val filter: String?,
    val tab: SportsLiveTab,
    val finishedKeys: Set<String> = emptySet()
)

/**
 * Dual screen for Back from this full player: the game playing now (a zap in full screen replaced the pane's
 * game) on the left, on [playingStream] with no lookup and with the sound, beside the other pane's game. Null with
 * no [SportsLiveZapSession.dualReturn], or when the game playing is the other pane's (or shares a source with it):
 * Back leaves as before.
 */
internal fun SportsLiveZapSession.dualSessionOnBack(playingStream: Stream?): SportsLiveDualSession? {
    val back = dualReturn ?: return null
    val game = current
    if (game.sharesSourceWith(back.other)) return null
    return SportsLiveDualSession(
        left = game,
        right = back.other,
        items = back.items,
        filter = back.filter,
        tab = back.tab,
        leftStream = playingStream,
        zapItems = back.zapItems,
        finishedKeys = back.finishedKeys
    )
}

/**
 * Back from the full player showing [contentId] (the nav host's one-line seam): when it was opened full screen from
 * dual, dual screen opens again on both games ([openDual] leaves the player as "Watch both" does) and this returns
 * true; false otherwise, and Back goes on as before.
 */
internal fun sportsLiveBackToDual(contentId: String?, openDual: () -> Unit): Boolean {
    val zap = SportsLiveZapSessions.activeFor(contentId) ?: return false
    val session = zap.dualSessionOnBack(SportsLiveWorkingLinks.shared.playing(zap.current.id)) ?: return false
    SportsLiveDualSessions.start(session)
    openDual()
    return true
}
