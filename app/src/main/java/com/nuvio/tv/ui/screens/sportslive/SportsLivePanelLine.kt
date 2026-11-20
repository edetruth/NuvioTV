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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
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
import com.nuvio.tv.core.sportslive.SportsLiveSportKind
import com.nuvio.tv.core.sportslive.SportsLiveDisplayText
import com.nuvio.tv.core.sportslive.scores.GameArtTeam
import com.nuvio.tv.core.sportslive.scores.TeamDirectory
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveFocus
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Size
import java.util.Locale

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
    // live.20 F5: a live row with no real clock shows the red dot alone; "LIVE" on every row repeats the section header.
    val clock = status.primary.takeUnless { status.live && period == null && it == strings.live }
    val art = row.art
    return when {
        row.group == ScoreboardGroup.CHANNELS -> PanelLine(
            PanelLineKind.CHANNEL,
            title = panelCleanText(SportsLiveDisplayText.of(row.item.meta.name).name),
            clock = clock,
            live = status.live
        )
        row.twoSided && art != null -> PanelLine(
            PanelLineKind.GAME,
            first = panelTeamLabel(art.first).let { if (art.first.unmatched) sideName(panelCleanText(it)) else it },
            second = panelTeamLabel(art.second).let { if (art.second.unmatched) sideName(panelCleanText(it)) else it },
            scores = scoreboardScoresOf(row.chip),
            // Score hidden: the eye between the names says so; the words would push the names out (#167).
            clock = clock.takeUnless { row.chip is ScoreChip.Hidden },
            period = period,
            live = status.live,
            tag = tag
        )
        else -> PanelLine(PanelLineKind.EVENT, title = panelCleanText(singleLineTitle(row.item)), clock = clock, period = period, live = status.live, tag = tag)
    }
}

/**
 * [text] without the addon's leading decoration (live.20 F5, #179): emoji or symbols ("📺 Portugal Free Practice"),
 * invisible marks, and a bracketed prefix ("[NBA 11/19] Lakers"); as it is when nothing would be left.
 */
internal fun panelCleanText(text: String): String {
    val trimmed = text.trim()
    val clean = trimmed.replaceFirst(LEADING_DECORATION, "").replaceFirst(LEADING_BRACKET, "").replaceFirst(LEADING_SYMBOLS, "").trim()
    return clean.ifEmpty { trimmed }
}

private val LEADING_DECORATION = Regex("""^[^\p{L}\p{N}(\[]+""")
private val LEADING_BRACKET = Regex("""^\[[^\]]*]\s*""")
private val LEADING_SYMBOLS = Regex("""^[^\p{L}\p{N}(]+""")

/**
 * A row's tag in the panel's fixed tag column (live.20 F5, #179): at most [PANEL_TAG_MAX_CHARS] capitals, so it never
 * cuts and names start at one x on every row. A short league or sport tag ("NBA", "NCAAM", "TENNIS", "CRICKET") as it
 * is; a long league its known short form ("BUNDESLIGA" → "BUNDES"); a long sport word its panel word ("MOTORSPORT" →
 * "MOTOR", "BASKETBALL" → "BBALL"); any other long tag the row's [sport] word, else its first word, else its start.
 */
internal fun panelTag(tag: String, sport: SportsLiveSportKind?): String {
    val text = tag.trim().uppercase(Locale.ROOT)
    if (text.isEmpty()) return ""
    PANEL_LEAGUE_SHORT[text]?.let { return it }
    if (text.length <= PANEL_TAG_MAX_CHARS) return text
    SportsLiveSportKind.of(text)?.let { return panelSportWord(it) }
    sport?.let { return panelSportWord(it) }
    text.split(' ').first().takeIf { it.isNotEmpty() && it.length <= PANEL_TAG_MAX_CHARS }?.let { return it }
    return text.take(PANEL_TAG_MAX_CHARS).trim()
}

/** A sport's word in the panel's tag column: its name, the three long ones shortened. */
internal fun panelSportWord(sport: SportsLiveSportKind): String = when (sport) {
    SportsLiveSportKind.BASKETBALL -> "BBALL"
    SportsLiveSportKind.MOTORSPORT -> "MOTOR"
    SportsLiveSportKind.WRESTLING -> "WRESTLE"
    else -> sport.name
}

