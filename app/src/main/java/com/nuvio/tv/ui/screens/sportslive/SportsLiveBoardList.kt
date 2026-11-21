package com.nuvio.tv.ui.screens.sportslive

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.SportsLiveChannelBrowseKind
import com.nuvio.tv.core.sportslive.SportsLiveChannelList
import com.nuvio.tv.core.sportslive.SportsLiveChannelSummary
import com.nuvio.tv.core.sportslive.SportsLiveMoreLive
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.util.dpadRepeatThrottle

/**
 * What Live now's and Channels' list shows (#115): built once per board, tab and filter, never per focus move.
 */
@Immutable
internal data class SportsLiveListModel(
    /** The rows in the order shown. */
    val items: List<SportsLiveItem>,
    /** Each row's Scoreboard row by its list key. */
    val rowOf: Map<String, ScoreboardRow>,
    /** Channels: section headings (and the Favorites hint); otherwise the Scoreboard's group headings. */
    val channelsTab: Boolean,
    val groupCounts: Map<ScoreboardGroup, Int>,
    val sectionCounts: Map<String, Int>,
    /** Channels phase 1: the tab's own view with no favorite listed leads with the Favorites hint. */
    val favoritesHint: Boolean,
    val otherRegionLabel: String,
    /** Channels phase 2: the entry rows under the tab's sections (opening the browse view); null for none. */
    val channelEntries: SportsLiveChannelSummary?,
    /** #167: the games My sports hides, the list's last line (0: none). */
    val hiddenByMySports: Int,
    /** Channels phase 2: each browse row's letter (◂ ▸ jump between them); null elsewhere. */
    val channelLetters: List<String>?,
    /** Live now's More live row (user 2026-11-21): (group key, count) as the row names them; empty: no row. */
    val moreLive: List<Pair<String, Int>> = emptyList(),
    /** The More live view is shown: [items] are its games, headed by sport ([ScoreboardRow.section]). */
    val moreLiveOpen: Boolean = false,
    /** The add-sport card's sport name ("Cricket"), shown above the first row; null for no card. */
    val addSportLabel: String? = null
)

/**
 * Each row's marks (#143), by list key: the row the stage shows while focus is on the stage (its white bar), the row
 * whose menu is open, the mini player's game, and the rows with a bell, a Replay pill or "▶ Ready". A focus move in
 * the list changes none of them, so the list's rows are left alone.
 */
@Immutable
internal data class SportsLiveRowMarks(
    val stageKey: String? = null,
    val menuKey: String? = null,
    val watchingKey: String? = null,
    val reminded: Set<String> = emptySet(),
    val replays: Set<String> = emptySet(),
    val ready: Set<String> = emptySet()
)

/**
 * The list's actions (#115): one holder whose identity never changes, its callbacks set by the screen as it composes.
 * The rows' lambdas only call through it, so they never change with the state the actions read (a score, the clock).
 */
@Stable
internal class SportsLiveListActions {
    /** One requester per card, keyed like the list (two addons can list the same meta id). */
    val requesters: MutableMap<String, FocusRequester> = mutableMapOf()

    fun requesterOf(item: SportsLiveItem): FocusRequester = requesters.getOrPut(item.listKey) { FocusRequester() }

    var onClick: (SportsLiveItem) -> Unit = {}
    var onLongClick: (SportsLiveItem) -> Unit = {}
    var onFocus: (SportsLiveItem) -> Unit = {}

    /** The list took or lost focus. */
    var onListFocus: (Boolean) -> Unit = {}

    /** Channels phase 2: ◂ ▸ on a browse row: focus to the row at this index, scrolled in. */
    var onJump: (Int) -> Unit = {}
    var onOpenBrowse: (SportsLiveChannelBrowseKind) -> Unit = {}

    /** #167: OK on the "hidden by My sports" line. */
    var onShowAllSports: () -> Unit = {}

    /** OK on Live now's "More live" row. */
    var onOpenMoreLive: () -> Unit = {}

    /** The add-sport card's Add and Not now, and its Add button's requester (Up from the first row lands there). */
    var onAddSport: () -> Unit = {}
    var onDeclineSport: () -> Unit = {}
    val addSportRequester = FocusRequester()
}

/**
 * Live now's Scoreboard and Channels' list (V2, #132; Channels P4 and phases 1–2), with the letter rail of Channels'
 * browse view. Its own composable (#115, #143) with narrow inputs, so the section's focus moves, score polls and
 * stage changes recompose only the rows whose own model or marks changed.
 */
