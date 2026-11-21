package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.PedalBike
import androidx.compose.material.icons.rounded.SportsBaseball
import androidx.compose.material.icons.rounded.SportsBasketball
import androidx.compose.material.icons.rounded.SportsCricket
import androidx.compose.material.icons.rounded.SportsFootball
import androidx.compose.material.icons.rounded.SportsGolf
import androidx.compose.material.icons.rounded.SportsHockey
import androidx.compose.material.icons.rounded.SportsMma
import androidx.compose.material.icons.rounded.SportsMotorsports
import androidx.compose.material.icons.rounded.SportsRugby
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.SportsLiveSportKind
import com.nuvio.tv.core.sportslive.scores.BigGameText
import com.nuvio.tv.core.sportslive.scores.PeriodLabel
import com.nuvio.tv.core.sportslive.scores.ScoreDetails
import com.nuvio.tv.core.sportslive.scores.ScoreDetailsBuilder
import com.nuvio.tv.core.sportslive.scores.ScoreDetailsStrings
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors
import java.util.Locale

/*
 * The stage's information (live.20 layout from the user's TV photos): which row it shows, what its media box draws,
 * and the facts line under the status ("▶ Stream ready · Started 7:30 PM"), so the stage reads
 * full without a gap above its buttons.
 */

/**
 * The row the stage shows (F23), by list key: the focused row while it is listed ([focusedKey], null right after a tab
 * or filter change), else the mini player's game ([miniKey]: playing, or the one just stopped) while listed, else the
 * first row. Never a selection the view model kept for another reason (a tab focus once cleared it to the addon's first
 * item, so the stage jumped to an unrelated channel). Null for an empty list.
 */
internal fun sportsLiveStageKey(keys: List<String>, focusedKey: String?, miniKey: String?): String? =
    focusedKey?.takeIf { it in keys } ?: miniKey?.takeIf { it in keys } ?: keys.firstOrNull()

/** What the stage's media box draws when no preview or mini player covers it. */
internal enum class StageArtKind {
    /** Two real sides: the drawn matchup. */
    MATCHUP,

    /** A channel with its own logo or picture: that art (a channel's art is its identity). */
    CHANNEL_ART,

    /** A channel with no picture: its name drawn on the stage's gradient, never an empty grey box (F7). */
    CHANNEL_NAME,

    /**
     * Any other game or event (a tournament feed, a race, a single-line event): our drawn event card, never the
     * addon's poster, which can carry its own name, letters in a circle or a crop of a logo (F7, live.20 item 5).
     */
    EVENT
}

internal fun stageArtKind(row: ScoreboardRow): StageArtKind = when {
    row.art != null && row.twoSided -> StageArtKind.MATCHUP
    row.group == ScoreboardGroup.CHANNELS || row.item.isChannel ->
        if (row.item.meta.logo != null || row.item.meta.poster != null || row.item.meta.backdropUrl != null) StageArtKind.CHANNEL_ART
        else StageArtKind.CHANNEL_NAME
    else -> StageArtKind.EVENT
}

/** The mark a single-line event carries on its row and stage (live.20 item 4): its sport, or a trophy. */
internal enum class SportsLiveEventMark {
    TROPHY, SOCCER, FOOTBALL, BASKETBALL, HOCKEY, BASEBALL, CRICKET, RUGBY, GOLF, FIGHT, MOTORSPORT, CYCLING
}

/**
 * [kind]'s mark. A tennis row with no two players is a tournament's feed ("ATP", "Day 6 Singles", "ATP 250 ·
 * Stockholm Open"): the trophy, as for an event of no known sport or one with no icon of its own (darts, snooker).
 */
