package com.nuvio.tv.ui.screens.sportslive

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.sportslive.SportsLiveLinkMark
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.ui.screens.player.NowPlayingMarker
import com.nuvio.tv.ui.screens.player.nowPlayingAccentBar
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveFocus
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens
import com.nuvio.tv.ui.screens.sportslive.theme.SportsLiveTokens.Colors

/*
 * The player's Sources panel for a game (live.20, V2): the same panel, keys and focus as for movies, with its
 * own rows. Links come in clear groups (Now playing, Links, Didn't work, From the sources), each name in words
 * (no emoji, "|" as " · "), "Played before" or why a link failed under its name, and the playing link marked.
 */

/** The groups of a game's Sources list, in list order; each has a header over its first row. */
internal enum class SportsLiveSourceGroup { NOW_PLAYING, LINKS, DIDNT_WORK, NOTICES }

/**
 * The header over each group's first row of a list of [size] rows: [currentIndex] the pinned playing link (or -1),
 * [isNotice] a source's message, [isFailed] a link that didn't work for the game. The list is already in group
 * order (playing, working and untried, failed, notices). Rows of one group after the first carry no header.
 */
internal fun sportsLiveSourceGroupHeaders(
    size: Int,
    currentIndex: Int,
    isNotice: (Int) -> Boolean,
    isFailed: (Int) -> Boolean
): Map<Int, SportsLiveSourceGroup> {
    val headers = LinkedHashMap<Int, SportsLiveSourceGroup>()
    for (index in 0 until size) {
        val group = when {
            index == currentIndex -> SportsLiveSourceGroup.NOW_PLAYING
            isNotice(index) -> SportsLiveSourceGroup.NOTICES
            isFailed(index) -> SportsLiveSourceGroup.DIDNT_WORK
            else -> SportsLiveSourceGroup.LINKS
        }
        if (group !in headers.values) headers[index] = group
    }
    return headers
}

private val PIPE = Regex("""\s*\|\s*""")
private val WHITESPACE = Regex("""\s+""")
private val TRAILING_TV = Regex("""\s*\(tv\)\s*$""", RegexOption.IGNORE_CASE)

/**
 * A source or link name in words (live.20): no emoji or pictographs (the TV font draws some as blank boxes), "|" as
 * " · ", no trailing "(tv)", one space between words. Falls back to [raw] trimmed when nothing is left.
 */
internal fun sportsLivePlainSourceName(raw: String): String {
    val text = buildString {
        var at = 0
        while (at < raw.length) {
            val point = raw.codePointAt(at)
            if (!sportsLiveIsPictograph(point)) appendCodePoint(point)
            at += Character.charCount(point)
        }
    }
    val plain = WHITESPACE.replace(PIPE.replace(text, " · "), " ")
        .replace(TRAILING_TV, "")
        .trim()
        .trim('·', '-', ' ')
    return plain.ifEmpty { raw.trim() }
}

/** A group's header: the V2 section header (caps, spaced), "Now playing" in the live text colour. */
@Composable
internal fun SportsLiveSourceGroupHeader(group: SportsLiveSourceGroup, modifier: Modifier = Modifier) {
    val text = stringResource(
        when (group) {
            SportsLiveSourceGroup.NOW_PLAYING -> R.string.sports_live_now_playing
            SportsLiveSourceGroup.LINKS -> R.string.sports_live_sources_links
            SportsLiveSourceGroup.DIDNT_WORK -> R.string.sports_live_links_didnt_work
            SportsLiveSourceGroup.NOTICES -> R.string.sports_live_sources_notices
        }
    )
    Text(
        text = text.uppercase(),
        modifier = modifier.padding(start = 4.dp, top = if (group == SportsLiveSourceGroup.NOW_PLAYING) 0.dp else 8.dp, bottom = 6.dp),
        style = SportsLiveTokens.style(SportsLiveTokens.Type.SectionHeader),
        color = when (group) {
            SportsLiveSourceGroup.NOW_PLAYING -> Colors.TextPrimary
            SportsLiveSourceGroup.DIDNT_WORK -> Colors.WarningAmber
            else -> Colors.TextMuted
        },
        maxLines = 1
    )
}

/**
 * One link of a game in the Sources panel (V2): its name, its details on one line, what it did for the game
 * ("Played before", or why it failed, in amber), and its source's clean name at the end. The playing link has the
 * equalizer and the leading bar. A failed link is dimmed except while focused. Focus: the white ring, the 1.03 lift
 * and the lighter fill. The same keys as the movie row: OK plays it, ▲ on the first row goes to the chips ([onUpKey]).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SportsLiveSourceRow(
    stream: Stream,
    focusRequester: FocusRequester?,
    requestInitialFocus: Boolean,
    isCurrentStream: Boolean,
    nowPlayingAnimates: Boolean,
    linkMark: SportsLiveLinkMark?,
    linkMarkNowMs: Long,
    onClick: () -> Unit,
    onFocusChanged: ((Boolean) -> Unit)?,
    onUpKey: (() -> Unit)?
) {
    var isFocused by remember { mutableStateOf(false) }
    val dimmed = linkMark is SportsLiveLinkMark.Failed && !isFocused
    val unknown = stringResource(R.string.stream_unknown)
    val name = remember(stream, unknown) {
        stream.getDisplayNameOrNull()?.let(::sportsLivePlainSourceName)?.takeIf { it.isNotBlank() } ?: unknown
    }
    val details = remember(stream, name) {
        stream.getDisplayDescription()
            ?.let(::sportsLivePlainSourceName)
            ?.takeIf { it.isNotBlank() && it != name }
    }
    val source = remember(stream.addonName) { sportsLivePlainSourceName(stream.addonName) }
    val shape = SportsLiveTokens.Radius.RowShape
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null && requestInitialFocus) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                isFocused = it.isFocused
                onFocusChanged?.invoke(it.isFocused)
            }
            .then(
                if (onUpKey != null) {
                    Modifier.onKeyEvent { event ->
                        if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN && event.key == Key.DirectionUp) {
                            onUpKey()
                            true
                        } else {
                            false
                        }
                    }
                } else {
                    Modifier
                }
            ),
        colors = CardDefaults.colors(containerColor = Colors.Row, focusedContainerColor = Colors.RowFocused),
        shape = CardDefaults.shape(shape = shape),
        border = CardDefaults.border(
            border = Border(border = BorderStroke(1.dp, Color.Transparent), shape = shape),
            focusedBorder = SportsLiveFocus.ring(shape)
        ),
        scale = CardDefaults.scale(focusedScale = SportsLiveTokens.Focus.LIFT)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (isCurrentStream) Modifier.nowPlayingAccentBar(Colors.FocusWhite) else Modifier)
                .then(if (dimmed) Modifier.alpha(SPORTS_LIVE_FAILED_LINK_ALPHA) else Modifier)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isCurrentStream) NowPlayingMarker(animate = nowPlayingAnimates)
                    Text(
                        text = name,
                        style = SportsLiveTokens.style(SportsLiveTokens.Type.RowName).sportsLiveBold(),
                        color = Colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                details?.let {
                    Text(
                        text = it,
                        style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
                        color = Colors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                linkMark?.let { mark ->
                    Text(
                        text = sportsLiveLinkMarkText(mark, linkMarkNowMs),
                        style = SportsLiveTokens.style(SportsLiveTokens.Type.Meta),
                        color = if (mark is SportsLiveLinkMark.Failed) Colors.WarningAmber else Colors.ReplayGreen,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                text = source,
                style = SportsLiveTokens.style(SportsLiveTokens.Type.Tag),
                color = Colors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp)
            )
        }
    }
}
