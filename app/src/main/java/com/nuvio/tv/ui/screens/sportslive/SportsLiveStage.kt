package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.scores.RankDisplay
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.ui.res.pluralStringResource
import com.nuvio.tv.core.sportslive.SportsLiveChannelCountries
import com.nuvio.tv.core.sportslive.SportsLiveChannelRegions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.SportsLiveDisplayText
import com.nuvio.tv.core.sportslive.SportsLiveSportKind
import com.nuvio.tv.core.sportslive.displayName
import com.nuvio.tv.core.sportslive.GameStatusStrings
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.GameArtTeam
import com.nuvio.tv.core.sportslive.scores.ScoreDetails
import com.nuvio.tv.core.sportslive.scores.ScoreDetailsBuilder
import com.nuvio.tv.core.sportslive.scores.TeamLogoUrls
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniPlacement
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniPlayers
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniState
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniSlot
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniSpot
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveBadge
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButton
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButtonRole
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors
import java.util.Locale

/**
 * Live Now's stage (V2, #132): 360 dp wide for every row. The media box (360×203) holds the muted preview, else
 * the drawn matchup (or our drawn event card, [stageArtKind]) at the same size, and the mini player when one plays
 * ([miniInStage]: it takes this slot instead of floating over the screen). Under it the score block (the 32 sp
 * scoreline, the status line, the facts line: "▶ Stream ready · Started 7:30 PM", the
 * linescore) or, for a channel, its clean name and "Now: …"; then, right under it (live.20), one row of two
 * buttons: **Watch** and **More…**.
 */
@Composable
internal fun SportsLiveStage(
    row: ScoreboardRow,
    details: ScoreDetails?,
    /** The SB1 situation line ("" for none), shown only with the live score. */
    situation: String,
    preview: (@Composable () -> Unit)?,
    previewShowing: Boolean,
    /** The mini player plays: it is drawn in the media box ([SportsLiveMiniSpot.STAGE]). */
    miniInStage: Boolean,
    noStream: Boolean,
    onWatch: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    watchModifier: Modifier = Modifier,
    moreModifier: Modifier = Modifier,
    /** How the stage reads with the mini player in its media box (#157). */
    header: SportsLiveStageHeader = SportsLiveStageHeader.NO_MINI,
    /** The facts line under the status (live.20): "▶ Ready", the start of a game in play, its sources. */
    facts: StageFacts = StageFacts(ready = false, startedAtMs = null)
) {
    val headed = header == SportsLiveStageHeader.SELECTED
    Column(
        modifier = modifier.width(SportsLiveStageLayout.WIDTH_DP.dp),
        verticalArrangement = Arrangement.spacedBy(SportsLiveStageLayout.GAP_DP.dp)
    ) {
        StageMedia(row, preview, previewShowing, miniInStage)
        if (headed) SportsLiveStageSelectedHeading()
        if (row.group == ScoreboardGroup.CHANNELS) {
            // A channel's text block takes its own height, so the buttons sit right under it (no empty block).
            ChannelBlock(row, noStream)
        } else {
            // live.20 (the user's TV photos): the block takes the height it needs, so the buttons sit right under it.
            Box(modifier = Modifier.fillMaxWidth().heightIn(max = SportsLiveStageLayout.INFO_MAX_DP.dp)) {
                GameBlock(row, details, situation, noStream, facts)
            }
        }
        Row(
            modifier = Modifier.height(SportsLiveStageLayout.BUTTON_ROW_HEIGHT_DP.dp),
            horizontalArrangement = Arrangement.spacedBy(SportsLiveTokens.Size.ButtonGap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SportsLiveStageLayout.buttons(row).forEach { button ->
                when (button) {
                    SportsLiveStageButton.WATCH -> SportsLiveButton(
                        onClick = onWatch,
                        role = SportsLiveButtonRole.PRIMARY,
                        modifier = watchModifier
                            .widthIn(min = WATCH_MIN_WIDTH)
                            .then(if (noStream) Modifier.alpha(0.5f) else Modifier)
                    ) {
                        Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        ButtonLabel(stringResource(R.string.sports_live_watch))
                    }
                    SportsLiveStageButton.MORE -> SportsLiveButton(
                        onClick = onMore,
                        role = SportsLiveButtonRole.SECONDARY,
                        modifier = moreModifier.widthIn(min = MORE_MIN_WIDTH)
                    ) {
                        ButtonLabel(stringResource(R.string.sports_live_more))
                    }
                }
            }
        }
    }
}

@Composable
private fun ButtonLabel(text: String) {
    Text(text = text, style = SportsLiveTokens.style(SportsLiveTokens.Type.Button), maxLines = 1, softWrap = false)
}

/**
 * How a stage reads while the mini player plays in its media box (#157): with no mini player, as always
 * ([NO_MINI]); on another game than the mini player's, its details under a **Selected** heading, the video
 * labelled WATCHING ([SELECTED]), so the two are never read as one; on the mini player's own game, one normal
 * stage whose Watch expands the mini player ([MERGED]).
 */
internal enum class SportsLiveStageHeader { NO_MINI, SELECTED, MERGED }

/** The stage's header for the mini player's game ([miniKey], null when none plays) and the stage's ([selectedKey]). */
internal fun sportsLiveStageHeader(miniKey: String?, selectedKey: String?): SportsLiveStageHeader = when {
    miniKey == null -> SportsLiveStageHeader.NO_MINI
    miniKey == selectedKey -> SportsLiveStageHeader.MERGED
    else -> SportsLiveStageHeader.SELECTED
}

/** The "Selected" heading over the focused game's details while the mini player plays another (#157). */
internal object SportsLiveStageSelectedHeading {
    val HEIGHT = 16.dp

    @Composable
    operator fun invoke() {
        Row(
            modifier = Modifier.height(HEIGHT),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.width(3.dp).height(12.dp).background(Colors.FocusWhite))
            Text(
                text = stringResource(R.string.sports_live_stage_selected).uppercase(Locale.getDefault()),
                style = SportsLiveTokens.style(SportsLiveTokens.Type.SectionHeader),
                color = Colors.TextSecondary,
                maxLines = 1
            )
        }
    }
}

