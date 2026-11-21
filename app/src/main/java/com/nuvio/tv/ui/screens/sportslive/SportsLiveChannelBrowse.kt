package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.SportsLiveChannelBrowse
import com.nuvio.tv.core.sportslive.SportsLiveChannelBrowseKind
import com.nuvio.tv.core.sportslive.SportsLiveChannelCountries
import com.nuvio.tv.core.sportslive.SportsLiveChannelRegions
import com.nuvio.tv.core.sportslive.SportsLiveChannelSummary
import com.nuvio.tv.core.sportslive.SportsLiveCountryReason
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveButton
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveFocus
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors
import java.util.Locale

/** What Channels' browse view and panels ask the view model for (phase 2). */
internal data class SportsLiveChannelActions(
    val onOpenBrowse: (SportsLiveChannelBrowseKind) -> Unit = {},
    val onCloseBrowse: () -> Unit = {},
    val onBrowseKind: (SportsLiveChannelBrowseKind) -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onCountries: (Set<String>?) -> Unit = {}
)

/** A country's name in words: "United States", "No country listed" for none ([SportsLiveChannelRegions.OTHER]). */
@Composable
internal fun channelCountryLabel(country: String): String =
    if (country == SportsLiveChannelRegions.OTHER) {
        stringResource(R.string.sports_live_channels_no_country)
    } else {
        channelRegionLabel(country, stringResource(R.string.sports_live_region_other))
    }

/**
 * An entry row under Channels' sections (phase 2): "See all popular · 58 ›", "Sports · 1,253 ›", "All channels · 8,231 ›".
 * A plain line in the list's focus language; OK opens the browse view.
 */
