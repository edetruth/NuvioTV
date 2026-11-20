package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.scores.CatchUp
import com.nuvio.tv.core.sportslive.scores.CatchUpItem
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.ui.screens.home.SportsLiveHomeMeta
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveFocus
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** One card of Live Now's "Your teams" strip (#155). */
internal sealed interface YourTeamsEntry {
    val key: String

    /** A followed team's game live now: its Live now row (the stage shows it as the list does). */
    data class Live(val row: ScoreboardRow) : YourTeamsEntry {
        override val key: String get() = "live|" + row.item.streamKey
    }

    /** A followed team's game later today ([startMs] ahead): OK is Remind me, the stage is Upcoming's. */
    data class LaterToday(val row: ScoreboardRow, val startMs: Long) : YourTeamsEntry {
        override val key: String get() = "today|" + row.item.streamKey
    }

    /** A catch-up replay (#58): the stage is Watch replay, never a score. */
    data class Replay(val item: CatchUpItem) : YourTeamsEntry {
        override val key: String get() = "replay|" + item.key
    }
}

/** What the Live Now stage describes while a strip card holds (or last held) focus. */
internal sealed interface YourTeamsStage {
    /** The list's selection: [listKey] is the Live now row a live strip card selects. */
    data class ListRow(val listKey: String) : YourTeamsStage

    data class Upcoming(val row: ScoreboardRow) : YourTeamsStage

    data class Replay(val item: CatchUpItem) : YourTeamsStage
}

/** Where focus lands on entering Live Now's content (#155): never on a catch-up replay. */
internal enum class LiveNowEntry { RESUME, STRIP, LIST, EMPTY }

internal object YourTeams {
    /** A catch-up older than this (from its estimated end) leaves the strip. */
    const val CATCH_UP_MAX_AGE_MS = 48 * 60 * 60_000L