/** The panel's short form of the league tags longer than its column ([com.nuvio.tv.core.sportslive.SportsLiveLeagueTags]). */
private val PANEL_LEAGUE_SHORT = mapOf(
    "WORLD CUP" to "WC",
    "WOMEN'S WC" to "WWC",
    "U21 FRNDLY" to "U21",
    "NATIONS LG" to "NATIONS",
    "EURO QUAL" to "EURO Q",
    "AFCON QUAL" to "AFCON Q",
    "ASIAN CUP" to "ASIA CUP",
    "CHAMPNSHP" to "EFL CH",
    "LEAGUE CUP" to "EFL CUP",
    "BUNDESLIGA" to "BUNDES",
    "DFB POKAL" to "POKAL",
    "EREDIVISIE" to "EREDIV",
    "SUPER LIG" to "SUPERLIG",
    "SAUDI PRO" to "SAUDI",
    "LIGA 1 ROU" to "LIGA 1",
    "EUROLEAGUE" to "EUROLG"
)

/** The panel tag's longest: eight capitals fit its [PANEL_TAG_COLUMN] at the Tag size ("FOOTBALL", "BASEBALL"). */
internal const val PANEL_TAG_MAX_CHARS = 8

/**
 * The names an unmatched side may take on a panel line, longest first (live.20 F5): the side as given, then, for a
 * one-on-one sport ([individual]: tennis, fights, darts…), each player's last name ("Neal Skupski / Joe Salisbury" →
 * "Skupski / Salisbury", "Novak Djokovic" → "Djokovic"). The line steps down only when the longer one does not fit.
 */
