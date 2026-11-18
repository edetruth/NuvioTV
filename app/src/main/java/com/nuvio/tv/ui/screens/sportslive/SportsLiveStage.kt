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
        Box(modifier = Modifier.fillMaxWidth().height(SportsLiveStageLayout.SCORE_BLOCK_HEIGHT_DP.dp)) {
            if (row.group == ScoreboardGroup.CHANNELS) ChannelBlock(row) else GameBlock(row, details, situation, noStream)
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

/** The 360×203 media box: preview, mini player or drawn art, never another size. */
@Composable
private fun StageMedia(
    row: ScoreboardRow,
    preview: (@Composable () -> Unit)?,
    previewShowing: Boolean,
    miniInStage: Boolean
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
            val art = row.art
            when {
                art != null -> StageArt(art, row.item.catalogName)
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
        art != null -> stringResource(R.string.sports_live_matchup, art.first.shortName, art.second.shortName)
        else -> row.item.displayName
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(
            text = headline,
            style = SportsLiveTokens.style(SportsLiveTokens.Type.StageScore).copy(color = Colors.TextPrimary),
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

/** A channel's block: its logo-free clean name and quality, and "Now: …" when known. Nothing else. */
@Composable
private fun ChannelBlock(row: ScoreboardRow) {
    val title = SportsLiveDisplayText.of(row.item.meta.name)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                text = title.name,
                style = SportsLiveTokens.style(SportsLiveTokens.Type.StageScore).copy(color = Colors.TextPrimary),
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.weight(1f, fill = false),
                autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = SportsLiveTokens.Type.StageScore.fontSize, stepSize = 1.sp)
            )
            title.quality?.let { SportsLiveBadge(it) }
        }
        channelNowText(row.item)?.let { now ->
            Text(
                text = stringResource(R.string.sports_live_channel_now, now),
                style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
                color = Colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private val STAGE_LOGO_SIZE = 60.dp
private val WATCH_MIN_WIDTH = 110.dp
private val MORE_MIN_WIDTH = 84.dp
