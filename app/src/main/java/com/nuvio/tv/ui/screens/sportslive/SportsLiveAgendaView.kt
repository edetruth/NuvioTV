package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.GameStatusText
import com.nuvio.tv.core.sportslive.displayName
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButton
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButtonRole
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** A day's date as the Agenda writes it: "Wed 18 Nov". */
internal fun agendaDateText(date: LocalDate, locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofPattern("EEE d MMM", locale).format(date)

/** A start's time as the Agenda writes it: "3:30 PM" (the locale's short time). */
internal fun agendaTimeText(startMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(Instant.ofEpochMilli(startMs).atZone(zone))

/** A day's own name: "Today", "Tomorrow", else its date; "Any time" for the closing group. */
@Composable
internal fun agendaDayName(day: AgendaDay): String = when (day.kind) {
    AgendaDayKind.TODAY -> stringResource(R.string.sports_live_agenda_today)
    AgendaDayKind.TOMORROW -> stringResource(R.string.sports_live_agenda_tomorrow)
    AgendaDayKind.YESTERDAY -> stringResource(R.string.sports_live_agenda_yesterday)
    AgendaDayKind.DATE -> day.date?.let { agendaDateText(it) }.orEmpty()
    AgendaDayKind.ANY_TIME -> stringResource(R.string.sports_live_agenda_any_time)
    AgendaDayKind.UNDATED -> stringResource(R.string.sports_live_replays_undated)
}

/** The sticky day header (V2 Upcoming): "Today  Wed 18 Nov" over a hairline; opaque, so rows scroll under it. */
@Composable
internal fun SportsLiveAgendaDayHeader(day: AgendaDay, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().background(Colors.Background)) {
        Row(
            modifier = Modifier.padding(top = 6.dp, bottom = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = agendaDayName(day),
                style = SportsLiveTokens.style(DAY_STYLE).sportsLiveBold(),
                color = Colors.TextPrimary,
                maxLines = 1
            )
            // Today, Tomorrow and Yesterday carry their date too.
            if (day.kind == AgendaDayKind.TODAY || day.kind == AgendaDayKind.TOMORROW || day.kind == AgendaDayKind.YESTERDAY) {
                day.date?.let { date ->
                    Text(
                        text = agendaDateText(date),
                        style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
                        color = Colors.TextMuted,
                        maxLines = 1,
                        modifier = Modifier.padding(bottom = 1.dp)
                    )
                }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Colors.Divider))
    }
}

/** A slot header's words: "3:30 PM · in 2 h 21 m" for one game, "6:00 PM · 4 games" for several; null for Any time. */
@Composable
internal fun agendaSlotText(slot: AgendaSlot, nowMs: Long): Pair<String, String?>? {
    val start = slot.startMs ?: return null
    val time = agendaTimeText(start)
    if (slot.count > 1) return time to pluralStringResource(R.plurals.sports_live_agenda_games, slot.count, slot.count)
    val strings = rememberGameStatusStrings()
    return time to GameStatusText.countdown(agendaCountdown(start, nowMs), strings)
}

/** A time-slot header (V2 Upcoming): the time in bold, then the countdown or the number of games, muted. */
@Composable
internal fun SportsLiveAgendaSlotHeader(slot: AgendaSlot, nowMs: Long, modifier: Modifier = Modifier) {
    val (time, rest) = agendaSlotText(slot, nowMs) ?: return
    Row(modifier = modifier.padding(top = 6.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text = time, style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta).sportsLiveBold(), color = Colors.TextPrimary, maxLines = 1)
        rest?.let {
            Text(
                text = " · $it",
                style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
                color = Colors.TextSecondary,
                maxLines = 1
            )
        }
    }
}