    /**
     * The strip, in order: followed teams' games live now (as the list orders them), then their games later today
     * (soonest first: still ahead and on [nowMs]'s local day in [zone]), then catch-up replays newest first, dropping
     * those over [CATCH_UP_MAX_AGE_MS] old ([CatchUp] already drops watched ones). [liveRows] are Live now's rows,
     * [upcomingRows] Upcoming's followed games. A game with no drawn art (no two sides) is left out.
     */
    fun strip(
        liveRows: List<ScoreboardRow>,
        upcomingRows: List<ScoreboardRow>,
        catchUp: List<CatchUpItem>,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<YourTeamsEntry> {
        val live = liveRows.filter { it.followed && it.group == ScoreboardGroup.LIVE && it.art != null }
            .distinctBy { it.item.streamKey }
            .map { YourTeamsEntry.Live(it) }
        val liveKeys = live.mapTo(HashSet()) { it.row.item.streamKey }
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val later = upcomingRows.asSequence()
            .filter { it.followed && it.group != ScoreboardGroup.LIVE && it.art != null && it.item.streamKey !in liveKeys && !it.item.isLive }
            .mapNotNull { row -> agendaStartOf(row)?.let { start -> YourTeamsEntry.LaterToday(row, start) } }
            .filter { it.startMs > nowMs && Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() == today }
            .distinctBy { it.row.item.streamKey }
            .sortedBy { it.startMs }
            .toList()
        val replays = catchUp.filter { fresh(it, nowMs) }
            .sortedByDescending { it.event.startMs }
            .map { YourTeamsEntry.Replay(it) }
        return live + later + replays
    }

    /** True while [item] ended (estimated) under [CATCH_UP_MAX_AGE_MS] ago. */
    fun fresh(item: CatchUpItem, nowMs: Long): Boolean {
        val start = item.event.startMs ?: return false
        return nowMs - CatchUp.estimatedEndMs(item.event, start) < CATCH_UP_MAX_AGE_MS
    }

    /** The stage for a focused strip card: a live card selects its list row, the others take the stage over. */
    fun stageOf(entry: YourTeamsEntry): YourTeamsStage = when (entry) {
        is YourTeamsEntry.Live -> YourTeamsStage.ListRow(entry.row.item.streamKey)
        is YourTeamsEntry.LaterToday -> YourTeamsStage.Upcoming(entry.row)
        is YourTeamsEntry.Replay -> YourTeamsStage.Replay(entry.item)
    }

    /**
     * The stage while focus is (or was last) on the strip card [focusedKey] among [strip]; null (the list's selection)
     * once focus went to the list, or the card has left the strip.
     */
    fun stageFor(strip: List<YourTeamsEntry>, focusedKey: String?): YourTeamsStage? =
        focusedKey?.let { key -> strip.firstOrNull { it.key == key } }?.let(::stageOf)

    /**
     * Where Down from the header lands on Live Now: the Resume row (#148) while offered; else a followed team's live
     * game in the strip; else the list's first live game; else a game later today; else the empty card. A catch-up
     * replay never takes focus first: live comes first on Live Now.
     */
    fun entry(resumeShown: Boolean, strip: List<YourTeamsEntry>, listHasRows: Boolean): LiveNowEntry = when {
        resumeShown -> LiveNowEntry.RESUME
        strip.firstOrNull() is YourTeamsEntry.Live -> LiveNowEntry.STRIP
        listHasRows -> LiveNowEntry.LIST
        strip.firstOrNull() is YourTeamsEntry.LaterToday -> LiveNowEntry.STRIP
        else -> LiveNowEntry.EMPTY
    }

    /** The row the Replay stage draws for a catch-up: the replay under ESPN's teams and start, never its poster. */
    fun replayStageRow(item: CatchUpItem): ScoreboardRow {
        val start = item.event.startMs
        val replay = item.replay.copy(startMs = start ?: item.replay.startMs, startHasTimeOfDay = start != null && item.event.timeValid)
        val art = GameArt.ofEvent(item.event)
        return ScoreboardRow(
            item = replay,
            group = ScoreboardGroup.JUST_ENDED,
            chip = null,
            art = art,
            firstFollowed = false,
            secondFollowed = false,
            probablyEnded = false,
            twoSided = true
        )
    }
}

/**
 * Live Now's "Your teams" strip (#155): live games, then later today, then catch-up replays. Each card is the
 * section's drawn game card. OK plays (live, replay) or sets the reminder (later today); hold OK opens the card's
 * menu. → from the last card enters the stage, which always describes the focused card ([YourTeams.stageOf]).
 */
@Composable
internal fun SportsLiveYourTeamsStrip(
    entries: List<YourTeamsEntry>,
    requesterOf: (String) -> FocusRequester,
    firstFocusRequester: FocusRequester,
    upFocusRequester: FocusRequester?,
    downFocusRequester: FocusRequester?,
    stageFocusRequester: FocusRequester?,
    /** The card whose stage is shown while focus is in the stage: it keeps a white outline (selected). */
    selectedKey: String?,
    scoresMode: ScoresMode?,
    reminded: (SportsLiveItem) -> Boolean,
    onFocus: (YourTeamsEntry) -> Unit,
    onClick: (YourTeamsEntry) -> Unit,
    onLongClick: (YourTeamsEntry) -> Unit,
    /** A menu of the strip is open: focus leaving for it keeps the card. */
    menuOpen: Boolean,
    /** Whether a card of the strip holds focus (its menu open counts), for when the strip empties. */
    onHoldsFocus: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var focusedKey by remember { mutableStateOf<String?>(null) }
    val currentOnHoldsFocus by rememberUpdatedState(onHoldsFocus)
    LaunchedEffect(focusedKey != null) { currentOnHoldsFocus(focusedKey != null) }
    // The focused card left (watched, started, or marked so): focus goes to the first card left.
    val keys = entries.map { it.key }
    LaunchedEffect(keys) {
        val key = focusedKey ?: return@LaunchedEffect
        if (key in keys || keys.isEmpty() || menuOpen) return@LaunchedEffect
        withFrameNanos { }
        runCatching { firstFocusRequester.requestFocus() }
    }
    val strings = rememberScoreChipStrings()
    val liveWord = stringResource(R.string.sports_live_team_live)
    val finalWord = stringResource(R.string.sports_live_chip_final_note)
    val todayFormat = stringResource(R.string.sports_live_your_teams_today)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(TITLE_GAP)) {
        Text(
            text = stringResource(R.string.sports_live_your_teams_title),
            style = SportsLiveTokens.style(SportsLiveTokens.Type.SectionHeader),
            color = Colors.TextMuted
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
            contentPadding = PaddingValues(vertical = FOCUS_ROOM),
            // Focus leaving for a menu keeps the card; leaving for anything else forgets it.
            modifier = Modifier.onFocusChanged { if (!it.hasFocus && !menuOpen && focusedKey?.let { key -> key in keys } == true) focusedKey = null }
        ) {
            itemsIndexed(entries, key = { _, entry -> entry.key }) { index, entry ->
                val footer = when (entry) {
                    is YourTeamsEntry.Live -> SportsLiveHomeMeta.cardFooter(entry.row.chip, scoresMode, strings, liveWord)
                    is YourTeamsEntry.LaterToday -> DrawnCardFooter(
                        lead = String.format(todayFormat, agendaTimeText(entry.startMs)),
                        trailing = DrawnCardTrailing.Bell(reminded(entry.row.item))
                    )
                    // Never a score, a winner or a final note: "Final · Sun 15 Nov" and the Replay pill.
                    is YourTeamsEntry.Replay -> DrawnCardFooter(
                        lead = SportsLiveHomeMeta.catchUpLead(entry.item.event.startMs, finalWord),
                        trailing = DrawnCardTrailing.Replay
                    )
                }
                val art = when (entry) {
                    is YourTeamsEntry.Live -> entry.row.art
                    is YourTeamsEntry.LaterToday -> entry.row.art
                    is YourTeamsEntry.Replay -> remember(entry.item.event) { GameArt.ofEvent(entry.item.event) }
                } ?: return@itemsIndexed
                YourTeamsCard(
                    art = art,
                    followed = entry !is YourTeamsEntry.Replay,
                    footer = footer,
                    selected = entry.key == selectedKey,
                    menuOpen = menuOpen && focusedKey == entry.key,
                    onClick = { onClick(entry) },
                    onLongClick = { onLongClick(entry) },
                    modifier = Modifier
                        .focusRequester(requesterOf(entry.key))
                        .then(if (index == 0) Modifier.focusRequester(firstFocusRequester) else Modifier)
                        .onFocusChanged {
                            if (it.isFocused) {
                                focusedKey = entry.key
                                onFocus(entry)
                            }
                        }
                        .focusProperties {
                            upFocusRequester?.let { up = it }
                            downFocusRequester?.let { down = it }
                            // The last card: → enters the stage, as from a list row.
                            if (index == entries.lastIndex) stageFocusRequester?.let { right = it }
                        }
                )
            }
        }
    }
}

