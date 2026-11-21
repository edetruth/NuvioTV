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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.GameStatus
import com.nuvio.tv.core.sportslive.GameStatusStrings
import com.nuvio.tv.core.sportslive.SportsLiveReadyMark
import com.nuvio.tv.core.sportslive.SportsLiveSportKind
import com.nuvio.tv.core.sportslive.SportsLiveTags
import com.nuvio.tv.core.sportslive.SportsLiveDisplayText
import com.nuvio.tv.core.sportslive.scores.EspnLeague
import com.nuvio.tv.core.sportslive.scores.EspnLeagueMap
import com.nuvio.tv.core.sportslive.scores.GameArtTeam
import com.nuvio.tv.core.sportslive.scores.TeamDirectory
import com.nuvio.tv.core.sportslive.scores.TeamLogoBook
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveFocus
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Size
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
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
    val tag: String = "",
    /** What tells this event or channel from another row of the same title ("Onboard", "UK"); null for none (live.20). */
    val suffix: String? = null,
    /** A ranked college team's rank (#137, live.24): "#5" before its name, as on every row ([GameIdentities.rankOf]). */
    val firstRank: Int? = null,
    val secondRank: Int? = null
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
    /** What tells an event or channel from a row of the same title ([panelTitleSuffixes]); null for none. */
    suffix: String? = null,
    /** An unmatched side's name for the line ([panelSideName]); as is by default. */
    sideName: (String) -> String = { it }
): PanelLine {
    val status = scoreboardStatusLines(row, strings, kickoff)
    // Short beside the clock (TV QA BUG-C): "1H" for men's college basketball's halves, "Q2", "OT".
    val halves = panelPlaysHalves((row.status as? GameStatus.Live)?.event?.league)
    val period = status.secondary.takeIf { status.live }?.let { panelPeriodShort(it, halves) }
    // live.20 F5: a live row with no real clock shows the red dot alone; "LIVE" on every row repeats the section header.
    val clock = status.primary.takeUnless { status.live && period == null && it == strings.live }
    val art = row.art
    return when {
        row.group == ScoreboardGroup.CHANNELS -> PanelLine(
            PanelLineKind.CHANNEL,
            title = panelTitleOf(row.item),
            clock = clock,
            live = status.live,
            suffix = suffix
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
            tag = tag,
            firstRank = GameIdentities.rankOf(art.first, art),
            secondRank = GameIdentities.rankOf(art.second, art)
        )
        else -> PanelLine(
            PanelLineKind.EVENT, title = panelTitleOf(row.item), clock = clock, period = period, live = status.live,
            tag = tag, suffix = suffix
        )
    }
}

/** An event's or a channel's one-line title on a panel row: the clean title, no leading decoration. */
internal fun panelTitleOf(item: SportsLiveItem): String =
    if (item.isChannel) panelCleanText(SportsLiveDisplayText.of(item.meta.name).name) else panelCleanText(singleLineTitle(item))

/**
 * The panel footer's line for [row] (live.20): who is playing, never cut short by the row's width. A two-sided game's
 * full names ("Orlando Magic vs Philadelphia 76ers", [vs] between them), else the clean title and its [suffix].
 */
internal fun panelFullName(row: ScoreboardRow, vs: String, suffix: String? = null): String {
    val art = row.art
    if (row.group != ScoreboardGroup.CHANNELS && row.twoSided && art != null) {
        return "${panelCleanText(art.first.name)} $vs ${panelCleanText(art.second.name)}"
    }
    return listOfNotNull(panelTitleOf(row.item), suffix).joinToString(PANEL_SUFFIX_SEPARATOR)
}

/**
 * What tells apart rows of one title (live.20: four "MotoGP" rows), by stream key; rows with a title of their own get
 * none. For each group of two or more rows whose [titleOf] reads the same, the first of these that differs between
 * them: the title's region and quality ("UK HD"), the start time ([startOf]), the catalog's name, the addon's name;
 * else "Feed 1", "Feed 2"… ([feed]). Never the description: an addon may put a live score there (release review,
 * spoilers). Each part is a clean label, never a web address, at most [PANEL_SUFFIX_MAX_CHARS].
 */
internal fun panelTitleSuffixes(
    items: List<SportsLiveItem>,
    feed: (Int) -> String,
    titleOf: (SportsLiveItem) -> String = ::panelTitleOf,
    startOf: (SportsLiveItem) -> String? = { panelStartLabel(it) }
): Map<String, String> {
    val sources = listOf(SUFFIX_SOURCES.first(), startOf) + SUFFIX_SOURCES.drop(1)
    val result = HashMap<String, String>()
    items.distinctBy { it.streamKey }
        .groupBy { titleOf(it).lowercase() }
        .values
        .filter { it.size > 1 }
        .forEach { group ->
            val title = titleOf(group.first()).lowercase()
            val parts = sources.asSequence()
                .map { source -> group.map { item -> source(item)?.let { panelSuffixPart(it, title) } } }
                .firstOrNull { values -> values.filterNotNull().distinct().size > 1 }
                ?: group.indices.map { feed(it + 1) }
            group.forEachIndexed { index, item -> parts[index]?.let { result[item.streamKey] = it } }
        }
    return result
}

