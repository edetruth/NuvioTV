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

/** One league row: its blocks in time order (start, then lane) and how many [lanes] they stack into. */
@Immutable
data class TimelineLeague(val name: String, val followed: Boolean, val lanes: Int, val blocks: List<TimelineBlock>)

/** A local hour boundary on the ruler; [midnight] ticks carry the day. */
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

    /** Local hour boundaries from [startMs] to [endMs] (both on hours), each a real hour apart. */
    fun ticks(startMs: Long, endMs: Long, zone: ZoneId): List<TimelineTick> {
        val ticks = ArrayList<TimelineTick>()
        var t = floorHour(startMs, zone)
        while (t <= endMs && ticks.size < MAX_TICKS) {
            ticks += TimelineTick(t, Instant.ofEpochMilli(t).atZone(zone).hour == 0)
            t = nextHour(t, zone)
        }
        return ticks
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
        for (row in rows) {
            val item = row.item
            // A kickoff with a time of day: a date alone never sits on the ruler (nor counts down).
            val start = row.art?.takeIf { it.hasTimeOfDay }?.startMs ?: item.startMs?.takeIf { item.startHasTimeOfDay }
            if (start == null) {
                untimed += row
                continue
            }
            val kind = when {
                row.chip is ScoreChip.Final || row.group == ScoreboardGroup.FINAL -> TimelineBlockKind.FINAL
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
        val (viewStart, viewMs) = window(tab, nowMs)
        val all = leagues.flatMap { it.blocks }
        val start = floorHour(minOf(viewStart, all.minOfOrNull { it.startMs } ?: viewStart), zone)
        val end = ceilHour(maxOf(viewStart + viewMs, all.maxOfOrNull { it.endMs } ?: 0L), zone)
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

    /** A guard against a runaway ruler (a start far in the future): two weeks of hours. */
    private const val MAX_TICKS = 24 * 14
}

/** A card's key in lists (unique within a tab: one copy per addon), as the card list keys it. */
internal val SportsLiveItem.timelineKey: String get() = "$addonId|$id"
