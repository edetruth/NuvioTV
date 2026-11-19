package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.FilterChipDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.SportsLiveItem
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveFocus
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.theme.NuvioTheme

/** What OK does on a filter chip (#79): focus only highlights a chip, OK picks it. */
internal enum class SportsLiveChipOk {
    /** OK on a chip not selected: it becomes the filter, focus stays on it. */
    SELECT,

    /** OK on the selected chip: into the list below. */
    OPEN_LIST
}

/** OK on a chip ([isSelected]: it is the filter in force). Focus never selects (#79). */
internal fun sportsLiveChipOk(isSelected: Boolean): SportsLiveChipOk =
    if (isSelected) SportsLiveChipOk.OPEN_LIST else SportsLiveChipOk.SELECT

/** A chip's OK action (#79): [onSelect] on a chip not selected, [onOpenList] on the selected one. */
internal fun sportsLiveChipClick(isSelected: Boolean, onSelect: () -> Unit, onOpenList: () -> Unit): () -> Unit = {
    when (sportsLiveChipOk(isSelected)) {
        SportsLiveChipOk.SELECT -> onSelect()
        SportsLiveChipOk.OPEN_LIST -> onOpenList()
    }
}

/**
 * A chip row that is one focus stop (the player's live games panel, #68): Left and Right move the
 * [highlighted] chip only; OK makes it the [selected] one (#79).
 */
internal data class SportsLiveChipCursor<T>(val chips: List<T>, val selected: T, val highlighted: T = selected) {
    /** Left (-1) or Right (+1): the highlight moves, kept on the row; the selection stays. */
    fun move(step: Int): SportsLiveChipCursor<T> {
        if (chips.isEmpty()) return this
        val from = chips.indexOf(highlighted).takeIf { it >= 0 } ?: chips.indexOf(selected).coerceAtLeast(0)
        return copy(highlighted = chips[(from + step).coerceIn(0, chips.lastIndex)])
    }

    /** OK: the highlighted chip is selected. */
    fun ok(): SportsLiveChipCursor<T> = copy(selected = highlighted)
}

/**
 * Each filter chip's game count over [items] (#79): All (null), "★ My teams" when a followed team
 * plays, and each value [filterOf] gives (a sport, or a group on Channels). Never a score, so the
 * counts show under Hide until focused too.
 */
internal fun sportsLiveFilterCounts(
    items: List<SportsLiveItem>,
    filterOf: (SportsLiveItem) -> String?,
    myTeams: Boolean = true
): Map<String?, Int> {
    val counts = LinkedHashMap<String?, Int>()
    counts[null] = items.size
    if (myTeams) {
        val followed = items.count { it.followed }
        if (followed > 0) counts[SportsLiveUiState.MY_TEAMS_FILTER] = followed
    }
    items.forEach { item -> filterOf(item)?.let { counts[it] = (counts[it] ?: 0) + 1 } }
    return counts
}

/**
 * The filter chips shown after All (#79): "★ My teams" first, then [values] A to Z, each only with
 * games ([counts]); the [selected] chip always shows, with 0 when its games have gone, until the user
 * moves ([SportsLiveViewModel.onUserMoved]).
 */
internal fun sportsLiveShownFilters(values: Collection<String>, counts: Map<String?, Int>, selected: String?): List<String> {
    fun shown(value: String) = (counts[value] ?: 0) > 0 || value == selected
    val myTeams = SportsLiveUiState.MY_TEAMS_FILTER
    val sorted = (values.filter { it != myTeams } + listOfNotNull(selected?.takeIf { it != myTeams }))
        .distinct()
        .filter(::shown)
        .sortedWith(String.CASE_INSENSITIVE_ORDER)
    return listOfNotNull(myTeams.takeIf(::shown)) + sorted
}

/**
 * [filters] with "All sports" (#146) after "★ My teams" (first otherwise), so the options read ★ My teams, My
 * sports (All), All sports, then the sports: offered while My sports hides some of the tab's games.
 */