/**
 * The media box's place, passed to the mini player while it plays (#132, #157), which is then drawn over it:
 * every stage's media box ([miniInStage], else the mini player's own state) claims it.
 */
@Composable
internal fun Modifier.sportsLiveMiniSlot(miniInStage: Boolean? = null): Modifier {
    val docked = miniInStage
        ?: (SportsLiveMiniPlayers.controller.state.collectAsState().value is SportsLiveMiniState.Playing)
    val token = remember { Any() }
    // The mini player takes this slot while it plays; held through a tab switch, then the corner once Sports Live leaves.
    DisposableEffect(docked) {
        if (docked) SportsLiveMiniPlacement.claim(token, SportsLiveMiniSpot.STAGE)
        onDispose { SportsLiveMiniPlacement.release(token) }
    }
    // #486: the box's place is kept whether or not the mini player plays, and handed over when it docks too. A placement
    // callback runs only when the layout changes, so one that skipped while not docked (Back from the player: the stage
    // laid out before the mini player started) left the video at the slot it had before, over the stage's status line.
    val bounds = remember { androidx.compose.runtime.mutableStateOf<SportsLiveMiniSlot?>(null) }
    androidx.compose.runtime.LaunchedEffect(docked, bounds.value) {
        sportsLiveMiniSlotToReport(docked, bounds.value)?.let { SportsLiveMiniPlacement.moveTo(token, it) }
    }
    return onGloballyPositioned { coordinates ->
        val box = coordinates.boundsInWindow()
        bounds.value = SportsLiveMiniSlot(box.left.toInt(), box.top.toInt(), box.width.toInt(), box.height.toInt())
    }
}

