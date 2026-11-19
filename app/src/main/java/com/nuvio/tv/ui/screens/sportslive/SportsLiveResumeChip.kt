package com.nuvio.tv.ui.screens.sportslive

import com.nuvio.tv.core.sportslive.GameStatus
import com.nuvio.tv.core.sportslive.scores.ScoreSnapshot
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.screens.sportslive.mini.SportsLiveResumeOffer

/** Live now's Resume chip (#148), after Back from a paused game. */
internal sealed interface SportsLiveResumeChip {
    val item: SportsLiveItem

    /** Still live: "▶ Resume <game> · Live". */
    data class Live(override val item: SportsLiveItem, val stream: Stream) : SportsLiveResumeChip

    /** Ended meanwhile ([status]: ESPN's final): "<game> · Final 2–3", spoiler setting applied. */
    data class Final(override val item: SportsLiveItem, val status: GameStatus, val event: ScoreSnapshot) : SportsLiveResumeChip
}

/** What OK on the chip does. */
internal sealed interface SportsLiveResumeOk {
    /** The same game and link again, at the live edge: a fresh player, never the paused position. */
    data class RejoinLiveEdge(val item: SportsLiveItem, val stream: Stream) : SportsLiveResumeOk

    /** The game is over: its Game page. */
    data class OpenGame(val item: SportsLiveItem, val event: ScoreSnapshot) : SportsLiveResumeOk
}

/**
 * The chip for [offer] at [nowMs], or null: none offered, expired ([SportsLiveResumeOffer.expiredAt]), or the
 * game neither listed in Live now ([listed], its current card) nor ended. [statusOf] is the screen's one
 * status source, so the chip never says Live where the Scoreboard says Final.
 */
internal fun sportsLiveResumeChip(
    offer: SportsLiveResumeOffer?,
    listed: SportsLiveItem?,
    statusOf: (SportsLiveItem) -> GameStatus,
    nowMs: Long
): SportsLiveResumeChip? {
    if (offer == null || offer.expiredAt(nowMs)) return null
    val item = listed ?: offer.item
    val status = statusOf(item)
    val event = when (status) {
        is GameStatus.Final -> status.event
        is GameStatus.JustEnded -> status.ended.event?.takeUnless { status.estimate }
        else -> null
    }
    return when {
        event != null -> SportsLiveResumeChip.Final(item, status, event)
        // Gone from Live now without a final (its addon dropped it): nothing left to rejoin.
        listed == null -> null
        else -> SportsLiveResumeChip.Live(item, offer.stream)
    }
}

internal fun sportsLiveResumeOk(chip: SportsLiveResumeChip): SportsLiveResumeOk = when (chip) {
    is SportsLiveResumeChip.Live -> SportsLiveResumeOk.RejoinLiveEdge(chip.item, chip.stream)
    is SportsLiveResumeChip.Final -> SportsLiveResumeOk.OpenGame(chip.item, chip.event)
}