internal fun eventMarkOf(kind: SportsLiveSportKind?): SportsLiveEventMark = when (kind) {
    SportsLiveSportKind.SOCCER -> SportsLiveEventMark.SOCCER
    SportsLiveSportKind.FOOTBALL -> SportsLiveEventMark.FOOTBALL
    SportsLiveSportKind.BASKETBALL -> SportsLiveEventMark.BASKETBALL
    SportsLiveSportKind.HOCKEY -> SportsLiveEventMark.HOCKEY
    SportsLiveSportKind.BASEBALL -> SportsLiveEventMark.BASEBALL
    SportsLiveSportKind.CRICKET -> SportsLiveEventMark.CRICKET
    SportsLiveSportKind.RUGBY -> SportsLiveEventMark.RUGBY
    SportsLiveSportKind.GOLF -> SportsLiveEventMark.GOLF
    SportsLiveSportKind.MMA, SportsLiveSportKind.BOXING, SportsLiveSportKind.WRESTLING -> SportsLiveEventMark.FIGHT
    SportsLiveSportKind.MOTORSPORT -> SportsLiveEventMark.MOTORSPORT
    SportsLiveSportKind.CYCLING -> SportsLiveEventMark.CYCLING
    SportsLiveSportKind.TENNIS, SportsLiveSportKind.DARTS, SportsLiveSportKind.SNOOKER, null -> SportsLiveEventMark.TROPHY
}

private fun SportsLiveEventMark.icon(): ImageVector = when (this) {
    SportsLiveEventMark.TROPHY -> Icons.Rounded.EmojiEvents
    SportsLiveEventMark.SOCCER -> Icons.Rounded.SportsSoccer
    SportsLiveEventMark.FOOTBALL -> Icons.Rounded.SportsFootball
    SportsLiveEventMark.BASKETBALL -> Icons.Rounded.SportsBasketball
    SportsLiveEventMark.HOCKEY -> Icons.Rounded.SportsHockey
    SportsLiveEventMark.BASEBALL -> Icons.Rounded.SportsBaseball
    SportsLiveEventMark.CRICKET -> Icons.Rounded.SportsCricket
    SportsLiveEventMark.RUGBY -> Icons.Rounded.SportsRugby
    SportsLiveEventMark.GOLF -> Icons.Rounded.SportsGolf
    SportsLiveEventMark.FIGHT -> Icons.Rounded.SportsMma
    SportsLiveEventMark.MOTORSPORT -> Icons.Rounded.SportsMotorsports
    SportsLiveEventMark.CYCLING -> Icons.Rounded.PedalBike
}

/** A single-line row's mark in the logo slot (live.20 item 4): muted, so it never reads as a team's logo. */
@Composable
internal fun SportsLiveEventMarkIcon(kind: SportsLiveSportKind?, modifier: Modifier = Modifier, tint: Color = Colors.TextMuted) {
    Icon(imageVector = eventMarkOf(kind).icon(), contentDescription = null, tint = tint, modifier = modifier)
}

/**
 * The drawn event card at the media box's size (live.20 items 4 and 5): the mark large, the league or sport in small
 * capitals and the clean title, on the stage's gradient. Never the addon's art and never letters in a circle.
 */
@Composable
internal fun SportsLiveEventStageArt(row: ScoreboardRow) {
    val caption = (sportsLiveLeagueLabel(row.art?.league ?: row.item.sport, row.item.catalogName) ?: row.sportTag?.let { sportKindLabel(it) })
        ?.uppercase(Locale.getDefault())
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Colors.SurfaceRaised, Colors.Surface)))
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
    ) {
        SportsLiveEventMarkIcon(row.sportTag, Modifier.size(EVENT_MARK_SIZE), tint = Colors.TextSecondary)
        caption?.let {
            Text(
                text = it,
                style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag).copy(letterSpacing = 0.08.em),
                color = Colors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = singleLineTitle(row.item),
            style = SportsLiveTokens.style(SportsLiveTokens.Type.RowName).copy(fontSize = 20.sp, lineHeight = 24.sp).sportsLiveBold(),
            color = Colors.TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** A channel with no picture (F7): its clean name large on the stage's gradient. */
@Composable
internal fun SportsLiveChannelNameArt(item: SportsLiveItem) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Colors.SurfaceRaised, Colors.Surface)))
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = singleLineTitle(item),
            style = SportsLiveTokens.style(SportsLiveTokens.Type.StageScore).sportsLiveBold(),
            color = Colors.TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * The facts under a stage's status line (live.20 layout, research 6): [ready] ("▶ Stream ready", my games' held link,
 * 1b) and when a live game [startedAtMs] started (a timed start only). How many addons list it is not a stage fact
 * (jargon for most viewers): Choose stream lists the sources.
 */
