package com.nuvio.tv.core.sportslive.scores

/**
 * Every place that shows a game's result (#117), with how the Scores setting and Reveal apply there. A new surface
 * is one line: its name and these switches (or an existing surface's).
 *
 * @param revealable a Reveal (pressed, or a focus dwell) shows it; false: Home, which has no reveal.
 * @param revealsUnderOff under Never show a Reveal still loads and shows it (the pages); false: never under Never
 *   show (Results rows, score chips), as nothing is fetched then.
 * @param recordWithLoad the season record, standing, season stats and leaders show whenever the data may load, so
 *   under Hide until I reveal too (decision 5); false: they hide with the score.
 * @param focusReveals under Hide until I reveal a focused row shows its result with no Reveal (the surface reads its
 *   own focus); Results rows and the section's cards reveal after a dwell instead, through `revealed`.
 * @param safeHidesRecord spoiler-safe (opened from a spoiler-safe Game page): the record and standing hide with the
 *   score until revealed, as a record would give the game's result away (the Team page).
 */
enum class SpoilerSurface(
    internal val revealable: Boolean,
    internal val revealsUnderOff: Boolean,
    internal val recordWithLoad: Boolean,
    internal val focusReveals: Boolean,
    internal val safeHidesRecord: Boolean = false
) {
    /** The Game page: score, records and stats together. */
    GAME(revealable = true, revealsUnderOff = true, recordWithLoad = false, focusReveals = false),

    /** The Player card: season stats whenever loaded; the last games' results and lines like a score. */
    PLAYER_CARD(revealable = true, revealsUnderOff = true, recordWithLoad = true, focusReveals = true),

    /** The Team page: record, standing, upcoming games and leaders; the last results like a score. */
    TEAM(revealable = true, revealsUnderOff = true, recordWithLoad = true, focusReveals = true, safeHidesRecord = true),

    /** A Results row. */
    RESULTS(revealable = true, revealsUnderOff = false, recordWithLoad = false, focusReveals = false),

    /** A game card's score chip, and the Scoreboard's situation line that follows it ([GameSituations]). */
    SCORE_CHIP(revealable = true, revealsUnderOff = false, recordWithLoad = false, focusReveals = false),

    /** Home's Live now score label: Show only, as Home has no reveal. */
    HOME_HERO(revealable = false, revealsUnderOff = false, recordWithLoad = false, focusReveals = false)
}

/**
 * Where and for what a result would show. [spoilerSafe] (opened from Catch up or a replay, #58, #151): hidden until
 * revealed even under Show. [eventState]: the game's state when the surface knows it; a postponed or unknown game
 * has no result to give away, so its status shows. [spoilerProtected] (#151): hidden whatever the setting or Reveal.
 */
data class SpoilerContext(
    val surface: SpoilerSurface,
    val spoilerSafe: Boolean = false,
    val eventState: ScoreState? = null,
    val spoilerProtected: Boolean = false
)

/**
 * What a surface may do. [mayLoad]: ask for the data at all. [showsScore]: a game's score or result (W/L, final).
 * [showsRecord]: season record, standing, season stats and leaders. [showsStats]: one game's stats (box score, a
 * player's line in a game). [revealsOnFocus]: hidden, but a focused row shows it with no Reveal.
 */
data class SpoilerVisibility(
    val mayLoad: Boolean,
    val showsScore: Boolean,
    val showsRecord: Boolean,
    val showsStats: Boolean,
    val revealsOnFocus: Boolean
) {
    /** Whether a row shows its result, [focused] or not. */
    fun showsRow(focused: Boolean): Boolean = showsScore || (revealsOnFocus && focused)

    companion object {
        val NOTHING = SpoilerVisibility(mayLoad = false, showsScore = false, showsRecord = false, showsStats = false, revealsOnFocus = false)
    }
}

/**
 * The one spoiler rule (#117): what the Scores setting [mode] (null: not read yet, so nothing is known to be safe)
 * and [revealed] let a surface show. Show: everything, unless spoiler-safe. Hide until I reveal: once revealed.
 * Never show: nothing is fetched, except a page's Reveal. A protected game: never.
 */
object SpoilerPolicy {

    private val SPOILING_STATES = setOf(ScoreState.PRE, ScoreState.IN, ScoreState.POST)

    fun visibility(mode: ScoresMode?, revealed: Boolean, context: SpoilerContext): SpoilerVisibility {
        mode ?: return SpoilerVisibility.NOTHING
        val surface = context.surface
        val reveal = revealed && surface.revealable && (mode != ScoresMode.OFF || surface.revealsUnderOff)
        val mayLoad = mode != ScoresMode.OFF || reveal
        val spoils = context.eventState == null || context.eventState in SPOILING_STATES
        val showsScore = when {
            !mayLoad -> false
            !spoils -> true
            context.spoilerProtected -> false
            else -> (mode == ScoresMode.SHOW && !context.spoilerSafe) || reveal
        }
        val revealsOnFocus = surface.focusReveals && mode == ScoresMode.HIDE_UNTIL_FOCUSED && !showsScore &&
            !context.spoilerSafe && !context.spoilerProtected
        return SpoilerVisibility(
            mayLoad = mayLoad,
            showsScore = showsScore,
            showsRecord = if (surface.recordWithLoad && !(surface.safeHidesRecord && context.spoilerSafe)) mayLoad else showsScore,
            showsStats = showsScore,
            revealsOnFocus = revealsOnFocus
        )
    }
}