/** The stage's "When" line: "Today · 3:30 PM · in 2 h 21 m"; a date alone for a game with no time. */
@Composable
internal fun agendaWhenText(row: ScoreboardRow, nowMs: Long): String? {
    val start = agendaStartOf(row)
    val zone = ZoneId.systemDefault()
    val strings = rememberGameStatusStrings()
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val dateMs = start ?: row.art?.startMs ?: row.item.startMs ?: return null
    val date = Instant.ofEpochMilli(dateMs).atZone(zone).toLocalDate()
    val day = when (java.time.temporal.ChronoUnit.DAYS.between(today, date)) {
        in Long.MIN_VALUE..0L -> stringResource(R.string.sports_live_agenda_today)
        1L -> stringResource(R.string.sports_live_agenda_tomorrow)
        else -> agendaDateText(date)
    }
    if (start == null) return day
    return listOfNotNull(day, agendaTimeText(start, zone), GameStatusText.countdown(agendaCountdown(start, nowMs), strings)).joinToString(" · ")
}

/** The stage's title: "Magic at Rockets" (the clean short names, as the title writes the game), else the clean title. */
@Composable
internal fun agendaStageTitle(row: ScoreboardRow): String {
    val art = row.art?.takeIf { row.twoSided } ?: return singleLineTitle(row.item)
    return stringResource(
        if (agendaWrittenAt(row.item.meta.name)) R.string.sports_live_matchup_at else R.string.sports_live_matchup,
        art.first.shortName,
        art.second.shortName
    )
}

/** [agendaStageTitle] outside composition (the player's title for a replay, P4). */
internal fun agendaStageTitleText(row: ScoreboardRow, context: android.content.Context): String {
    val art = row.art?.takeIf { row.twoSided } ?: return singleLineTitle(row.item)
    return context.getString(
        if (agendaWrittenAt(row.item.meta.name)) R.string.sports_live_matchup_at else R.string.sports_live_matchup,
        art.first.shortName,
        art.second.shortName
    )
}