internal fun withAllSports(filters: List<String>): List<String> {
    val allSports = SportsLiveUiState.ALL_SPORTS_FILTER
    val rest = filters.filter { it != allSports }
    val myTeams = rest.takeWhile { it == SportsLiveUiState.MY_TEAMS_FILTER }
    return myTeams + allSports + rest.drop(myTeams.size)
}

/** The leading icon of a "My …" chip (#79): a star for followed teams, a grid for Results' My sports. */
internal enum class SportsLiveChipIcon { NONE, MY_TEAMS, MY_SPORTS }

/** A chip's icon for its filter [value] on Live now, Upcoming, Channels, Replays or the player's panel. */
internal fun sportsLiveChipIconOf(value: String?): SportsLiveChipIcon =
    if (value == SportsLiveUiState.MY_TEAMS_FILTER) SportsLiveChipIcon.MY_TEAMS else SportsLiveChipIcon.NONE

/** "Soccer 6": a chip's label with its game count; the label alone when not counted. */
internal fun sportsLiveChipText(name: String, count: Int?): String = if (count == null) name else "$name $count"

/**
 * A Sports & Live filter chip (#79), in the one focus language (#93): the selected chip has a tinted
 * fill (never white); focus adds the white ring and the lift, so a focused chip never looks picked. OK
 * calls [onClick]; focus alone does nothing. [count] follows the name ("Soccer 6"); [icon] leads it.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SportsLiveFilterChip(
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
    icon: SportsLiveChipIcon = SportsLiveChipIcon.NONE
) {
    var focused by remember { mutableStateOf(false) }
    val shape = SportsLiveTokens.Radius.PillShape
    val gold = icon == SportsLiveChipIcon.MY_TEAMS
    val fill = SportsLiveFocus.selectedFill
    val content = when {
        gold -> SportsLiveTokens.Colors.FollowedGold
        isSelected || focused -> SportsLiveTokens.Colors.TextPrimary
        else -> SportsLiveTokens.Colors.TextInactive
    }
    val ring = SportsLiveFocus.ring(shape)
    FilterChip(
        selected = isSelected,
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        scale = FilterChipDefaults.scale(focusedScale = SportsLiveTokens.Focus.LIFT, focusedSelectedScale = SportsLiveTokens.Focus.LIFT),
        colors = FilterChipDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = SportsLiveFocus.focusedFill(Color.Transparent),
            selectedContainerColor = fill,
            focusedSelectedContainerColor = SportsLiveFocus.focusedFill(fill),
            contentColor = content,
            focusedContentColor = content,
            selectedContentColor = content,
            focusedSelectedContentColor = content
        ),
        border = FilterChipDefaults.border(
            border = SportsLiveFocus.outline(shape, if (gold) SportsLiveTokens.Colors.FollowedGold.copy(alpha = 0.4f) else SportsLiveTokens.Colors.Outline),
            focusedBorder = ring,
            selectedBorder = SportsLiveFocus.outline(shape, fill),
            focusedSelectedBorder = ring
        ),
        shape = FilterChipDefaults.shape(shape = shape)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            SportsLiveChipIconView(icon, tint = content, size = 16)
            Text(
                text = sportsLiveChipText(name, count),
                style = SportsLiveTokens.style(if (isSelected) SportsLiveTokens.Type.ControlSelected else SportsLiveTokens.Type.Control),
                color = content,
                maxLines = 1
            )
        }
    }
}

/** The chip's leading icon; nothing for [SportsLiveChipIcon.NONE]. */
@Composable
internal fun SportsLiveChipIconView(icon: SportsLiveChipIcon, tint: Color, size: Int) {
    val vector = when (icon) {
        SportsLiveChipIcon.NONE -> return
        SportsLiveChipIcon.MY_TEAMS -> Icons.Rounded.Star
        SportsLiveChipIcon.MY_SPORTS -> Icons.Rounded.GridView
    }
    Icon(imageVector = vector, contentDescription = null, tint = tint, modifier = Modifier.size(size.dp))
}