@Composable
internal fun SportsLiveBoardList(
    model: SportsLiveListModel,
    marks: SportsLiveRowMarks,
    actions: SportsLiveListActions,
    listState: LazyListState,
    /** Where focus comes back into the list when the row it left is gone. */
    restoreFocusRequester: FocusRequester,
    /** Up from the first row. */
    upFocusRequester: FocusRequester,
    /** Right from any row: the stage. */
    stageFocusRequester: FocusRequester,
    /** Channels phase 2: the letters round the focused row while the list holds focus; null: no rail. */
    railLetters: Triple<String?, String, String?>?,
    modifier: Modifier = Modifier
) {
    // Channels phase 2: the browse list keeps room at its right edge for its letter.
    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .sportsLiveFadingEdges(listState, LIST_FADE)
                .onFocusChanged { actions.onListFocus(it.hasFocus) }
                .focusRestorer(restoreFocusRequester)
                .dpadRepeatThrottle(),
            contentPadding = PaddingValues(
                start = LIST_FOCUS_ROOM,
                end = LIST_FOCUS_ROOM + if (model.channelLetters != null) LETTER_RAIL_WIDTH else 0.dp,
                top = LIST_FOCUS_ROOM,
                bottom = LIST_FOCUS_ROOM
            ),
            verticalArrangement = Arrangement.spacedBy(SportsLiveTokens.Size.RowGap)
        ) {
            val items = model.items
            val rowOf = model.rowOf
            // Read here, not through [model] in each row's key handler: a score poll builds a new model, and the handler
            // (part of the row's modifier) must stay equal so an unchanged row skips (#143).
            val letters = model.channelLetters
            // The add-sport card (after a More live game): above the first row, never first focus.
            val addSportLabel = model.addSportLabel
            if (addSportLabel != null) {
                item(key = ADD_SPORT_KEY, contentType = ENTRY_CONTENT) {
                    SportsLiveAddSportCard(
                        sportLabel = addSportLabel,
                        onAdd = { actions.onAddSport() },
                        onNotNow = { actions.onDeclineSport() },
                        addRequester = actions.addSportRequester,
                        up = upFocusRequester,
                        right = stageFocusRequester
                    )
                }
            }
            itemsIndexed(items, key = { _, item -> item.listKey }, contentType = { _, _ -> ROW_CONTENT }) { index, item ->
                val boardRow = rowOf[item.listKey] ?: return@itemsIndexed
                // The group's heading rides on its first row, so list indices stay the rows' own.
                Column {
                    if (model.moreLiveOpen) {
                        // More live: one heading per sport, with its count.
                        val section = boardRow.section
                        if (section != null && (index == 0 || rowOf[items[index - 1].listKey]?.section != section)) {
                            SportsLiveSectionHeader(moreLiveGroupLabel(section), model.sectionCounts[section] ?: 0)
                        }
                    } else if (model.channelsTab) {
                        val section = boardRow.section
                        if (index == 0 && model.favoritesHint) SportsLiveFavoritesHint()
                        // Phase 2: every section is headed (Favorites, Recent, Popular now, a letter); no region headings.
                        if (section != null && (index == 0 || rowOf[items[index - 1].listKey]?.section != section)) {
                            SportsLiveSectionHeader(channelSectionLabel(section, model.otherRegionLabel), model.sectionCounts[section] ?: 0)
                        }
                    } else if (index == 0 || rowOf[items[index - 1].listKey]?.group != boardRow.group) {
                        SportsLiveScoreboardHeader(boardRow.group, model.groupCounts[boardRow.group] ?: 0)
                    }
                    val key = item.listKey
                    SportsLiveScoreboardRow(
                        row = boardRow,
                        // Focus in the stage: the selected row keeps a white left bar (not while it shows a strip card).
                        selected = key == marks.stageKey,
                        onClick = { actions.onClick(item) },
                        onLongClick = { actions.onLongClick(item) },
                        onFocus = { actions.onFocus(item) },
                        menuOpen = key == marks.menuKey,
                        reminded = key in marks.reminded,
                        hasReplay = item.streamKey in marks.replays,
                        watching = key == marks.watchingKey,
                        ready = key in marks.ready,
                        modifier = Modifier
                            .focusRequester(actions.requesterOf(item))
                            .focusProperties {
                                right = stageFocusRequester
                                if (index == 0) up = if (addSportLabel != null) actions.addSportRequester else upFocusRequester
                            }
                            .onPreviewKeyEvent { event ->
                                // Channels phase 2: ◂ ▸ on a browse row jump to the previous or next letter.
                                val shownLetters = letters ?: return@onPreviewKeyEvent false
                                val native = event.nativeKeyEvent
                                val forward = when (native.keyCode) {
                                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> true
                                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> false
                                    else -> return@onPreviewKeyEvent false
                                }
                                if (native.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent true
                                val target = SportsLiveChannelList.jump(shownLetters, index, forward) ?: return@onPreviewKeyEvent true
                                actions.onJump(target)
                                true
                            }
                    )
                }
            }
            // Channels phase 2: the entry rows under the tab's sections open the browse view.
            val summary = model.channelEntries
            if (summary != null) {
                if (summary.popularTotal > summary.popularShown) {
                    item(key = CHANNEL_POPULAR_KEY, contentType = ENTRY_CONTENT) {
                        SportsLiveChannelEntryLine(
                            text = stringResource(R.string.sports_live_channels_see_all_popular, summary.popularTotal),
                            onClick = { actions.onOpenBrowse(SportsLiveChannelBrowseKind.POPULAR) },
                            modifier = Modifier.focusProperties { right = stageFocusRequester }
                        )
                    }
                }
                item(key = CHANNEL_SPORTS_KEY, contentType = ENTRY_CONTENT) {
                    SportsLiveChannelEntryLine(
                        text = stringResource(R.string.sports_live_channels_sports_entry, summary.sports),
                        onClick = { actions.onOpenBrowse(SportsLiveChannelBrowseKind.SPORTS) },
                        modifier = Modifier.focusProperties { right = stageFocusRequester }
                    )
                }
                item(key = CHANNEL_ALL_KEY, contentType = ENTRY_CONTENT) {
                    SportsLiveChannelEntryLine(
                        text = stringResource(R.string.sports_live_channels_all_entry, summary.all),
                        onClick = { actions.onOpenBrowse(SportsLiveChannelBrowseKind.ALL) },
                        modifier = Modifier.focusProperties { right = stageFocusRequester }
                    )
                }
            }
            // More live (user 2026-11-21): the list's last row, the live games My sports leaves out, by sport.
            if (model.moreLive.isNotEmpty() && !model.moreLiveOpen) {
                item(key = MORE_LIVE_KEY, contentType = ENTRY_CONTENT) {
                    SportsLiveChannelEntryLine(
                        text = moreLiveRowText(model.moreLive),
                        onClick = { actions.onOpenMoreLive() },
                        modifier = Modifier.focusProperties { right = stageFocusRequester }
                    )
                }
            }
            // #167: under My sports, the games it hides are one press away (OK: All sports).
            if (model.hiddenByMySports > 0) {
                item(key = HIDDEN_BY_MY_SPORTS_KEY, contentType = HIDDEN_CONTENT) {
                    SportsLiveHiddenByMySportsLine(
                        count = model.hiddenByMySports,
                        onClick = { actions.onShowAllSports() },
                        height = SportsLiveTokens.Size.PanelLine + 8.dp,
                        modifier = Modifier.focusProperties { right = stageFocusRequester }
                    )
                }
            }
        }
        if (railLetters != null) {
            SportsLiveChannelLetterRail(railLetters, Modifier.align(Alignment.CenterEnd))
        }
    }
}

