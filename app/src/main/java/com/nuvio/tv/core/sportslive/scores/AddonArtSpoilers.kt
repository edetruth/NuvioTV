package com.nuvio.tv.core.sportslive.scores

import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveKind

/**
 * Addon artwork that may tell a result (#182): an addon's poster for a game under way or over can carry the score
 * painted in ("FINISHED 92–83"). Nothing in an item's data says reliably which images do (the picture is opaque,
 * and its title says "FINISHED" only sometimes), so the safe rule applies: under Hide until focused or Off, and for
 * a game saved for later (#151), a game that is live, over or a replay never shows its addon art; the neutral drawn
 * card stands in. Channels, and games that have not started, keep their own art; so does every game under Show.
 */
object AddonArtSpoilers {

    /**
     * True when [item]'s addon poster, background and logo must not show. [mode] null (not loaded yet) reads as Show,
     * the default; [protected] is the game's spoiler protection ([ScoreSnapshot.spoilerProtected], or a saved
     * replay's key in [SpoilerProtection.savedReplayKeys]).
     */
    fun hidesAddonArt(item: SportsLiveItem, mode: ScoresMode?, protected: Boolean, nowMs: Long): Boolean {
        if (item.isChannel || item.kind == SportsLiveKind.CHANNELS) return false
        if (!protected && (mode == null || mode == ScoresMode.SHOW)) return false
        return item.isLive || item.isFinished || item.kind == SportsLiveKind.REPLAY ||
            item.startMs?.let { it <= nowMs } == true || saysOver(item.meta.name) || saysOver(item.meta.releaseInfo)
    }

    // "FINISHED", "FT", "Final", "Full time", "Result": a listing that says the game is over.
    private fun saysOver(text: String?): Boolean = text != null && OVER.containsMatchIn(text)

    private val OVER = Regex("""\b(finished|final|full[ -]?time|ft|result|results|ended)\b""", RegexOption.IGNORE_CASE)
}
