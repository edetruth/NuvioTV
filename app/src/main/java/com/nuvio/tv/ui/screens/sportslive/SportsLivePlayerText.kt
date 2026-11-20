package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.SportsLiveDisplayText
import kotlin.math.abs

private val PIPE = Regex("""\s*\|\s*""")
private val WHITESPACE = Regex("""\s+""")
private const val ZERO_WIDTH_JOINER = 0x200D
private const val VARIATION_SELECTOR = 0xFE0F
private const val KEYCAP = 0x20E3

/**
 * A game's title as the player's cards say it (live.19 QA F24, F11): the clean display name (#100), without
 * emoji or pictographs ("⏱", "🏆", which also left a blank gap where the TV font has no glyph), "|" separators as
 * " · ", one space between words. Words over symbols (10-ft rule). Falls back to the clean name if nothing is left.
 */
internal fun sportsLivePlainTitle(raw: String): String {
    val name = SportsLiveDisplayText.name(raw)
    val text = buildString {
        var at = 0
        while (at < name.length) {
            val point = name.codePointAt(at)
            if (!sportsLiveIsPictograph(point)) appendCodePoint(point)
            at += Character.charCount(point)
        }
    }
    val plain = WHITESPACE.replace(PIPE.replace(text, " · "), " ").trim().trim('·', '-', ' ')
    return plain.ifEmpty { name }
}

/** An emoji, pictograph or joiner: the TV font draws some as blank boxes (F24), so plain text leaves them out. */
internal fun sportsLiveIsPictograph(point: Int): Boolean {
    if (point == ZERO_WIDTH_JOINER || point == VARIATION_SELECTOR || point == KEYCAP) return true
    val type = Character.getType(point)
    return type == Character.OTHER_SYMBOL.toInt() || type == Character.PRIVATE_USE.toInt() || type == Character.SURROGATE.toInt()
}

/** How near the kickoff prompt's game must start for its time to be worth saying (F11). */
internal const val KICKOFF_TIME_WINDOW_MS = 30 * 60_000L

/**
 * Whether the kickoff prompt shows its game's start time (live.19 QA F11): only a real time of day within
 * [KICKOFF_TIME_WINDOW_MS] of now. A date-only start, or a placeholder midnight far from now, would read as
 * "12:00 AM" under "Kicking off now".
 */
internal fun sportsLiveKickoffShowsTime(startMs: Long?, hasTimeOfDay: Boolean, nowMs: Long): Boolean =
    startMs != null && hasTimeOfDay && abs(startMs - nowMs) <= KICKOFF_TIME_WINDOW_MS
