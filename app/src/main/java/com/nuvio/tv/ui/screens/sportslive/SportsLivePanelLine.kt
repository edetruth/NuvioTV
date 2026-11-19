package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.GameStatusStrings
import com.nuvio.tv.core.sportslive.SportsLiveDisplayText
import com.nuvio.tv.core.sportslive.scores.GameArtTeam
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveFocus
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Size

/** What a one-line panel row draws (#140): two teams and their score, an event's title, or a channel. */
internal enum class PanelLineKind { GAME, EVENT, CHANNEL }

/**
 * A row of the in-player panel on one line (#140), [Size.PanelLine] tall: a [PanelLineKind.GAME] is its two
 * teams' short names ([first], [second]) around the [scores] ("ORL 97–92 PHI"); an [PanelLineKind.EVENT] or a
 * [PanelLineKind.CHANNEL] its clean [title]. Then the status: the [clock] (the kickoff time, the final word)
 * and, for a game in play ([live], the red dot), its [period]. Never a second line.
 */
@Immutable
internal data class PanelLine(
    val kind: PanelLineKind,
    val first: String? = null,
    val second: String? = null,
    val scores: ScoreboardScores = ScoreboardScores.None,
    val title: String? = null,
    val clock: String? = null,
    val period: String? = null,
    val live: Boolean = false
) {
    /** Always one line: the row is [Size.PanelLine] tall. */
    val lines: Int get() = 1
}

/**
 * [row] (a Scoreboard row, the one model every Sports & Live list draws from) as a one-line panel row: the
 * same groups, scores (spoiler setting applied) and status words ([scoreboardStatusLines]); [kickoff] words
 * its start. A live game keeps its period beside the clock; any other row only the first status line.
 */
internal fun panelLineOf(row: ScoreboardRow, strings: GameStatusStrings, kickoff: String?): PanelLine {
    val status = scoreboardStatusLines(row, strings, kickoff)
    val period = status.secondary.takeIf { status.live }
    val art = row.art
    return when {
        row.group == ScoreboardGroup.CHANNELS -> PanelLine(
            PanelLineKind.CHANNEL,
            title = SportsLiveDisplayText.of(row.item.meta.name).name,
            clock = status.primary,
            live = status.live
        )
        row.twoSided && art != null -> PanelLine(
            PanelLineKind.GAME,
            first = panelTeamLabel(art.first),
            second = panelTeamLabel(art.second),
            scores = scoreboardScoresOf(row.chip),
            clock = status.primary,
            period = period,
            live = status.live
        )
        else -> PanelLine(PanelLineKind.EVENT, title = singleLineTitle(row.item), clock = status.primary, period = period, live = status.live)
    }
}

/** A team's name on a one-line row: its abbreviation ("ORL"), else its short name, else its name. */
internal fun panelTeamLabel(team: GameArtTeam): String =
    team.short.trim().ifEmpty { team.shortName.trim() }.ifEmpty { team.name.trim() }

/**
 * A row of the in-player panel (#140): one line, [Size.PanelLine] tall, on no fill (the panel's fade shows
 * through), so the game stays visible beside it. Live shows only as the red dot. The game playing
 * ([nowPlaying]) has a thin white left accent and "NOW PLAYING" above it. Focus is the V2 focus language
 * (ring, lift, 8 % lighter fill). Hold OK opens the menu ([onLongClick]).
 */
@Composable
internal fun SportsLivePanelLine(
    row: ScoreboardRow,
    nowPlaying: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
    menuOpen: Boolean = false
) {
    val strings = rememberGameStatusStrings()
    val kickoff = row.art?.let { kickoffText(it) }
    val line = remember(row, strings, kickoff) { panelLineOf(row, strings, kickoff) }
    val shape = RoundedCornerShape(PANEL_LINE_RADIUS)
    var focused by remember { mutableStateOf(false) }
    val (longPress, click) = if (onLongClick != null) rememberSportsLiveLongPress(menuOpen, onLongClick, onClick) else Modifier to onClick
    Column(modifier = Modifier.fillMaxWidth()) {
        if (nowPlaying) {
            Text(
                text = stringResource(R.string.sports_live_panel_now_playing),
                style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag),
                color = Colors.TextMuted,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp, top = 2.dp)
            )
        }
        Surface(
            onClick = click,
            modifier = modifier
                .fillMaxWidth()
                .height(Size.PanelLine)
                .then(if (row.dimmed(focused)) Modifier.alpha(JUST_ENDED_LINE_ALPHA) else Modifier)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocus()
                }
                .then(longPress),
            shape = ClickableSurfaceDefaults.shape(shape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = SportsLiveTokens.Focus.LIFT),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = Colors.TextPrimary,
                focusedContainerColor = SportsLiveFocus.focusedFill(Color.Transparent),
                focusedContentColor = Colors.TextPrimary,
                pressedContainerColor = SportsLiveFocus.focusedFill(Color.Transparent),
                pressedContentColor = Colors.TextPrimary
            ),
            border = ClickableSurfaceDefaults.border(focusedBorder = SportsLiveFocus.ring(shape), pressedBorder = SportsLiveFocus.ring(shape))
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(start = 8.dp, end = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        when (line.kind) {
                            PanelLineKind.GAME -> GameLine(row, line)
                            PanelLineKind.EVENT -> EventLine(row, line)
                            PanelLineKind.CHANNEL -> LineText(line.title.orEmpty(), Modifier.weight(1f, fill = false))
                        }
                    }
                    StatusText(line)
                }
                if (nowPlaying) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .width(PANEL_LINE_ACCENT)
                            .fillMaxHeight()
                            .padding(vertical = 3.dp)
                            .background(Colors.FocusWhite, RoundedCornerShape(1.dp))
                    )
                }
            }
        }
    }
}