/** Where a same-title row's suffix may come from, in order, the start time put second ([panelTitleSuffixes]). */
private val SUFFIX_SOURCES: List<(SportsLiveItem) -> String?> = listOf(
    { item -> SportsLiveDisplayText.of(item.meta.name).let { listOfNotNull(it.region, it.quality).joinToString(" ") } },
    { item -> SportsLiveDisplayText.label(item.catalogName) },
    { item -> SportsLiveDisplayText.label(item.addonName) }
)

/** [item]'s start as a short local time ("7:30 PM") for a same-title suffix; null when it has no time of day. */
internal fun panelStartLabel(item: SportsLiveItem, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String? {
    val start = item.startMs?.takeIf { item.startHasTimeOfDay } ?: return null
    return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(Instant.ofEpochMilli(start).atZone(zone))
}

/** [text] fit for a suffix: trimmed, at most [PANEL_SUFFIX_MAX_CHARS] (whole words), null when blank, the [title] itself or a web address. */
private fun panelSuffixPart(text: String, title: String): String? {
    val clean = panelCleanText(text).trim()
    if (clean.isEmpty() || clean.lowercase() == title || WEB_ADDRESS.containsMatchIn(clean)) return null
    if (clean.length <= PANEL_SUFFIX_MAX_CHARS) return clean
    val words = clean.split(WHITESPACE)
    val fitted = StringBuilder()
    for (word in words) {
        val next = if (fitted.isEmpty()) word else "$fitted $word"
        if (next.length > PANEL_SUFFIX_MAX_CHARS) break
        fitted.clear().append(next)
    }
    return fitted.toString().ifEmpty { clean.take(PANEL_SUFFIX_MAX_CHARS) }
}

/** A web address or a path in a label ("example.com", "a/b"): never shown as a suffix. */
private val WEB_ADDRESS = Regex("""/|[\p{L}\p{N}-]\.\p{L}{2,}""")

/** A same-title row's suffix's longest. */
internal const val PANEL_SUFFIX_MAX_CHARS = 22

/** Between a title and its suffix. */
internal const val PANEL_SUFFIX_SEPARATOR = " · "

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
 * The names an unmatched side may take on a panel line, longest first (live.20 F5): the side as given, then, for a
 * one-on-one sport ([individual]: tennis, fights, darts…), each player's last name ("Neal Skupski / Joe Salisbury" →
 * "Skupski / Salisbury", "Novak Djokovic" → "Djokovic"); a team's code: ESPN's real one when known ([code], BUG-C
 * follow-up), else one derived from its name ([panelShortCode]). The line steps down only when the longer one does not fit.
 */
