package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.EndedGame
import com.nuvio.tv.core.sportslive.GameStatus

/**
 * Settled screens (live.26, ruling 2026-11-22: "nothing reorders unless the user acts, or a quiet indicator offers
 * it"). The view model already keeps Live now's ITEMS in place once shown ([sportsLiveCalm], [sportsLiveFreeze]); the
 * board then grouped its rows again on every build by each game's status, so a score landing late moved a game from
 * Live to Starting soon, a final dropped it to Just ended, and a late match changed a one-line row into a two-line one
 * (another height): rows moved under focus after the first frame.
 *
 * Once the list is settled ([settles]), a new board keeps the shown board's rows where they were: each row takes its
 * fresh status, score and badges, but keeps the place, header and height it was shown with ([inPlace]). A game that
 * ends there reads its final word in place ([endedInPlace]); OK on it says it ended ([sportsLiveSettledOk]). Rows the
 * view model dropped go; a row the shown board did not have (rare: the view model holds new games behind "N new")
 * joins the end of its own group, so nothing above it moves. "N new", a follow, My sports, a tab or filter switch and a
 * long absence are the user's own (or a fresh open): the board shows as built.
 */
internal object SportsLiveSettledRows {
    /**
     * The landing window (amendment 2, "show ready in 1 s"): builds within this long of the list's first frame (the
     * first scores and logos landing) still lay out as built; after it, the list is settled.
     */
    const val READY_MS = 1_000L

    /** True when a list first shown at [shownSinceMs] (null: not shown this visit) is settled at [nowMs]. */
    fun settles(shownSinceMs: Long?, nowMs: Long): Boolean = shownSinceMs != null && nowMs - shownSinceMs >= READY_MS

    /**
     * [built]'s rows laid out as [previous] showed them: [previous]'s order, each row as built but in its shown place
     * ([inPlace]); [previous]'s rows [built] no longer has go; [built]'s rows [previous] did not show join the end of
     * their group's last run (after the last row of an earlier group when their group has none). Nothing shown yet, an
     * emptied list, or nothing of [previous] left: [built] as it is.
     */
    fun settle(previous: List<ScoreboardRow>, built: List<ScoreboardRow>): List<ScoreboardRow> {
        if (previous.isEmpty() || built.isEmpty()) return built
        val builtByKey = HashMap<String, ScoreboardRow>(built.size * 2)
        built.forEach { builtByKey[it.item.streamKey] = it }
        val placed = ArrayList<ScoreboardRow>(built.size)
        val placedKeys = HashSet<String>(built.size * 2)
        for (old in previous) {
            val key = old.item.streamKey
            val fresh = builtByKey[key] ?: continue
            if (placedKeys.add(key)) placed += inPlace(old, fresh)
        }
        if (placed.isEmpty()) return built
        for (fresh in built) {
            if (!placedKeys.add(fresh.item.streamKey)) continue
            val lastOfGroup = placed.indexOfLast { it.group == fresh.group }
            val at = if (lastOfGroup >= 0) lastOfGroup + 1 else placed.indexOfLast { it.group.ordinal < fresh.group.ordinal } + 1
            placed.add(at, fresh)
        }
        return placed
    }

    /**
     * [fresh] in [shown]'s place: its group (the header it sits under, its height and tint) and its one- or two-line
     * layout as shown; everything else (status, score chip, progress, badges, logos) as built.
     */
    fun inPlace(shown: ScoreboardRow, fresh: ScoreboardRow): ScoreboardRow =
        if (fresh.group == shown.group && fresh.twoSided == shown.twoSided) fresh else fresh.copy(group = shown.group, twoSided = shown.twoSided)
}

/**
 * A game that ended while its row was shown (settled screens, amendment 1): it stays where it was, out of the dimmed
 * Just ended strip, and its status column reads its final word ([scoreboardStatusLines]). Only [SportsLiveSettledRows]
 * places a final outside Just ended ([scoreboardGroupOf] files every final there).
 */
internal val ScoreboardRow.endedInPlace: Boolean
    get() = group != ScoreboardGroup.JUST_ENDED && (status is GameStatus.Final || status is GameStatus.JustEnded)

/**
 * This board's rows settled on [previous] ([SportsLiveSettledRows.settle]): only for the same tab and filter, and never
 * for More live's view or an Agenda (whose days are laid out by date). [previous] null: as built.
 */
internal fun SportsLiveBoard.settledOn(previous: SportsLiveBoard?): SportsLiveBoard {
    val built = rows ?: return this
    if (agenda != null || moreLive) return this
    // A board from before "N new" or a follow (another epoch) never lends its order (gemini review #7).
    val before = previous?.takeIf {
        it.tab == tab && it.filter == filter && !it.moreLive && it.agenda == null && it.settleEpoch == settleEpoch
    }?.rows
    if (before.isNullOrEmpty()) return this
    val settled = SportsLiveSettledRows.settle(before, built)
    if (settled === built) return this
    return copy(rows = settled, items = settled.map { it.item })
}

/**
 * The rows a Live now header counts: an ended game kept in place ([endedInPlace]) no longer counts as live, so "Live
 * (12)" agrees with the chips, Home and the panel, which never count a final.
 */
internal fun sportsLiveGroupCounts(rows: List<ScoreboardRow>): Map<ScoreboardGroup, Int> =
    // #373: a game still waiting for ESPN keeps its place under LIVE but is not counted there until it reads LIVE.
    // F7: under LIVE only rows that read LIVE count: not one settled there that ESPN has as scheduled or postponed.
    rows.filterNot {
        it.endedInPlace || (it.group == ScoreboardGroup.LIVE && (it.awaitingScore || it.status !is GameStatus.Live))
    }.groupingBy { it.group }.eachCount()

/**
 * OK on a Live now card ([row]: its shown row, null when none; [ended]: its Just ended verdict): a game ESPN says ended
 * in place opens its Game page, which says it ended (amendment 1: "OK says so"), never a dead live stream; otherwise
 * the usual rule ([sportsLiveCardOk]: an ESPN final in Just ended opens its Game page, every other card plays).
 */
internal fun sportsLiveSettledOk(row: ScoreboardRow?, ended: EndedGame?): SportsLiveCardOk {
    val status = row?.status
    if (row != null && row.endedInPlace && status is GameStatus.Final) return SportsLiveCardOk.OpenGame(status.event)
    return sportsLiveCardOk(ended)
}
