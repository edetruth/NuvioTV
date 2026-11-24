package com.nuvio.tv.ui.screens.sportslive

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.SportsLiveReplayPositions
import com.nuvio.tv.core.sportslive.SportsLiveReplayResume
import com.nuvio.tv.core.sportslive.scores.CatchUpItem
import com.nuvio.tv.core.sportslive.scores.CatchUpRepository
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.ScoreTeam
import com.nuvio.tv.core.sportslive.scores.SpoilerProtectionStore
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.home.SportsLiveHomeMeta
import com.nuvio.tv.ui.screens.home.SportsLiveHomeRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The Catch up row (#58) for Home and Live Now. */
@HiltViewModel
class SportsLiveCatchUpViewModel @Inject constructor(
    private val repository: CatchUpRepository,
    private val spoilers: SpoilerProtectionStore,
    private val replayPositions: SportsLiveReplayPositions
) : ViewModel() {
    /** The row (Home) and strip (Live Now); empty while Sports & Live, its Home row or the row is off. */
    val items: StateFlow<List<CatchUpItem>> = repository.items

    /** The row's days are still loading (P14): Home holds its place and title meanwhile. */
    val loading: StateFlow<Boolean> = repository.loading

    /** The screen is on screen again (each resume). */
    fun onOpened() = repository.onOpened()

    fun markWatched(item: CatchUpItem) = repository.markWatched(item)

    /**
     * The replays the user kept (research 7, worded as everywhere: "Watch later (no spoilers)"), by ESPN event id. Keep is Watch later (#151): it
     * stays until its replay is watched or for 7 days ([com.nuvio.tv.core.sportslive.scores.ReplayLifetime] rule 4), with
     * its score hidden meanwhile, and it shows under Replays' Saved for later.
     */
    val kept: StateFlow<Set<String>> = spoilers.protection
        .map { protection -> protection.saved.map { it.eventId }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Keep [item] until it is watched, or let it go again ([kept]: it is kept now). */
    fun toggleKeep(item: CatchUpItem, kept: Boolean) {
        viewModelScope.launch { if (kept) spoilers.reveal(item.event) else spoilers.saveForLater(item.event) }
    }

    /** Where each replay can resume (coordinator 2026-11-20), by ESPN event id: only places worth offering. */
    val resume: StateFlow<Map<String, Long>> = replayPositions.positions
        .map { all -> all.mapNotNull { (event, position) -> SportsLiveReplayResume.resumeAt(position)?.let { event to it } }.toMap() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * OK on [item]'s card, just before its replay plays: the player starts it where it was left (what the card said), and
     * saves its place as it plays. [startOver] (More… → Start over) starts from the beginning and forgets the place.
     */
    fun prepareReplay(item: CatchUpItem, startOver: Boolean = false) {
        if (startOver) replayPositions.forget(item.key)
        SportsLiveReplayResume.register(item.replays.map { it.id }, item.key, if (startOver) null else resume.value[item.key])
    }
}

/** A card's "Resume from 1:12:30" ([short]: "Resume 1:12:30", the slim strip card); null when there is nothing to resume. */
@Composable
internal fun replayResumeText(resumeMs: Long?, short: Boolean = false): String? = resumeMs?.let {
    stringResource(if (short) R.string.sports_live_replay_resume_short else R.string.sports_live_replay_resume_from, SportsLiveReplayResume.clock(it))
}

/** The catch-up card's title outside composition (the player's, the source picker's): ESPN's "‹away› at ‹home›". */
internal fun catchUpTitle(context: Context, item: CatchUpItem): String =
    context.getString(R.string.sports_live_results_menu_title, item.event.away.catchUpName, item.event.home.catchUpName)

/** The Game page for a catch-up item: its replay, the score behind Reveal whatever the Scores setting. */
internal fun catchUpGameRequest(item: CatchUpItem): SportsLiveGameRequest =
    SportsLiveGameRequest(event = item.event, replay = item.replay, revealed = false, spoilerSafe = true)

/** A side's name as Results lists it: ESPN's full name first. */
internal val ScoreTeam.catchUpName: String get() = displayName ?: shortDisplayName ?: name ?: abbreviation.orEmpty()

/**
 * The Catch up card's text: "‹away› at ‹home›" (ESPN's names, never the replay's title, which may carry
 * a score), as Results orders them.
 */
@Composable
internal fun catchUpMatchup(item: CatchUpItem): String =
    stringResource(R.string.sports_live_results_menu_title, item.event.away.catchUpName, item.event.home.catchUpName)

/**
 * Hold OK on a catch-up card (Home or Live Now), in the one More… sheet every Sports & Live card uses (Q8: never a
 * second, centered menu): "‹away› at ‹home›" over "Replay · Sun 15 Nov", then Game stats (score behind Reveal), Watch
 * later (no spoilers) / Reveal result ([kept], research 7: it keeps the replay until watched) and Mark as watched, which reads "Remove from Your teams" on
 * Live Now's strip ([fromYourTeams], live.20 1c).
 */
@Composable
internal fun SportsLiveCatchUpMenu(
    item: CatchUpItem,
    onOpenGame: () -> Unit,
    onMarkWatched: () -> Unit,
    onDismiss: () -> Unit,
    fromYourTeams: Boolean = false,
    kept: Boolean = false,
    /** Watch later / Reveal result; null hides it. */
    onToggleKeep: (() -> Unit)? = null,
    /** "Start over": the replay has a place to resume from (OK resumes there); null hides it. */
    onStartOver: (() -> Unit)? = null
) {
    SportsLiveSheet(
        title = catchUpMatchup(item),
        status = catchUpReplayWhen(item, System.currentTimeMillis()),
        statusLive = false,
        onDismiss = onDismiss,
        onBack = null
    ) { first ->
        if (onStartOver != null) {
            SportsLiveMenuButton(text = stringResource(R.string.sports_live_replay_start_over), onClick = onStartOver, modifier = first())
        }
        SportsLiveMenuButton(text = stringResource(R.string.sports_live_game_stats), onClick = onOpenGame, modifier = first())
        if (onToggleKeep != null) {
            SportsLiveMenuButton(
                text = stringResource(if (kept) R.string.sports_live_reveal_result_menu else R.string.sports_live_watch_later),
                onClick = onToggleKeep,
                modifier = first()
            )
        }
        SportsLiveMenuButton(
            text = stringResource(if (fromYourTeams) R.string.sports_live_your_teams_remove else R.string.sports_live_catch_up_mark_watched),
            onClick = onMarkWatched,
            modifier = first()
        )
    }
}

/** The synthetic Home row of the catch-up items, marked by its own base URL; cards carry no score. */
object SportsLiveCatchUpHomeRow {
    const val BASE_URL = "nuvio://sports-live/catch-up"
    const val CATALOG_ID = "catch_up"
    const val RAW_TYPE = "sport"
    const val ADDON_NAME = "Sports & Live"

    /** The held place's key while the row loads (P14). */
    const val PLACEHOLDER_KEY = "sportslive_catch_up_pending"
    private const val ID_PREFIX = "catchup:"

    fun isCatchUpRow(addonBaseUrl: String): Boolean = addonBaseUrl == BASE_URL

    /** The row: Sports & Live's addon id (See all opens the section), its own base URL and [cards]. */
    fun build(items: List<CatchUpItem>, title: String, names: (CatchUpItem) -> String, replayLabel: String): CatalogRow = CatalogRow(
        addonId = SportsLiveHomeRow.ADDON_ID,
        addonName = ADDON_NAME,
        addonBaseUrl = BASE_URL,
        catalogId = CATALOG_ID,
        catalogName = title,
        type = ContentType.UNKNOWN,
        rawType = "sport",
        items = cards(items, names, replayLabel),
        isLoading = false,
        hasMore = false,
        supportsSkip = false
    )

    /** A card's id for [item]; [eventIdOf] reads it back. */
    fun cardId(item: CatchUpItem): String = ID_PREFIX + item.key

    fun eventIdOf(cardId: String): String? = cardId.takeIf { it.startsWith(ID_PREFIX) }?.removePrefix(ID_PREFIX)

    /**
     * A card per item: [names] ("‹away› at ‹home›", ESPN's names), [replayLabel] in the label line, and no
     * image at all: Home draws [GameArt.ofEvent] in its place ([rememberCatchUpCardArt]). Nothing from the replay
     * (its title, description or artwork may carry a score).
     */
    fun cards(items: List<CatchUpItem>, names: (CatchUpItem) -> String, replayLabel: String): List<MetaPreview> =
        items.map { item ->
            MetaPreview(
                id = cardId(item),
                type = ContentType.UNKNOWN,
                rawType = "sport",
                name = names(item),
                poster = null,
                posterShape = PosterShape.LANDSCAPE,
                background = null,
                logo = null,
                description = null,
                releaseInfo = replayLabel,
                imdbRating = null,
                genres = listOfNotNull(item.event.leagueName)
            )
        }
}

/** Shown while a catch-up replay's sources are looked up; Back cancels. Named by ESPN's matchup only. */
@Composable
internal fun SportsLiveReplayResolvingDialog(title: String, onDismiss: () -> Unit) {
    NuvioDialog(onDismiss = onDismiss, title = stringResource(R.string.sports_live_zap_switching, title)) {
        SportsLiveSkeletonRows(count = 3)
    }
}

/**
 * A catch-up replay offered by several sources: one button per source, named by the source (addon) only,
 * never a stream's or replay's title (they may carry the score). OK plays that source's best stream.
 */
@Composable
internal fun SportsLiveReplaySourceDialog(title: String, sources: List<String>, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val firstFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstFocusRequester.requestFocus() }
    }
    NuvioDialog(onDismiss = onDismiss, title = title, subtitle = stringResource(R.string.sports_live_catch_up_pick_source)) {
        sources.forEachIndexed { index, source ->
            SportsLiveMenuButton(
                text = source,
                onClick = { onPick(index) },
                modifier = if (index == 0) Modifier.focusRequester(firstFocusRequester) else Modifier
            )
        }
    }
}

/**
 * Home's drawn art for its Catch up cards, by card id ([LocalDrawnCardArt]): the section's game card (V2 phase 10)
 * with the logos and names, "Final · Sun 15 Nov" and the Replay pill; never a score.
 */
@Composable
internal fun rememberCatchUpCardArt(items: List<CatchUpItem>, resume: Map<String, Long> = emptyMap()): (String) -> (@Composable () -> Unit)? {
    val finalWord = stringResource(R.string.sports_live_chip_final_note)
    val resumeFormat = stringResource(R.string.sports_live_replay_resume_from)
    return remember(items, finalWord, resume, resumeFormat) {
        val cards = items.associate { item ->
            val art = GameArt.ofEvent(item.event, records = false) // spoiler-free: records move with the result
            // A replay left part-way says where OK picks it up (coordinator 2026-11-20).
            val lead = resume[item.key]?.let { resumeFormat.format(SportsLiveReplayResume.clock(it)) }
                ?: SportsLiveHomeMeta.catchUpLead(item.event.startMs, finalWord)
            val footer = DrawnCardFooter(lead = lead, trailing = DrawnCardTrailing.Replay)
            val card: @Composable () -> Unit = { SportsLiveDrawnGameCard(art, followed = false, footer = footer, modifier = Modifier.fillMaxSize()) }
            SportsLiveCatchUpHomeRow.cardId(item) to card
        }
        val lookup: (String) -> (@Composable () -> Unit)? = { id -> cards[id] }
        lookup
    }
}