@Composable
internal fun SportsLiveChannelEntryLine(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(ENTRY_HEIGHT),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = SportsLiveTokens.Focus.LIFT),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Colors.TextSecondary,
            focusedContainerColor = SportsLiveFocus.focusedFill(Color.Transparent),
            focusedContentColor = Colors.TextPrimary,
            pressedContainerColor = SportsLiveFocus.focusedFill(Color.Transparent),
            pressedContentColor = Colors.TextPrimary
        ),
        border = ClickableSurfaceDefaults.border(focusedBorder = SportsLiveFocus.ring(shape), pressedBorder = SportsLiveFocus.ring(shape))
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
            Text(text = text, style = SportsLiveTokens.style(SportsLiveTokens.Type.Button), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The browse view's bar (phase 2): its title ("All channels") and count ("1,204 in your countries"), then **Countries:
 * United States, Canada ▾** (the countries panel), **All** / **Sports only** (✓ on the one shown) and **Find a channel**
 * (with the words typed). [firstFocus] is its first button, where Up from the list and Down from the header land.
 */
@Composable
internal fun SportsLiveChannelBrowseBar(
    browse: SportsLiveChannelBrowse,
    summary: SportsLiveChannelSummary?,
    shownCount: Int,
    firstFocus: FocusRequester,
    upFocus: FocusRequester,
    downFocus: FocusRequester?,
    onCountries: () -> Unit,
    onKind: (SportsLiveChannelBrowseKind) -> Unit,
    onFind: () -> Unit,
    modifier: Modifier = Modifier
) {
    val title = stringResource(
        when (browse.kind) {
            SportsLiveChannelBrowseKind.ALL -> R.string.sports_live_channels_browse_all
            SportsLiveChannelBrowseKind.SPORTS -> R.string.sports_live_channels_browse_sports
            SportsLiveChannelBrowseKind.POPULAR -> R.string.sports_live_channels_browse_popular
        }
    )
    val total = when (browse.kind) {
        SportsLiveChannelBrowseKind.ALL -> summary?.all ?: 0
        SportsLiveChannelBrowseKind.SPORTS -> summary?.sports ?: 0
        SportsLiveChannelBrowseKind.POPULAR -> summary?.popularTotal ?: 0
    }
    val count = when {
        browse.query.isNotBlank() -> stringResource(R.string.sports_live_channels_matching, shownCount, browse.query.trim())
        summary?.selected == null -> stringResource(R.string.sports_live_channels_count, total)
        else -> stringResource(R.string.sports_live_channels_in_your_countries, total)
    }
    val countries = summary?.selected?.let { selected ->
        // "No country listed" is named only when it is the one choice.
        val named = selected.filter { it != SportsLiveChannelRegions.OTHER }.ifEmpty { selected.toList() }
        named.map { channelCountryLabel(it) }.joinToString(", ")
    } ?: stringResource(R.string.sports_live_channels_all_countries)
    val nav = Modifier.focusProperties {
        up = upFocus
        downFocus?.let { down = it }
    }
    Column(modifier = modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(text = title, style = SportsLiveTokens.style(BROWSE_TITLE), color = Colors.TextPrimary, maxLines = 1)
            Spacer(Modifier.width(10.dp))
            Text(
                text = count,
                style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
                color = Colors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SportsLiveButton(onClick = onCountries, modifier = nav.focusRequester(firstFocus).widthIn(max = 300.dp)) {
                Text(
                    text = stringResource(R.string.sports_live_channels_countries_button, countries),
                    style = SportsLiveTokens.style(SportsLiveTokens.Type.Button),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            if (browse.kind != SportsLiveChannelBrowseKind.POPULAR) {
                KindButton(stringResource(R.string.sports_live_channels_kind_all), browse.kind == SportsLiveChannelBrowseKind.ALL, nav) {
                    onKind(SportsLiveChannelBrowseKind.ALL)
                }
                KindButton(stringResource(R.string.sports_live_channels_kind_sports), browse.kind == SportsLiveChannelBrowseKind.SPORTS, nav) {
                    onKind(SportsLiveChannelBrowseKind.SPORTS)
                }
            }
            SportsLiveButton(onClick = onFind, modifier = nav) {
                Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = browse.query.trim().ifEmpty { stringResource(R.string.sports_live_channels_find) },
                    style = SportsLiveTokens.style(SportsLiveTokens.Type.Button),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun KindButton(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    SportsLiveButton(onClick = onClick, modifier = modifier) {
        if (selected) {
            Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text = text, style = SportsLiveTokens.style(SportsLiveTokens.Type.Button), maxLines = 1)
    }
}

/**
 * The browse list's letter at its right edge (phase 2, as Android's fast scroll): the focused row's letter big, the
 * letters before and after it small. ◂ ▸ on a row jump between them. Drawn in the room the list keeps for it.
 */
@Composable
internal fun SportsLiveChannelLetterRail(letters: Triple<String?, String, String?>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.width(LETTER_RAIL_WIDTH),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(text = letters.first.orEmpty(), style = SportsLiveTokens.style(SMALL_LETTER), color = Colors.TextMuted, maxLines = 1)
        Text(text = letters.second, style = SportsLiveTokens.style(BIG_LETTER), color = Colors.TextPrimary, maxLines = 1)
        Text(text = letters.third.orEmpty(), style = SportsLiveTokens.style(SMALL_LETTER), color = Colors.TextMuted, maxLines = 1)
    }
}

/**
 * Find a channel (phase 2): the on-screen letter grid of team search ([SportsLiveLetterGrid]) in a sheet over the
 * stage, so the list beside it narrows as letters are typed. Back closes it and keeps the words.
 */
@Composable
internal fun SportsLiveFindChannelSheet(query: String, matches: Int, onQuery: (String) -> Unit, onDismiss: () -> Unit) {
    val firstKey = remember { FocusRequester() }
    SportsLiveSheet(
        title = stringResource(R.string.sports_live_channels_find),
        status = stringResource(R.string.sports_live_channels_find_matches, matches),
        statusLive = false,
        onDismiss = onDismiss,
        onBack = null
    ) { _ ->
        LaunchedEffect(Unit) {
            repeat(2) { withFrameNanos { } }
            runCatching { firstKey.requestFocus() }
        }
        SportsLiveSearchField(query = query, listening = false)
        Spacer(Modifier.height(10.dp))
        SportsLiveLetterGrid(
            firstKey = firstKey,
            downFocus = null,
            onKey = { key -> onQuery(query + key) },
            onDelete = { onQuery(query.dropLast(1)) },
            onClear = { onQuery("") }
        )
    }
}

/**
 * The countries panel (phase 2): a sheet from the right with **All countries · N**, YOUR COUNTRIES (this TV's region
 * and followed teams' leagues' countries, each with why and its count, ticked by default), MOST CHANNELS and
 * **All countries A–Z ›** (a page). OK ticks or unticks; Back saves ([onSave]: null for the viewer's own,
 * [SportsLiveChannelCountries.ALL] for every country).
 */
@Composable
internal fun SportsLiveCountriesSheet(summary: SportsLiveChannelSummary, onSave: (Set<String>?) -> Unit, onDismiss: () -> Unit) {
    val yours = summary.yours.map { it.first.country }
    // Null: every country.
    var ticked by remember { mutableStateOf(summary.selected) }
    var allPage by remember { mutableStateOf(false) }
    val total = summary.countryCounts.sumOf { it.second }
    fun toggle(country: String) {
        val now = ticked
        ticked = when {
            now == null -> setOf(country)
            country in now -> (now - country).ifEmpty { null }
            else -> now + country
        }
    }
    fun save() {
        val chosen = ticked
        onSave(
            when {
                chosen == null -> setOf(SportsLiveChannelCountries.ALL)
                chosen == yours.toSet() -> null
                else -> chosen
            }
        )
        onDismiss()
    }
    val locale = Locale.getDefault()
    SportsLiveSheet(
        title = stringResource(R.string.sports_live_channels_countries_title),
        status = stringResource(R.string.sports_live_channels_countries_hint),
        statusLive = false,
        onDismiss = ::save,
        onBack = if (allPage) ({ allPage = false }) else null,
        page = allPage
    ) { first ->
        if (allPage) {
            summary.countryCounts.filter { it.first != SportsLiveChannelRegions.OTHER }
                .map { (country, count) -> Triple(country, channelRegionLabel(country, ""), count) }
                .sortedBy { it.second.lowercase(locale) }
                .forEach { (country, label, count) ->
                    CountryEntry(label, count, null, ticked?.contains(country) == true, first()) { toggle(country) }
                }
            return@SportsLiveSheet
        }
        CountryEntry(stringResource(R.string.sports_live_channels_all_countries), total, null, ticked == null, first()) { ticked = null }
        PanelLabel(stringResource(R.string.sports_live_channels_your_countries))
        summary.yours.forEach { (country, count) ->
            val reason = when (val why = country.reason) {
                SportsLiveCountryReason.TvRegion -> stringResource(R.string.sports_live_channels_reason_tv)
                is SportsLiveCountryReason.Teams -> stringResource(R.string.sports_live_channels_reason_teams, why.names.take(2).joinToString(", "))
                SportsLiveCountryReason.NoCountry -> stringResource(R.string.sports_live_channels_reason_none)
            }
            CountryEntry(channelCountryLabel(country.country), count, reason, ticked?.contains(country.country) == true, first()) {
                toggle(country.country)
            }
        }
        val most = SportsLiveChannelCountries.most(summary.countryCounts, yours.toSet())
        if (most.isNotEmpty()) {
            PanelLabel(stringResource(R.string.sports_live_channels_most))
            most.forEach { (country, count) ->
                CountryEntry(channelCountryLabel(country), count, null, ticked?.contains(country) == true, first()) { toggle(country) }
            }
        }
        SportsLiveMenuButton(text = stringResource(R.string.sports_live_channels_all_countries_az), onClick = { allPage = true }, modifier = first())
    }
}

@Composable
private fun CountryEntry(label: String, count: Int, reason: String?, ticked: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val text = listOfNotNull(stringResource(R.string.sports_live_section_count, label, count), reason).joinToString(" · ")
    SportsLiveMenuButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        icon = if (ticked) Icons.Rounded.Check else null,
        iconTint = Colors.TextPrimary
    )
}

@Composable
private fun PanelLabel(text: String) {
    Text(
        text = text.uppercase(Locale.getDefault()),
        style = SportsLiveTokens.style(SportsLiveTokens.Type.SectionHeader),
        color = Colors.TextPrimary.copy(alpha = 0.6f),
        maxLines = 1,
        modifier = Modifier.padding(start = 10.dp, top = 10.dp, bottom = 2.dp)
    )
}

private val ENTRY_HEIGHT = 40.dp

/** The room the browse list keeps at its right edge for [SportsLiveChannelLetterRail]. */
internal val LETTER_RAIL_WIDTH = 36.dp

private val BROWSE_TITLE = TextStyle(fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
private val BIG_LETTER = TextStyle(fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold)
private val SMALL_LETTER = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