internal fun panelSideChoices(name: String, individual: Boolean, code: String? = null): List<String> {
    val full = name.trim()
    if (!individual) {
        // Already a code ("UTAH", "ORL", TV QA BUG-C): nothing shorter reads better.
        if (panelIsCode(full)) return listOf(full)
        val short = code?.trim()?.takeIf { it.isNotEmpty() } ?: panelShortCode(full)
        return listOf(full, short).filter { it.isNotEmpty() }.distinct()
    }
    val players = full.split(PAIR_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
    val surnames = players.joinToString(" / ") { it.split(WHITESPACE).last() }
    return listOf(full, surnames).filter { it.isNotEmpty() }.distinct()
}

private val PAIR_SEPARATOR = Regex("""\s*[/&]\s*""")

/** A side that is a code already (TV QA BUG-C): one word of at most four capitals or digits ("UTAH", "ORL", "76"). */
internal fun panelIsCode(name: String): Boolean {
    val text = name.trim()
    return text.length in 1..4 && text.none(Char::isWhitespace) && text == text.uppercase(Locale.ROOT)
}

/**
 * A team's three-letter code for a tight panel line when ESPN gave none (BUG-C, TV QA: "A… L…", "U…"), in capitals
 * as matched rows' codes ("ORL"), from the team's place as ESPN's codes are ("Utah Utes" → UTA, "Dayton Flyers" →
 * DAY, "Pogoń Szczecin" → POG): club affixes, articles and numbers left out ("FC Andorra" → AND, "1. FC Köln" → KÖL,
 * "AS Nancy Lorraine" → NAN, "Le Havre" → HAV); one or two words: the first word's first three letters, topped up from
 * the second when it is shorter ("EA Guingamp" → EAG); three or more: the first three initials ("New York Knicks" →
 * NYK). Empty for a name with no letters.
 */
internal fun panelShortCode(name: String): String {
    val words = name.trim().split(WHITESPACE).map { word -> word.filter(Char::isLetter) }.filter { it.isNotEmpty() }
    val significant = words.filterNot { it.uppercase(Locale.ROOT) in CLUB_AFFIXES }.ifEmpty { words }
    val code = when (significant.size) {
        0 -> ""
        1, 2 -> significant.joinToString("").take(3)
        else -> significant.take(3).joinToString("") { it.take(1) }
    }
    return code.uppercase(Locale.ROOT)
}

/** Club affixes and articles a short code leaves out ("FC", "AFC", "SV", "Le"…): they tell no club apart. */
private val CLUB_AFFIXES = setOf(
    "FC", "CF", "AFC", "SC", "AC", "AS", "CD", "UD", "SD", "SV", "VFB", "VFL", "TSG", "SSC", "RC", "RCD", "FK", "NK",
    "SK", "BK", "IF", "KV", "CA", "CLUB", "DE", "LE", "LA", "LES", "EL", "AL", "THE"
)

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

/**
 * How a line's two names and the score (or "vs", the eye) between them share [available] px (user TV report, live.20:
 * "38–3" is a wrong score). The middle is never cut: it keeps its whole width ([middle]) while each name still gets
 * [minName] px (or all it needs, when less); the names then share the rest by need ([panelSideWidths]) and ellipsize.
 * When even that does not fit, the middle is dropped whole ([SidesFit.middle] false) and the names share the line.
 * [gap] sits on each side of the middle.
 */
internal data class SidesFit(val first: Int, val second: Int, val middle: Boolean)

internal fun panelSidesFit(wantFirst: Int, wantSecond: Int, middle: Int, gap: Int, minName: Int, available: Int): SidesFit {
    val room = available - middle - 2 * gap
    if (room >= minOf(wantFirst, minName) + minOf(wantSecond, minName)) {
        val (first, second) = panelSideWidths(wantFirst, wantSecond, room)
        return SidesFit(first, second, middle = true)
    }
    val (first, second) = panelSideWidths(wantFirst, wantSecond, available - 2 * gap)
    return SidesFit(first, second, middle = false)
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
    /** Listed since the panel opened (#164): a "New" tag at its end. */
    isNew: Boolean = false,
    /** Gone from the list since the panel opened (#164): kept in place, dimmed. */
    gone: Boolean = false,
    /** What the check-ahead found (#147, live.20 1b): "▶ Ready" or no working stream ("No stream yet"); null: not known, no mark. */
    readyMark: SportsLiveReadyMark? = null,
    /** What tells this row from another of the same title ([panelTitleSuffixes]); null for none. */
    titleSuffix: String? = null,
    /** Told whether the row's "▶ Ready" was left out for want of room (BUG-C), so the footer can say it instead. */
    onReadyHidden: ((Boolean) -> Unit)? = null
) {
    val strings = rememberGameStatusStrings()
    val endedShort = stringResource(R.string.sports_live_panel_probably_ended_short)
    val kickoff = row.art?.let { kickoffText(it) }
    // live.20 F5: the panel's own short tag, so the fixed column never cuts it ("MOTORSPORT" → "RACING").
    // The one game identity (live.24): Live Now's tag and sport.
    val identity = rememberGameIdentity(row)
    val tag = identity.tag
    val individual = identity.sport in PANEL_INDIVIDUAL_SPORTS
    val line = remember(row, strings, kickoff, tag, individual, titleSuffix) {
        panelLineOf(row, strings, kickoff, tag, titleSuffix) { side ->
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
    val checking = rememberSportsLiveChecking(row.item)
    // Set by the row's layout: its chips were left out so the names keep their room (BUG-C).
    var chipsDropped by remember { mutableStateOf(false) }
    val readyHidden = chipsDropped && panelReadyMarkShown(readyMark, focused) == SportsLiveReadyMark.READY
    val currentOnReadyHidden by rememberUpdatedState(onReadyHidden)
    LaunchedEffect(readyHidden) { currentOnReadyHidden?.invoke(readyHidden) }
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
                // BUG-C (TV QA): the chips give way before the names ([PanelLineParts]), never squeezing them.
                PanelLineParts(
                    main = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            when (line.kind) {
                                PanelLineKind.GAME -> GameLine(row, line, individual = individual)
                                // A channel keeps the empty tag column too (live.20 F5): its name starts where games' names do.
                                PanelLineKind.EVENT, PanelLineKind.CHANNEL -> EventLine(line, favorite = row.favorite)
                            }
                        }
                    },
                    status = { StatusText(line, compactClock = endedShort.takeIf { line.clock == strings.probablyEnded }) },
                    chips = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            panelReadyMarkShown(readyMark, focused)?.let { ReadyMark(it) }
                            if (panelCheckingShown(readyMark, focused, checking)) SportsLiveCheckingMark()
                            val tag = if (isNew) stringResource(R.string.sports_live_panel_new) else null
                            if (tag != null) {
                                Text(
                                    text = tag,
                                    style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag),
                                    color = Colors.TextSecondary,
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
                    },
                    modifier = Modifier.fillMaxSize().padding(start = 8.dp, end = 6.dp),
                    onChipsShown = { shown -> chipsDropped = !shown }
                )
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

/**
 * What a panel row's end shows of its readiness (live.20): "▶ Ready" always; "No stream yet" only while the row has
 * focus, so the words never squeeze the names: unfocused, the row is dimmed instead ([panelNoStreamDimmed]), as a
 * game whose lookup found nothing already is (#145).
 */
internal fun panelReadyMarkShown(mark: SportsLiveReadyMark?, focused: Boolean): SportsLiveReadyMark? =
    mark?.takeIf { it != SportsLiveReadyMark.NO_STREAM || focused }

/**
 * The faint "checking" mark on a panel row (live.20): while its link check runs and no readiness mark shows, so "not
 * checked yet" reads apart from "checked: no stream". A "▶ Ready" (or a focused "No stream yet") keeps its place.
 */
internal fun panelCheckingShown(mark: SportsLiveReadyMark?, focused: Boolean, checking: Boolean): Boolean =
    checking && panelReadyMarkShown(mark, focused) == null

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
 * mark or "vs" when none shows. Each side shows its longest form that fits ([panelSideChoices], [panelNamesFit]): a
 * team always keeps a readable code (ESPN's, else derived), never "U…"; the logos go before the codes would be cut.
 */
@Composable
private fun RowScope.GameLine(row: ScoreboardRow, line: PanelLine, individual: Boolean) {
    LineTag(line.tag, line.live)
    val logos = rememberTeamLogoPair(row.art)?.takeIf { !it.failed }
    // User TV report (live.20): the names always show beside the logos (crests alone name no one).
    val matched = row.art?.let { !it.first.unmatched && !it.second.unmatched } == true
    // ESPN's real codes for the names, when the app knows the teams anywhere (BUG-C follow-up): before derived ones.
    // Looked up by the card's own name first ("Utah Utes"), which a feed knows better than the line's ("Utah").
    val firstName = row.art?.first?.takeIf { it.unmatched }?.name?.let(::panelCleanText)
    val secondName = row.art?.second?.takeIf { it.unmatched }?.name?.let(::panelCleanText)
    val firstCode = remember(line.first, firstName) { panelCodeOf(firstName, line.first) }
    val secondCode = remember(line.second, secondName) { panelCodeOf(secondName, line.second) }
    val firstChoices = remember(line.first, individual, matched, firstCode) {
        panelSideChoices(line.first.orEmpty(), individual && !matched, firstCode)
    }
    val secondChoices = remember(line.second, individual, matched, secondCode) {
        panelSideChoices(line.second.orEmpty(), individual && !matched, secondCode)
    }
    PanelNames(
        firstChoices = firstChoices,
        secondChoices = secondChoices,
        firstRank = line.firstRank,
        secondRank = line.secondRank,
        firstLogo = { if (logos != null) TeamLogoSlot(logos.first, logos.shown, Size.PanelLogo) },
        middle = { LineMiddle(line.scores) },
        secondLogo = { if (logos != null) TeamLogoSlot(logos.second, logos.shown, Size.PanelLogo) },
        modifier = Modifier.weight(1f, fill = false)
    )
    if (row.followed) Icon(imageVector = Icons.Rounded.Star, contentDescription = null, tint = Colors.FollowedGold, modifier = Modifier.size(10.dp))
}

/** ESPN's code for a side, by the card's own name ([cardName]) and then by the line's ([lineName]); null when unknown. */
private fun panelCodeOf(cardName: String?, lineName: String?): String? =
    sequenceOf(cardName, lineName).filterNotNull().filter { it.isNotBlank() }.distinct()
        .firstNotNullOfOrNull { TeamLogoBook.shared.abbreviationAnywhere(it) }

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
 * Which form of each side a game line shows, and at what width (TV QA BUG-C): [first] and [second] index the sides'
 * forms ([panelSideChoices]), [firstWidth] and [secondWidth] are what each gets; [middle] (score, eye or "vs") and
 * [logos] say whether those show.
 */
internal data class PanelNamesFit(
    val first: Int,
    val second: Int,
    val firstWidth: Int,
    val secondWidth: Int,
    val middle: Boolean,
    val logos: Boolean
)

/**
 * How a game line's two sides fit [available] px (TV QA BUG-C: "U… 6–7 DAY"), decided from widths alone, never from
 * a cut text: [first] and [second] are each side's forms' widths, longest first (the name, then its code); [middle]
 * is the score's (or "vs"'s) width, [gap] the space on each side of it, [logos] both logos with their gaps (0 for
 * none). The full names and the logos first; while they do not fit the wider side steps down to its next form, then
 * the other; then the logos go, then the middle, whole. Only when even the two shortest forms alone do not fit are they
 * cut ([panelSideWidths]), so a code ("UTAH", "DAY") shows whole on any row the panel can draw.
 */
internal fun panelNamesFit(first: List<Int>, second: List<Int>, middle: Int, logos: Int, gap: Int, available: Int): PanelNamesFit {
    fun need(i: Int, j: Int, withLogos: Boolean, withMiddle: Boolean): Int =
        first[i] + gap + second[j] + (if (withMiddle) middle + gap else 0) + (if (withLogos) logos else 0)
    var i = 0
    var j = 0
    while (need(i, j, withLogos = true, withMiddle = true) > available) {
        val stepFirst = i < first.lastIndex && (j == second.lastIndex || first[i] >= second[j])
        when {
            stepFirst -> i++
            j < second.lastIndex -> j++
            else -> break
        }
    }
    return when {
        need(i, j, withLogos = true, withMiddle = true) <= available ->
            PanelNamesFit(i, j, first[i], second[j], middle = true, logos = logos > 0)
        need(i, j, withLogos = false, withMiddle = true) <= available ->
            PanelNamesFit(i, j, first[i], second[j], middle = true, logos = false)
        need(i, j, withLogos = false, withMiddle = false) <= available ->
            PanelNamesFit(i, j, first[i], second[j], middle = false, logos = false)
        else -> {
            val (a, b) = panelSideWidths(first[i], second[j], available - gap)
            PanelNamesFit(i, j, a, b, middle = false, logos = false)
        }
    }
}

/**
 * "[logo] first middle second [logo]" on one line (TV QA BUG-C): every form of each side ([firstChoices],
 * [secondChoices], longest first) is laid out as a candidate and the layout keeps the longest that fits
 * ([panelNamesFit]), in one pass: no form is chosen from a text that was already cut. Its least width (min intrinsic)
 * is both shortest forms around the middle, which the row keeps for it ([PanelLineParts]).
 */
@Composable
private fun PanelNames(
    firstChoices: List<String>,
    secondChoices: List<String>,
    firstLogo: @Composable () -> Unit,
    middle: @Composable () -> Unit,
    secondLogo: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    /** Ranked college teams' ranks (live.24): "#5" before every form, so the rank is never what is cut. */
    firstRank: Int? = null,
    secondRank: Int? = null
) {
    val firstForms = firstChoices.ifEmpty { listOf("") }
    val secondForms = secondChoices.ifEmpty { listOf("") }
    val policy = remember(firstForms.size, secondForms.size) { PanelNamesPolicy(firstForms.size, secondForms.size) }
    Layout(
        content = {
            Box { firstLogo() }
            firstForms.forEach { LineText(it, rank = firstRank) }
            Box { middle() }
            secondForms.forEach { LineText(it, rank = secondRank) }
            Box { secondLogo() }
        },
        modifier = modifier,
        measurePolicy = policy
    )
}

/** [PanelNames]' layout: its children are the first logo, [firstCount] forms, the middle, [secondCount] forms, the second logo. */
private class PanelNamesPolicy(private val firstCount: Int, private val secondCount: Int) : MeasurePolicy {
    private val middleIndex = 1 + firstCount
    private val secondLogoIndex = 2 + firstCount + secondCount

    private fun <T : IntrinsicMeasurable> firstForms(all: List<T>): List<T> = all.subList(1, 1 + firstCount)
    private fun <T : IntrinsicMeasurable> secondForms(all: List<T>): List<T> = all.subList(2 + firstCount, 2 + firstCount + secondCount)

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val gap = PANEL_SIDE_GAP.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val free = loose.copy(maxWidth = Constraints.Infinity)
        val firstWidths = firstForms(measurables).map { it.maxIntrinsicWidth(loose.maxHeight) }
        val secondWidths = secondForms(measurables).map { it.maxIntrinsicWidth(loose.maxHeight) }
        val logoA = measurables[0].measure(free)
        val mid = measurables[middleIndex].measure(free)
        val logoB = measurables[secondLogoIndex].measure(free)
        val logos = (if (logoA.width > 0) logoA.width + gap else 0) + (if (logoB.width > 0) logoB.width + gap else 0)
        val available = if (loose.hasBoundedWidth) {
            loose.maxWidth
        } else {
            firstWidths.first() + secondWidths.first() + mid.width + 2 * gap + logos
        }
        val fit = panelNamesFit(firstWidths, secondWidths, mid.width, logos, gap, available)
        val a = measurables[1 + fit.first].measure(loose.copy(maxWidth = fit.firstWidth.coerceAtLeast(0)))
        val b = measurables[2 + firstCount + fit.second].measure(loose.copy(maxWidth = fit.secondWidth.coerceAtLeast(0)))
        val parts = buildList {
            if (fit.logos && logoA.width > 0) add(logoA)
            add(a)
            if (fit.middle) add(mid)
            add(b)
            if (fit.logos && logoB.width > 0) add(logoB)
        }
        val height = parts.maxOf { it.height }
        val width = (parts.sumOf { it.width } + gap * (parts.size - 1)).coerceAtMost(available)
        return layout(width, height) {
            var x = 0
            parts.forEach { part ->
                part.placeRelative(x, (height - part.height) / 2)
                x += part.width + gap
            }
        }
    }

    /** At its least: both shortest forms around the middle, no logos. */
    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int {
        val gap = PANEL_SIDE_GAP.roundToPx()
        return firstForms(measurables).last().maxIntrinsicWidth(height) + gap +
            measurables[middleIndex].maxIntrinsicWidth(height) + gap +
            secondForms(measurables).last().maxIntrinsicWidth(height)
    }

    /** At its most: the logos and both full names around the middle. */
    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int {
        val gap = PANEL_SIDE_GAP.roundToPx()
        val logoA = measurables[0].maxIntrinsicWidth(height).let { if (it > 0) it + gap else 0 }
        val logoB = measurables[secondLogoIndex].maxIntrinsicWidth(height).let { if (it > 0) it + gap else 0 }
        return logoA + firstForms(measurables).first().maxIntrinsicWidth(height) + gap +
            measurables[middleIndex].maxIntrinsicWidth(height) + gap +
            secondForms(measurables).first().maxIntrinsicWidth(height) + logoB
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        measurables.maxOf { it.minIntrinsicHeight(width) }

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        measurables.maxOf { it.maxIntrinsicHeight(width) }
}

/**
 * A panel row's three parts on one line (BUG-C, TV QA): [main] (the tag and the names, or the title) from the start,
 * then [status] (the clock) and [chips] ("▶ Ready", the checking mark, "New", the OK hint) at the end. The chips give
 * way first: they show only while [main] keeps all it needs or at least [PANEL_NAMES_FLOOR] and its own least
 * ([panelChipsShown]); else they are left out whole. Then the status gives way to the main part's least (a game's
 * codes and score, TV QA: "U… 6–7 DAY"): it steps down to its shorter form ([panelStatusWidth], [PanelFirstFit]).
 */
@Composable
private fun PanelLineParts(
    main: @Composable () -> Unit,
    status: @Composable () -> Unit,
    chips: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    /** Told at each layout whether the chips show. */
    onChipsShown: (Boolean) -> Unit = {}
) {
    // Each part in a Box: a part that draws nothing (no clock) still takes its place, at no width.
    Layout(content = { Box { main() }; Box { status() }; Box { chips() } }, modifier = modifier) { measurables, constraints ->
        val gap = PANEL_PART_GAP.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val chipsPlaced = measurables[2].measure(loose)
        val chipsWidth = if (chipsPlaced.width > 0) chipsPlaced.width + gap else 0
        val wantMain = measurables[0].maxIntrinsicWidth(loose.maxHeight)
        val leastMain = measurables[0].minIntrinsicWidth(loose.maxHeight).coerceAtMost(wantMain)
        val wantStatus = measurables[1].maxIntrinsicWidth(loose.maxHeight)
        val wantStatusWidth = if (wantStatus > 0) wantStatus + gap else 0
        val bounded = loose.hasBoundedWidth
        val end = if (bounded) loose.maxWidth else wantMain + wantStatusWidth + chipsWidth
        val floor = maxOf(PANEL_NAMES_FLOOR.roundToPx(), leastMain)
        val showChips = !bounded || panelChipsShown(end, wantStatusWidth, chipsWidth, wantMain, floor)
        onChipsShown(showChips || chipsPlaced.width == 0)
        val chipsUsed = if (showChips) chipsWidth else 0
        val statusMax = panelStatusWidth(end - chipsUsed, wantStatus, leastMain, gap)
        val statusPlaced = measurables[1].measure(loose.copy(maxWidth = statusMax))
        val statusWidth = if (statusPlaced.width > 0) statusPlaced.width + gap else 0
        val mainMax = (end - statusWidth - chipsUsed).coerceAtLeast(0)
        val mainPlaced = measurables[0].measure(loose.copy(maxWidth = mainMax))
        val height = if (loose.hasBoundedHeight) loose.maxHeight else maxOf(mainPlaced.height, statusPlaced.height, chipsPlaced.height)
        layout(end, height) {
            mainPlaced.placeRelative(0, (height - mainPlaced.height) / 2)
            var x = end
            if (showChips && chipsPlaced.width > 0) {
                x -= chipsPlaced.width
                chipsPlaced.placeRelative(x, (height - chipsPlaced.height) / 2)
                x -= gap
            }
            if (statusPlaced.width > 0) statusPlaced.placeRelative(x - statusPlaced.width, (height - statusPlaced.height) / 2)
        }
    }
}

/**
 * The status's room on a panel row (TV QA BUG-C): all it wants ([wantStatus] px) while the main part keeps its least
 * ([leastMain] px: a game's codes and score) in [room] px with the [gap] between them; else what is left, so the status
 * steps down ("15:47 1st" → "15:47") rather than the codes being cut. 0 when there is no status.
 */
internal fun panelStatusWidth(room: Int, wantStatus: Int, leastMain: Int, gap: Int): Int =
    if (wantStatus <= 0) 0 else (room - leastMain - gap).coerceIn(0, wantStatus)

/** The first of [widths] (forms, longest first) that fits [available] px; else the last (shortest), to be cut. */
internal fun panelFirstFit(widths: List<Int>, available: Int): Int =
    widths.indexOfFirst { it <= available }.takeIf { it >= 0 } ?: widths.lastIndex

/**
 * Forms of one part, longest first, as [content]'s children (the status: "● 15:47 1st", then "● 15:47"): the first
 * that fits the width given ([panelFirstFit]). At least the last form's width, at most the first's.
 */
@Composable
internal fun PanelFirstFit(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier, measurePolicy = PanelFirstFitPolicy)
}

private object PanelFirstFitPolicy : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        if (measurables.isEmpty()) return layout(0, 0) {}
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val widths = measurables.map { it.maxIntrinsicWidth(loose.maxHeight) }
        val available = if (loose.hasBoundedWidth) loose.maxWidth else widths.first()
        val placeable = measurables[panelFirstFit(widths, available)].measure(loose)
        return layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measurables.lastOrNull()?.maxIntrinsicWidth(height) ?: 0

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        measurables.firstOrNull()?.maxIntrinsicWidth(height) ?: 0

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        measurables.maxOfOrNull { it.minIntrinsicHeight(width) } ?: 0

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        measurables.maxOfOrNull { it.maxIntrinsicHeight(width) } ?: 0
}

/**
 * The panel footer's line (live.20): the focused row's [fullName], then "▶ Ready" ([ready], after [separator]) when
 * its row had no room for the chip (BUG-C), so the mark is never lost on a tight row.
 */
internal fun panelFooterText(fullName: String, readyHidden: Boolean, ready: String, separator: String): String =
    if (readyHidden) fullName + separator + ready else fullName

/**
 * Whether a panel row's chips show (BUG-C): only while the main part ([wantMain] px at its longest) still gets all it
 * wants, or at least [floor] px, beside the status ([status] px with its gap) and the chips ([chips] px with theirs)
 * in [total] px. No chips: nothing to decide.
 */
internal fun panelChipsShown(total: Int, status: Int, chips: Int, wantMain: Int, floor: Int): Boolean =
    chips <= 0 || total - status - chips >= minOf(wantMain, floor)

/**
 * "[first] [middle] [second]" on one line (live.20 F5): the middle (score, eye or "vs") at its own width, never cut,
 * and the names sharing the rest by need ([panelSidesFit]), so a short name never leaves room unused that the long one
 * could take; with too little room the middle is left out whole. As narrow as its content.
 */
@Composable
internal fun PanelSides(
    first: @Composable () -> Unit,
    middle: @Composable () -> Unit,
    second: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Layout(content = { first(); middle(); second() }, modifier = modifier) { measurables, constraints ->
        val gap = PANEL_SIDE_GAP.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val mid = measurables[1].measure(loose.copy(maxWidth = Constraints.Infinity))
        val wantFirst = measurables[0].maxIntrinsicWidth(loose.maxHeight)
        val wantSecond = measurables[2].maxIntrinsicWidth(loose.maxHeight)
        val available = if (loose.hasBoundedWidth) loose.maxWidth else wantFirst + wantSecond + mid.width + 2 * gap
        val fit = panelSidesFit(wantFirst, wantSecond, mid.width, gap, PANEL_MIN_NAME.roundToPx(), available)
        val a = measurables[0].measure(loose.copy(maxWidth = fit.first.coerceAtLeast(0)))
        val b = measurables[2].measure(loose.copy(maxWidth = fit.second.coerceAtLeast(0)))
        val midWidth = if (fit.middle) mid.width else 0
        val height = maxOf(a.height, if (fit.middle) mid.height else 0, b.height)
        val width = (a.width + gap + midWidth + gap + b.width).coerceAtMost(available)
        layout(width, height) {
            a.placeRelative(0, (height - a.height) / 2)
            if (fit.middle) mid.placeRelative(a.width + gap, (height - mid.height) / 2)
            b.placeRelative(a.width + gap + midWidth + gap, (height - b.height) / 2)
        }
    }
}

/**
 * A row's league or sport tag ("NBA", "TENNIS", [SportsLiveTags.short]) at the start of one fixed column (live.20 F5):
 * every tag fits it whole, its left edge is one line down the panel and names start at one x on every row; the column
 * stays, empty, when no tag is known. A [live] row's tag is in the live colour (V2), so the live games read at a glance.
 */
@Composable
private fun LineTag(tag: String, live: Boolean) {
    Box(modifier = Modifier.width(LocalPanelTagColumn.current), contentAlignment = Alignment.CenterStart) {
        if (tag.isNotEmpty()) {
            Text(
                text = tag,
                style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag),
                color = if (live) Colors.LiveText else Colors.TextSecondary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * A single-line event or a channel: its sport tag, its clean title, then (muted, never cut) what tells it from a row of
 * the same title ("· Onboard"): the title gives way first.
 */
@Composable
private fun RowScope.EventLine(line: PanelLine, favorite: Boolean = false) {
    LineTag(line.tag, line.live)
    LineText(line.title.orEmpty(), Modifier.weight(1f, fill = false))
    // A favorite channel (Channels phase 1): the gold ★ after its name, as a followed team's game.
    if (favorite) Icon(imageVector = Icons.Rounded.Star, contentDescription = null, tint = Colors.FollowedGold, modifier = Modifier.size(10.dp))
    line.suffix?.let { suffix ->
        Text(
            text = PANEL_SUFFIX_SEPARATOR.trimStart() + suffix,
            style = SportsLiveTokens.style(SportsLiveTokens.Type.PanelLine),
            color = Colors.TextMuted,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun LineText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Colors.TextPrimary,
    /** A ranked college team (live.24): a small muted "#5" before the name ([rankedName]). */
    rank: Int? = null
) {
    if (rank != null) {
        Text(
            text = rankedName(text, rank, SportsLiveTokens.Type.Tag.fontSize),
            style = SportsLiveTokens.style(SportsLiveTokens.Type.PanelLine),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier
        )
        return
    }
    Text(
        text = text,
        style = SportsLiveTokens.style(SportsLiveTokens.Type.PanelLine),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/**
 * The status at the row's end: the red dot and the clock with its period while live (the dot alone when no clock is
 * known: the LIVE header says the word), else the time or final word. TV QA BUG-C ("15:47 1st" crowding the score):
 * the period is short ([panelLineOf], [panelPeriodShort]) and smaller, beside the clock; where the row is tight the status steps down
 * ([PanelFirstFit]) to the clock alone, and a [compactClock] ("Ended?") stands in for a long word ("Probably ended").
 */
@Composable
private fun StatusText(line: PanelLine, compactClock: String? = null) {
    val clock = line.clock
    if (clock == null && !line.live) return
    val period = line.period
    PanelFirstFit {
        StatusForm(line.live, clock, period)
        if (period != null) StatusForm(line.live, clock, null)
        if (compactClock != null && compactClock != clock) StatusForm(line.live, compactClock, null)
    }
}

/** One form of a row's status ([StatusText]): the live dot, the [clock] and, smaller, its [period]. */
@Composable
private fun StatusForm(live: Boolean, clock: String?, period: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (live) Box(modifier = Modifier.size(LIVE_DOT).clip(CircleShape).background(Colors.LiveRed))
        if (clock != null) {
            Text(
                text = clock,
                style = SportsLiveTokens.style(SportsLiveTokens.Type.PanelScore),
                color = if (live) Colors.TextPrimary else Colors.TextSecondary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (period != null) {
            Text(
                text = period,
                style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag),
                color = Colors.TextSecondary,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/**
 * A period short for a panel row's status (TV QA BUG-C): "1st half" → "1H", "2nd quarter" → "Q2", "3rd period" →
 * "3rd", "overtime" → "OT"; a bare "1st" or "2nd" is a half where the game plays [halves] ("1H", coordinator decision:
 * men's college basketball); anything else ("1st", "Q4", "OT2") as it is.
 */
internal fun panelPeriodShort(period: String, halves: Boolean = false): String {
    val text = period.trim()
    if (halves) ORDINAL.matchEntire(text)?.let { return "${it.groupValues[1]}H" }
    PERIOD_WORDS.matchEntire(text)?.let { match ->
        val (number, suffix, unit) = match.destructured
        return when (unit.lowercase(Locale.ROOT)) {
            "half" -> "${number}H"
            "quarter" -> "Q$number"
            else -> number + suffix.lowercase(Locale.ROOT)
        }
    }
    return if (text.equals("overtime", ignoreCase = true)) "OT" else text
}

/**
 * Whether a game in [league] plays two halves, so ESPN's "1st" is its first half: men's college basketball (the
 * women's game plays quarters since 2015, as the pros do).
 */
internal fun panelPlaysHalves(league: EspnLeague?): Boolean = league == EspnLeagueMap.COLLEGE_BASKETBALL

private val ORDINAL = Regex("""^([12])(st|nd)$""", RegexOption.IGNORE_CASE)

private val PERIOD_WORDS = Regex("""^(\d+)(st|nd|rd|th)\s+(half|quarter|period)$""", RegexOption.IGNORE_CASE)

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
    height: androidx.compose.ui.unit.Dp = Size.PanelLine,
    /** More live's row (user 2026-11-21): "More live: 4 Racing · 3 Cricket ›" in place of the count's words. */
    summary: List<Pair<String, Int>> = emptyList()
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
                text = if (summary.isNotEmpty()) moreLiveRowText(summary) else stringResource(R.string.sports_live_hidden_by_my_sports, count),
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
/** Fits [SportsLiveTags.MAX_CHARS] capitals at the Tag size ("BASKETBALL" is about 72 dp, #217), never cut (live.20 F5). */
private val PANEL_TAG_COLUMN = 76.dp

/**
 * The panel rows' tag column (TV QA BUG-C follow-up, coordinator decision): as wide as the widest tag the panel shows
 * ([rememberPanelTagColumn]), so "NCAAM" rows do not keep "FOOTBALL"'s width; [PANEL_TAG_COLUMN] outside a panel.
 */
internal val LocalPanelTagColumn = staticCompositionLocalOf { PANEL_TAG_COLUMN }

/**
 * The tag column for a panel showing [rows]: the widest of their tags ([boardTagText]) at the Tag size, at most
 * [PANEL_TAG_COLUMN] ([panelTagColumnWidth]); names start at one x on every row still.
 */
@Composable
internal fun rememberPanelTagColumn(rows: List<ScoreboardRow>): Dp {
    val tags = rows.map { boardTagText(it) }.filter { it.isNotEmpty() }.distinct()
    val style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(tags, style, density) {
        val widths = tags.map { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width }
        with(density) { panelTagColumnWidth(widths, PANEL_TAG_COLUMN.roundToPx()).toDp() }
    }
}

/** The tag column's width in px: the widest tag's ([widths]), at most [max]; 0 when no row has a tag. */
internal fun panelTagColumnWidth(widths: List<Int>, max: Int): Int = (widths.maxOrNull() ?: 0).coerceIn(0, max)
private val PANEL_SIDE_GAP = 5.dp
private val PANEL_PART_GAP = 5.dp

/**
 * The least a panel row's tag and names keep before its chips go (BUG-C): the tag column, two logos and two
 * three-letter names around "vs" ("NBA [logo] AND vs LEG [logo]") at the panel's line size.
 */
private val PANEL_NAMES_FLOOR = 190.dp

/** The least a name keeps beside the score before the score goes (about three letters and an ellipsis). */
private val PANEL_MIN_NAME = 26.dp
private val LIVE_DOT = 6.dp
private const val JUST_ENDED_LINE_ALPHA = 0.6f