internal data class StageFacts(val ready: Boolean, val startedAtMs: Long?) {
    val isEmpty: Boolean get() = !ready && startedAtMs == null
}

/**
 * [row]'s facts on Live Now's stage: the start of a game in play when its time of day is known (ESPN's, else the
 * card's); [ready] as given. A channel has no start.
 */
internal fun sportsLiveStageFacts(row: ScoreboardRow, ready: Boolean, nowMs: Long): StageFacts {
    val item = row.item
    val live = row.group == ScoreboardGroup.LIVE
    val start = row.art?.takeIf { it.hasTimeOfDay }?.startMs ?: item.startMs?.takeIf { item.startHasTimeOfDay }
    return StageFacts(
        ready = ready,
        startedAtMs = start?.takeIf { live && !item.isChannel && it <= nowMs }
    )
}

/** The facts line, "▶ Stream ready · Started 7:30 PM" (research 6), one line; nothing when there is nothing to say. */
@Composable
internal fun SportsLiveStageFactsLine(facts: StageFacts, modifier: Modifier = Modifier) {
    if (facts.isEmpty) return
    val started = facts.startedAtMs?.let { stringResource(R.string.sports_live_stage_started, agendaTimeText(it)) }
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        // The stage spells it out once, "Stream ready" (a bare "Ready" reads as "ready for what?"); rows keep "▶ Ready".
        if (facts.ready) SportsLiveReadyPill(stage = true)
        if (started != null) {
            Text(
                text = started,
                style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
                color = Colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * The one badge a row's status column has room for (live.20). The mini player's game (BUG-D): its excitement or
 * close-game badge (new information; the mini player already says what is watching), else "▶ Watching". Any other
 * row, one pill by the game grammar's priority (live.24, audit A2): "▶ Ready" (my game, one press to play), then
 * excitement, then close game, then "No stream yet" (BUG2: its last lookup found nothing playable; the row is dimmed
 * too, so it never reads as playable).
 */
internal enum class RowBadge { WATCHING, READY, NO_STREAM, EXCITEMENT, CLOSE_GAME, NONE }

internal fun rowBadgeOf(
    ready: Boolean,
    excitement: Boolean,
    closeGame: Boolean,
    noStream: Boolean = false,
    watching: Boolean = false
): RowBadge = when {
    watching -> when {
        excitement -> RowBadge.EXCITEMENT
        closeGame -> RowBadge.CLOSE_GAME
        else -> RowBadge.WATCHING
    }
    ready -> RowBadge.READY
    excitement -> RowBadge.EXCITEMENT
    closeGame -> RowBadge.CLOSE_GAME
    noStream -> RowBadge.NO_STREAM
    else -> RowBadge.NONE
}

/**
 * The stage's compact linescore (live.20): the last [maxPeriods] periods on one line, each "3rd 25–20" (first side
 * first, as the scoreline), an overtime or extra time by its own name ("OT 7–5"); a period the first side has not played reads
 * [ScoreDetailsStrings.emptyPeriod]; one the second side has not played ends at the dash ("9th 1–"). Null with no periods. The full table is the Game page's.
 */
internal fun stageLinescoreText(
    details: ScoreDetails.Shown,
    strings: ScoreDetailsStrings,
    ordinal: (Int) -> String = BigGameText::englishOrdinal,
    maxPeriods: Int = STAGE_PERIODS
): String? {
    if (details.periods.isEmpty()) return null
    val from = (details.periods.size - maxPeriods).coerceAtLeast(0)
    return (from until details.periods.size).joinToString(" · ") { index ->
        val label = when (val period = details.periods[index]) {
            is PeriodLabel.Number -> ordinal(period.n)
            else -> ScoreDetailsBuilder.periodText(period, strings)
        }
        val first = details.first.linescores.getOrNull(index)?.toString()
        val second = details.second.linescores.getOrNull(index)?.toString()
        // A side that has not played the period yet shows nothing after the dash ("9th 1–"), never a doubled dash.
        "$label ${first ?: strings.emptyPeriod}–${second.orEmpty()}"
    }
}

/** The periods the stage's compact linescore shows. */
private const val STAGE_PERIODS = 3

private val EVENT_MARK_SIZE = 44.dp
