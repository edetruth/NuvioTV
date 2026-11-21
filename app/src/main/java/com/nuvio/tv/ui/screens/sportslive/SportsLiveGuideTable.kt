package com.nuvio.tv.ui.screens.sportslive

import android.view.KeyEvent
import androidx.compose.runtime.Immutable
import com.nuvio.tv.core.sportslive.GameStatus
import com.nuvio.tv.core.sportslive.GameStatusStrings
import com.nuvio.tv.core.sportslive.SportsLiveGuideStyle
import com.nuvio.tv.core.sportslive.SportsLiveReadyMark
import com.nuvio.tv.core.sportslive.scores.GameArtTeam
import com.nuvio.tv.ui.screens.sportslive.SportsLiveGuideKey.CONSUMED
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The in-player guide, design B2 (docs/design/in-player-guide-brief.md, "B2 trims"): while it is open the video
 * squeezes into a window at the top right and a full-width table lists the games under the pinned Now playing. This
 * file is its pure part (rows, marks, keys, selection, the squeeze's geometry); the drawing is in
 * SportsLiveGuideTableView.kt. The data (the frozen lineup, chips, Ready, scores and spoiler rules) is the live games
 * panel's own ([SportsLiveZapPanelState]): only the presentation differs.
 */

/** A mark after a guide row's names (B2), only when it matters; most rows have none. */
internal enum class GuideMark {
    /** Amber stopwatch: a close game late on, or in overtime ([ScoreboardRow.closeGame]), with its score shown. */
    CLOSE_LATE,

    /** Gold ★: a followed team plays. */
    MY_TEAM,

    /** Small green dot: a verified stream is held. Only on my games ([SportsLiveGameReadiness.rowOf]), never on Now playing. */
    READY
}

/** A guide row draws two teams, or one title (an event, a channel). */
internal enum class GuideRowKind { GAME, TITLE }

/**
 * One row of the guide's table (B2): the dimmed league [tag]; a game's two teams, each as its name forms longest first
 * ([guideNameChoices]: the row shows the longest that fits, a code at worst, never "…"), or a [title] with its
 * [suffix]; the score ("97–92", null when none shows) and the [time] ("Q4 2:31") in one column; the [marks]. A row
 * [dimmedAtRest] (just ended, gone, no stream found) is dimmed unless selected.
 */
@Immutable
internal data class GuideRow(
    val key: String,
    val kind: GuideRowKind,
    val tag: String,
    val firstNames: List<String> = emptyList(),
    val secondNames: List<String> = emptyList(),
    val title: String? = null,
    val suffix: String? = null,
    val score: String? = null,
    /** Hide until focused, not revealed: [time] says "Score hidden" and no number shows. */
    val scoreHidden: Boolean = false,
    val time: String? = null,
    val live: Boolean = false,
    val marks: List<GuideMark> = emptyList(),
    val dimmedAtRest: Boolean = false,
    val isNew: Boolean = false
)

/**
 * [board] (the Scoreboard row the panel draws from, scores mode and spoiler rules already applied) as a guide row.
 * [readyMark] is the row's readiness ([SportsLiveGameReadiness.rowOf], already "my games" only); [nowPlaying] rows
 * never show Ready (the stream is playing). [codeOf] gives ESPN's code for an unmatched side's name, when known;
 * [scoreText] words a score ("97–92").
 */
internal fun guideRowOf(
    board: ScoreboardRow,
    strings: GameStatusStrings,
    kickoff: String?,
    tag: String,
    readyMark: SportsLiveReadyMark? = null,
    nowPlaying: Boolean = false,
    gone: Boolean = false,
    isNew: Boolean = false,
    suffix: String? = null,
    individual: Boolean = false,
    codeOf: (String) -> String? = { null },
    scoreText: (Int, Int) -> String = { first, second -> "$first–$second" }
): GuideRow {
    val status = scoreboardStatusLines(board, strings, kickoff)
    val scores = scoreboardScoresOf(board.chip)
    val shown = scores as? ScoreboardScores.Shown
    val halves = panelPlaysHalves((board.status as? GameStatus.Live)?.event?.league)
    val art = board.art
    val game = board.group != ScoreboardGroup.CHANNELS && board.twoSided && art != null
    return GuideRow(
        key = board.item.streamKey,
        kind = if (game) GuideRowKind.GAME else GuideRowKind.TITLE,
        tag = tag,
        firstNames = if (game) guideNameChoices(art!!.first, individual, codeOf) else emptyList(),
        secondNames = if (game) guideNameChoices(art!!.second, individual, codeOf) else emptyList(),
        title = if (game) null else panelTitleOf(board.item),
        suffix = if (game) null else suffix,
        score = shown?.let { scoreText(it.first, it.second) },
        scoreHidden = scores is ScoreboardScores.Hidden,
        time = guideTimeText(status, halves),
        live = status.live,
        // "Close and late" says how the game is going: only beside a score that shows (Hide until focused keeps it).
        marks = guideMarks(closeLate = board.closeGame && shown != null, myTeam = board.followed, ready = readyMark, nowPlaying = nowPlaying),
        dimmedAtRest = board.dimmed(focused = false) || gone || readyMark == SportsLiveReadyMark.NO_STREAM,
        isNew = isNew
    )
}

/**
 * The time half of the score · time column (B2): a live game's period, short, then its clock ("Q4 2:31", "2H 8:12",
 * "67'"), or the live word when ESPN gives no clock; any other row its status words ("8:30 PM · in 12 min", "Final",
 * "Score hidden"). Null when there is nothing to say (a channel).
 */
internal fun guideTimeText(status: ScoreboardStatusLines, halves: Boolean = false): String? {
    if (status.live) {
        val period = status.secondary?.let { panelPeriodShort(it, halves) }
        return listOfNotNull(period, status.primary).joinToString(" ").ifEmpty { null }
    }
    return listOfNotNull(status.primary, status.secondary).joinToString(GUIDE_TIME_SEPARATOR).ifEmpty { null }
}

private const val GUIDE_TIME_SEPARATOR = " · "

/** A row's marks (B2), in drawing order: stopwatch, ★, Ready. */
internal fun guideMarks(closeLate: Boolean, myTeam: Boolean, ready: SportsLiveReadyMark?, nowPlaying: Boolean): List<GuideMark> =
    buildList {
        if (closeLate) add(GuideMark.CLOSE_LATE)
        if (myTeam) add(GuideMark.MY_TEAM)
        if (ready == SportsLiveReadyMark.READY && !nowPlaying) add(GuideMark.READY)
    }

/**
 * A team's names on a guide row, longest first (B2, ~480 px per team): its full name ("Maryland Eastern Shore"),
 * ESPN's short name, then its code (ESPN's, else [codeOf] by name, else one derived, [panelShortCode]), so the row
 * shows the longest that fits and never cuts a name. A player in a one-on-one sport ([individual]) steps down to last
 * names instead ([panelSideChoices]).
 */
internal fun guideNameChoices(team: GameArtTeam, individual: Boolean, codeOf: (String) -> String? = { null }): List<String> {
    val name = panelCleanText(team.name)
    if (individual) return panelSideChoices(name, individual = true)
    val short = panelCleanText(team.shortName)
    val code = team.short.trim().takeIf { it.isNotEmpty() && it != team.name.trim() }
        ?: codeOf(name)?.trim()?.takeIf { it.isNotEmpty() }
        ?: panelShortCode(name)
    return listOf(name, short, code).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

// ---- The footer's legend ----

/** What the footer's legend explains, in order (B2: the profile's first few openings, or Info). */
internal enum class GuideLegend { HOLD_OK, CLOSE_LATE, MY_TEAM, READY }

/**
 * The legend after the footer's keys while the keys hint is [shown]: "Hold OK More" first (the actions are found
 * nowhere else on screen), then the three marks; nothing otherwise.
 */
internal fun guideLegend(shown: Boolean): List<GuideLegend> = if (shown) GuideLegend.entries.toList() else emptyList()

// ---- Selection ----

/** The table shows this many rows under Now playing (B2). */
internal const val GUIDE_VISIBLE_ROWS = 5

/** The lines the table lists (B2: no section headers): game rows, then "N more hidden by My sports". */
internal fun guideLines(guide: PanelGuide): List<PanelGuideEntry> = guide.entries.filterNot { it is PanelGuideEntry.Header }

/**
 * Where the selection opens (brief B): on the game ▼ would zap to, so open + OK does what one zap does: the first
 * zappable row after the game playing ([currentKey]) in the [lineup], wrapping, that the table lists; else the first line.
 */
internal fun guideOpeningKey(lines: List<PanelGuideEntry>, lineup: List<ZapPanelRow>, currentKey: String?): String? {
    val listed = lines.mapNotNullTo(HashSet()) { (it as? PanelGuideEntry.Game)?.key }
    val at = lineup.indexOfFirst { it.item.streamKey == currentKey }
    if (at >= 0) {
        for (step in 1 until lineup.size) {
            val row = lineup[(at + step) % lineup.size]
            if (row.zappable && row.item.streamKey in listed) return row.item.streamKey
        }
    }
    return lines.firstOrNull()?.key
}

/** The selected index after a move of [delta] rows among [size]: never past either end; -1 when empty. */
internal fun guideMove(index: Int, delta: Int, size: Int): Int = if (size <= 0) -1 else (index + delta).coerceIn(0, size - 1)

/**
 * The selected index in [keys] (an update may move or drop rows): the row [selectedKey] where it is listed, else the
 * one now at [lastIndex] (the nearest to where it was); -1 when the table is empty.
 */
internal fun guideReselect(keys: List<String>, selectedKey: String?, lastIndex: Int): Int {
    if (keys.isEmpty()) return -1
    val at = if (selectedKey == null) -1 else keys.indexOf(selectedKey)
    return if (at >= 0) at else lastIndex.coerceIn(0, keys.size - 1)
}

/** The first row shown: [start] moved as little as keeps [selected] in a window of [visible] of [size] rows. */
internal fun guideWindowStart(start: Int, selected: Int, size: Int, visible: Int = GUIDE_VISIBLE_ROWS): Int {
    val last = (size - visible).coerceAtLeast(0)
    if (selected < 0) return start.coerceIn(0, last)
    val moved = when {
        selected < start -> selected
        selected >= start + visible -> selected - visible + 1
        else -> start
    }
    return moved.coerceIn(0, last)
}

/** The chip index ◂ (−1) or ▸ (+1) moves to from [selected] among [chips]; null at either end (no wrap). */
internal fun guideChipStep(chips: List<String?>, selected: String?, delta: Int): Int? {
    if (chips.isEmpty()) return null
    val at = chips.indexOf(selected).coerceAtLeast(0)
    val next = at + delta
    return next.takeIf { it in chips.indices && it != at }
}

/**
 * A sport chip's words: its [name], with ★ only on "My teams" (★ means your team, nowhere else: coordinator decision 4,
 * so "My sports" has none), then its [count] of live games, except on My sports scoping the guide ([scoped], the
 * mockup's bare "My sports").
 */
internal fun guideChipText(chip: String?, name: String, count: Int?, scoped: Boolean): String {
    val label = if (chip == SportsLiveUiState.MY_TEAMS_FILTER) "★ $name" else name
    val shown = count?.takeIf { !(chip == null && scoped) }
    return if (shown != null) "$label $shown" else label
}

/** The chips drawn: at most [max] around [selected] of [count], so the picked one always shows. */
internal fun guideChipWindow(count: Int, selected: Int, max: Int = GUIDE_MAX_CHIPS): IntRange {
    if (count <= max) return 0 until count
    val first = (selected - max / 2).coerceIn(0, count - max)
    return first until first + max
}

internal const val GUIDE_MAX_CHIPS = 4

// ---- Keys ----

/** What a key does in the open guide ([SportsLiveGuideKeys]). */
internal enum class SportsLiveGuideKey {
    /** Not the guide's (Back, Info…): the player handles it. */
    PASS,

    /** Taken, nothing to do (a release, a throttled repeat, OK's press before its release). */
    CONSUMED,
    UP,
    DOWN,
    PAGE_UP,
    PAGE_DOWN,
    PREVIOUS_SPORT,
    NEXT_SPORT,

    /** OK released: watch the selected game (the guide closes). */
    WATCH,

    /** Hold OK, or Menu: the row's actions (Watch both, Choose stream, Game stats, Follow). */
    MORE
}

/**
 * The open guide's keys (B2, decided defaults): ▲ ▼ move the selection (held: at a steady pace), CH+ CH− a page;
 * ◂ ▸ change the sport chip; OK watches the selected game on its release, Hold OK (or Menu) opens its actions. Every
 * D-pad event is taken, so nothing reaches the player behind; Back and the rest pass.
 */
internal class SportsLiveGuideKeys(
    private val longPressMs: Long = GUIDE_LONG_PRESS_MS,
    private val repeatGateMs: Long = GUIDE_REPEAT_GATE_MS
) {
    // OK's press seen by the guide (its event time), -1 when none; and whether its hold already opened the actions.
    private var okDownAt = -1L
    private var okHeld = false
    private var lastMoveAt = Long.MIN_VALUE / 2

    fun onKey(keyCode: Int, action: Int, repeatCount: Int, eventTime: Long): SportsLiveGuideKey {
        val down = action == KeyEvent.ACTION_DOWN
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> move(down, repeatCount, eventTime, SportsLiveGuideKey.UP)
            KeyEvent.KEYCODE_DPAD_DOWN -> move(down, repeatCount, eventTime, SportsLiveGuideKey.DOWN)
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> move(down, repeatCount, eventTime, SportsLiveGuideKey.PAGE_UP)
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> move(down, repeatCount, eventTime, SportsLiveGuideKey.PAGE_DOWN)
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down && repeatCount == 0) SportsLiveGuideKey.PREVIOUS_SPORT else CONSUMED
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (down && repeatCount == 0) SportsLiveGuideKey.NEXT_SPORT else CONSUMED
            KeyEvent.KEYCODE_MENU -> if (down && repeatCount == 0) SportsLiveGuideKey.MORE else CONSUMED
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> ok(down, action, repeatCount, eventTime)
            else -> SportsLiveGuideKey.PASS
        }
    }

    private fun move(down: Boolean, repeatCount: Int, eventTime: Long, key: SportsLiveGuideKey): SportsLiveGuideKey {
        if (!down) return CONSUMED
        if (repeatCount > 0 && eventTime - lastMoveAt < repeatGateMs) return CONSUMED
        lastMoveAt = eventTime
        return key
    }

    private fun ok(down: Boolean, action: Int, repeatCount: Int, eventTime: Long): SportsLiveGuideKey = when {
        down && repeatCount == 0 -> {
            okDownAt = eventTime
            okHeld = false
            CONSUMED
        }
        down -> if (okDownAt >= 0 && !okHeld) {
            okHeld = true
            SportsLiveGuideKey.MORE
        } else {
            CONSUMED
        }
        action == KeyEvent.ACTION_UP -> {
            val ours = okDownAt >= 0
            val held = okHeld
            val long = ours && eventTime - okDownAt >= longPressMs
            okDownAt = -1L
            okHeld = false
            when {
                // The release of a press the guide never saw (the OK that opened it): nothing.
                !ours || held -> CONSUMED
                long -> SportsLiveGuideKey.MORE
                else -> SportsLiveGuideKey.WATCH
            }
        }
        else -> CONSUMED
    }
}