/** #486: the slot the stage's media box hands the mini player: its last known place, only while the mini player docks there. */
internal fun sportsLiveMiniSlotToReport(docked: Boolean, bounds: SportsLiveMiniSlot?): SportsLiveMiniSlot? = bounds?.takeIf { docked }

/**
 * The 360×203 media box: preview, mini player or drawn art, never another size. [agendaArt]: Upcoming's
 * drawn matchup (league and season on top, records under the teams, "at" or "vs" between).
 */
@Composable
internal fun StageMedia(
    row: ScoreboardRow,
    preview: (@Composable () -> Unit)?,
    previewShowing: Boolean,
    miniInStage: Boolean,
    agendaArt: Boolean = false
) {
    val shape = SportsLiveTokens.Radius.RowShape
    Box(
        modifier = Modifier
            .width(SportsLiveStageLayout.WIDTH_DP.dp)
            .height(SportsLiveStageLayout.MEDIA_HEIGHT_DP.dp)
            .sportsLiveMiniSlot(miniInStage)
            .clip(shape)
            .background(Colors.Surface)
    ) {
        // The video is laid out while the stream loads, so its first frame can arrive; the art covers it until then.
        if (!miniInStage) preview?.invoke()
        if (!previewShowing || miniInStage) {
            // F7 / live.20 item 5: a game or event never shows the addon's art (its name painted in, letters in a
            // circle, an oversized crop); our drawn matchup or event card does. A channel keeps its own logo.
            when (stageArtKind(row)) {
                StageArtKind.MATCHUP -> row.art?.let { art -> if (agendaArt) AgendaStageArt(art, row.item) else StageArt(art, row) }
                StageArtKind.CHANNEL_ART -> ChannelArt(row)
                StageArtKind.CHANNEL_NAME -> SportsLiveChannelNameArt(row.item)
                StageArtKind.EVENT -> SportsLiveEventStageArt(row)
            }
        }
        if (previewShowing && !miniInStage) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(SportsLiveTokens.Radius.Badge))
                    .background(Colors.Background.copy(alpha = 0.7f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(imageVector = Icons.AutoMirrored.Rounded.VolumeOff, contentDescription = null, tint = Colors.TextPrimary, modifier = Modifier.size(12.dp))
                Text(
                    text = stringResource(R.string.sports_live_preview_muted),
                    style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag),
                    color = Colors.TextPrimary,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Upcoming's drawn matchup (V2 P3) at the media box's size: the league and season on top, the two teams
 * with big logos, their names and ESPN records under them, "at" or "vs" between; without both logos the
 * names are set large, stacked (never letters).
 */
@Composable
internal fun AgendaStageArt(art: GameArt, item: com.nuvio.tv.domain.model.SportsLiveItem) {
    val league = sportsLiveLeagueLabel(art.league, item.catalogName)
    val season = art.seasonType?.let { seasonLabelRes(it) }?.let { stringResource(it) }
    val top = listOfNotNull(league, season).joinToString(" · ").uppercase(Locale.getDefault())
    MatchupStageArt(art, top, at = GameIdentities.saysAt(item, art, SportsLiveSportKind.tagOf(item)))
}

/**
 * The drawn matchup at the media box's size (V2): [top] (league · season) over the two teams with big logos,
 * their names and records, "at" ([at]) or "vs" between; without both logos the names are set large, stacked.
 * Upcoming, Replays and Results draw it; it never shows a score.
 */
@Composable
internal fun MatchupStageArt(art: GameArt, top: String, at: Boolean) {
    val logos = rememberTeamLogoPair(art, TeamLogoUrls.LARGE_PX)?.takeIf { !it.failed }
    val word = stringResource(if (at) R.string.sports_live_art_at else R.string.sports_live_art_vs)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Colors.SurfaceRaised, Colors.Surface)))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
    ) {
        if (top.isNotEmpty()) {
            Text(
                text = top,
                style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag).copy(letterSpacing = 0.08.em),
                color = Colors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (logos != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AgendaArtTeam(art.first, Modifier.weight(1f)) { TeamLogoSlot(logos.first, logos.shown, STAGE_LOGO_SIZE) }
                ArtCaption(word, bold = false)
                AgendaArtTeam(art.second, Modifier.weight(1f)) { TeamLogoSlot(logos.second, logos.shown, STAGE_LOGO_SIZE) }
            }
        } else {
            StackedNameWithRecord(art.first)
            ArtCaption(word, bold = false)
            StackedNameWithRecord(art.second)
        }
    }
}

