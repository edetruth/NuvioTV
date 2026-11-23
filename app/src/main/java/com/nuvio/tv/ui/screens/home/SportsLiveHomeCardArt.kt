package com.nuvio.tv.ui.screens.home

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.SportsLiveReadyMark
import com.nuvio.tv.core.sportslive.displayName
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.ScoreChips
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.core.sportslive.scores.ScoreState
import com.nuvio.tv.core.sportslive.scores.ScoresMode
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.ui.screens.sportslive.SportsLiveDrawnEventCard
import com.nuvio.tv.ui.screens.sportslive.SportsLiveDrawnGameCard
import com.nuvio.tv.ui.screens.sportslive.kickoffText
import com.nuvio.tv.ui.screens.sportslive.rememberSportsLiveReadyMark
import com.nuvio.tv.ui.screens.sportslive.rememberScoreChipStrings
import com.nuvio.tv.ui.screens.sportslive.liveNowItemTag
import com.nuvio.tv.ui.screens.sportslive.sportKindLabelRes

/**
 * Home's drawn Live now cards (V2 phase 10), by card id ([com.nuvio.tv.ui.components.LocalDrawnCardArt]): the
 * section's game card with the league, both logos, the red dot and clock, and the score (Show), "Score hidden"
 * (Hide until focused) or nothing more (Off); a gold ★ for a followed team; "▶ Ready" while a verified link is held
 * (live.20 1b). A game naming no two sides gets the neutral card, its clean title in place of the addon's art (live.19
 * F3, #182: that art may show only a channel's logo, or a final score). Channels keep their own art (null).
 */
@Composable
internal fun rememberLiveNowCardArt(
    items: List<SportsLiveItem>,
    scores: State<Map<String, ScoreSnapshot>>,
    mode: ScoresMode?,
    unmatched: State<Set<String>>
): (String) -> (@Composable () -> Unit)? {
    val strings = rememberScoreChipStrings()
    val liveWord = stringResource(R.string.sports_live_team_live)
    val listedWord = stringResource(R.string.sports_live_listed_live)
    // The game playing in a player (live.26 TV): "▶ Watching", as Live Now's row says, never "No score yet".
    val watchingWord = stringResource(R.string.sports_live_row_watching)
    val startsFormat = stringResource(R.string.sports_live_waiting_starts)
    // Live Now's tag words (RC TV finding): the same league tag as its row, else the sport word, never a catalog's name.
    val context = androidx.compose.ui.platform.LocalContext.current
    val sportWord: (com.nuvio.tv.core.sportslive.SportsLiveSportKind) -> String = remember(context) {
        { kind -> context.getString(sportKindLabelRes(kind)) }
    }
    // #207: the lookup changes with the cards only, never with a score. Every card on Home reads it, so a new one on
    // each score update redrew them all; each drawn card reads its own game's score instead, and only a card whose game
    // changed is drawn again.
    return remember(items, scores, mode, strings, liveWord, unmatched, listedWord, startsFormat, sportWord, watchingWord) {
        val cards = HashMap<String, @Composable () -> Unit>()
        for (item in items) {
            if (item.id in cards || !SportsLiveHomeMeta.drawsCard(item)) continue
            val title = SportsLiveHomeMeta.cleanTitle(item.displayName)
            // live.25 #226: a race or fight card (no score feed) never gets a score, so it reads just "Live".
            val noScoreYet = listedWord.takeIf { com.nuvio.tv.core.sportslive.scores.LiveScoresRepository.hasScoreFeed(item) }
            val card: @Composable () -> Unit = {
                val snapshot by remember { derivedStateOf { scores.value[item.streamKey] } }
                val confirmedUnmatched by remember { derivedStateOf { item.streamKey in unmatched.value } }
                val art = remember(snapshot) { GameArt.of(item, snapshot) }
                // l24 polish P2: a game the score feed says has not started reads its start ("Starts 7:00 PM"), as Live
                // Now's rows show its kickoff; never "● Live".
                val scheduled = remember(snapshot) { ScoreChips.matchedEvent(item, snapshot)?.state == ScoreState.PRE }
                val kickoff = if (scheduled) art?.let { kickoffText(it) } else null
                // Home has no focus reveal: Hide until focused shows "Score hidden" on every game.
                // live.26 TV (spoiler): keyed on the Scores setting too. A setting change rebuilds this lookup, but the new
                // card is drawn in the old card's place, so a footer keyed on the score alone kept the old setting's
                // (a score under Hide) until the score next changed.
                val footer = remember(snapshot, confirmedUnmatched, kickoff, mode) {
                    // #215: listed live by its addon, not found by ESPN ([listedLiveOnly]).
                    val listed = item.isLive && !item.isChannel && SportsLiveHomeMeta.listedLiveOnly(mode, confirmedUnmatched, ScoreChips.matchedEvent(item, snapshot) != null)
                    SportsLiveHomeMeta.cardFooter(
                        ScoreChips.of(item, snapshot, mode, revealed = false), mode, strings, liveWord, listedWord.takeIf { listed },
                        halves = com.nuvio.tv.ui.screens.sportslive.panelPlaysHalves(snapshot?.league),
                        noScoreYet = noScoreYet,
                        startsAt = kickoff?.let { String.format(startsFormat, it) },
                        watching = watchingWord.takeIf { snapshot?.watched == true }
                    )
                }
                val tag = remember(snapshot) { liveNowItemTag(item, snapshot, sportWord).ifEmpty { null } }
                val ready = rememberSportsLiveReadyMark(item) == SportsLiveReadyMark.READY
                if (art != null) {
                    SportsLiveDrawnGameCard(art = art.copy(league = tag), followed = item.followed, footer = footer, modifier = Modifier.fillMaxSize(), ready = ready)
                } else {
                    SportsLiveDrawnEventCard(title = title, league = tag, followed = item.followed, footer = footer, modifier = Modifier.fillMaxSize(), ready = ready)
                }
            }
            cards[item.id] = card
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
