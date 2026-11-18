package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.SportsLivePillTime
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.GameArtTeam
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.core.sportslive.scores.ScoreChipStrings
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.TeamLogoUrls
import com.nuvio.tv.core.sportslive.scores.TeamMarks
import com.nuvio.tv.ui.theme.NuvioTheme
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** The chip's words from string resources. */
@Composable
internal fun rememberScoreChipStrings(): ScoreChipStrings {
    val score = stringResource(R.string.sports_live_chip_score)
    val live = stringResource(R.string.sports_live_chip_live)
    val final = stringResource(R.string.sports_live_chip_final)
    val finalNote = stringResource(R.string.sports_live_chip_final_note)
    val postponed = stringResource(R.string.sports_live_chip_postponed)
    val hidden = stringResource(R.string.sports_live_results_score_hidden)
    return remember(score, live, final, finalNote, postponed, hidden) {
        ScoreChipStrings(score, live, final, finalNote, postponed, hidden)
    }
}

/** A card's score chip: a red dot while live, then the clock and score ("Q3 4:12 · 88–80", "FT 3–1"). */
@Composable
internal fun SportsLiveScoreChip(chip: ScoreChip, modifier: Modifier = Modifier) {
    val (lead, score) = scoreChipParts(chip, rememberScoreChipStrings())
    val live = chip is ScoreChip.Live
    val color = when {
        live -> ArtColors.LiveText
        chip is ScoreChip.Status -> ArtColors.Muted
        else -> ArtColors.Secondary
    }
    val style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(NuvioTheme.radii.sm))
            .background(if (live) ArtColors.LiveBlock else ArtColors.FinalBlock)
            .padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (live) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(ArtColors.LiveRed))
        }
        // Short of room, the clock or note gives way; the score never does.
        if (lead.isNotEmpty()) {
            Text(
                text = lead,
                style = style,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
        if (score != null) {
            Text(text = score, style = style, color = color, maxLines = 1, softWrap = false)
        }
    }
}

/**
 * The chip's words split for layout: the lead (clock or note, with its separator) and the score, or
 * null when the chip shows none ("Postponed", "Score hidden", a clock alone). The lead may be empty.
 */
internal fun scoreChipParts(chip: ScoreChip, strings: ScoreChipStrings): Pair<String, String?> {
    val text = ScoreChips.text(chip, strings)
    val score = when (chip) {
        is ScoreChip.Live -> chip.score
        is ScoreChip.Final -> chip.score
        else -> null
    }?.let { String.format(strings.score, it.first, it.second) }
    if (score == null || !text.endsWith(score)) return text to null
    // The trailing space goes (the row spaces the two); a separator ("·") stays with the lead.
    return text.removeSuffix(score).trim() to score
}

/** The kickoff in local time: time only today, "Tomorrow <time>", weekday or date otherwise. */
@Composable
internal fun kickoffText(art: GameArt): String? {
    val tomorrow = stringResource(R.string.sports_live_tomorrow)
    // Keyed by today's date, so "Today" / "Tomorrow" move on at midnight (the lists recompose on refresh).
    val today = LocalDate.now()
    return remember(art.startMs, art.hasTimeOfDay, tomorrow, today) {
        when (val pill = GameArt.kickoff(art, System.currentTimeMillis(), ZoneId.systemDefault(), Locale.getDefault())) {
            is SportsLivePillTime.Today -> pill.time
            is SportsLivePillTime.Tomorrow -> String.format(tomorrow, pill.time)
            is SportsLivePillTime.Other -> pill.text
            null -> null
        }
    }
}

/**
 * The card Nuvio draws for a game instead of the addon's poster. [compact] is the list card's small
 * thumbnail (abbreviations beside small logos, or short names alone, then a kickoff · league footer);
 * otherwise the details pane's art (full names, large logos or large name type, league, the score chip
 * and the kickoff). Logos show only when both teams have one (#130); never letters in their place.
 */