/** One team of [AgendaStageArt]: its logo, its short name in bold and its record under it. */
@Composable
private fun AgendaArtTeam(team: GameArtTeam, modifier: Modifier, logo: @Composable () -> Unit) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        logo()
        Text(
            text = team.shortName,
            style = SportsLiveTokens.style(SportsLiveTokens.Type.RowName).sportsLiveBold(),
            color = Colors.TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        team.record?.let { record ->
            Text(
                text = sportsLiveRecordText(record),
                style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta).copy(fontFeatureSettings = "tnum"),
                color = Colors.TextSecondary,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun StackedNameWithRecord(team: GameArtTeam) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        Text(
            text = team.name,
            style = SportsLiveTokens.style(SportsLiveTokens.Type.RowName).copy(fontSize = 22.sp, lineHeight = 26.sp).sportsLiveBold(),
            color = Colors.TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        team.record?.let { record ->
            Text(text = sportsLiveRecordText(record), style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta), color = Colors.TextSecondary, maxLines = 1)
        }
    }
}

/** A record as the board writes it: "18–9", "12–8–14" (en dashes, not ESPN's hyphens). */
internal fun sportsLiveRecordText(record: String): String = record.trim().replace('-', '\u2013')

/** ESPN's season type as words: 1 preseason, 2 regular season, 3 postseason; null for any other. */
internal fun seasonLabelRes(type: Int): Int? = when (type) {
    1 -> R.string.sports_live_season_pre
    2 -> R.string.sports_live_season_regular
    3 -> R.string.sports_live_season_post
    else -> null
}

/** The title writes the game "A at B" or "A @ B" (away first): the stage says "at", else "vs". */
internal fun agendaWrittenAt(title: String): Boolean = title.contains(" at ", ignoreCase = true) || title.contains(" @ ")

/** Whether [row]'s matchup reads "A at B": the game grammar's connector ([GameIdentities.saysAt]). */
internal fun agendaSaysAt(row: ScoreboardRow): Boolean =
    GameIdentities.saysAt(row.item, row.art, row.sportTag ?: SportsLiveSportKind.tagOf(row.item))

/**
 * What the drawn matchup says under "vs" (Live Now's stage before its preview plays): [row]'s status in the one
 * status format ([scoreboardStatusLines], from [GameStatus]): a live game's [score] (the Scores setting and
 * spoilers applied) and its clock and period, a final's word, else the kickoff and countdown. Never the start
 * time of a game in play.
 */
internal data class StageArtStatus(val score: ScoreboardScores, val status: String?, val live: Boolean)

internal fun stageArtStatus(row: ScoreboardRow, strings: GameStatusStrings, kickoff: String?): StageArtStatus {
    val lines = scoreboardStatusLines(row, strings, kickoff)
    val status = listOfNotNull(lines.primary, lines.secondary).joinToString(" · ").ifEmpty { null }
    return StageArtStatus(scoreboardScoresOf(row.chip), status, lines.live)
}

