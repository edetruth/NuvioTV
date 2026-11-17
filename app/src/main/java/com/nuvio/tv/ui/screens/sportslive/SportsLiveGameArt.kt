package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.SportsLivePillTime
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.GameArtTeam
import com.nuvio.tv.core.sportslive.scores.ScoreChip
import com.nuvio.tv.core.sportslive.scores.ScoreChipStrings
import com.nuvio.tv.core.sportslive.scores.ScoreChips
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
    val text = ScoreChips.text(chip, rememberScoreChipStrings())
    val live = chip is ScoreChip.Live
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
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
            color = when {
                live -> ArtColors.LiveText
                chip is ScoreChip.Status -> ArtColors.Muted
                else -> ArtColors.Secondary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
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
 * thumbnail (abbreviations, small logos, a kickoff · league footer); otherwise the details pane's art
 * (full names, large logos or large name type, league, the score chip and the kickoff).
 */
@Composable
internal fun SportsLiveGameArt(art: GameArt, compact: Boolean, modifier: Modifier = Modifier, chip: ScoreChip? = null) {
    val kickoff = kickoffText(art)
    Box(
        modifier = modifier.background(Brush.verticalGradient(listOf(ArtColors.Panel, ArtColors.Background)))
    ) {
        if (compact) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                CompactTeam(art.first, art.hasLogos)
                CompactTeam(art.second, art.hasLogos)
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
        } else {
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
                    LargeTeam(art.first, art.hasLogos, Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.sports_live_art_vs),
                        style = MaterialTheme.typography.titleMedium,
                        color = ArtColors.Muted
                    )
                    LargeTeam(art.second, art.hasLogos, Modifier.weight(1f))
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

@Composable
private fun CompactTeam(team: GameArtTeam, withLogo: Boolean) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (withLogo) {
            AsyncImage(
                model = team.logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(18.dp)
            )
        }
        Text(
            text = team.short,
            // Without logos the names carry the card, in larger type.
            style = TextStyle(fontSize = if (withLogo) 13.sp else 15.sp, fontWeight = FontWeight.Bold),
            color = ArtColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LargeTeam(team: GameArtTeam, withLogo: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        if (withLogo) {
            AsyncImage(
                model = team.logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(72.dp)
            )
        }
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

/** "Shared visual language" of the Sports & Live designs (dark broadcast look). */
private object ArtColors {
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
