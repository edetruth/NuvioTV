package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.ui.screens.sportslive.SportsLiveDrawnGameCard
import com.nuvio.tv.ui.screens.sportslive.rememberScoreChipStrings

/**
 * Home's drawn Live now cards (V2 phase 10), by card id ([com.nuvio.tv.ui.components.LocalDrawnCardArt]): the
 * section's game card with the league, both logos, the red dot and clock, and the score (Show), "Score hidden"
 * (Hide until focused) or nothing more (Off); a gold ★ for a followed team. Channels and items naming no two sides
 * keep their own art (null).
 */
@Composable
internal fun rememberLiveNowCardArt(
    items: List<SportsLiveItem>,
    scores: Map<String, ScoreSnapshot>,
    mode: ScoresMode?
): (String) -> (@Composable () -> Unit)? {
    val strings = rememberScoreChipStrings()
    val liveWord = stringResource(R.string.sports_live_team_live)
    return remember(items, scores, mode, strings, liveWord) {
        val cards = HashMap<String, @Composable () -> Unit>()
        for (item in items) {
            if (item.id in cards) continue
            val snapshot = scores[item.streamKey]
            val art = GameArt.of(item, snapshot) ?: continue
            // Home has no focus reveal: Hide until focused shows "Score hidden" on every game.
            val chip = ScoreChips.of(item, snapshot, mode, revealed = false)
            val footer = SportsLiveHomeMeta.cardFooter(chip, mode, strings, liveWord)
            cards[item.id] = { SportsLiveDrawnGameCard(art = art, followed = item.followed, footer = footer, modifier = Modifier.fillMaxSize()) }
        }
        val lookup: (String) -> (@Composable () -> Unit)? = { id -> cards[id] }
        lookup
    }
}

/** Two drawn-art lookups as one: [first]'s card wins. */
internal fun drawnArtOf(
    first: (String) -> (@Composable () -> Unit)?,
    second: (String) -> (@Composable () -> Unit)?
): (String) -> (@Composable () -> Unit)? = { id -> first(id) ?: second(id) }

/** Live now's See all line on Home (V2 phase 10): "32 live in Sports & Live"; null shows See all alone. */
internal val LocalSportsLiveSeeAll = compositionLocalOf<String?> { null }
