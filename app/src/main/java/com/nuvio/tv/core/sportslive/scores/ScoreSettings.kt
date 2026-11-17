package com.nuvio.tv.core.sportslive.scores

/** Settings → Sports & Live → Scores. */
enum class ScoresMode {
    /** Scores show on every surface (default). */
    SHOW,

    /** Surfaces show "Score hidden" until the card is focused. Polling runs as for [SHOW]. */
    HIDE_UNTIL_FOCUSED,

    /** No scores anywhere and no polling at all. */
    OFF
}

/** Settings → Sports & Live → Score delay (#41): seconds to hold back score, clock and state changes. */
object ScoreDelayChoices {
    val SECONDS = listOf(0, 15, 30, 60, 90)

    /** [seconds] if it is a choice, else Off (0). */
    fun coerce(seconds: Int?): Int = seconds?.takeIf { it in SECONDS } ?: 0
}

/** The two score settings of the active profile. */
data class ScoreSettings(val mode: ScoresMode = ScoresMode.SHOW, val delaySeconds: Int = 0)