@Composable
internal fun SportsLiveGameArt(art: GameArt, compact: Boolean, modifier: Modifier = Modifier, chip: ScoreChip? = null) {
    val kickoff = kickoffText(art)
    val logos = rememberTeamLogoPair(art, if (compact) TeamLogoUrls.CARD_PX else TeamLogoUrls.LARGE_PX)
    Box(
        modifier = modifier.background(Brush.verticalGradient(listOf(ArtColors.Panel, ArtColors.Background)))
    ) {
        if (compact) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                CompactTeam(art.first, logos, logos?.first)
                CompactTeam(art.second, logos, logos?.second)
                Spacer(modifier = Modifier.weight(1f))
                val footer = when {
                    kickoff != null && art.league != null -> stringResource(R.string.sports_live_art_footer, kickoff, art.league)
                    else -> kickoff ?: art.league
                }
                footer?.let { text ->
                    Text(
                        text = text,
                        style = TextStyle(fontSize = 10.sp, fontFeatureSettings = "tnum"),
                        color = ArtColors.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // The pane shrinks its art to the room left: below the full layout's height, the short one.
            if (maxHeight < FULL_ART_MIN_HEIGHT) {
                ShortArt(art, logos, chip, kickoff, logoSize = (maxHeight * 0.32f).coerceIn(20.dp, 44.dp))
                return@BoxWithConstraints
            }
            Column(
                modifier = Modifier.fillMaxSize().padding(NuvioTheme.spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
            ) {
                art.league?.let { league ->
                    Text(
                        text = league.uppercase(Locale.getDefault()),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = ArtColors.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LargeTeam(art.first, logos, logos?.first, Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.sports_live_art_vs),
                        style = MaterialTheme.typography.titleMedium,
                        color = ArtColors.Muted
                    )
                    LargeTeam(art.second, logos, logos?.second, Modifier.weight(1f))
                }
                chip?.let { SportsLiveScoreChip(it) }
                kickoff?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                        color = ArtColors.Secondary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * The pane's art when it is short: both logos around the league, then the chip and the kickoff, each on
 * its own line, so none is cut; the title under the art names the teams. Without logos (#130): the
 * league over the two short names, stacked.
 */
@Composable
private fun ShortArt(art: GameArt, logos: TeamLogoPair?, chip: ScoreChip?, kickoff: String?, logoSize: Dp) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = NuvioTheme.spacing.sm, vertical = NuvioTheme.spacing.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs, Alignment.CenterVertically)
    ) {
        val league = art.league?.uppercase(Locale.getDefault())
        if (logos != null && !logos.failed) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TeamLogoSlot(logos.first, logos.shown, logoSize)
                Text(
                    text = league ?: stringResource(R.string.sports_live_art_vs),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = ArtColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                TeamLogoSlot(logos.second, logos.shown, logoSize)
            }
        } else {
            league?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = ArtColors.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            StackedName(art.first)
            StackedName(art.second)
        }
        chip?.let { SportsLiveScoreChip(it) }
        kickoff?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                color = ArtColors.Secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** A team's short name on its own line: the logo-less short art stacks the two (#130). */
@Composable
private fun StackedName(team: GameArtTeam) {
    Text(
        text = team.shortName,
        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold),
        color = ArtColors.Text,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/** Below this height the pane's art takes the short layout (the full one needs its logos, names and kickoff). */
private val FULL_ART_MIN_HEIGHT = 240.dp

@Composable
private fun CompactTeam(team: GameArtTeam, logos: TeamLogoPair?, logo: Painter?) {
    val withLogo = logos != null && logo != null && !logos.failed
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (logos != null && logo != null && !logos.failed) TeamLogoSlot(logo, logos.shown, 18.dp)
        Text(
            // Without logos the short names carry the card, in larger type.
            text = if (withLogo) team.short else team.shortName,
            style = TextStyle(fontSize = if (withLogo) 13.sp else 15.sp, fontWeight = FontWeight.Bold),
            color = ArtColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LargeTeam(team: GameArtTeam, logos: TeamLogoPair?, logo: Painter?, modifier: Modifier = Modifier) {
    val withLogo = logos != null && logo != null && !logos.failed
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        if (logos != null && logo != null && !logos.failed) TeamLogoSlot(logo, logos.shown, 72.dp)
        Text(
            text = team.name,
            style = (if (withLogo) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall)
                .copy(fontWeight = FontWeight.Bold),
            color = ArtColors.Text,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * A game's two logos (#130), loading from the moment the pair is remembered: null unless both teams
 * have one ([TeamMarks.pair]). Both are drawn only once both have loaded ([shown]); one failing hides
 * both ([failed]), and the names take the room. Never letters or an empty circle in their place.
 */
internal class TeamLogoPair(val first: Painter, val second: Painter, val shown: Boolean, val failed: Boolean)

@Composable
internal fun rememberTeamLogoPair(art: GameArt?, px: Int = TeamLogoUrls.CARD_PX): TeamLogoPair? =
    rememberTeamLogoPair(art?.first?.logo, art?.second?.logo, px)

@Composable
internal fun rememberTeamLogoPair(first: String?, second: String?, px: Int = TeamLogoUrls.CARD_PX): TeamLogoPair? {
    val urls = TeamMarks.pair(first, second) ?: return null
    val a = rememberLogoPainter(urls.first, px)
    val b = rememberLogoPainter(urls.second, px)
    val stateA by a.state.collectAsState()
    val stateB by b.state.collectAsState()
    return TeamLogoPair(
        first = a,
        second = b,
        shown = TeamMarks.pairShown(stateA is AsyncImagePainter.State.Success, stateB is AsyncImagePainter.State.Success),
        failed = stateA is AsyncImagePainter.State.Error || stateB is AsyncImagePainter.State.Error
    )
}

/** One team's logo alone (the Team page): null when it has none ([TeamMarks.single]); [shown] once loaded. */
internal class TeamLogoImage(val painter: Painter, val shown: Boolean)

@Composable
internal fun rememberTeamLogo(logo: String?, px: Int = TeamLogoUrls.CARD_PX): TeamLogoImage? {
    val url = TeamMarks.single(logo) ?: return null
    val painter = rememberLogoPainter(url, px)
    val state by painter.state.collectAsState()
    return TeamLogoImage(painter, state is AsyncImagePainter.State.Success)
}

/** A team's logo, scaled by ESPN to [px] (#66) and decoded at that size (#71), never larger. */
@Composable
private fun rememberLogoPainter(url: String, px: Int): AsyncImagePainter {
    val context = LocalPlatformContext.current
    val request = remember(url, px, context) {
        ImageRequest.Builder(context).data(TeamLogoUrls.scaled(url, px)).size(px, px).build()
    }
    return rememberAsyncImagePainter(request)
}

/**
 * A logo's slot (#130): nothing drawn until [shown], never a circle or letters; then the logo fades in.
 * The slot keeps the logo's height from the start and opens sideways only, so the row it sits in never
 * jumps. [modifier] goes inside the [size] square (the Game page's backing circle).
 */
@Composable
internal fun TeamLogoSlot(painter: Painter, shown: Boolean, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier = Modifier.height(size), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible = shown, enter = LOGO_ENTER, exit = LOGO_EXIT) {
            Image(painter = painter, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(size).then(modifier))
        }
    }
}

/** A game's two logos side by side (the dual picker): nothing until both have loaded, then they fade in together. */
@Composable
internal fun TeamLogoPairSlot(logos: TeamLogoPair, size: Dp, gap: Dp) {
    Box(modifier = Modifier.height(size), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible = logos.shown, enter = LOGO_ENTER, exit = LOGO_EXIT) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                Image(painter = logos.first, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(size))
                Image(painter = logos.second, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(size))
            }
        }
    }
}

private const val LOGO_FADE_MS = 150
private val LOGO_ENTER = fadeIn(tween(LOGO_FADE_MS)) + expandHorizontally(tween(LOGO_FADE_MS))
private val LOGO_EXIT = fadeOut(tween(LOGO_FADE_MS)) + shrinkHorizontally(tween(LOGO_FADE_MS))

/** "Shared visual language" of the Sports & Live designs (dark broadcast look). */
internal object ArtColors {
    val Background = Color(0xFF0A0D12)
    val Panel = Color(0xFF121821)
    val Text = Color(0xFFF2F4F7)
    val Secondary = Color(0xFFC7CED8)
    val Muted = Color(0xFF9AA4B2)
    val LiveRed = Color(0xFFFF4D57)
    val LiveBlock = Color(0xFF2A1416)
    val LiveText = Color(0xFFFFB3B8)
    val FinalBlock = Color(0xFF151B24)
}
