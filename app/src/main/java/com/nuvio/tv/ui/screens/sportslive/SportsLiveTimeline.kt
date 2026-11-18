package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.runtime.Immutable
import com.nuvio.tv.core.sportslive.EndedGame
import com.nuvio.tv.core.sportslive.EndedSignal
import com.nuvio.tv.core.sportslive.SportsLiveGameLength
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.SportsLiveTab
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** How a Timeline block is drawn: live (red), upcoming (outlined) or over (dimmed). */
enum class TimelineBlockKind { LIVE, UPCOMING, FINAL }

/**
 * A game on the ruler: its Scoreboard [row] (chip, art, follow and ended state, spoiler rule applied),
 * where it sits in time ([startMs] to [endMs], an estimate: ESPN gives no length) and its [lane] in the
 * league row. [startsIn]: (hours, minutes) to a timed kickoff of an upcoming game, at the model's clock.
 */
@Immutable
data class TimelineBlock(
    val row: ScoreboardRow,
    val kind: TimelineBlockKind,
    val startMs: Long,
    val endMs: Long,
    val lane: Int,
    val startsIn: Pair<Int, Int>? = null
) {
    val key: String get() = row.item.timelineKey
}

/**
 * One league row: its blocks in time order (start, then lane) and how many [lanes] they stack into. [id]
 * is the league's stable key (its name folded), for the row list.
 */
@Immutable
data class TimelineLeague(val id: String, val name: String, val followed: Boolean, val lanes: Int, val blocks: List<TimelineBlock>)

/** A local hour boundary on the ruler; [midnight] ticks are a new local day's first and carry the day. */
@Immutable
data class TimelineTick(val ms: Long, val midnight: Boolean)

/**
 * The Timeline of one tab: [leagues] on a ruler from [startMs] to [endMs] (local hours, covering every
 * block and the opening window), the opening window [viewStartMs] + [viewMs], hour [ticks], and the
 * games with no time of day ([untimed]: channels, date-only starts), listed apart in a "No time" row.
 */
@Immutable
data class TimelineModel(
    val leagues: List<TimelineLeague>,
    val untimed: List<ScoreboardRow>,
    val startMs: Long,
    val endMs: Long,
    val viewStartMs: Long,
    val viewMs: Long,
    val ticks: List<TimelineTick>,
    val nowMs: Long
) {
    /** The rows ← → move along and ↑ ↓ move between: leagues, then the "No time" row. */
    val navRows: List<List<String>> by lazy {
        leagues.map { league -> league.blocks.map { it.key } } +
            listOfNotNull(untimed.map { it.item.timelineKey }.takeIf { it.isNotEmpty() })
    }

    /** Every game in navigation order: the screen's item list. */
    val items: List<SportsLiveItem> by lazy {
        leagues.flatMap { league -> league.blocks.map { it.row.item } } + untimed.map { it.item }
    }

    val blocks: Map<String, TimelineBlock> by lazy { leagues.flatMap { it.blocks }.associateBy { it.key } }
}

/** A remote move on the Timeline. */
enum class TimelineMove { LEFT, RIGHT, UP, DOWN }

/**
 * The Matchday timeline's model (#24): Live now's or Upcoming's rows placed on a time ruler, with its D-pad
 * moves. No longer a layout of either tab (V2, #132); kept, pure and tested, for a future full-screen Guide
 * (#25), which will draw it.
 */
internal object SportsLiveTimeline {
    private const val HOUR_MS = 3_600_000L
    /** Live now opens on now − 1 h … now + 5 h. */
    const val LIVE_BEFORE_MS = HOUR_MS
    const val LIVE_VIEW_MS = 6 * HOUR_MS
    /** Upcoming opens on the next 12 hours. */
    const val UPCOMING_VIEW_MS = 12 * HOUR_MS
    /** A block is never drawn shorter than this, so a game just started or just ended stays a target. */
    const val MIN_BLOCK_MS = 15 * 60_000L

    /** The opening window for [tab] at [nowMs]: (start, length). */
    fun window(tab: SportsLiveTab, nowMs: Long): Pair<Long, Long> =
        if (tab == SportsLiveTab.UPCOMING) nowMs to UPCOMING_VIEW_MS else (nowMs - LIVE_BEFORE_MS) to LIVE_VIEW_MS

    /** How long [item]'s block runs: its sport's length (#60 table) without the late-start buffer. */
    fun lengthOf(item: SportsLiveItem): Long =
        (SportsLiveGameLength.of(item) ?: (SportsLiveGameLength.UNKNOWN_MS + SportsLiveGameLength.LATE_START_MS)) -
            SportsLiveGameLength.LATE_START_MS

