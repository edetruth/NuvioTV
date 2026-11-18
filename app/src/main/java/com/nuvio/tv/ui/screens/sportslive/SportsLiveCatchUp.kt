package com.nuvio.tv.ui.screens.sportslive

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.scores.CatchUpItem
import com.nuvio.tv.core.sportslive.scores.CatchUpRepository
import com.nuvio.tv.core.sportslive.scores.GameArt
import com.nuvio.tv.core.sportslive.scores.ScoreTeam
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.components.LightweightCard
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.home.SportsLiveHomeRow
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** The Catch up row (#58) for Home and Live Now. */
@HiltViewModel
class SportsLiveCatchUpViewModel @Inject constructor(private val repository: CatchUpRepository) : ViewModel() {
    /** The row (Home) and strip (Live Now); empty while Sports & Live, its Home row or the row is off. */
    val items: StateFlow<List<CatchUpItem>> = repository.items

    /** The screen is on screen again (each resume). */
    fun onOpened() = repository.onOpened()

    fun markWatched(item: CatchUpItem) = repository.markWatched(item)
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
 * The Catch up strip at the top of Live Now: "Catch up · your teams", then a card per game with the
 * league, both teams and the Replay pill. Never a score, a winner or a final note, whatever the Scores
 * setting. OK plays; hold OK opens [SportsLiveCatchUpMenu].
 */
@Composable
internal fun SportsLiveCatchUpStrip(
    items: List<CatchUpItem>,
    firstFocusRequester: FocusRequester,
    upFocusRequester: FocusRequester?,
    downFocusRequester: FocusRequester?,
    onPlay: (CatchUpItem) -> Unit,
    onOpenGame: (CatchUpItem) -> Unit,
    onMarkWatched: (CatchUpItem) -> Unit,
    /** Whether a card of the strip holds focus (its menu open counts), for when the strip empties. */
    onHoldsFocus: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var menuKey by remember { mutableStateOf<String?>(null) }
    // The card focus was last on, while focus stays in the strip or its menu.
    var focusedKey by remember { mutableStateOf<String?>(null) }
    val currentOnHoldsFocus by rememberUpdatedState(onHoldsFocus)
    LaunchedEffect(focusedKey != null) { currentOnHoldsFocus(focusedKey != null) }
    // The focused card left (watched, or marked so): focus goes to the first card left.
    val keys = items.map { it.key }
    LaunchedEffect(keys) {
        val key = focusedKey ?: return@LaunchedEffect
        if (key in keys || keys.isEmpty() || menuKey != null) return@LaunchedEffect
        withFrameNanos { }
        runCatching { firstFocusRequester.requestFocus() }
    }
    items.firstOrNull { it.key == menuKey }?.let { item ->
        SportsLiveCatchUpMenu(
            item = item,
            onOpenGame = {
                menuKey = null
                onOpenGame(item)
            },
            onMarkWatched = {
                menuKey = null
                onMarkWatched(item)
            },
            onDismiss = { menuKey = null }
        )
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
        Text(
            text = stringResource(R.string.sports_live_catch_up_title),
            style = MaterialTheme.typography.titleSmall,
            color = NuvioTheme.colors.TextSecondary
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
            // Focus leaving for the menu keeps the card; leaving for anything else forgets it.
            modifier = Modifier.onFocusChanged { if (!it.hasFocus && menuKey == null && focusedKey?.let { key -> key in keys } == true) focusedKey = null }
        ) {
            itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                CatchUpCard(
                    item = item,
                    onClick = { onPlay(item) },
                    onLongClick = { menuKey = item.key },
                    menuOpen = menuKey == item.key,
                    modifier = Modifier
                        .then(if (index == 0) Modifier.focusRequester(firstFocusRequester) else Modifier)
                        .onFocusChanged { if (it.isFocused) focusedKey = item.key }
                        .focusProperties {
                            upFocusRequester?.let { up = it }
                            downFocusRequester?.let { down = it }
                        }
                )
            }
        }
    }
}

@Composable
private fun CatchUpCard(
    item: CatchUpItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    menuOpen: Boolean,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(NuvioTheme.radii.md)
    val (longPress, click) = rememberSportsLiveLongPress(menuOpen = menuOpen, onLongClick = onLongClick, onClick = onClick)
    LightweightCard(
        onClick = click,
        modifier = modifier.width(CATCH_UP_CARD_WIDTH).then(longPress),
        shape = shape,
        containerColor = NuvioTheme.colors.BackgroundCard,
        focusedBorder = Border(border = NuvioTheme.focusRing.border(NuvioTheme.spacing.xxs), shape = shape)
    ) {
        // The drawn art (logos when both teams have one, else the names; the league), never the replay's poster.
        val art = remember(item.event) { GameArt.ofEvent(item.event) }
        SportsLiveGameArt(art, compact = true, modifier = Modifier.fillMaxWidth().height(CATCH_UP_ART_HEIGHT))
        Row(
            modifier = Modifier.padding(horizontal = NuvioTheme.spacing.md, vertical = NuvioTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = catchUpMatchup(item),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            ReplayPill()
        }
    }
}

/** Hold OK on a catch-up card (Home or Live Now): Game stats (score behind Reveal) and Mark as watched. */
@Composable
internal fun SportsLiveCatchUpMenu(
    item: CatchUpItem,
    onOpenGame: () -> Unit,
    onMarkWatched: () -> Unit,
    onDismiss: () -> Unit
) {
    val firstFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocusRequester.requestFocus() } }
    NuvioDialog(onDismiss = onDismiss, title = catchUpMatchup(item)) {
        SportsLiveMenuButton(
            text = stringResource(R.string.sports_live_game_stats),
            onClick = onOpenGame,
            modifier = Modifier.focusRequester(firstFocusRequester)
        )
        SportsLiveMenuButton(text = stringResource(R.string.sports_live_catch_up_mark_watched), onClick = onMarkWatched)
    }
}

/** The synthetic Home row of the catch-up items, marked by its own base URL; cards carry no score. */
object SportsLiveCatchUpHomeRow {
    const val BASE_URL = "nuvio://sports-live/catch-up"
    const val CATALOG_ID = "catch_up"
    private const val ID_PREFIX = "catchup:"

    fun isCatchUpRow(addonBaseUrl: String): Boolean = addonBaseUrl == BASE_URL

    /** The row: Sports & Live's addon id (See all opens the section), its own base URL and [cards]. */
    fun build(items: List<CatchUpItem>, title: String, names: (CatchUpItem) -> String, replayLabel: String): CatalogRow = CatalogRow(
        addonId = SportsLiveHomeRow.ADDON_ID,
        addonName = "Sports & Live",
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
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { LoadingIndicator() }
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

/** Home's drawn art for its Catch up cards, by card id ([LocalDrawnCardArt]): logos and names, never a score. */
@Composable
internal fun rememberCatchUpCardArt(items: List<CatchUpItem>): (String) -> (@Composable () -> Unit)? =
    remember(items) {
        val arts = items.associate { SportsLiveCatchUpHomeRow.cardId(it) to GameArt.ofEvent(it.event) }
        val lookup: (String) -> (@Composable () -> Unit)? = { id ->
            arts[id]?.let { art -> { SportsLiveGameArt(art, compact = false, modifier = Modifier.fillMaxSize()) } }
        }
        lookup
    }

private val CATCH_UP_CARD_WIDTH = 240.dp
private val CATCH_UP_ART_HEIGHT = 64.dp