/** The drawn matchup at the media box's size: big logos round the league and the game's status, or the names stacked when logos are missing (#130). */
@Composable
private fun StageArt(art: GameArt, row: ScoreboardRow) {
    val logos = rememberTeamLogoPair(art, TeamLogoUrls.LARGE_PX)?.takeIf { !it.failed }
    val strings = rememberGameStatusStrings()
    val kickoff = kickoffText(art)
    val status = remember(row, strings, kickoff) { stageArtStatus(row, strings, kickoff) }
    val league = sportsLiveLeagueLabel(art.league, row.item.catalogName)?.uppercase(Locale.getDefault())
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Colors.SurfaceRaised, Colors.Surface))),
        contentAlignment = Alignment.Center
    ) {
        if (logos != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TeamLogoSlot(logos.first, logos.shown, STAGE_LOGO_SIZE)
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    league?.let { ArtCaption(it, bold = true) }
                    ArtCaption(stringResource(R.string.sports_live_art_vs), bold = false)
                    StageArtStatusLines(status)
                }
                TeamLogoSlot(logos.second, logos.shown, STAGE_LOGO_SIZE)
            }
        } else {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                league?.let { ArtCaption(it, bold = true) }
                StackedName(art.first)
                ArtCaption(stringResource(R.string.sports_live_art_vs), bold = false)
                StackedName(art.second)
                StageArtStatusLines(status)
            }
        }
    }
}