/** "[logo] ORL 97–92 PHI [logo]": the leader's score in real bold, the hidden mark or "vs" when none shows. */
@Composable
private fun GameLine(row: ScoreboardRow, line: PanelLine) {
    val logos = rememberTeamLogoPair(row.art)?.takeIf { !it.failed }
    if (logos != null) TeamLogoSlot(logos.first, logos.shown, Size.PanelLogo)
    LineText(line.first.orEmpty())
    when (val scores = line.scores) {
        is ScoreboardScores.Shown -> {
            val text = stringResource(R.string.sports_live_chip_score, scores.first, scores.second)
            val bold = SportsLiveTokens.style(SportsLiveTokens.Type.PanelScore).sportsLiveBold().toSpanStyle()
            Text(
                text = panelScoreText(text, scores, bold),
                style = SportsLiveTokens.style(SportsLiveTokens.Type.PanelScore),
                color = Colors.TextPrimary,
                maxLines = 1,
                softWrap = false
            )
        }
        ScoreboardScores.Hidden -> Icon(
            imageVector = Icons.Rounded.VisibilityOff,
            contentDescription = stringResource(R.string.sports_live_results_score_hidden),
            tint = Colors.TextMuted,
            modifier = Modifier.size(12.dp)
        )
        ScoreboardScores.None -> LineText(stringResource(R.string.sports_live_panel_vs), color = Colors.TextMuted)
    }
    LineText(line.second.orEmpty())
    if (logos != null) TeamLogoSlot(logos.second, logos.shown, Size.PanelLogo)
    if (row.followed) Icon(imageVector = Icons.Rounded.Star, contentDescription = null, tint = Colors.FollowedGold, modifier = Modifier.size(10.dp))
}

/** A single-line event's sport tag ("TENNIS"); nothing when none is known. */
@Composable
private fun EventTag(tag: String) {
    if (tag.isEmpty()) return
    Text(
        text = tag,
        style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag),
        color = Colors.TextSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = PANEL_TAG_MAX)
    )
}

/** A single-line event: its sport tag, then its clean title. */
@Composable
private fun RowScope.EventLine(row: ScoreboardRow, line: PanelLine) {
    EventTag(rowTagText(row))
    LineText(line.title.orEmpty(), Modifier.weight(1f, fill = false))
}

@Composable
private fun LineText(text: String, modifier: Modifier = Modifier, color: Color = Colors.TextPrimary) {
    Text(
        text = text,
        style = SportsLiveTokens.style(SportsLiveTokens.Type.PanelLine),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/** The status at the row's end: the red dot and the clock with its period while live, else the time or final word. */
@Composable
private fun StatusText(line: PanelLine) {
    val clock = line.clock ?: return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (line.live) Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(Colors.LiveRed))
        Text(
            text = line.period?.let { stringResource(R.string.sports_live_panel_clock, clock, it) } ?: clock,
            style = SportsLiveTokens.style(SportsLiveTokens.Type.PanelScore),
            color = if (line.live) Colors.TextPrimary else Colors.TextSecondary,
            maxLines = 1,
            softWrap = false
        )
    }
}

/** [text] ("97–92") with the leader's score in [bold] and the trailer's muted; level scores both plain. */
private fun panelScoreText(text: String, scores: ScoreboardScores.Shown, bold: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(text)
    val first = scores.first.toString()
    val second = scores.second.toString()
    val firstAt = text.indexOf(first)
    val secondAt = text.lastIndexOf(second)
    if (firstAt < 0 || secondAt < 0 || secondAt < firstAt + first.length) return@buildAnnotatedString
    val muted = SpanStyle(color = Colors.TextMuted)
    when (scores.leader) {
        0 -> {
            addStyle(bold, firstAt, firstAt + first.length)
            addStyle(muted, secondAt, secondAt + second.length)
        }
        1 -> {
            addStyle(muted, firstAt, firstAt + first.length)
            addStyle(bold, secondAt, secondAt + second.length)
        }
        else -> Unit
    }
}

/** A group's heading in the panel (#140): a small caps line, LIVE in red with its count. */
@Composable
internal fun SportsLivePanelHeader(group: ScoreboardGroup, count: Int) {
    SportsLiveScoreboardHeader(group, count, Modifier.padding(start = 8.dp))
}

private val PANEL_LINE_RADIUS = 6.dp
private val PANEL_LINE_ACCENT = 2.dp
private val PANEL_TAG_MAX = 64.dp
private const val JUST_ENDED_LINE_ALPHA = 0.6f
