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
import com.nuvio.tv.core.sportslive.SportsLiveReadyMark
import com.nuvio.tv.core.sportslive.SportsLiveDisplayText
import com.nuvio.tv.core.sportslive.scores.GameArtTeam
import com.nuvio.tv.core.sportslive.scores.TeamDirectory
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveFocus
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Size

/** What a one-line panel row draws (#140): two teams and their score, an event's title, or a channel. */
internal enum class PanelLineKind { GAME, EVENT, CHANNEL }

/**
 * A row of the in-player panel on one line (#140), [Size.PanelLine] tall: a [PanelLineKind.GAME] is its two
 * teams' short names ([first], [second]) around the [scores] ("ORL 97–92 PHI"); an [PanelLineKind.EVENT] or a
 * [PanelLineKind.CHANNEL] its clean [title]. A game or an event leads with its compact league or sport [tag]
 * ("NBA", "HOCKEY", #167), as Live Now's rows do; empty when none is known. Then the status: the [clock] (the
 * kickoff time, the final word) and, for a game in play ([live], the red dot), its [period]. Never a second line.
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
    val live: Boolean = false,
    val tag: String = ""
) {
    /** Always one line: the row is [Size.PanelLine] tall. */
    val lines: Int get() = 1
}

/**
 * [row] (a Scoreboard row, the one model every Sports & Live list draws from) as a one-line panel row: the
 * same groups, scores (spoiler setting applied) and status words ([scoreboardStatusLines]); [kickoff] words
 * its start. A live game keeps its period beside the clock; any other row only the first status line.
 */
internal fun panelLineOf(
    row: ScoreboardRow,
    strings: GameStatusStrings,
    kickoff: String?,
    tag: String = "",
    /** An unmatched side's name for the line ([panelSideName]); as is by default. */
    sideName: (String) -> String = { it }
): PanelLine {
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
            first = panelTeamLabel(art.first).let { if (art.first.unmatched) sideName(it) else it },
            second = panelTeamLabel(art.second).let { if (art.second.unmatched) sideName(it) else it },
            scores = scoreboardScoresOf(row.chip),
            // Score hidden: the eye between the names says so; the words would push the names out (#167).
            clock = status.primary.takeUnless { row.chip is ScoreChip.Hidden },
            period = period,
            live = status.live,
            tag = tag
        )
        else -> PanelLine(PanelLineKind.EVENT, title = singleLineTitle(row.item), clock = status.primary, period = period, live = status.live, tag = tag)
    }
}

/** A side the art names by the card's own words: no ESPN abbreviation or short name was found for it. */
private val GameArtTeam.unmatched: Boolean get() = short.trim() == name.trim()

/**
 * An unmatched side's name on a one-line panel row (#167): its directory [abbreviation] ("SUU") when it has one;
 * else the name without its trailing nickname when what is left is a place, two words or more or a known
 * location ([isPlace]): "Southern Utah Thunderbirds" → "Southern Utah", "Stetson Hatters" → "Stetson"; else the
 * name as it is. Whatever still does not fit is cut per side on the line, so both sides stay visible.
 */
internal fun panelSideName(name: String, abbreviation: String?, isPlace: (String) -> Boolean = { false }): String {
    abbreviation?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val words = name.trim().split(WHITESPACE)
    if (words.size < 2) return name.trim()
    val place = words.dropLast(1).joinToString(" ")
    return if (words.size - 1 >= 2 || isPlace(place)) place else name.trim()
}

