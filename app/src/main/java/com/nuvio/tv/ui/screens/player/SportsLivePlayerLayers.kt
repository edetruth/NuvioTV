package com.nuvio.tv.ui.screens.player

/**
 * The Sports & Live cards of the full player that hold the keys (live.19 QA F10, F11), highest priority first.
 * Only one of them is on screen at a time ([SportsLivePlayerLayers.owner]): the others wait their turn and come
 * back when it goes, so two cards never stack and two buttons never look focused at once.
 */
enum class SportsLivePlayerLayer {
    /** The kickoff prompt (#173): a question with a 15 s countdown; it takes OK, ← → and Back while it is up. */
    KICKOFF_PROMPT,

    /** The recovery card: nothing plays; Try next link, Choose stream, Next live game. */
    ERROR,

    /** The end-of-game card (#161), not its toast-only form. */
    END_CARD,

    /** "Waiting for the stream to start…" (#162): nothing plays yet. */
    WAITING,

    /** A link is starting (#138): Try next link, Sources. */
    STARTING,

    /** The player's own pause screen ("You're watching…"): only when no Sports & Live card is up. */
    PAUSE_INFO
}

/** The one place that says which of the player's Sports & Live cards owns the screen (F10, F11). */
object SportsLivePlayerLayers {

    /** Highest first: a timed question beats a failure, a failure beats the end card, and so on down. */
    val PRIORITY: List<SportsLivePlayerLayer> = SportsLivePlayerLayer.entries

    /** The layers [up] in effect now, from what each card would show by itself. */
    fun up(
        kickoffPrompt: Boolean,
        error: Boolean,
        endCard: Boolean,
        waiting: Boolean,
        starting: Boolean,
        pauseInfo: Boolean
    ): Set<SportsLivePlayerLayer> = buildSet {
        if (kickoffPrompt) add(SportsLivePlayerLayer.KICKOFF_PROMPT)
        if (error) add(SportsLivePlayerLayer.ERROR)
        if (endCard) add(SportsLivePlayerLayer.END_CARD)
        if (waiting) add(SportsLivePlayerLayer.WAITING)
        if (starting) add(SportsLivePlayerLayer.STARTING)
        if (pauseInfo) add(SportsLivePlayerLayer.PAUSE_INFO)
    }

    /** The card that is drawn and takes the keys; null when none is up. */
    fun owner(up: Set<SportsLivePlayerLayer>): SportsLivePlayerLayer? = PRIORITY.firstOrNull { it in up }

    /** Whether [layer] may show now: it is up and nothing above it is. */
    fun shows(layer: SportsLivePlayerLayer, up: Set<SportsLivePlayerLayer>): Boolean = owner(up) == layer

    /** Whether a card above [layer] is up, so [layer] waits (whether or not it is up itself). */
    fun covered(layer: SportsLivePlayerLayer, up: Set<SportsLivePlayerLayer>): Boolean =
        PRIORITY.takeWhile { it != layer }.any { it in up }
}