internal const val GUIDE_LONG_PRESS_MS = 500L
internal const val GUIDE_REPEAT_GATE_MS = 112L

/**
 * OK with the guide closed (B2: OK opens it; a short ◂ still does): its press and repeats are taken while [active]
 * (the new guide on, a live game playing, nothing else wanting OK), and its release opens the guide
 * ([SportsLiveShortPress.OPEN_PANEL]), so that release can never reach a row and play it.
 */
internal class SportsLiveGuideOkKey {
    private var armed = false

    fun onKey(keyCode: Int, action: Int, repeatCount: Int, active: Boolean): SportsLiveShortPress {
        if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER && keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER) {
            return SportsLiveShortPress.NONE
        }
        return when (action) {
            KeyEvent.ACTION_DOWN -> {
                if (repeatCount == 0) armed = active
                if (armed) SportsLiveShortPress.CONSUMED else SportsLiveShortPress.NONE
            }
            KeyEvent.ACTION_UP -> {
                val open = armed
                armed = false
                if (open) SportsLiveShortPress.OPEN_PANEL else SportsLiveShortPress.NONE
            }
            else -> SportsLiveShortPress.NONE
        }
    }
}

// ---- Idle close ----

/** The guide closes by itself after this long with no key pressed in it (B2 decided default). */
internal const val GUIDE_IDLE_CLOSE_MS = 20_000L

