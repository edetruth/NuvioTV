package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.SportsLiveReadyMark
import com.nuvio.tv.core.sportslive.scores.CatchUpItem
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.core.sportslive.scores.TeamLogoUrls
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.ui.screens.home.SportsLiveHomeMeta
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveFocus
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

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

/** Where focus lands on entering Live Now's content (#155, live.20 1c). */
internal enum class LiveNowEntry { RESUME, STRIP, LIST, EMPTY }

/** The strip's heading counts (live.20 1c): "Your teams · 1 live · 2 replays · 3 tonight". */
internal data class YourTeamsSummary(val live: Int, val replays: Int, val later: Int)

internal object YourTeams {
    /** A game starting within this is "starting soon": it comes before the replays. */
    const val SOON_MS = 60 * 60_000L

    /** At most this many replays, the newest. */
    const val MAX_REPLAYS = 3

    /**
     * The strip (live.20 1c), in order: followed teams' games live now (as the list orders them), then those starting
     * within [SOON_MS], then the catch-up replays newest first (at most [MAX_REPLAYS]: [catchUp] holds only the unwatched
     * ones still useful, [com.nuvio.tv.core.sportslive.scores.ReplayLifetime]), then the rest of today by start (still
     * ahead and on [nowMs]'s local day in [zone]). [liveRows] are Live now's rows, [upcomingRows] Upcoming's followed
     * games. A game with no drawn art (no two sides) is left out.
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
        val (soon, rest) = later.partition { it.startMs - nowMs <= SOON_MS }
        val replays = catchUp.sortedByDescending { it.event.startMs ?: Long.MIN_VALUE }
            .distinctBy { it.key }
            .take(MAX_REPLAYS)
            .map { YourTeamsEntry.Replay(it) }
        return live + soon + replays + rest
    }

    /** The heading's counts: live games, replays and games later today. */
    fun summary(strip: List<YourTeamsEntry>): YourTeamsSummary = YourTeamsSummary(
        live = strip.count { it is YourTeamsEntry.Live },
        replays = strip.count { it is YourTeamsEntry.Replay },
        later = strip.count { it is YourTeamsEntry.LaterToday }
    )

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
     * Where Down from the header lands on Live Now (user decision 2026-11-20): the Resume row (#148) while offered;
     * else the strip's first card, whatever it is (a followed team's live game comes first; else a game starting
     * soon, a replay, a game later today), never another team's live game in the list; else the list; else the
     * empty card.
     */
    fun entry(resumeShown: Boolean, strip: List<YourTeamsEntry>, listHasRows: Boolean): LiveNowEntry = when {
        resumeShown -> LiveNowEntry.RESUME
        strip.isNotEmpty() -> LiveNowEntry.STRIP
        listHasRows -> LiveNowEntry.LIST
        else -> LiveNowEntry.EMPTY
    }

    /**
     * OK on a strip card plays (live, replay); on a game not started yet it plays only once its verified link is held
     * ([mark] READY: "Watch now", the held link at once, 1b HARD RULE), else it sets the reminder.
     */
    fun okPlays(entry: YourTeamsEntry, mark: SportsLiveReadyMark?): Boolean =
        entry !is YourTeamsEntry.LaterToday || mark == SportsLiveReadyMark.READY

