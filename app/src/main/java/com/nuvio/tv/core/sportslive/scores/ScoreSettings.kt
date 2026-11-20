package com.nuvio.tv.core.sportslive.scores

/** Settings → Sports & Live → Scores. */
enum class ScoresMode {
    /** Scores show on every surface (default). Settings: "Show". */
    SHOW,

    /**
     * Surfaces show "Score hidden" until the card is focused. Polling runs as for [SHOW].
     * Settings: "Hide until I reveal" (live.20, research item 5); the stored name is unchanged.
     */
    HIDE_UNTIL_FOCUSED,

    /** No scores anywhere and no polling at all. Settings: "Never show"; the stored name is unchanged. */
    OFF
}

/**
 * Scores as Settings shows it (live.20, research item 5): the mode, and under Hide until I reveal whether only
 * my teams' games hide ([myTeamsOnly], "Hide scores for my teams only").
 *
 * Stored as before, as the Scores mode plus the "my teams" protection switch (#151), so no stored value changes
 * and a TV on an older version reads the same behaviour: My teams only is Show with my teams protected (what
 * Show + that switch always meant). Any other stored pair reads as its mode; a choice other than My teams only
 * stores the switch off.
 */
data class ScoresChoice(val mode: ScoresMode = ScoresMode.SHOW, val myTeamsOnly: Boolean = false) {
    /** The Scores mode to store (and that every surface reads). */
    val storedMode: ScoresMode get() = if (isMyTeamsOnly) ScoresMode.SHOW else mode

    /** The "my teams" protection switch to store. */
    val storedMyTeams: Boolean get() = isMyTeamsOnly

    private val isMyTeamsOnly: Boolean get() = mode == ScoresMode.HIDE_UNTIL_FOCUSED && myTeamsOnly

    /** This choice with "Hide scores for my teams only" set to [on]: on means Hide until I reveal for my teams only. */
    fun withMyTeamsOnly(on: Boolean): ScoresChoice = ScoresChoice(ScoresMode.HIDE_UNTIL_FOCUSED, on)

    /** This choice with the Scores picker set to [picked]: Hide until I reveal keeps its scope. */
    fun withMode(picked: ScoresMode): ScoresChoice =
        ScoresChoice(picked, myTeamsOnly = picked == ScoresMode.HIDE_UNTIL_FOCUSED && isMyTeamsOnly)

    companion object {
        /** What Settings shows for the stored [mode] and "my teams" switch [myTeams]. */
        fun of(mode: ScoresMode, myTeams: Boolean): ScoresChoice =
            if (mode == ScoresMode.SHOW && myTeams) ScoresChoice(ScoresMode.HIDE_UNTIL_FOCUSED, myTeamsOnly = true)
            else ScoresChoice(mode)
    }
}

/** Settings → Sports & Live → Score delay (#41): seconds to hold back score, clock and state changes. */
object ScoreDelayChoices {
    val SECONDS = listOf(0, 15, 30, 60, 90)

    /**
     * A profile that never set Score delay (live.20, #85): addon streams run about a minute behind ESPN (TV QA saw
     * the chip and "Just scored" ahead of the picture), so scores wait 60 s. A stored Off (0) stays Off.
     */
    const val DEFAULT_SECONDS = 60

    /** [seconds] if it is a choice, else Off (0). */
    fun coerce(seconds: Int?): Int = seconds?.takeIf { it in SECONDS } ?: 0
}

/** The two score settings of the active profile. */
data class ScoreSettings(val mode: ScoresMode = ScoresMode.SHOW, val delaySeconds: Int = 0)
