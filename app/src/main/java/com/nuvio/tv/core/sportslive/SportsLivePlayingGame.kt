package com.nuvio.tv.core.sportslive

import com.nuvio.tv.domain.model.SportsLiveItem
import kotlin.math.abs

/**
 * The game playing and its copies (J5, user TV report 2026-11-21: watching "Liverpool vs Chelsea", the panel's first
 * row was another addon's unmerged "Liverpool FC vs Chelsea FC", with no sign it was the same game, and OK on it started
 * a new stream and buffered again). A row that is the playing game is treated as it: the lists leave it out as they do
 * the playing game's own row, and OK on it only goes back to the full-screen game, never restarting the stream.
 */
object SportsLivePlayingGame {
    /** Two timed copies of one game start at most this far apart ("the same day" for a game listed twice). */
    private const val SAME_DAY_MS = 12 * 60 * 60 * 1000L

    /**
     * True when [item] is [playing]'s game: the same source (its stream key, or one of either's merged sources), or the
     * same two sides either way round once names are folded (club suffixes, accents, aliases: [SportsLiveTeams.mergeMatch])
     * on the same day. A channel or an entry with no two sides is only ever itself.
     */
    fun isSameGame(item: SportsLiveItem, playing: SportsLiveItem): Boolean {
        val keys = sourceKeys(playing)
        if (sourceKeys(item).any { it in keys }) return true
        if (item.isChannel || playing.isChannel) return false
        val a = item.teams ?: return false
        val b = playing.teams ?: return false
        if (SportsLiveTeams.isPlaceholderGame(a) || SportsLiveTeams.isPlaceholderGame(b)) return false
        val sides = (sameSide(a.home, b.home) && sameSide(a.away, b.away)) || (sameSide(a.home, b.away) && sameSide(a.away, b.home))
        return sides && sameDay(item, playing)
    }

    private fun sourceKeys(item: SportsLiveItem): Set<String> = (listOf(item) + item.alternates).mapTo(HashSet()) { it.streamKey }

    private fun sameSide(x: String, y: String): Boolean = SportsLiveTeams.mergeMatch(x, y) != SportsLiveTeams.MergeMatch.NONE

    private fun sameDay(a: SportsLiveItem, b: SportsLiveItem): Boolean {
        val startA = a.startMs
        val startB = b.startMs
        if (startA != null && startB != null) return abs(startA - startB) <= SAME_DAY_MS
        // Listed live (or undated) together: the same game now.
        return (a.isLive || startA == null) && (b.isLive || startB == null)
    }
}
