package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.GameArtTeam
import com.nuvio.tv.core.sportslive.scores.TeamLogoUrls
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors
import java.util.Locale

/**
 * The bottom line of a drawn game card (V2 phase 10, #155): [lead] at the left (the clock, "Final · Sun 15 Nov",
 * "Today 7:30 PM"), led by the red dot while [live], then the muted [detail] (the period); [trailing] at the right.
 */
internal data class DrawnCardFooter(
    val lead: String,
    val detail: String? = null,
    val live: Boolean = false,
    val trailing: DrawnCardTrailing? = null
)

/** The right end of a drawn card's bottom line. */
internal sealed interface DrawnCardTrailing {
    /** The score ("91–88"), bold. */
    data class Score(val text: String) : DrawnCardTrailing

    /** Muted words ("Score hidden"). */
    data class Note(val text: String) : DrawnCardTrailing

    /** The green Replay pill: never a score. */
    data object Replay : DrawnCardTrailing

    /** The reminder bell: outline when off, blue when set. */
    data class Bell(val on: Boolean) : DrawnCardTrailing
}

/**
 * The section's own game card face (V2 phase 10): the league tag, a gold ★ for a followed team, both logos (or the
 * short names, #130) around "vs", and [footer]; "▶ Ready" at the top right while [ready] (live.20 1b). It fills its
 * box: Home draws it at its 16:9 card size. The frame, focus and click belong to the caller.
 */
@Composable
internal fun SportsLiveDrawnGameCard(art: GameArt, followed: Boolean, footer: DrawnCardFooter, modifier: Modifier = Modifier, ready: Boolean = false) {
    DrawnCardFrame(league = art.league, followed = followed, footer = footer, ready = ready, modifier = modifier) { logoSize, pad ->
        DrawnMatchup(art, logoSize, Modifier.align(Alignment.Center).padding(horizontal = pad))
    }
}

/**
 * The neutral drawn card (live.19 F3, #182) for a game that names no two sides ("Moto3 · Round 21 · FP1"): the
 * league tag, the title in two lines at most, and [footer]. Never the addon's art, which may show only a channel's
 * logo or a final score.
 */
@Composable
internal fun SportsLiveDrawnEventCard(
    title: String,
    league: String?,
    followed: Boolean,
    footer: DrawnCardFooter,
    modifier: Modifier = Modifier,
    ready: Boolean = false
) {
    DrawnCardFrame(league = league, followed = followed, footer = footer, ready = ready, modifier = modifier) { logoSize, pad ->
        Text(
            text = title,
            style = SportsLiveTokens.style(SportsLiveTokens.Type.RowName).copy(fontWeight = FontWeight.Bold),
            color = Colors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = pad + logoSize / 4)
        )
    }
}

