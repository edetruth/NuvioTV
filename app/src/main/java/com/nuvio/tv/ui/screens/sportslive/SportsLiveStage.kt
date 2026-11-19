package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.nuvio.tv.core.sportslive.displayName
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.GameArtTeam
import com.nuvio.tv.core.sportslive.scores.ScoreDetails
import com.nuvio.tv.core.sportslive.scores.ScoreDetailsBuilder
import com.nuvio.tv.core.sportslive.scores.TeamLogoUrls
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniPlacement
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniSlot
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveMiniSpot
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveBadge
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButton
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButtonRole
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors
import java.util.Locale

/**
 * Live Now's stage (V2, #132): fixed at 360 dp, the same size for every row, so nothing re-flows as focus
 * moves (F4). The media box (360×203) holds the muted preview, else the drawn matchup at the same size,
 * and the mini player when one plays ([miniInStage]: it takes this slot instead of floating over the
 * screen). Under it the score block (the 32 sp scoreline, the status line, the linescore) or, for a
 * channel, its clean name and "Now: …"; then one row of two buttons: **Watch** and **More…**.
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
    moreModifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(SportsLiveStageLayout.WIDTH_DP.dp)
            .height(SportsLiveStageLayout.HEIGHT_DP.dp),
        verticalArrangement = Arrangement.spacedBy(SportsLiveStageLayout.GAP_DP.dp)
    ) {
        StageMedia(row, preview, previewShowing, miniInStage)
        if (row.group == ScoreboardGroup.CHANNELS) {
            // A channel's text block takes its own height, so the buttons sit right under it (no empty block).
            ChannelBlock(row, noStream)
        } else {
            Box(modifier = Modifier.fillMaxWidth().height(SportsLiveStageLayout.SCORE_BLOCK_HEIGHT_DP.dp)) {
                GameBlock(row, details, situation, noStream)
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
    val token = remember { Any() }
    // The mini player takes this slot while it plays; the corner again once the stage leaves.
    DisposableEffect(miniInStage) {
        if (miniInStage) SportsLiveMiniPlacement.claim(token, SportsLiveMiniSpot.STAGE)
        onDispose { SportsLiveMiniPlacement.release(token) }
    }
    val shape = SportsLiveTokens.Radius.RowShape
    Box(
        modifier = Modifier
            .width(SportsLiveStageLayout.WIDTH_DP.dp)
            .height(SportsLiveStageLayout.MEDIA_HEIGHT_DP.dp)
            .onGloballyPositioned { coordinates ->
                if (!miniInStage) return@onGloballyPositioned
                val bounds = coordinates.boundsInWindow()
                SportsLiveMiniPlacement.moveTo(
                    token,
                    SportsLiveMiniSlot(bounds.left.toInt(), bounds.top.toInt(), bounds.width.toInt(), bounds.height.toInt())
                )
            }
            .clip(shape)
            .background(Colors.Surface)
    ) {
        // The video is laid out while the stream loads, so its first frame can arrive; the art covers it until then.
        if (!miniInStage) preview?.invoke()
        if (!previewShowing || miniInStage) {
            val art = row.art?.takeIf { row.twoSided }
            when {
                art != null && agendaArt -> AgendaStageArt(art, row.item)
                art != null -> StageArt(art, row.item.catalogName)
                row.group == ScoreboardGroup.CHANNELS -> ChannelArt(row)
                else -> {
                    val image = row.item.meta.backdropUrl ?: row.item.meta.logo ?: row.item.meta.poster
                    if (image != null) {
                        AsyncImage(
                            model = rememberSizedImage(image, SportsLiveStageLayout.WIDTH_DP.dp, SportsLiveStageLayout.MEDIA_HEIGHT_DP.dp),
                            contentDescription = null,
                            contentScale = if (row.item.meta.backdropUrl != null) ContentScale.Crop else ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
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
private fun AgendaStageArt(art: GameArt, item: com.nuvio.tv.domain.model.SportsLiveItem) {
    val logos = rememberTeamLogoPair(art, TeamLogoUrls.LARGE_PX)?.takeIf { !it.failed }
    val league = sportsLiveLeagueLabel(art.league, item.catalogName)
    val season = art.seasonType?.let { seasonLabelRes(it) }?.let { stringResource(it) }
    val top = listOfNotNull(league, season).joinToString(" · ").uppercase(Locale.getDefault())
    val word = stringResource(if (agendaWrittenAt(item.meta.name)) R.string.sports_live_art_at else R.string.sports_live_art_vs)
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

/** The drawn matchup at the media box's size: big logos round the league and kickoff, or the names stacked when logos are missing (#130). */
@Composable
private fun StageArt(art: GameArt, catalogName: String) {
    val logos = rememberTeamLogoPair(art, TeamLogoUrls.LARGE_PX)?.takeIf { !it.failed }
    val kickoff = kickoffText(art)
    val league = sportsLiveLeagueLabel(art.league, catalogName)?.uppercase(Locale.getDefault())
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
                    kickoff?.let { ArtCaption(it, bold = false) }
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
                kickoff?.let { ArtCaption(it, bold = false) }
            }
        }
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
private fun GameBlock(row: ScoreboardRow, details: ScoreDetails?, situation: String, noStream: Boolean) {
    val detailStrings = rememberScoreDetailsStrings()
    val strings = rememberGameStatusStrings()
    val art = row.art
    val shown = details as? ScoreDetails.Shown
    val headline = when {
        shown != null -> ScoreDetailsBuilder.scorelineText(shown, detailStrings)
        art != null && row.twoSided -> stringResource(R.string.sports_live_matchup, art.first.shortName, art.second.shortName)
        else -> singleLineTitle(row.item)
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(
            text = headline,
            // Real bold (the app's variable fonts ignore FontWeight alone).
            style = SportsLiveTokens.style(SportsLiveTokens.Type.StageScore).sportsLiveBold().copy(color = Colors.TextPrimary),
            maxLines = 1,
            softWrap = false,
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
            if (noStream) {
                withStyle(SpanStyle(color = Colors.TextPrimary)) { append(" · $noStreamText") }
            }
        }
        Text(
            text = statusLine,
            style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (shown != null && shown.periods.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(SportsLiveTokens.Radius.Button))
                    .background(Colors.Surface)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                LinescoreTable(shown, detailStrings, style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta))
            }
        }
    }
}

/**
 * A channel's block: its clean name and quality, then "Now: …" and "Next: …" when known ([channelStageLines]).
 * It takes only the height its lines need, so the buttons follow right under it.
 */
@Composable
private fun ChannelBlock(row: ScoreboardRow, noStream: Boolean) {
    val title = SportsLiveDisplayText.of(row.item.meta.name)
    val lines = channelStageLines(now = channelNowText(row.item), next = null)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                text = title.name,
                style = SportsLiveTokens.style(SportsLiveTokens.Type.StageScore).sportsLiveBold().copy(color = Colors.TextPrimary),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.weight(1f, fill = false),
                autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = SportsLiveTokens.Type.StageScore.fontSize, stepSize = 1.sp)
            )
            title.quality?.let { SportsLiveBadge(it) }
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
                color = Colors.TextPrimary,
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