private val WHITESPACE = Regex("""\s+""")

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
    menuOpen: Boolean = false,
    /** What OK does, hinted at the row's end while it has focus ("Box score ›" on a matched Just ended game); null: none. */
    okHint: String? = null,
    /** The game playing's own place in the list (#140): "▶ Watching" at its end, never focused. */
    watching: Boolean = false,
    /** Listed since the panel opened (#164): a "New" tag at its end. */
    isNew: Boolean = false,
    /** Gone from the list since the panel opened (#164): kept in place, dimmed. */
    gone: Boolean = false,
    /** What the check-ahead found (#147): ✓ ready or ⚠ no working stream; null: not known, no mark. */
    readyMark: SportsLiveReadyMark? = null
) {
    val strings = rememberGameStatusStrings()
    val kickoff = row.art?.let { kickoffText(it) }
    val tag = rowTagText(row)
    val line = remember(row, strings, kickoff, tag) {
        panelLineOf(row, strings, kickoff, tag) { side ->
            panelSideName(side, TeamDirectory.shared.abbreviationOf(row.item, side), TeamDirectory.shared::isLocation)
        }
    }
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
                .then(if (row.dimmed(focused) || (gone && !focused)) Modifier.alpha(JUST_ENDED_LINE_ALPHA) else Modifier)
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
                            PanelLineKind.EVENT -> EventLine(line)
                            PanelLineKind.CHANNEL -> LineText(line.title.orEmpty(), Modifier.weight(1f, fill = false))
                        }
                    }
                    StatusText(line)
                    if (readyMark != null) ReadyMark(readyMark)
                    val tag = when {
                        watching -> stringResource(R.string.sports_live_panel_watching)
                        isNew -> stringResource(R.string.sports_live_panel_new)
                        else -> null
                    }
                    if (tag != null) {
                        Text(
                            text = tag,
                            style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag),
                            color = if (watching) Colors.TextPrimary else Colors.TextSecondary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    if (focused && okHint != null) {
                        Text(
                            text = okHint,
                            style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag),
                            color = Colors.TextSecondary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
                if (nowPlaying || watching) {
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

/** The game's readiness in words (#147, 1b): "▶ Ready" while a verified link is held, "No stream yet" (muted) when none was found. */
@Composable
private fun ReadyMark(mark: SportsLiveReadyMark) {
    SportsLiveReadyMarkView(mark, showNoStream = true)
}

/**
 * "NBA [logo] ORL 97–92 PHI [logo]" (V2 `Player.dc.html` 3): the tag, a matched game's logos or flags beside the
 * abbreviations around the score (none when they are not both known), the leader's score in real bold, the hidden
 * mark or "vs" when none shows. The names give way (ellipsis) first: the tag, the score and the clock always fit.
 */
@Composable
private fun RowScope.GameLine(row: ScoreboardRow, line: PanelLine) {
    LineTag(line.tag)
    val logos = rememberTeamLogoPair(row.art)?.takeIf { !it.failed }
    // A matched game with both logos shown: abbreviations that no longer fit (a revealed score and clock) go whole,
    // never as "B…": the logos name the teams. An unmatched game's names are cut per side instead. Taken afresh
    // for each new line.
    var namesFit by remember(line) { mutableStateOf(true) }
    val matched = row.art?.let { !it.first.unmatched && !it.second.unmatched } == true
    val logosShown = logos?.shown == true && matched
    val onOverflow: (() -> Unit)? = if (logosShown) ({ namesFit = false }) else null
    if (logos != null) TeamLogoSlot(logos.first, logos.shown, Size.PanelLogo)
    if (namesFit || !logosShown) LineText(line.first.orEmpty(), Modifier.weight(1f, fill = false), onOverflow = onOverflow)
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
    if (namesFit || !logosShown) LineText(line.second.orEmpty(), Modifier.weight(1f, fill = false), onOverflow = onOverflow)
    if (logos != null) TeamLogoSlot(logos.second, logos.shown, Size.PanelLogo)
    if (row.followed) Icon(imageVector = Icons.Rounded.Star, contentDescription = null, tint = Colors.FollowedGold, modifier = Modifier.size(10.dp))
}

/**
 * A row's league or sport tag ("NBA", "TENNIS") in one right-aligned column, so names start at one x on every
 * line (V2 `Player.dc.html` 3); the column stays, empty, when no tag is known.
 */
@Composable
private fun LineTag(tag: String) {
    Box(modifier = Modifier.width(PANEL_TAG_COLUMN), contentAlignment = Alignment.CenterEnd) {
        if (tag.isNotEmpty()) {
            Text(
                text = tag,
                style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag),
                color = Colors.TextSecondary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** A single-line event: its sport tag, then its clean title. */
@Composable
private fun RowScope.EventLine(line: PanelLine) {
    LineTag(line.tag)
    LineText(line.title.orEmpty(), Modifier.weight(1f, fill = false))
}

@Composable
private fun LineText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Colors.TextPrimary,
    /** Called when [text] does not fit whole. */
    onOverflow: (() -> Unit)? = null
) {
    Text(
        text = text,
        style = SportsLiveTokens.style(SportsLiveTokens.Type.PanelLine),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
        onTextLayout = { if (onOverflow != null && it.hasVisualOverflow) onOverflow() }
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

/**
 * "N more hidden by My sports · Show all" (#167), the last line of Live now's list and of the in-player panel
 * under My sports: OK ([onClick]) switches to All sports. [height] is the list's row height.
 */
@Composable
internal fun SportsLiveHiddenByMySportsLine(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
    height: androidx.compose.ui.unit.Dp = Size.PanelLine
) {
    val shape = RoundedCornerShape(PANEL_LINE_RADIUS)
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = SportsLiveTokens.Focus.LIFT),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Colors.TextSecondary,
            focusedContainerColor = SportsLiveFocus.focusedFill(Color.Transparent),
            focusedContentColor = Colors.TextPrimary,
            pressedContainerColor = SportsLiveFocus.focusedFill(Color.Transparent),
            pressedContentColor = Colors.TextPrimary
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = SportsLiveFocus.ring(shape), pressedBorder = SportsLiveFocus.ring(shape))
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(start = 8.dp, end = 6.dp), contentAlignment = Alignment.CenterStart) {
            Text(
                text = stringResource(R.string.sports_live_hidden_by_my_sports, count),
                style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** A group's heading in the panel (#140): a small caps line, LIVE in red with its count. */
@Composable
internal fun SportsLivePanelHeader(group: ScoreboardGroup, count: Int) {
    SportsLiveScoreboardHeader(group, count, Modifier.padding(start = 8.dp))
}

private val PANEL_LINE_RADIUS = 6.dp
private val PANEL_LINE_ACCENT = 2.dp
private val PANEL_TAG_COLUMN = 72.dp
private const val JUST_ENDED_LINE_ALPHA = 0.6f