/** A drawn card's frame: the gradient, the league tag, ▶ Ready and the ★ at the top, [footer] at the bottom. */
@Composable
private fun DrawnCardFrame(
    league: String?,
    followed: Boolean,
    footer: DrawnCardFooter,
    ready: Boolean,
    modifier: Modifier,
    middle: @Composable BoxScope.(logoSize: Dp, pad: Dp) -> Unit
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Colors.SurfaceRaised, Colors.Surface)))
    ) {
        // Every size from the card's height, so Home's card and the strip's read the same.
        val pad = (maxHeight * 0.08f).coerceIn(6.dp, 14.dp)
        val logoSize = (maxHeight * 0.32f).coerceIn(22.dp, 56.dp)
        val big = maxHeight >= BIG_CARD_HEIGHT
        val tagStyle = SportsLiveTokens.style(SportsLiveTokens.Type.Tag).let { if (big) it.copy(fontSize = 13.sp, lineHeight = 16.sp) else it }
        val leadStyle = SportsLiveTokens.style(SportsLiveTokens.Type.Meta).let { if (big) it.copy(fontSize = 16.sp, lineHeight = 20.sp) else it }
        val scoreStyle = SportsLiveTokens.style(SportsLiveTokens.Type.RowScore).let { if (big) it.copy(fontSize = 17.sp, lineHeight = 20.sp) else it.copy(fontSize = 15.sp, lineHeight = 18.sp) }
        league?.takeIf { it.isNotBlank() }?.let { text ->
            Text(
                // The one shared tag vocabulary (live.20), as Live Now's rows and the panel.
                text = com.nuvio.tv.core.sportslive.SportsLiveTags.short(text.uppercase(Locale.getDefault()), null),
                style = tagStyle,
                color = Colors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Room for ▶ Ready and the ★ at the right.
                modifier = Modifier.align(Alignment.TopStart).padding(start = pad, top = pad, end = pad + logoSize / 2 + if (ready) READY_ROOM else 0.dp)
            )
        }
        if (followed || ready) {
            Row(
                modifier = Modifier.align(Alignment.TopEnd).padding(top = pad, end = pad),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (ready) SportsLiveReadyPill()
                if (followed) {
                    Icon(
                        imageVector = Icons.Rounded.Star,
                        contentDescription = stringResource(R.string.sports_live_your_team),
                        tint = Colors.FollowedGold,
                        modifier = Modifier.size(if (big) 18.dp else 14.dp)
                    )
                }
            }
        }
        middle(logoSize, pad)
        Row(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = pad, vertical = pad * 0.8f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (footer.live) {
                Box(modifier = Modifier.size(if (big) 8.dp else 6.dp).clip(CircleShape).background(Colors.LiveRed))
            }
            Text(
                text = footer.lead,
                style = leadStyle.copy(fontWeight = if (footer.live) FontWeight.Bold else FontWeight.Medium, fontFeatureSettings = "tnum"),
                color = if (footer.live) Colors.TextPrimary else Colors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = footer.detail == null)
            )
            footer.detail?.let { detail ->
                Text(
                    text = detail,
                    style = leadStyle,
                    color = Colors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            when (val trailing = footer.trailing) {
                is DrawnCardTrailing.Score -> Text(text = trailing.text, style = scoreStyle, color = Colors.TextPrimary, maxLines = 1, softWrap = false)
                is DrawnCardTrailing.Note -> Text(text = trailing.text, style = leadStyle, color = Colors.TextMuted, maxLines = 1, softWrap = false)
                DrawnCardTrailing.Replay -> DrawnReplayPill(leadStyle.fontSize.value)
                is DrawnCardTrailing.Bell -> AgendaBellIcon(
                    if (trailing.on) AgendaBell.ON else AgendaBell.OFF,
                    Modifier.size(if (big) 20.dp else 16.dp),
                    offTint = Colors.TextSecondary
                )
                null -> Unit
            }
        }
    }
}

@Composable
private fun DrawnMatchup(art: GameArt, logoSize: androidx.compose.ui.unit.Dp, modifier: Modifier) {
    val logos = rememberTeamLogoPair(art, TeamLogoUrls.CARD_PX)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(logoSize * 0.6f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (logos != null && !logos.failed) {
            TeamLogoSlot(logos.first, logos.shown, logoSize)
            VsText()
            TeamLogoSlot(logos.second, logos.shown, logoSize)
        } else {
            // No logos (#130): the short names, never letters in a circle.
            DrawnName(art.first, Modifier.weight(1f, fill = false))
            VsText()
            DrawnName(art.second, Modifier.weight(1f, fill = false))
        }
    }
}

@Composable
private fun VsText() {
    Text(text = stringResource(R.string.sports_live_art_vs), style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta), color = Colors.TextMuted, maxLines = 1)
}

@Composable
private fun DrawnName(team: GameArtTeam, modifier: Modifier) {
    Text(
        text = team.shortName,
        style = SportsLiveTokens.style(SportsLiveTokens.Type.RowName).copy(fontWeight = FontWeight.Bold),
        color = Colors.TextPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/** The Replay pill at the card's text size, in the V2 replay green. */
@Composable
private fun DrawnReplayPill(fontSize: Float) {
    Text(
        text = stringResource(R.string.sports_live_results_replay),
        style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag).copy(fontSize = fontSize.sp, lineHeight = (fontSize + 3).sp, fontWeight = FontWeight.Bold),
        color = Colors.ReplayGreenText,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clip(RoundedCornerShape(SportsLiveTokens.Radius.Badge))
            .background(Colors.ReplayGreen)
            .padding(horizontal = 6.dp, vertical = 1.dp)
    )
}

/** From this height on a drawn card uses Home's larger type. */
private val BIG_CARD_HEIGHT = 110.dp

/** The league tag stops short of "▶ Ready". */
private val READY_ROOM = 64.dp