internal fun panelSideChoices(name: String, individual: Boolean): List<String> {
    val full = name.trim()
    if (!individual) return listOf(full)
    val players = full.split(PAIR_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
    val surnames = players.joinToString(" / ") { it.split(WHITESPACE).last() }
    return listOf(full, surnames).filter { it.isNotEmpty() }.distinct()
}

private val PAIR_SEPARATOR = Regex("""\s*[/&]\s*""")

/** Sports played one on one (or two on two): a side is a player's name, best cut to the last name. */
internal val PANEL_INDIVIDUAL_SPORTS = setOf(
    SportsLiveSportKind.TENNIS, SportsLiveSportKind.MMA, SportsLiveSportKind.BOXING, SportsLiveSportKind.DARTS,
    SportsLiveSportKind.SNOOKER, SportsLiveSportKind.GOLF
)

/**
 * The widths of a line's two names in [available] px (live.20 F5): each its own when both fit; else a name that needs
 * no more than half keeps its width and the other takes the rest; else half each. Never more than either needs.
 */
internal fun panelSideWidths(first: Int, second: Int, available: Int): Pair<Int, Int> {
    val room = available.coerceAtLeast(0)
    if (first + second <= room) return first to second
    val half = room / 2
    return when {
        first <= half -> first to room - first
        second <= half -> room - second to second
        else -> half to room - half
    }
}

/** A side the art names by the card's own words: no ESPN abbreviation or short name was found for it. */
private val GameArtTeam.unmatched: Boolean get() = short.trim() == name.trim()

/**
 * An unmatched side's name on a one-line panel row (#167): its directory [abbreviation] ("SUU") when it has one;
 * else the name without its trailing nickname when what is left is a place, two words or more or a known
 * location ([isPlace]): "Southern Utah Thunderbirds" → "Southern Utah", "Stetson Hatters" → "Stetson"; else the
 * name as it is. Whatever still does not fit is cut per side on the line, so both sides stay visible. A player's
 * name in a one-on-one sport ([individual], live.20) is never cut as a team's nickname ("Neal Skupski / Joe Salisbury"
 * stays whole; the line may step it down to last names, [panelSideChoices]).
 */
internal fun panelSideName(
    name: String,
    abbreviation: String?,
    individual: Boolean = false,
    isPlace: (String) -> Boolean = { false }
): String {
    if (individual) return name.trim()
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
    /** What the check-ahead found (#147, live.20 1b): "▶ Ready" or no working stream ("No stream yet"); null: not known, no mark. */
    readyMark: SportsLiveReadyMark? = null
) {
    val strings = rememberGameStatusStrings()
    val kickoff = row.art?.let { kickoffText(it) }
    // live.20 F5: the panel's own short tag, so the fixed column never cuts it ("MOTORSPORT" → "MOTOR").
    val sport = remember(row) { row.sportTag ?: SportsLiveSportKind.plainTagOf(row.item) }
    val tag = panelTag(rowTagText(row), sport)
    val individual = sport in PANEL_INDIVIDUAL_SPORTS
    val line = remember(row, strings, kickoff, tag, individual) {
        panelLineOf(row, strings, kickoff, tag) { side ->
            panelSideName(
                side,
                TeamDirectory.shared.abbreviationOf(row.item, side),
                individual = individual,
                isPlace = TeamDirectory.shared::isLocation
            )
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
                .then(if (row.dimmed(focused) || (gone && !focused) || panelNoStreamDimmed(readyMark, focused)) Modifier.alpha(JUST_ENDED_LINE_ALPHA) else Modifier)
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
                            PanelLineKind.GAME -> GameLine(row, line, individual = individual)
                            PanelLineKind.EVENT -> EventLine(line)
                            PanelLineKind.CHANNEL -> {
                                // The empty tag column too (live.20 F5): a channel's name starts where games' names do.
                                LineTag(line.tag)
                                LineText(line.title.orEmpty(), Modifier.weight(1f, fill = false))
                            }
                        }
                    }
                    StatusText(line)
                    panelReadyMarkShown(readyMark, focused)?.let { ReadyMark(it) }
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

/**
 * What a panel row's end shows of its readiness (live.20): "▶ Ready" always; "No stream yet" only while the row has
 * focus, so the words never squeeze the names: unfocused, the row is dimmed instead ([panelNoStreamDimmed]), as a
 * game whose lookup found nothing already is (#145).
 */
internal fun panelReadyMarkShown(mark: SportsLiveReadyMark?, focused: Boolean): SportsLiveReadyMark? =
    mark?.takeIf { it != SportsLiveReadyMark.NO_STREAM || focused }

/** A row with no working stream found (live.20), dimmed until focused, where its "No stream yet" shows. */
internal fun panelNoStreamDimmed(mark: SportsLiveReadyMark?, focused: Boolean): Boolean =
    mark == SportsLiveReadyMark.NO_STREAM && !focused

/** The game's readiness in words (#147, 1b): "▶ Ready" while a verified link is held, "No stream yet" (muted) when none was found. */
@Composable
private fun ReadyMark(mark: SportsLiveReadyMark) {
    SportsLiveReadyMarkView(mark, showNoStream = true)
}

/**
 * "NBA [logo] ORL 97–92 PHI [logo]" (V2 `Player.dc.html` 3): the tag, a matched game's logos or flags beside the
 * abbreviations around the score (none when they are not both known), the leader's score in real bold, the hidden
 * mark or "vs" when none shows. The names give way (ellipsis) first: the tag, the score and the clock always fit.
 * Between them the names share the room by need ([panelSideWidths]); a player's side in an [individual] sport first
 * steps down to last names ([panelSideChoices]) before it is cut.
 */
@Composable
private fun RowScope.GameLine(row: ScoreboardRow, line: PanelLine, individual: Boolean) {
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
    if (namesFit || !logosShown) {
        PanelSides(
            first = { SideText(line.first.orEmpty(), individual && !matched, onOverflow) },
            middle = { LineMiddle(line.scores) },
            second = { SideText(line.second.orEmpty(), individual && !matched, onOverflow) },
            modifier = Modifier.weight(1f, fill = false)
        )
    } else {
        LineMiddle(line.scores)
    }
    if (logos != null) TeamLogoSlot(logos.second, logos.shown, Size.PanelLogo)
    if (row.followed) Icon(imageVector = Icons.Rounded.Star, contentDescription = null, tint = Colors.FollowedGold, modifier = Modifier.size(10.dp))
}

/** Between a line's names: the score (the leader's in bold), the hidden-score eye, or "vs". */
@Composable
private fun LineMiddle(scores: ScoreboardScores) {
    when (scores) {
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
}

/**
 * One side's name: its longest choice ([panelSideChoices]) that fits the width [PanelSides] gave it, else the
 * shortest cut with an ellipsis; [onOverflow] is told once even the shortest does not fit. Taken afresh per name.
 */
@Composable
private fun SideText(name: String, individual: Boolean, onOverflow: (() -> Unit)?) {
    val choices = remember(name, individual) { panelSideChoices(name, individual) }
    var level by remember(choices) { mutableIntStateOf(0) }
    LineText(
        choices[level.coerceAtMost(choices.lastIndex)],
        onOverflow = { if (level < choices.lastIndex) level++ else onOverflow?.invoke() }
    )
}

/**
 * "[first] [middle] [second]" on one line (live.20 F5): the middle (score, eye or "vs") at its own width, and the
 * names sharing the rest by need ([panelSideWidths]), so a short name never leaves room unused that the long one
 * could take. As narrow as its content.
 */
@Composable
private fun PanelSides(
    first: @Composable () -> Unit,
    middle: @Composable () -> Unit,
    second: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Layout(content = { first(); middle(); second() }, modifier = modifier) { measurables, constraints ->
        val gap = PANEL_SIDE_GAP.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val mid = measurables[1].measure(loose)
        val wantFirst = measurables[0].maxIntrinsicWidth(loose.maxHeight)
        val wantSecond = measurables[2].maxIntrinsicWidth(loose.maxHeight)
        val room = if (loose.hasBoundedWidth) loose.maxWidth - mid.width - 2 * gap else wantFirst + wantSecond
        val (firstWidth, secondWidth) = panelSideWidths(wantFirst, wantSecond, room)
        val a = measurables[0].measure(loose.copy(maxWidth = firstWidth))
        val b = measurables[2].measure(loose.copy(maxWidth = secondWidth))
        val height = maxOf(a.height, mid.height, b.height)
        val width = (a.width + gap + mid.width + gap + b.width).coerceAtMost(loose.maxWidth)
        layout(width, height) {
            a.placeRelative(0, (height - a.height) / 2)
            mid.placeRelative(a.width + gap, (height - mid.height) / 2)
            b.placeRelative(a.width + gap + mid.width + gap, (height - b.height) / 2)
        }
    }
}

/**
 * A row's league or sport tag ("NBA", "TENNIS", [panelTag]) at the start of one fixed column (live.20 F5): every tag
 * fits it whole, its left edge is one line down the panel and names start at one x on every row; the column stays,
 * empty, when no tag is known.
 */
@Composable
private fun LineTag(tag: String) {
    Box(modifier = Modifier.width(PANEL_TAG_COLUMN), contentAlignment = Alignment.CenterStart) {
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

/**
 * The status at the row's end: the red dot and the clock with its period while live (the dot alone when no clock is
 * known: the LIVE header says the word), else the time or final word.
 */
@Composable
private fun StatusText(line: PanelLine) {
    val clock = line.clock
    if (clock == null && !line.live) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (line.live) Box(modifier = Modifier.size(LIVE_DOT).clip(CircleShape).background(Colors.LiveRed))
        if (clock != null) {
            Text(
                text = line.period?.let { stringResource(R.string.sports_live_panel_clock, clock, it) } ?: clock,
                style = SportsLiveTokens.style(SportsLiveTokens.Type.PanelScore),
                color = if (line.live) Colors.TextPrimary else Colors.TextSecondary,
                maxLines = 1,
                softWrap = false
            )
        }
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
/** Fits [PANEL_TAG_MAX_CHARS] capitals at the Tag size ("FOOTBALL" is about 57 dp), never cut (live.20 F5). */
private val PANEL_TAG_COLUMN = 62.dp
private val PANEL_SIDE_GAP = 5.dp
private val LIVE_DOT = 6.dp
private const val JUST_ENDED_LINE_ALPHA = 0.6f