/** Channels phase 2: the entry rows' list keys (never a channel's `addonId|id`). */
private const val CHANNEL_POPULAR_KEY = "\u0000channels:popular"
private const val CHANNEL_SPORTS_KEY = "\u0000channels:sports"
private const val CHANNEL_ALL_KEY = "\u0000channels:all"

/** The add-sport card's list key. */
private const val ADD_SPORT_KEY = "live:add_sport"

/** Live now's More live row's list key. */
private const val MORE_LIVE_KEY = "live:more_live"

/** A More live group's name: its sport, "Fights" or "Other". */
@Composable
internal fun moreLiveGroupLabel(key: String): String = when (key) {
    SportsLiveMoreLive.FIGHTS -> stringResource(R.string.sports_live_more_live_fights)
    SportsLiveMoreLive.OTHER -> stringResource(R.string.sports_live_more_live_other)
    else -> SportsLiveMoreLive.kindOf(key)?.let { sportKindLabel(it) } ?: stringResource(R.string.sports_live_more_live_other)
}

/** "More live: 4 Racing · 3 Cricket · 2 Fights · 2 Other ›". */
@Composable
internal fun moreLiveRowText(summary: List<Pair<String, Int>>): String {
    val parts = summary.map { (key, count) -> stringResource(R.string.sports_live_more_live_count, count, moreLiveGroupLabel(key)) }
    return stringResource(R.string.sports_live_more_live_row, parts.joinToString(" · "))
}

/** The list's content types (#143): game rows, entry lines and the hidden-games line are reused only as their own kind. */
private const val ROW_CONTENT = "row"
private const val ENTRY_CONTENT = "entry"
private const val HIDDEN_CONTENT = "hidden"