    /** [ms] floored to its local hour in [zone]. */
    fun floorHour(ms: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(ms).atZone(zone).truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli()

    /** [ms] ceiled to a local hour in [zone] (itself when already on one). */
    fun ceilHour(ms: Long, zone: ZoneId): Long {
        val floor = floorHour(ms, zone)
        return if (floor == ms) ms else nextHour(floor, zone)
    }

    /** The local hour boundary after [hourMs], an hour later on the instant timeline (DST included). */
    private fun nextHour(hourMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(hourMs).atZone(zone).plusHours(1).truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli()

    /**
     * Local hour boundaries from [startMs] to [endMs] (both on hours), each a real hour apart. A new local
     * day's first tick is its midnight, also where DST skips 00:00 (the day then starts at 01:00).
     */
    fun ticks(startMs: Long, endMs: Long, zone: ZoneId): List<TimelineTick> {
        val ticks = ArrayList<TimelineTick>()
        var t = floorHour(startMs, zone)
        var day = Instant.ofEpochMilli(t).atZone(zone).toLocalDate().minusDays(1)
        while (t <= endMs && ticks.size < MAX_TICKS) {
            val local = Instant.ofEpochMilli(t).atZone(zone)
            ticks += TimelineTick(t, local.toLocalDate() != day && (ticks.isNotEmpty() || local.hour == 0))
            day = local.toLocalDate()
            t = nextHour(t, zone)
        }
        return ticks
    }

    /**
     * The part of a block from [left] to [right] (px on the ruler) drawn at window offset [scroll] of
     * width [viewport]: clamped to the window, so a block longer than the window (an 8 h live game) is
     * never laid out wider than the screen and pushed out of view. A block wholly outside keeps its own
     * span (it is off screen anyway). Returns (left, right).
     */
    fun visibleSpan(left: Float, right: Float, scroll: Float, viewport: Float): Pair<Float, Float> {
        val from = maxOf(left, scroll)
        val to = minOf(right, scroll + viewport)
        return if (to > from) from to to else left to minOf(right, left + viewport)
    }

    /**
     * Where the window scrolls to show a focused block from [left] to [right], or null when enough of it
     * is on screen already (its whole width, or a quarter of the window, as [visibleSpan] draws it). A
     * block mostly right of the window comes in with its start half an hour from the left edge; one
     * mostly left of it shows its last part (a long live game: its end, now, at three quarters of the
     * window) or, if short, its start. Clamped to [minScroll]..[maxScroll].
     */
    fun revealTarget(
        left: Float,
        right: Float,
        scroll: Float,
        viewport: Float,
        hourPx: Float,
        minScroll: Float,
        maxScroll: Float
    ): Float? {
        val shown = minOf(right, scroll + viewport) - maxOf(left, scroll)
        if (shown >= minOf(right - left, viewport * 0.25f)) return null
        val target = if ((left + right) / 2 < scroll + viewport / 2) {
            maxOf(left - hourPx * 0.5f, right - viewport * 0.75f)
        } else {
            left - hourPx * 0.5f
        }
        return target.coerceIn(minScroll, maxOf(minScroll, maxScroll))
    }

    /**
     * Stacks blocks (one league, any order) into lanes: by start, each takes the first lane whose last
     * block has ended. Returns them in time order (start, then lane) with the lane set.
     */
    fun stack(blocks: List<TimelineBlock>): List<TimelineBlock> {
        val laneEnds = ArrayList<Long>()
        return blocks.sortedWith(compareBy<TimelineBlock>({ it.startMs }, { it.endMs })).map { block ->
            val lane = laneEnds.indexOfFirst { it <= block.startMs }.takeIf { it >= 0 } ?: laneEnds.size
            if (lane == laneEnds.size) laneEnds += block.endMs else laneEnds[lane] = block.endMs
            block.copy(lane = lane)
        }.sortedWith(compareBy({ it.startMs }, { it.lane }))
    }

    /**
     * The window offset for ← on a row's first block or → on its last ([move]), the block from [left] to
     * [right]: half a window on, but never so far that less than a quarter window (or the whole block)
     * of it stays on screen.
     */
    fun edgeScroll(move: TimelineMove, left: Float, right: Float, scroll: Float, viewport: Float): Float {
        val keep = minOf(right - left, viewport * 0.25f)
        return if (move == TimelineMove.LEFT) {
            maxOf(scroll - viewport / 2, minOf(scroll, left + keep - viewport))
        } else {
            minOf(scroll + viewport / 2, maxOf(scroll, right - keep))
        }
    }

    /**
     * The Timeline for [rows] (the tab's Scoreboard rows, spoiler setting applied) at [nowMs] in [zone].
     * [ended] are the Just ended verdicts (#60, Live now only): a final's block ends where ESPN's final was
     * first seen.
     */
    fun model(
        rows: List<ScoreboardRow>,
        tab: SportsLiveTab,
        nowMs: Long,
        zone: ZoneId,
        ended: Map<String, EndedGame> = emptyMap()
    ): TimelineModel {
        val untimed = ArrayList<ScoreboardRow>()
        val byLeague = LinkedHashMap<String, MutableList<TimelineBlock>>()
        val names = HashMap<String, String>()
        val followed = HashSet<String>()
        // A kickoff with a time of day: a date alone never sits on the ruler (nor counts down).
        fun startOf(row: ScoreboardRow): Long? =
            row.art?.takeIf { it.hasTimeOfDay }?.startMs ?: row.item.startMs?.takeIf { row.item.startHasTimeOfDay }
        val (viewStart, viewMs) = window(tab, nowMs)
        // The ruler spans at most [MAX_TICKS] hours: a game starting past that is listed under "No time".
        val rulerStart = floorHour(minOf(viewStart, rows.mapNotNull(::startOf).minOrNull() ?: viewStart), zone)
        val rulerCap = rulerStart + (MAX_TICKS - 1) * HOUR_MS
        for (row in rows) {
            val item = row.item
            val start = startOf(row)
            if (start == null || start >= rulerCap) {
                untimed += row
                continue
            }
            val kind = when {
                row.chip is ScoreChip.Final || row.group == ScoreboardGroup.JUST_ENDED -> TimelineBlockKind.FINAL
                row.group == ScoreboardGroup.LIVE -> TimelineBlockKind.LIVE
                else -> TimelineBlockKind.UPCOMING
            }
            val estimated = start + lengthOf(item)
            val endedGame = ended[item.streamKey]
            val end = when (kind) {
                TimelineBlockKind.LIVE -> maxOf(nowMs, estimated)
                TimelineBlockKind.UPCOMING -> estimated
                TimelineBlockKind.FINAL -> when (endedGame?.signal) {
                    EndedSignal.FINAL -> endedGame.endedAtMs
                    EndedSignal.ESTIMATE -> estimated
                    null -> minOf(nowMs, estimated)
                }
            }.coerceAtLeast(start + MIN_BLOCK_MS)
            val league = (row.art?.league ?: item.sport ?: item.catalogName).trim()
            val leagueKey = league.lowercase()
            names.putIfAbsent(leagueKey, league)
            if (row.firstFollowed || row.secondFollowed || item.followed) followed += leagueKey
            byLeague.getOrPut(leagueKey) { ArrayList() } += TimelineBlock(
                row = row,
                kind = kind,
                startMs = start,
                endMs = end,
                lane = 0,
                startsIn = if (kind == TimelineBlockKind.UPCOMING) scoreboardStartsIn(start, nowMs) else null
            )
        }
        val leagues = byLeague.map { (key, blocks) ->
            val stacked = stack(blocks)
            TimelineLeague(
                id = key,
                name = names.getValue(key),
                followed = key in followed,
                lanes = (stacked.maxOfOrNull { it.lane } ?: 0) + 1,
                blocks = stacked
            )
        }.sortedWith(
            compareByDescending<TimelineLeague> { it.followed }
                .thenByDescending { league -> league.blocks.any { it.kind == TimelineBlockKind.LIVE } }
                .thenBy { league -> league.blocks.filter { it.kind != TimelineBlockKind.FINAL }.minOfOrNull { it.startMs } ?: Long.MAX_VALUE }
                .thenBy { it.name.lowercase() }
        )
        val all = leagues.flatMap { it.blocks }
        val start = rulerStart
        val end = minOf(ceilHour(maxOf(viewStart + viewMs, all.maxOfOrNull { it.endMs } ?: 0L), zone), rulerCap)
        return TimelineModel(
            leagues = leagues,
            untimed = untimed,
            startMs = start,
            endMs = end,
            viewStartMs = viewStart,
            viewMs = viewMs,
            ticks = ticks(start, end, zone),
            nowMs = nowMs
        )
    }

    /**
     * Where [move] from the block [fromKey] lands, or null at an edge. ← → the previous / next block of
     * the row in time; ↑ ↓ the adjacent row's block nearest in time to this one's start (one running
     * then first); the "No time" row by position (from it, the block nearest now).
     */
    fun moveTarget(model: TimelineModel, fromKey: String, move: TimelineMove): String? {
        val rows = model.navRows
        val rowIndex = rows.indexOfFirst { fromKey in it }
        if (rowIndex < 0) return null
        val row = rows[rowIndex]
        val index = row.indexOf(fromKey)
        return when (move) {
            TimelineMove.LEFT -> row.getOrNull(index - 1)
            TimelineMove.RIGHT -> row.getOrNull(index + 1)
            TimelineMove.UP, TimelineMove.DOWN -> {
                val target = rows.getOrNull(if (move == TimelineMove.UP) rowIndex - 1 else rowIndex + 1) ?: return null
                val anchor = model.blocks[fromKey]?.startMs ?: model.nowMs
                val timed = target.mapNotNull { model.blocks[it] }
                if (timed.isEmpty()) {
                    target.getOrNull(index.coerceAtMost(target.lastIndex)) ?: target.firstOrNull()
                } else {
                    timed.minWithOrNull(
                        compareBy<TimelineBlock> { block ->
                            when {
                                anchor in block.startMs until block.endMs -> 0L
                                anchor < block.startMs -> block.startMs - anchor
                                else -> anchor - block.endMs + 1
                            }
                        }.thenBy { it.lane }
                    )?.key
                }
            }
        }
    }

    /**
     * The block focus enters on: [focusedKey] while shown, else the first live game (rows in order),
     * else the next game to start, else the first one.
     */
    fun entryKey(model: TimelineModel, focusedKey: String?): String? {
        if (focusedKey != null && model.navRows.any { focusedKey in it }) return focusedKey
        val blocks = model.leagues.flatMap { it.blocks }
        return blocks.firstOrNull { it.kind == TimelineBlockKind.LIVE }?.key
            ?: blocks.filter { it.kind == TimelineBlockKind.UPCOMING && it.startMs >= model.nowMs }.minByOrNull { it.startMs }?.key
            ?: model.navRows.firstOrNull()?.firstOrNull()
    }

    /**
     * Where Down from the header enters the grid (QA pass 3): [preferredKey] while its row is among
     * [visibleRows] (the rows laid out now), else the first row on screen's entry game: its first live game,
     * else its next to start, else its first block. The rows are lazy, so a block in a row scrolled away has
     * nothing to take focus. With no row laid out yet, [preferredKey].
     */
    fun onScreenEntryKey(model: TimelineModel, preferredKey: String?, visibleRows: List<Int>): String? {
        val rows = model.navRows
        val shown = visibleRows.filter { it in rows.indices }
        if (shown.isEmpty()) return preferredKey
        if (preferredKey != null && rows.indexOfFirst { preferredKey in it } in shown) return preferredKey
        val keys = rows[shown.min()]
        val blocks = keys.mapNotNull { model.blocks[it] }
        return blocks.firstOrNull { it.kind == TimelineBlockKind.LIVE }?.key
            ?: blocks.filter { it.kind == TimelineBlockKind.UPCOMING && it.startMs >= model.nowMs }.minByOrNull { it.startMs }?.key
            ?: keys.firstOrNull()
    }

    /** How far fast-forward / rewind (or holding ← →) moves the window (#77). */
    const val JUMP_MS = 3 * HOUR_MS

    /** A block in another row counts this much further from a jump's time per row away (#77). */
    private const val JUMP_ROW_PENALTY_MS = 30 * 60_000L

    /**
     * Where a ±3 h jump ([forward] or back) from [fromKey] puts focus (#77): the game starting nearest
     * [JUMP_MS] on from the focused one's start (now, from the "No time" row), among the games starting
     * after it (before it, going back), the focused row first ([JUMP_ROW_PENALTY_MS] per row away). Null
     * when no game lies that way: the window still moves.
     */
    fun jumpTarget(model: TimelineModel, fromKey: String, forward: Boolean): String? {
        val anchor = model.blocks[fromKey]?.startMs ?: model.nowMs
        val target = if (forward) anchor + JUMP_MS else anchor - JUMP_MS
        val fromRow = model.leagues.indexOfFirst { league -> league.blocks.any { it.key == fromKey } }
            .takeIf { it >= 0 } ?: model.leagues.size
        return model.leagues.withIndex().flatMap { (rowIndex, league) ->
            league.blocks
                .filter { it.key != fromKey && if (forward) it.startMs > anchor else it.startMs < anchor }
                .map { block -> block to kotlin.math.abs(block.startMs - target) + kotlin.math.abs(rowIndex - fromRow) * JUMP_ROW_PENALTY_MS }
        }.minWithOrNull(compareBy<Pair<TimelineBlock, Long>> { it.second }.thenBy { it.first.lane })?.first?.key
    }

    /**
     * The window offset (px) after a jump: [JUMP_MS] on or back from [scroll], [hourPx] to an hour,
     * clamped to [minScroll]..[maxScroll].
     */
    fun jumpScroll(scroll: Float, forward: Boolean, hourPx: Float, minScroll: Float, maxScroll: Float): Float {
        val delta = hourPx * (JUMP_MS / HOUR_MS)
        return (if (forward) scroll + delta else scroll - delta).coerceIn(minScroll, maxOf(minScroll, maxScroll))
    }

    /**
     * The hint a league row shows when none of its games is in the window [windowStartMs]..[windowEndMs]
     * (#77): how many start later and the first of them, else how many ended earlier and the latest of
     * those. Null while a game of the row is in view.
     */
    fun rowHint(league: TimelineLeague, windowStartMs: Long, windowEndMs: Long): TimelineRowHint? {
        if (league.blocks.any { it.startMs < windowEndMs && it.endMs > windowStartMs }) return null
        val later = league.blocks.filter { it.startMs >= windowEndMs }
        if (later.isNotEmpty()) return TimelineRowHint(later = true, count = later.size, atMs = later.minOf { it.startMs })
        val earlier = league.blocks.filter { it.endMs <= windowStartMs }
        if (earlier.isEmpty()) return null
        return TimelineRowHint(later = false, count = earlier.size, atMs = earlier.maxOf { it.startMs })
    }

    /**
     * Where the now tag's left edge sits (px in the ruler): [shiftPx] left of the now line at [nowX]
     * less [scroll], kept wholly inside a ruler [viewport] wide while the now line is in the window (N7).
     * Once the line has left the window the tag goes wholly out of view with it, never a clipped sliver
     * at the edge (QA pass 3).
     */
    fun nowTagLeft(nowX: Float, scroll: Float, shiftPx: Float, tagWidth: Float, viewport: Float): Float {
        val line = nowX - scroll
        if (line < 0f) return minOf(line - shiftPx, -tagWidth)
        if (line > viewport) return maxOf(line - shiftPx, viewport)
        return (line - shiftPx).coerceIn(0f, maxOf(0f, viewport - tagWidth))
    }

    /** Whether a span from [aLeft] of width [aWidth] comes within [gap] of one from [bLeft] of [bWidth]. */
    fun spansOverlap(aLeft: Float, aWidth: Float, bLeft: Float, bWidth: Float, gap: Float = 0f): Boolean =
        aLeft < bLeft + bWidth + gap && bLeft < aLeft + aWidth + gap

    /** A guard against a runaway ruler (a start far in the future): two weeks of hours. */
    const val MAX_TICKS = 24 * 14
}

/** What a key does to the Timeline's window (#77). */
enum class TimelineJumpKey { FORWARD, BACK, CONSUME, RELEASE }

/**
 * The Timeline's jump keys (#77): fast-forward / rewind jump ±3 h on their first down (their repeats and
 * release are consumed); holding → / ← jumps once when the key starts repeating (a press's first down
 * still moves a block), then its repeats are consumed until the release ([held]: this press has jumped).
 * Null: the key moves as usual.
 */
internal fun timelineJumpKey(keyCode: Int, action: Int, repeatCount: Int, held: Boolean): TimelineJumpKey? {
    val down = action == android.view.KeyEvent.ACTION_DOWN
    return when (keyCode) {
        android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> when {
            down && repeatCount == 0 ->
                if (keyCode == android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) TimelineJumpKey.FORWARD else TimelineJumpKey.BACK
            else -> TimelineJumpKey.CONSUME
        }
        android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_DPAD_LEFT -> when {
            !down -> if (held) TimelineJumpKey.RELEASE else null
            repeatCount == 0 -> null
            held -> TimelineJumpKey.CONSUME
            keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> TimelineJumpKey.FORWARD
            else -> TimelineJumpKey.BACK
        }
        else -> null
    }
}

/**
 * A league row's edge hint (#77): [count] games [later] than the window, the first at [atMs] ("+2 later ·
 * 7:30 PM"), or that many ended before it, the latest starting at [atMs].
 */
@Immutable
data class TimelineRowHint(val later: Boolean, val count: Int, val atMs: Long)

/** A card's key in lists (unique within a tab: one copy per addon), as the card list keys it. */
internal val SportsLiveItem.timelineKey: String get() = "$addonId|$id"