/** A replay row's time ("8:30 PM", the locale's short time); null for a replay dated without a time. */
internal fun replayTimeText(item: com.nuvio.tv.domain.model.SportsLiveItem, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String? =
    item.startMs?.takeIf { item.startHasTimeOfDay }?.let { agendaTimeText(it, zone, locale) }

/** A replay's "When" on its stage: "Yesterday · 8:30 PM", "Mon 16 Nov" (the device's locale, never "11/16/26"). */
@Composable
internal fun replayWhenText(item: com.nuvio.tv.domain.model.SportsLiveItem, nowMs: Long): String? {
    val start = item.startMs ?: return null
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val date = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
    val day = when (java.time.temporal.ChronoUnit.DAYS.between(date, today)) {
        in Long.MIN_VALUE..0L -> stringResource(R.string.sports_live_agenda_today)
        1L -> stringResource(R.string.sports_live_agenda_yesterday)
        else -> agendaDateText(date)
    }
    return listOfNotNull(day, replayTimeText(item, zone)).joinToString(" · ")
}

/**
 * The Replays stage (V2 P4, #90): the same fixed 360 dp. Drawn matchup art (never the addon's poster, which may
 * carry the score); the clean [title], the date, and **Watch replay** and **More…** (Game stats on a strict
 * match, Team page, Details). No tagline, description or score: those live only under Details.
 */
@Composable
internal fun SportsLiveReplayStage(
    row: ScoreboardRow,
    title: String,
    nowMs: Long,
    onWatch: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    watchModifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.width(SportsLiveStageLayout.WIDTH_DP.dp).fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(SportsLiveStageLayout.GAP_DP.dp)
    ) {
        Box(
            modifier = Modifier
                .width(SportsLiveStageLayout.WIDTH_DP.dp)
                .height(SportsLiveStageLayout.MEDIA_HEIGHT_DP.dp)
                .clip(SportsLiveTokens.Radius.RowShape)
                .background(Colors.Surface)
        ) {
            val art = row.art?.takeIf { row.twoSided }
            val league = sportsLiveLeagueLabel(art?.league ?: row.item.sport, row.item.catalogName)
                ?: row.sportTag?.let { sportKindLabel(it) }
            if (art != null) {
                MatchupStageArt(art, top = league.orEmpty().uppercase(Locale.getDefault()), at = agendaWrittenAt(row.item.meta.name))
            } else {
                // No two sides: the clean title drawn large on the stage's gradient, never the addon's art.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Colors.SurfaceRaised, Colors.Surface)))
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
                ) {
                    league?.let {
                        Text(text = it.uppercase(Locale.getDefault()), style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag), color = Colors.TextMuted, maxLines = 1)
                    }
                    Text(
                        text = title,
                        style = SportsLiveTokens.style(TITLE_STYLE).sportsLiveBold(),
                        color = Colors.TextPrimary,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().height(SportsLiveStageLayout.SCORE_BLOCK_HEIGHT_DP.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            BasicText(
                text = title,
                style = SportsLiveTokens.style(TITLE_STYLE).sportsLiveBold().copy(color = Colors.TextPrimary),
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = 15.sp, maxFontSize = TITLE_STYLE.fontSize, stepSize = 1.sp)
            )
            replayWhenText(row.item, nowMs)?.let { whenText ->
                Text(text = whenText, style = SportsLiveTokens.style(WHEN_STYLE), color = Colors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(
            modifier = Modifier.height(SportsLiveStageLayout.BUTTON_ROW_HEIGHT_DP.dp),
            horizontalArrangement = Arrangement.spacedBy(SportsLiveTokens.Size.ButtonGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SportsLiveButton(onClick = onWatch, role = SportsLiveButtonRole.PRIMARY, modifier = watchModifier.widthIn(min = PRIMARY_MIN_WIDTH)) {
                Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                StageButtonLabel(stringResource(R.string.sports_live_game_watch_replay))
            }
            SportsLiveButton(onClick = onMore, role = SportsLiveButtonRole.SECONDARY, modifier = Modifier.widthIn(min = MORE_MIN_WIDTH)) {
                StageButtonLabel(stringResource(R.string.sports_live_more))
            }
        }
    }
}

/**
 * The stage's "Stream listed by N sources" (P4): only when two or more addons list the game; one source is
 * every game's case, so it says nothing. Null hides the line (and for a channel).
 */
internal fun upcomingListedSources(item: com.nuvio.tv.domain.model.SportsLiveItem): Int? =
    item.sourceCount.takeIf { !item.isChannel && it >= 2 }

/**
 * Upcoming's stage (V2, P3): the same fixed 360 dp as Live Now's. The drawn matchup (or the preview, or the
 * mini player) in the media box; the title, "When" and "Stream listed by N sources"; then **Remind me**
 * (primary while the start is over 15 minutes away, the bell showing its state) or **Watch**, and **More…**.
 * The remote's keys are hinted at its foot.
 */
@Composable
internal fun SportsLiveUpcomingStage(
    row: ScoreboardRow,
    nowMs: Long,
    reminded: Boolean,
    preview: (@Composable () -> Unit)?,
    previewShowing: Boolean,
    miniInStage: Boolean,
    noStream: Boolean,
    onRemind: () -> Unit,
    onWatch: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    primaryModifier: Modifier = Modifier
) {
    val buttons = upcomingStageButtons(row.item, agendaStartOf(row), nowMs)
    Column(
        modifier = modifier.width(SportsLiveStageLayout.WIDTH_DP.dp).fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(SportsLiveStageLayout.GAP_DP.dp)
    ) {
        StageMedia(row, preview, previewShowing, miniInStage, agendaArt = true)
        Column(
            modifier = Modifier.fillMaxWidth().height(SportsLiveStageLayout.SCORE_BLOCK_HEIGHT_DP.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            BasicText(
                text = agendaStageTitle(row),
                style = SportsLiveTokens.style(TITLE_STYLE).sportsLiveBold().copy(color = Colors.TextPrimary),
                maxLines = 1,
                softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = 15.sp, maxFontSize = TITLE_STYLE.fontSize, stepSize = 1.sp)
            )
            agendaWhenText(row, nowMs)?.let { whenText ->
                Text(
                    text = whenText,
                    style = SportsLiveTokens.style(WHEN_STYLE),
                    color = Colors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            upcomingListedSources(row.item)?.let { sources ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Colors.ReplayGreen))
                    Text(
                        text = pluralStringResource(R.plurals.sports_live_stream_listed_by, sources, sources),
                        style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
                        color = Colors.ReplayGreen,
                        maxLines = 1
                    )
                }
            }
            if (noStream) {
                Text(
                    text = stringResource(R.string.sports_live_no_streams_now),
                    style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
                    color = Colors.TextSecondary,
                    maxLines = 1
                )
            }
        }
        Row(
            modifier = Modifier.height(SportsLiveStageLayout.BUTTON_ROW_HEIGHT_DP.dp),
            horizontalArrangement = Arrangement.spacedBy(SportsLiveTokens.Size.ButtonGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            buttons.forEachIndexed { index, button ->
                val role = if (index == 0) SportsLiveButtonRole.PRIMARY else SportsLiveButtonRole.SECONDARY
                val buttonModifier = if (index == 0) primaryModifier else Modifier
                when (button) {
                    UpcomingStageButton.REMIND -> SportsLiveButton(onClick = onRemind, role = role, modifier = buttonModifier.widthIn(min = PRIMARY_MIN_WIDTH)) {
                        AgendaBellIcon(if (reminded) AgendaBell.ON else AgendaBell.OFF, Modifier.size(16.dp), offTint = Colors.OnPrimary)
                        Spacer(modifier = Modifier.width(6.dp))
                        StageButtonLabel(stringResource(if (reminded) R.string.sports_live_reminder_on else R.string.sports_live_remind_me))
                    }
                    UpcomingStageButton.WATCH -> SportsLiveButton(
                        onClick = onWatch,
                        role = role,
                        modifier = buttonModifier.widthIn(min = PRIMARY_MIN_WIDTH).then(if (noStream) Modifier.alpha(0.5f) else Modifier)
                    ) {
                        Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        StageButtonLabel(stringResource(R.string.sports_live_watch))
                    }
                    UpcomingStageButton.MORE -> SportsLiveButton(onClick = onMore, role = role, modifier = buttonModifier.widthIn(min = MORE_MIN_WIDTH)) {
                        StageButtonLabel(stringResource(R.string.sports_live_more))
                    }
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        SportsLiveAgendaKeyHints()
    }
}

@Composable
private fun StageButtonLabel(text: String) {
    Text(text = text, style = SportsLiveTokens.style(SportsLiveTokens.Type.Button), maxLines = 1, softWrap = false)
}

/** The key-hint footer (V2 Upcoming): "↑ ↓ games   ← → time slots   CH +/− day   OK remind / watch". */
@Composable
internal fun SportsLiveAgendaKeyHints(modifier: Modifier = Modifier) {
    val hints = listOf(
        R.string.sports_live_hint_keys_games to R.string.sports_live_hint_games,
        R.string.sports_live_hint_keys_slots to R.string.sports_live_hint_slots,
        R.string.sports_live_hint_keys_day to R.string.sports_live_hint_day,
        R.string.sports_live_hint_keys_ok to R.string.sports_live_hint_ok
    )
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        hints.forEach { (keys, label) ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(keys),
                    style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag),
                    color = Colors.TextSecondary,
                    maxLines = 1,
                    modifier = Modifier
                        .border(1.dp, Colors.OutlineStrong, RoundedCornerShape(SportsLiveTokens.Radius.Badge))
                        .padding(horizontal = 3.dp, vertical = 1.dp)
                )
                Text(text = stringResource(label), style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag).copy(fontWeight = FontWeight.Normal), color = Colors.TextMuted, maxLines = 1)
            }
        }
    }
}

/** The day header: 34 px. */
private val DAY_STYLE = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)

/** The stage title: 44 px bold. */
private val TITLE_STYLE = androidx.compose.ui.text.TextStyle(fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)

/** The stage's "When": 28 px. */
private val WHEN_STYLE = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium)

private val PRIMARY_MIN_WIDTH = 110.dp
private val MORE_MIN_WIDTH = 84.dp