    /** From 5 pm local the day's games read "tonight", before it "today" (the heading and Home's line). */
    fun isEvening(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        Instant.ofEpochMilli(nowMs).atZone(zone).hour >= EVENING_HOUR

    private const val EVENING_HOUR = 17

    /** The row the Replay stage draws for a catch-up: the replay under ESPN's teams and start, never its poster. */
    fun replayStageRow(item: CatchUpItem): ScoreboardRow {
        val start = item.event.startMs
        val replay = item.replay.copy(startMs = start ?: item.replay.startMs, startHasTimeOfDay = start != null && item.event.timeValid)
        val art = GameArt.ofEvent(item.event, records = false) // spoiler-free: records move with the result
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
 * Live Now's "Your teams" strip (#155, live.20 1c): live games, games starting soon, catch-up replays, then later
 * today, under "Your teams · 1 live · 2 replays · 3 tonight". Slim cards (logos, short names, the time) so four fit
 * across the list column; "▶ Ready" on a game holding a verified link (1b); "+N ›" while more cards are off-screen.
 * OK plays (live, replay) or sets the reminder (later today); hold OK opens the card's menu. → from the last card
 * enters the stage, which always describes the focused card ([YourTeams.stageOf]).
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
    val todayWord = stringResource(R.string.sports_live_agenda_today)
    val listState = rememberLazyListState()
    // Cards past the last one fully in view: the "+N ›" cue.
    val offScreen by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastFull = info.visibleItemsInfo.lastOrNull { it.offset + it.size <= info.viewportEndOffset }?.index ?: return@derivedStateOf 0
            (info.totalItemsCount - 1 - lastFull).coerceAtLeast(0)
        }
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(TITLE_GAP)) {
        Text(
            text = yourTeamsHeading(YourTeams.summary(entries)),
            style = SportsLiveTokens.style(SportsLiveTokens.Type.SectionHeader),
            color = Colors.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            // Four across the list column (live.20 1c), never narrower than the logos need nor wider than Home's look.
            val cardWidth = ((maxWidth - CARD_GAP * (CARDS_ACROSS - 1)) / CARDS_ACROSS).coerceIn(CARD_MIN_WIDTH, CARD_MAX_WIDTH)
            LazyRow(
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
                contentPadding = PaddingValues(vertical = FOCUS_ROOM),
                // Focus leaving for a menu keeps the card; leaving for anything else forgets it.
                modifier = Modifier.onFocusChanged { if (!it.hasFocus && !menuOpen && focusedKey?.let { key -> key in keys } == true) focusedKey = null }
            ) {
                itemsIndexed(entries, key = { _, entry -> entry.key }) { index, entry ->
                    val art = when (entry) {
                        is YourTeamsEntry.Live -> entry.row.art
                        is YourTeamsEntry.LaterToday -> entry.row.art
                        is YourTeamsEntry.Replay -> remember(entry.item.event) { GameArt.ofEvent(entry.item.event, records = false) }
                    } ?: return@itemsIndexed
                    val ready = when (entry) {
                        is YourTeamsEntry.Live -> rememberSportsLiveReadyMark(entry.row.item) == SportsLiveReadyMark.READY
                        is YourTeamsEntry.LaterToday -> rememberSportsLiveReadyMark(entry.row.item) == SportsLiveReadyMark.READY
                        is YourTeamsEntry.Replay -> false
                    }
                    val face = when (entry) {
                        is YourTeamsEntry.Live -> {
                            val footer = SportsLiveHomeMeta.cardFooter(entry.row.chip, scoresMode, strings, liveWord)
                            SlimFace(
                                status = SlimStatus.Live(footer.lead),
                                corner = if (ready) SlimCorner.Ready else null,
                                score = (footer.trailing as? DrawnCardTrailing.Score)?.text
                            )
                        }
                        // Ready: OK is Watch now (the held link), the pill says so; else OK is Remind me, the bell.
                        is YourTeamsEntry.LaterToday -> SlimFace(
                            status = SlimStatus.Time(agendaTimeText(entry.startMs)),
                            corner = if (ready) SlimCorner.Ready else SlimCorner.Bell(reminded(entry.row.item))
                        )
                        // Never a score, a winner or a final note: the Replay pill and the day.
                        is YourTeamsEntry.Replay -> SlimFace(
                            status = SlimStatus.Replay,
                            corner = SlimCorner.Day(replayDayText(entry.item.event.startMs, todayWord))
                        )
                    }
                    YourTeamsCard(
                        art = art,
                        face = face,
                        width = cardWidth,
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
            if (offScreen > 0) {
                MoreCue(offScreen, Modifier.align(Alignment.CenterEnd))
            }
        }
    }
}

/** "Your teams · 1 live · 2 replays · 3 tonight" ("3 today" before 5 pm): the counts that are not zero. */
@Composable
private fun yourTeamsHeading(summary: YourTeamsSummary): String {
    val context = LocalContext.current
    val parts = listOfNotNull(
        stringResource(R.string.sports_live_your_teams_title),
        summary.live.takeIf { it > 0 }?.let { context.getString(R.string.sports_live_your_teams_live, it) },
        summary.replays.takeIf { it > 0 }?.let { context.resources.getQuantityString(R.plurals.sports_live_your_teams_replays, it, it) },
        summary.later.takeIf { it > 0 }?.let {
            val evening = YourTeams.isEvening(System.currentTimeMillis())
            context.getString(if (evening) R.string.sports_live_your_teams_tonight else R.string.sports_live_your_teams_today_count, it)
        }
    )
    return parts.joinToString(stringResource(R.string.sports_live_game_info_separator))
}

/** A replay card's day: "Today", else the weekday ("Sun"): short, so it fits beside the Replay pill. */
private fun replayDayText(startMs: Long?, todayWord: String, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String? {
    val start = startMs ?: return null
    val date = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
    if (date == LocalDate.now(zone)) return todayWord
    return DateTimeFormatter.ofPattern("EEE", locale).format(date)
}

/** The top line's left end of a slim card. */
private sealed interface SlimStatus {
    /** The red dot and the clock ("4:12", "Live"). */
    data class Live(val clock: String) : SlimStatus

    /** The kickoff ("7:30 PM"). */
    data class Time(val text: String) : SlimStatus

    /** The green Replay pill. */
    data object Replay : SlimStatus
}

/** The top line's right end of a slim card. */
private sealed interface SlimCorner {
    data object Ready : SlimCorner

    data class Bell(val on: Boolean) : SlimCorner

    data class Day(val text: String?) : SlimCorner
}

/** What a slim card says besides the teams; [score] (Show only) takes the short names' line. */
private data class SlimFace(val status: SlimStatus, val corner: SlimCorner?, val score: String? = null)

@Composable
private fun YourTeamsCard(
    art: GameArt,
    face: SlimFace,
    width: Dp,
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
            .width(width)
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
        YourTeamsSlimFace(art, face)
    }
}

/**
 * A slim card's face (live.20 1c): the status and the corner (▶ Ready, the bell, the replay's day) on top, both
 * logos (or the short names, #130) in the middle, the short names (or the score under Show) below.
 */
@Composable
private fun YourTeamsSlimFace(art: GameArt, face: SlimFace) {
    val logos = rememberTeamLogoPair(art, TeamLogoUrls.CARD_PX)
    val logosShown = logos != null && !logos.failed
    val meta = SportsLiveTokens.style(SportsLiveTokens.Type.Meta)
    val tag = SportsLiveTokens.style(SportsLiveTokens.Type.Tag)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Colors.SurfaceRaised, Colors.Surface)))
            .padding(horizontal = SLIM_PAD, vertical = SLIM_PAD),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(SLIM_TOP_HEIGHT),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (val status = face.status) {
                is SlimStatus.Live -> {
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Colors.LiveRed))
                    SlimText(status.clock, tag.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"), Colors.TextPrimary, Modifier.weight(1f))
                }
                is SlimStatus.Time -> SlimText(status.text, tag.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"), Colors.TextPrimary, Modifier.weight(1f))
                SlimStatus.Replay -> {
                    Text(
                        text = stringResource(R.string.sports_live_results_replay),
                        style = tag.copy(fontWeight = FontWeight.Bold),
                        color = Colors.ReplayGreenText,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier
                            .clip(RoundedCornerShape(SportsLiveTokens.Radius.Badge))
                            .background(Colors.ReplayGreen)
                            .padding(horizontal = 5.dp)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            when (val corner = face.corner) {
                SlimCorner.Ready -> SportsLiveReadyPill()
                is SlimCorner.Bell -> AgendaBellIcon(if (corner.on) AgendaBell.ON else AgendaBell.OFF, Modifier.size(14.dp), offTint = Colors.TextSecondary)
                is SlimCorner.Day -> corner.text?.let { SlimText(it, tag, Colors.TextSecondary) }
                null -> Unit
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        if (logosShown) {
            Row(horizontalArrangement = Arrangement.spacedBy(SLIM_LOGO_GAP), verticalAlignment = Alignment.CenterVertically) {
                TeamLogoSlot(logos!!.first, logos.shown, SLIM_LOGO)
                TeamLogoSlot(logos.second, logos.shown, SLIM_LOGO)
            }
        } else {
            // No logos (#130): the short names, larger, never letters in a circle.
            SlimNames(art, SportsLiveTokens.style(SportsLiveTokens.Type.RowName).copy(fontWeight = FontWeight.Bold), Colors.TextPrimary)
        }
        Spacer(modifier = Modifier.weight(1f))
        when {
            face.score != null -> SlimText(face.score, meta.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"), Colors.TextPrimary)
            logosShown -> SlimNames(art, meta, Colors.TextSecondary)
            else -> Spacer(modifier = Modifier.height(meta.lineHeight.value.dp))
        }
    }
}

/** "BOS · NYK": both short names, each cut with an ellipsis rather than clipped. */
@Composable
private fun SlimNames(art: GameArt, style: TextStyle, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(SLIM_LOGO_GAP), verticalAlignment = Alignment.CenterVertically) {
        SlimText(art.first.short, style, color, Modifier.weight(1f, fill = false))
        SlimText(art.second.short, style, color, Modifier.weight(1f, fill = false))
    }
}

@Composable
private fun SlimText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    Text(text = text, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** "+2 ›": more cards off-screen to the right, over the last card in view (a fade, then the count). */
@Composable
private fun MoreCue(count: Int, modifier: Modifier) {
    Box(
        modifier = modifier
            .padding(vertical = FOCUS_ROOM)
            .height(CARD_HEIGHT)
            .background(Brush.horizontalGradient(listOf(Color.Transparent, Colors.Background.copy(alpha = 0.92f))))
            .padding(start = 20.dp, end = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.sports_live_your_teams_more, count),
            style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta).copy(fontWeight = FontWeight.Bold),
            color = Colors.TextPrimary,
            maxLines = 1,
            softWrap = false
        )
    }
}

/** Four slim cards across the 400 dp list column (live.20 1c); wider where the strip has the room. */
private const val CARDS_ACROSS = 4
private val CARD_MIN_WIDTH = 88.dp
private val CARD_MAX_WIDTH = 132.dp
private val CARD_HEIGHT = 92.dp
private val CARD_GAP = 8.dp
private val FOCUS_ROOM = 4.dp
private val SELECTED_OUTLINE = 2.dp
private val TITLE_GAP = 4.dp
private val SLIM_PAD = 6.dp
private val SLIM_TOP_HEIGHT = 18.dp
private val SLIM_LOGO = 28.dp
private val SLIM_LOGO_GAP = 10.dp

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