@Composable
private fun YourTeamsCard(
    art: GameArt,
    followed: Boolean,
    footer: DrawnCardFooter,
    selected: Boolean,
    menuOpen: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = SportsLiveTokens.Radius.RowShape
    val (longPress, click) = rememberSportsLiveLongPress(menuOpen = menuOpen, onLongClick = onLongClick, onClick = onClick)
    Surface(
        onClick = click,
        modifier = modifier
            .width(CARD_WIDTH)
            .height(CARD_HEIGHT)
            .then(longPress)
            // Focus in the stage: the card it describes keeps a white outline (selected, never the ring).
            .then(if (selected) Modifier.border(SELECTED_OUTLINE, Colors.FocusWhite, shape) else Modifier),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (menuOpen) 1f else SportsLiveTokens.Focus.LIFT),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Colors.Row,
            contentColor = Colors.TextPrimary,
            focusedContainerColor = SportsLiveFocus.focusedFill(Colors.Row),
            focusedContentColor = Colors.TextPrimary,
            pressedContainerColor = SportsLiveFocus.focusedFill(Colors.Row),
            pressedContentColor = Colors.TextPrimary
        ),
        border = if (menuOpen) {
            ClickableSurfaceDefaults.border()
        } else {
            ClickableSurfaceDefaults.border(focusedBorder = SportsLiveFocus.ring(shape), pressedBorder = SportsLiveFocus.ring(shape))
        }
    ) {
        SportsLiveDrawnGameCard(art = art, followed = followed, footer = footer)
    }
}

/** Two cards and a peek of the third fit the 400 dp list column; 16:9-ish so the drawn card reads as Home's. */
private val CARD_WIDTH = 176.dp
private val CARD_HEIGHT = 96.dp
private val CARD_GAP = 8.dp
private val FOCUS_ROOM = 4.dp
private val SELECTED_OUTLINE = 2.dp
private val TITLE_GAP = 4.dp

/** A catch-up's "When" on the Replay stage (#155): "Replay · Yesterday", "Replay · Today", "Replay · Sun 15 Nov". */
@Composable
internal fun catchUpReplayWhen(item: CatchUpItem, nowMs: Long): String {
    val today = stringResource(R.string.sports_live_agenda_today)
    val yesterday = stringResource(R.string.sports_live_agenda_yesterday)
    val start = item.event.startMs ?: return stringResource(R.string.sports_live_results_replay)
    val zone = ZoneId.systemDefault()
    val todayDate = Instant.ofEpochMilli(nowMs.takeIf { it > 0L } ?: System.currentTimeMillis()).atZone(zone).toLocalDate()
    val date = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
    val day = when (ChronoUnit.DAYS.between(date, todayDate)) {
        0L -> today
        1L -> yesterday
        else -> agendaDateText(date)
    }
    return stringResource(R.string.sports_live_your_teams_replay_when, day)
}