/** [status] under the matchup: the score in bold (when one shows), then the red dot and clock, or the kickoff. */
@Composable
private fun StageArtStatusLines(status: StageArtStatus) {
    (status.score as? ScoreboardScores.Shown)?.let { score ->
        Text(
            text = stringResource(R.string.sports_live_chip_score, score.first, score.second),
            style = SportsLiveTokens.style(SportsLiveTokens.Type.RowName).copy(fontSize = 22.sp, lineHeight = 26.sp, fontFeatureSettings = "tnum").sportsLiveBold(),
            color = Colors.TextPrimary,
            maxLines = 1
        )
    }
    val text = status.status ?: return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (status.live) Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Colors.LiveRed))
        Text(
            text = text,
            style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
            color = if (status.live) Colors.LiveText else Colors.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StackedName(team: GameArtTeam) {
    Text(
        text = team.name,
        style = SportsLiveTokens.style(SportsLiveTokens.Type.RowName).copy(fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
        color = Colors.TextPrimary,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun ArtCaption(text: String, bold: Boolean) {
    Text(
        text = text,
        style = SportsLiveTokens.style(if (bold) SportsLiveTokens.Type.Tag else SportsLiveTokens.Type.Meta),
        color = Colors.TextMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * A game's score block: the scoreline ("BOS 91–88 NYK", 32 sp bold), else the matchup; the status line
 * ("● 4:12 · Q4 · NBA" and the situation); the linescore at 13 sp.
 */
@Composable
private fun GameBlock(row: ScoreboardRow, details: ScoreDetails?, situation: String, noStream: Boolean, facts: StageFacts) {
    val detailStrings = rememberScoreDetailsStrings()
    val strings = rememberGameStatusStrings()
    val art = row.art
    val shown = details as? ScoreDetails.Shown
    val headline = when {
        shown != null -> AnnotatedString(ScoreDetailsBuilder.scorelineText(shown, detailStrings))
        // The game grammar's connector: "Hornets at Hawks" only when the first side is the away side. A ranked side's small
        // muted "#22" goes before its name here (l24 polish P5), so the ranks line below never repeats the matchup.
        art != null && row.twoSided -> stageMatchup(
            stringResource(if (agendaSaysAt(row)) R.string.sports_live_matchup_at else R.string.sports_live_matchup),
            rankedName(art.first.shortName, art.first.rank, SportsLiveTokens.Type.Meta.fontSize),
            rankedName(art.second.shortName, art.second.rank, SportsLiveTokens.Type.Meta.fontSize)
        )
        else -> AnnotatedString(singleLineTitle(row.item))
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(
            text = headline,
            // Real bold (the app's variable fonts ignore FontWeight alone).
            style = SportsLiveTokens.style(SportsLiveTokens.Type.StageScore).sportsLiveBold().copy(color = Colors.TextPrimary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = SportsLiveTokens.Type.StageScore.fontSize, stepSize = 1.sp)
        )
        val lines = scoreboardStatusLines(row, strings, art?.let { kickoffText(it) })
        val status = listOfNotNull(lines.primary, lines.secondary).joinToString(" · ")
        val league = sportsLiveLeagueLabel(art?.league ?: row.item.sport, row.item.catalogName)
        val noStreamText = stringResource(R.string.sports_live_no_streams_now)
        val statusLine = buildAnnotatedString {
            withStyle(SpanStyle(color = if (lines.live) Colors.LiveText else Colors.TextSecondary)) {
                if (lines.live) append("● ")
                append(status)
                if (league != null) append(if (status.isEmpty()) league else " · $league")
            }
            if (situation.isNotEmpty() && lines.live) {
                withStyle(SpanStyle(color = Colors.TextSecondary)) { append(" · $situation") }
            }
            // Muted (coordinator, live.20), as "No stream yet": a calm note, never the loudest word on the stage.
            if (noStream) {
                withStyle(SpanStyle(color = Colors.TextMuted)) { append(" · $noStreamText") }
            }
        }
        Text(
            text = statusLine,
            style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        SportsLiveStageFactsLine(facts)
        // Ranks and the round (#137, #160): "#1 Sinner vs #8 Alcaraz · Semifinal", "#3 UConn vs #12 Arizona". Under a
        // scoreline only: a matchup headline already carries the ranks (l24 polish P5), so only the round is left.
        (if (shown != null) stageRanksLine(row) else row.tennisRound)?.let { line ->
            Text(
                text = line,
                style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
                color = Colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // The compact linescore (live.20): the last periods on one line ("3rd 25–20 · 4th 9–11"), never clipped; the full
        // table stays on the Game page.
        shown?.let { stageLinescoreText(it, detailStrings) }?.let { line ->
            Text(
                text = line,
                style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta).copy(fontFeatureSettings = "tnum"),
                color = Colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * The stage's matchup headline (l24 polish P5): [template] ("%1$s vs %2$s", as the string resource is written, in any
 * order) with [first] and [second] in place, each with its rank's style ([rankedName]).
 */
internal fun stageMatchup(template: String, first: AnnotatedString, second: AnnotatedString): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (match in MATCHUP_PLACEHOLDER.findAll(template)) {
        append(template.substring(last, match.range.first))
        append(if (match.groupValues[1] == "1") first else second)
        last = match.range.last + 1
    }
    append(template.substring(last))
}

private val MATCHUP_PLACEHOLDER = Regex("""%([12])\${'$'}s""")

/**
 * The stage's ranks line (#137, #160): each ranked side as "#1 Sinner" (unranked: the name alone), joined by "vs",
 * then a tennis round; null when no side is ranked and there is no round.
 */
@Composable
internal fun stageRanksLine(row: ScoreboardRow): String? {
    val art = row.art?.takeIf { row.twoSided }
    val ranked = art != null && (art.first.rank != null || art.second.rank != null)
    val ranks = if (ranked) {
        fun side(team: com.nuvio.tv.core.sportslive.scores.GameArtTeam) =
            listOfNotNull(team.rank?.let(RankDisplay::label), team.shortName).joinToString(" ")
        stringResource(if (agendaSaysAt(row)) R.string.sports_live_matchup_at else R.string.sports_live_matchup, side(art!!.first), side(art.second))
    } else {
        null
    }
    return listOfNotNull(ranks, row.tennisRound).joinToString(" · ").ifEmpty { null }
}

/**
 * A channel's block: its clean name and quality (the gold ★ for a favorite), its country line ("United States ·
 * 3 sources", [channelCountryLine]), then "Now: …" and "Next: …" when known ([channelStageLines]).
 * It takes only the height its lines need, so the buttons follow right under it.
 */
@Composable
private fun ChannelBlock(row: ScoreboardRow, noStream: Boolean) {
    val title = SportsLiveDisplayText.of(row.item.meta.name)
    val lines = channelStageLines(now = channelNowText(row.item), next = null)
    val otherRegion = stringResource(R.string.sports_live_region_other)
    val sources = row.item.sourceCount
    val sourcesText = pluralStringResource(R.plurals.sports_live_sources_count, sources, sources)
    val countryLine = remember(row.item, otherRegion, sourcesText) {
        channelCountryLine(SportsLiveChannelCountries.countryOf(row.item), sources, { channelRegionLabel(it, otherRegion) }, { sourcesText })
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                text = title.name,
                style = SportsLiveTokens.style(SportsLiveTokens.Type.StageScore).sportsLiveBold().copy(color = Colors.TextPrimary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
                autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = SportsLiveTokens.Type.StageScore.fontSize, stepSize = 1.sp)
            )
            title.quality?.let { SportsLiveBadge(it) }
            if (row.favorite) Icon(imageVector = Icons.Rounded.Star, contentDescription = null, tint = Colors.FollowedGold, modifier = Modifier.size(18.dp))
        }
        countryLine?.let {
            Text(text = it, style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta), color = Colors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        lines.forEach { line ->
            Text(
                text = when (line) {
                    is ChannelStageLine.Now -> stringResource(R.string.sports_live_channel_now, line.text)
                    is ChannelStageLine.Next -> stringResource(R.string.sports_live_zap_next, line.text)
                },
                style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
                color = if (line is ChannelStageLine.Now) Colors.TextSecondary else Colors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (noStream) {
            Text(
                text = stringResource(R.string.sports_live_no_streams_now),
                style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
                color = Colors.TextMuted,
                maxLines = 1
            )
        }
    }
}

/** A line under a channel's name on the stage. */
internal sealed interface ChannelStageLine {
    data class Now(val text: String) : ChannelStageLine
    data class Next(val text: String) : ChannelStageLine
}

/**
 * The stage's country line for a channel (channels-redesign.md, the stage): its country in words ([countryLabel] of
 * [country], none for no country listed) and, when it is listed by more than one addon, how many ([sourcesText] of
 * [sources]): "United States · 3 sources", "Canada", "2 sources"; null when neither is known (no empty line).
 */
internal fun channelCountryLine(country: String, sources: Int, countryLabel: (String) -> String, sourcesText: (Int) -> String): String? =
    listOfNotNull(
        country.takeIf { it != SportsLiveChannelRegions.OTHER }?.let(countryLabel)?.takeIf { it.isNotBlank() },
        sources.takeIf { it > 1 }?.let(sourcesText)
    ).joinToString(" · ").ifEmpty { null }

/** The lines under a channel's name (V2 P3): "Now: …" and "Next: …", each only when known; none leaves no gap. */
internal fun channelStageLines(now: String?, next: String?): List<ChannelStageLine> = listOfNotNull(
    now?.takeIf { it.isNotBlank() }?.let { ChannelStageLine.Now(it) },
    next?.takeIf { it.isNotBlank() }?.let { ChannelStageLine.Next(it) }
)

/** A channel's art in the media box: its logo large on the stage's gradient (or its backdrop, filling the box). */
@Composable
private fun ChannelArt(row: ScoreboardRow) {
    val meta = row.item.meta
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Colors.SurfaceRaised, Colors.Surface))),
        contentAlignment = Alignment.Center
    ) {
        val logo = meta.logo ?: meta.poster
        val image = logo ?: meta.backdropUrl
        if (image != null) {
            AsyncImage(
                model = rememberSizedImage(image, SportsLiveStageLayout.WIDTH_DP.dp, SportsLiveStageLayout.MEDIA_HEIGHT_DP.dp),
                contentDescription = null,
                contentScale = if (logo != null) ContentScale.Fit else ContentScale.Crop,
                modifier = if (logo != null) Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp) else Modifier.fillMaxSize()
            )
        }
    }
}

private val STAGE_LOGO_SIZE = 60.dp
private val WATCH_MIN_WIDTH = 110.dp
private val MORE_MIN_WIDTH = 84.dp