/**
 * Closes the open guide after [timeoutMs] with no key ([kick] on opening and on every key; [stop] on closing and while
 * a menu over it is open). Runs in [scope]; [onIdle] is called once per quiet spell.
 */
internal class SportsLiveGuideIdle(
    private val scope: CoroutineScope,
    private val timeoutMs: Long = GUIDE_IDLE_CLOSE_MS,
    private val onIdle: () -> Unit
) {
    private var job: Job? = null

    val running: Boolean get() = job?.isActive == true

    fun kick() {
        job?.cancel()
        job = scope.launch {
            delay(timeoutMs)
            job = null
            onIdle()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}

// ---- The video squeeze ----

/**
 * How the video goes to its window as the guide opens (to compare on the onn TV, [SportsLiveGuideStyle]): [SCALE] animates a graphics-layer
 * scale and move of the same video container over [GUIDE_SQUEEZE_MS] (no layout change, so the surface keeps its
 * size); [JUMP] resizes the container's layout once, at once, and only the guide fades in. Neither re-attaches the
 * surface nor touches the player.
 */
internal enum class GuideSqueezeMode { SCALE, JUMP }

/** The squeeze of a guide [style] (live.21: one build compares both on the TV); null for the side panel. */
internal fun guideSqueezeModeOf(style: SportsLiveGuideStyle): GuideSqueezeMode? = when (style) {
    SportsLiveGuideStyle.PANEL -> null
    SportsLiveGuideStyle.TABLE_SMOOTH -> GuideSqueezeMode.SCALE
    SportsLiveGuideStyle.TABLE_JUMP -> GuideSqueezeMode.JUMP
}

/** How long the guide fades in and out with [mode]: with the smooth squeeze, as long as the video moves. */
internal fun guideFadeMs(mode: GuideSqueezeMode?): Int = if (mode == GuideSqueezeMode.JUMP) GUIDE_FADE_MS else GUIDE_SQUEEZE_MS

internal const val GUIDE_SQUEEZE_MS = 200
internal const val GUIDE_FADE_MS = 150

/** The video window's width as a share of the screen (B2: 768 of 1920 px). */
internal const val GUIDE_VIDEO_SCALE = 0.4f

/** The 5 % safe margin the window keeps from the top and right edges. */
internal const val GUIDE_SAFE_INSET = 0.05f

/** The video window in px, on a [width] × [height] screen: top right inside the safe area (1080p: 1056, 54, 768 × 432). */
internal data class GuideVideoWindow(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

internal fun guideVideoWindow(width: Float, height: Float): GuideVideoWindow {
    val w = width * GUIDE_VIDEO_SCALE
    val h = height * GUIDE_VIDEO_SCALE
    return GuideVideoWindow(left = width * (1f - GUIDE_SAFE_INSET) - w, top = height * GUIDE_SAFE_INSET, width = w, height = h)
}

/** The video container's graphics layer at [progress] (0 full screen, 1 the window), scaled about its top-right corner. */
internal data class GuideSqueezeTransform(val scale: Float, val translationX: Float, val translationY: Float)

internal fun guideSqueezeTransform(progress: Float, width: Float, height: Float): GuideSqueezeTransform {
    val p = progress.coerceIn(0f, 1f)
    return GuideSqueezeTransform(
        scale = 1f - (1f - GUIDE_VIDEO_SCALE) * p,
        translationX = -width * GUIDE_SAFE_INSET * p,
        translationY = height * GUIDE_SAFE_INSET * p
    )
}
